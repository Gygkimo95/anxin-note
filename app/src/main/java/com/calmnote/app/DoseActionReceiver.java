package com.calmnote.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/** 通知上点「吃了」：这时候 app 大多没开着，只有原生侧能把这条落下去。 */
public class DoseActionReceiver extends BroadcastReceiver {

    static final String ACTION_TAKEN = "com.calmnote.app.DOSE_TAKEN";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_TAKEN.equals(intent.getAction())) return;

        String slot = intent.getStringExtra(Reminders.EXTRA_SLOT);
        if (slot == null || slot.isEmpty()) return;

        String today = Store.todayKey();
        int marked = 0;
        for (Store.Item item : Store.itemsOn(Store.meds(context), today)) {
            if (!item.at().equals(slot)) continue;
            if (Store.markDose(context, today, item.medId(), item.timeId(), "notification")) {
                marked++;
            }
        }

        Reminders.cancelNotification(context, slot);
        if (marked > 0) Backups.backup(context, false);
        Toast.makeText(
            context,
            marked > 1 ? "记下了 " + marked + " 种" : "记下了",
            Toast.LENGTH_SHORT
        ).show();
    }
}
