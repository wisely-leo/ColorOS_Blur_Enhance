package com.shortcutblur.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * 配置读写层 —— UI 与 hook 的解耦桥梁。
 *
 * 可扩展性设计：
 *   - UI 只跟本类打交道，不直接碰 XSharedPreferences / 广播
 *   - 以后换存储方式（SP → 文件 → XSP），只改这一个文件
 *   - 以后加设置项，在这里加一对 getter/setter 即可
 *
 * 注意：当前用普通 SharedPreferences 占位；
 *      若要与 hook 侧共享，后续替换为 XSharedPreferences（需 lsp 的 prefs 目录）。
 */
public final class SettingsStore {

    private static final String PREF = "blur_enhance";
    // ---- 4 个功能开关（与宿主 FeatureFlags 的 key 完全对应）----
    private static final String KEY_SHORTCUT    = "shortcut_blur";
    private static final String KEY_RECENTS     = "recents_blur";
    private static final String KEY_WIDGET      = "widget_blur";
    private static final String KEY_POSTEFFECT  = "posteffect";
    private static final String KEY_SAMPLE_SCALE= "sample_scale";
    /** 自定义背景图（应用私有目录下的文件名，空 = 未设置） */
    private static final String KEY_BG_FILE     = "bg_file";
    private static final String KEY_LOG         = "log";
    /** 【下拉搜索实时模糊】全局搜索背景透明化（默认开）。 */
    private static final String KEY_QS_PROBE    = "quicksearch_blur";

    private final SharedPreferences sp;
    /** 持有上下文（appName 等需要用它读模块信息）。 */
    private final Context ctx;

