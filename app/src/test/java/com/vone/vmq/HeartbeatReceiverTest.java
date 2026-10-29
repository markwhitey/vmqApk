package com.vone.vmq;

import android.app.AlarmManager;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class HeartbeatReceiverTest {
    private Context context;
    private AlarmManager alarms;
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        ShadowAlarmManager.setCanScheduleExactAlarms(true);
        Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners",
                new ComponentName(context, NeNotificationService2.class).flattenToString());
    }
    @Test public void repeatedStartsReplaceOneWakeupAlarm() {
        HeartbeatReceiver.start(context); HeartbeatReceiver.start(context);
        assertEquals(1, shadowOf(alarms).getScheduledAlarms().size());
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, shadowOf(alarms).peekNextScheduledAlarm().type);
    }
    @Test public void cancelRemovesScheduledWakeup() {
        HeartbeatReceiver.start(context); HeartbeatReceiver.cancel(context);
        assertTrue(shadowOf(alarms).getScheduledAlarms().isEmpty());
    }
    @Test public void disablingNotificationAccessStopsHeartbeat() {
        HeartbeatReceiver.start(context);
        Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners", "");
        HeartbeatReceiver.start(context);
        assertTrue(shadowOf(alarms).getScheduledAlarms().isEmpty());
    }
    @Test public void testBuildPausesWhenOriginalMonitorIsEnabled() {
        String own = new ComponentName(context, NeNotificationService2.class).flattenToString();
        Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners",
                own + ":com.vone.qrcode/com.vone.vmq.NeNotificationService2");
        assertTrue(HeartbeatReceiver.oldMonitorEnabled(context));
        HeartbeatReceiver.start(context);
        assertTrue(shadowOf(alarms).getScheduledAlarms().isEmpty());
    }
    @Test public void reportsExactAlarmPermissionFailure() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false);
        assertFalse(HeartbeatReceiver.schedule(context, 60_000));
        assertEquals(0, context.getSharedPreferences("heartbeat_status", Context.MODE_PRIVATE).getLong("last_success", 0));
        assertFalse(context.getSharedPreferences("heartbeat_status", Context.MODE_PRIVATE).getString("message", "").isEmpty());
    }
}
