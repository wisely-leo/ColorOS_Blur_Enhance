package com.shortcutblur;
public final class FeatureFlags {
    public static volatile boolean SHORTCUT_BLUR = true;

    public static volatile boolean RECENTS_BLUR = false;
    public static volatile boolean WIDGET_BLUR = true;
    /** 桌面卡片模糊（附属「时钟组件模糊」）：桌面 OPPO 卡片（耗电看板等）高斯模糊背景。
     *  注意：区别于 CARD_BLUR_*（那套是「快捷菜单卡片」弹窗模糊）。 */
    public static volatile boolean LAUNCHER_CARD_BLUR = true;
    public static volatile boolean QUICKSEARCH_BLUR = true;
    public static volatile boolean GALLERY_LIGHT = true;
    public static volatile boolean POSTEFFECT = true;
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

    // 键盘边缘「内发光」的强度（alpha，0x00~0xFF）。0 = 关闭发光。
    // 内发光 = 贴边最亮、向内渐隐（不是暗色内阴影），所以基色始终是亮色；
    // 深浅两种背景只调整强度：深色键盘上白线对比强 → alpha 取低一点避免"抢眼"；
    // 浅色键盘本身偏亮 → 白线需要更高 alpha 才看得出"发光"。
    public static volatile int IME_GLOW_ALPHA_DARK = 0x44;
    public static volatile int IME_GLOW_ALPHA_LIGHT = 0x60;
    /** 内发光基色（RGB，仅低 24 位有效）。默认纯白。 */
    public static volatile int IME_GLOW_COLOR_DARK = 0xFFFFFF;
    public static volatile int IME_GLOW_COLOR_LIGHT = 0xFFFFFF;
    /** 内发光带宽度（dp）：从边缘向内衰减完的总宽度。 */
    public static volatile float IME_GLOW_FEATHER_DP = 3f;
    /** 内发光跟随的圆角半径（dp）；< 0 表示与键盘自身的圆角保持一致。 */
    public static volatile float IME_GLOW_CORNER_DP = -1f;

    // ---- 桌面应用名阴影（常驻柔和阴影）----
    // 原生只在「暗壁纸 + 默认主题色」时给应用名挂 8px / 0x4d000000 的阴影，亮壁纸直接清空；
    // 这里改成常驻：亮色文字配暗色阴影，暗色文字配亮色阴影（保证可读）。
    public static volatile boolean LAUNCHER_LABEL_SHADOW = true;
    /** 阴影模糊半径（dp）。0 = 硬阴影。 */
    /** 阴影半径（dp）。已固定，不再开放调节。 */
    public static volatile float LAUNCHER_LABEL_SHADOW_RADIUS_DP = 4.0f;
    /** 阴影垂直偏移（dp，正值向下）。比原生多这一点点下坠感。 */
    /** 阴影向下偏移（dp）。已固定，不再开放调节。 */
    public static volatile float LAUNCHER_LABEL_SHADOW_DY_DP = 1.0f;
    /** 亮色文字时使用的阴影色（ARGB）。默认暗色。 */
    public static volatile int LAUNCHER_LABEL_SHADOW_DARK = 0x70000000;
    /** 暗色文字（亮壁纸）时使用的阴影色（ARGB）。 */

    // 快捷菜单（ShortcutBlur）模糊半径：图标模糊动画的目标半径。0=不模糊，上限 200。
    public static volatile float SHORTCUT_BLUR_RADIUS = 64.0f;

    // 快捷菜单弹窗本体（popup 背景视图）模糊：用 Oplus 私有模糊 API 对弹窗背景视图挂背景渲染效果。
    // 隶属于「Shortcut 实时模糊」(SHORTCUT_BLUR)：无独立开关，开了总开关即启用。
    // 半径 = SHORTCUT_WINDOW_BLUR_RADIUS。
    public static volatile float SHORTCUT_WINDOW_BLUR_RADIUS = 64.0f;

