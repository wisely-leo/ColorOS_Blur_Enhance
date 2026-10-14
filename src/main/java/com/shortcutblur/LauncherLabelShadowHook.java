package com.shortcutblur;

import android.view.View;
import android.widget.TextView;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;

/**
 * 桌面应用名阴影（iOS 风：常驻的柔和暗色阴影，贴在文字下方）。
 *
 * <p>依据反编译的 OplusLauncher 16.6.17（Operit/archives/OplusLauncher_16.6.17）梳理出的原生链路：
 * <ul>
 *   <li>{@code com.android.launcher3.BubbleTextView#refreshShadowLayer()}（唯一决定应用名阴影的方法）
 *       只在「默认主题文字色 && 壁纸不亮 && IBubbleTextViewExt.getTitleShadowFlag() && mTextAlpha != 0」
 *       时才挂阴影，参数固定 {@code setShadowLayer(8f, 0f, 0f, 0x4d000000 * mTextAlpha)}；</li>
 *   <li>其余情况（尤其<b>亮壁纸</b>）直接 {@code setShadowLayer(0,0,0,0)} 清掉；</li>
 *   <li>{@code WallpaperUtil.updateTextShadow(boolean, TextView)} 也是同一套 8f / 0x4d000000 逻辑，
 *       且亮壁纸同样清空；</li>
 *   <li>继承关系：{@code OplusBubbleTextView extends BubbleTextView extends TextView}，
 *       桌面 / 全部应用 / 文件夹里的应用名都是它们。</li>
 * </ul>
 *
 * <p>因此原生阴影是「有时有、有时没有」，而且没有向下偏移。这里改成 iOS 那样的常驻柔和阴影：
 * <ol>
 *   <li>拦截 {@code TextView#setShadowLayer}：宿主是桌面应用名 View 时换成我们的参数。
 *       连「清除阴影」的调用也会被换成阴影 → 阴影不会被主题 / 壁纸 / 透明度动画清掉；</li>
 *   <li>在 {@code BubbleTextView#refreshShadowLayer()} 之后补一次，
 *       覆盖「从不调用 setShadowLayer」的机型 / 分支；</li>
 *   <li>在 {@code BubbleTextView#applyLabel(...)} 之后补一次，
 *       让 GUI（{@code launcher_label_shadow} 开关）刚打开时能尽快铺到已有图标上。</li>
 * </ol>
 * 阴影恒为纯黑，只有「浓度」（alpha）可调；不做亮色文字配亮色阴影的自适应。
 * 全部参数走 FeatureFlags，可热更（LAUNCHER_LABEL_SHADOW*）。
 */
final class LauncherLabelShadowHook {

    private static final String TAG = "LABELSHADOW";
    private static final String CLS_BUBBLE_TV = "com.android.launcher3.BubbleTextView";
    private static final String CLS_OPLUS_BTV = "com.android.launcher3.OplusBubbleTextView";
    private static final String CLS_TEXTVIEW = "android.widget.TextView";
    /** 快捷菜单（长按图标弹出的快捷方式列表）的条目容器；它内部的应用名不是「桌面应用名」。 */
    private static final String CLS_DEEP_SHORTCUT_VIEW = "com.android.launcher3.shortcuts.DeepShortcutView";
    /** 任务栏 / 预测应用图标的双阴影 View，同样不属于桌面网格应用名。 */
    private static final String CLS_DOUBLE_SHADOW_BTV = "com.android.launcher3.views.DoubleShadowBubbleTextView";
    /** 桌面应用名 View 的扩展接口，getTitleShadowFlag() 正是「这个名字该不该有阴影」的原生语义。 */
    private static final String CLS_IBTV_EXT = "com.android.launcher3.IBubbleTextViewExt";
    /** BubbleTextView.mInfo：描述「这个应用名背后是什么」。 */
    private static final String FLD_MINFO = "mInfo";
    /** 应用 / 快捷方式（含文件夹里的应用）——这些才是要加阴影的。 */
    private static final String CLS_WORKSPACE_ITEM_INFO = "com.android.launcher3.model.data.WorkspaceItemInfo";
    /** 文件夹图标自身——文件夹名不该有应用名阴影。 */
    private static final String CLS_FOLDER_INFO = "com.android.launcher3.model.data.FolderInfo";

