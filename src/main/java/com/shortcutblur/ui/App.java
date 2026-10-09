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