    // 快捷菜单卡片（一/二级）统一 Oplus 背景模糊（com.oplus.view.ViewRootManager）的混色。
    // 与输入法模糊同源，用同一套私有 API；一级与二级共用同组参数以保证观感一致。
    public static volatile int CARD_BLUR_COLOR_LIGHT = 0x66FFFFFF;
    public static volatile int CARD_BLUR_COLOR_DARK = 0x99000000;
    /** 卡片模糊圆角（dp）。直接决定模糊层圆角；<=0 时退回读取卡片面板自身圆角（COUI RoundFrameLayout）。 */
    public static volatile float CARD_BLUR_CORNER_DP = 16f;

    /**
     * 二级菜单（"更多功能"子菜单）背景模糊半径。独立于一级，可单独调大/调小。
     * <=0 时回退用 SHORTCUT_BLUR_RADIUS（一级半径）。
     */
    public static volatile float CARD_SECONDARY_BLUR_RADIUS = 96.0f;

    // 一级（长按图标）卡片面板背景的不透明度（0x00~0xFF）。
    // 面板背景本身多为浅色/白色，数值越高越"实"、越显白；降低可让模糊更透出来。
    public static volatile int CARD_PANEL_ALPHA = 0x99;

    // 键盘圆角固定 25dp，不再允许用户自定义。
    // 原因：系统 BackgroundBlurDrawable.setCornerRadius 在半径超过绘制区域
    // 短边一半时会触发 native 路径构建越界崩溃（本机实测阈值 ≈25dp@560dpi），
    // 且崩溃后必须 pm clear 输入法数据才能恢复（代价高）。
    // 固定为 25dp 以远离临界值。
    public static final float IME_BLUR_CORNER_DP = 25f;

    public static volatile int IME_BLUR_COLOR_LIGHT = 0xC0DCDCDC;

    public static volatile int IME_BLUR_COLOR_DARK = 0xB3000000;

