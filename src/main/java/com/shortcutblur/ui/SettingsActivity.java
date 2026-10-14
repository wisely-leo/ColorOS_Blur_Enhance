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

    private static final String LOG_TAG_VER = "v46.2";
    static void lg(String s) {
        if (s == null) return;
        if (!App.logEnabled()) return;
        try { android.util.Log.i("SoftUi", s); } catch (Throwable ignored) {}
        // 统一走 UiLog：持久 Writer + 2MB 轮转，避免每条日志 open/close 抖动与无限增长。
        UiLog.write(s);
    }
private SettingsStore store;
    private View shell;

    private android.view.ViewGroup uiRoot;

    // 生命周期兜底：在途后台线程完成后回主线程时，若 Activity 已销毁则不再操作 UI。
    private volatile boolean destroyed = false;

    @Override
    protected void onDestroy() {
        destroyed = true;
        // 断开静态/长生命周期引用，帮助 GC 回收 Activity 与其 View 树。
        try { SoftUi.clearBackdrop(); } catch (Throwable ignored) {}
        try { SoftUi.GlassSyncUnhook(); } catch (Throwable ignored) {}
        // 移除可能残留的 Shizuku 权限回调监听器（它静态持有本 Activity）。
        try { Adb.removePending(); } catch (Throwable ignored) {}
        adbGateShowing = false;
        shell = null;
        uiRoot = null;
        store = null;
        super.onDestroy();
    }

    // ===================== ADB(Shizuku) 权限门禁 =====================
    // 每次打开 GUI 检测一次 ADB 权限；无权限则弹窗要求获取，
    // 用户拒绝 / 获取失败 / 点了退出 -> finish() 关闭界面。
    //
    // 线程安全约定：
    //  - Shizuku 权限回调在【非主线程】触发，且可能在 Activity 进入 onDestroy 之后到达；
    //  - onDestroy 会把 uiRoot 置 null，因此在回调里【绝不能】直接 uiRoot.post(...)，
    //    必须先经 safePost() 判空，避免空指针崩溃与对已销毁视图的无意义操作。
    private android.view.ViewGroup curRoot() {
        return destroyed ? null : uiRoot;
    }

    /** 安全 post：Activity 已销毁或 uiRoot 已置 null 时静默丢弃。 */
    private void safePost(final Runnable r) {
        final android.view.ViewGroup root = curRoot();
        if (root == null || r == null) return;
        try {
            root.post(() -> {
                if (destroyed || uiRoot == null) return;   // 执行前再判一次（post 是异步的）
                try { r.run(); } catch (Throwable t) { lg("[safePost] 任务异常: " + t); }
            });
        } catch (Throwable ignored) {}
    }

    // 门禁弹窗同一时刻只允许一个（防止用户连点导致 overlay 叠层）。
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

        final boolean connected = (st == Adb.NO_PERM); // Shizuku 已运行，仅缺授权
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
            // 统一在弹窗消失后清标记，保证同一时刻只有一个门禁弹窗。
            Runnable clearFlag = () -> adbGateShowing = false;
            SoftUi.confirm(uiRoot, title, msg,
                    connected ? "立即获取" : "重试",
                    "退出",
                    () -> {
                        clearFlag.run();
                        lg("[ADB门禁] 用户选择获取/重试");
                        if (!connected) {
                            // Shizuku 未连接：重试一次检测；仍无则再次弹窗
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
                        // 已连接但未授权：走 Shizuku 授权流程
                        // 注意：done 回调可能在 Activity 销毁后到达（见 Adb.request），
                        // 这里不直接碰 uiRoot，统一交给 safePost 判空。
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
            // ADB(Shizuku) 权限门禁：无权限则弹窗要求获取，拒绝/获取失败则退出 GUI。
            // 用 safePost：Activity 若在此刻已销毁则静默丢弃，避免校验已销毁视图。
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
    private static long tagToCode(String tag) {
        if (tag == null) return -1L;
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < tag.length(); i++) {
            char c = tag.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.') d.append(c);
        }
        String num = d.toString();
        if (num.length() == 0) return -1L;
        while (num.startsWith(".")) num = num.substring(1);
        while (num.endsWith(".")) num = num.substring(0, num.length() - 1);
        if (num.length() == 0) return -1L;
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
            "v46.2\n"
            + "· 新增「桌面应用名阴影（iOS 风）」\n"
            + "  · 应用名常驻一层柔和阴影，亮 / 暗壁纸下都清晰（原生只在暗壁纸下才有）\n"
            + "  · 原生亮壁纸会直接清空阴影；现改为常驻，主题 / 壁纸 / 透明度动画都清不掉\n"
            + "  · 亮色文字配暗色阴影（iOS 观感），暗色文字配亮色阴影保证可读\n"
            + "  · 新增热更参数 launcher_label_shadow / _radius_dp / _dy_dp / _dark / _light\n"
            + "  · 设置界面新增独立开关，并附带可折叠参数：阴影模糊 / 向下偏移 / 阴影颜色\n"
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
            + "· 最近任务缩放下限由 0.92 调整为 0.96（卡片不再缩得过小，观感更稳）\n"
            + "· 修复全局搜索进不去背景模糊偶发报错（移除已失效的窗口背景 Hook）\n"
            + "· 日志降噪：模糊采样率 / 时钟文字颜色两类高频日志按状态去重，避免刷屏\n"
            + "· 稳定性与可维护性内部改进（异常收口、反射符号集中、代码结构整理）\n"
            + "· 内部版本号同步至 v45.5.1\n"
            + "\n"
            + "v45.5\n"
            + "· 统一日志开关：界面日志 / 功能日志共用同一开关\n"
            + "  · 关闭日志后不再有任何后台写文件，减少无谓 IO\n"
            + "  · 修复个别日志在开关关闭时仍会写入的问题\n"
            + "· 性能优化（拖动 / 滑动更顺滑，减少卡顿与内存抖动）\n"
            + "  · 色相滑块拖动时不再每帧分配临时数组\n"
            + "  · 彩色渐变缓存随尺寸变化正确失效，避免显示错位\n"
            + "  · 卡片点按高亮背景在参数未变时复用，避免重复创建\n"
            + "· 内部版本号统一同步至 v45.5\n"
            + "\n"
            + "v45.4\n"
            + "· 新增「ADB 权限检测」：打开设置界面时自动检测 ADB(Shizuku) 权限\n"
            + "  · 未授权时弹窗引导获取；拒绝授权或获取失败则退出设置界面\n"
            + "  · 未检测到 Shizuku 服务时提示先启动 Shizuku，并支持重试\n"
            + "  · 授权成功后即时放行，无需重启界面\n"
            + "· 适配真我 (realme) 日历/天气横向小组件（weather_widget_layout）\n"
            + "  · 新增识别日历日期文字（calendar）与组件根容器，模糊可正常呈现\n"
            + "· 时钟模糊：真我 (realme) 组件改用视图透明度（setAlpha）控制\n"
            + "  · 消除「实心白 / 模糊」来回闪烁，透明来源唯一，调节线性直观\n"
            + "· 日志完善：启动时记录系统版本 / 机型 / 进程等环境信息\n"
            + "· 内部版本号统一同步至 v45.4\n"
            + "\n"
            + "v45.3\n"
            + "· 修复时钟模糊「长时间不可见（待机/切页）后再次可见时不更新」的问题\n"
            + "  · 不可见期间的时间变化不再被丢弃，恢复可见立即重建，无需等待轮询\n"
            + "  · 灭屏期间保留低频探测，避免广播丢失后状态机停摆\n"
            + "· 修复亮屏使用时偶发的发热问题\n"
            + "  · 重建任务的排队与取消统一到同一消息队列，消除任务堆积\n"
            + "· 修复多处内存泄漏与资源未释放问题\n"
            + "  · 输入法模糊缓存改为弱引用，避免长期持有视图\n"
            + "  · 图标模糊动画结束后及时清理，避免动画对象累积\n"
            + "  · 时钟重建等待回调在视图移除时主动解除，避免残留\n"
            + "· 提升稳定性：hook 安装增加幂等保护与失败重试能力\n"
            + "· 性能优化：减少重复遍历与内存分配，降低单次重建开销\n"
            + "· 内部清理与优化：移除无用代码，降低内存与体积\n"
            + "\n"
            + "v45.2\n"
            + "· 最近任务模糊判据重构：改用系统壁纸缩放信号（app_recent_enter/exit）驱动进出\n"
            + "  · 进出响应更快、更稳；状态机降级为延迟兜底（壁纸信号缺失时补位）\n"
            + "· 修复从应用返回桌面时偶发误判「进入最近任务」导致的假模糊\n"
            + "· 键盘按键配色跟随系统深浅色切换（深色下自动适配）\n"
            + "· 内部清理与优化：移除大量无用代码，降低内存与体积\n"
            + "\n"
            + "v45.1\n"
            + "· 键盘按键配色优化：按键背景改为「半透明白玻璃」观感（仿 iOS）\n"
            + "  · 回车键保持系统强调色（原生行为）\n"
            + "· 稳定性与内存优化\n"
            + "  · 修复多处内存泄漏与资源未释放问题，降低长时间使用后的内存占用\n"
            + "  · 修复网络请求、日志读取等场景下的资源泄漏\n"
            + "· 日志文件不再无限增长（自动轮转，单个上限 2MB）\n"
            + "  · 界面侧日志改为常驻写入，减少反复读写带来的卡顿\n"
            + "· 键盘模糊圆角固定为安全值，避免偶发崩溃\n"
            + "\n"
            + "v45\n"
            + "· 新增「输入法键盘模糊」（仅适配语燕输入法）\n"
            + "  · 让输入法键盘背景呈现高斯模糊，与系统其他模糊区域观感统一\n"
            + "  · 可调：模糊程度 / 键盘圆角 / 白灰蒙版强度\n"
            + "  · 启用前会提示适配范围；不使用该输入法则无需开启\n"
            + "  · 需在 LSPosed 中把本模块作用域勾选「语燕输入法」后重启生效\n"
            + "· 输入法模糊性能优化（减少重复计算，降低卡顿）\n"
            + "  · 深色判定结果缓存，避免每帧重复读取系统配置\n"
            + "  · 键盘根布局按类缓存查找结果，避免反复反射\n"
            + "  · 同一帧内多次触发去抖合并，避免重复施模糊\n"
            + "· 适配逻辑去硬编码：改为「候选名 + 特征校验」自动识别，\n"
            + "  宿主列表改为读取模块作用域自动同步，不再写死\n"
            + "· 设置界面：带子选项的选项统一改用可折叠控件（开关展开/收起）\n"
            + "· 启用「输入法键盘模糊」前新增适配范围提示弹窗\n"
            + "· 作用域重启列表的应用名改用中文显示（时钟 / 桌面 / 相册 等）\n"
            + "\n"
            + "v44.3\n"
            + "· 新增时钟组件「自定义混色」：可调色相 + 手工输入色号，向时钟混入自定义颜色\n"
            + "  · 色相条与色号输入双向联动，实时同步\n"
            + "· 设置界面弹窗重构：统一为通用弹窗骨架，三个弹窗共用\n"
            + "  · 色号输入弹窗、确认弹窗、信息弹窗样式与键盘避让一致\n"
            + "  · 弹窗毛玻璃随位置实时重采样，键盘顶起不错位\n"
            + "· 修复深色模式切回浅色后，禁用控件灰度偏深的问题\n"
            + "· 修复一级页面底部选项被底栏遮挡的问题\n"
            + "· 清理无调用的冗余代码与行内注释\n"
            + "\n"
            + "v44.2\n"
            + "· 新增「相册强制白色主题」（默认开启）\n"
            + "  · 强制相册照片页使用浅色背景\n"
            + "  · 需在 LSPosed 中把本模块作用域勾选「相册」后重启相册生效\n"
            + "· 调整升级迁移策略：「最近任务模糊增强」仅在从 v44 及更早版本升级时自动关闭一次；\n"
            + "  从 v44.1 及更高版本升级将完全尊重你的设置（不再强制关闭）\n"
            + "\n"
            + "v44.1\n"
            + "· 适配 17 版时钟组件（布局改版导致旧 ID 白名单失效）\n"
            + "· 修复 4×2 布局组件刚放置时无模糊的问题\n"
            + "· 「最近任务模糊增强」调整为实验性功能（默认关闭）\n"
            + "· 升级迁移：本次更新后自动关闭「最近任务模糊增强」（需手动重新启用）\n"
            + "· 日志按进程分文件，避免多进程互相覆盖\n"
            + "· 修复「检查更新」在本地版本较新时显示错误版本号的问题\n"
            + "· 弹窗布局优化：自适应高度、正文过长可滚动\n"
            + "· 设置界面全面打磨：\n"
            + "  · 主题切换改为「原地重建 + 旧界面快照交叉淡入」，内容不再闪现\n"
            + "  · 切换主题时状态栏/导航栏图标正确反色\n"
            + "  · 深色模式全套配色适配\n"
            + "  · 「夜间模式」改为三段式分段控件（浅色 / 跟随系统 / 深色）\n"
            + "  · 分段控件支持跟手拖动，点击不再误滑到相邻选项\n"
            + "  · 滑块 / 分段控件 / 开关旋钮新增点按缩放反馈（按下放大 12%，松手缩回）\n"
            + "  · 进度条滑块静止时缩小、拖动时放大\n"
            + "  · 修复可折叠选项展开/折叠时内容不居中、位置跳变的问题\n"
            + "  · 修复「开源 API 致谢」条目缺少点按高亮的问题\n"
            + "  · 行内点按高亮铺满卡片边缘，首/末行圆角贴合卡片轮廓\n"
            + "  · 主题切换后保留当前分页与滚动位置\n"
            + "\n"
            + "v44\n"
            + "· 新增「下拉搜索实时模糊」\n"
            + "\n"
            + "v43.1\n"
            + "· 时钟组件模糊全面重构，适配更多组件布局\n"
            + "\n"
            + "v43\n"
            + "· 新增图形化设置界面\n"
            + "\n"
            + "v42.5\n"
            + "· 多任务模糊重做\n"
            + "\n"
            + "完整历史见项目 README";

    private void showChangelog() {
        try {
            SoftUi.infoDialog(shell, "更新日志", CHANGELOG, "关闭");
        } catch (Throwable t) {
            lg("showChangelog fail: " + t);
        }
    }
    private void checkUpdate(final android.widget.TextView status) {
        final SettingsStore st = store;   // 捕获局部引用，避免 onDestroy 置 null 后 NPE
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
                        // finally 关闭：中途抛异常时也要释放连接流。
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
                // 无论成功/异常都断开连接，避免 socket 滞留。
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
            // 键盘圆角固定 25dp（见 FeatureFlags.IME_BLUR_CORNER_DP 注释）：
            // 系统模糊圆角 >25dp 会触发 native 崩溃，故不再提供自定义滑块。
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
                    recentsRow);

            // 桌面应用名阴影：独立卡片 + 可折叠参数（开启后才展开）
            final float[] lsRadiusRef = { store.getLabelShadowRadius() };
            final float[] lsDyRef     = { store.getLabelShadowDy() };
            final int[]   lsDarkRef   = { store.getLabelShadowDark() };
            View lsRadiusRow = SoftUi.slider(this, "阴影模糊",
                    lsRadiusRef[0], 0f, 10f, "dp", 0.5f,
                    v -> { lsRadiusRef[0] = v; store.setLabelShadowRadius(v); });
            View lsDyRow = SoftUi.slider(this, "向下偏移",
                    lsDyRef[0], -3f, 3f, "dp", 0.5f,
                    v -> { lsDyRef[0] = v; store.setLabelShadowDy(v); });
            final View[] lsDarkTextRef = new View[1];
            final float[] lsDarkFracRef = { lsDarkRef[0] / 255f };
            View lsDarkRow = SoftUi.colorSlider(this, "阴影颜色",
                    lsDarkFracRef[0],
                    (color, frac) -> {
                        lsDarkFracRef[0] = frac;
                        int argb = (color & 0x00FFFFFF) | 0x70000000;
                        lsDarkRef[0] = argb;
                        store.setLabelShadowDark(argb);
                        if (lsDarkTextRef[0] instanceof android.widget.TextView) {
                            ((android.widget.TextView) lsDarkTextRef[0]).setText(
                                    "#" + Integer.toHexString(argb).toUpperCase());
                        }
                    });
            lsDarkTextRef[0] = lsDarkRow.findViewById(SoftUi.ROW_TITLE_ID);
            View[] lsRows = SoftUi.toggleWithDependents(this, "桌面应用名阴影（iOS 风）",
                    store.isLabelShadow(), null, lsRadiusRow, lsDyRow, lsDarkRow);
            final SoftUi.Row lsToggleRow = (SoftUi.Row) lsRows[0];
            final View lsFoldHolder = lsRows[1];
            lsToggleRow.setOnToggle(v -> {
                final SoftUi.Switch sw = lsToggleRow.switchView();
                if (!v) {
                    store.setLabelShadow(false);
                    SoftUi.applyFold(lsFoldHolder, false, true);
                    return;
                }
                SoftUi.confirm(shell,
                        "启用桌面应用名阴影",
                        "把桌面应用名换成 iOS 风的常驻柔和阴影：\n"
                                + "· 亮 / 暗壁纸下都有阴影（原生只在暗壁纸下才有）\n"
                                + "· 亮色文字配暗色阴影，暗色文字配亮色阴影\n"
                                + "· 原生只为默认主题文字色生效，这里统一接管\n\n"
                                + "开关在运行期即时生效：新绑定的应用名立刻带上阴影，已经画在屏上的图标"
                                + "下次重绑（切页 / 换壁纸 / 密码解锁）或重启桌面后生效。\n\n确定启用吗？",
                        "确定启用", "取消",
                        () -> {
                            store.setLabelShadow(true);
                            SoftUi.applyFold(lsFoldHolder, true, true);
                        },
                        () -> {
                            if (sw != null) sw.setCheckedImmediate(false);
                            SoftUi.applyFold(lsFoldHolder, false, false);
                        });
            });
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

            View[] clockRows = SoftUi.toggleWithDependents(this, "时钟组件模糊",
                    store.isWidget(), v -> {
                        store.setWidget(v);
                        if (!v) {

                            SoftUi.setToggleChecked(glassRows[0], false, true);
                        }
                        syncGlassEnable.run();
                    },
                    clockAlphaRow, clockBrightRow, glassRows[0]);
            SoftUi.Card cardClock = SoftUi.card(this, clockRows[0], clockRows[1], glassRows[1]);

            syncGlassEnable.run();
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
                    SoftUi.link(this, "GitHub 项目主页", () -> openUrl(GITHUB)));
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
            SoftUi.Card[] cards = { cardFunc, cardImeBlur, cardClock, cardPost, cardOther,
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
