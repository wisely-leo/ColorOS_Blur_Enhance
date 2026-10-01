package com.shortcutblur.ui;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;


/**
 * 应用级入口。
 *
 * 职责：
 *   1) UI 侧统一日志出口 lg()（Logcat + /sdcard/Download/UiStartup.log）
 *   2) 提供 UI 本地 SharedPreferences（prefs()）——只做"UI 自己的记忆"
 *
 * 配置如何到达宿主（本项目的核心结论，勿再走弯路）：
 *   框架通道（XposedProvider / RemotePreferences / 世界可读 prefs）在本机【全部不通】，
 *   实测：UI 拿不到 [svc] BOUND；hook 读 getRemotePreferences 恒为空；
 *        hook 进程看不到 UI 的 shared_prefs 文件。
 *
 *   最终方案（已被日志验证闭环）：
 *     ① UI 用 Shizuku(shell, uid=2000) 把配置写到
 *          /data/local/tmp/ColorOSBlurEnhance/blur.conf
 *        并 chmod 644 + chown shell:shell（=> 世界可读，任何 uid 都能读）；
 *     ② 宿主进程重启 / hook 注入时，BlurConfig.loadFromFile() 直接读这个文件；
 *     ③ （可选）同时发广播，供"不重启也想立刻生效"的热更新场景。
 */
public class App extends Application {

    /** UI 侧本地 SharedPreferences 名（只记 UI 自己的状态；不是与 hook 共享的通道）。 */
    public static final String REMOTE_GROUP = "blur_conf";
    /** 应用单例（供静态 lg() 读取日志开关）。 */
    private static App sApp;

    @Override
    public void onCreate() {
        super.onCreate();
        sApp = this;
        lg("=== App.onCreate (v42.5-ui) pid=" + android.os.Process.myPid() + " ===");
    }

    /** UI 本地 SharedPreferences。 */
    public static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(REMOTE_GROUP, Context.MODE_PRIVATE);
    }

    /** 读 GUI 本地日志开关（KEY_LOG=log，默认 false）。 */
    public static boolean logEnabled() {
        try {
            if (sApp == null) return false;
            return sApp.getSharedPreferences("blur_enhance", Context.MODE_PRIVATE)
                    .getBoolean("log", false);
        } catch (Throwable t) { return false; }
    }

    /** UI 侧统一日志：Logcat + UiStartup.log。 */
    public static void lg(String s) {
        // 统一日志开关：默认关；读 GUI 本地 prefs（无 App 实例时保守放行一次初始化）
        if (sApp != null && !logEnabled()) return;
        android.util.Log.i("BlurUI", s);
        try {
            java.io.File dir = new java.io.File("/storage/emulated/0/Download");
            if (dir.exists() && dir.canWrite()) {
                java.io.FileOutputStream fo =
                        new java.io.FileOutputStream(new java.io.File(dir, "UiStartup.log"), true);
                java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(fo, "UTF-8");
                w.write(s);
                w.write((char) 10);
                w.flush();
                w.close();
            }
        } catch (Throwable ignored) {}
    }
}
