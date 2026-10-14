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
    /** WidgetNameHelper.widgetName：小组件名的 OplusBubbleTextView。 */
    private static final String FLD_WIDGET_NAME = "widgetName";
    /** BubbleTextView 用来裁剪文字画布的 Path；阴影被切成矩形就是它在作怪。 */
    private static final String FLD_TEXT_CLIP_PATH = "mTextClipPath";
    /** 应用 / 快捷方式（含文件夹里的应用）——这些才是要加阴影的。 */
    private static final String CLS_WORKSPACE_ITEM_INFO = "com.android.launcher3.model.data.WorkspaceItemInfo";
    /** 文件夹图标自身——文件夹名不该有应用名阴影。 */
    private static final String CLS_FOLDER_INFO = "com.android.launcher3.model.data.FolderInfo";
    /**
     * 桌面小组件的标题。
     *
     * <p>反编译确认：{@code com.android.launcher3.widget.utils.WidgetNameHelper} 持有的
     * {@code widgetName} 字段类型就是 {@code OplusBubbleTextView}，而
     * {@code BaseLauncherAppWidgetHostView.mWidgetTitleView} 也是同一个类 ——
     * 也就是说小组件名同样是 BubbleTextView 家族，只是它的 {@code mInfo} 装的是
     * {@code LauncherAppWidgetInfo}（父类 {@code ItemInfo}，接不进 TextView 的
     * {@code mInfo:ItemInfoWithIcon} 字段，所以通常为 null）→ 之前被判成「不要阴影」。
     */
    private static final String CLS_APPWIDGET_INFO = "com.android.launcher3.model.data.LauncherAppWidgetInfo";
    /** 小组件标题 View 所在的宿主类，用于父链识别。 */
    private static final String CLS_WIDGET_HOST_VIEW = "com.android.launcher3.widget.BaseLauncherAppWidgetHostView";
    /** 小组件标题 View 的资源 id 名（布局里是 widget_name）。 */
    private static final String CLS_WIDGET_HOST_VIEW_IMPL = "com.android.launcher3.widget.CustomLauncherAppWidgetHostView";

    /** 阴影色相的固定部分：阴影恒为纯黑，只有 alpha（浓度）可调。 */
    private static final int SHADOW_RGB_BLACK = 0x000000;

    private static volatile boolean sInstalled = false;
    private static volatile Class<?> sDeepShortcutCls = null;
    private static volatile java.lang.reflect.Field sInfoField = null;
    private static volatile java.lang.reflect.Field sWidgetNameField = null;
    /** BubbleTextView.mTextClipPath 反射句柄。 */
    private static volatile java.lang.reflect.Field sTextClipField = null;
    /**
     * clipPath 重入保护：
     * 我们把 Path 改写后要交回原始 clipPath 去执行，而那会再次进入本 hook
     * （登记表按对象同一性识别，Path 还是同一个），因此必须挡住第二次。
     * 裁剪只发生在绘制线程，用 ThreadLocal 最轻量。
     */
    private static final ThreadLocal<Boolean> sClipReentry =
            new ThreadLocal<Boolean>();
    /** mTextClipPath 对象 -> 所属应用名 View；用于按「对象同一性」识别是谁在裁画布。 */
    private static final java.util.Map<android.graphics.Path, java.lang.ref.WeakReference<TextView>>
            sTextClipOwners = java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<android.graphics.Path, java.lang.ref.WeakReference<TextView>>());

    private static volatile Class<?> sWorkspaceItemCls = null;
    private static volatile Class<?> sFolderInfoCls = null;
    private static volatile Class<?> sAppWidgetInfoCls = null;
    private static volatile Class<?> sWidgetHostCls = null;
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
            sAppWidgetInfoCls = Reflect.loadClass(CLS_APPWIDGET_INFO, cl);
            sWidgetHostCls = Reflect.loadClass(CLS_WIDGET_HOST_VIEW, cl);
            sInfoField = Reflect.field(label, FLD_MINFO);
        } catch (Throwable t) {
            ModuleLog.e(TAG, "resolve label class failed", t);
            return 0;
        }
        int n = 0;
        n += hookSetShadowLayer(mod, cl);
        n += hookRefreshShadowLayer(mod);
        n += hookApplyLabel(mod);
        n += hookClipPath(mod, cl);
        n += hookOnSizeChanged(mod);
        n += hookWidgetTitleBind(mod);
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
                                if (self instanceof TextView) rememberTextClipPath((TextView) self);
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
                                    final TextView tv = (TextView) self;
                                    applyTo(tv);
                                    // 改名会伴随一段淡入动画（changeTextWithFade → setTextAlpha
                                    // → refreshShadowLayer，动画期间 alpha=0 的分支会把阴影清掉）。
                                    // 这里在动画时长期内补几次，保证收尾时阴影仍在。
                                    scheduleReapply(tv);
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
    /**
     * 在改名淡入动画期间补几次阴影。
     *
     * <p>改名链路：{@code applyLabel → changeTextWithFade → 逐帧 setTextAlpha}
     * → 每一帧都会 {@code refreshShadowLayer()}，而 {@code mTextAlpha == 0} 的分支会
     * 把阴影清成 {@code (0,0,0,0)}。动画结束后不会再触发任何刷新，于是阴影就没了。
     *
     * <p>与其去 hook 动画的每一帧，不如在 applyLabel 之后按动画时长补几次（幂等，
     * 且只在开关仍打开、仍是桌面应用名时执行）。
     */
    private static void scheduleReapply(final TextView tv) {
        if (tv == null) return;
        final Runnable again = new Runnable() {
            @Override
            public void run() {
                try {
                    if (!FeatureFlags.LAUNCHER_LABEL_SHADOW) return;
                    if (isNotDesktopLabel(tv)) return;
                    applyTo(tv);
                } catch (Throwable t) {
                    ModuleLog.swallow("launcher.label.reapply", t);
                }
            }
        };
        // 淡入动画约 200ms；覆盖 6 个时间点，跨过整段动画。
        for (int delay : new int[]{60, 120, 200, 300, 450, 700}) {
            tv.postDelayed(again, delay);
        }
    }

    /**
     * ④ 去掉 BubbleTextView 对文字画布的「矩形裁剪」——阴影被裁成矩形的真正原因。
     *
     * <p>反编译得到的 {@code BubbleTextView#drawText(Canvas, boolean)} 里有两件事：
     * <pre>
     *   Rect frame = getResizeFrameBounds();
     *   mTextClipRectF.set(0, frame.bottom, getWidth(), getHeight());  // 文字区域矩形
     *   mTextClipPath.reset();
     *   mTextClipPath.addRect(mTextClipRectF, Path.Direction.CW);      // ★ 矩形在这里固化
     *   canvas.clipPath(mTextClipPath);                                // ★ 把画布裁成矩形
     * </pre>
     * 阴影一旦越出这个矩形就被硬切，所以看起来「阴影是个矩形」。
     *
     * <p>这是 View 内部直接对 Canvas 做的裁剪，{@code setClipChildren(false)} 完全无效。
     * 而且行 2 的 {@code set(...)} 发生在 clipPath 之前，hook drawText 后置扩张只能影响
     * <b>下一帧</b>、下一帧又被覆盖，永远差一拍。所以只能在 clipPath 处拦：
     * 认出「这是某个桌面应用名的 mTextClipPath」，就替它裁一个<b>外扩后的矩形</b>
     * （用 clipRect 而不是 clipPath，避免再撞上同一个 Path 造成递归）。
     *
     * <p>判定用「对象同一性」而非形状，避免误伤别处的裁剪。
     */
    private static int hookClipPath(final BlurEnhanceModule mod, final ClassLoader cl) {
        try {
            Method m = Reflect.method(android.graphics.Canvas.class, "clipPath",
                    android.graphics.Path.class);
            if (m == null) {
                ModuleLog.d(TAG, "Canvas.clipPath not found");
                return 0;
            }
            mod.hook(m)
                    .setId("launcher.label.clippath")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            try {
                                if (!FeatureFlags.LAUNCHER_LABEL_SHADOW) return chain.proceed();
                                Object[] args = chain.getArgs().toArray();
                                if (args.length < 1 || !(args[0] instanceof android.graphics.Path)) {
                                    return chain.proceed(args);
                                }
                                // 防重入：本次改写后交回原生 clipPath 时不要再处理一遍
                                if (Boolean.TRUE.equals(sClipReentry.get())) {
                                    return chain.proceed(args);
                                }
                                TextView owner = ownerOfTextClipPath((android.graphics.Path) args[0]);
                                if (owner == null) return chain.proceed(args);

                                // 是桌面应用名的文字区域裁剪：把这块矩形「外扩」后再让原生裁。
                                //
                                // 注意：这里绝对不能自己 canvas.save() 后 return null。
                                // drawText 原生会做 save/restore 配对，跳过它就等于每次绘制
                                // 都往 Canvas 栈里泄漏一层，残留的裁剪会把整个图标裁掉
                                // （表现为图标消失、点一下桌面又恢复）。
                                // 正确做法：只改写它自己那个 Path 的内容，裁剪仍由原生调用完成。
                                android.graphics.Path target = (android.graphics.Path) args[0];
                                float pad = FeatureFlags.LAUNCHER_LABEL_SHADOW_RADIUS_DP
                                        * densityOf(owner) + 2f;
                                target.reset();
                                target.addRect(-pad, -pad,
                                        owner.getWidth() + pad, owner.getHeight() + pad,
                                        android.graphics.Path.Direction.CW);
                                // 交给原始 clipPath（现在它裁的是一个更大的矩形）
                                sClipReentry.set(Boolean.TRUE);
                                try {
                                    return chain.proceed(args);
                                } finally {
                                    sClipReentry.remove();
                                }
                            } catch (Throwable t) {
                                ModuleLog.swallow("launcher.label.clippath", t);
                                return chain.proceed();
                            }
                        }
                    });
            return 1;
        } catch (Throwable t) {
            ModuleLog.e(TAG, "hook clipPath failed", t);
            return 0;
        }
    }

    /** 若给定 Path 正是某个桌面应用名 View 的 mTextClipPath，返回该 View。 */
    private static TextView ownerOfTextClipPath(android.graphics.Path path) {
        java.lang.ref.WeakReference<TextView> ref = sTextClipOwners.get(path);
        return ref == null ? null : ref.get();
    }

    /**
     * 登记某个应用名 View 的 mTextClipPath，供 clipPath 拦截时识别。
     * 在每次 {@code refreshShadowLayer} / {@code applyLabel} 之后调用，
     * 保证映射里始终有最新的 Path 实例。
     */
    private static void rememberTextClipPath(TextView tv) {
        try {
            java.lang.reflect.Field f = sTextClipField;
            if (f == null) {
                f = Reflect.field(tv.getClass(), FLD_TEXT_CLIP_PATH);
                if (f == null && sLabelCls != null) f = Reflect.field(sLabelCls, FLD_TEXT_CLIP_PATH);
                if (f == null) return;
                sTextClipField = f;
            }
            Object o = f.get(tv);
            if (o instanceof android.graphics.Path) {
                sTextClipOwners.put((android.graphics.Path) o,
                        new java.lang.ref.WeakReference<TextView>(tv));
            }
        } catch (Throwable t) {
            ModuleLog.swallow("launcher.label.clippath.remember", t);
        }
    }

    /**
     * ⑤ 切图标形态（大 / 中 / 正常）之后把阴影补回来。
     *
     * <p>反编译取证：{@code BubbleTextView#onSizeChanged(int,int,int,int)} 里依次做了
     * {@code checkForEllipsis()} → {@code OperatorFontCustomization.checkAndSetMaxLines(...)}
     * → 读 {@code mInfo} → {@code getIcon()}。也就是说「换图标尺寸」必然经过这里，
     * 而尺寸变化后文字会被重新测量 / 重设，阴影（shadowLayer 是随字号与测量结果走的）
     * 就丢了，且原生不会再补。
     *
     * <p>这里挂 {@code onSizeChanged}：执行完原生逻辑后，只要这个 View 还是
     * 「桌面应用的文字」（应用名 / 文件夹名 / 小组件名），就重新 apply 一次。
     * 这比继续追「谁改了布局」更可靠 —— 无论切尺寸走的是哪条链路，View 尺寸变了就一定会到这里。
     */
    private static int hookOnSizeChanged(final BlurEnhanceModule mod) {
        final Class<?> cls = sLabelCls;
        if (cls == null) return 0;
        try {
            Method m = Reflect.method(cls, "onSizeChanged", 4);
            if (m == null) {
                ModuleLog.d(TAG, "onSizeChanged not found");
                return 0;
            }
            mod.hook(m)
                    .setId("launcher.label.size")
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
                                ModuleLog.swallow("launcher.label.size", t);
                            }
                            return r;
                        }
                    });
            return 1;
        } catch (Throwable t) {
            ModuleLog.e(TAG, "hook onSizeChanged failed", t);
            return 0;
        }
    }

    /**
     * ⑥ 小组件标题绑定后补阴影。
     *
     * <p>{@code WidgetNameHelper#updateTitle(...)} / {@code #title(String, ItemInfo)}
     * 是设置小组件名的入口，它走的是自己的一套（不经过 {@code applyLabel}），
     * 所以之前在「桌面应用名」上生效的三个 hook 都覆盖不到小组件名。
     * 这里直接挂 {@code WidgetNameHelper} 的 {@code updateTitle}，执行完补一次。
     *
     * <p>类被混淆或方法签名变化时安静跳过，不影响其它 hook。
     */
    private static int hookWidgetTitleBind(final BlurEnhanceModule mod) {
        try {
            Class<?> helper = Reflect.loadClass(
                    "com.android.launcher3.widget.utils.WidgetNameHelper",
                    LauncherLabelShadowHook.class.getClassLoader());
            if (helper == null) {
                ModuleLog.d(TAG, "WidgetNameHelper not found (ok)");
                return 0;
            }
            Method m = Reflect.method(helper, "title", 2);   // title(String, ItemInfo)
            if (m == null) m = Reflect.method(helper, "updateTitle", 2);
            if (m == null) {
                ModuleLog.d(TAG, "WidgetNameHelper.title not found (ok)");
                return 0;
            }
            mod.hook(m)
                    .setId("launcher.label.widget")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            try {
                                if (FeatureFlags.LAUNCHER_LABEL_SHADOW) {
                                    TextView tv = widgetNameOf(chain.getThisObject());
                                    if (tv != null) applyTo(tv);
                                }
                            } catch (Throwable t) {
                                ModuleLog.swallow("launcher.label.widget", t);
                            }
                            return r;
                        }
                    });
            return 1;
        } catch (Throwable t) {
            ModuleLog.e(TAG, "hook WidgetNameHelper failed", t);
            return 0;
        }
    }

    /** 从 {@code WidgetNameHelper} 实例上取出它的 {@code widgetName}（OplusBubbleTextView）。 */
    private static TextView widgetNameOf(Object helper) {
        try {
            if (helper == null) return null;
            java.lang.reflect.Field f = sWidgetNameField;
            if (f == null) {
                f = Reflect.field(helper.getClass(), FLD_WIDGET_NAME);
                if (f == null) return null;
                sWidgetNameField = f;
            }
            Object o = f.get(helper);
            return (o instanceof TextView) ? (TextView) o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void applyTo(TextView tv) {
        try {
            float density = densityOf(tv);
            enableShadowOverflow(tv);
            rememberTextClipPath(tv);
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
            // 关键：不能只解开「直接父容器」。
            // 应用名的宽度受格子限制，名字长时文字/阴影会横向溢出 TextView 矩形；
            // 若上层还有 ShortcutAndWidgetContainer / CellLayout 在 clipChildren，
            // 阴影照样会在格子边界被切。这里沿父链把若干层都解开，
            // 一直到 Workspace / Folder 这类「不会因为不裁剪而画错」的容器为止。
            android.view.ViewParent vp = tv.getParent();
            for (int i = 0; i < 6 && vp != null; i++) {
                if (!(vp instanceof android.view.ViewGroup)) break;
                android.view.ViewGroup vg = (android.view.ViewGroup) vp;
                String cn = vg.getClass().getName();
                if (cn.equals("com.android.launcher3.Workspace")
                        || cn.startsWith("com.android.launcher3.folder.")) {
                    break;                          // 到工作区/文件夹容器就停，不再往上
                }
                vg.setClipChildren(false);
                vp = vg.getParent();
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
        // 注意：改名 / 换行时 getLineHeight() 可能短暂返回 0 或异常小值，
        // 此时绝不能拿它当上限——否则半径会被钳成 0，表现为「改名后阴影没了」。
        // 因此只在该值「看起来合理」（≥ 12px，约等于一行小字）时才参与上限计算。
        try {
            int fh = tv.getLineHeight();
            if (fh < 12) return radiusPx;
            float max = fh * 0.75f;     // 不超过 3/4 行高
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
        Class<?> ai = sAppWidgetInfoCls;
        try {
            java.lang.reflect.Field f = sInfoField;
            if (f != null) {
                Object info = f.get(v);
                if (info != null) {
                    if (wi != null && wi.isInstance(info)) return true;   // 应用 → 要
                    if (fi != null && fi.isInstance(info)) return true;   // 文件夹名 → 也要
                    if (ai != null && ai.isInstance(info)) return true;   // 小组件名 → 也要
                    // mInfo 有值但不是桌面三类：可能是全部应用列表 / 搜索项等，排除。
                    // 但要先放行「小组件宿主里的标题」，它的 mInfo 常常为 null，
                    // 有值时也可能不是上面几类，所以父链兜底放在返回 false 之前。
                    if (isWidgetTitle(v)) return true;
                    return false;
                }
            }
        } catch (Throwable ignored) {}
        // 兜底：父链判定（工作区 / 文件夹 / 小组件宿主）
        return isInWorkspaceOrFolder(v) || isWidgetTitle(v);
    }

    /**
     * v 是不是「桌面小组件的标题」。
     *
     * <p>反编译确认 {@code BaseLauncherAppWidgetHostView.mWidgetTitleView} 是
     * {@code OplusBubbleTextView}，并在构造时把它交给 {@code WidgetNameHelper}。
     * 因此只需沿父链找小组件宿主 View 即可确认身份，不依赖 {@code mInfo}。
     */
    private static boolean isWidgetTitle(View v) {
        try {
            Class<?> host = sWidgetHostCls;
            View p = v;
            for (int i = 0; i < 6 && p != null; i++) {
                String cn = p.getClass().getName();
                if (host != null && host.isInstance(p)) return true;
                if (cn.startsWith("com.android.launcher3.widget.")
                        && cn.endsWith("AppWidgetHostView")) {
                    return true;
                }
                android.view.ViewParent vp = p.getParent();
                p = (vp instanceof View) ? (View) vp : null;
            }
        } catch (Throwable ignored) {}
        return false;
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