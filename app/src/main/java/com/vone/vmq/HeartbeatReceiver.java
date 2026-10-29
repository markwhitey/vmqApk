package com.vone.vmq;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

/** One wake-up per minute; the CPU lock exists only while a bounded request is in flight. */
public final class HeartbeatReceiver extends BroadcastReceiver {
    static final String ACTION = "com.vone.vmq.HEARTBEAT";
    static final long INTERVAL_MS = 60_000;
    static final long LOCK_TIMEOUT_MS = 25_000;
    private static final String TAG = "VmqHeartbeat";
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();

    static boolean listenerEnabled(Context context) {
        String raw = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        if (raw == null) return false;
        ComponentName own = new ComponentName(context, NeNotificationService2.class);
        for (String name : raw.split(":")) {
            if (own.equals(ComponentName.unflattenFromString(name))) return true;
        }
        return false;
    }

    static boolean oldMonitorEnabled(Context context) {
        if (context.getPackageName().equals("com.vone.qrcode")) return false;
        String raw = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        return raw != null && raw.contains("com.vone.qrcode/");
    }

    static boolean mayRun(Context context) {
        return listenerEnabled(context) && !oldMonitorEnabled(context);
    }

    static PendingIntent operation(Context context) {
        return PendingIntent.getBroadcast(context, 6001,
                new Intent(context, HeartbeatReceiver.class).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void cancel(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) alarms.cancel(operation(context));
    }

    static void start(Context context) {
        if (!mayRun(context)) { cancel(context); return; }
        schedule(context, SystemClock.elapsedRealtime() + 1_000);
    }

    static boolean schedule(Context context, long when) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return false;
        if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) {
            record(context, false, "请允许精确定时，并将本应用加入电池优化白名单");
            return false;
        }
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, operation(context));
            return true;
        } catch (SecurityException e) {
            record(context, false, "系统拒绝精确定时，请检查电池优化和闹钟权限");
            return false;
        }
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        if (!mayRun(context)) { cancel(context); return; }
        // Schedule independently of network success, so a failed request never stops the timer.
        schedule(context, SystemClock.elapsedRealtime() + INTERVAL_MS);
        if (!NeNotificationService2.listenerConnected) {
            android.service.notification.NotificationListenerService.requestRebind(
                    new ComponentName(context, NeNotificationService2.class));
            record(context, false, "监听服务未连接，已请求重连；不发送虚假在线心跳");
            return;
        }
        if (!IN_FLIGHT.compareAndSet(false, true)) return;
        SharedPreferences prefs = context.getSharedPreferences("vone", Context.MODE_PRIVATE);
        String host = prefs.getString("host", ""), key = prefs.getString("key", "");
        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(key)) {
            IN_FLIGHT.set(false); record(context, false, "请先配置服务地址和密钥"); return;
        }
        PendingResult pending = goAsync();
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock lock = power == null ? null : power.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "vmq:heartbeat");
        try {
            if (lock != null) lock.acquire(LOCK_TIMEOUT_MS);
            Log.i(TAG, "心跳唤醒，间隔=60s，屏幕无需点亮");
            Request request = MonitorProtocol.heartbeat(host, key, Long.toString(System.currentTimeMillis()));
            MonitorNetwork.CLIENT.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    try { record(context, false, "心跳网络失败（下一周期继续）"); }
                    finally { finish(lock, pending); }
                }
                @Override public void onResponse(Call call, Response response) {
                    try {
                        MonitorNetwork.Result result = MonitorNetwork.read(response);
                        record(context, result.success, result.summary());
                    } catch (IOException e) { record(context, false, "心跳响应读取失败"); }
                    finally { finish(lock, pending); }
                }
            });
        } catch (RuntimeException e) {
            record(context, false, "心跳启动失败，请检查配置/权限"); finish(lock, pending);
        }
    }

    private static void finish(PowerManager.WakeLock lock, PendingResult pending) {
        try { if (lock != null && lock.isHeld()) lock.release(); }
        finally { IN_FLIGHT.set(false); pending.finish(); }
    }

    static void record(Context context, boolean success, String message) {
        Log.i(TAG, (success ? "心跳已确认: " : "心跳未确认: ") + message);
        SharedPreferences.Editor editor = context.getSharedPreferences("heartbeat_status", Context.MODE_PRIVATE)
                .edit().putLong("last_attempt", System.currentTimeMillis()).putString("message", message);
        if (success) editor.putLong("last_success", System.currentTimeMillis());
        editor.apply();
    }
}