    public SettingsStore(Context ctx) {
        this.ctx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        this.sp = this.ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ---- 默认值 ----
    public static final boolean DEF_SHORTCUT   = true;
    public static final boolean DEF_RECENTS    = true;
    public static final boolean DEF_WIDGET     = true;
    public static final boolean DEF_POSTEFFECT = true;
    public static final float   DEF_SAMPLE_SCALE = 0.5f;
    public static final boolean DEF_LOG        = false;
    /** 【下拉搜索实时模糊】默认开。 */
    public static final boolean DEF_QS_PROBE   = true;

    // ==================== 同步层（UI -> 宿主）====================

    /**
     * 目标宿主包（与 META-INF/xposed/scope.list 保持一致）。
     *
     * 广播会分别发往这三个包；宿主侧若有接收端就能收到。
     * 注意：外发广播需要包可见性，故逐个显式 setPackage()。
     */
    private static final String[] HOST_PKGS = {
            "com.coloros.alarmclock",
            "com.android.launcher",
            "com.oplus.blur",
            "com.heytap.quicksearchbox",
    };

    /** 与宿主 BlurEnhanceModule.ACTION_SETCONF 必须完全一致。 */
    public static final String ACTION_SETCONF = "com.wiselyleo.blurenhance.SETCONF";

    /** 持久化文件（与宿主 FeatureFlags.CONF_PATH 必须完全一致）。 */

    /** 公共目录里的配置（宿主真正读的那个；由 shell 代写）。 */
    private static final String PUBLIC_DIR = "/data/local/tmp/ColorOSBlurEnhance";
    private static final String PUBLIC_PATH = PUBLIC_DIR + "/blur.conf";

    private final android.os.Handler syncHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable syncTask = new Runnable() {
        @Override public void run() { doSync(); }
    };

    /**
     * 请求同步（幂等防抖）。
     *
     * 同一批连续 setter（链式调用）只会真正执行一次，
     * 避免“连发多条广播 + 多次写盘”。
     */
    private void syncToHost() {
        syncHandler.removeCallbacks(syncTask);
        syncHandler.post(syncTask);
    }

    /** 真正的同步动作：写文件（持久） + 发广播（热更新）。 */
    private void doSync() {
        sendShizukuWrite();            // shell 代写 /data/local/tmp/.../blur.conf（chmod 644，宿主可读）
        int n = sendConfBroadcast();   // 广播（热更新，可选）
        App.lg("[sync] broadcast=" + n + "/" + HOST_PKGS.length + " pkgs"
                + "  " + snapshot().replace("\n", " "));
    }

    /**
     * 让 Shizuku(shell, uid=2000) 把配置写到 /data/local/tmp（世界可读），供宿主读取。
     *
     * 背景（实测结论，勿改）：
     *   - UI 写自己的 Android/data/ 、UI 私有目录 -> 宿主(FUSE/uid 隔离) 都读不到；
     *   - UI 直接写 /sdcard/Download/ -> 普通 app 无权限；写 /data/local/tmp 也无权限；
     *   - shell(uid=2000) 能写 /data/local/tmp，写完后 chmod 644 + chown shell:shell，
     *     文件即"世界可读"，任意 uid（含各宿主进程）都能读 —— 这是当前唯一验证可行的通道。
     * 故：UI 把内容 base64 后交给 shell 代写并改权限。
     *
     * 注意：Adb.run 是同步阻塞的，必须放后台线程，不能卡 UI。
     */
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

    // ---------------- ① 持久层：写外部文件 ----------------

    /** 当前开关的文本快照（与宿主 FeatureFlags.loadFromFile 的解析协议严格对应）。 */
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

    

    

    // ---------------- ② 热更新层：发广播 ----------------

    /** 把全部功能开关作为 extra 播给宿主（值用 1/0 字符串）。 */
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

    // ---- getters ----
    public boolean isShortcut()    { return sp.getBoolean(KEY_SHORTCUT, DEF_SHORTCUT); }
    public boolean isRecents()     { return sp.getBoolean(KEY_RECENTS, DEF_RECENTS); }
    public boolean isWidget()      { return sp.getBoolean(KEY_WIDGET, DEF_WIDGET); }
    public boolean isPostEffect()  { return sp.getBoolean(KEY_POSTEFFECT, DEF_POSTEFFECT); }
    public float   getSampleScale(){ return sp.getFloat(KEY_SAMPLE_SCALE, DEF_SAMPLE_SCALE); }
    public boolean isLog()         { return sp.getBoolean(KEY_LOG, DEF_LOG); }
    /** 【Test·验证】全局搜索背景透明化探针。 */
    public boolean isQsProbe()     { return sp.getBoolean(KEY_QS_PROBE, DEF_QS_PROBE); }

    // ---- setters（统一 apply，返回 this 便于链式）----
    public SettingsStore setShortcut(boolean v)   { sp.edit().putBoolean(KEY_SHORTCUT, v).apply(); syncToHost(); return this; }
    public SettingsStore setRecents(boolean v)    { sp.edit().putBoolean(KEY_RECENTS, v).apply(); syncToHost(); return this; }
    public SettingsStore setWidget(boolean v)     { sp.edit().putBoolean(KEY_WIDGET, v).apply(); syncToHost(); return this; }
    public SettingsStore setPostEffect(boolean v) { sp.edit().putBoolean(KEY_POSTEFFECT, v).apply(); syncToHost(); return this; }
    public SettingsStore setSampleScale(float v)  { sp.edit().putFloat(KEY_SAMPLE_SCALE, v).apply(); syncToHost(); return this; }
    public SettingsStore setLog(boolean v)        { sp.edit().putBoolean(KEY_LOG, v).apply(); syncToHost(); return this; }
    /** 【Test·验证】全局搜索背景透明化探针。 */
    public SettingsStore setQsProbe(boolean v)    { sp.edit().putBoolean(KEY_QS_PROBE, v).apply(); syncToHost(); return this; }


    /**
     * 模块名：优先从 module.prop 的 name= 读取（LSPosed 会把它放到模块路径）。
     * 回退顺序：module.prop -> strings 里的 app_name -> 常量。
     * 不硬编码，改 module.prop 一处即可全界面生效。
     */
    public String appName() {
        // 1) 常见模块目录（LSPosed / KernelSU / Magisk）
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
        // 2) 回退：应用自身的 label
        try {
            android.content.pm.ApplicationInfo ai = ctx.getApplicationInfo();
            CharSequence cs = ctx.getPackageManager().getApplicationLabel(ai);
            if (cs != null && cs.length() > 0) return cs.toString();
        } catch (Throwable ignored) {}
        // 3) 最后兜底
        return "ColorOS Blur Enhance";
    }

    /** 通用：从 module.prop 读某个 key 的值（如 version / author）。 */
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

    /** 背景图文件名（空 = 未设置）。 */
    public String getBgFile() { return sp.getString(KEY_BG_FILE, ""); }
    public SettingsStore setBgFile(String name) {
        sp.edit().putString(KEY_BG_FILE, name == null ? "" : name).apply();
        return this;
    }
    /** 背景图文件的完整路径（供读取）。 */
    public java.io.File bgFile() {
        String n = getBgFile();
        if (n.length() == 0) return null;
        return new java.io.File(ctx.getFilesDir(), n);
    }
    /** 清除背景图：删除私有副本 + 清空记录（恢复纯色背景）。 */
    public SettingsStore clearBg() {
        try {
            java.io.File f = bgFile();
            if (f != null && f.exists()) f.delete();
        } catch (Throwable ignored) {}
        return setBgFile("");
    }

    /** 背景图是否已设置。 */
    public boolean hasBg() {
        java.io.File f = bgFile();
        return f != null && f.exists() && f.length() > 0;
    }

    /**
     * 模块作用域（scope）的真值来源 = 本 APK 内的 META-INF/xposed/scope.list。
     *
     * 依据：模块使用 libxposed 新规范（module.prop minApiVersion=102），
     *       作用域不再是清单 meta-data「xposedscope」（旧规范），
     *       而是编译时打包进 APK 的 META-INF/xposed/scope.list。
     *
     * 读 APK 自身 assets 即可拿到，无需任何权限、无需 /data/adb 可读。
     * 想改作用域 -> 改 meta/META-INF/xposed/scope.list 后重打包（改一处生效）。
     */
    public java.util.List<String> scopePackages() {
        java.util.List<String> out = new java.util.ArrayList<>();
        // 真值来源：本 APK 内 META-INF/xposed/scope.list（libxposed 新规范）。
        // 注意：META-INF/ 不是 assets，必须当成 zip 条目读自己 APK。
        java.util.zip.ZipFile zip = null;
        java.io.InputStream in = null;
        try {
            String apk = ctx.getApplicationInfo().sourceDir;   // /data/app/.../base.apk
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

    /**
     * 版本号（展示用）。
     * 1) 优先：本 APK 的 versionName（形如 "v42.5"，由 AndroidManifest 统一维护）
     * 2) 其次：module.prop 的 version 字符串
     * 3) 兜底：versionCode
     */
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
    /** 【新增】读取本 APK 的 versionCode（用于「检查更新」版本比较）。 */
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

    /** 包名 -> 应用友好名（拿不到就回退包名）。用于作用域列表展示。 */
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