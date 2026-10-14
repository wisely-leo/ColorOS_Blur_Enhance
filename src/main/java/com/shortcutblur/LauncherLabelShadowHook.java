package com.shortcutblur;

import android.view.View;
import android.widget.TextView;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;

final class LauncherLabelShadowHook {

    private static final String TAG = "LABELSHADOW";
    private static final String CLS_BUBBLE_TV = "com.android.launcher3.BubbleTextView";
    private static final String CLS_OPLUS_BTV = "com.android.launcher3.OplusBubbleTextView";
    private static final String CLS_TEXTVIEW = "android.widget.TextView";

    private static final String CLS_DEEP_SHORTCUT_VIEW = "com.android.launcher3.shortcuts.DeepShortcutView";

    private static final String CLS_DOUBLE_SHADOW_BTV = "com.android.launcher3.views.DoubleShadowBubbleTextView";

    private static final String CLS_IBTV_EXT = "com.android.launcher3.IBubbleTextViewExt";

    private static final String FLD_MINFO = "mInfo";

    private static final String FLD_WIDGET_NAME = "widgetName";

    private static final String FLD_TEXT_CLIP_PATH = "mTextClipPath";

    private static final String FLD_SHADOW_RADIUS = "mShadowRadius";

    private static final String CLS_WORKSPACE_ITEM_INFO = "com.android.launcher3.model.data.WorkspaceItemInfo";

    private static final String CLS_FOLDER_INFO = "com.android.launcher3.model.data.FolderInfo";

    private static final String CLS_APPWIDGET_INFO = "com.android.launcher3.model.data.LauncherAppWidgetInfo";

    private static final String CLS_WIDGET_HOST_VIEW = "com.android.launcher3.widget.BaseLauncherAppWidgetHostView";

    private static final int MAX_PARENT_WALK = 10;

    private static final float PAD_EXTRA_PX = 2f;

    private static final int[] REAPPLY_DELAYS = {120, 300, 600};

    private static final int SHADOW_RGB_BLACK = 0x000000;

    private static volatile boolean sInstalled = false;
    private static volatile Class<?> sDeepShortcutCls = null;
    private static volatile java.lang.reflect.Field sInfoField = null;
    private static volatile java.lang.reflect.Field sWidgetNameField = null;

    private static volatile java.lang.reflect.Field sTextClipField = null;

    private static volatile java.lang.reflect.Field sShadowRadiusField = null;
    private static volatile boolean sShadowRadiusTried = false;

    private static volatile TextView sDrawingOwner = null;
    private static volatile android.graphics.Path sDrawingPath = null;

    private static volatile java.lang.reflect.Method sSetClipToPaddingM = null;
    private static volatile java.lang.reflect.Method sGetTagM = null;
    private static volatile java.lang.reflect.Method sSetTagM = null;

    private static volatile Class<?> sWorkspaceItemCls = null;
    private static volatile Class<?> sFolderInfoCls = null;
    private static volatile Class<?> sAppWidgetInfoCls = null;
    private static volatile Class<?> sWidgetHostCls = null;

    private static volatile Class<?> sDoubleShadowCls = null;

    private static volatile Class<?> sWorkspaceCls = null;

    private static final int SHADOW_OVERFLOW_TAG = 0x7f0e0462;
    private static volatile Class<?> sLabelCls = null;

