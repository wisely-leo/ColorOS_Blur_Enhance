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

    private static final String LOG_TAG_VER = "v44.3";
    static void lg(String s) {
        if (!App.logEnabled()) return;
        android.util.Log.i("SoftUi", s);
        try {
            for (String d : new String[]{
                    "/storage/emulated/0/Download",
                    "/sdcard/Download",
                    "/storage/emulated/0"}) {
                File dir = new File(d);
                if (!dir.exists() || !dir.canWrite()) continue;
                FileOutputStream fo = new FileOutputStream(
                        new File(dir, "UiStartup.log"), true);
                OutputStreamWriter w = new OutputStreamWriter(fo, "UTF-8");
                w.write(s + "\n");
                w.flush();
                w.close();
                return;
            }
        } catch (Throwable ignored) {}
    }
    private SettingsStore store;
    private View shell;

    private android.view.ViewGroup uiRoot;

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
                android.widget.Toast.makeText(SettingsActivity.this,
                        pkg + " -> " + (r.startsWith("!") ? "失败" : "已重启"),
                        android.widget.Toast.LENGTH_SHORT).show();
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
                android.widget.Toast.makeText(SettingsActivity.this,
                        "重启全部 -> " + (r.startsWith("!") ? "失败（见日志）" : "已重启"),
                        android.widget.Toast.LENGTH_SHORT).show();
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
                android.widget.Toast.makeText(this,
                        "图片过大（超过 " + MAX_BG_MB + "MB），请换一张",
                        android.widget.Toast.LENGTH_LONG).show();
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
                android.widget.Toast.makeText(this,
                        "图片过大（超过 " + MAX_BG_MB + "MB），请换一张",
                        android.widget.Toast.LENGTH_LONG).show();
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
            "v44.3\n"
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
        new Thread(() -> {
            String msg;
            try {
                String api = "https://api.github.com/repos/wisely-leo/ColorOS_Blur_Enhance/releases/latest";
                HttpURLConnection conn = (HttpURLConnection) new URL(api).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "ColorOSBlurEnhance-GUI");
                int code = conn.getResponseCode();
                if (code != 200) {
                    msg = "!HTTP " + code;
                } else {
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    String body = sb.toString();
                    String tag = jsonStr(body, "tag_name");
                    if (tag == null || tag.length() == 0) {
                        msg = "!无法解析版本";
                    } else {
                        long localCode = store.versionCode();
                        long remoteCode = tagToCode(tag);
                        String localName = store.versionName();
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
                conn.disconnect();
            } catch (Throwable t) {
                lg("checkUpdate fail: " + t);
                msg = "!网络错误";
            }
            final String m = msg;
            runOnUiThread(() -> {
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
            SoftUi.Card cardFunc = SoftUi.card(this,
                    SoftUi.toggle(this, "Shortcut 实时模糊", store.isShortcut(),
                            v -> store.setShortcut(v)),
                    SoftUi.toggle(this, "下拉搜索实时模糊", store.isQsProbe(),
                            v -> store.setQsProbe(v)),
                    SoftUi.toggle(this, "相册强制白色主题", store.isGalleryLight(),
                            v -> store.setGalleryLight(v)));

            View imeRadiusRow = SoftUi.slider(this, "模糊程度", store.getImeBlurRadius(), 0f, 350f, "",
                    v -> store.setImeBlurRadius(v));
            View imeCornerRow = SoftUi.slider(this, "键盘圆角", store.getImeBlurCornerDp(), 0f, 48f, "dp",
                    v -> store.setImeBlurCorner(v));
            View imeMaskRow = SoftUi.slider(this, "白灰蒙版强度", store.getImeBlurMask() * 100f, 0f, 100f, "%",
                    v -> store.setImeBlurMask(v / 100f));

            View[] imeRows = SoftUi.toggleWithDependents(this, "输入法键盘模糊",
                    store.isImeBlur(), v -> store.setImeBlur(v),
                    imeRadiusRow, imeCornerRow, imeMaskRow);

            SoftUi.Card cardImeBlur = SoftUi.card(this, imeRows[0], imeRows[1]);

            final SoftUi.Row recentsRow = SoftUi.toggle(this,
                    "最近任务模糊增强（实验性）", store.isRecents(), null, 0f, false);
            recentsRow.setOnToggle(v -> {
                final SoftUi.Switch sw = recentsRow.switchView();
                if (!v) {

                    store.setRecents(false);
                    return;
                }

                SoftUi.confirm(shell,
                        "启用实验性功能",
                        "「最近任务模糊增强」经过多次版本更迭验证为不稳定，"
                                + "部分 bug 难以修复（如快速上滑时模糊突变为衰减、"
                                + "偶发不生效等）。\n\n确定启用吗？",
                        "确定启用", "取消",
                        () -> {
                            lg("experimental recents ENABLED by user");
                            store.setRecents(true);
                        },
                        () -> {
                            if (sw != null) sw.setCheckedImmediate(false);
                        });
            });

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
            SoftUi.Card cardExperimental = SoftUi.card(this, recentsRow);
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
                    SoftUi.infoBlock(this, "libxposed API (102)",
                            "libxposed / LSPosed  ·  遵循其自身许可",
                            () -> openUrl("https://github.com/libxposed")),
                    SoftUi.infoBlock(this, "Shizuku API",
                            "RikkaApps  ·  Apache-2.0",
                            () -> openUrl("https://github.com/RikkaApps/Shizuku-API")));
            SoftUi.Card[] cards = { cardFunc, cardImeBlur, cardClock, cardPost, cardOther, cardExperimental,
                                    cardAdb, cardScope, cardUpdate, cardPerm, cardAbout, cardFiles, cardNote, cardOss };
            SoftUi.stack(pageSettings,
                SoftUi.group(this, "功能"),
                cardFunc,
                SoftUi.group(this, "输入法模糊"),
                cardImeBlur,
                SoftUi.group(this, "时钟组件模糊"),
                cardClock,
                SoftUi.group(this, "实验性功能"),
                cardExperimental,
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
                SoftUi.group(this, "开源 API 致谢"),
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
