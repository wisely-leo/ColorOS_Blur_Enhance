package com.shortcutblur.ui;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IBinder;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;

/**
 * ADB 权限通道 —— **只用 Shizuku 的官方 API**，唯一用途是「重启作用域」。
 *
 * 三条硬规矩（按需求定死，别再自己加戏）：
 *   ★ 只用 rikka.shizuku.Shizuku.* 这一套 API，不碰 Sui、不碰 su、不碰 root。
 *   ★ 不检查"装没装 Shizuku"（用户可能用任意分支/版本，包名不可靠）——
 *     只看 binder 是否握手成功、权限是否授予。
 *   ★ 没有授权就是"没有通道"，不做任何降级尝试。
 *
 * 执行命令走 Shizuku 服务端的内部事务 {@code IShizukuService.newProcess()}：
 *   它同样以 shell 身份运行（Shizuku 服务本身就是 uid=2000），但不走 UserService
 *   生命周期 —— 不绑定、不升级、不重载 APK、同步返回。详见 run() 的注释。
 *
 * 注意：run() 是同步阻塞的，请放后台线程（本类不做线程切换）。
 */
public final class Adb {

    /** 没有可用通道（Shizuku 服务没在跑）。 */
    public static final int NONE = 0;
    /** 通道在，但权限没给。 */
    public static final int NO_PERM = 1;
    /** 已授权。 */
    public static final int OK = 2;

    private Adb() {}

    // -------------------- ① 检测 --------------------

    /** Shizuku 服务是否在跑（只看 binder 握手，不看包名）。 */
    public static boolean alive() {
        try {
            return rikka.shizuku.Shizuku.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 权限状态。 */
    public static int status() {
        try {
            if (alive()) {
                return rikka.shizuku.Shizuku.checkSelfPermission()
                        == PackageManager.PERMISSION_GRANTED ? OK : NO_PERM;
            }
        } catch (Throwable ignored) {}
        return NONE;
    }

    /** 行右侧的状态文字。 */
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

    // -------------------- ② 申请 --------------------

    /** 申请权限（必须先注册结果回调，Shizuku 11+ / 13 都是这个姿势）。 */
    public static void request(final Activity a, final Runnable done) {
        try {
            if (!alive()) {
                SettingsActivity.lg("申请 ADB 权限：Shizuku 未连接（" + label() + "）");
                if (done != null) a.runOnUiThread(done);
                return;
            }
            final rikka.shizuku.Shizuku.OnRequestPermissionResultListener l =
                    new rikka.shizuku.Shizuku.OnRequestPermissionResultListener() {
                        @Override public void onRequestPermissionResult(int requestCode, int grantResult) {
                            try {
                                rikka.shizuku.Shizuku.removeRequestPermissionResultListener(this);
                            } catch (Throwable ignored) {}
                            SettingsActivity.lg("ADB 权限回调: requestCode=" + requestCode
                                    + " grantResult=" + grantResult);
                            if (done != null) a.runOnUiThread(done);
                        }
                    };
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(l);
            SettingsActivity.lg("申请 ADB 权限…");
            rikka.shizuku.Shizuku.requestPermission(0);
        } catch (Throwable t) {
            SettingsActivity.lg("申请 ADB 权限失败: " + t);
            if (done != null) a.runOnUiThread(done);
        }
    }

    // -------------------- ③ 干活：重启作用域 --------------------

    /** 重启作用域：对每个目标包 am force-stop。返回结果串（'!' 开头 = 失败）。 */
    public static String restartScope(Context c, List<String> pkgs) {
        if (pkgs == null || pkgs.isEmpty()) return "!作用域为空";
        String self = (c == null) ? null : c.getPackageName();
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) {
            if (p == null || p.length() == 0) continue;
            // 永远不要 kill 自己（模块进程），否则 UI 会自杀重启
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

    /**
     * 通过 Shizuku 的 {@code IShizukuService.newProcess()} 以 shell 身份执行一条 sh 命令。
     *
     * ────────────────────────────────────────────────────────────────────────
     * 为什么不用 UserService（走过弯路，结论写在这里，别再改回去）：
     *
     *   用 bindUserService(UserServiceArgs) 时，Shizuku 每次绑定都会先：
     *     UserServiceManager: 停止旧服务以升级: <pkg>:...:adb
     *     UserServiceRecord : 停止服务
     *     UserServiceManager: 启动 UserService
     *   而"重新加载 UserService 实现类"需要重新读我们的 APK —— 我们的 APK 同时是
     *   LSPosed 模块，于是触发模块重载，连正在 await 的我们自己一起被杀，
     *   onServiceConnected 永远收不到 → 固定超时。daemon(true) / 换进程名都救不了
     *   （UserService 本来就是独立进程，冲突点在"升级重载 APK"这一步）。
     *
     *   绕开办法：直接用 Shizuku 服务端的内部事务 newProcess ——
     *   它同样以 shell 身份运行（Shizuku 服务本身就是 uid=2000），
     *   但不走 UserService 生命周期，不绑定、不升级、不重载、同步返回。
     *
     *   API 来源：dev.rikka.shizuku:aidl（moe.shizuku.server.*），
     *   真身取自 Shizuku.getBinder()。全程只用 Shizuku，不碰 Sui / su / root。
     * ────────────────────────────────────────────────────────────────────────
     *
     * 本方法是同步阻塞的（起进程 + 读输出 + 等退出）——勿在主线程调用。
     */
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

            // 以 shell 身份起进程：/system/bin/sh -c "<cmd>"
            moe.shizuku.server.IRemoteProcess proc = svc.newProcess(
                    new String[]{"/system/bin/sh", "-c", cmd}, null, null);
            if (proc == null) return "!newProcess 返回空";
            SettingsActivity.lg("ADB newProcess 已启动: uid=" + svc.getUid());

            // getInputStream/getErrorStream 返回 ParcelFileDescriptor
            is = new FileInputStream(proc.getInputStream().getFileDescriptor());
            es = new FileInputStream(proc.getErrorStream().getFileDescriptor());
            String out = readAll(is);
            String err = readAll(es);

            // ★ 不用 waitForTimeout：不同 Shizuku 版本它的签名会漂移
            //   （我们设备上实际是 (long, TimeUnit)，而 aidl.jar 里是 (long, String)，
            //     传字符串会抛 "No enum constant TimeUnit.xxx"）。
            //   改用 Signature 最稳定的无参 waitFor()：命令都是短命进程，直接等它退完。
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

    /** 把流读到字符串（不关流，由调用方关）。 */
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
