package com.shortcutblur;

/**
 * 功能开关（运行期从配置读取，不是编译期常量）。
 *
 * 语义：关闭 = 对应功能的 hook 在装配阶段就完全跳过，该功能实现代码一行都不运行。
 * 生效时机：宿主进程启动时（onPackageReady 装配 hook 之前）读一次。改后需重启宿主进程。
 * 配置来源：文件 /data/local/tmp/ColorOSBlurEnhance/blur.conf（GUI 写入），其次 Settings.System。
 * 文件格式：key=1|0，# 开头为注释。
 *   shortcut_blur=1
 *   recents_blur=1
 *   widget_blur=1
 *   posteffect=1
 *   sample_scale=0.5
 * 缺省：任何键缺失/文件不存在 -> 保持开与默认值（行为同改造前）。
 */
public final class FeatureFlags {

    /** (1) shortcut 背景模糊替换（模块自实现）。 */
    public static volatile boolean SHORTCUT_BLUR = true;

    /** (2) recents 模糊实现。 */
    public static volatile boolean RECENTS_BLUR = true;

    /** (3) 组件模糊的全部实现（含时钟组件半透明）。 */
    public static volatile boolean WIDGET_BLUR = true;

    /**
     * (5) 【下拉搜索实时模糊】全局搜索（com.heytap.quicksearchbox）背景透明化。
     * 原理：进入来源为桌面时，把搜索页顶层背景置透明，透出下层桌面的实时模糊；
     *       其它来源（负一屏等）保持搜索默认背景模糊。
     * 默认开。关闭 = 不装 hook，该功能一行不跑。
     */
    public static volatile boolean QUICKSEARCH_BLUR = true;

    /** (4) 后处理（PostEffect）实现。关闭 = 不装后处理 hook，采样交给系统原值。 */
    public static volatile boolean POSTEFFECT = true;

    /** 后处理采样比例（0.0~1.0）。仅 POSTEFFECT 开启时作为强制采样值使用。 */
    public static volatile float SAMPLE_SCALE = 0.5f;

    /** 模块日志开关（运行期可调，默认关）。由 GUI 写入 log_enabled=0/1。 */
    public static volatile boolean LOG_ENABLED = false;

    /** 配置文件路径（与宿主跨进程共享）。 */
    public static final String CONF_PATH = "/data/local/tmp/ColorOSBlurEnhance/blur.conf";

    private static volatile boolean sLoaded = false;

    private static final String TAG = "FLAGS";

    private FeatureFlags() {}

    /** 装配前调用一次。幂等。 */
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

        try {
            String s = android.provider.Settings.System.getString(cr, "coloros_blur_feature_sample_scale");
            if (s != null) {
                float f = Float.parseFloat(s.trim());
                if (f >= 0.0f && f <= 1.0f) SAMPLE_SCALE = f;
            }
        } catch (Throwable ignored) {}
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

    private static void applyKey(String k, String v) {
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
            case "log_enabled":
            case "log":
                LOG_ENABLED = parseBool(v, LOG_ENABLED);
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

    /** 供日志用的一行摘要。 */
    public static String summary() {
        return "shortcut=" + SHORTCUT_BLUR
                + " recents=" + RECENTS_BLUR
                + " widget=" + WIDGET_BLUR
                + " posteffect=" + POSTEFFECT
                + " qsBlur=" + QUICKSEARCH_BLUR
                + " sampleScale=" + SAMPLE_SCALE
                + " log=" + LOG_ENABLED;
    }
}
