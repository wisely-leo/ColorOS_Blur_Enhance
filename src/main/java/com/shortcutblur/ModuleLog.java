package com.shortcutblur;
import android.os.Process;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
public final class ModuleLog {
    public static boolean enabled() { return FeatureFlags.LOG_ENABLED; }
    public static final String TAG = "colorosblurenhance";
    private static final long T0 = SystemClock.uptimeMillis();
    private static final String[] DIRS = {
            "/storage/emulated/0/Download",
            "/sdcard/Download",
            "/storage/emulated/0/Android/media",
            "/storage/emulated/0"
    };
    private static final String FILE = "ColorOSBlurEnhance.log";
    private static final String FILE_BLUR = "PostEffectBlur.log";
    private static final int UID_BLUR = 10205;
    private static File logFile;
    private static volatile boolean broken;
    private ModuleLog() {}
    public static volatile boolean VERBOSE = false;
    public static void dv(String category, String detail) {
        if (!VERBOSE) return;
        d(category, detail);
    }
    public static void d(String category, String detail) {
        if (!enabled()) return;
        long t = SystemClock.uptimeMillis() - T0;
        write("D|" + category + "|t=" + t + "|pid=" + Process.myPid() + "|" + detail + "\n");
    }
    public static void e(String category, String detail, Throwable t) {
        if (!enabled()) return;
        long ms = SystemClock.uptimeMillis() - T0;
        String extra = "";
        if (t != null) {
            extra = "|" + t.getClass().getSimpleName() + ":" + t.getMessage();
            try {
                StackTraceElement[] st = t.getStackTrace();
                if (st != null && st.length > 0) {
                    extra += "@" + st[0].getClassName() + "." + st[0].getMethodName()
                            + ":" + st[0].getLineNumber();
                }
            } catch (Throwable ignored) {}
        }
        write("E|" + category + "|t=" + ms + "|pid=" + Process.myPid() + "|" + detail + extra + "\n");
    }
    public static void i(String detail) {
        d("INFO", detail);
    }

    // 持久输出流：避免每条日志都 open/write/close（高频日志时是严重 IO 抖动）。
    // 用 BufferedWriter 聚合，按行 flush；超过 MAX_LOG_BYTES 后截断重开，防无限增长。
    private static final long MAX_LOG_BYTES = 2L * 1024 * 1024;
    private static Writer logWriter = null;
    private static long logBytes = 0L;

    private static synchronized void write(String s) {
        if (!enabled()) return;
        try {
            android.util.Log.i(TAG, s.trim());
        } catch (Throwable ignored) { }
        try {
            if (logFile == null) {
                logFile = open();
                if (logFile == null) { broken = true; return; }
                logBytes = logFile.length();
            }
            // 超限轮转：截断重开（单文件封顶，避免累积占满空间）
            if (logBytes > MAX_LOG_BYTES) {
                closeQuietly();
                try { new FileOutputStream(logFile, false).close(); } catch (Throwable ignore) {}
                logWriter = new java.io.BufferedWriter(
                        new java.io.OutputStreamWriter(new FileOutputStream(logFile, true), "UTF-8"), 8192);
                logBytes = 0L;
                rawLine("=== log rotated (>" + (MAX_LOG_BYTES / 1024 / 1024) + "MB) pid=" + Process.myPid() + " ===\n");
            }
            rawLine(s);
        } catch (Throwable t) {
            broken = true;
            closeQuietly();
            logFile = null;
            android.util.Log.w(TAG, "write failed: " + t);
        }
    }

    /** 走持久缓冲流写一行，不 close。 */
    private static void rawLine(String s) {
        try {
            if (logWriter == null) {
                if (logFile == null) return;
                logWriter = new java.io.BufferedWriter(
                        new java.io.OutputStreamWriter(new FileOutputStream(logFile, true), "UTF-8"), 8192);
            }
            logWriter.write(s);
            logWriter.flush();
            logBytes += s.length();
        } catch (Throwable t) {
            broken = true;
            closeQuietly();
        }
    }

    private static synchronized void closeQuietly() {
        if (logWriter != null) {
            try { logWriter.close(); } catch (Throwable ignored) {}
            logWriter = null;
        }
        if (logFile != null) {
            try { logFile = null; } catch (Throwable ignored) {}
        }
    }
    private static String pickFileName() {
        if (!enabled()) return FILE;
        try {
            if (Process.myUid() == UID_BLUR) return FILE_BLUR;
        } catch (Throwable ignored) {}

        int pid = -1;
        try { pid = Process.myPid(); } catch (Throwable ignored) {}
        if (pid > 0) {
            return "ColorOSBlurEnhance_p" + pid + ".log";
        }
        return FILE;
    }
    private static File open() {
        if (!enabled()) return null;
        for (String d : DIRS) {
            try {
                File dir = new File(d);
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, pickFileName());
                FileOutputStream fos = new FileOutputStream(f, false);
                fos.write(("=== ColorOSBlurEnhance log start uid=" + Process.myUid() + " ===\n").getBytes("UTF-8"));
                fos.close();
                android.util.Log.i(TAG, "log file opened: " + f.getAbsolutePath());
                return f;
            } catch (Throwable t) {
                android.util.Log.w(TAG, "open failed at " + d + ": " + t);
            }
        }
        android.util.Log.e(TAG, "NO writable log dir found");
        return null;
    }
}
