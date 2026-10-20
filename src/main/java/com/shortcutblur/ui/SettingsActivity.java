package com.shortcutblur.ui;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
public class SettingsActivity extends Activity {

    private static int hueToBaseColor(int hueDeg) {
        float h = ((hueDeg % 360) + 360) % 360;
        return android.graphics.Color.HSVToColor(new float[]{ h, 0.72f, 0.88f });
    }

    private static boolean sFadeOnCreate = false;

    private static final String EXTRA_FADE = "fade_in";

    private static int sTab = 0;
    private static int sScrollY = 0;
    private static final int MAX_BG_MB = 15;
    private static final long MAX_BG_BYTES = MAX_BG_MB * 1024L * 1024L;

    private static final String LOG_TAG_VER = "v47.2";
    static void lg(String s) {
        if (s == null) return;
        if (!App.logEnabled()) return;
        try { android.util.Log.i("SoftUi", s); } catch (Throwable ignored) {}
        UiLog.write(s);
    }
private SettingsStore store;
    private View shell;

    private android.view.ViewGroup uiRoot;

    private volatile boolean destroyed = false;

    @Override
    protected void onDestroy() {
        destroyed = true;
        try { SoftUi.clearBackdrop(); } catch (Throwable ignored) {}
        try { SoftUi.GlassSyncUnhook(); } catch (Throwable ignored) {}
        try { Adb.removePending(); } catch (Throwable ignored) {}
        adbGateShowing = false;
        shell = null;
        uiRoot = null;
        store = null;
        super.onDestroy();
    }

    private android.view.ViewGroup curRoot() {
        return destroyed ? null : uiRoot;
    }

    private void safePost(final Runnable r) {
        final android.view.ViewGroup root = curRoot();
        if (root == null || r == null) return;
        try {
            root.post(() -> {
                if (destroyed || uiRoot == null) return;
                try { r.run(); } catch (Throwable t) { lg("[safePost] 任务异常: " + t); }
            });
        } catch (Throwable ignored) {}
    }

    private boolean adbGateShowing = false;

    private void checkAdbGate() {
        if (destroyed) return;
        int st;
        try { st = Adb.status(); } catch (Throwable t) { st = Adb.NONE; }
        if (st == Adb.OK) { lg("[ADB门禁] 已授权，放行: " + Adb.label()); return; }
        lg("[ADB门禁] 无权限 status=" + st + " label=" + Adb.label());
        showAdbGateDialog();
    }

    private void showAdbGateDialog() {
        if (destroyed || uiRoot == null) return;
        if (adbGateShowing) { lg("[ADB门禁] 已有弹窗在显示，忽略重复调用"); return; }
        final int st;
        try { st = Adb.status(); } catch (Throwable t) { return; }
        if (st == Adb.OK) return;
        adbGateShowing = true;

        final boolean connected = (st == Adb.NO_PERM);
        String title = "需要 ADB 权限";
        String msg;
        if (connected) {
            msg = "本模块需要 ADB(Shizuku) 权限才能工作。\n\n"
                    + "当前状态：" + Adb.label() + "\n\n"
                    + "点击「立即获取」授权；若不授权，将退出设置界面。";
        } else {
            msg = "未检测到 Shizuku 服务（ADB 权限通道）。\n\n"
                    + "请先安装并启动 Shizuku（或在 Root 环境下启用 Sui），"
                    + "然后回到本界面重试。\n\n"
                    + "若不获取权限，将退出设置界面。";
        }
        try {
            Runnable clearFlag = () -> adbGateShowing = false;
            SoftUi.confirm(uiRoot, title, msg,
                    connected ? "立即获取" : "重试",
                    "退出",
                    () -> {
                        clearFlag.run();
                        lg("[ADB门禁] 用户选择获取/重试");
                        if (!connected) {
                            safePost(() -> {
                                if (Adb.status() == Adb.OK) {
                                    lg("[ADB门禁] 重试成功，放行");
                                    SoftUi.infoDialog(uiRoot, "已获取 ADB 权限", Adb.label(), "好");
                                } else {
                                    showAdbGateDialog();
                                }
                            });
                            return;
                        }
                        Adb.request(SettingsActivity.this, () -> safePost(() -> {
                            if (Adb.status() == Adb.OK) {
                                lg("[ADB门禁] 授权成功，放行: " + Adb.label());
                                SoftUi.infoDialog(uiRoot, "已获取 ADB 权限", Adb.label(), "好");
                            } else {
                                lg("[ADB门禁] 授权未通过: " + Adb.label());
                                showAdbGateDialog();
                            }
                        }));
                    },
                    () -> {
                        clearFlag.run();
                        lg("[ADB门禁] 用户选择退出 -> finish()");
                        if (!destroyed) finish();
                    });
        } catch (Throwable t) {
            adbGateShowing = false;
            lg("[ADB门禁] 弹窗失败，直接退出: " + t);
            if (!destroyed) finish();
        }
    }

    private static int themeIndex(int mode) {
        if (mode == SettingsStore.THEME_LIGHT) return 0;
        if (mode == SettingsStore.THEME_DARK) return 2;
        return 1;
    }

    private static int themeModeOf(int idx) {
        if (idx == 0) return SettingsStore.THEME_LIGHT;
        if (idx == 2) return SettingsStore.THEME_DARK;
        return SettingsStore.THEME_SYSTEM;
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        lg("=== onCreate 进入 === [" + LOG_TAG_VER + "]");
        try {
            super.onCreate(savedInstanceState);
            store = new SettingsStore(this);
            SoftUi.initTheme(this, store.getThemeMode());

            getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                            | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED);
            getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(SoftUi.CANVAS));
            android.graphics.Bitmap bg = null;
            if (store.hasBg()) {
                try {
                    bg = android.graphics.BitmapFactory.decodeFile(
                            store.bgFile().getAbsolutePath());
                    lg("背景图已加载: " + (bg != null ? bg.getWidth() + "x" + bg.getHeight() : "null"));
                } catch (Throwable t) { lg("背景图加载失败: " + t); }
            }

