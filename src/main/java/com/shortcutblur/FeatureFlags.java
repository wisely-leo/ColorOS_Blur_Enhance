package com.shortcutblur;
public final class FeatureFlags {
    public static volatile boolean SHORTCUT_BLUR = true;

    public static volatile boolean RECENTS_BLUR = false;
    public static volatile boolean WIDGET_BLUR = true;
    public static volatile boolean LAUNCHER_CARD_BLUR = true;
    public static volatile boolean QUICKSEARCH_BLUR = true;
    public static volatile boolean GALLERY_LIGHT = true;
    public static volatile boolean POSTEFFECT = true;
    public static volatile boolean HOME_ROTATE = true;
    public static volatile boolean HOME_ROTATE_ICON = true;
    public static volatile float SAMPLE_SCALE = 0.5f;
    public static volatile boolean LOG_ENABLED = false;

    public static volatile float CLOCK_ICON_ALPHA = 0.30f;

    public static volatile float CLOCK_TEXT_ALPHA = 0.30f;

    public static volatile float CLOCK_BRIGHTEN = 1.25f;

    public static volatile boolean CLOCK_GLASS = false;

    public static volatile int CLOCK_GLASS_BLEND = 0;

    public static volatile int CLOCK_GLASS_MIX = 0;

    public static volatile boolean IME_BLUR = false;
    public static volatile int IME_BLUR_RADIUS = 100;

    public static volatile int IME_GLOW_ALPHA_DARK = 0x44;
    public static volatile int IME_GLOW_ALPHA_LIGHT = 0x60;
    public static volatile int IME_GLOW_COLOR_DARK = 0xFFFFFF;
    public static volatile int IME_GLOW_COLOR_LIGHT = 0xFFFFFF;
    public static volatile float IME_GLOW_FEATHER_DP = 3f;
    public static volatile float IME_GLOW_CORNER_DP = -1f;

    public static volatile boolean LAUNCHER_LABEL_SHADOW = true;
    public static volatile float LAUNCHER_LABEL_SHADOW_RADIUS_DP = 4.0f;
    public static volatile float LAUNCHER_LABEL_SHADOW_DY_DP = 1.0f;
    public static volatile int LAUNCHER_LABEL_SHADOW_DARK = 0x70000000;

    public static volatile float SHORTCUT_BLUR_RADIUS = 64.0f;

    public static volatile float SHORTCUT_WINDOW_BLUR_RADIUS = 64.0f;

    public static volatile int CARD_BLUR_COLOR_LIGHT = 0x66FFFFFF;
    public static volatile int CARD_BLUR_COLOR_DARK = 0x99000000;
    public static volatile float CARD_BLUR_CORNER_DP = 16f;

    public static volatile float CARD_SECONDARY_BLUR_RADIUS = 96.0f;

    public static volatile int CARD_PANEL_ALPHA = 0x99;

    public static final float IME_BLUR_CORNER_DP = 25f;

    public static volatile int IME_BLUR_COLOR_LIGHT = 0xC0DCDCDC;

    public static volatile int IME_BLUR_COLOR_DARK = 0xB3000000;

    public static volatile float IME_BLUR_MASK_ALPHA = 1.0f;

    public static final String CONF_PATH = "/data/local/tmp/ColorOSBlurEnhance/blur.conf";
    private static volatile boolean sLoaded = false;
    private static final String TAG = "FLAGS";
    private FeatureFlags() {}
    public static void load() {
        FlagStore.load();
    }

    public static void applyKey(String k, String v) {
        FlagStore.applyKey(k, v);
    }

    public static boolean applyFromIntent(android.content.Intent i) {
        if (i == null) return false;
        try {
            android.os.Bundle b = i.getExtras();
            if (b == null) return false;
            String before = summary();
            for (String k : b.keySet()) {
                if (k == null) continue;
                if ("mark".equals(k) || "verbose".equals(k)) continue;
                Object v = b.get(k);
                if (v == null) continue;
                applyKey(k, String.valueOf(v));
            }
            boolean changed = !before.equals(summary());
            if (changed) ModuleLog.d(TAG, "live conf -> " + summary());
            return changed;
        } catch (Throwable t) {
            ModuleLog.e(TAG, "applyFromIntent failed", t);
            return false;
        }
    }
    public static String summary() {
        return "shortcut=" + SHORTCUT_BLUR
                + " recents=" + RECENTS_BLUR
                + " widget=" + WIDGET_BLUR
                + " card=" + LAUNCHER_CARD_BLUR
                + " posteffect=" + POSTEFFECT
                + " qsBlur=" + QUICKSEARCH_BLUR
                + " sampleScale=" + SAMPLE_SCALE
                + " log=" + LOG_ENABLED
                + " clockIconA=" + CLOCK_ICON_ALPHA
                + " clockTextA=" + CLOCK_TEXT_ALPHA
                + " clockBright=" + CLOCK_BRIGHTEN
                + " clockGlass=" + CLOCK_GLASS
                + " imeBlur=" + IME_BLUR
                + " imeRadius=" + IME_BLUR_RADIUS
                + " imeCorner=" + IME_BLUR_CORNER_DP
                + " shortcutRadius=" + SHORTCUT_BLUR_RADIUS
                + " scWindowBlurR=" + SHORTCUT_WINDOW_BLUR_RADIUS
                + " cardAlpha=0x" + Integer.toHexString(CARD_PANEL_ALPHA)
                + " cardSecRadius=" + CARD_SECONDARY_BLUR_RADIUS
                + " cardCornerDp=" + CARD_BLUR_CORNER_DP;
    }
}
