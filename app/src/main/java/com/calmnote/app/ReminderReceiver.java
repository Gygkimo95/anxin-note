package com.calmnote.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ReminderReceiver extends BroadcastReceiver {

    static final String ACTION_FIRE = "com.calmnote.app.REMINDER_FIRE";
    static final String ACTION_TEST = "com.calmnote.app.REMINDER_TEST";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_TEST.equals(action)) {
            Reminders.recordFire(context, "排查");
            Reminders.notifyTest(context);
            return;
        }

        if (ACTION_FIRE.equals(action)) {
            String slot = intent.getStringExtra(Reminders.EXTRA_SLOT);
            if (slot != null && !slot.isEmpty()) {
                // 先记下「闹钟确实响到了」，再决定要不要弹通知。
                // 顺序很重要：这一顿可能已经吃过了不弹，但闹钟是响了的。
                Reminders.recordFire(context, slot);
                // 这一顿已经自己记过了，notifySlot 内部会跳过，不多响一声。
                Reminders.notifySlot(context, slot, false);
            }
            Reminders.rescheduleAll(context);
            return;
        }

        // 重启、对时、换时区、应用更新后闹钟都会丢，需要重新排。
        Reminders.rescheduleAll(context);
        // 常驻服务开着的话，开机也要把它拉回来，否则重启之后这道保险就没了。
        KeepAliveService.start(context);
    }
}