    public static volatile float IME_BLUR_MASK_ALPHA = 1.0f;

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
            case "launcher_card_blur":
            case "launcher_card":
                LAUNCHER_CARD_BLUR = parseBool(v, LAUNCHER_CARD_BLUR);
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
            case "ime_blur":
            case "imeblur":
                IME_BLUR = parseBool(v, IME_BLUR);
                break;
            case "ime_blur_radius":
                try {
                    int r = Integer.parseInt(v.trim());
                    if (r >= 0 && r <= 500) IME_BLUR_RADIUS = r;
                } catch (Throwable ignored) {}
                break;
            case "shortcut_blur_radius":
                try {
                    float f = Float.parseFloat(v.trim());
                    if (f >= 0f && f <= 200f) SHORTCUT_BLUR_RADIUS = f;
                } catch (Throwable ignored) {}
                break;
            case "shortcut_window_blur_radius":
                try {
                    float f = Float.parseFloat(v.trim());
                    if (f >= 0f && f <= 200f) SHORTCUT_WINDOW_BLUR_RADIUS = f;
                } catch (Throwable ignored) {}
                break;
            case "card_panel_alpha":
                // 一级快捷菜单面板背景不透明度（0x00~0xFF）。数值越高越"实"、越显白。
                try {
                    String s = v.trim();
                    int a = (s.startsWith("0x") || s.startsWith("0X"))
                            ? (int) Long.parseLong(s.substring(2), 16)
                            : Integer.parseInt(s);
                    if (a >= 0 && a <= 0xFF) CARD_PANEL_ALPHA = a;
                } catch (Throwable ignored) {}
                break;
            case "card_secondary_blur_radius":
                // 二级（"更多功能"）菜单模糊半径；<=0 时回退一级半径。
                try {
                    float f = Float.parseFloat(v.trim());
                    CARD_SECONDARY_BLUR_RADIUS = f;
                } catch (Throwable ignored) {}
                break;
            case "card_blur_corner":
            case "card_blur_corner_dp":
                try {
                    float f = Float.parseFloat(v.trim());
                    if (f >= 0f && f <= 100f) CARD_BLUR_CORNER_DP = f;
                } catch (Throwable ignored) {}
                break;
            case "ime_blur_corner":
            case "ime_blur_corner_dp":
                // 圆角已固定为 25dp（IME_BLUR_CORNER_DP 为 final 常量），
                // 忽略外部传入值，防止越界崩溃。
                break;
            case "ime_blur_color_light":
                try { IME_BLUR_COLOR_LIGHT = (int) Long.parseLong(v.trim(), 16); } catch (Throwable ignored) {}
                break;
            case "ime_blur_color_dark":
                try { IME_BLUR_COLOR_DARK = (int) Long.parseLong(v.trim(), 16); } catch (Throwable ignored) {}
                break;
            case "ime_blur_mask":
            case "ime_blur_mask_alpha":
                try {
                    float f = Float.parseFloat(v.trim());
                    if (f >= 0f && f <= 1f) IME_BLUR_MASK_ALPHA = f;
                } catch (Throwable ignored) {}
                break;
            case "ime_glow_alpha_dark":
                try {
                    String s = v.trim();
                    int a = (s.startsWith("0x") || s.startsWith("0X"))
                            ? (int) Long.parseLong(s.substring(2), 16)
                            : Integer.parseInt(s);
                    if (a >= 0 && a <= 0xFF) IME_GLOW_ALPHA_DARK = a;
                } catch (Throwable ignored) {}
                break;
            case "ime_glow_alpha_light":
                try {
                    String s = v.trim();
                    int a = (s.startsWith("0x") || s.startsWith("0X"))
                            ? (int) Long.parseLong(s.substring(2), 16)
                            : Integer.parseInt(s);
                    if (a >= 0 && a <= 0xFF) IME_GLOW_ALPHA_LIGHT = a;
                } catch (Throwable ignored) {}
                break;
            case "ime_glow_feather":
            case "ime_glow_feather_dp":
                try {
                    float f = Float.parseFloat(v.trim());
                    if (f >= 0f && f <= 40f) IME_GLOW_FEATHER_DP = f;
                } catch (Throwable ignored) {}
                break;
            case "ime_glow_color_dark":
                try {
                    IME_GLOW_COLOR_DARK = (int) (Long.parseLong(v.trim(), 16) & 0xFFFFFFL);
                } catch (Throwable ignored) {}
                break;
            case "ime_glow_color_light":
                try {
                    IME_GLOW_COLOR_LIGHT = (int) (Long.parseLong(v.trim(), 16) & 0xFFFFFFL);
                } catch (Throwable ignored) {}
                break;
            case "ime_glow_corner":
            case "ime_glow_corner_dp":
                try {
                    float f = Float.parseFloat(v.trim());
                    if (f >= -1f && f <= 100f) IME_GLOW_CORNER_DP = f;
                } catch (Throwable ignored) {}
                break;
            case "launcher_label_shadow":
                LAUNCHER_LABEL_SHADOW = parseBool(v, LAUNCHER_LABEL_SHADOW);
                break;
            case "launcher_label_shadow_radius":
            case "launcher_label_shadow_radius_dp":
            case "launcher_label_shadow_dy":
            case "launcher_label_shadow_dy_dp":
                // 半径与向下偏移已固定为 4dp / 1dp，热更 key 保留但不再生效。
                ModuleLog.d(TAG, "launcher_label_shadow radius/dy is fixed, ignore: " + k + "=" + v);
                break;
            case "launcher_label_shadow_dark":
                try { LAUNCHER_LABEL_SHADOW_DARK = (int) Long.parseLong(v.trim(), 16); } catch (Throwable ignored) {}
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