    /** 阴影色相的固定部分：阴影恒为纯黑，只有 alpha（浓度）可调。 */
    private static final int SHADOW_RGB_BLACK = 0x000000;

    private static volatile boolean sInstalled = false;
    private static volatile Class<?> sDeepShortcutCls = null;
    private static volatile java.lang.reflect.Field sInfoField = null;
    private static volatile Class<?> sWorkspaceItemCls = null;
    private static volatile Class<?> sFolderInfoCls = null;
    /** 标记「已为该 View 开启过阴影越界」，避免重复设置。 */
    private static final int SHADOW_OVERFLOW_TAG = 0x7f0e0462;
    private static volatile Class<?> sLabelCls = null;

    private LauncherLabelShadowHook() {}

    /** 只在桌面进程调用。@return 成功挂上的 hook 数（0 = 未安装）。 */
    static int install(final BlurEnhanceModule mod, final ClassLoader cl) {
        if (sInstalled) return 0;
        synchronized (LauncherLabelShadowHook.class) {
            if (sInstalled) return 0;
            sInstalled = true;
        }
        try {
            Class<?> label = Reflect.loadClass(CLS_BUBBLE_TV, cl);
            if (label == null) label = Reflect.loadClass(CLS_OPLUS_BTV, cl);
            if (label == null) {
                ModuleLog.d(TAG, "label class not found, skip");
                return 0;
            }
            sLabelCls = label;
            sDeepShortcutCls = Reflect.loadClass(CLS_DEEP_SHORTCUT_VIEW, cl);
            sWorkspaceItemCls = Reflect.loadClass(CLS_WORKSPACE_ITEM_INFO, cl);
            sFolderInfoCls = Reflect.loadClass(CLS_FOLDER_INFO, cl);
            sInfoField = Reflect.field(label, FLD_MINFO);
        } catch (Throwable t) {
            ModuleLog.e(TAG, "resolve label class failed", t);
            return 0;
        }
        int n = 0;
        n += hookSetShadowLayer(mod, cl);
        n += hookRefreshShadowLayer(mod);
        n += hookApplyLabel(mod);
        ModuleLog.d(TAG, "installed hooks=" + n + " label=" + sLabelCls.getName());
        return n;
    }

