package com.shortcutblur;

import static com.shortcutblur.FeatureFlags.*;

final class FlagStore {

    private static volatile boolean sLoaded = false;

    private static final String TAG = "FLAGS";

    private FlagStore() {}

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
                try { br.close(); } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
            }
        }
    }
    private static void loadFromSettings() {
        android.content.Context ctx = null;
        try {
            ctx = BlurLib.currentAppContext();
        } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
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
        b = readSettingBool(cr, "coloros_blur_feature_launcher_card");
        if (b != null) LAUNCHER_CARD_BLUR = b;
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
        } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
        try {
            String s = android.provider.Settings.System.getString(cr, "coloros_blur_clock_icon_alpha");
            if (s != null) { float f = Float.parseFloat(s.trim()); if (f >= 0.0f && f <= 1.0f) CLOCK_ICON_ALPHA = f; }
        } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
        try {
            String s = android.provider.Settings.System.getString(cr, "coloros_blur_clock_text_alpha");
            if (s != null) { float f = Float.parseFloat(s.trim()); if (f >= 0.0f && f <= 1.0f) CLOCK_TEXT_ALPHA = f; }
        } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
        try {
            String s = android.provider.Settings.System.getString(cr, "coloros_blur_clock_brighten");
            if (s != null) { float f = Float.parseFloat(s.trim()); if (f >= 0.5f && f <= 3.0f) CLOCK_BRIGHTEN = f; }
        } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
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
        } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
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
            case "launcher_card_blur":
            case "launcher_card":
                LAUNCHER_CARD_BLUR = parseBool(v, LAUNCHER_CARD_BLUR);
                break;
            case "posteffect":
            case "post_effect":
                POSTEFFECT = parseBool(v, POSTEFFECT);
                break;
            case "home_rotate":
            case "homerotate":
            case "home_rotation":
                boolean hr = parseBool(v, HOME_ROTATE);
                HOME_ROTATE = hr;
                HOME_ROTATE_ICON = hr;
                break;
            case "home_rotate_icon":
                boolean hri = parseBool(v, HOME_ROTATE_ICON);
                HOME_ROTATE = hri;
                HOME_ROTATE_ICON = hri;
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
                try { float f = Float.parseFloat(v); if (f >= 0f && f <= 1f) CLOCK_ICON_ALPHA = f; } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
                break;
            case "clock_text_alpha":
                try { float f = Float.parseFloat(v); if (f >= 0f && f <= 1f) CLOCK_TEXT_ALPHA = f; } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
                break;
            case "clock_brighten":
                try { float f = Float.parseFloat(v); if (f >= 0.5f && f <= 3f) CLOCK_BRIGHTEN = f; } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
                break;
            case "clock_glass":
                CLOCK_GLASS = parseBool(v, CLOCK_GLASS);
                break;
            case "clock_glass_blend":
                try { CLOCK_GLASS_BLEND = (int) Long.parseLong(v.trim(), 16); } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
                break;
            case "clock_glass_mix":
                try { CLOCK_GLASS_MIX = (int) Long.parseLong(v.trim(), 16); } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
                break;
            case "sample_scale":
                try {
                    float f = Float.parseFloat(v);
                    if (f >= 0.0f && f <= 1.0f) SAMPLE_SCALE = f;
                } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
                break;
            case "ime_blur":
            case "imeblur":
                IME_BLUR = parseBool(v, IME_BLUR);
                break;
            case "ime_blur_radius":
                try {
                    int r = Integer.parseInt(v.trim());
                    if (r >= 0 && r <= 500) IME_BLUR_RADIUS = r;
                } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
                break;
            case "shortcut_blur_radius":
                try {
                    float f = Float.parseFloat(v.trim());
                    if (f >= 0f && f <= 200f) SHORTCUT_BLUR_RADIUS = f;
                } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
                break;
            case "shortcut_window_blur_radius":
                try {
                    float f = Float.parseFloat(v.trim());
                    if (f >= 0f && f <= 200f) SHORTCUT_WINDOW_BLUR_RADIUS = f;
                } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
                break;
            case "card_panel_alpha":
                try {
                    String s = v.trim();
                    int a = (s.startsWith("0x") || s.startsWith("0X"))
                            ? (int) Long.parseLong(s.substring(2), 16)
                            : Integer.parseInt(s);
                    if (a >= 0 && a <= 0xFF) CARD_PANEL_ALPHA = a;
                } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
                break;
            case "card_secondary_blur_radius":
                try {
                    float f = Float.parseFloat(v.trim());
                    CARD_SECONDARY_BLUR_RADIUS = f;
                } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
                break;
            case "card_blur_corner":
            case "card_blur_corner_dp":
                try {
                    float f = Float.parseFloat(v.trim());
                    if (f >= 0f && f <= 100f) CARD_BLUR_CORNER_DP = f;
                } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
                break;
            case "ime_blur_corner":
            case "ime_blur_corner_dp":
                break;
            case "ime_blur_color_light":
                try { IME_BLUR_COLOR_LIGHT = (int) Long.parseLong(v.trim(), 16); } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
                break;
            case "ime_blur_color_dark":
                try { IME_BLUR_COLOR_DARK = (int) Long.parseLong(v.trim(), 16); } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
                break;
            case "ime_blur_mask":
            case "ime_blur_mask_alpha":
                try {
                    float f = Float.parseFloat(v.trim());
                    if (f >= 0f && f <= 1f) IME_BLUR_MASK_ALPHA = f;
                } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
                break;
            case "ime_glow_alpha_dark":
                try {
                    String s = v.trim();
                    int a = (s.startsWith("0x") || s.startsWith("0X"))
                            ? (int) Long.parseLong(s.substring(2), 16)
                            : Integer.parseInt(s);
                    if (a >= 0 && a <= 0xFF) IME_GLOW_ALPHA_DARK = a;
                } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
                break;
            case "ime_glow_alpha_light":
                try {
                    String s = v.trim();
                    int a = (s.startsWith("0x") || s.startsWith("0X"))
                            ? (int) Long.parseLong(s.substring(2), 16)
                            : Integer.parseInt(s);
                    if (a >= 0 && a <= 0xFF) IME_GLOW_ALPHA_LIGHT = a;
                } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
                break;
            case "ime_glow_feather":
            case "ime_glow_feather_dp":
                try {
                    float f = Float.parseFloat(v.trim());
                    if (f >= 0f && f <= 40f) IME_GLOW_FEATHER_DP = f;
                } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
                break;
            case "ime_glow_color_dark":
                try {
                    IME_GLOW_COLOR_DARK = (int) (Long.parseLong(v.trim(), 16) & 0xFFFFFFL);
                } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
                break;
            case "ime_glow_color_light":
                try {
                    IME_GLOW_COLOR_LIGHT = (int) (Long.parseLong(v.trim(), 16) & 0xFFFFFFL);
                } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
                break;
            case "ime_glow_corner":
            case "ime_glow_corner_dp":
                try {
                    float f = Float.parseFloat(v.trim());
                    if (f >= -1f && f <= 100f) IME_GLOW_CORNER_DP = f;
                } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
                break;
            case "launcher_label_shadow":
                LAUNCHER_LABEL_SHADOW = parseBool(v, LAUNCHER_LABEL_SHADOW);
                break;
            case "launcher_label_shadow_radius":
            case "launcher_label_shadow_radius_dp":
            case "launcher_label_shadow_dy":
            case "launcher_label_shadow_dy_dp":
                ModuleLog.d(TAG, "launcher_label_shadow radius/dy is fixed, ignore: " + k + "=" + v);
                break;
            case "launcher_label_shadow_dark":
                try { LAUNCHER_LABEL_SHADOW_DARK = (int) Long.parseLong(v.trim(), 16); } catch (Throwable ignored) { ModuleLog.swallow("FlagStore", ignored); }
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

}
