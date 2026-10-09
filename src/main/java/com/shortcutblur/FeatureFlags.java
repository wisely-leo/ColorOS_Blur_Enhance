package com.shortcutblur;
public final class FeatureFlags {
    public static volatile boolean SHORTCUT_BLUR = true;

    public static volatile boolean RECENTS_BLUR = false;
    public static volatile boolean WIDGET_BLUR = true;
    public static volatile boolean QUICKSEARCH_BLUR = true;
    public static volatile boolean GALLERY_LIGHT = true;
    public static volatile boolean POSTEFFECT = true;
    public static volatile float SAMPLE_SCALE = 0.5f;
    public static volatile boolean LOG_ENABLED = false;

    // ===== 时钟组件透明度 / 彩色玻璃（可调参数）=====
    /** 天气图标透明度 0.0-1.0（默认 0.30）*/
    public static volatile float CLOCK_ICON_ALPHA = 0.30f;
    /** 时钟文字/数字颜色 alpha 0.0-1.0（默认 0.30）*/
    public static volatile float CLOCK_TEXT_ALPHA = 0.30f;
    /** 时钟提亮增益（默认 1.25）*/
    public static volatile float CLOCK_BRIGHTEN = 1.25f;
    /** 【预留】彩色玻璃开关 */
    public static volatile boolean CLOCK_GLASS = false;
    /** 【预留】彩色玻璃 blendColor（ARGB int）*/
    public static volatile int CLOCK_GLASS_BLEND = 0;
    /** 【预留】彩色玻璃 mixColor（ARGB int）*/
    public static volatile int CLOCK_GLASS_MIX = 0;
    public static final String CONF_PATH = "/data/local/tmp/ColorOSBlurEnhance/blur.conf";
    private static volatile boolean sLoaded = false;
    private static final String TAG = "FLAGS";
    private FeatureFlags() {}
    public static void load() {
        if (sLoaded) return;
        synchronized (FeatureFlags.class) {
            if (sLoaded) return;
            try {
                loadFromFile();
            } catch (Throwable t) {
                ModuleLog.e(TAG, "loadFromFile failed", t);
            }
            try {
                loadFromSettings();
            } catch (Throwable t) {
                ModuleLog.e(TAG, "loadFromSettings failed", t);
            }
            sLoaded = true;
            ModuleLog.d(TAG, "loaded " + summary());
        }
    }
    private static void loadFromFile() throws Throwable {
        java.io.File f = new java.io.File(CONF_PATH);
        if (!f.exists() || !f.canRead()) {
            ModuleLog.d(TAG, "conf not found: " + CONF_PATH);
            return;
        }
        java.io.BufferedReader br = null;
        try {
            br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                applyKey(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } finally {
            if (br != null) {
                try { br.close(); } catch (Throwable ignored) {}
            }
        }
    }
    private static void loadFromSettings() {
        android.content.Context ctx = null;
        try {
            ctx = BlurLib.currentAppContext();
        } catch (Throwable ignored) {}
        if (ctx == null) return;
        android.content.ContentResolver cr = ctx.getContentResolver();
        if (cr == null) return;
        Boolean b;
        b = readSettingBool(cr, "coloros_blur_feature_shortcut");
        if (b != null) SHORTCUT_BLUR = b;
        b = readSettingBool(cr, "coloros_blur_feature_recents");
        if (b != null) RECENTS_BLUR = b;
        b = readSettingBool(cr, "coloros_blur_feature_widget");
        if (b != null) WIDGET_BLUR = b;
        b = readSettingBool(cr, "coloros_blur_feature_posteffect");
        if (b != null) POSTEFFECT = b;
        b = readSettingBool(cr, "coloros_blur_feature_quicksearch");
        if (b != null) QUICKSEARCH_BLUR = b;
        b = readSettingBool(cr, "coloros_blur_feature_gallery_light");
        if (b != null) GALLERY_LIGHT = b;
        try {
            String s = android.provider.Settings.System.getString(cr, "coloros_blur_feature_sample_scale");
            if (s != null) {
                float f = Float.parseFloat(s.trim());
                if (f >= 0.0f && f <= 1.0f) SAMPLE_SCALE = f;
            }
        } catch (Throwable ignored) {}
        try {
            String s = android.provider.Settings.System.getString(cr, "coloros_blur_clock_icon_alpha");
            if (s != null) { float f = Float.parseFloat(s.trim()); if (f >= 0.0f && f <= 1.0f) CLOCK_ICON_ALPHA = f; }
        } catch (Throwable ignored) {}
        try {
            String s = android.provider.Settings.System.getString(cr, "coloros_blur_clock_text_alpha");
            if (s != null) { float f = Float.parseFloat(s.trim()); if (f >= 0.0f && f <= 1.0f) CLOCK_TEXT_ALPHA = f; }
        } catch (Throwable ignored) {}
        try {
            String s = android.provider.Settings.System.getString(cr, "coloros_blur_clock_brighten");
            if (s != null) { float f = Float.parseFloat(s.trim()); if (f >= 0.5f && f <= 3.0f) CLOCK_BRIGHTEN = f; }
        } catch (Throwable ignored) {}
        b = readSettingBool(cr, "coloros_blur_clock_glass");
        if (b != null) CLOCK_GLASS = b;
    }
    private static Boolean readSettingBool(android.content.ContentResolver cr, String key) {
        try {
            String v = android.provider.Settings.System.getString(cr, key);
            if (v == null) return null;
            v = v.trim();
            if ("1".equals(v) || "true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v)) return Boolean.TRUE;
            if ("0".equals(v) || "false".equalsIgnoreCase(v) || "off".equalsIgnoreCase(v)) return Boolean.FALSE;
        } catch (Throwable ignored) {}
        return null;
    }
    public static void applyKey(String k, String v) {
        switch (k) {
            case "shortcut_blur":
            case "shortcut":
                SHORTCUT_BLUR = parseBool(v, SHORTCUT_BLUR);
                break;
            case "recents_blur":
            case "recents":
                RECENTS_BLUR = parseBool(v, RECENTS_BLUR);
                break;
            case "widget_blur":
            case "widget":
                WIDGET_BLUR = parseBool(v, WIDGET_BLUR);
                break;
            case "posteffect":
            case "post_effect":
                POSTEFFECT = parseBool(v, POSTEFFECT);
                break;
            case "quicksearch_blur":
            case "quicksearch":
            case "qs_blur":
                QUICKSEARCH_BLUR = parseBool(v, QUICKSEARCH_BLUR);
                break;
            case "gallery_light":
            case "gallery_light_theme":
                GALLERY_LIGHT = parseBool(v, GALLERY_LIGHT);
                break;
            case "log_enabled":
            case "log":
                LOG_ENABLED = parseBool(v, LOG_ENABLED);
                break;
            case "clock_icon_alpha":
                try { float f = Float.parseFloat(v); if (f >= 0f && f <= 1f) CLOCK_ICON_ALPHA = f; } catch (Throwable ignored) {}
                break;
            case "clock_text_alpha":
                try { float f = Float.parseFloat(v); if (f >= 0f && f <= 1f) CLOCK_TEXT_ALPHA = f; } catch (Throwable ignored) {}
                break;
            case "clock_brighten":
                try { float f = Float.parseFloat(v); if (f >= 0.5f && f <= 3f) CLOCK_BRIGHTEN = f; } catch (Throwable ignored) {}
                break;
            case "clock_glass":
                CLOCK_GLASS = parseBool(v, CLOCK_GLASS);
                break;
            case "clock_glass_blend":
                try { CLOCK_GLASS_BLEND = (int) Long.parseLong(v.trim(), 16); } catch (Throwable ignored) {}
                break;
            case "clock_glass_mix":
                try { CLOCK_GLASS_MIX = (int) Long.parseLong(v.trim(), 16); } catch (Throwable ignored) {}
                break;
            case "sample_scale":
                try {
                    float f = Float.parseFloat(v);
                    if (f >= 0.0f && f <= 1.0f) SAMPLE_SCALE = f;
                } catch (Throwable ignored) {}
                break;
            default:
                ModuleLog.d(TAG, "unknown key: " + k);
                break;
        }
    }
    private static boolean parseBool(String v, boolean def) {
        if (v == null) return def;
        v = v.trim();
        if ("1".equals(v) || "true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v)) {
            return true;
        }
        if ("0".equals(v) || "false".equalsIgnoreCase(v) || "off".equalsIgnoreCase(v) || "no".equalsIgnoreCase(v)) {
            return false;
        }
        return def;
    }
    /** 从广播 Intent 解析配置（GUI 实时下发），返回是否有变更 */
    public static boolean applyFromIntent(android.content.Intent i) {
        if (i == null) return false;
        boolean changed = false;
        try {
            String v;
            v = i.getStringExtra("clock_icon_alpha");
            if (v != null) { float old = CLOCK_ICON_ALPHA; applyKey("clock_icon_alpha", v); changed |= (old != CLOCK_ICON_ALPHA); }
            v = i.getStringExtra("clock_text_alpha");
            if (v != null) { float old = CLOCK_TEXT_ALPHA; applyKey("clock_text_alpha", v); changed |= (old != CLOCK_TEXT_ALPHA); }
            v = i.getStringExtra("clock_brighten");
            if (v != null) { float old = CLOCK_BRIGHTEN; applyKey("clock_brighten", v); changed |= (old != CLOCK_BRIGHTEN); }
            v = i.getStringExtra("clock_glass");
            if (v != null) { boolean old = CLOCK_GLASS; applyKey("clock_glass", v); changed |= (old != CLOCK_GLASS); }
            v = i.getStringExtra("clock_glass_blend");
            if (v != null) { int old = CLOCK_GLASS_BLEND; applyKey("clock_glass_blend", v); changed |= (old != CLOCK_GLASS_BLEND); }
            v = i.getStringExtra("clock_glass_mix");
            if (v != null) { int old = CLOCK_GLASS_MIX; applyKey("clock_glass_mix", v); changed |= (old != CLOCK_GLASS_MIX); }
        } catch (Throwable t) {
            ModuleLog.e(TAG, "applyFromIntent failed", t);
        }
        if (changed) ModuleLog.d(TAG, "live conf -> " + summary());
        return changed;
    }
    public static String summary() {
        return "shortcut=" + SHORTCUT_BLUR
                + " recents=" + RECENTS_BLUR
                + " widget=" + WIDGET_BLUR
                + " posteffect=" + POSTEFFECT
                + " qsBlur=" + QUICKSEARCH_BLUR
                + " sampleScale=" + SAMPLE_SCALE
                + " log=" + LOG_ENABLED
                + " clockIconA=" + CLOCK_ICON_ALPHA
                + " clockTextA=" + CLOCK_TEXT_ALPHA
                + " clockBright=" + CLOCK_BRIGHTEN
                + " clockGlass=" + CLOCK_GLASS;
    }
}
