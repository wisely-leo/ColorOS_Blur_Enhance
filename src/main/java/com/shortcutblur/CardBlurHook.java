package com.shortcutblur;

import android.view.View;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

final class CardBlurHook {

    private static final String TAG = "LAUNCHCARD";

    private static final String[] TARGET_CLASSES = {
            Symbols.CLS_TITLE_CARD,
            Symbols.CLS_GROUP_CARD,
    };

    private CardBlurHook() {}

    static int install(final BlurEnhanceModule mod, final ClassLoader cl) {
        int n = 0;
        for (String cn : TARGET_CLASSES) {
            n += hookClass(mod, cl, cn);
        }
        ModuleLog.d(TAG, "BUILD=v47 op(-2)=pass l40=guard idem=1");
        ModuleLog.d(TAG, "card auto-blur hooks installed n=" + n);
        return n;
    }

    private static int hookClass(final BlurEnhanceModule mod, final ClassLoader cl, String cn) {
        int n = 0;
        Class<?> c;
        try {
            c = Class.forName(cn, false, cl);
        } catch (Throwable t) {
            ModuleLog.d(TAG, "class not found (skip): " + cn);
            return 0;
        }
        n += hookMethod(mod, c, "onAttachedToWindow");
        n += hookMethod(mod, c, "initCardAfterInflate");
        return n;
    }

    private static int hookMethod(final BlurEnhanceModule mod,
                                  final Class<?> c, final String methodName) {
        try {
            final Method m = c.getDeclaredMethod(methodName);
            m.setAccessible(true);
            mod.hook(m)
                    .setId("cardblur." + c.getSimpleName() + "#" + methodName)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            try {
                                Object self = chain.getThisObject();
                                if (self instanceof View) {
                                    final View card = (View) self;
                                    if ("initCardAfterInflate".equals(methodName)) {
                                        CardBlurAttacher.forget(card);
                                        LauncherLabelShadowHook.applyToLabelTree(card);
                                    }
                                    if (card.isAttachedToWindow()) {
                                        CardBlurAttacher.attach(card, card.getClass().getClassLoader());
                                        if (ModuleLog.enabled()) {
                                            ModuleLog.d(TAG, "auto-attach via " + methodName
                                                    + " " + c.getSimpleName());
                                        }
                                    } else {
                                        card.post(new Runnable() {
                                            @Override public void run() {
                                                try {
                                                    if (card.isAttachedToWindow()) {
                                                        CardBlurAttacher.attach(card, card.getClass().getClassLoader());
                                                        if (ModuleLog.enabled()) {
                                                            ModuleLog.d(TAG, "auto-attach(post) via " + methodName
                                                                    + " " + c.getSimpleName());
                                                        }
                                                    }
                                                } catch (Throwable t2) {
                                                    ModuleLog.swallow("cardblur.autohook2", t2);
                                                }
                                            }
                                        });
                                    }
                                }
                            } catch (Throwable t) {
                                ModuleLog.swallow("cardblur.autohook", t);
                            }
                            return r;
                        }
                    });
            ModuleLog.d(TAG, "hook " + c.getSimpleName() + "#" + methodName + " ok");
            return 1;
        } catch (Throwable t) {
            ModuleLog.d(TAG, "hook " + c.getSimpleName() + "#" + methodName + " fail: " + t);
            return 0;
        }
    }
}
