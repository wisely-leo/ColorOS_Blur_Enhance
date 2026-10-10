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

    private static final String KEY_IME_BLUR        = "ime_blur";
    private static final String KEY_IME_BLUR_RADIUS = "ime_blur_radius";
    private static final String KEY_IME_BLUR_CORNER = "ime_blur_corner";
    private static final String KEY_IME_BLUR_MASK   = "ime_blur_mask";
    private static final String KEY_BG_FILE     = "bg_file";
    private static final String KEY_LOG         = "log";
    private static final String KEY_QS_PROBE    = "quicksearch_blur";
    private static final String KEY_GALLERY_LIGHT = "gallery_light";

    private static final String KEY_CLOCK_ICON_ALPHA = "clock_icon_alpha";
    private static final String KEY_CLOCK_TEXT_ALPHA = "clock_text_alpha";
    private static final String KEY_CLOCK_BRIGHTEN   = "clock_brighten";
    private static final String KEY_CLOCK_GLASS      = "clock_glass";
    private static final String KEY_CLOCK_GLASS_BLEND= "clock_glass_blend";
    private static final String KEY_CLOCK_GLASS_MIX  = "clock_glass_mix";
    private static final String KEY_THEME_MODE  = "theme_mode";

    private static final String KEY_LAST_VERCODE = "last_version_code";

    public static volatile boolean sUpgradeClosedRecents = false;
    private final SharedPreferences sp;
    private final Context ctx;
    public SettingsStore(Context ctx) {
        this.ctx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        this.sp = this.ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        migrateOnUpgrade();
    }

    private void migrateOnUpgrade() {
        try {
            final long cur = versionCode();
            if (cur <= 0) return;
            final long last = sp.getLong(KEY_LAST_VERCODE, -1L);
            if (last == cur) return;

            if (last >= 0 && last <= 440L) {
                boolean hadRecents = sp.getBoolean(KEY_RECENTS, DEF_RECENTS);
                if (hadRecents) {
                    sp.edit().putBoolean(KEY_RECENTS, false).apply();
                    sUpgradeClosedRecents = true;
                    App.lg("[migrate] 版本 " + last + " -> " + cur
                            + "：已自动关闭「最近任务模糊」（需手动重新启用）");
                    try { syncToHost(); } catch (Throwable ignored) {}
                } else {
                    App.lg("[migrate] 版本 " + last + " -> " + cur + "：最近任务模糊本就未启用");
                }
            } else {
                App.lg("[migrate] 版本 " + last + " -> " + cur + "：按用户设置保留（不强制关闭）");
            }
            sp.edit().putLong(KEY_LAST_VERCODE, cur).apply();
        } catch (Throwable t) {
            App.lg("[migrate] EX: " + t);
        }
    }
    public static final boolean DEF_SHORTCUT   = true;

    public static final boolean DEF_RECENTS    = false;
    public static final boolean DEF_WIDGET     = true;
    public static final boolean DEF_POSTEFFECT = true;
    public static final float   DEF_SAMPLE_SCALE = 0.5f;

    public static final boolean DEF_IME_BLUR        = false;
    public static final float   DEF_IME_BLUR_RADIUS = 100f;
    public static final float   DEF_IME_BLUR_CORNER = 0f;
    public static final float   DEF_IME_BLUR_MASK   = 1.0f;
    public static final boolean DEF_LOG        = false;
    public static final boolean DEF_QS_PROBE   = true;
    public static final boolean DEF_GALLERY_LIGHT = true;
    public static final float   DEF_CLOCK_ICON_ALPHA = 0.30f;
    public static final float   DEF_CLOCK_TEXT_ALPHA = 0.30f;
    public static final float   DEF_CLOCK_BRIGHTEN   = 1.25f;
    public static final boolean DEF_CLOCK_GLASS      = false;
    private static final String[] HOST_PKGS_FALLBACK = {
            "com.coloros.alarmclock",
            "com.android.launcher",
            "com.oplus.blur",
            "com.heytap.quicksearchbox",
            "com.yuyan.pinyin.offline.release",
    };
    private static volatile String[] sHostPkgs = null;
    private static String[] hostPkgs() {
        if (sHostPkgs != null) return sHostPkgs;
        synchronized (SettingsStore.class) {
            if (sHostPkgs != null) return sHostPkgs;
            String[] loaded = readScopeList();
            sHostPkgs = (loaded != null && loaded.length > 0) ? loaded : HOST_PKGS_FALLBACK;
            App.lg("[scope] host pkgs=" + sHostPkgs.length
                    + (loaded != null && loaded.length > 0 ? " (from scope.list)" : " (fallback)"));
            return sHostPkgs;
        }
    }
    private static String[] readScopeList() {
        android.content.Context ctx = App.ctx();
        if (ctx == null) return null;
        java.util.zip.ZipFile zf = null;
        java.io.InputStream is = null;
        try {
            java.io.File apk = new java.io.File(ctx.getApplicationInfo().sourceDir);
            if (!apk.exists()) return null;
            zf = new java.util.zip.ZipFile(apk);
            java.util.zip.ZipEntry e = zf.getEntry("META-INF/xposed/scope.list");
            if (e == null) return null;
            is = zf.getInputStream(e);
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(is, "UTF-8"));
            java.util.List<String> out = new java.util.ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) continue;
                out.add(line);
            }
            br.close();
            return out.isEmpty() ? null : out.toArray(new String[0]);
        } catch (Throwable t) {
            App.lg("[scope] read scope.list failed: " + t);
            return null;
        } finally {
            try { if (is != null) is.close(); } catch (Throwable ignored) {}
            try { if (zf != null) zf.close(); } catch (Throwable ignored) {}
        }
    }
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
        App.lg("[sync] broadcast=" + n + "/" + hostPkgs().length + " pkgs"
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
        sb.append("gallery_light=").append(isGalleryLight() ? 1 : 0).append("\n");
        sb.append("clock_icon_alpha=").append(getClockIconAlpha()).append("\n");
        sb.append("clock_text_alpha=").append(getClockTextAlpha()).append("\n");
        sb.append("clock_brighten=").append(getClockBrighten()).append("\n");
        sb.append("clock_glass=").append(isClockGlass() ? 1 : 0).append("\n");
        sb.append("clock_glass_blend=").append(Integer.toHexString(getClockGlassBlend())).append("\n");
        sb.append("clock_glass_mix=").append(Integer.toHexString(getClockGlassMix())).append("\n");
        sb.append("ime_blur=").append(isImeBlur() ? 1 : 0).append("\n");
        sb.append("ime_blur_radius=").append((int) getImeBlurRadius()).append("\n");
        sb.append("ime_blur_corner=").append(getImeBlurCornerDp()).append("\n");
        sb.append("ime_blur_mask=").append(getImeBlurMask()).append("\n");
        return sb.toString();
    }
    public int sendConfBroadcast() {
        int ok = 0;
        for (String pkg : hostPkgs()) {
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
                i.putExtra("gallery_light", isGalleryLight() ? "1" : "0");
                i.putExtra("clock_icon_alpha", String.valueOf(getClockIconAlpha()));
                i.putExtra("clock_text_alpha", String.valueOf(getClockTextAlpha()));
                i.putExtra("clock_brighten", String.valueOf(getClockBrighten()));
                i.putExtra("clock_glass", isClockGlass() ? "1" : "0");
                i.putExtra("clock_glass_blend", Integer.toHexString(getClockGlassBlend()));
                i.putExtra("clock_glass_mix", Integer.toHexString(getClockGlassMix()));

                i.putExtra("ime_blur", isImeBlur() ? "1" : "0");
                i.putExtra("ime_blur_radius", String.valueOf((int) getImeBlurRadius()));
                i.putExtra("ime_blur_corner", String.valueOf(getImeBlurCornerDp()));
                i.putExtra("ime_blur_mask", String.valueOf(getImeBlurMask()));
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
    public boolean isGalleryLight(){ return sp.getBoolean(KEY_GALLERY_LIGHT, DEF_GALLERY_LIGHT); }
    public float   getClockIconAlpha(){ return sp.getFloat(KEY_CLOCK_ICON_ALPHA, DEF_CLOCK_ICON_ALPHA); }
    public float   getClockTextAlpha(){ return sp.getFloat(KEY_CLOCK_TEXT_ALPHA, DEF_CLOCK_TEXT_ALPHA); }
    public float   getClockBrighten(){ return sp.getFloat(KEY_CLOCK_BRIGHTEN, DEF_CLOCK_BRIGHTEN); }
    public boolean isClockGlass(){ return sp.getBoolean(KEY_CLOCK_GLASS, DEF_CLOCK_GLASS); }
    public int     getClockGlassBlend(){ return sp.getInt(KEY_CLOCK_GLASS_BLEND, 0); }
    public int     getClockGlassMix(){ return sp.getInt(KEY_CLOCK_GLASS_MIX, 0); }

    public static final int THEME_SYSTEM = 0, THEME_LIGHT = 1, THEME_DARK = 2;
    public int getThemeMode()      { return sp.getInt(KEY_THEME_MODE, THEME_SYSTEM); }
    public SettingsStore setThemeMode(int v) { sp.edit().putInt(KEY_THEME_MODE, v).apply(); return this; }
    public SettingsStore setShortcut(boolean v)   { sp.edit().putBoolean(KEY_SHORTCUT, v).apply(); syncToHost(); return this; }
    public SettingsStore setRecents(boolean v)    { sp.edit().putBoolean(KEY_RECENTS, v).apply(); syncToHost(); return this; }
    public SettingsStore setWidget(boolean v)     { sp.edit().putBoolean(KEY_WIDGET, v).apply(); syncToHost(); return this; }
    public SettingsStore setPostEffect(boolean v) { sp.edit().putBoolean(KEY_POSTEFFECT, v).apply(); syncToHost(); return this; }
    public SettingsStore setSampleScale(float v)  { sp.edit().putFloat(KEY_SAMPLE_SCALE, v).apply(); syncToHost(); return this; }

    public boolean isImeBlur()        { return sp.getBoolean(KEY_IME_BLUR, DEF_IME_BLUR); }
    public float   getImeBlurRadius() { return sp.getFloat(KEY_IME_BLUR_RADIUS, DEF_IME_BLUR_RADIUS); }
    // 键盘圆角固定 25dp，不再由用户设置（见 FeatureFlags.IME_BLUR_CORNER_DP）。
    public float   getImeBlurCornerDp(){ return 25f; }
    public SettingsStore setImeBlur(boolean v)    { sp.edit().putBoolean(KEY_IME_BLUR, v).apply(); syncToHost(); return this; }
    public SettingsStore setImeBlurRadius(float v){ sp.edit().putFloat(KEY_IME_BLUR_RADIUS, v).apply(); syncToHost(); return this; }
    public float   getImeBlurMask()   { return sp.getFloat(KEY_IME_BLUR_MASK, DEF_IME_BLUR_MASK); }
    public SettingsStore setImeBlurMask(float v){ sp.edit().putFloat(KEY_IME_BLUR_MASK, v).apply(); syncToHost(); return this; }
    public SettingsStore setLog(boolean v)        { sp.edit().putBoolean(KEY_LOG, v).apply(); syncToHost(); return this; }
    public SettingsStore setQsProbe(boolean v)    { sp.edit().putBoolean(KEY_QS_PROBE, v).apply(); syncToHost(); return this; }
    public SettingsStore setGalleryLight(boolean v) { sp.edit().putBoolean(KEY_GALLERY_LIGHT, v).apply(); syncToHost(); return this; }
    public SettingsStore setClockIconAlpha(float v){ sp.edit().putFloat(KEY_CLOCK_ICON_ALPHA, v).apply(); syncToHost(); return this; }
    public SettingsStore setClockTextAlpha(float v){ sp.edit().putFloat(KEY_CLOCK_TEXT_ALPHA, v).apply(); syncToHost(); return this; }
    public SettingsStore setClockBrighten(float v){ sp.edit().putFloat(KEY_CLOCK_BRIGHTEN, v).apply(); syncToHost(); return this; }
    public SettingsStore setClockGlass(boolean v){ sp.edit().putBoolean(KEY_CLOCK_GLASS, v).apply(); syncToHost(); return this; }

    public void setClockGlassColor(int hueColorBase, float opacity, float mixStrength) {
        opacity = Math.max(0f, Math.min(1f, opacity));
        mixStrength = Math.max(0f, Math.min(1f, mixStrength));
        int aA = Math.round(opacity * 255f);
        int r = (hueColorBase >> 16) & 0xFF;
        int g = (hueColorBase >> 8) & 0xFF;
        int b = hueColorBase & 0xFF;
        int blendA = (aA << 24) | (r << 16) | (g << 8) | b;
        int rB = Math.min(255, r + (255 - r) / 4);
        int gB = Math.min(255, g + (255 - g) / 4);
        int bB = Math.min(255, b + (255 - b) / 4);
        int aB = Math.round(opacity * mixStrength * 255f);
        int mixB = (aB << 24) | (rB << 16) | (gB << 8) | bB;
        sp.edit().putInt(KEY_CLOCK_GLASS_BLEND, blendA)
                 .putInt(KEY_CLOCK_GLASS_MIX, mixB).apply();
        syncToHost();
    }

    public float getClockGlassOpacity() {
        return ((getClockGlassBlend() >>> 24) & 0xFF) / 255f;
    }

    public int   getClockGlassHue(){ return sp.getInt("clock_glass_hue", 0); }
    public void  setClockGlassHue(int v){ sp.edit().putInt("clock_glass_hue", v).apply(); }
    public float getClockGlassMixStrength(){ return sp.getFloat("clock_glass_mix_strength", 0.5f); }
    public void  setClockGlassMixStrength(float v){ sp.edit().putFloat("clock_glass_mix_strength", v).apply(); }
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
        String mapped = LABEL_FALLBACK.get(pkg);
        if (mapped != null) return mapped;
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            android.content.pm.ApplicationInfo ai =
                    pm.getApplicationInfo(pkg, android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);
            CharSequence cs = pm.getApplicationLabel(ai);
            if (cs != null && cs.length() > 0) {
                String s = cs.toString();
                App.lg("[label] " + pkg + " => " + s);
                return s;
            }
            App.lg("[label] " + pkg + " => empty label");
        } catch (Throwable t) {
            App.lg("[label] " + pkg + " => EX " + t);
        }
        return pkg;
    }
    private static final java.util.HashMap<String, String> LABEL_FALLBACK = new java.util.HashMap<>();
    static {
        LABEL_FALLBACK.put("com.coloros.alarmclock", "时钟");
        LABEL_FALLBACK.put("com.android.launcher", "桌面");
        LABEL_FALLBACK.put("com.oplus.launcher", "桌面");
        LABEL_FALLBACK.put("com.coloros.launcher", "桌面");
        LABEL_FALLBACK.put("com.oplus.blur", "模糊服务");
        LABEL_FALLBACK.put("com.heytap.quicksearchbox", "全局搜索");
        LABEL_FALLBACK.put("com.coloros.gallery3d", "相册");
        LABEL_FALLBACK.put("com.yuyan.pinyin.offline.release", "语燕输入法");
    }
}