    private LauncherLabelShadowHook() {}

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
        n += hookWidgetTitleBind(mod);
        ModuleLog.d(TAG, "installed hooks=" + n + " label=" + sLabelCls.getName());
        return n;
    }

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

                            if (!FeatureFlags.LAUNCHER_LABEL_SHADOW || !isLabel(self)
                                    || isNotDesktopLabel((View) self)) {
                                return chain.proceed();
                            }
                            Object[] args = chain.getArgs().toArray();
                            if (args.length < 4) return chain.proceed(args);
                            TextView tv = (TextView) self;
                            float density = densityOf(tv);

                            enableShadowOverflow(tv);
                            args[0] = clampRadius(tv, FeatureFlags.LAUNCHER_LABEL_SHADOW_RADIUS_DP * density);
                            args[1] = 0f;
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

    private static int hookApplyLabel(final BlurEnhanceModule mod) {
        final Class<?> cls = sLabelCls;
        if (cls == null) return 0;
        try {
            Method m = Reflect.method(cls, "applyLabel", 1);
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

    private static void scheduleReapply(final TextView tv) {
        if (tv == null) return;
        final Runnable again = new Runnable() {
            @Override
            public void run() {
                try {

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

                            android.graphics.Path target = sDrawingPath;
                            if (target == null) return chain.proceed();
                            TextView owner = sDrawingOwner;
                            if (owner == null) return chain.proceed();

                            sDrawingPath = null;
                            sDrawingOwner = null;
                            try {

                                float pad = FeatureFlags.LAUNCHER_LABEL_SHADOW_RADIUS_DP
                                        * densityOf(owner) + PAD_EXTRA_PX;
                                int w = owner.getWidth();
                                int h = owner.getHeight();
                                target.reset();
                                target.addRect(-pad, -pad, w + pad, h + pad,
                                        android.graphics.Path.Direction.CW);
                            } catch (Throwable t) {

                                ModuleLog.swallow("launcher.label.clippath", t);
                            }

                            return chain.proceed();
                        }
                    });
            return 1;
        } catch (Throwable t) {
            ModuleLog.e(TAG, "hook clipPath failed", t);
            return 0;
        }
    }

    private static int hookDrawText(final BlurEnhanceModule mod) {
        final Class<?> cls = sLabelCls;
        if (cls == null) return 0;
        try {
            Method m = Reflect.method(cls, "drawText", 2);
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

    private static boolean hasShadowLayer(TextView tv) {
        try {
            java.lang.reflect.Field f = shadowRadiusField();
            if (f != null) return f.getFloat(tv) > 0f;
        } catch (Throwable ignored) {}
        return false;
    }

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

    private static int hookWidgetTitleBind(final BlurEnhanceModule mod) {
        try {
            Class<?> helper = Reflect.loadClass(
                    "com.android.launcher3.widget.utils.WidgetNameHelper",
                    LauncherLabelShadowHook.class.getClassLoader());
            if (helper == null) {
                ModuleLog.d(TAG, "WidgetNameHelper not found (ok)");
                return 0;
            }
            Method m = Reflect.method(helper, "title", 2);
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

    private static void enableShadowOverflow(TextView tv) {
        try {
            if (Boolean.TRUE.equals(getTagCompat(tv, SHADOW_OVERFLOW_TAG))) return;
            setTagCompat(tv, SHADOW_OVERFLOW_TAG, Boolean.TRUE);
            setClipToPaddingCompat(tv, false);

            Class<?> ws = sWorkspaceCls;
            View cur = parentOf(tv);
            for (int i = 0; i < 6 && cur != null; i++) {
                if (!(cur instanceof android.view.ViewGroup)) break;
                Class<?> c = cur.getClass();
                if (c == ws || c.getName().startsWith("com.android.launcher3.folder.")) {
                    break;
                }
                ((android.view.ViewGroup) cur).setClipChildren(false);
                cur = parentOf(cur);
            }
        } catch (Throwable t) {
            ModuleLog.swallow("launcher.label.overflow", t);
        }
    }

    private static float clampRadius(TextView tv, float radiusPx) {

        try {
            int fh = tv.getLineHeight();
            if (fh < 12) return radiusPx;
            float max = fh * 0.75f;
            return Math.min(radiusPx, max);
        } catch (Throwable t) {
            return radiusPx;
        }
    }

    private static int shadowColorFor(TextView tv) {
        return (FeatureFlags.LAUNCHER_LABEL_SHADOW_DARK & 0xFF000000) | SHADOW_RGB_BLACK;
    }

    private static boolean isNotDesktopLabel(View v) {
        if (v == null) return true;
        if (!isLabel(v)) return true;

        boolean inShortcut = false;
        boolean inWorkspace = false;
        boolean widgetHost = false;
        Class<?> ds = sDeepShortcutCls;
        Class<?> ws = sWorkspaceCls;
        Class<?> wh = sWidgetHostCls;
        View p = v;
        for (int i = 0; i < MAX_PARENT_WALK && p != null; i++) {
            Class<?> c = p.getClass();
            if (c == ds) {
                inShortcut = true;
            } else if (c == ws) {
                inWorkspace = true;
            } else if (wh != null && wh.isInstance(p)) {
                widgetHost = true;
            } else {
                String cn = c.getName();
                if (cn.startsWith("com.android.launcher3.folder.")) {
                    inWorkspace = true;
                } else if (cn.startsWith("com.android.launcher3.widget.")
                        && cn.endsWith("AppWidgetHostView")) {
                    widgetHost = true;
                }
            }
            if (inShortcut && inWorkspace) break;
            p = parentOf(p);
        }

        if (inShortcut) return true;

        Class<?> dsc = sDoubleShadowCls;
        if (dsc != null && dsc.isInstance(v)) return true;

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

            return !widgetHost;
        }

        return !(inWorkspace || widgetHost);
    }

    private static View parentOf(View v) {
        android.view.ViewParent vp = v.getParent();
        return (vp instanceof View) ? (View) vp : null;
    }

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
