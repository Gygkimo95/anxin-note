package com.calmnote.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class Reminders {

    static final String CHANNEL_ID = "med-reminder-quiet";
    /** 0.5.0 之前那个会响会震的渠道，只留着为了删掉它。 */
    private static final String LOUD_CHANNEL_ID = "med-reminder";
    static final String EXTRA_SLOT = "slot";

    private static final int ALARM_BASE = 40000;
    private static final int NOTIFY_BASE = 20000;
    private static final int TEST_CODE = ALARM_BASE + 2000;
    private static final String PREFS = "reminder-state";
    private static final String KEY_SCHEDULED = "scheduled-slots";
    private static final String KEY_FIRE_AT = "last-fire-at";
    private static final String KEY_FIRE_SLOT = "last-fire-slot";
    private static final String KEY_TEST_DUE = "test-due";
    private static final String KEY_TEST_FIRE = "test-fire";
    private static final String KEY_ARMED_AT = "armed-at";

    private Reminders() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int slotKey(int hour, int minute) {
        return hour * 60 + minute;
    }

    private static PendingIntent alarmIntent(Context context, String slot, int slotKey) {
        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.setAction(ReminderReceiver.ACTION_FIRE);
        intent.putExtra(EXTRA_SLOT, slot);
        // data 必须带上 slot，否则不同顿的 PendingIntent 会被当成同一个而互相覆盖。
        intent.setData(android.net.Uri.parse("anxin://slot/" + slot));
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, ALARM_BASE + slotKey, intent, flags);
    }

    /** 今天要吃的所有时间点，去重后升序。一种药一天吃几次就贡献几个。 */
    static List<String> activeSlots(Context context) {
        Set<String> times = new HashSet<>();
        for (Store.Item item : Store.itemsOn(Store.meds(context), Store.todayKey())) {
            String at = item.at();
            if (!at.isEmpty()) times.add(at);
        }
        List<String> sorted = new ArrayList<>(times);
        Collections.sort(sorted);
        return sorted;
    }

    static long nextTriggerAt(String slot) {
        int[] hm = Store.parseHm(slot);
        if (hm == null) return 0;
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, hm[0]);
        next.set(Calendar.MINUTE, hm[1]);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (next.getTimeInMillis() <= System.currentTimeMillis()) {
            next.add(Calendar.DAY_OF_YEAR, 1);
        }
        return next.getTimeInMillis();
    }

    /** 把之前排过的全撤掉，再按当前的药重排。药改了、时间改了、关掉提醒都走这里。 */
    static void rescheduleAll(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;

        for (String old : previouslyScheduled(context)) {
            int[] hm = Store.parseHm(old);
            if (hm == null) continue;
            alarms.cancel(alarmIntent(context, old, slotKey(hm[0], hm[1])));
        }

        List<String> slots = Store.remindEnabled(context)
            ? activeSlots(context)
            : new ArrayList<String>();

        for (String slot : slots) {
            int[] hm = Store.parseHm(slot);
            if (hm == null) continue;
            schedule(alarms, context, slot, slotKey(hm[0], hm[1]), nextTriggerAt(slot));
        }

        // 记下「从什么时候开始有提醒在排」。界面要靠它判断某一顿是不是该到却没到：
        // 提醒打开之前就过去的那些顿，本来就没排过闹钟，不能算没送到。
        SharedPreferences.Editor edit = prefs(context).edit()
            .putString(KEY_SCHEDULED, android.text.TextUtils.join(",", slots));
        if (slots.isEmpty()) {
            edit.putLong(KEY_ARMED_AT, 0);
        } else if (prefs(context).getLong(KEY_ARMED_AT, 0) == 0) {
            edit.putLong(KEY_ARMED_AT, System.currentTimeMillis());
        }
        edit.apply();
    }

    /** 提醒是什么时候开始排上的；0 表示现在没在排。 */
    static long armedAt(Context context) {
        return prefs(context).getLong(KEY_ARMED_AT, 0);
    }

    private static List<String> previouslyScheduled(Context context) {
        String raw = prefs(context).getString(KEY_SCHEDULED, "");
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return result;
        for (String part : raw.split(",")) {
            if (!part.trim().isEmpty()) result.add(part.trim());
        }
        return result;
    }

    private static void schedule(
        AlarmManager alarms, Context context, String slot, int key, long at
    ) {
        setAlarm(alarms, alarmIntent(context, slot, key), at);
    }

    private static void setAlarm(AlarmManager alarms, PendingIntent pending, long at) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
            } else {
                alarms.setExact(AlarmManager.RTC_WAKEUP, at, pending);
            }
        } catch (SecurityException e) {
            // 系统没给精确闹钟权限时退回不精确，宁可晚几分钟也别不响。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
            } else {
                alarms.set(AlarmManager.RTC_WAKEUP, at, pending);
            }
        }
    }

    // ---- 排查用：闹钟到底有没有响过 ----
    //
    // 国产 ROM 上「通知不自动弹」几乎都不是通知本身的问题，是闹钟压根没走到我们这儿：
    // 进程被清后台杀了、没给自启动、开机后没排上。这些在代码里查不出来，
    // 只能把每次真响过的时间记下来，让用户自己对一眼。

    /** 闹钟真的递到了就记一笔。这是唯一能证明「响过」的证据。 */
    static void recordFire(Context context, String slot) {
        prefs(context).edit()
            .putLong(KEY_FIRE_AT, System.currentTimeMillis())
            .putString(KEY_FIRE_SLOT, slot == null ? "" : slot)
            .apply();
    }

    static long lastFireAt(Context context) {
        return prefs(context).getLong(KEY_FIRE_AT, 0);
    }

    static String lastFireSlot(Context context) {
        return prefs(context).getString(KEY_FIRE_SLOT, "");
    }

    /** 排查用的那条：几分钟后响一次，可以划掉 App、锁屏再等它。 */
    static String scheduleTest(Context context, int minutes) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return "系统闹钟服务拿不到";
        ensureChannel(context);

        long at = System.currentTimeMillis() + minutes * 60_000L;
        setAlarm(alarms, testIntent(context), at);
        prefs(context).edit().putLong(KEY_TEST_DUE, at).putLong(KEY_TEST_FIRE, 0).apply();
        return "";
    }

    static long testDueAt(Context context) {
        return prefs(context).getLong(KEY_TEST_DUE, 0);
    }

    static long testFiredAt(Context context) {
        return prefs(context).getLong(KEY_TEST_FIRE, 0);
    }

    static void notifyTest(Context context) {
        prefs(context).edit().putLong(KEY_TEST_FIRE, System.currentTimeMillis()).apply();
        ensureChannel(context);
        NotificationManager manager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(context, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(context);
        }

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        builder.setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("排查用的这条来了")
            .setContentText("说明到点能弹出来，真的提醒也会来。")
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(context, NOTIFY_BASE + 999, open, flags)
            );
        silence(builder);

        try {
            manager.notify(NOTIFY_BASE + 999, builder.build());
        } catch (Exception ignored) {
            // 排查用的，发不出去就算了，界面上照样能看到「响过没响」。
        }
    }

    private static PendingIntent testIntent(Context context) {
        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.setAction(ReminderReceiver.ACTION_TEST);
        intent.setData(android.net.Uri.parse("anxin://test"));
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, TEST_CODE, intent, flags);
    }

    /** 界面上要显示「下次提醒」，取所有顿里最早的那次。 */
    static long soonestTrigger(Context context) {
        if (!Store.remindEnabled(context)) return 0;
        long soonest = 0;
        for (String slot : activeSlots(context)) {
            long at = nextTriggerAt(slot);
            if (at > 0 && (soonest == 0 || at < soonest)) soonest = at;
        }
        return soonest;
    }

    /**
     * 要的是「弹一条提示」，不是闹钟。
     *
     * IMPORTANCE_HIGH 留着，因为它决定的是「横幅弹不弹出来」——这条得看得见，
     * 沉在通知栏里就跟没提一样。响不响是另一回事，由 sound / vibration 决定，
     * 两个都关掉：给焦虑的人做的东西不该突然响一声。
     *
     * 渠道建完之后属性就改不动了（只有用户能在系统设置里改），所以换了新的
     * 渠道 id，并把旧那个会响的删掉，不然老用户升级上来还是响。
     */
    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        if (manager.getNotificationChannel(LOUD_CHANNEL_ID) != null) {
            manager.deleteNotificationChannel(LOUD_CHANNEL_ID);
        }
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return;

        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, "服药提醒", NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("到时间了弹一条，可以直接在上面点「吃了」。不响。");
        channel.setShowBadge(false);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.enableLights(false);
        manager.createNotificationChannel(channel);
    }

    /** 这一顿里还没记的。全都记过了返回空，就不用再响。 */
    private static List<Store.Item> pendingItems(Context context, String slot) {
        String today = Store.todayKey();
        JSONObject root = Store.readObject(context);
        JSONArray meds = root.optJSONArray("meds");
        if (meds == null) return new ArrayList<>();
        JSONObject doses = root.optJSONObject("doses");
        JSONObject day = doses == null ? null : doses.optJSONObject(today);

        List<Store.Item> result = new ArrayList<>();
        for (Store.Item item : Store.itemsOn(meds, today)) {
            if (!item.at().equals(slot)) continue;
            if (Store.hasDose(day, item.medId(), item.timeId())) continue;
            result.add(item);
        }
        return result;
    }

    static String notifySlot(Context context, String slot, boolean force) {
        ensureChannel(context);
        NotificationManager manager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return "系统通知服务拿不到";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !manager.areNotificationsEnabled()) {
            return "通知权限没开，系统把它拦住了";
        }

        List<Store.Item> pending = pendingItems(context, slot);
        if (pending.isEmpty() && !force) return "这一顿已经记过了，不用再提";

        StringBuilder names = new StringBuilder();
        for (Store.Item item : pending) {
            String name = item.med.optString("name", "").trim();
            if (name.isEmpty()) continue;
            if (names.length() > 0) names.append("、");
            names.append(name);
            String dose = item.med.optString("dose", "").trim();
            if (!dose.isEmpty() && pending.size() == 1) names.append(' ').append(dose);
        }
        String text = names.length() > 0 ? "到时间了 · " + names : "到时间了";

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        int[] hm = Store.parseHm(slot);
        int key = hm == null ? 0 : slotKey(hm[0], hm[1]);

        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending =
            PendingIntent.getActivity(context, NOTIFY_BASE + key, open, flags);

        Intent taken = new Intent(context, DoseActionReceiver.class);
        taken.setAction(DoseActionReceiver.ACTION_TAKEN);
        taken.putExtra(EXTRA_SLOT, slot);
        taken.setData(android.net.Uri.parse("anxin://taken/" + slot));
        PendingIntent takenPending =
            PendingIntent.getBroadcast(context, ALARM_BASE + 1000 + key, taken, flags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(context, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(context);
        }
        builder.setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(pending.size() > 1 ? slot + " 这一顿" : "安心手记")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .addAction(takenAction(takenPending, pending.size() > 1));
        // Android 7 上没有渠道，静音得在这条通知上说。
        silence(builder);

        try {
            manager.notify(NOTIFY_BASE + key, builder.build());
        } catch (Exception e) {
            return "发通知时出错：" + e.getClass().getSimpleName();
        }
        return "";
    }

    /** 渠道是 O 才有的，更老的系统上声音和震动挂在通知本身。 */
    @SuppressWarnings("deprecation")
    private static void silence(Notification.Builder builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return;
        builder.setDefaults(0).setSound(null).setVibrate(null);
    }

    @SuppressWarnings("deprecation")
    private static Notification.Action takenAction(PendingIntent pending, boolean plural) {
        String label = plural ? "都吃了" : "吃了";
        return new Notification.Action.Builder(R.drawable.ic_notify, label, pending).build();
    }

    static void cancelNotification(Context context, String slot) {
        NotificationManager manager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        int[] hm = Store.parseHm(slot);
        manager.cancel(NOTIFY_BASE + (hm == null ? 0 : slotKey(hm[0], hm[1])));
    }
}
