package com.shortcutblur.ui;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IBinder;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
public final class Adb {
    public static final int NONE = 0;
    public static final int NO_PERM = 1;
    public static final int OK = 2;
    private Adb() {}
    public static boolean alive() {
        try {
            return rikka.shizuku.Shizuku.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }
    public static int status() {
        try {
            if (alive()) {
                return rikka.shizuku.Shizuku.checkSelfPermission()
                        == PackageManager.PERMISSION_GRANTED ? OK : NO_PERM;
            }
        } catch (Throwable ignored) {}
        return NONE;
    }
    public static String label() {
        int st = status();
        if (st == OK) {
            int uid = -1;
            try { uid = rikka.shizuku.Shizuku.getUid(); } catch (Throwable ignored) {}
            return "已授权" + (uid >= 0 ? " · uid=" + uid : "");
        }
        if (st == NO_PERM) return "未授权 · 点此申请";
        return "未连接（请先启动 Shizuku）";
    }

    private static volatile rikka.shizuku.Shizuku.OnRequestPermissionResultListener sPendingListener = null;

    public static void removePending() {
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener l = sPendingListener;
        sPendingListener = null;
        if (l != null) {
            try { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(l); } catch (Throwable ignored) {}
        }
    }

    public static void request(final Activity a, final Runnable done) {
        try {
            if (!alive()) {
                SettingsActivity.lg("申请 ADB 权限：Shizuku 未连接（" + label() + "）");
                if (done != null) a.runOnUiThread(done);
                return;
            }

            removePending();
            final rikka.shizuku.Shizuku.OnRequestPermissionResultListener l =
                    new rikka.shizuku.Shizuku.OnRequestPermissionResultListener() {
                        @Override public void onRequestPermissionResult(int requestCode, int grantResult) {
                            try {
                                rikka.shizuku.Shizuku.removeRequestPermissionResultListener(this);
                            } catch (Throwable ignored) {}
                            if (sPendingListener == this) sPendingListener = null;
                            SettingsActivity.lg("ADB 权限回调: requestCode=" + requestCode
                                    + " grantResult=" + grantResult);

                            if (done != null) {
                                try {
                                    if (!a.isFinishing() && !a.isDestroyed()) {
                                        a.runOnUiThread(done);
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    };
            sPendingListener = l;
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(l);
            SettingsActivity.lg("申请 ADB 权限…");
            rikka.shizuku.Shizuku.requestPermission(0);
        } catch (Throwable t) {
            SettingsActivity.lg("申请 ADB 权限失败: " + t);
            if (done != null) a.runOnUiThread(done);
        }
    }
    public static String restartScope(Context c, List<String> pkgs) {
        if (pkgs == null || pkgs.isEmpty()) return "!作用域为空";
        String self = (c == null) ? null : c.getPackageName();
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) {
            if (p == null || p.length() == 0) continue;
            if (self != null && self.equals(p)) {
                SettingsActivity.lg("restartScope: skip self " + p);
                continue;
            }
            sb.append("am force-stop ").append(p).append("; ");
        }
        if (sb.length() == 0) return "!过滤后为空";
        String out = run(c, sb.toString());
        SettingsActivity.lg("重启作用域 " + pkgs + " -> " + out);
        return out;
    }
    public static String run(Context c, String cmd) {
        if (status() != OK) return "!没有 ADB 权限（未连接或未授权）";
        SettingsActivity.lg("ADB 执行: " + cmd
                + " | thread=" + Thread.currentThread().getName()
                + " | 主线程=" + (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()));
        InputStream is = null;
        InputStream es = null;
        try {
            IBinder binder = rikka.shizuku.Shizuku.getBinder();
            if (binder == null) return "!Shizuku binder 为空";
            moe.shizuku.server.IShizukuService svc =
                    moe.shizuku.server.IShizukuService.Stub.asInterface(binder);
            if (svc == null) return "!拿不到 IShizukuService";
            moe.shizuku.server.IRemoteProcess proc = svc.newProcess(
                    new String[]{"/system/bin/sh", "-c", cmd}, null, null);
            if (proc == null) return "!newProcess 返回空";
            SettingsActivity.lg("ADB newProcess 已启动: uid=" + svc.getUid());
            is = new FileInputStream(proc.getInputStream().getFileDescriptor());
            es = new FileInputStream(proc.getErrorStream().getFileDescriptor());
            String out = readAll(is);
            String err = readAll(es);
            int rc = proc.waitFor();
            StringBuilder sb = new StringBuilder();
            sb.append("[rc=").append(rc).append("]");
            if (out != null && out.trim().length() > 0) sb.append(" ").append(out.trim());
            if (err != null && err.trim().length() > 0) sb.append(" | err: ").append(err.trim());
            return sb.toString();
        } catch (Throwable t) {
            return "!newProcess 异常: " + t;
        } finally {
            try { if (is != null) is.close(); } catch (Throwable ignored) {}
            try { if (es != null) es.close(); } catch (Throwable ignored) {}
        }
    }
    private static String readAll(InputStream in) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }
}
