package com.calmnote.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 全部数据只存在应用私有目录的 data.json，不联网、不备份到云。
 * 原生侧持有它，是因为通知上点「吃了」时 WebView 通常没在运行。
 *
 * v3 结构：
 *   meds:  [{ id, name, dose, note, since, until,
 *             times: [{ id, at, since, until }] }]
 *   remind: bool
 *   doses: { "2026-09-18": { "m1": { "t1": { at, source } } } }
 *
 * 一种药一天吃好几次，所以「顿」的最小单位是 (药, 时间点) 这一对，
 * 服药记录也必须按这一对来存。
 */
final class Store {

    private static final String FILE_NAME = "data.json";
    private static final Object LOCK = new Object();

    private Store() {
    }

    static String todayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    static String nowStamp() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date());
    }

    /** "8:0"、"08:00:00" 之类都能吃下；解析不出来返回 null，绝不让格式问题静默取消闹钟。 */
    static int[] parseHm(String time) {
        if (time == null) return null;
        String[] parts = time.trim().split(":");
        if (parts.length < 2) return null;
        try {
            int hour = Integer.parseInt(parts[0].trim());
            int minute = Integer.parseInt(parts[1].trim());
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
            return new int[]{hour, minute};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String formatHm(int hour, int minute) {
        return (hour < 10 ? "0" : "") + hour + ":" + (minute < 10 ? "0" : "") + minute;
    }

    static String read(Context context) {
        synchronized (LOCK) {
            File file = new File(context.getFilesDir(), FILE_NAME);
            if (!file.exists()) return "";
            try (InputStream in = new FileInputStream(file)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            } catch (IOException e) {
                return "";
            }
        }
    }

    static boolean write(Context context, String json) {
        synchronized (LOCK) {
            File file = new File(context.getFilesDir(), FILE_NAME);
            File temp = new File(context.getFilesDir(), FILE_NAME + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } catch (IOException e) {
                return false;
            }
            return temp.renameTo(file);
        }
    }

    /** 读出来的一定是最新结构；碰到旧版本就地迁移并写回。 */
    static JSONObject readObject(Context context) {
        String raw = read(context);
        JSONObject root;
        if (raw.isEmpty()) {
            root = new JSONObject();
        } else {
            try {
                root = new JSONObject(raw);
            } catch (Exception e) {
                root = new JSONObject();
            }
        }
        if (migrate(root)) write(context, root.toString());
        return root;
    }

    /** 返回 true 表示结构变过，需要写回。 */
    static boolean migrate(JSONObject root) {
        int version = root.optInt("version", 1);
        boolean changed = false;
        try {
            if (version < 2 || !root.has("meds")) {
                toV2(root);
                changed = true;
            }
            if (root.optInt("version", 1) < 3) {
                toV3(root);
                changed = true;
            }
        } catch (Exception e) {
            // 迁移中途出错就别写回，宁可用旧结构跑着，也不能落一份半成品。
            return false;
        }
        return changed;
    }

    /** v1：单个 med 对象 + doses[日]={at, source}。 */
    private static void toV2(JSONObject root) throws Exception {
        JSONObject legacy = root.optJSONObject("med");
        JSONArray meds = root.optJSONArray("meds");
        if (meds == null) meds = new JSONArray();

        if (legacy != null && meds.length() == 0) {
            JSONObject med = new JSONObject();
            med.put("id", "m1");
            med.put("name", legacy.optString("name", ""));
            med.put("dose", legacy.optString("dose", ""));
            med.put("time", legacy.optString("time", "08:00"));
            med.put("note", legacy.optString("note", ""));
            // 老数据没有起始日，用最早那条服药记录兜底，别让历史那些天变成「漏了」。
            med.put("since", earliestDoseDay(root));
            meds.put(med);
            root.put("remind", legacy.optBoolean("remind", true));
        }
        root.put("meds", meds);
        root.remove("med");
        if (!root.has("remind")) root.put("remind", true);

        // doses: { day: { at, source } }  ->  { day: { m1: { at, source } } }
        JSONObject doses = root.optJSONObject("doses");
        JSONArray days = doses == null ? null : doses.names();
        for (int i = 0; days != null && i < days.length(); i++) {
            String day = days.optString(i);
            JSONObject entry = doses.optJSONObject(day);
            if (entry != null && entry.has("at")) {
                JSONObject wrapped = new JSONObject();
                wrapped.put("m1", entry);
                doses.put(day, wrapped);
            }
        }
        root.put("version", 2);
    }

    /** v2：一种药只有一个 time。升到 times[]，服药记录多一层时间点。 */
    private static void toV3(JSONObject root) throws Exception {
        JSONArray meds = root.optJSONArray("meds");
        for (int i = 0; meds != null && i < meds.length(); i++) {
            JSONObject med = meds.optJSONObject(i);
            if (med == null) continue;
            JSONArray times = med.optJSONArray("times");
            if (times == null || times.length() == 0) {
                int[] hm = parseHm(med.optString("time", ""));
                JSONObject entry = new JSONObject();
                entry.put("id", "t1");
                entry.put("at", hm == null ? "08:00" : formatHm(hm[0], hm[1]));
                // 时间点自己的生死跟着药走，这样已经记下的那些天不会变。
                entry.put("since", med.optString("since", ""));
                entry.put("until", med.optString("until", ""));
                times = new JSONArray();
                times.put(entry);
                med.put("times", times);
            }
            med.remove("time");
        }

        // doses: { day: { m1: { at, source } } }  ->  { day: { m1: { t1: { at, source } } } }
        JSONObject doses = root.optJSONObject("doses");
        JSONArray days = doses == null ? null : doses.names();
        for (int i = 0; days != null && i < days.length(); i++) {
            JSONObject perMed = doses.optJSONObject(days.optString(i));
            JSONArray medIds = perMed == null ? null : perMed.names();
            for (int j = 0; medIds != null && j < medIds.length(); j++) {
                String medId = medIds.optString(j);
                JSONObject entry = perMed.optJSONObject(medId);
                if (entry != null && entry.has("at")) {
                    JSONObject wrapped = new JSONObject();
                    wrapped.put("t1", entry);
                    perMed.put(medId, wrapped);
                }
            }
        }
        root.put("version", 3);
    }

    private static String earliestDoseDay(JSONObject root) {
        JSONObject doses = root.optJSONObject("doses");
        JSONArray days = doses == null ? null : doses.names();
        String earliest = null;
        for (int i = 0; days != null && i < days.length(); i++) {
            String day = days.optString(i);
            if (earliest == null || day.compareTo(earliest) < 0) earliest = day;
        }
        return earliest == null ? todayKey() : earliest;
    }

    static JSONArray meds(Context context) {
        JSONArray meds = readObject(context).optJSONArray("meds");
        return meds == null ? new JSONArray() : meds;
    }

    static boolean remindEnabled(Context context) {
        return readObject(context).optBoolean("remind", false);
    }

    /** 某天在吃的药：加进来之前、停掉之后的日子都不算。 */
    static List<JSONObject> medsOn(JSONArray meds, String dayKey) {
        List<JSONObject> result = new ArrayList<>();
        for (int i = 0; i < meds.length(); i++) {
            JSONObject med = meds.optJSONObject(i);
            if (med == null) continue;
            if (aliveOn(med, dayKey)) result.add(med);
        }
        return result;
    }

    /** 某天这种药要吃的时间点。加这一次之前、减掉之后的日子都不算。 */
    static List<JSONObject> timesOn(JSONObject med, String dayKey) {
        List<JSONObject> result = new ArrayList<>();
        JSONArray times = med.optJSONArray("times");
        for (int i = 0; times != null && i < times.length(); i++) {
            JSONObject time = times.optJSONObject(i);
            if (time == null) continue;
            if (aliveOn(time, dayKey)) result.add(time);
        }
        return result;
    }

    private static boolean aliveOn(JSONObject thing, String dayKey) {
        String since = thing.optString("since", "");
        String until = thing.optString("until", "");
        if (!since.isEmpty() && dayKey.compareTo(since) < 0) return false;
        return until.isEmpty() || dayKey.compareTo(until) < 0;
    }

    /** 一次该吃的药：某种药 + 某个时间点。这是「记没记」的最小单位。 */
    static final class Item {
        final JSONObject med;
        final JSONObject time;

        Item(JSONObject med, JSONObject time) {
            this.med = med;
            this.time = time;
        }

        String medId() {
            return med.optString("id", "");
        }

        String timeId() {
            return time.optString("id", "");
        }

        /** 归一化后的时间点；解析不出来返回空串（界面上是「没设时间」，闹钟会跳过）。 */
        String at() {
            int[] hm = parseHm(time.optString("at", ""));
            return hm == null ? "" : formatHm(hm[0], hm[1]);
        }
    }

    static List<Item> itemsOn(JSONArray meds, String dayKey) {
        List<Item> result = new ArrayList<>();
        for (JSONObject med : medsOn(meds, dayKey)) {
            for (JSONObject time : timesOn(med, dayKey)) {
                result.add(new Item(med, time));
            }
        }
        return result;
    }

    static boolean markDose(
        Context context, String dayKey, String medId, String timeId, String source
    ) {
        try {
            JSONObject root = readObject(context);
            JSONObject doses = child(root, "doses");
            JSONObject day = child(doses, dayKey);
            JSONObject perMed = child(day, medId);
            if (perMed.has(timeId)) return false;
            JSONObject entry = new JSONObject();
            entry.put("at", nowStamp());
            entry.put("source", source);
            perMed.put(timeId, entry);
            write(context, root.toString());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean clearDose(Context context, String dayKey, String medId, String timeId) {
        try {
            JSONObject root = readObject(context);
            JSONObject doses = root.optJSONObject("doses");
            JSONObject day = doses == null ? null : doses.optJSONObject(dayKey);
            JSONObject perMed = day == null ? null : day.optJSONObject(medId);
            if (perMed == null) return false;
            perMed.remove(timeId);
            if (perMed.length() == 0) day.remove(medId);
            if (day.length() == 0) doses.remove(dayKey);
            write(context, root.toString());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean hasDose(JSONObject day, String medId, String timeId) {
        JSONObject perMed = day == null ? null : day.optJSONObject(medId);
        return perMed != null && perMed.has(timeId);
    }

    private static JSONObject child(JSONObject parent, String key) throws Exception {
        JSONObject existing = parent.optJSONObject(key);
        if (existing != null) return existing;
        JSONObject created = new JSONObject();
        parent.put(key, created);
        return created;
    }

    static String dosesJson(Context context) {
        JSONObject doses = readObject(context).optJSONObject("doses");
        return doses == null ? "{}" : doses.toString();
    }
}
