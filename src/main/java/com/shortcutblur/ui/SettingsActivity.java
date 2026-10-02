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

/**
 * 设置界面宿主 —— 用 SoftUi 铺装（含模糊顶栏）。
 *
 */
public class SettingsActivity extends Activity {
    /** 跨 recreate 传递：下一次 onCreate 是否需要“背景切换淡入”。 */
    private static boolean sFadeOnCreate = false;

    /** 背景图最大尺寸：15MB（超过则拒绝）。  */
    private static final int MAX_BG_MB = 15;
    private static final long MAX_BG_BYTES = MAX_BG_MB * 1024L * 1024L;


    static void lg(String s) {
        // 统一日志开关：默认关；与 App.lg() 共用同一判断源（GUI 本地 prefs）
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        lg("=== onCreate 进入 ===");
        try {
            super.onCreate(savedInstanceState);

            store = new SettingsStore(this);

            // 读背景图（若有），失败则退纯色
            android.graphics.Bitmap bg = null;
            if (store.hasBg()) {
                try {
                    bg = android.graphics.BitmapFactory.decodeFile(
                            store.bgFile().getAbsolutePath());
                    lg("背景图已加载: " + (bg != null ? bg.getWidth() + "x" + bg.getHeight() : "null"));
                    // 预模糊由 BackdropView 登记壁纸时自动完成（幂等，卡片/顶栏共用）
                } catch (Throwable t) { lg("背景图加载失败: " + t); }
            }

            // 有背景图时，卡片用真·毛玻璃（Backdrop + StaticGlass）
            SoftUi.CARD_FILL = SoftUi.SURFACE;   // 无背景时仍是纯白

            shell = SoftUi.scrollingScreen(this, store.appName(), bg);
            LinearLayout content = SoftUi.contentOf(shell);
            lg("scrollingScreen OK, content=" + (content != null));

            // —— 两个页面：模块设置 / 关于（同一条 ScrollView，切页只切可见性，不重建）——
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
            // 采样倍率：作为「后处理」的附属项（关闭时一并收纳）
            View sampleRow = SoftUi.slider(this, "采样倍率",
                    store.getSampleScale() * 100f, 0, 100, "%",
                    v -> store.setSampleScale(v / 100f));
            // 功能开关卡片（5 项，与宿主 FeatureFlags 一一对应）
            SoftUi.Card cardFunc = SoftUi.card(this,
                    SoftUi.toggle(this, "Shortcut 实时模糊", store.isShortcut(),
                            v -> store.setShortcut(v)),
                    SoftUi.toggle(this, "最近任务模糊增强", store.isRecents(),
                            v -> store.setRecents(v)),
                    SoftUi.toggle(this, "小组件模糊（含时钟）", store.isWidget(),
                            v -> store.setWidget(v)),
                    SoftUi.toggle(this, "下拉搜索实时模糊", store.isQsProbe(),
                            v -> store.setQsProbe(v)));
            // 后处理（父）+ 采样倍率（子，折叠）：关掉后处理则滑块收起、不参与
            View[] pePair = SoftUi.toggleWithDependents(this, "posteffect 模糊采样率",
                    store.isPostEffect(), v -> store.setPostEffect(v), sampleRow);
            SoftUi.Card cardPost = SoftUi.card(this, pePair[0], pePair[1]);
            // 「背景图片 / 清除背景图」与「日志开关」同一张卡：背景图片正好在日志上一行
            final View[] clearRowRef = new View[1];   // 供收回动画句柄引用自身
            java.util.ArrayList<View> otherRows = new java.util.ArrayList<>();
            otherRows.add(SoftUi.link(this, "背景图片", () -> pickBgImage()));
            if (store.hasBg()) {                       // 只有设过壁纸才给"恢复"入口
                final View clearRow = SoftUi.link(this, "清除背景图",
                        () -> collapseThen(clearRowRef[0], this::clearBgImage));
                clearRowRef[0] = clearRow;              // 供 lambda 回调引用
                otherRows.add(clearRow);
            }
            otherRows.add(SoftUi.toggle(this, "日志开关", store.isLog(),
                    v -> store.setLog(v), 0f, false));   // ★ 日志行：仅开关本体可点
            SoftUi.Card cardOther = SoftUi.card(this,
                    otherRows.toArray(new View[otherRows.size()]));

            // —— ADB 权限（Shizuku / Sui）——
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

            // scope restart card (per-package)
            java.util.List<String> pkgs = store.scopePackages();
            java.util.List<View> scopeRows = new java.util.ArrayList<>();
            for (final String pkg : pkgs) {
                final String appLabel = store.appLabel(pkg);
                scopeRows.add(SoftUi.actionArrowBlock(this,
                        appLabel,                           // friendly app name (line 1)
                        pkg,                               // package name (line 2, wrappable)
                        () -> {
                            lg("restart scope request: " + pkg);
                            // ★ 确认弹窗
                            SoftUi.confirm(shell,
                                    "重启作用域",
                                    "确定重启作用域 " + pkg + " 吗",
                                    "确定", "取消",
                                    () -> doRestartOne(pkg));
                        }));
            }
            // bottom: restart all
            scopeRows.add(SoftUi.actionArrowBlock(this, "重启全部",
                    pkgs.size() + " 个应用",
                    () -> {
                        lg("restart ALL scope request: " + pkgs);
                        // ★ 确认弹窗
                        SoftUi.confirm(shell,
                                "重启作用域",
                                "确定重启作用域全部 " + pkgs.size() + " 个应用吗",
                                "确定", "取消",
                                () -> doRestartAll(pkgs));
                    }));
            SoftUi.Card cardScope = SoftUi.card(this,
                    scopeRows.toArray(new View[scopeRows.size()]));

            // update card
            final android.widget.TextView updStatus =
                    SoftUi.statusText(this, "检查 GitHub Releases");
            SoftUi.Card cardUpdate = SoftUi.card(this,
                    SoftUi.action(this, "检查更新", updStatus, () -> {
                        updStatus.setText("正在检查…");
                        checkUpdate(updStatus);
                    }));

            // 权限说明卡片
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
            // 模块文件位置说明
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
            // 开源 API 致谢
            SoftUi.Card cardOss = SoftUi.card(this,
                    SoftUi.infoBlock(this, "libxposed API (102)",
                            "libxposed / LSPosed  ·  遵循其自身许可",
                            () -> openUrl("https://github.com/libxposed")),
                    SoftUi.infoBlock(this, "Shizuku API",
                            "RikkaApps  ·  Apache-2.0",
                            () -> openUrl("https://github.com/RikkaApps/Shizuku-API")));

            SoftUi.Card[] cards = { cardFunc, cardPost, cardOther,
                                    cardAdb, cardScope, cardUpdate, cardPerm, cardAbout, cardFiles, cardNote, cardOss };

            SoftUi.stack(pageSettings,
                SoftUi.group(this, "功能"),
                cardFunc,
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

            // —— 底栏：两个标签，切换页面 ——
            final String[] TABS = { "模块设置", "更多" };
            final SoftUi.TabBar footer = new SoftUi.TabBar(this, TABS, idx -> {
                boolean isSettings = (idx == 0);
                pageSettings.setVisibility(isSettings ? View.VISIBLE : View.GONE);
                pageAbout.setVisibility(isSettings ? View.GONE : View.VISIBLE);
                SoftUi.setTitle(shell, TABS[idx]);       // 顶栏标题跟着页走
                SoftUi.scrollToTop(shell);               // 切页回到顶部
                shell.post(SoftUi::refreshGlass);        // 布局定了再刷玻璃层
                lg("切换页面 -> " + TABS[idx]);
            });
            SoftUi.attachFooter(shell, footer, SoftUi.FOOTER_H);
            SoftUi.setTitle(shell, TABS[0]);

            // 有背景图：给每张卡片开真·毛玻璃（30dp，与顶栏一致）
            if (bg != null) {
                for (SoftUi.Card c2 : cards) {
                    c2.enableGlass(SoftUi.HEADER_BLUR, SoftUi.GLASS_TINT);
                }
                lg("已为 " + cards.length + " 张卡片开启毛玻璃");
            }

            lg("卡片构建完成");

            setContentView(shell);
            // 背景变化引起的重建：整体淡入，避免背景突冫出现/消失
            if (sFadeOnCreate) {
                sFadeOnCreate = false;
                shell.setAlpha(0f);
                shell.animate().alpha(1f).setDuration(240)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
            }
            setupEdgeToEdge();
            // 关键：让 shell 自己铺满 DecorView（不被系统 inset 推下去）
            try {
                android.view.View decor = getWindow().getDecorView();
                if (decor instanceof android.view.ViewGroup) {
                    ((android.view.ViewGroup) decor).setFitsSystemWindows(false);
                }
                shell.setFitsSystemWindows(false);
                shell.setOnApplyWindowInsetsListener((v, insets) -> {
                    // 不清除 inflate 的 offset，只保证 shell 从 (0,0) 开始
                    return insets;
                });
                lg("shell inset ok: shellTop=" + shell.getTop() + " shellH=" + shell.getHeight());
            } catch (Throwable t) { lg("shell inset 失败: " + t); }
            lg("setContentView OK —— onCreate 完成");
        } catch (Throwable t) {
            lg("!!! 异常: " + t);
            for (StackTraceElement e : t.getStackTrace()) lg("    at " + e);
            throw t;
        }
    }

    /** 打开外部链接（浏览器）。 */
    // ============================================================
    // 背景图：选择 / 保存 / 回调
    // ============================================================

    private static final int REQ_PICK_BG = 1001;

    /** 调起系统图片选择器。 */
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

    /**
     * 先把某行高度从当前收到 0（回收动画），动画结束后执行 after。
     * 用于“清除背景图”这类“点完就消失”的入口，避免突冫跳变。
     */
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

    /** 重启单个作用域应用（确认后真正执行）。 */
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

    /** 重启全部作用域应用（确认后真正执行）。 */
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

    /** 清除自定义背景图（恢复纯色），然后重建界面。 */
    private void clearBgImage() {
        try {
            store.clearBg();
            SoftUi.clearBackdrop();   // 必须：清掉静态壁纸引用，否则顶栏/底栏还糊着旧图
            lg("背景图已清除（恢复纯色）");
        } catch (Throwable t) {
            lg("clearBgImage 失败: " + t);
        }
        sFadeOnCreate = true;   // 重建后整体淡入
        recreate();
    }

    /** 从 content URI 探查文件大小（字节）；拿不到返回 -1。 */
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

    /** 把选中的图复制到应用私有目录（避免 URI 权限失效）。 */
    private boolean copyBgToPrivate(Intent data) {
        try {
            if (data == null || data.getData() == null) return false;
            Uri uri = data.getData();
            // 尝试持久化权限（下次开机仍可读，虽然我们已经复制了）
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {}

            // ① 先探查文件大小（OpenableColumns.SIZE），超限直接拒绝
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
                // ② 流式守位：实际写入超限（某些 provider 不报 SIZE）
                if (written > MAX_BG_BYTES) { tooBig = true; break; }
                out.write(buf, 0, n);
            }
            out.flush(); out.close(); in.close();
            if (tooBig) {
                dst.delete();                      // 删半成品
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
            // 重建界面以应用背景（整体淡入）
            sFadeOnCreate = true;
            recreate();
        }
    }

    /**
     * 检查 GitHub Releases 是否有更新。
     * 请求 api.github.com 的 latest release，解析 tag_name，与本地版本比对。
     */
    /** 把 GitHub tag 解析为与 versionCode 同语义的数字（宽松）。
     *  v43 -> 430 ; v43.0 -> 430 ; v42.5 -> 425 ; release-v43-beta -> 430
     *  解析不出返回 -1。 */
    private static long tagToCode(String tag) {
        if (tag == null) return -1L;
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < tag.length(); i++) {
            char c = tag.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.') d.append(c);
        }
        String num = d.toString();
        if (num.length() == 0) return -1L;
        // 去掉首尾多余的点
        while (num.startsWith(".")) num = num.substring(1);
        while (num.endsWith(".")) num = num.substring(0, num.length() - 1);
        if (num.length() == 0) return -1L;
        String[] parts = num.split("\\.");
        try {
            long major = Long.parseLong(parts[0]);
            long minor = 0L;
            if (parts.length >= 2 && parts[1].length() > 0) {
                String mn = parts[1];
                if (mn.length() > 1) mn = mn.substring(0, 1);   // 只取第 1 位（3位码规则）
                minor = Long.parseLong(mn);
            }
            return major * 10L + minor;
        } catch (Throwable ignored) {}
        return -1L;
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
                        // 版本比较：仅当 GitHub 版本「高于」本地时才提示新版本
                        long localCode = store.versionCode();          // 本地，如 430
                        long remoteCode = tagToCode(tag);             // GitHub tag -> 同语义数字，如 v43 -> 430
                        String localName = store.versionName();
                        if (remoteCode <= 0) {
                            // tag 无法解析出数字：宽松处理，只判不同
                            msg = tag.equalsIgnoreCase(localName)
                                    ? ("已是最新 " + tag)
                                    : ("发现新版本 " + tag);
                        } else if (remoteCode > localCode) {
                            msg = "发现新版本 " + tag;
                        } else {
                            msg = "已是最新 " + tag;   // 相等 或 远程更旧，均视为最新
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

    /** 从 JSON 文本里抠出 "key":"value" 的字符串值（够用即可，不引 JSON 库）。 */
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


    /** 沉浸式：内容延伸到状态栏/导航栏下，状态栏图标适配深色背景。 */
    private void setupEdgeToEdge() {
        android.view.Window win = getWindow();
        // 0) 允许内容画到刘海/状态栏区域
        try {
            android.view.WindowManager.LayoutParams lp = win.getAttributes();
            lp.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            win.setAttributes(lp);
        } catch (Throwable t) { lg("edge: cutout 失败 " + t); }
        // 0) 允许内容画到刘海/状态栏区域
        try {
            android.view.WindowManager.LayoutParams lp = win.getAttributes();
            lp.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            win.setAttributes(lp);
        } catch (Throwable t) { lg("edge: cutout 失败 " + t); }
        // 1) 状态栏/导航栏透明（Window 级，无需 DecorView）
        try {
            win.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            win.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        } catch (Throwable t) { lg("edge: barColor 失败 " + t); }
        // 2) 内容延伸到系统栏下（同上，Window 级）
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                win.setDecorFitsSystemWindows(false);
            }
        } catch (Throwable t) { lg("edge: decorFits 失败 " + t); }
        // 3) 状态栏图标颜色（需要 DecorView，必须 setContentView 之后调用）
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController c = win.getInsetsController();
                if (c != null) {
                    c.setSystemBarsAppearance(
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                    lg("edge: insetsController OK");
                } else {
                    lg("edge: insetsController null");
                }
            } else {
                win.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            }
        } catch (Throwable t) {
            lg("edge: iconAppearance 失败 " + t);
        }
    }
}