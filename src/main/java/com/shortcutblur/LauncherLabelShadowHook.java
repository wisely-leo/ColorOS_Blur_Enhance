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

    /** 阴影色相的固定部分：阴影恒为纯黑，只有 alpha（浓度）可调。 */
    private static final int SHADOW_RGB_BLACK = 0x000000;

    private static volatile boolean sInstalled = false;
    private static volatile Class<?> sDeepShortcutCls = null;
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
                            args[0] = FeatureFlags.LAUNCHER_LABEL_SHADOW_RADIUS_DP * density;
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
            tv.setShadowLayer(FeatureFlags.LAUNCHER_LABEL_SHADOW_RADIUS_DP * density,
                    0f,
                    FeatureFlags.LAUNCHER_LABEL_SHADOW_DY_DP * density,
                    shadowColorFor(tv));
        } catch (Throwable t) {
            ModuleLog.swallow("launcher.label.apply", t);
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
        Class<?> ds = sDeepShortcutCls;
        try {
            if (ds != null) {
                Class<?> dsc = Reflect.loadClass(CLS_DOUBLE_SHADOW_BTV, v.getClass().getClassLoader());
                if (dsc != null && dsc.isInstance(v)) return true;
            }
        } catch (Throwable ignored) {}
        try {
            View p = v;
            for (int i = 0; i < 6 && p != null; i++) {
                if (ds != null && ds.isInstance(p)) return true;
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