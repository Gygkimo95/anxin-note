package com.calmnote.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final String PERMISSION_NOTIFY = "android.permission.POST_NOTIFICATIONS";
    private static final int REQUEST_NOTIFY = 4001;
    private static final int REQUEST_IMPORT = 4002;
    private static final String PREFS = "permission-state";
    private static final String KEY_ASKED_NOTIFY = "asked-notify";

    private WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        webView.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        webView.addJavascriptInterface(new Bridge(), "Native");
        webView.loadUrl("file:///android_asset/index.html");

        Reminders.ensureChannel(this);
        Reminders.rescheduleAll(this);
        KeepAliveService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 通知上点过「吃了」的话，数据在原生侧变了，回到前台要重新读一遍。
        Reminders.rescheduleAll(this);
        // 在前台，这里拉服务不受后台启动限制；顺便把那条常驻通知的「下次」刷新。
        KeepAliveService.start(this);
        reloadWeb();
        Backups.backup(this, false);
    }

    private void reloadWeb() {
        if (webView == null) return;
        webView.evaluateJavascript("window.__reloadFromNative && window.__reloadFromNative();", null);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != REQUEST_NOTIFY) return;
        Reminders.rescheduleAll(this);
        reloadWeb();
    }

    /** 没有 meds 也没有 stress，就是一份没内容的状态。 */
    private static boolean isBlank(JSONObject root) {
        if (root == null) return true;
        JSONArray meds = root.optJSONArray("meds");
        JSONArray stress = root.optJSONArray("stress");
        boolean noMeds = meds == null || meds.length() == 0;
        boolean noStress = stress == null || stress.length() == 0;
        return noMeds && noStress;
    }

    private void openBackupPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain"});
        try {
            startActivityForResult(intent, REQUEST_IMPORT);
        } catch (Exception e) {
            toast("这台手机上找不到文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQUEST_IMPORT) return;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        restoreFrom(data.getData());
    }

    private void restoreFrom(Uri uri) {
        String raw;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                toast("这个文件读不出来");
                return;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
            raw = out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            toast("这个文件读不出来");
            return;
        }

        JSONObject root;
        try {
            root = new JSONObject(raw);
        } catch (Exception e) {
            toast("这不像是安心手记的备份");
            return;
        }
        if (!root.has("meds") && !root.has("med") && !root.has("stress") && !root.has("doses")) {
            toast("这不像是安心手记的备份");
            return;
        }
        if (isBlank(root) && root.optJSONObject("doses") == null) {
            toast("这份备份里是空的");
            return;
        }

        // 覆盖之前先把当前状态存成一份备份，免得恢复错文件又丢一次。
        Backups.backup(this, true);
        Store.migrate(root);
        Store.write(this, root.toString());
        Reminders.rescheduleAll(this);
        reloadWeb();
        toast("恢复好了");
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript("window.__handleBack && window.__handleBack();", value -> {
                if (!"true".equals(value)) finish();
            });
            return;
        }
        super.onBackPressed();
    }

    private void toast(String message) {
        mainHandler.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    private void startSafely(Intent intent) {
        mainHandler.post(() -> {
            try {
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "打不开这个系统设置页", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private boolean saveTextToDownloads(String filename, String content, String mime) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                values.put(MediaStore.Downloads.MIME_TYPE, mime);
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = getContentResolver()
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return false;
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) return false;
                    out.write(bytes);
                }
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(uri, values, null, null);
                return true;
            }
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists() && !dir.mkdirs()) return false;
            try (FileOutputStream out = new FileOutputStream(new File(dir, filename))) {
                out.write(bytes);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public class Bridge {

        @JavascriptInterface
        public String load() {
            return Store.readObject(MainActivity.this).toString();
        }

        /**
         * 网页层保存整份状态，但 doses 一律以原生文件为准并原样保留——
         * 通知里记下的那次不能被一次普通保存覆盖掉。
         */
        @JavascriptInterface
        public String save(String json) {
            try {
                JSONObject incoming = new JSONObject(json);
                JSONObject current = Store.readObject(MainActivity.this);
                JSONObject doses = current.optJSONObject("doses");
                incoming.put("doses", doses == null ? new JSONObject() : doses);

                // 界面里没有「清空全部」这种操作，所以一个又没药又没记录的状态
                // 只可能是读盘失败后拿空白兜底，绝对不能让它落盘。
                if (isBlank(incoming) && !isBlank(current)) {
                    return current.optJSONObject("doses") == null
                        ? "{}" : current.optJSONObject("doses").toString();
                }

                Store.write(MainActivity.this, incoming.toString());
                Reminders.rescheduleAll(MainActivity.this);
                Backups.backup(MainActivity.this, false);
                return incoming.optJSONObject("doses").toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public long lastBackupAt() {
            return Backups.lastBackupAt(MainActivity.this);
        }

        @JavascriptInterface
        public String backupLocation() {
            return Backups.location();
        }

        @JavascriptInterface
        public String backupNow() {
            return Backups.backup(MainActivity.this, true);
        }

        @JavascriptInterface
        public void pickBackupToRestore() {
            runOnUiThread(MainActivity.this::openBackupPicker);
        }

        @JavascriptInterface
        public String setDose(String dayKey, String medId, String timeId) {
            Store.markDose(MainActivity.this, dayKey, medId, timeId, "app");
            return Store.dosesJson(MainActivity.this);
        }

        @JavascriptInterface
        public String clearDose(String dayKey, String medId, String timeId) {
            Store.clearDose(MainActivity.this, dayKey, medId, timeId);
            return Store.dosesJson(MainActivity.this);
        }

        /** 界面上显示「下次提醒」用；0 表示没有排上。 */
        @JavascriptInterface
        public String nextReminderAt() {
            return String.valueOf(Reminders.soonestTrigger(MainActivity.this));
        }

        @JavascriptInterface
        public boolean notificationsEnabled() {
            NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                return manager.areNotificationsEnabled();
            }
            return true;
        }

        @JavascriptInterface
        public void requestNotifications() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                openAppNotificationSettings();
                return;
            }
            SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            boolean asked = prefs.getBoolean(KEY_ASKED_NOTIFY, false);
            mainHandler.post(() -> {
                // 已经拒过一次且系统不再允许解释，弹框不会出现，只能把人送到设置页。
                if (asked && !shouldShowRequestPermissionRationale(PERMISSION_NOTIFY)) {
                    openAppNotificationSettings();
                    return;
                }
                prefs.edit().putBoolean(KEY_ASKED_NOTIFY, true).apply();
                requestPermissions(new String[]{PERMISSION_NOTIFY}, REQUEST_NOTIFY);
            });
        }

        @JavascriptInterface
        public void openAppNotificationSettings() {
            Intent intent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            } else {
                intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", getPackageName(), null));
            }
            startSafely(intent);
        }

        @JavascriptInterface
        public boolean canScheduleExact() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
            AlarmManager alarms = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            return alarms != null && alarms.canScheduleExactAlarms();
        }

        @JavascriptInterface
        public void openExactAlarmSettings() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
            startSafely(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.fromParts("package", getPackageName(), null)));
        }

        @JavascriptInterface
        public boolean batteryUnrestricted() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
            PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return power != null && power.isIgnoringBatteryOptimizations(getPackageName());
        }

        @SuppressLint("BatteryLife")
        @JavascriptInterface
        public void openBatterySettings() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
            startSafely(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.fromParts("package", getPackageName(), null)));
        }

        @JavascriptInterface
        public void saveTextFile(String filename, String content, String mime) {
            String name = (filename == null || filename.trim().isEmpty())
                ? "anxin-backup.json" : filename.trim();
            String type = (mime == null || mime.trim().isEmpty())
                ? "application/json" : mime.trim();
            boolean ok = saveTextToDownloads(name, content == null ? "" : content, type);
            MainActivity.this.toast(ok ? "已存到「下载」：" + name : "保存失败");
        }

        @JavascriptInterface
        public void toast(String message) {
            if (message != null && !message.isEmpty()) MainActivity.this.toast(message);
        }

        /** 闹钟上次真的响到的时间；0 表示装上以来一次都没响过。 */
        @JavascriptInterface
        public String lastFireAt() {
            return String.valueOf(Reminders.lastFireAt(MainActivity.this));
        }

        @JavascriptInterface
        public String lastFireSlot() {
            return Reminders.lastFireSlot(MainActivity.this);
        }

        @JavascriptInterface
        public boolean keepAliveOn() {
            return KeepAliveService.enabled(MainActivity.this);
        }

        /** 常驻前台服务开关。只在排查面板里露出，默认关。 */
        @JavascriptInterface
        public void setKeepAlive(boolean on) {
            KeepAliveService.setEnabled(MainActivity.this, on);
            MainActivity.this.toast(on
                ? "通知栏会多一条常驻的，它在帮你把提醒钉住"
                : "已经关掉，常驻通知会消失");
        }

        /** 提醒从什么时候开始排上的；界面用它排除「打开提醒之前就过去的那些顿」。 */
        @JavascriptInterface
        public String armedAt() {
            return String.valueOf(Reminders.armedAt(MainActivity.this));
        }

        /** 排查用的闹钟：预定时间 和 实际响的时间，两个都给界面，好算差了多久。 */
        @JavascriptInterface
        public String testDueAt() {
            return String.valueOf(Reminders.testDueAt(MainActivity.this));
        }

        @JavascriptInterface
        public String testFiredAt() {
            return String.valueOf(Reminders.testFiredAt(MainActivity.this));
        }

        @JavascriptInterface
        public String scheduleTestAlarm(int minutes) {
            int wait = minutes < 1 ? 2 : minutes;
            return Reminders.scheduleTest(MainActivity.this, wait);
        }

        /**
         * 自启动白名单。没有任何 API 能查到开没开，也没有标准入口，
         * 只能按厂家一个个试已知的设置页，全都打不开就退回应用详情页。
         */
        @JavascriptInterface
        public void openAutoStartSettings() {
            String[][] known = {
                {"com.miui.securitycenter",
                 "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.huawei.systemmanager",
                 "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager",
                 "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"},
                {"com.coloros.safecenter",
                 "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.coloros.safecenter",
                 "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                {"com.oplus.safecenter",
                 "com.oplus.safecenter.permission.startup.StartupAppListActivity"},
                {"com.vivo.permissionmanager",
                 "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.iqoo.secure",
                 "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
                {"com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"},
                {"com.oneplus.security",
                 "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"},
                {"com.letv.android.letvsafe",
                 "com.letv.android.letvsafe.AutobootManageActivity"},
            };

            for (String[] target : known) {
                Intent intent = new Intent()
                    .setComponent(new android.content.ComponentName(target[0], target[1]))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (getPackageManager().resolveActivity(intent, 0) == null) continue;
                try {
                    startActivity(intent);
                    return;
                } catch (Exception ignored) {
                    // 能解析到不代表允许外部打开，继续试下一个。
                }
            }

            MainActivity.this.toast("这台手机没找到自启动页，去应用信息里找找");
            startSafely(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", getPackageName(), null)));
        }

        /** 返回空字符串表示发出去了，否则是失败原因，直接显示给用户看。 */
        @JavascriptInterface
        public String previewReminder() {
            java.util.List<String> slots = Reminders.activeSlots(MainActivity.this);
            if (slots.isEmpty()) return "还没有填好时间的药";
            return Reminders.notifySlot(MainActivity.this, slots.get(0), true);
        }
    }
}