    /** ① 拦截 TextView#setShadowLayer：桌面自己设/清阴影时一律换成 iOS 风参数。 */
    private static int hookSetShadowLayer(final BlurEnhanceModule mod, final ClassLoader cl) {
        try {
            Class<?> tvCls = Class.forName(CLS_TEXTVIEW, false, cl);
            Method m = Reflect.method(tvCls, "setShadowLayer",
                    float.class, float.class, float.class, int.class);
            if (m == null) {
                ModuleLog.d(TAG, "setShadowLayer not found");
                return 0;
            }
            mod.hook(m)
                    .setId("launcher.label.ssl")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object self = chain.getThisObject();
                            // 热路径过滤：非桌面应用名 View / 开关关闭时原样放行，零额外分配。
                            if (!(self instanceof TextView) || !isLabel(self)
                                    || !FeatureFlags.LAUNCHER_LABEL_SHADOW
                                    || isNotDesktopLabel((View) self)) {
                                return chain.proceed();
                            }
                            Object[] args = chain.getArgs().toArray();
                            if (args.length < 4) return chain.proceed(args);
                            TextView tv = (TextView) self;
                            float density = densityOf(tv);
                            // 阴影是 TextView 自己在自己的画布内画的，一旦半径超出
                            // 「文字行高 + 上下 padding」，超出部分就被 View 边界硬裁掉，
                            // 表现为半径越大越像被截断。enableShadowOverflow() 通过
                            // 放宽 View 的裁剪范围给阴影腾出空间。
                            enableShadowOverflow(tv);
                            args[0] = clampRadius(tv, FeatureFlags.LAUNCHER_LABEL_SHADOW_RADIUS_DP * density);
                            args[1] = 0f;   // iOS 的阴影没有水平偏移
                            args[2] = FeatureFlags.LAUNCHER_LABEL_SHADOW_DY_DP * density;
                            args[3] = shadowColorFor(tv);
                            return chain.proceed(args);
                        }
                    });
            return 1;
        } catch (Throwable t) {
            ModuleLog.e(TAG, "hook setShadowLayer failed", t);
            return 0;
        }
    }

    /** ② 原生刷新点之后补一次（幂等），覆盖不走 setShadowLayer 的分支。 */
    private static int hookRefreshShadowLayer(final BlurEnhanceModule mod) {
        final Class<?> cls = sLabelCls;
        if (cls == null) return 0;
        try {
            Method m = Reflect.method(cls, "refreshShadowLayer");
            if (m == null) {
                ModuleLog.d(TAG, "refreshShadowLayer not found (ok, rely on setShadowLayer)");
                return 0;
            }
            mod.hook(m)
                    .setId("launcher.label.refresh")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            try {
                                Object self = chain.getThisObject();
                                if (self instanceof TextView && FeatureFlags.LAUNCHER_LABEL_SHADOW
                                        && !isNotDesktopLabel((View) self)) {
                                    applyTo((TextView) self);
                                }
                            } catch (Throwable t) {
                                ModuleLog.swallow("launcher.label.refresh", t);
                            }
                            return r;
                        }
                    });
            return 1;
        } catch (Throwable t) {
            ModuleLog.e(TAG, "hook refreshShadowLayer failed", t);
            return 0;
        }
    }

    /**
     * ③ 应用名绑定之后补一次。{@code applyLabel} 在每次给图标绑应用名时都会走
     * （含全部应用列表滚动、换壁纸 / 换主题后的重绑），用来把「开关从关到开」
     * 的新状态尽快铺到已经存在的图标上。
     */
    private static int hookApplyLabel(final BlurEnhanceModule mod) {
        final Class<?> cls = sLabelCls;
        if (cls == null) return 0;
        try {
            Method m = Reflect.method(cls, "applyLabel", 1);   // applyLabel(ItemInfoWithIcon)
            if (m == null) {
                ModuleLog.d(TAG, "applyLabel not found (ok)");
                return 0;
            }
            mod.hook(m)
                    .setId("launcher.label.bind")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            try {
                                Object self = chain.getThisObject();
                                if (self instanceof TextView && FeatureFlags.LAUNCHER_LABEL_SHADOW
                                        && !isNotDesktopLabel((View) self)) {
                                    applyTo((TextView) self);
                                }
                            } catch (Throwable t) {
                                ModuleLog.swallow("launcher.label.bind", t);
                            }
                            return r;
                        }
                    });
            return 1;
        } catch (Throwable t) {
            ModuleLog.e(TAG, "hook applyLabel failed", t);
            return 0;
        }
    }

    /** 直接给某个应用名 View 挂上 iOS 风阴影（会再次进入上面的拦截，参数一致，不会递归）。 */
    private static void applyTo(TextView tv) {
        try {
            float density = densityOf(tv);
            enableShadowOverflow(tv);
            tv.setShadowLayer(clampRadius(tv, FeatureFlags.LAUNCHER_LABEL_SHADOW_RADIUS_DP * density),
                    0f,
                    FeatureFlags.LAUNCHER_LABEL_SHADOW_DY_DP * density,
                    shadowColorFor(tv));
        } catch (Throwable t) {
            ModuleLog.swallow("launcher.label.apply", t);
        }
    }

    /**
     * 让阴影「溢出」到 View 边界之外，避免被 View 自身的裁剪硬切。
     *
     * <p>TextView 的 shadowLayer 是在 {@code onDraw} 里用 View 自己的画布画的，
     * 而画布会被 {@code clipBounds} 裁到 View 的矩形内。半径超出「行高 + padding」
     * 的部分直接消失，表现为大半径时阴影被截断。
     *
     * <p>这里做两件事把空间腾出来：
     * <ol>
     *   <li>{@code setClipToPadding(false)}：允许绘制进入 padding 区（很多机型的
     *       BubbleTextView 默认会裁掉 padding 部分）；</li>
     *   <li>给父容器 {@code setClipChildren(false)}：允许 TextView 的绘制溢出到父容器，
     *       这一步是关键——桌面图标的上下留白通常由 ShortcutAndWidgetContainer 分配。</li>
     * </ol>
     *
     * <p>注意：只对判定为「桌面应用名」的 View 做，且幂等（重复设置无副作用）。
     */
    private static void enableShadowOverflow(TextView tv) {
        try {
            if (Boolean.TRUE.equals(getTagCompat(tv, SHADOW_OVERFLOW_TAG))) return;   // 已处理过
            setTagCompat(tv, SHADOW_OVERFLOW_TAG, Boolean.TRUE);
            setClipToPaddingCompat(tv, false);
            android.view.ViewParent vp = tv.getParent();
            if (vp instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) vp).setClipChildren(false);
            }
        } catch (Throwable t) {
            ModuleLog.swallow("launcher.label.overflow", t);
        }
    }

    /**
     * 半径上限：超过「行高的一半」时阴影必然大面积超出可绘制区，视觉上就是被切。
     * 这里按文字行高做软上限，避免用户把滑块拉到底后出现难看的截断。
     */
    private static float clampRadius(TextView tv, float radiusPx) {
        try {
            int fh = tv.getLineHeight();
            if (fh <= 0) return radiusPx;
            float max = fh * 0.5f;      // 不超过半行高
            return Math.min(radiusPx, max);
        } catch (Throwable t) {
            return radiusPx;
        }
    }

    /**
     * 阴影颜色：恒为纯黑，只从参数里取「浓度」（alpha）。
     *
     * <p>不做「亮色文字配亮色阴影」的自适应：应用名阴影只能是黑色，亮色阴影在视觉上
     * 会变成外发光而不是阴影，反而破坏 iOS 观感。参数里即使带了非黑色相，RGB 也会被
     * 归一为黑，只保留它的 alpha。
     */
    private static int shadowColorFor(TextView tv) {
        return (FeatureFlags.LAUNCHER_LABEL_SHADOW_DARK & 0xFF000000) | SHADOW_RGB_BLACK;
    }

    /**
     * 判断这个应用名 View 是不是「真正属于桌面」的那一个。
     *
     * <p>桌面网格 / 文件夹里的应用名用的是 {@code BubbleTextView}，但快捷菜单条目也是它
     * （{@code DeepShortcutView.mBubbleText}）、任务栏用的是 {@code DoubleShadowBubbleTextView}。
     * 给这些加阴影会污染快捷菜单（iOS 的快捷菜单本来就不带阴影）。所以沿父链排除：
     */
    private static boolean isNotDesktopLabel(View v) {
        if (v == null) return true;
        // ① 快捷菜单条目（DeepShortcutView.mBubbleText）——快捷菜单不该有应用名阴影。
        if (isInDeepShortcut(v)) return true;
        // ② 任务栏 / 预测图标（DoubleShadowBubbleTextView）——它们自带双阴影逻辑。
        if (isDoubleShadow(v)) return true;
        // ③ 主判据：看这个应用名背后的 mInfo 是什么。
        //      · WorkspaceItemInfo = 应用 / 快捷方式（含文件夹内的应用）→ 要阴影
        //      · FolderInfo        = 文件夹图标的名字                  → 也要阴影
        //    桌面上的应用名与文件夹名都属于「桌面应用的文字」，统一加；
        //    其余类型（搜索项、全部应用列表项等）不加。
        return !isDesktopTitle(v);
    }

    /**
     * 判定 v 是不是「桌面上的应用文字」——依据 {@code BubbleTextView.mInfo} 的类型。
     *
     * <p>反编译确认：{@code mInfo} 声明为 {@code ItemInfoWithIcon}。
     * 桌面应用 / 快捷方式 / 文件夹内的应用放 {@code WorkspaceItemInfo}；
     * 文件夹图标自身的名字放 {@code FolderInfo}。这两类都是「桌面上的应用文字」，都要加阴影。
     *
     * <p>原生代码自己也用 {@code instance-of WorkspaceItemInfo} 做这类区分，
     * 这比靠包名前缀猜更可靠：不受类所在包、容器嵌套深度影响。
     *
     * <p>父链白名单（Workspace / Folder 容器）保留为兜底：万一 {@code mInfo} 取不到，
     * 只要父链落在桌面工作区或文件夹里，仍按「桌面应用文字」处理。
     */
    private static boolean isDesktopTitle(View v) {
        Class<?> wi = sWorkspaceItemCls;
        Class<?> fi = sFolderInfoCls;
        try {
            java.lang.reflect.Field f = sInfoField;
            if (f != null) {
                Object info = f.get(v);
                if (info != null) {
                    if (wi != null && wi.isInstance(info)) return true;   // 应用 → 要
                    if (fi != null && fi.isInstance(info)) return true;   // 文件夹名 → 也要
                    return false;                                         // 其他类型 → 不要
                }
            }
        } catch (Throwable ignored) {}
        // 兜底：父链判定
        return isInWorkspaceOrFolder(v);
    }

    /**
     * {@code View.setTag(int, Object)} 在公开 SDK 上不可见（属于 @hide），
     * 这里用反射调用，拿不到就退回无参 setTag（用固定对象做「已处理」标记）。
     */
    /**
     * {@code View.setClipToPadding} 在公开 SDK 上不可见，只能反射。
     * 作用：允许绘制进入 padding 区域，给阴影多腾出一点空间。
     */
    private static void setClipToPaddingCompat(View v, boolean clip) {
        try {
            java.lang.reflect.Method m = View.class.getDeclaredMethod(
                    "setClipToPadding", boolean.class);
            m.setAccessible(true);
            m.invoke(v, clip);
        } catch (Throwable ignored) {}
    }

    private static Object getTagCompat(View v, int key) {
        try {
            java.lang.reflect.Method m = View.class.getDeclaredMethod("getTag", int.class);
            m.setAccessible(true);
            return m.invoke(v, key);
        } catch (Throwable t) {
            try { return v.getTag(); } catch (Throwable ignored) { return null; }
        }
    }

    private static void setTagCompat(View v, int key, Object value) {
        try {
            java.lang.reflect.Method m = View.class.getDeclaredMethod(
                    "setTag", int.class, Object.class);
            m.setAccessible(true);
            m.invoke(v, key, value);
        } catch (Throwable t) {
            try { v.setTag(value); } catch (Throwable ignored) {}
        }
    }

    /** v 是否落在 DeepShortcutView 内部（快捷菜单条目）。 */
    private static boolean isInDeepShortcut(View v) {
        Class<?> ds = sDeepShortcutCls;
        if (ds == null) return false;
        try {
            View p = v;
            for (int i = 0; i < 8 && p != null; i++) {
                if (ds.isInstance(p)) return true;
                android.view.ViewParent vp = p.getParent();
                p = (vp instanceof View) ? (View) vp : null;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** v 是否本身就是任务栏 / 预测图标的双阴影 View。 */
    private static boolean isDoubleShadow(View v) {
        try {
            Class<?> c = Reflect.loadClass(CLS_DOUBLE_SHADOW_BTV, v.getClass().getClassLoader());
            return c != null && c.isInstance(v);
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * 正向判定：v 是否位于「桌面工作区（Workspace）」或「文件夹（Folder）」容器内。
     *
     * <p>这是把阴影<b>严格限定到桌面应用文字</b>的关键。桌面应用名的父链是：
     * {@code BubbleTextView → ShortcutAndWidgetContainer → CellLayout → Workspace → ...}；
     * 文件夹里则是 {@code BubbleTextView → ... → Folder}。其他位置（全部应用列表、搜索结果、
     * 快捷菜单、任务栏、最近任务预览）都不会命中这两个容器类型。
     */
    private static boolean isInWorkspaceOrFolder(View v) {
        try {
            View p = v;
            for (int i = 0; i < 10 && p != null; i++) {
                String cn = p.getClass().getName();
                // 实际是「桌面工作区 / 文件夹」的一切容器。folder 包下的 Folder（文件夹容器）
                // 与 FolderIcon（文件夹图标自身，含它的名字）都属于「桌面上的应用文字」。
                if (cn.equals("com.android.launcher3.Workspace")
                        || cn.startsWith("com.android.launcher3.folder.")) {
                    return true;
                }
                android.view.ViewParent vp = p.getParent();
                p = (vp instanceof View) ? (View) vp : null;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static float densityOf(View v) {
        float d = v.getResources().getDisplayMetrics().density;
        return d > 0f ? d : 1f;
    }

    private static boolean isLabel(Object o) {
        Class<?> c = sLabelCls;
        return c != null && c.isInstance(o);
    }
}