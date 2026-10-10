package com.shortcutblur.ui;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;

/**
 * UI 侧（设置页进程）的日志输出。
 *
 * 背景：原先 App.lg / SettingsActivity.lg 各自实现了一份「每条日志都
 * 探测目录 → open FileOutputStream → write → flush → close」的逻辑。
 * 开启日志后，设置页大量交互（点击、滚动、滑块）会触发频繁文件 IO 抖动，
 * 且写入的 UiStartup.log 只增不减，长时间使用会持续膨胀。
 *
 * 这里统一为：持久 BufferedWriter（只开一次）+ 2MB 轮转截断重开，
 * 与模块内 ModuleLog 的策略保持一致。
 */
final class UiLog {

    private static final long MAX_LOG_BYTES = 2L * 1024 * 1024;
    private static final String FILE_NAME = "UiStartup.log";

    private static BufferedWriter sWriter = null;
    private static long sBytes = 0L;
    private static String sDir = null;

    private UiLog() {}

    private static String resolveDir() {
        for (String d : new String[]{
                "/storage/emulated/0/Download",
                "/sdcard/Download",
                "/storage/emulated/0"}) {
            try {
                File dir = new File(d);
                if (dir.exists() && dir.canWrite()) return d;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static boolean openLocked(String dir) {
        try {
            File f = new File(dir, FILE_NAME);
            sBytes = f.exists() ? f.length() : 0L;
            boolean append = sBytes <= MAX_LOG_BYTES;
            FileOutputStream fo = new FileOutputStream(f, append);
            if (!append) sBytes = 0L;
            sWriter = new BufferedWriter(new OutputStreamWriter(fo, "UTF-8"));
            return true;
        } catch (Throwable t) {
            sWriter = null;
            return false;
        }
    }

    static synchronized void write(String s) {
        if (s == null) return;
        try {
            if (sWriter == null) {
                if (sDir == null) {
                    sDir = resolveDir();
                    if (sDir == null) return;
                }
                if (!openLocked(sDir)) return;
            }
            if (sBytes > MAX_LOG_BYTES) {
                try { sWriter.close(); } catch (Throwable ignored) {}
                sWriter = null;
                if (!openLocked(sDir)) return;
                sBytes = 0L;
            }
            String line = s + "\n";
            sWriter.write(line);
            sWriter.flush();
            sBytes += line.length();
        } catch (Throwable t) {
            try { sWriter.close(); } catch (Throwable ignored) {}
            sWriter = null;
        }
    }
}