            uiRoot = new android.widget.FrameLayout(this);
            setContentView(uiRoot, new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            boolean needFade = sFadeOnCreate || getIntent().getBooleanExtra(EXTRA_FADE, false);
            sFadeOnCreate = false;
            getIntent().removeExtra(EXTRA_FADE);
            buildUi(uiRoot, null, bg, needFade, null);

            if (SettingsStore.sUpgradeClosedRecents) {
                SettingsStore.sUpgradeClosedRecents = false;
                lg("升级提示：已自动关闭最近任务模糊");
                uiRoot.post(() -> {
                    try {
                        SoftUi.infoDialog(uiRoot, "版本更新提示",
                                "由于「最近任务模糊增强」稳定性问题，本次更新已自动关闭该功能。\n\n"
                                        + "如需继续使用，请前往「实验性功能」手动重新启用。",
                                "知道了");
                    } catch (Throwable t) { lg("升级提示弹窗失败: " + t); }
                });
            }
            setupEdgeToEdge();
            try {
                android.view.View decor = getWindow().getDecorView();
                if (decor instanceof android.view.ViewGroup) {
                    ((android.view.ViewGroup) decor).setFitsSystemWindows(false);
                }
                shell.setFitsSystemWindows(false);

                shell.setOnApplyWindowInsetsListener((v, insets) -> insets);
                shell.post(() -> { try { shell.requestApplyInsets(); } catch (Throwable ignored) {} });
                lg("shell inset ok: shellTop=" + shell.getTop() + " shellH=" + shell.getHeight());
            } catch (Throwable t) { lg("shell inset 失败: " + t); }
            lg("setContentView OK —— onCreate 完成");
            safePost(() -> checkAdbGate());
        } catch (Throwable t) {
            lg("!!! 异常: " + t);
            for (StackTraceElement e : t.getStackTrace()) lg("    at " + e);
            throw t;
        }
    }
    private static final int REQ_PICK_BG = 1001;
    private void pickBgImage() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_PICK_BG);
        } catch (Throwable t) {
            lg("pickBgImage 失败: " + t);
        }
    }
    private void collapseThen(final View row, final Runnable after) {
        if (row == null) { if (after != null) after.run(); return; }
        final ViewGroup.LayoutParams lp = row.getLayoutParams();
        if (lp == null) { if (after != null) after.run(); return; }
        final int fromH = row.getHeight() > 0 ? row.getHeight()
                : SoftUi.measureContentHeight(row);
        if (fromH <= 0) { if (after != null) after.run(); return; }
        android.animation.ValueAnimator va =
                android.animation.ValueAnimator.ofInt(fromH, 0);
        va.setDuration(180);
        va.setInterpolator(new android.view.animation.AccelerateInterpolator());
        va.addUpdateListener(a -> {
            lp.height = (int) a.getAnimatedValue();
            row.setLayoutParams(lp);
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                lp.height = 0;
                row.setLayoutParams(lp);
                if (after != null) after.run();
            }
        });
        va.start();
    }
    private void doRestartOne(final String pkg) {
        new Thread(() -> {
            final String r;
            if (Adb.status() != Adb.OK) {
                r = "!尚未授权 ADB";
            } else {
                java.util.List<String> one = new java.util.ArrayList<>();
                one.add(pkg);
                r = Adb.restartScope(SettingsActivity.this, one);
            }
            runOnUiThread(() -> {
                if (destroyed) return;
                SoftUi.toast(SettingsActivity.this,
                        pkg + " -> " + (r.startsWith("!") ? "失败" : "已重启"));
                lg("restart scope result: " + r);
            });
        }, "adb-scope-one").start();
    }
    private void doRestartAll(final java.util.List<String> pkgs) {
        new Thread(() -> {
            final String r;
            if (Adb.status() != Adb.OK) {
                r = "!尚未授权 ADB";
            } else {
                r = Adb.restartScope(SettingsActivity.this, pkgs);
            }
            runOnUiThread(() -> {
                if (destroyed) return;
                SoftUi.toast(SettingsActivity.this,
                        "重启全部 -> " + (r.startsWith("!") ? "失败（见日志）" : "已重启"));
                lg("restart ALL result: " + r);
            });
        }, "adb-scope-all").start();
    }
    private void clearBgImage() {
        try {
            store.clearBg();
            SoftUi.clearBackdrop();
            lg("背景图已清除（恢复纯色）");
        } catch (Throwable t) {
            lg("clearBgImage 失败: " + t);
        }
        sFadeOnCreate = true;
        recreate();
    }
    private long querySize(Uri uri) {
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(uri,
                    new String[]{ android.provider.OpenableColumns.SIZE },
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Throwable t) {
            lg("querySize 失败: " + t);
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) {}
        }
        return -1;
    }
    private boolean copyBgToPrivate(Intent data) {
        try {
            if (data == null || data.getData() == null) return false;
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {}
            long size = querySize(uri);
            if (size > MAX_BG_BYTES) {
                lg("背景图过大: " + (size / 1024 / 1024) + "MB > " + MAX_BG_MB + "MB，已拒绝");
                SoftUi.toast(this,
                        "图片过大（超过 " + MAX_BG_MB + "MB），请换一张", true);
                return false;
            }
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) return false;
            java.io.File dst = new java.io.File(getFilesDir(), "bg.jpg");
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[64 * 1024];
            int n;
            long written = 0;
            boolean tooBig = false;
            while ((n = in.read(buf)) > 0) {
                written += n;
                if (written > MAX_BG_BYTES) { tooBig = true; break; }
                out.write(buf, 0, n);
            }
            out.flush(); out.close(); in.close();
            if (tooBig) {
                dst.delete();
                lg("背景图实际超过 " + MAX_BG_MB + "MB，已中止并删除");
                SoftUi.toast(this,
                        "图片过大（超过 " + MAX_BG_MB + "MB），请换一张", true);
                return false;
            }
            store.setBgFile("bg.jpg");
            lg("背景图已保存: " + dst.getAbsolutePath() + " (" + dst.length() + " B)");
            return true;
        } catch (Throwable t) {
            lg("copyBgToPrivate 失败: " + t);
            return false;
        }
    }
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK_BG) return;
        if (res != RESULT_OK) { lg("选图取消"); return; }
        if (copyBgToPrivate(data)) {
            sFadeOnCreate = true;
            recreate();
        }
    }
    private static String extractVersionDigits(String tag) {
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < tag.length(); i++) {
            char c = tag.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.') d.append(c);
        }
        String num = d.toString();
        if (num.length() == 0) return null;
        while (num.startsWith(".")) num = num.substring(1);
        while (num.endsWith(".")) num = num.substring(0, num.length() - 1);
        return num.length() == 0 ? null : num;
    }
    private static long tagToCode(String tag) {
        if (tag == null) return -1L;
        String num = extractVersionDigits(tag);
        if (num == null) return -1L;
        String[] parts = num.split("\\.");
        try {
            long major = Long.parseLong(parts[0]);
            long minor = 0L;
            if (parts.length >= 2 && parts[1].length() > 0) {
                String mn = parts[1];
                if (mn.length() > 1) mn = mn.substring(0, 1);
                minor = Long.parseLong(mn);
            }
            return major * 10L + minor;
        } catch (Throwable ignored) {}
        return -1L;
    }

    private static final String CHANGELOG =
            "v47.2\n"
            + "· 从后台任务（最近任务）切入横屏应用时，不再触发入场旋转\n"
            + "  · 该场景下应用的真实方向已经出现过，再转一次显得多余且突兀\n"
            + "  · 现在只在从桌面图标打开时旋转，退场动画不变\n"
            + "  · 判断复用最近任务界面的已有状态，未新增探测，也未增加每帧开销\n"
            + "v47.1\n"
            + "· 修复入场旋转的角度不正确（打开横屏应用时方向歪斜）\n"
            + "  · 入场改用「退场动画反向播放」：角度由 -90° 逐渐扶正到 0°，\n"
            + "    动画结束时严格归零，再由系统切换到真正的横屏窗口，衔接无错位\n"
            + "  · 之前单纯把角度取反号，转完停在 +90°，表现为「竖屏下的横屏」\n"
            + "· 从后台任务切入横屏应用时，不再触发入场旋转\n"
            + "  · 该场景下应用的真实方向已经出现过，再转一次显得多余且突兀；\n"
            + "    现在只在从桌面图标打开时旋转\n"
            + "· 修复「桌面应用名阴影」卡片缺少毛玻璃背景\n"
            + "  · 该卡片一直没被纳入「需要启用毛玻璃的卡片」清单，因此始终是纯色背景；\n"
            + "    现在与输入法、时钟等卡片一致，设置背景图后自动变为高斯模糊\n"
            + "· 修复「桌面应用名阴影」子项无法折叠\n"
            + "  · 上一次改动在开关回调外层额外注册了一次处理，覆盖掉内部的折叠逻辑，\n"
            + "    表现为开关能切换、参数能保存，但「阴影浓度」始终不展开 / 收起\n"
            + "· 布局整理（行为不变）\n"
            + "  · 「横屏应用过渡旋转」没有可调参数，并入「功能」卡片，\n"
            + "    不再单独成卡并占用「横屏过渡旋转」分组标题\n"
            + "· 已知限制\n"
            + "  · 视频播放器（B站、各类播放器）的全屏旋转不参与本功能：\n"
            + "    它的竖屏列表与横屏全屏共用同一个界面，系统上报的方向始终是竖屏，\n"
            + "    无法在「从桌面打开的那一刻」区分后面会不会全屏\n"
            + "  · 若强行按应用名单旋转，竖屏视频也会跟着转，观感更差，故不做\n"
            + "\n"
            + "v47\n"
            + "· 新增「横屏应用过渡旋转」（默认开启）\n"
            + "  · 横屏应用退出时保持横屏，过渡动画中逐帧逆时针转 90° 落回竖屏桌面；从桌面打开横屏应用时反向\n"
            + "  · 窗口层与图标层同步旋转，两者不会分离；不裁切窗口，曲线线性直通，不起新动画\n"
            + "  · 设置界面新增开关，开 / 关下一帧即生效，无需重启桌面\n"
            + "· 修复横屏旋转整体不生效\n"
            + "  · 目标类名解析路径写错（实际位于 systemui.shared.system 下），安装期直接失败\n"
            + "  · 安装过程独立隔离，本功能出问题不再牵连其它任何功能\n"
            + "· 修复末尾渐变破坏过渡动画拖尾\n"
            + "  · 淡出改为与系统自身的透明度曲线相乘，不再覆盖，拖尾与渐变都保留\n"
            + "· 修复动画幅度大时末尾被提前淡出「打断」\n"
            + "  · 判据不再看进度，改为逐帧测图标实际位移与尺寸变化，确定停住才动手\n"
            + "  · 收尾阶段以固定角速度顺时针回正（约 17 帧走完 90°），淡出结束时角度归零，与真实图标完全重合\n"
            + "· 开关即时生效\n"
            + "  · 设置页关掉任意功能后宿主进程立即停止处理，不必重启桌面 / 输入法等\n"
            + "  · 「桌面卡片模糊」「桌面应用名阴影」开 / 关都立刻生效\n"
            + "· 修复部分开关在设置页改了不生效\n"
            + "  · 配置写盘与实时广播统一为同一份键值表，不再出现两处不一致\n"
            + "  · 广播热更改为统一解析，之前只有部分参数能实时生效\n"
            + "· 性能\n"
            + "  · 进度读取改为直接访问字段，每帧不再产生临时对象\n"
            + "  · 场景 / 横竖 / 目标信息按动画缓存，窗口通道每帧反射次数由 6~7 次降到 3 次\n"
            + "  · 两条通道共享变换矩阵，120Hz 下每秒减少上百次原生对象分配\n"
            + "  · 收尾阶段不再重复写调试日志，避免同步文件写入落在桌面线程上\n"
            + "· 修复内存滞留\n"
            + "  · 逐帧缓存里的强引用（含一块 SurfaceControl）从不释放，动画结束与功能关闭时改为主动清空\n"
            + "· 代码结构整理（行为不变）\n"
            + "  · 配置读写与词表解析独立为 FlagStore\n"
            + "  · 屏幕亮灭 / 配置广播独立为 BroadcastBridge\n"
            + "  · 入口类只保留包名路由与 hook 安装\n"
            + "· 可诊断性\n"
            + "  · 78 处静默吞掉的异常改为可记录（日志开关关闭时零开销）\n"
            + "· 版本号编码规则调整\n"
            + "  · 版本号与内部版本码一一对应：v47 对应 470，v47.1 对应 471\n"
            + "  · 末位到 9 后继续发布自动进位到下一个功能位（479 之后为 480）\n"
            + "· 内部版本号同步至 v47\n"
            + "\n"
            + "v46.4\n"
            + "· 修复桌面卡片黑屏（调整卡片大小后整个桌面变黑）\n"
            + "  · 卡片重建时若目标已是模糊背景则直接跳过，避免模糊节点重复挂载导致合成器出错\n"
            + "  · 原生接口未真正替换背景时，改为「原样回滚」原背景，不再把背景清空（清空后无人补色是黑屏的直接成因）\n"
            + "· 修正卡片底色层的透明度判据\n"
            + "  · 快应用 CSS 底色层实测返回 -2 / -3 而非 -1，旧判据只认 -1，导致部分卡片（L40 / K40）选不中、不生效\n"
            + "  · 改为直接比较 PixelFormat 常量，两类卡片统一按 CSS 背景放行\n"
            + "· 可维护性重构（纯提取，零行为变化）\n"
            + "  · 快捷菜单动画入口、卡片模糊、组件重试的长方法与深层嵌套拆为独立方法\n"
            + "  · 卡片包名 / 类名等常量集中到 Symbols 单点定义，消除多处硬编码\n"
            + "· 性能与整洁\n"
            + "  · 失败回滚路径的日志补上开关守卫，关闭日志时不再产生字符串分配\n"
            + "  · 模糊背景判定去掉 toLowerCase，改为零分配匹配，且不再受系统语言影响\n"
            + "  · 移除已无调用方的死代码\n"
            + "· 内部版本号同步至 v46.4\n"
            + "\n"
            + "v46.3\n"
            + "· 新增「桌面卡片模糊」（耗电看板等）\n"
            + "  · 卡片出现在桌面即自动挂上高斯模糊，无需手动触发\n"
            + "  · 只处理已知卡片类并校验网格跨度，非卡片视图不受影响\n"
            + "  · 内容变化时自动重新处理；内容未就绪则按指数退避重试（窗口约 18 秒）\n"
            + "  · 已有结果的卡片不再重复扫描（切页 / 布局变化零开销），放弃后也不会反复重试\n"
            + "  · 新增热更参数 launcher_card_blur；开关位于「时钟组件模糊」卡片内\n"
            + "· 「桌面应用名阴影」适配卡片内标题（看板卡 / 集合卡标题同样常驻阴影）\n"
            + "  · 卡片基类改为按需解析，避免解析时机过早导致该项永久失效\n"
            + "· 日志：桌面卡片拆出独立标签，便于与快捷菜单卡片的日志区分\n"
            + "· 内部版本号同步至 v46.3\n"
            + "\n"
            + "v46.2\n"
            + "· 新增「桌面应用名阴影」\n"
            + "  · 应用名常驻一层柔和阴影，亮 / 暗壁纸下都清晰（原生只在暗壁纸下才有）\n"
            + "  · 原生亮壁纸会直接清空阴影；现改为常驻，主题 / 壁纸 / 透明度动画都清不掉\n"
            + "  · 阴影恒为纯黑（不做亮色文字配亮色阴影的自适应），只有浓度可调\n"
            + "  · 新增热更参数 launcher_label_shadow / _dark（半径与偏移已固定，不可热更）\n"
            + "  · 设置界面新增独立开关，可折叠参数只有「阴影浓度」（0~100%），半径 4dp / 偏移 1dp 固定\n"
            + "· 修复键盘内发光观感（按「到圆角矩形边界的距离」重做）\n"
            + "  · 不再发粗：发光带宽度由 8dp 收窄到 3dp，贴边最亮、向内快速渐隐\n"
            + "  · 圆角不再熄灭：圆角改用径向渐变，与直边亮度连续（此前圆角整段没光）\n"
            + "  · 恢复「内发光」语义：始终是亮色发光，不再出现暗色内阴影\n"
            + "  · 新增热更参数 ime_glow_alpha_dark / ime_glow_alpha_light / ime_glow_feather_dp\n"
            + "    / ime_glow_color_dark / ime_glow_color_light / ime_glow_corner_dp\n"
            + "· 内部版本号同步至 v46.2\n"
            + "\n"
            + "v46.1\n"
            + "· 修复切换悬浮键盘后再切回，键盘圆角未恢复的问题（切换后自动重新应用）\n"
            + "· 发热与耗电优化（重点修复长稳使用下的发热）\n"
            + "  · 日志输出不再逐条同步刷盘，改为批量写入，大幅降低存储 IO\n"
            + "  · 日志不再逐条写入系统日志，减少跨进程通信开销\n"
            + "  · 时钟/天气组件在「被遮挡但未移除」时不再高频探测（4Hz → 1.25Hz）\n"
            + "  · 快捷菜单关闭动画的透明度压制减少冗余帧，降低关闭瞬间的 CPU 占用\n"
            + "  · 最近任务滑动时的配置读取频率下调（2s → 5s），减少滑动期卡顿\n"
            + "  · 桌面主题色读取先做快速判定，避免大量无关调用进入处理逻辑\n"
            + "  · 设置界面滚动时不再重绘屏幕外的玻璃层\n"
            + "  · 弹窗等热路径日志降级，仅在调试档输出\n"
            + "  · 补充若干缓存上限保护，避免长时间运行后缓慢膨胀\n"
            + "  · 键盘发光描边按「边界距离」分块绘制，绘制次数由 12 层降为 6 次以内\n"
            + "  · 键盘模糊、卡片模糊的重试次数下调，避免反复重建造成的短时功耗尖峰\n"
            + "  · 设置界面玻璃层增加内容指纹复用，内容未变时不再重复整树重绘\n"
            + "  · 关闭菜单的模糊渐隐加入节流，不再逐帧重复创建模糊效果\n"
            + "  · 快捷菜单弹窗链路日志全面降噪，仅在调试档输出\n"
            + "  · 二级菜单模糊解析结果缓存并避免重复设置，切换更省电\n"
            + "  · 反射缓存在“未找到”时快速返回，并移除全局锁，降低高频调用开销\n"
            + "· 内部版本号同步至 v46.1\n"
            + "\n"
            + "v46\n"
            + "· 快捷菜单模糊修复与增强（长按图标弹出的一级菜单 + 「更多功能」二级菜单）\n"
            + "  · 修复一级菜单关闭时短暂「恢复实心 / 闪白」，退出动画全程保持半透明模糊\n"
            + "  · 修复二级「更多功能」菜单弹出时，桌面图标模糊被误清除的问题\n"
            + "  · 二级菜单模糊半径独立可调（默认 96，比一级更强），两级互不影响\n"
            + "  · 二级菜单圆角调整为 16dp，与一级观感统一\n"
            + "· 新增快捷菜单模糊参数热更（改完即时生效，无需重装）\n"
            + "  · card_panel_alpha：一级面板不透明度\n"
            + "  · card_secondary_blur_radius：二级菜单模糊半径\n"
            + "  · card_blur_corner：二级菜单圆角（dp）\n"
            + "· 性能与内存优化（快捷菜单模糊链路）\n"
            + "  · 清理调试探针与失效 Hook，减少无关开销与日志噪声\n"
            + "  · 模糊动画缓存改用弱引用，消除 View 泄漏与每次动画的临时对象分配\n"
            + "  · 关闭动画压透明加入面板缓存，消除每帧遍历子视图的开销\n"
            + "  · 修复若干跨线程集合访问，并统一加锁顺序消除潜在死锁\n"
            + "· 代码结构性调整（提升可维护性，行为不变）\n"
            + "  · 拆分臃肿的入口类：快捷菜单模糊抽取为独立模块，入口类从 1300+ 行精简至 600 行以内\n"
            + "  · 反射调用统一收敛到公共工具，附带缓存与缓存上限，减少重复代码与重复反射开销\n"
            + "  · 各功能按职责分区组织（安装调度 / 图标模糊 / 二级模糊 / 动画生命周期），边界更清晰\n"
            + "  · 清理无引用常量与失效 Hook，降低后续维护与排障成本\n"
            + "· 内部版本号同步至 v46\n"
            + "\n"
            + "v45.5.1\n"
            + "· 最近任务缩放下限由 0.92 调整为 0.96（卡片不再缩得过小）\n"
            + "· 修复全局搜索进不去背景模糊的偶发报错\n"
            + "· 日志降噪：模糊采样率 / 时钟文字颜色两类高频日志按状态去重\n"
            + "\n"
            + "v45.5\n"
            + "· 统一日志开关：界面日志 / 功能日志共用同一开关，关闭后不再有后台写文件\n"
            + "· 性能优化：色相滑块拖动不再每帧分配数组，彩色渐变缓存正确失效\n"
            + "· 卡片点按高亮背景在参数未变时复用，避免重复创建\n"
            + "\n"
            + "v45.4\n"
            + "· 新增「ADB 权限检测」：打开设置界面自动检测 ADB(Shizuku) 权限，未授权时引导获取\n"
            + "· 适配真我 (realme) 日历 / 天气横向小组件，模糊可正常呈现\n"
            + "· 时钟模糊：真我组件改用视图透明度控制，消除「实心白 / 模糊」闪烁\n"
            + "· 日志完善：启动时记录系统版本 / 机型 / 进程等环境信息\n"
            + "\n"
            + "v45.3\n"
            + "· 修复时钟模糊「长时间不可见（待机 / 切页）后再次可见时不更新」的问题\n"
            + "· 修复亮屏使用时偶发的发热问题（重建任务统一排队与取消）\n"
            + "· 修复多处内存泄漏：输入法模糊缓存改弱引用、图标模糊动画结束即清理\n"
            + "\n"
            + "v45.2\n"
            + "· 最近任务模糊判据重构：改用系统壁纸缩放信号驱动进出，响应更快更稳\n"
            + "· 修复从应用返回桌面时偶发误判「进入最近任务」导致的假模糊\n"
            + "· 键盘按键配色跟随系统深浅色切换\n"
            + "\n"
            + "v45.1\n"
            + "· 键盘按键配色优化：按键背景改为「半透明白玻璃」观感\n"
            + "· 修复多处内存泄漏与资源未释放问题，降低长时间使用后的内存占用\n"
            + "· 日志文件不再无限增长（自动轮转，单个上限 2MB）\n"
            + "\n"
            + "v45\n"
            + "· 新增「输入法键盘模糊」（仅适配语燕输入法）：可调模糊程度 / 键盘圆角 / 白灰蒙版强度\n"
            + "· 输入法模糊性能优化：深色判定结果缓存、根布局按类缓存、同帧去抖合并\n"
            + "· 适配逻辑去硬编码：改为「候选名 + 特征校验」自动识别\n"
            + "· 设置界面：带子选项的选项统一改用可折叠控件\n"
            + "\n"
            + "v44.3\n"
            + "· 新增时钟组件「自定义混色」：可调色相 + 手工输入色号，向时钟混入自定义颜色\n"
            + "· 设置界面弹窗重构：统一为通用弹窗骨架，毛玻璃随位置实时重采样\n"
            + "\n"
            + "v44.2\n"
            + "· 新增「相册强制白色主题」（默认开启），需在 LSPosed 中勾选「相册」作用域\n"
            + "· 调整升级迁移策略：仅在从 v44 及更早版本升级时自动关闭一次「最近任务模糊增强」\n"
            + "\n"
            + "v44.1\n"
            + "· 适配 17 版时钟组件（布局改版导致旧 ID 白名单失效）\n"
            + "· 「最近任务模糊增强」调整为实验性功能（默认关闭）\n"
            + "· 设置界面全面打磨：主题交叉淡入切换、深色模式全套配色、滑块与开关点按反馈\n"
            + "\n"
            + "v44\n"
            + "· 新增「下拉搜索实时模糊」\n"
            + "\n"
            + "v43.1\n"
            + "· 时钟组件模糊全面重构：适配更多组件布局，改用事件驱动状态机，性能大幅优化\n"
            + "\n"
            + "v43\n"
            + "· 新增图形化设置界面：功能开关实时生效、检查更新、作用域逐包重启、自定义背景图\n"
            + "\n"
            + "v42.5\n"
            + "· 多任务模糊重做\n"
            + "\n"
            + "更早版本见项目 README\n";

    private void showChangelog() {
        try {
            SoftUi.infoDialog(shell, "更新日志", CHANGELOG, "关闭");
        } catch (Throwable t) {
            lg("showChangelog fail: " + t);
        }
    }
    private static final String ORIGIN =
            "部分功能最早在作者的另一仓库独立迭代，之后整体合入本仓库，并在此继续维护。\n"
            + "两个仓库同属同一作者（wisely-leo），该早期仓库不构成独立的第三方来源。\n"
            + "\n"
            + "早期迭代仓库\n"
            + "  Color-OS-16-RotFall\n"
            + "  github.com/wisely-leo/Color-OS-16-RotFall\n"
            + "\n"
            + "已合入并在此维护的功能\n"
            + "  横屏应用过渡旋转（v47 合入）\n"
            + "    合入时已重写为适配本模块的分层结构（安装隔离、开关热更、日志体系）；\n"
            + "    合入后继续修复：类名解析路径、末尾淡出破坏拖尾、大幅度动画被提前淡出打断、\n"
            + "    回正角速度、逐帧开销与内存滞留等\n"
            + "\n"
            + "同步状态\n"
            + "  早期仓库中的代码为当时版本，其后所有修复均只在本仓库进行，两者不再同步。\n"
            + "  需要最新实现请以本仓库为准。";
    private void showOrigin() {
        try {
            SoftUi.infoDialog(shell, "来源说明", ORIGIN, "关闭");
        } catch (Throwable t) {
            lg("showOrigin fail: " + t);
        }
    }
    private void checkUpdate(final android.widget.TextView status) {
        final SettingsStore st = store;
        new Thread(() -> {
            String msg;
            HttpURLConnection conn = null;
            try {
                String api = "https://api.github.com/repos/wisely-leo/ColorOS_Blur_Enhance/releases/latest";
                conn = (HttpURLConnection) new URL(api).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "ColorOSBlurEnhance-GUI");
                int code = conn.getResponseCode();
                if (code != 200) {
                    msg = "!HTTP " + code;
                } else {
                    BufferedReader br = null;
                    String body;
                    try {
                        br = new BufferedReader(
                                new InputStreamReader(conn.getInputStream(), "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                        body = sb.toString();
                    } finally {
                        if (br != null) { try { br.close(); } catch (Throwable ignored) {} }
                    }
                    String tag = jsonStr(body, "tag_name");
                    if (tag == null || tag.length() == 0) {
                        msg = "!无法解析版本";
                    } else {
                        long localCode = (st == null) ? 0 : st.versionCode();
                        long remoteCode = tagToCode(tag);
                        String localName = (st == null) ? "?" : st.versionName();
                        if (remoteCode <= 0) {
                            msg = tag.equalsIgnoreCase(localName)
                                    ? ("已是最新 " + localName)
                                    : ("发现新版本 " + tag);
                        } else if (remoteCode > localCode) {
                            msg = "发现新版本 " + tag;
                        } else {

                            msg = "已是最新 " + localName;
                        }
                    }
                }
            } catch (Throwable t) {
                lg("checkUpdate fail: " + t);
                msg = "!网络错误";
            } finally {
                if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) {} }
            }
            final String m = msg;
            runOnUiThread(() -> {
                if (destroyed) return;
                status.setText(m);
                lg("checkUpdate -> " + m);
            });
        }, "gh-update").start();
    }
    private static String jsonStr(String json, String key) {
        if (json == null) return null;
        char q = '"';
        String k = q + key + q;
        int i = json.indexOf(k);
        if (i < 0) return null;
        int c = json.indexOf(':', i + k.length());
        if (c < 0) return null;
        int q1 = json.indexOf('"', c + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }
    private void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            lg("openUrl fail: " + t);
        }
    }
    @Override protected void onResume() {
        super.onResume();
        lg("=== onResume ===");
    }
    private void setupEdgeToEdge() {
        android.view.Window win = getWindow();
        try {
            android.view.WindowManager.LayoutParams lp = win.getAttributes();
            lp.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            win.setAttributes(lp);
        } catch (Throwable t) { lg("edge: cutout 失败 " + t); }
        try {
            android.view.WindowManager.LayoutParams lp = win.getAttributes();
            lp.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            win.setAttributes(lp);
        } catch (Throwable t) { lg("edge: cutout 失败 " + t); }
        try {
            win.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            win.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        } catch (Throwable t) { lg("edge: barColor 失败 " + t); }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                win.setDecorFitsSystemWindows(false);
            }
        } catch (Throwable t) { lg("edge: decorFits 失败 " + t); }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController c = win.getInsetsController();
                if (c != null) {

                    int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
                    if (!SoftUi.DARK) {
                        c.setSystemBarsAppearance(light, light);
                    } else {
                        c.setSystemBarsAppearance(0, light);
                    }
                    lg("edge: insetsController OK dark=" + SoftUi.DARK);
                } else {
                    lg("edge: insetsController null");
                }
            } else {
                int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
                if (!SoftUi.DARK) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                }
                win.getDecorView().setSystemUiVisibility(flags);
            }
        } catch (Throwable t) {
            lg("edge: iconAppearance 失败 " + t);
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean wasDark = SoftUi.DARK;
        SoftUi.initTheme(this, store != null ? store.getThemeMode() : 0);
        if (wasDark != SoftUi.DARK) {
            sFadeOnCreate = true;
            recreate();
        }
    }

    private android.graphics.Bitmap snapshotView(android.view.View v) {
        try {
            if (v == null || v.getWidth() <= 0 || v.getHeight() <= 0) return null;
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    v.getWidth(), v.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            v.draw(canvas);
            lg("snapshotView: " + v.getWidth() + "x" + v.getHeight());
            return bmp;
        } catch (Throwable t) {
            lg("snapshotView 失败: " + t);
            return null;
        }
    }
    private SoftUi.Card[] buildAboutCards() {
        final String GITHUB = "https://github.com/wisely-leo/ColorOS_Blur_Enhance";
        final String COOLAPK = "https://www.coolapk.com/u/26072346";
        SoftUi.Card cardPerm = SoftUi.card(this,
                SoftUi.infoBlock(this, "网络访问 (INTERNET)",
                        "检查更新时请求 GitHub Releases API；安装即授予，无需手动开启"),
                SoftUi.infoBlock(this, "ADB 授权 (Shizuku)",
                        "重启作用域（force-stop）；需安装 Shizuku 并在本页授权"),
                SoftUi.infoBlock(this, "LSPosed 作用域",
                        "hook 模糊逻辑；需在 LSPosed 管理器中启用模块并勾选作用域应用"),
                SoftUi.infoBlock(this, "图片选择（无需权限）",
                        "背景图使用系统文档选择器，仅读取你主动选中的那张图，不读取相册"));
        SoftUi.Card cardAbout = SoftUi.card(this,
                SoftUi.info(this, "模块", store.appName()),
                SoftUi.info(this, "版本", store.versionName()),
                SoftUi.actionArrow(this, "作者",
                        SoftUi.statusText(this, store.authorName()),
                        () -> openUrl(COOLAPK)),
                SoftUi.link(this, "GitHub 项目主页", () -> openUrl(GITHUB)),
                SoftUi.link(this, "来源说明", () -> showOrigin()));
        SoftUi.Card cardFiles = SoftUi.card(this,
                SoftUi.infoBlock(this, "配置文件",
                        "/data/local/tmp/ColorOSBlurEnhance/blur.conf"),
                SoftUi.infoBlock(this, "背景图",
                        "应用私有目录 files/bg.jpg"),
                SoftUi.infoBlock(this, "模块日志",
                        "/storage/emulated/0/Download/ColorOSBlurEnhance.log"),
                SoftUi.infoBlock(this, "界面日志",
                        "/storage/emulated/0/Download/UiStartup.log"));
        SoftUi.Card cardNote = SoftUi.card(this,
                SoftUi.info(this, "说明", "基于 libxposed API 的 ColorOS 模糊增强模块。"
                        + "\n为快捷菜单、最近任务、小组件与后置特效提供可调动态模糊。"
                        + "\n\n功能开关可在本界面实时调整，保存后由宿主模块读取生效。"
                        + "\n\n重启作用域后立即生效，无需重启手机。"));
        SoftUi.Card cardOss = SoftUi.card(this,
                SoftUi.infoBlock(this, "SoftUI",
                        "本项目自研  ·  基于系统 API 绘制实现"),
                SoftUi.infoBlock(this, "libxposed API (102)",
                        "libxposed / LSPosed  ·  遵循其自身许可",
                        () -> openUrl("https://github.com/libxposed")),
                SoftUi.infoBlock(this, "Shizuku API",
                        "RikkaApps  ·  Apache-2.0",
                        () -> openUrl("https://github.com/RikkaApps/Shizuku-API")));
        return new SoftUi.Card[] { cardPerm, cardAbout, cardFiles, cardNote, cardOss };
    }
    private void buildUi(android.view.ViewGroup root,
                         final android.view.View oldShell,
                         android.graphics.Bitmap bg,
                         boolean needFade,
                         android.graphics.Bitmap snapshot) {
            SoftUi.CARD_FILL = SoftUi.SURFACE;
            shell = SoftUi.scrollingScreen(this, store.appName(), bg);
            LinearLayout content = SoftUi.contentOf(shell);
            lg("scrollingScreen OK, content=" + (content != null));
            final LinearLayout pageSettings = new LinearLayout(this);
            pageSettings.setOrientation(LinearLayout.VERTICAL);
            final LinearLayout pageAbout = new LinearLayout(this);
            pageAbout.setOrientation(LinearLayout.VERTICAL);
            pageAbout.setVisibility(View.GONE);
            content.addView(pageSettings, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            content.addView(pageAbout, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            final String GITHUB = "https://github.com/wisely-leo/ColorOS_Blur_Enhance";
            final String COOLAPK = "https://www.coolapk.com/u/26072346";
            View sampleRow = SoftUi.slider(this, "采样倍率",
                    store.getSampleScale() * 100f, 0, 100, "%",
                    v -> store.setSampleScale(v / 100f));

            View imeRadiusRow = SoftUi.slider(this, "模糊程度", store.getImeBlurRadius(), 0f, 350f, "",
                    v -> store.setImeBlurRadius(v));
            View imeMaskRow = SoftUi.slider(this, "白灰蒙版强度", store.getImeBlurMask() * 100f, 0f, 100f, "%",
                    v -> store.setImeBlurMask(v / 100f));

            View[] imeRows = SoftUi.toggleWithDependents(this, "输入法键盘模糊",
                    store.isImeBlur(), null,
                    imeRadiusRow, imeMaskRow);
            final SoftUi.Row imeToggleRow = (SoftUi.Row) imeRows[0];
            final View imeFoldHolder = imeRows[1];
            imeToggleRow.setOnToggle(v -> {
                final SoftUi.Switch sw = imeToggleRow.switchView();
                if (!v) {
                    store.setImeBlur(false);
                    SoftUi.applyFold(imeFoldHolder, false, true);
                    return;
                }
                SoftUi.confirm(shell,
                        "启用输入法键盘模糊",
                        "该功能当前仅适配了语燕输入法(2026530.10)，"
                                + "以后不保证适配其他输入法(取决于适配难度)，"
                                + "如果你不使用该输入法就没有必要启用该功能。\n\n确定启用吗？",
                        "确定启用", "取消",
                        () -> {
                            store.setImeBlur(true);
                            SoftUi.applyFold(imeFoldHolder, true, true);
                        },
                        () -> {
                            if (sw != null) sw.setCheckedImmediate(false);
                            SoftUi.applyFold(imeFoldHolder, false, false);
                        });
            });

            SoftUi.Card cardImeBlur = SoftUi.card(this, imeRows[0], imeRows[1]);

            final SoftUi.Row recentsRow = SoftUi.toggle(this,
                    "最近任务模糊增强", store.isRecents(), null, 0f, false);
            recentsRow.setOnToggle(v -> {
                final SoftUi.Switch sw = recentsRow.switchView();
                if (!v) {

                    store.setRecents(false);
                    return;
                }

                SoftUi.confirm(shell,
                        "启用提示",
                        "该功能仅在作者设备上测试通过稳定性测验，其他设备请自测。\n\n确定启用吗？",
                        "确定启用", "取消",
                        () -> {
                            lg("recents ENABLED by user");
                            store.setRecents(true);
                        },
                        () -> {
                            if (sw != null) sw.setCheckedImmediate(false);
                        });
            });

            SoftUi.Card cardFunc = SoftUi.card(this,
                    SoftUi.toggle(this, "Shortcut 实时模糊", store.isShortcut(),
                            v -> store.setShortcut(v)),
                    SoftUi.toggle(this, "下拉搜索实时模糊", store.isQsProbe(),
                            v -> store.setQsProbe(v)),
                    SoftUi.toggle(this, "相册强制白色主题", store.isGalleryLight(),
                            v -> store.setGalleryLight(v)),
                    recentsRow,
                    SoftUi.toggle(this, "横屏应用过渡旋转", store.isHomeRotate(),
                            v -> store.setHomeRotate(v)));

            final int[] lsDarkRef = { store.getLabelShadowDark() };
            final float[] lsDarkPctRef = { (lsDarkRef[0] >>> 24) / 255f * 100f };
            View lsDarkRow = SoftUi.slider(this, "阴影浓度",
                    lsDarkPctRef[0], 0, 100, "%",
                    v -> {
                        lsDarkPctRef[0] = v;
                        int alpha = Math.min(255, Math.round(v / 100f * 255f));
                        int argb = (lsDarkRef[0] & 0x00FFFFFF) | (alpha << 24);
                        lsDarkRef[0] = argb;
                        store.setLabelShadowDark(argb);
                    });
            View[] lsRows = SoftUi.toggleWithDependents(this, "桌面应用名阴影",
                    store.isLabelShadow(), v -> store.setLabelShadow(v), lsDarkRow);
            final SoftUi.Row lsToggleRow = (SoftUi.Row) lsRows[0];
            final View lsFoldHolder = lsRows[1];
            SoftUi.Card cardLabelShadow = SoftUi.card(this, lsToggleRow, lsFoldHolder);

            View clockAlphaRow = SoftUi.slider(this, "时钟透明度",
                    store.getClockIconAlpha() * 100f, 0, 100, "%",
                    v -> { float a = v / 100f; store.setClockIconAlpha(a); store.setClockTextAlpha(a); });
            View clockBrightRow = SoftUi.slider(this, "时钟提亮增益",
                    store.getClockBrighten() * 100f, 50, 300, "%",
                    v -> store.setClockBrighten(v / 100f));

            float initOpacity = store.getClockGlassOpacity();

            if (initOpacity <= 0.01f) initOpacity = 0.5f;
            final float[] glassOpacityRef = { initOpacity };
            final float[] glassMixRef = { store.getClockGlassMixStrength() };

            int rawHue = store.getClockGlassHue();
            if (rawHue > 0 && rawHue <= 8) {
                rawHue = Math.round((rawHue - 1) * 45f);
                store.setClockGlassHue(rawHue);
            }
            final float[] glassHueFracRef = { rawHue / 360f };
            final View[] hexValHolder = new View[1];
            View glassHueRow = SoftUi.colorSlider(this, "自定义混色颜色",
                    glassHueFracRef[0],
                    (color, frac) -> {
                        glassHueFracRef[0] = frac;
                        store.setClockGlassHue(Math.round(frac * 360f));
                        store.setClockGlass(true);
                        store.setClockGlassColor(color, glassOpacityRef[0], glassMixRef[0]);

                        if (hexValHolder[0] != null) {
                            SoftUi.setRowTitle(hexValHolder[0],
                                    "色号  #" + String.format("%06X", color & 0xFFFFFF));
                        }
                    });

            final int initRgb = hueToBaseColor(store.getClockGlassHue()) & 0xFFFFFF;
            View glassHexRow = SoftUi.link(this,
                    "色号  #" + String.format("%06X", initRgb), () -> {
                        SoftUi.colorPickerDialog(hexValHolder[0], "输入色号", initRgb, (rgb, valid) -> {
                            if (!valid) return;
                            float[] hsv = new float[3];
                            android.graphics.Color.colorToHSV(rgb, hsv);
                            store.setClockGlassHue(Math.round(hsv[0]));
                            SoftUi.ColorSlider cs = SoftUi.findColorSlider(glassHueRow);
                            if (cs != null) cs.setFrac(hsv[0] / 360f);
                            store.setClockGlass(true);
                            store.setClockGlassColor(rgb, glassOpacityRef[0], glassMixRef[0]);

                            if (hexValHolder[0] != null) {
                                SoftUi.setRowTitle(hexValHolder[0],
                                        "色号  #" + String.format("%06X", rgb));
                            }
                        });
                    });
            hexValHolder[0] = glassHexRow;
            View glassOpacityRow = SoftUi.slider(this, "混色浓度",
                    glassOpacityRef[0] * 100f, 0, 100, "%",
                    v -> {
                        glassOpacityRef[0] = v / 100f;
                        int base = hueToBaseColor(store.getClockGlassHue());
                        store.setClockGlassColor(base, glassOpacityRef[0], glassMixRef[0]);
                        store.setClockGlass(true);
                    });

            View glassMixRow = SoftUi.slider(this, "混色提亮强度",
                    glassMixRef[0] * 100f, 0, 100, "%",
                    v -> {
                        glassMixRef[0] = v / 100f;
                        store.setClockGlassMixStrength(glassMixRef[0]);
                        int base = hueToBaseColor(store.getClockGlassHue());
                        store.setClockGlassColor(base, glassOpacityRef[0], glassMixRef[0]);
                        store.setClockGlass(true);
                    });

            final Runnable applyGlassLock = () -> {
                boolean on = store.isClockGlass();

                SoftUi.setRowEnabled(clockAlphaRow, !on);
                SoftUi.setRowEnabled(clockBrightRow, !on);

                SoftUi.setRowValue(clockAlphaRow, store.getClockIconAlpha() * 100f);
                SoftUi.setRowValue(clockBrightRow, store.getClockBrighten() * 100f);
            };

            final View[] sGlassToggleRowRef = new View[1];
            final Runnable syncGlassEnable = () -> {
                boolean widgetOn = store.isWidget();
                boolean glassUsable = widgetOn && store.isClockGlass();
                SoftUi.setRowEnabled(sGlassToggleRowRef[0], widgetOn);
                SoftUi.setRowEnabled(glassHueRow, glassUsable);
                SoftUi.setRowEnabled(glassHexRow, glassUsable);
                SoftUi.setRowEnabled(glassOpacityRow, glassUsable);
                SoftUi.setRowEnabled(glassMixRow, glassUsable);
            };
            View[] glassRows = SoftUi.toggleWithDependents(this, "自定义混色",
                    store.isClockGlass(), v -> {
                        store.setClockGlass(v);
                        applyGlassLock.run();
                        syncGlassEnable.run();
                    },
                    glassHueRow, glassHexRow, glassOpacityRow, glassMixRow);

            sGlassToggleRowRef[0] = glassRows[0];

            final View cardBlurRow = SoftUi.toggle(this, "卡片模糊（耗电看板等）",
                    store.isCardBlur(), v -> store.setCardBlur(v));
            final Runnable syncCardEnable = () -> {
                SoftUi.setRowEnabled(cardBlurRow, store.isWidget());
            };

            View[] clockRows = SoftUi.toggleWithDependents(this, "时钟组件模糊",
                    store.isWidget(), v -> {
                        store.setWidget(v);
                        if (!v) {

                            SoftUi.setToggleChecked(glassRows[0], false, true);
                        }
                        syncGlassEnable.run();
                        syncCardEnable.run();
                    },
                    clockAlphaRow, clockBrightRow, glassRows[0]);
            SoftUi.Card cardClock = SoftUi.card(this, clockRows[0], clockRows[1], glassRows[1], cardBlurRow);

            syncGlassEnable.run();
            syncCardEnable.run();
            if (store.isWidget()) {
                applyGlassLock.run();
            } else {
                SoftUi.setRowEnabled(clockAlphaRow, false);
                SoftUi.setRowEnabled(clockBrightRow, false);
            }
            View[] pePair = SoftUi.toggleWithDependents(this, "posteffect 模糊采样率",
                    store.isPostEffect(), v -> store.setPostEffect(v), sampleRow);
            SoftUi.Card cardPost = SoftUi.card(this, pePair[0], pePair[1]);
            final View[] clearRowRef = new View[1];
            java.util.ArrayList<View> otherRows = new java.util.ArrayList<>();
            otherRows.add(SoftUi.link(this, "背景图片", () -> pickBgImage()));
            if (store.hasBg()) {
                final View clearRow = SoftUi.link(this, "清除背景图",
                        () -> collapseThen(clearRowRef[0], this::clearBgImage));
                clearRowRef[0] = clearRow;
                otherRows.add(clearRow);
            }
            otherRows.add(SoftUi.toggle(this, "日志开关", store.isLog(),
                    v -> store.setLog(v), 0f, false));

            otherRows.add(SoftUi.segmentRow(this, "夜间模式",
                    new String[]{"浅色", "跟随系统", "深色"},
                    themeIndex(store.getThemeMode()),
                    idx -> {
                        int mode = themeModeOf(idx);

                        boolean targetDark = SoftUi.resolveDark(this, mode);
                        store.setThemeMode(mode);
                        lg("theme: 选择 mode=" + mode + " targetDark=" + targetDark
                                + " curDark=" + SoftUi.DARK);
                        if (targetDark == SoftUi.DARK) {
                            lg("theme: 无实际变化，跳过重建");
                            return;
                        }

                        sScrollY = SoftUi.scrollY(shell);

                        android.graphics.Bitmap snap = snapshotView(shell);

                        SoftUi.initTheme(this, mode);
                        setupEdgeToEdge();
                        getWindow().setBackgroundDrawable(
                                new android.graphics.drawable.ColorDrawable(SoftUi.CANVAS));
                        final android.view.View old = shell;
                        android.graphics.Bitmap bg2 = null;
                        if (store.hasBg()) {
                            try {
                                bg2 = android.graphics.BitmapFactory.decodeFile(
                                        store.bgFile().getAbsolutePath());
                            } catch (Throwable ignored) {}
                        }
                        buildUi(uiRoot, old, bg2, true, snap);
                    }));
            SoftUi.Card cardOther = SoftUi.card(this,
                    otherRows.toArray(new View[otherRows.size()]));
            final android.widget.TextView adbStatus =
                    SoftUi.statusText(this, Adb.label());
            SoftUi.Card cardAdb = SoftUi.card(this,
                    SoftUi.action(this, "获取 ADB 权限", adbStatus, () -> {
                        lg("点击「获取 ADB 权限」，当前=" + Adb.label());
                        Adb.request(SettingsActivity.this, () -> {
                            adbStatus.setText(Adb.label());
                            lg("ADB 权限刷新: " + Adb.label());
                        });
                    }));
            lg("ADB 通道: " + Adb.label());
            java.util.List<String> pkgs = store.scopePackages();
            java.util.List<View> scopeRows = new java.util.ArrayList<>();
            for (final String pkg : pkgs) {
                final String appLabel = store.appLabel(pkg);
                scopeRows.add(SoftUi.actionArrowBlock(this,
                        appLabel,
                        pkg,
                        () -> {
                            lg("restart scope request: " + pkg);
                            SoftUi.confirm(shell,
                                    "重启作用域",
                                    "确定重启作用域 " + pkg + " 吗",
                                    "确定", "取消",
                                    () -> doRestartOne(pkg));
                        }));
            }
            scopeRows.add(SoftUi.actionArrowBlock(this, "重启全部",
                    pkgs.size() + " 个应用",
                    () -> {
                        lg("restart ALL scope request: " + pkgs);
                        SoftUi.confirm(shell,
                                "重启作用域",
                                "确定重启作用域全部 " + pkgs.size() + " 个应用吗",
                                "确定", "取消",
                                () -> doRestartAll(pkgs));
                    }));
            SoftUi.Card cardScope = SoftUi.card(this,
                    scopeRows.toArray(new View[scopeRows.size()]));
            final android.widget.TextView updStatus =
                    SoftUi.statusText(this, "检查 GitHub Releases");
            SoftUi.Card cardUpdate = SoftUi.card(this,
                    SoftUi.action(this, "检查更新", updStatus, () -> {
                        updStatus.setText("正在检查…");
                        checkUpdate(updStatus);
                    }),
                    SoftUi.link(this, "更新日志", () -> showChangelog()));
            SoftUi.Card[] about = buildAboutCards();
            SoftUi.Card cardPerm = about[0];
            SoftUi.Card cardAbout = about[1];
            SoftUi.Card cardFiles = about[2];
            SoftUi.Card cardNote = about[3];
            SoftUi.Card cardOss = about[4];
            SoftUi.Card[] cards = { cardFunc, cardImeBlur, cardClock, cardLabelShadow, cardPost, cardOther,
                                    cardAdb, cardScope, cardUpdate, cardPerm, cardAbout, cardFiles, cardNote, cardOss };
            SoftUi.stack(pageSettings,
                SoftUi.group(this, "功能"),
                cardFunc,
                SoftUi.group(this, "输入法模糊"),
                cardImeBlur,
                SoftUi.group(this, "时钟组件模糊"),
                cardClock,
                SoftUi.group(this, "桌面应用名阴影"),
                cardLabelShadow,
                SoftUi.group(this, "posteffect 模糊采样率"),
                cardPost,
                SoftUi.group(this, "其他"),
                cardOther,
                SoftUi.group(this, "ADB 权限"),
                cardAdb,
                SoftUi.group(this, "作用域重启"),
                cardScope
            );
            SoftUi.stack(pageAbout,
                SoftUi.group(this, "更多"),
                cardUpdate,
                SoftUi.group(this, "关于"),
                cardAbout,
                cardFiles,
                cardNote,
                SoftUi.group(this, "权限说明"),
                cardPerm,
                SoftUi.group(this, "开源 API 与项目自有 API"),
                cardOss
            );
            final String[] TABS = { "模块设置", "更多" };
            final SoftUi.TabBar footer = new SoftUi.TabBar(this, TABS, idx -> {
                boolean isSettings = (idx == 0);
                pageSettings.setVisibility(isSettings ? View.VISIBLE : View.GONE);
                pageAbout.setVisibility(isSettings ? View.GONE : View.VISIBLE);
                SoftUi.setTitle(shell, TABS[idx]);
                SoftUi.scrollToTop(shell);
                shell.post(SoftUi::refreshGlass);
                sTab = idx;
                sScrollY = 0;
                lg("切换页面 -> " + TABS[idx]);
            });
            SoftUi.attachFooter(shell, footer, SoftUi.FOOTER_H);

            int startTab = Math.max(0, Math.min(TABS.length - 1, sTab));
            boolean startSettings = (startTab == 0);
            pageSettings.setVisibility(startSettings ? View.VISIBLE : View.GONE);
            pageAbout.setVisibility(startSettings ? View.GONE : View.VISIBLE);
            SoftUi.setTitle(shell, TABS[startTab]);
            footer.select(startTab);

            if (sScrollY > 0) {
                final int restoreY = sScrollY;
                shell.post(() -> SoftUi.scrollToY(shell, restoreY));
            }
            lg("卡片构建完成");
            shell.setFitsSystemWindows(false);
            root.addView(shell, new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            if (bg != null) {
                for (SoftUi.Card c2 : cards) {
                    c2.enableGlass(SoftUi.HEADER_BLUR, SoftUi.GLASS_TINT);
                }
                lg("已为 " + cards.length + " 张卡片开启毛玻璃");
            }
            shell.post(SoftUi::refreshGlass);

            if (needFade && snapshot != null) {
                final android.view.View nw = shell;
                nw.setAlpha(1f);
                final android.widget.ImageView mask = new android.widget.ImageView(this);
                mask.setImageBitmap(snapshot);
                mask.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
                mask.setAlpha(1f);
                root.addView(mask, new android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT));
                if (oldShell != null && oldShell.getParent() == root) root.removeView(oldShell);
                mask.animate().alpha(0f).setDuration(380)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .withEndAction(() -> root.removeView(mask))
                        .start();
                lg("theme cross-fade: 快照遮罩淡出 (" + (snapshot == null ? "null" : snapshot.getWidth() + "x" + snapshot.getHeight()) + ")");
            } else if (needFade && oldShell != null) {
                final android.view.View old = oldShell;
                final android.view.View nw = shell;
                nw.setAlpha(0f);
                old.animate().alpha(0f).setDuration(200)
                        .setInterpolator(new android.view.animation.AccelerateInterpolator())
                        .withEndAction(() -> {
                            root.removeView(old);
                            nw.animate().alpha(1f).setDuration(320)
                                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                                    .start();
                        }).start();
                lg("theme cross-fade: 交叉淡入");
            } else if (needFade) {
                shell.setAlpha(0f);
                shell.animate().alpha(1f).setDuration(420)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
                lg("fade-in: 淡入新界面");
            } else {
                if (oldShell != null && oldShell.getParent() == root) root.removeView(oldShell);
                lg("fade-in: 跳过");
            }
        }
}
