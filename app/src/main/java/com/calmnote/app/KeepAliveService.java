package com.calmnote.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.util.Calendar;

/**
 * 常驻前台服务，把进程钉在内存里。
 *
 * 为什么需要它：有些 ROM（vivo、部分小米）连 setAlarmClock 都会在清后台时一起掐掉。
 * 闹钟活不过强杀，这一点代码里解决不了——除非进程别死。前台服务是安卓唯一
 * 允许长期存活的方式，代价是通知栏里一直挂一条。
 *
 * 只要提醒已开启且有有效时间，它就自动运行。用户既然开启了提醒，就不该再知道
 * “保活”这种实现细节，更不该因为漏开一个排查开关而收不到提醒。
 *
 * 那条常驻通知只写下次提醒时间，不出现药名或剂量。
 */
public class KeepAliveService extends Service {

    static final String CHANNEL_ID = "keep-alive";
    private static final int NOTIFY_ID = 30001;

    static boolean enabled(Context context) {
        return Store.remindEnabled(context) && !Reminders.activeSlots(context).isEmpty();
    }

    /** 提醒状态或药物时间变化后同步服务，不让可靠性依赖一个额外开关。 */
    static void sync(Context context) {
        if (enabled(context)) {
            start(context);
        } else {
            context.stopService(new Intent(context, KeepAliveService.class));
        }
    }

    /** 启动或刷新常驻通知。调用前由 sync 判断当前是否确实需要运行。 */
    private static void start(Context context) {
        Intent intent = new Intent(context, KeepAliveService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception ignored) {
            // 后台启动前台服务在 Android 12+ 有限制，拉不起来就算了，
            // 闹钟那条路还在，不能因为这个把提醒整个搞崩。
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIFY_ID, build());
        } catch (Exception ignored) {
            // Android 14 上类型不对或受限会抛，别让它把进程带崩。
        }
        // 被系统杀掉之后要自己回来，这正是它存在的意义。
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification build() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("安心手记")
            .setContentText(nextLine())
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(PendingIntent.getActivity(this, NOTIFY_ID, open, flags));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // 锁屏上不显示常驻内容，进一步减少健康信息暴露。
            builder.setVisibility(Notification.VISIBILITY_SECRET);
        }
        return builder.build();
    }

    /** 「下次提醒 21:00」。不在通知栏暴露药名或剂量。 */
    private String nextLine() {
        long at = Reminders.soonestTrigger(this);
        if (at <= 0) return "在帮你记着";

        Calendar when = Calendar.getInstance();
        when.setTimeInMillis(at);
        String slot = pad(when.get(Calendar.HOUR_OF_DAY)) + ":" + pad(when.get(Calendar.MINUTE));

        Calendar now = Calendar.getInstance();
        boolean today = when.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
            && when.get(Calendar.YEAR) == now.get(Calendar.YEAR);

        return "下次提醒 " + (today ? "" : "明天 ") + slot;
    }

    private static String pad(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    /**
     * 单独一个渠道，IMPORTANCE_MIN：不响、不震、折叠在通知栏最下面，
     * 尽量不占用户的注意力。它的作用是活着，不是被看见。
     */
    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, "一直开着（保证提醒不被系统掐掉）", NotificationManager.IMPORTANCE_MIN
        );
        channel.setDescription("常驻的一条，用来防止系统把提醒清掉。不响。");
        channel.setShowBadge(false);
        channel.setSound(null, null);
        channel.enableVibration(false);
        manager.createNotificationChannel(channel);
    }
}
