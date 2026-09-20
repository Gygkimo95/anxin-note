package com.calmnote.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 备份必须落在「下载」这种共享目录里。
 * 应用私有目录的东西一卸载就跟着没了，那不叫备份。
 */
final class Backups {

    static final String FOLDER = "安心手记";
    private static final String LATEST = "安心手记-备份.json";
    private static final String PREVIOUS = "安心手记-备份-上一次.json";

    private static final String PREFS = "backup-state";
    private static final String KEY_LAST = "last-auto";
    private static final long INTERVAL = 12 * 60 * 60 * 1000L;

    private Backups() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static long lastBackupAt(Context context) {
        return prefs(context).getLong(KEY_LAST, 0);
    }

    static String location() {
        return "下载/" + FOLDER;
    }

    /** 用「有多少条东西」粗略衡量份量，用来拦住把好备份盖成空文件。 */
    private static int contentScore(String json) {
        if (json == null || json.isEmpty()) return -1;
        try {
            JSONObject root = new JSONObject(json);
            int score = 0;
            if (root.optJSONArray("meds") != null) score += root.optJSONArray("meds").length();
            if (root.optJSONArray("stress") != null) score += root.optJSONArray("stress").length();
            JSONObject doses = root.optJSONObject("doses");
            if (doses != null) score += doses.length();
            return score;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * @param force 用户手点「立即备份」时为 true，跳过时间间隔限制。
     * @return 空串表示成功，否则是原因。
     */
    static String backup(Context context, boolean force) {
        String current = Store.read(context);
        int score = contentScore(current);
        if (score <= 0) return "现在没什么可备份的";

        if (!force) {
            long last = lastBackupAt(context);
            if (last > 0 && System.currentTimeMillis() - last < INTERVAL) return "";
        }

        String existing = readShared(context, LATEST);
        int existingScore = contentScore(existing);
        // 新的比旧的还空，八成是出了岔子，宁可不备份也别把好的盖掉。
        if (existingScore > score && !force) {
            return "这次的内容比上次的备份还少，先没覆盖";
        }

        if (existing != null && !existing.isEmpty()) {
            writeShared(context, PREVIOUS, existing);
        }
        boolean ok = writeShared(context, LATEST, current);
        if (!ok) return "写不进「下载」文件夹";

        prefs(context).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply();
        return "";
    }

    /* ---------------- 共享目录读写 ---------------- */

    private static Uri findShared(Context context, String name) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        String[] projection = {MediaStore.Downloads._ID};
        String selection = MediaStore.Downloads.RELATIVE_PATH + "=? AND "
            + MediaStore.Downloads.DISPLAY_NAME + "=?";
        String[] args = {Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/", name};
        try (Cursor cursor = context.getContentResolver().query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                return Uri.withAppendedPath(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, String.valueOf(cursor.getLong(0))
                );
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    static String readShared(Context context, String name) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Uri uri = findShared(context, name);
                if (uri == null) return null;
                try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                    return in == null ? null : slurp(in);
                }
            }
            File file = legacyFile(name);
            if (!file.exists()) return null;
            try (InputStream in = new FileInputStream(file)) {
                return slurp(in);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean writeShared(Context context, String name, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Uri uri = findShared(context, name);
                if (uri == null) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                    values.put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER
                    );
                    uri = context.getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) return false;
                }
                // "wt" 是截断写，不然内容变短时会留着上一版的尾巴。
                try (OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) return false;
                    out.write(bytes);
                }
                return true;
            }

            File file = legacyFile(name);
            File dir = file.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) return false;
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(bytes);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static File legacyFile(String name) {
        File dir = new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER
        );
        return new File(dir, name);
    }

    private static String slurp(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
            out.write(buffer, 0, count);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
