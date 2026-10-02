package com.shortcutblur.ui;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
public final class SettingsStore {
    private static final String PREF = "blur_enhance";
    private static final String KEY_SHORTCUT    = "shortcut_blur";
    private static final String KEY_RECENTS     = "recents_blur";
    private static final String KEY_WIDGET      = "widget_blur";
    private static final String KEY_POSTEFFECT  = "posteffect";
    private static final String KEY_SAMPLE_SCALE= "sample_scale";
    private static final String KEY_BG_FILE     = "bg_file";
    private static final String KEY_LOG         = "log";
    private static final String KEY_QS_PROBE    = "quicksearch_blur";
    private final SharedPreferences sp;
    private final Context ctx;
    public SettingsStore(Context ctx) {
        this.ctx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        this.sp = this.ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
    public static final boolean DEF_SHORTCUT   = true;
    public static final boolean DEF_RECENTS    = true;
    public static final boolean DEF_WIDGET     = true;
    public static final boolean DEF_POSTEFFECT = true;
    public static final float   DEF_SAMPLE_SCALE = 0.5f;
    public static final boolean DEF_LOG        = false;
    public static final boolean DEF_QS_PROBE   = true;
    private static final String[] HOST_PKGS = {
            "com.coloros.alarmclock",
            "com.android.launcher",
            "com.oplus.blur",
            "com.heytap.quicksearchbox",
    };
    public static final String ACTION_SETCONF = "com.wiselyleo.blurenhance.SETCONF";
    private static final String PUBLIC_DIR = "/data/local/tmp/ColorOSBlurEnhance";
    private static final String PUBLIC_PATH = PUBLIC_DIR + "/blur.conf";
    private final android.os.Handler syncHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable syncTask = new Runnable() {
        @Override public void run() { doSync(); }
    };
    private void syncToHost() {
        syncHandler.removeCallbacks(syncTask);
        syncHandler.post(syncTask);
    }
    private void doSync() {
        sendShizukuWrite();
        int n = sendConfBroadcast();
        App.lg("[sync] broadcast=" + n + "/" + HOST_PKGS.length + " pkgs"
                + "  " + snapshot().replace("\n", " "));
    }
    private void sendShizukuWrite() {
        final String b64;
        try {
            b64 = android.util.Base64.encodeToString(
                    snapshot().getBytes("UTF-8"), android.util.Base64.NO_WRAP);
        } catch (Throwable t) {
            App.lg("[sync] shizuku write: snapshot encode EX " + t);
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String cmd = "mkdir -p " + PUBLIC_DIR
                            + " && echo '" + b64 + "' | base64 -d > " + PUBLIC_PATH
                            + " && chmod 755 " + PUBLIC_DIR
                            + " && chmod 644 " + PUBLIC_PATH
                            + " && chown shell:shell " + PUBLIC_PATH
                            + " && ls -la " + PUBLIC_PATH
                            + " && echo WROTE=$(wc -c < " + PUBLIC_PATH + ")";
                    String r = Adb.run(ctx, cmd);
                    App.lg("[sync] shizuku write -> " + r);
                } catch (Throwable t) {
                    App.lg("[sync] shizuku write EX: " + t);
                }
            }
        }, "blur-shizuku-write").start();
    }
    public String snapshot() {
        StringBuilder sb = new StringBuilder(160);
        sb.append("# ColorOS Blur Enhance - runtime feature flags (written by GUI)\n");
        sb.append("shortcut_blur=").append(isShortcut() ? 1 : 0).append("\n");
        sb.append("recents_blur=").append(isRecents() ? 1 : 0).append("\n");
        sb.append("widget_blur=").append(isWidget() ? 1 : 0).append("\n");
        sb.append("posteffect=").append(isPostEffect() ? 1 : 0).append("\n");
        sb.append("sample_scale=").append(getSampleScale()).append("\n");
        sb.append("log_enabled=").append(isLog() ? 1 : 0).append("\n");
        sb.append("quicksearch_blur=").append(isQsProbe() ? 1 : 0).append("\n");
        return sb.toString();
    }
    public int sendConfBroadcast() {
        int ok = 0;
        for (String pkg : HOST_PKGS) {
            try {
                Intent i = new Intent(ACTION_SETCONF);
                i.setPackage(pkg);
                i.putExtra("shortcut_blur", isShortcut()   ? "1" : "0");
                i.putExtra("recents_blur",  isRecents()    ? "1" : "0");
                i.putExtra("widget_blur",   isWidget()     ? "1" : "0");
                i.putExtra("posteffect",    isPostEffect() ? "1" : "0");
                i.putExtra("sample_scale",  String.valueOf(getSampleScale()));
                i.putExtra("log_enabled",   isLog() ? "1" : "0");
                i.putExtra("quicksearch_blur", isQsProbe() ? "1" : "0");
                ctx.sendBroadcast(i);
                ok++;
            } catch (Throwable t) {
                App.lg("[sync] broadcast -> " + pkg + " EX: " + t);
            }
        }
        return ok;
    }
    public boolean isShortcut()    { return sp.getBoolean(KEY_SHORTCUT, DEF_SHORTCUT); }
    public boolean isRecents()     { return sp.getBoolean(KEY_RECENTS, DEF_RECENTS); }
    public boolean isWidget()      { return sp.getBoolean(KEY_WIDGET, DEF_WIDGET); }
    public boolean isPostEffect()  { return sp.getBoolean(KEY_POSTEFFECT, DEF_POSTEFFECT); }
    public float   getSampleScale(){ return sp.getFloat(KEY_SAMPLE_SCALE, DEF_SAMPLE_SCALE); }
    public boolean isLog()         { return sp.getBoolean(KEY_LOG, DEF_LOG); }
    public boolean isQsProbe()     { return sp.getBoolean(KEY_QS_PROBE, DEF_QS_PROBE); }
    public SettingsStore setShortcut(boolean v)   { sp.edit().putBoolean(KEY_SHORTCUT, v).apply(); syncToHost(); return this; }
    public SettingsStore setRecents(boolean v)    { sp.edit().putBoolean(KEY_RECENTS, v).apply(); syncToHost(); return this; }
    public SettingsStore setWidget(boolean v)     { sp.edit().putBoolean(KEY_WIDGET, v).apply(); syncToHost(); return this; }
    public SettingsStore setPostEffect(boolean v) { sp.edit().putBoolean(KEY_POSTEFFECT, v).apply(); syncToHost(); return this; }
    public SettingsStore setSampleScale(float v)  { sp.edit().putFloat(KEY_SAMPLE_SCALE, v).apply(); syncToHost(); return this; }
    public SettingsStore setLog(boolean v)        { sp.edit().putBoolean(KEY_LOG, v).apply(); syncToHost(); return this; }
    public SettingsStore setQsProbe(boolean v)    { sp.edit().putBoolean(KEY_QS_PROBE, v).apply(); syncToHost(); return this; }
    public String appName() {
        String[] dirs = {
                "/data/adb/modules/" + ctx.getPackageName(),
                "/data/adb/lspd/modules/" + ctx.getPackageName(),
        };
        for (String d : dirs) {
            try {
                java.io.File f = new java.io.File(d, "module.prop");
                if (!f.exists() || !f.canRead()) continue;
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
                String ln;
                while ((ln = br.readLine()) != null) {
                    ln = ln.trim();
                    if (ln.startsWith("name=")) {
                        String v = ln.substring(5).trim();
                        br.close();
                        if (v.length() > 0) return v;
                    }
                }
                br.close();
            } catch (Throwable ignored) {}
        }
        try {
            android.content.pm.ApplicationInfo ai = ctx.getApplicationInfo();
            CharSequence cs = ctx.getPackageManager().getApplicationLabel(ai);
            if (cs != null && cs.length() > 0) return cs.toString();
        } catch (Throwable ignored) {}
        return "ColorOS Blur Enhance";
    }
    public String moduleProp(String key, String def) {
        String[] dirs = {
                "/data/adb/modules/" + ctx.getPackageName(),
                "/data/adb/lspd/modules/" + ctx.getPackageName(),
        };
        String prefix = key + "=";
        for (String d : dirs) {
            try {
                java.io.File f = new java.io.File(d, "module.prop");
                if (!f.exists() || !f.canRead()) continue;
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
                String ln;
                while ((ln = br.readLine()) != null) {
                    ln = ln.trim();
                    if (ln.startsWith(prefix)) {
                        String v = ln.substring(prefix.length()).trim();
                        br.close();
                        if (v.length() > 0) return v;
                    }
                }
                br.close();
            } catch (Throwable ignored) {}
        }
        return def;
    }
    public String getBgFile() { return sp.getString(KEY_BG_FILE, ""); }
    public SettingsStore setBgFile(String name) {
        sp.edit().putString(KEY_BG_FILE, name == null ? "" : name).apply();
        return this;
    }
    public java.io.File bgFile() {
        String n = getBgFile();
        if (n.length() == 0) return null;
        return new java.io.File(ctx.getFilesDir(), n);
    }
    public SettingsStore clearBg() {
        try {
            java.io.File f = bgFile();
            if (f != null && f.exists()) f.delete();
        } catch (Throwable ignored) {}
        return setBgFile("");
    }
    public boolean hasBg() {
        java.io.File f = bgFile();
        return f != null && f.exists() && f.length() > 0;
    }
    public java.util.List<String> scopePackages() {
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.zip.ZipFile zip = null;
        java.io.InputStream in = null;
        try {
            String apk = ctx.getApplicationInfo().sourceDir;
            zip = new java.util.zip.ZipFile(apk);
            java.util.zip.ZipEntry e = zip.getEntry("META-INF/xposed/scope.list");
            if (e == null) {
                App.lg("[scope] META-INF/xposed/scope.list NOT FOUND in " + apk);
                return out;
            }
            in = zip.getInputStream(e);
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, "UTF-8"));
            String ln;
            while ((ln = br.readLine()) != null) {
                ln = ln.trim();
                if (ln.length() == 0 || ln.startsWith("#")) continue;
                out.add(ln);
            }
            App.lg("[scope] loaded " + out.size() + " pkgs from scope.list: " + out);
        } catch (Throwable t) {
            App.lg("[scope] read scope.list EX: " + t);
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (zip != null) zip.close(); } catch (Throwable ignored) {}
        }
        return out;
    }
    public String versionName() {
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            if (pi.versionName != null && pi.versionName.length() > 0)
                return pi.versionName;
        } catch (Throwable ignored) {}
        String mp = moduleProp("version", "");
        if (mp != null && mp.length() > 0) return mp;
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            long vc = (android.os.Build.VERSION.SDK_INT >= 28)
                    ? pi.getLongVersionCode() : pi.versionCode;
            if (vc > 0) return String.valueOf(vc);
        } catch (Throwable ignored) {}
        return "unknown";
    }
    public long versionCode() {
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            return (android.os.Build.VERSION.SDK_INT >= 28)
                    ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Throwable ignored) {}
        return 0L;
    }
    public String authorName()  { return moduleProp("author",  "Wisely_Leo"); }
    public String appLabel(String pkg) {
        if (pkg == null || pkg.length() == 0) return "?";
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            android.content.pm.ApplicationInfo ai =
                    pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(ai);
            if (cs != null && cs.length() > 0) return cs.toString();
        } catch (Throwable ignored) {}
        return pkg;
    }
}
