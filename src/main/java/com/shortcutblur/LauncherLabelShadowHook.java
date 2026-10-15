package com.shortcutblur;

import android.view.View;
import android.widget.TextView;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;

/**
 * 桌面应用名阴影（常驻的柔和暗色阴影，贴在文字下方）。
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
 * <p>因此原生阴影是「有时有、有时没有」，而且没有向下偏移。这里改成常驻柔和阴影：
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
    /** TextView.mShadowRadius：当前阴影半径（float）。属于 @hide，只能反射读。 */
    private static final String FLD_SHADOW_RADIUS = "mShadowRadius";
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
    /** 父链向上查找的最大层数（桌面图标 → 工作区的深度远小于此）。 */
    private static final int MAX_PARENT_WALK = 10;
    /** 外扩裁剪时额外多留的余量（px），保证阴影边缘不被切得发干。 */
    private static final float PAD_EXTRA_PX = 2f;
    /** 改名淡入动画期间补阴影的时间点（ms）。动画约 200ms，覆盖到收尾即可。 */
    private static final int[] REAPPLY_DELAYS = {120, 300, 600};

    /** 阴影色相的固定部分：阴影恒为纯黑，只有 alpha（浓度）可调。 */
    private static final int SHADOW_RGB_BLACK = 0x000000;

    private static volatile boolean sInstalled = false;
    private static volatile Class<?> sDeepShortcutCls = null;
    /** 桌面 OPPO 卡片基类（TitleCardView / GroupCardView 共同祖先）：
     *  卡片标题也是 BubbleTextView 家族，但其 mInfo 为空、父链不含 Workspace，
     *  会被原来的「非桌面项」逻辑误排除，故显式识别为桌面项。 */
    private static volatile Class<?> sLauncherCardViewCls = null;
    /** 卡片基类解析用的 ClassLoader（install 时记录，供首次未命中时的懒解析）。 */
    private static volatile ClassLoader sLabelLoader = null;
    private static volatile java.lang.reflect.Field sInfoField = null;
    private static volatile java.lang.reflect.Field sWidgetNameField = null;
    /** BubbleTextView.mTextClipPath 反射句柄。 */
    private static volatile java.lang.reflect.Field sTextClipField = null;
    /** TextView.mShadowRadius 的反射句柄（惰性获取一次，避免反复查找）。 */
    private static volatile java.lang.reflect.Field sShadowRadiusField = null;
    private static volatile boolean sShadowRadiusTried = false;
    /**
     * 「当前正在绘制的文字裁剪 Path」及其宿主。
     *
     * <p>性能核心：{@code BubbleTextView#drawText} 与它内部那唯一一处
     * {@code Canvas#clipPath} 是「外层设、内层用」的关系。把这两个值在 drawText 入口
     * 缓存下来，clipPath 拦截里只需 <b>一次静态字段读取</b> 就能判断命中 ——
     * 不必再 {@code getArgs().toArray()}（每帧分配数组）、
     * 也不必 WeakHashMap + synchronizedMap（每帧加锁 + 哈希 + GC 开销）。
     *
     * <p>绘制都在 UI 线程；volatile 保证可见性，成对 set/restore 以支持嵌套绘制。
     */
    private static volatile TextView sDrawingOwner = null;
    private static volatile android.graphics.Path sDrawingPath = null;

    /** 反射句柄缓存：View 的 @hide 方法每次查找都很贵，必须缓存。 */
    private static volatile java.lang.reflect.Method sSetClipToPaddingM = null;
    private static volatile java.lang.reflect.Method sGetTagM = null;
    private static volatile java.lang.reflect.Method sSetTagM = null;

    private static volatile Class<?> sWorkspaceItemCls = null;
    private static volatile Class<?> sFolderInfoCls = null;
    private static volatile Class<?> sAppWidgetInfoCls = null;
    private static volatile Class<?> sWidgetHostCls = null;
    /** 预解析：任务栏 / 预测图标的双阴影 View。原实现每次调用都 loadClass，太贵。 */
    private static volatile Class<?> sDoubleShadowCls = null;
    /** 预解析：桌面工作区容器类。 */
    private static volatile Class<?> sWorkspaceCls = null;
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
            sDoubleShadowCls = Reflect.loadClass(CLS_DOUBLE_SHADOW_BTV, cl);
            sWorkspaceCls = Reflect.loadClass("com.android.launcher3.Workspace", cl);
            sLabelLoader = cl;
            sLauncherCardViewCls = Reflect.loadClass("com.android.launcher3.card.LauncherCardView", cl);
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
        n += hookDrawText(mod);
        n += hookOnSizeChanged(mod);
        n += hookWidgetTitleBind(mod, cl);
        ModuleLog.d(TAG, "installed hooks=" + n + " label=" + sLabelCls.getName());
        return n;
    }

    /** ① 拦截 TextView#setShadowLayer：桌面自己设/清阴影时一律换成统一的柔和阴影参数。 */
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
                            // 热路径过滤：最便宜的两项放前面（isLabel 已内含类型检查），
                            // 非桌面应用名 View / 开关关闭时原样放行，零额外分配。
                            if (!FeatureFlags.LAUNCHER_LABEL_SHADOW || !isLabel(self)
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
                            args[1] = 0f;   // 阴影没有水平偏移
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
                                if (FeatureFlags.LAUNCHER_LABEL_SHADOW
                                        && self instanceof TextView
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

    /** 直接给某个应用名 View 挂上柔和阴影（会再次进入上面的拦截，参数一致，不会递归）。 */
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
                    // View 已被移除 / 复用出窗口时直接放弃：既避免无谓绘制（发热），
                    // 也让 post 队列能尽快丢掉对它的引用（防滞留）。
                    if (!tv.isAttachedToWindow()) return;
                    if (!FeatureFlags.LAUNCHER_LABEL_SHADOW) return;
                    if (isNotDesktopLabel(tv)) return;
                    applyTo(tv);
                } catch (Throwable t) {
                    ModuleLog.swallow("launcher.label.reapply", t);
                }
            }
        };
        for (int delay : REAPPLY_DELAYS) {
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
     * <p>这是 View 内部直接对 Canvas 做的裁剪，{@code setClipChildren(false)} 完全无效；
     * 又因为 {@code mTextClipRectF.set(...)} 发生在 clipPath 之前，hook drawText 后置扩张
     * 永远差一拍。所以只能在 clipPath 处拦，且只改写它自己那个 Path 的内容
     * （绝不能自己 canvas.save() 后跳过原生调用 —— 那会泄漏 Canvas 栈层，
     * 残留裁剪会把整个图标裁掉，表现为图标消失、点一下桌面才恢复）。
     *
     * <p><b>性能</b>：{@code clipPath} 是超高频繁调用（每一帧、每个文字 View 都走），
     * 因此这里做成零分配：
     * <ul>
     *   <li>能否命中由 {@code drawText} 预先缓存的 {@code sDrawingPath} 决定，
     *       只读两个静态字段，<b>不调用 {@code getArgs()}</b>（避免每帧分配数组）；</li>
     *   <li>不再用 WeakHashMap + synchronizedMap 做对象同一性比对，
     *       省掉每帧的加锁、哈希与 GC 开销；</li>
     *   <li>改写后先清空缓存再 {@code proceed()}：重入时直接放行，
     *       因此也不需要额外的 ThreadLocal 重入标记。</li>
     * </ul>
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
                            // 热路径：与文字阴影无关的 clipPath 占绝大多数，这里零分配放行。
                            android.graphics.Path target = sDrawingPath;
                            if (target == null) return chain.proceed();
                            TextView owner = sDrawingOwner;
                            if (owner == null) return chain.proceed();
                            // 先清空：改写完交回原生 clipPath 会再次进入本 hook，
                            // 那时 target 已是 null → 直接放行，不会递归。
                            sDrawingPath = null;
                            sDrawingOwner = null;
                            try {
                                // 先把尺寸/密度都算好，最后才动 Path：
                                // 万一前面抛异常，Path 还保持原始状态，不会变成空裁剪。
                                float pad = FeatureFlags.LAUNCHER_LABEL_SHADOW_RADIUS_DP
                                        * densityOf(owner) + PAD_EXTRA_PX;
                                int w = owner.getWidth();
                                int h = owner.getHeight();
                                target.reset();
                                target.addRect(-pad, -pad, w + pad, h + pad,
                                        android.graphics.Path.Direction.CW);
                            } catch (Throwable t) {
                                // 宁可退回原生裁剪行为，也绝不让绘制中断。
                                ModuleLog.swallow("launcher.label.clippath", t);
                            }
                            // 用无参 proceed()，沿用原参数，不构造数组。
                            return chain.proceed();
                        }
                    });
            return 1;
        } catch (Throwable t) {
            ModuleLog.e(TAG, "hook clipPath failed", t);
            return 0;
        }
    }

    /**
     * ⑦ 绘制入口：把「本次绘制的文字裁剪 Path」缓存起来，供 clipPath 拦截零成本命中。
     *
     * <p>为什么挂在 {@code drawText}：反编译确认它内部对 {@code clipPath} 只有唯一一处调用，
     * 且 {@code OplusBubbleTextView} / {@code DoubleShadowBubbleTextView} 都没有覆写
     * {@code drawText}，所以钩在基类上就能覆盖全部调用。
     *
     * <p>为什么用 {@code mShadowRadius > 0} 判定：只有确实带阴影的文字才需要放开
     * 裁剪区域。若对所有文字都放开，会把「本该被裁掉的文字部分」也画到 View 边界外，
     * 属于功能性错误。这个 getter 只是一次轻量同步读，代价远低于原来的每帧 Map 查找。
     */
    private static int hookDrawText(final BlurEnhanceModule mod) {
        final Class<?> cls = sLabelCls;
        if (cls == null) return 0;
        try {
            Method m = Reflect.method(cls, "drawText", 2);   // drawText(Canvas, boolean)
            if (m == null) {
                ModuleLog.d(TAG, "drawText not found (ok, clip fix inactive)");
                return 0;
            }
            mod.hook(m)
                    .setId("launcher.label.draw")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            android.graphics.Path prevPath = null;
                            TextView prevOwner = null;
                            boolean marked = false;
                            try {
                                Object self = chain.getThisObject();
                                if (FeatureFlags.LAUNCHER_LABEL_SHADOW
                                        && hasShadowLayer((TextView) self)) {
                                    android.graphics.Path cp = textClipPathOf((TextView) self);
                                    if (cp != null) {
                                        prevPath = sDrawingPath;
                                        prevOwner = sDrawingOwner;
                                        sDrawingOwner = (TextView) self;
                                        sDrawingPath = cp;
                                        marked = true;
                                    }
                                }
                                return chain.proceed();
                            } finally {
                                // 恢复现场：clipPath 命中后已自行清空，这里兜底还原，
                                // 同时支持嵌套绘制（内层绘制完不应破坏外层状态）。
                                if (marked) {
                                    sDrawingOwner = prevOwner;
                                    sDrawingPath = prevPath;
                                }
                            }
                        }
                    });
            return 1;
        } catch (Throwable t) {
            ModuleLog.e(TAG, "hook drawText failed", t);
            return 0;
        }
    }


    /**
     * {@code TextView.mShadowRadius} 的反射句柄（惰性获取一次）。
     *
     * <p>为什么不调 {@code getShadowLayerRadius()}：该 getter 属于 {@code @hide}，
     * 在公开 SDK 上编译不过，只能运行时反射；而且它内部要进同步块。
     * 直接读字段用 {@code Field.getFloat}，连装箱都省了，是这里最快的做法。
     */
    private static java.lang.reflect.Field shadowRadiusField() {
        java.lang.reflect.Field f = sShadowRadiusField;
        if (f == null && !sShadowRadiusTried) {
            f = Reflect.field(TextView.class, FLD_SHADOW_RADIUS);
            if (f != null) {
                try { f.setAccessible(true); } catch (Throwable ignored) {}
            }
            sShadowRadiusField = f;
            sShadowRadiusTried = true;
        }
        return f;
    }

    /**
     * 这个 TextView 当前是否带阴影层。
     *
     * <p>只有确实带阴影的文字才需要「放开文字裁剪」；对没有阴影的文字也放开的话，
     * 长名字换行时文字会被画到本该裁掉的图标区域上 —— 那是功能性错误。
     *
     * <p>读不到字段时返回 false（保守）：最多是阴影被裁一点，绝不会画错。
     */
    private static boolean hasShadowLayer(TextView tv) {
        try {
            java.lang.reflect.Field f = shadowRadiusField();
            if (f != null) return f.getFloat(tv) > 0f;
        } catch (Throwable ignored) {}
        return false;
    }
    /**
     * 读取某个 BubbleTextView 的 {@code mTextClipPath}。
     *
     * <p>反编译确认该字段是 {@code final}，仅在构造函数里初始化一次，
     * {@code drawText} 每帧只是 {@code iget} 复用同一个实例（然后 {@code reset()}），
     * 因此这里读到的 Path 与后续 {@code clipPath} 收到的必定是同一个对象。
     */
    private static android.graphics.Path textClipPathOf(TextView tv) {
        try {
            java.lang.reflect.Field f = sTextClipField;
            if (f == null) {
                f = Reflect.field(tv.getClass(), FLD_TEXT_CLIP_PATH);
                if (f == null && sLabelCls != null) f = Reflect.field(sLabelCls, FLD_TEXT_CLIP_PATH);
                if (f == null) return null;
                sTextClipField = f;
            }
            Object o = f.get(tv);
            return (o instanceof android.graphics.Path) ? (android.graphics.Path) o : null;
        } catch (Throwable t) {
            return null;
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
    private static int hookWidgetTitleBind(final BlurEnhanceModule mod, final ClassLoader cl) {
        try {
            Class<?> helper = Reflect.loadClass(
                    "com.android.launcher3.widget.utils.WidgetNameHelper", cl);
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
            tv.setShadowLayer(clampRadius(tv, FeatureFlags.LAUNCHER_LABEL_SHADOW_RADIUS_DP * density),
                    0f,
                    FeatureFlags.LAUNCHER_LABEL_SHADOW_DY_DP * density,
                    shadowColorFor(tv));
        } catch (Throwable t) {
            ModuleLog.swallow("launcher.label.apply", t);
        }
    }

    /**
     * 对外入口：给一棵视图子树里的所有「桌面应用名」TextView 补挂阴影。
     *
     * <p>用途：桌面 OPPO 卡片（耗电看板等）的标题由快应用自行设置文本，
     * <b>不走</b> launcher 的 {@code applyLabel} / {@code refreshShadowLayer}，
     * 因此既有 hook 覆盖不到，卡片标题没有阴影。卡片内容就绪
     * （{@code initCardAfterInflate}）后调用本方法主动补挂。
     *
     * <p>幂等：内部 {@code applyTo} 只是重设 shadowLayer，重复调用无害；
     * 判定用与其它路径一致的 {@code isNotDesktopLabel}（已含卡片白名单）。
     */
    static void applyToLabelTree(final View root) {
        if (root == null || !FeatureFlags.LAUNCHER_LABEL_SHADOW) return;
        try {
            applyToLabelTreeRec(root, 0);
        } catch (Throwable t) {
            ModuleLog.swallow("launcher.label.tree", t);
        }
    }

    private static void applyToLabelTreeRec(final View v, final int depth) {
        if (v == null || depth > 16) return;
        try {
            if (v instanceof TextView && isLabel(v) && !isNotDesktopLabel(v)) {
                final TextView tv = (TextView) v;
                applyTo(tv);
                // 卡片内容/setText 可能晚于本回调一帧，再补几次（幂等）。
                scheduleReapply(tv);
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                int n;
                try { n = g.getChildCount(); } catch (Throwable t) { return; }
                for (int i = 0; i < n; i++) {
                    applyToLabelTreeRec(g.getChildAt(i), depth + 1);
                }
            }
        } catch (Throwable t) {
            ModuleLog.swallow("launcher.label.treeRec", t);
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
            Class<?> ws = sWorkspaceCls;
            View cur = parentOf(tv);
            for (int i = 0; i < 6 && cur != null; i++) {
                if (!(cur instanceof android.view.ViewGroup)) break;
                Class<?> c = cur.getClass();
                if (c == ws || c.getName().startsWith("com.android.launcher3.folder.")) {
                    break;                          // 到工作区/文件夹容器就停，不再往上
                }
                ((android.view.ViewGroup) cur).setClipChildren(false);
                cur = parentOf(cur);
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
     * 会变成外发光而不是阴影，反而破坏观感。参数里即使带了非黑色相，RGB 也会被
     * 归一为黑，只保留它的 alpha。
     */
    private static int shadowColorFor(TextView tv) {
        return (FeatureFlags.LAUNCHER_LABEL_SHADOW_DARK & 0xFF000000) | SHADOW_RGB_BLACK;
    }

    /**
     * 判断这个文字 View 是不是「不属于桌面应用文字」——应当<b>跳过</b>阴影。
     *
     * <p><b>性能</b>：本方法位于 {@code TextView#setShadowLayer} 热路径上。
     * 原实现依次调用 {@code isInDeepShortcut} / {@code isDoubleShadow} /
     * {@code isDesktopTitle}，而后者内部还会再走一遍父链，
     * 合计最多 <b>4 遍父链遍历、约 40 层循环</b>，每层都要
     * {@code getClass().getName()} + 字符串比较。
     *
     * <p>这里改为：<b>单次父链遍历</b>一次性收集四类容器信息，
     * 再按「先便宜、后昂贵」的顺序判定 —— 类型判定 → mInfo 反射读 → 父链兜底。
     * 绝大多数桌面图标在读 mInfo 时就能直接得出结果，根本走不到父链兜底。
     */
    /**
     * 取「桌面卡片基类」（{@code com.android.launcher3.card.LauncherCardView}）。
     *
     * <p>首次未命中（install 时该类尚未进入 ClassLoader）时不放弃：下次判定时
     * 再试一次 —— 否则一次失败会让卡片标题阴影<b>永久失效</b>（静默）。
     */
    private static Class<?> launcherCardViewCls() {
        Class<?> c = sLauncherCardViewCls;
        if (c != null) return c;
        try {
            c = Reflect.loadClass("com.android.launcher3.card.LauncherCardView", sLabelLoader);
            if (c != null) sLauncherCardViewCls = c;
        } catch (Throwable ignored) {
        }
        return c;
    }

    private static boolean isNotDesktopLabel(View v) {
        if (v == null) return true;
        if (!isLabel(v)) return true;   // 最便宜：非 BubbleTextView 家族直接排除

        // 单次父链遍历：同时识别「快捷菜单 / 工作区 / 文件夹 / 小组件宿主」。
        boolean inShortcut = false;
        boolean inWorkspace = false;
        boolean widgetHost = false;
        boolean inCard = false;
        Class<?> ds = sDeepShortcutCls;
        Class<?> ws = sWorkspaceCls;
        Class<?> wh = sWidgetHostCls;
        Class<?> lc = launcherCardViewCls();
        View p = v;
        for (int i = 0; i < MAX_PARENT_WALK && p != null; i++) {
            Class<?> c = p.getClass();
            if (c == ds) {
                inShortcut = true;
            } else if (c == ws) {
                inWorkspace = true;
            } else if (wh != null && wh.isInstance(p)) {
                widgetHost = true;
            } else if (lc != null && lc.isInstance(p)) {
                // ★ 桌面 OPPO 卡片（TitleCardView / GroupCardView 等）：
                //   卡片标题挂载在 LauncherCardView 子树内，但其 mInfo 为空、
                //   父链不含 Workspace，原逻辑会误判为「非桌面项」而排除阴影。
                inCard = true;
            } else {
                String cn = c.getName();
                if (cn.startsWith("com.android.launcher3.folder.")) {
                    inWorkspace = true;
                } else if (cn.startsWith("com.android.launcher3.widget.")
                        && cn.endsWith("AppWidgetHostView")) {
                    widgetHost = true;
                }
            }
            if (inShortcut && inWorkspace) break;   // 已足够判定，无需继续向上
            if (inCard) break;                      // 卡片已判定，无需继续向上
            p = parentOf(p);
        }

        // ① 快捷菜单条目：原生语义就是不带应用名阴影，硬排除。
        if (inShortcut) return true;
        // ② 任务栏 / 预测图标：自带双阴影逻辑，不参与。
        Class<?> dsc = sDoubleShadowCls;
        if (dsc != null && dsc.isInstance(v)) return true;
        // ②.5 桌面卡片标题：是桌面项，要挂阴影（否则卡片文字无阴影）。
        if (inCard) return false;

        // ③ 主判据：mInfo 的类型（一次反射读，命中即出结果，不走父链）。
        Object info = null;
        java.lang.reflect.Field f = sInfoField;
        if (f != null) {
            try {
                info = f.get(v);
            } catch (Throwable ignored) {}
        }
        if (info != null) {
            if (sWorkspaceItemCls != null && sWorkspaceItemCls.isInstance(info)) return false;
            if (sFolderInfoCls != null && sFolderInfoCls.isInstance(info)) return false;
            if (sAppWidgetInfoCls != null && sAppWidgetInfoCls.isInstance(info)) return false;
            // mInfo 有值但不是桌面三类：全部应用列表 / 搜索项等 → 排除；
            // 唯一例外是小组件标题（它的 mInfo 语义与网格项不同）。
            return !widgetHost;
        }
        // ④ mInfo 取不到：父链兜底（小组件标题的 mInfo 常常为 null）。
        return !(inWorkspace || widgetHost);
    }

    /** 沿父链向上取一层（ViewParent → View）。 */
    private static View parentOf(View v) {
        android.view.ViewParent vp = v.getParent();
        return (vp instanceof View) ? (View) vp : null;
    }

    /**
     * {@code View#setClipToPadding} / {@code View#getTag(int)} / {@code View#setTag(int,Object)}
     * 都属于 {@code @hide}，公开 SDK 上不可见，只能反射。
     *
     * <p><b>性能</b>：{@code getDeclaredMethod} 每次调用都要扫方法表 + 做访问检查，
     * 原实现放在热路径里等于每加一个图标就查一次。这里把句柄缓存到静态字段，只查一次。
     */
    private static void setClipToPaddingCompat(View v, boolean clip) {
        try {
            java.lang.reflect.Method m = sSetClipToPaddingM;
            if (m == null) {
                m = View.class.getDeclaredMethod("setClipToPadding", boolean.class);
                m.setAccessible(true);
                sSetClipToPaddingM = m;
            }
            m.invoke(v, clip);
        } catch (Throwable ignored) {}
    }

    private static Object getTagCompat(View v, int key) {
        try {
            java.lang.reflect.Method m = sGetTagM;
            if (m == null) {
                m = View.class.getDeclaredMethod("getTag", int.class);
                m.setAccessible(true);
                sGetTagM = m;
            }
            return m.invoke(v, key);
        } catch (Throwable t) {
            try { return v.getTag(); } catch (Throwable ignored) { return null; }
        }
    }

    private static void setTagCompat(View v, int key, Object value) {
        try {
            java.lang.reflect.Method m = sSetTagM;
            if (m == null) {
                m = View.class.getDeclaredMethod("setTag", int.class, Object.class);
                m.setAccessible(true);
                sSetTagM = m;
            }
            m.invoke(v, key, value);
        } catch (Throwable t) {
            try { v.setTag(value); } catch (Throwable ignored) {}
        }
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