            v = i.getStringExtra("ime_blur");
            if (v != null) { boolean old = IME_BLUR; applyKey("ime_blur", v); changed |= (old != IME_BLUR); }
            v = i.getStringExtra("ime_blur_radius");
            if (v != null) { int old = IME_BLUR_RADIUS; applyKey("ime_blur_radius", v); changed |= (old != IME_BLUR_RADIUS); }
            v = i.getStringExtra("ime_blur_corner");
            if (v != null) { applyKey("ime_blur_corner", v); }
            v = i.getStringExtra("ime_blur_color_light");
            if (v != null) { int old = IME_BLUR_COLOR_LIGHT; applyKey("ime_blur_color_light", v); changed |= (old != IME_BLUR_COLOR_LIGHT); }
            v = i.getStringExtra("ime_blur_color_dark");
            if (v != null) { int old = IME_BLUR_COLOR_DARK; applyKey("ime_blur_color_dark", v); changed |= (old != IME_BLUR_COLOR_DARK); }
            v = i.getStringExtra("ime_blur_mask");
            if (v != null) { float old = IME_BLUR_MASK_ALPHA; applyKey("ime_blur_mask", v); changed |= (old != IME_BLUR_MASK_ALPHA); }
            v = i.getStringExtra("ime_glow_alpha_dark");
            if (v != null) { int old = IME_GLOW_ALPHA_DARK; applyKey("ime_glow_alpha_dark", v); changed |= (old != IME_GLOW_ALPHA_DARK); }
            v = i.getStringExtra("ime_glow_alpha_light");
            if (v != null) { int old = IME_GLOW_ALPHA_LIGHT; applyKey("ime_glow_alpha_light", v); changed |= (old != IME_GLOW_ALPHA_LIGHT); }
            v = i.getStringExtra("ime_glow_feather_dp");
            if (v == null) v = i.getStringExtra("ime_glow_feather");
            if (v != null) { float old = IME_GLOW_FEATHER_DP; applyKey("ime_glow_feather_dp", v); changed |= (old != IME_GLOW_FEATHER_DP); }
            v = i.getStringExtra("ime_glow_color_dark");
            if (v != null) { int old = IME_GLOW_COLOR_DARK; applyKey("ime_glow_color_dark", v); changed |= (old != IME_GLOW_COLOR_DARK); }
            v = i.getStringExtra("ime_glow_color_light");
            if (v != null) { int old = IME_GLOW_COLOR_LIGHT; applyKey("ime_glow_color_light", v); changed |= (old != IME_GLOW_COLOR_LIGHT); }
            v = i.getStringExtra("ime_glow_corner_dp");
            if (v == null) v = i.getStringExtra("ime_glow_corner");
            if (v != null) { float old = IME_GLOW_CORNER_DP; applyKey("ime_glow_corner_dp", v); changed |= (old != IME_GLOW_CORNER_DP); }
            v = i.getStringExtra("launcher_label_shadow");
            if (v != null) { boolean old = LAUNCHER_LABEL_SHADOW; applyKey("launcher_label_shadow", v); changed |= (old != LAUNCHER_LABEL_SHADOW); }
            // launcher_label_shadow_radius_dp / _dy_dp 已固定为 4dp / 1dp，不再从广播接收。
            v = i.getStringExtra("launcher_label_shadow_dark");
            if (v != null) { int old = LAUNCHER_LABEL_SHADOW_DARK; applyKey("launcher_label_shadow_dark", v); changed |= (old != LAUNCHER_LABEL_SHADOW_DARK); }
            v = i.getStringExtra("shortcut_blur_radius");
            if (v != null) { float old = SHORTCUT_BLUR_RADIUS; applyKey("shortcut_blur_radius", v); changed |= (old != SHORTCUT_BLUR_RADIUS); }
            v = i.getStringExtra("shortcut_window_blur_radius");
            if (v != null) { float old = SHORTCUT_WINDOW_BLUR_RADIUS; applyKey("shortcut_window_blur_radius", v); changed |= (old != SHORTCUT_WINDOW_BLUR_RADIUS); }
            v = i.getStringExtra("card_panel_alpha");
            if (v != null) { int old = CARD_PANEL_ALPHA; applyKey("card_panel_alpha", v); changed |= (old != CARD_PANEL_ALPHA); }
            v = i.getStringExtra("card_secondary_blur_radius");
            if (v != null) { float old = CARD_SECONDARY_BLUR_RADIUS; applyKey("card_secondary_blur_radius", v); changed |= (old != CARD_SECONDARY_BLUR_RADIUS); }
            v = i.getStringExtra("card_blur_corner");
            if (v != null) { float old = CARD_BLUR_CORNER_DP; applyKey("card_blur_corner", v); changed |= (old != CARD_BLUR_CORNER_DP); }
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
