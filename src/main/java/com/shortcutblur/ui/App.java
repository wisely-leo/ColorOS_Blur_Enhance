package com.shortcutblur.ui;
import android.app.Application;
import android.content.Context;
public class App extends Application {
    private static App sApp;
    @Override
    public void onCreate() {
        super.onCreate();
        sApp = this;
        lg("=== App.onCreate (v45.5.1-ui) pid=" + android.os.Process.myPid() + " ===");
        try {
            lg("环境: Android " + android.os.Build.VERSION.RELEASE
                    + " (SDK " + android.os.Build.VERSION.SDK_INT + ")"
                    + " | " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                    + " | 进程=" + this.getPackageName());
        } catch (Throwable ignored) {}
    }
    public static Context ctx() {
        return sApp;
    }
    public static boolean logEnabled() {
        try {
            if (sApp == null) return false;
            return sApp.getSharedPreferences("blur_enhance", Context.MODE_PRIVATE)
                    .getBoolean("log", false);
        } catch (Throwable t) { return false; }
    }
    public static void lg(String s) {
        // 统合日志开关：与功能日志一致，未初始化(sApp==null)或开关关闭时均静默。
        if (!logEnabled()) return;
        try { android.util.Log.i("BlurUI", s); } catch (Throwable ignored) {}
        // 统一走 UiLog：持久 Writer + 2MB 轮转，避免每条日志 open/close 抖动与无限增长。
        UiLog.write(s);
    }
}
