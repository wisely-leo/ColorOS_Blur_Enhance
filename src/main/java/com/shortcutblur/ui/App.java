package com.shortcutblur.ui;
import android.app.Application;
import android.content.Context;
public class App extends Application {
    private static App sApp;
    @Override
    public void onCreate() {
        super.onCreate();
        sApp = this;
        lg("=== App.onCreate (v42.5-ui) pid=" + android.os.Process.myPid() + " ===");
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
        if (sApp != null && !logEnabled()) return;
        android.util.Log.i("BlurUI", s);
        // 统一走 UiLog：持久 Writer + 2MB 轮转，避免每条日志 open/close 抖动与无限增长。
        UiLog.write(s);
    }
}
