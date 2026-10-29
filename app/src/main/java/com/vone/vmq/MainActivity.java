package com.vone.vmq;

import android.Manifest;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import com.google.zxing.activity.CaptureActivity;
import com.vone.qrcode.BuildConfig;
import com.vone.qrcode.R;
import com.vone.vmq.util.Constant;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView hostView, keyView, statusView;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { refreshStatus(); handler.postDelayed(this, 5_000); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        hostView = findViewById(R.id.txt_host);
        keyView = findViewById(R.id.txt_key);
        statusView = findViewById(R.id.txt_heartbeat_status);
        if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 103);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (HeartbeatReceiver.mayRun(this)) {
            NotificationListenerService.requestRebind(new ComponentName(this, NeNotificationService2.class));
            HeartbeatReceiver.start(this);
        }
        handler.removeCallbacks(refresh); refresh.run();
    }
    @Override protected void onPause() {
        handler.removeCallbacks(refresh); super.onPause();
    }

    private void refreshStatus() {
        SharedPreferences config = getSharedPreferences("vone", MODE_PRIVATE);
        hostView.setText("通知地址：" + config.getString("host", "请扫码配置"));
        keyView.setText(config.getString("key", "").isEmpty() ? "通讯密钥：未配置" : "通讯密钥：已保存（隐藏）");
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        AlarmManager alarm = (AlarmManager) getSystemService(ALARM_SERVICE);
        boolean exempt = power != null && power.isIgnoringBatteryOptimizations(getPackageName());
        boolean exact = alarm != null && (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms());
        SharedPreferences status = getSharedPreferences("heartbeat_status", MODE_PRIVATE);
        long last = status.getLong("last_success", 0);
        String message = "版本：" + BuildConfig.VERSION_NAME + "\n"
                + "通知监听：" + (NeNotificationService2.listenerConnected ? "已连接" : "未连接") + "\n"
                + "电池白名单：" + (exempt ? "已允许" : "未允许，请设置")
                + "；精确定时：" + (exact ? "已允许" : "未允许，请设置") + "\n"
                + "省电心跳：约60秒一次；请求期间限时持锁，不主动亮屏\n"
                + "最后成功：" + (last == 0 ? "尚未确认" : DateFormat.getDateTimeInstance().format(new Date(last)))
                + "\n" + status.getString("message", "请配置后检测心跳");
        if (HeartbeatReceiver.oldMonitorEnabled(this)) {
            message = "测试版已暂停：请先在通知使用权中关闭原版 V免签，再启用本测试版，避免两个监听端同时上报。\n" + message;
        } else if (!exempt) {
            message += "\n尚未获得电池优化豁免，熄屏下的60秒心跳不能保证。";
        }
        statusView.setText(message);
    }

    public void doPowerSettings(View ignored) {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null && !power.isIgnoringBatteryOptimizations(getPackageName())) {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } else if (Build.VERSION.SDK_INT >= 31 && !((AlarmManager)getSystemService(ALARM_SERVICE)).canScheduleExactAlarms()) {
            startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName())));
        } else { toast("电池白名单和精确定时已允许；请保留小米自启动/后台运行权限"); }
    }

    public void doListenerSettings(View ignored) {
        startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }

    public void startQrCode(View ignored) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, Constant.REQ_PERM_CAMERA);
            return;
        }
        // Camera scanning does not require storage permission on Android 13+.
        startActivityForResult(new Intent(this, CaptureActivity.class), Constant.REQ_QR_CODE);
    }

    public void doInput(View ignored) {
        EditText input = new EditText(this);
        input.setHint("域名/通讯密钥");
        new AlertDialog.Builder(this).setTitle("输入 VMQFox 配置")
                .setView(input).setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> saveConfig(input.getText().toString())).show();
    }

    private void saveConfig(String raw) {
        int slash = raw.lastIndexOf('/');
        if (slash <= 0 || slash == raw.length() - 1) { toast("配置格式应为：域名/密钥"); return; }
        String host = raw.substring(0, slash).trim(), key = raw.substring(slash + 1).trim();
        try { MonitorProtocol.origin(host); }
        catch (IllegalArgumentException e) { toast(e.getMessage()); return; }
        if (key.isEmpty()) { toast("密钥不能为空"); return; }
        getSharedPreferences("vone", MODE_PRIVATE).edit().putString("host", host).putString("key", key).apply();
        HeartbeatReceiver.start(this);
        toast("配置已保存，请检测心跳验证连接"); refreshStatus();
    }

    public void doStart(View ignored) {
        if (HeartbeatReceiver.oldMonitorEnabled(this)) { toast("请先关闭原版的通知监听，再测试"); return; }
        if (!HeartbeatReceiver.listenerEnabled(this)) { doListenerSettings(null); return; }
        if (!NeNotificationService2.listenerConnected) {
            NotificationListenerService.requestRebind(new ComponentName(this, NeNotificationService2.class));
            toast("正在连接通知监听，请稍后再试"); return;
        }
        SharedPreferences prefs = getSharedPreferences("vone", MODE_PRIVATE);
        String host = prefs.getString("host", ""), key = prefs.getString("key", "");
        if (host.isEmpty() || key.isEmpty()) { toast("请先配置服务地址和密钥"); return; }
        final Request request;
        try { request = MonitorProtocol.heartbeat(host, key, Long.toString(System.currentTimeMillis())); }
        catch (IllegalArgumentException e) { toast(e.getMessage()); return; }
        MonitorNetwork.CLIENT.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                HeartbeatReceiver.record(MainActivity.this, false, "手动检测：网络连接失败");
                toast("心跳未确认，请查看网络和服务地址");
            }
            @Override public void onResponse(Call call, Response response) {
                try {
                    MonitorNetwork.Result result = MonitorNetwork.read(response);
                    HeartbeatReceiver.record(MainActivity.this, result.success, result.summary());
                    toast(result.success ? "心跳已被服务器确认" : "心跳未确认：" + result.summary());
                } catch (IOException e) { toast("心跳响应读取失败"); }
            }
        });
        HeartbeatReceiver.start(this);
    }

    public void checkPush(View ignored) {
        if (!HeartbeatReceiver.mayRun(this)) { doListenerSettings(null); return; }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        manager.createNotificationChannel(new NotificationChannel("monitor_test", "监听测试", NotificationManager.IMPORTANCE_DEFAULT));
        manager.notify(1102, new Notification.Builder(this, "monitor_test")
                .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("V免签测试推送")
                .setContentText("这是一条测试推送信息，如果程序正常，则会提示监听权限正常").build());
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == Constant.REQ_QR_CODE && result == RESULT_OK && data != null && data.getExtras() != null) {
            String raw = data.getExtras().getString(Constant.INTENT_EXTRA_KEY_QR_SCAN);
            if (raw != null) saveConfig(raw);
        }
    }
    @Override public void onRequestPermissionsResult(int request, @NonNull String[] permissions, @NonNull int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == Constant.REQ_PERM_CAMERA && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) startQrCode(null);
    }
    private void toast(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
    }
}
