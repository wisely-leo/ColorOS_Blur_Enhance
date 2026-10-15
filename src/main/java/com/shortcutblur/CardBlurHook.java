package com.shortcutblur;

import android.view.View;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 卡片自动模糊 hook：hook 卡片类的生命周期，让卡片「出现即带模糊」。
 *
 * <p>OPPO 卡片（{@code com.android.launcher3.card.*}）挂到窗口时会走
 * {@code onAttachedToWindow()}；内容加载完成会走 {@code initCardAfterInflate()}。
 * 我们在这两个点后自动调用 {@link CardBlurAttacher#attach}，
 * 于是卡片一出现在屏幕上就已经挂上模糊。
 *
 * <p>hook 目标（均为 public/可反射方法）：
 * <ul>
 *   <li>{@code TitleCardView.onAttachedToWindow()} / {@code initCardAfterInflate()}</li>
 *   <li>{@code GroupCardView.onAttachedToWindow()} / {@code initCardAfterInflate()}</li>
 * </ul>
 *
 * <p>回调里做两件事：给卡片挂模糊（是否受理由 {@link CardBlurAttacher#isCard} 判定），
 * 以及给卡片标题补阴影（{@link LauncherLabelShadowHook#applyToLabelTree}）。
 * 某个方法不存在（版本差异）时跳过，不影响其它 hook。
 */
final class CardBlurHook {

    private static final String TAG = "LAUNCHCARD";

    private static final String[] TARGET_CLASSES = {
            "com.android.launcher3.card.TitleCardView",
            "com.android.launcher3.card.groupcard.GroupCardView",
    };

    private CardBlurHook() {}

    /** 安装卡片自动模糊 hook，返回安装成功的方法数。 */
    static int install(final BlurEnhanceModule mod, final ClassLoader cl) {
        int n = 0;
        for (String cn : TARGET_CLASSES) {
            n += hookClass(mod, cl, cn);
        }
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

    /** hook 目标类的指定无参方法，在其返回后自动给卡片挂模糊。 */
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
                                    // initCardAfterInflate = 卡片内容加载完成（真内容就绪）：
                                    // 必须清掉 sDone，允许重新 attach（否则之前打到占位层的记录会挡住）。
                                    if ("initCardAfterInflate".equals(methodName)) {
                                        CardBlurAttacher.forget(card);
                                        // 卡片标题由快应用自绘，不走 launcher 的阴影链路 → 主动补挂阴影。
                                        LauncherLabelShadowHook.applyToLabelTree(card);
                                    }
                                    // 卡片刚 attach/刚 inflate 时尺寸可能为 0，
                                    // attach 内部带 retry（postDelayed）会自动兜住。
                                    // 注意：这里不做任何「解除定论」动作 —— 定论状态由
                                    // CardBlurAttacher 内部的 sSettled 闸门短路（切页回来
                                    // 不会重跑重试链）；解除只由本回调的 forget() 承担
                                    // （即：仅内容真正变化时才重新尝试）。
                                    if (card.isAttachedToWindow()) {
                                        CardBlurAttacher.attach(card, card.getClass().getClassLoader());
                                        if (ModuleLog.enabled()) {
                                            ModuleLog.d(TAG, "auto-attach via " + methodName
                                                    + " " + c.getSimpleName());
                                        }
                                    } else {
                                        // 还没 attach：post 一帧后再试
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