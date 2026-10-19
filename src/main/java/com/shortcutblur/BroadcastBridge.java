package com.shortcutblur;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import java.lang.reflect.Executable;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

final class BroadcastBridge {

    static final String ACTION_SETCONF = "com.wiselyleo.blurenhance.SETCONF";

    interface Hooker {
        void hook(Executable target, XposedInterface.Hooker hooker);
    }

    private static volatile boolean sScreenReceiverInstalled = false;
    private static volatile boolean sConfReceiverInstalled = false;

    private static Hooker sHooker;
    private static String sBuildTag = "";

    private BroadcastBridge() {}

    static void install(final BlurEnhanceModule module,
                        XposedModuleInterface.PackageReadyParam param,
                        String buildTag) {
        sBuildTag = buildTag == null ? "" : buildTag;
        sHooker = new Hooker() {
            @Override public void hook(Executable target, XposedInterface.Hooker h) {
                module.hook(target).intercept(h);
            }
        };
        installScreenReceiverViaApp(param);
    }

    private static void installScreenReceiverViaApp(XposedModuleInterface.PackageReadyParam param) {
        if (sScreenReceiverInstalled) return;
        synchronized (BroadcastBridge.class) {
            if (sScreenReceiverInstalled) return;
            try {
                ClassLoader cl = param.getClassLoader();
                if (cl == null) { ModuleLog.d("SCREEN", "loader null"); return; }
                ModuleLog.d("SCREEN", "try hook Application.attach via loader=" + cl.getClass().getName());
                Class<?> appCls = Class.forName("android.app.Application", false, cl);
                for (final java.lang.reflect.Method m : appCls.getDeclaredMethods()) {
                    if (!m.getName().equals("attach")) continue;
                    Class<?>[] ps = m.getParameterTypes();
                    if (ps.length != 1 || !ps[0].getName().equals("android.content.Context")) continue;
                    m.setAccessible(true);
                    sHooker.hook(m, chain -> {
                        Object r = chain.proceed();
                        try {
                            Object self = chain.getThisObject();
                            if (self instanceof Context) {
                                if (!sScreenReceiverInstalled) {
                                    registerScreenReceiver((Context) self);
                                    registerConfReceiver((Context) self);
                                }
                            }
                        } catch (Throwable t) {
                            ModuleLog.d("SCREEN", "attach hook err: " + t);
                        }
                        return r;
                    });
                    ModuleLog.d("SCREEN", "hooked Application.attach (waiting)");
                    return;
                }
                ModuleLog.d("SCREEN", "Application.attach not found");
            } catch (Throwable t) {
                ModuleLog.d("SCREEN", "hook attach err: " + t);
            }
        }
    }

    private static void registerScreenReceiver(Context ctx) {
        synchronized (BroadcastBridge.class) {
            if (sScreenReceiverInstalled) return;
            try {
                IntentFilter f = new IntentFilter();
                f.addAction(Intent.ACTION_SCREEN_ON);
                f.addAction(Intent.ACTION_SCREEN_OFF);
                f.addAction(Intent.ACTION_USER_PRESENT);
                ctx.registerReceiver(new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent i) {
                        String a = i == null ? "?" : i.getAction();
                        boolean on = !Intent.ACTION_SCREEN_OFF.equals(a);
                        ModuleLog.d("SCREEN", "action=" + a + " screenOn=" + on);
                        if (FeatureFlags.WIDGET_BLUR) {
                            GlyphBlurRenderer.setScreenOn(on);
                        }
                    }
                }, f);
                sScreenReceiverInstalled = true;
                ModuleLog.d("READY", "screen receiver installed ctx=" + ctx.getClass().getName());
            } catch (Throwable t) {
                ModuleLog.d("SCREEN", "registerReceiver err: " + t);
            }
        }
    }

    private static void registerConfReceiver(Context ctx) {
        synchronized (BroadcastBridge.class) {
            if (sConfReceiverInstalled) return;
            try {
                IntentFilter f = new IntentFilter(ACTION_SETCONF);
                BroadcastReceiver recv = new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent i) {
                        if (i == null) return;
                        try {
                            RecentsBlur.applyConf(i);
                            boolean clockChanged = FeatureFlags.applyFromIntent(i);
                            if (clockChanged) {
                                try { GlyphBlurRenderer.notifyContentMaybeChangedAll(); }
                                catch (Throwable ignored) { ModuleLog.swallow("module.notify", ignored); }
                            }
                            String mk = i.getStringExtra("mark");
                            if (mk != null) ModuleLog.d("MARK", "==== " + mk + " ====");
                            String vb = i.getStringExtra("verbose");
                            if (vb != null) ModuleLog.VERBOSE = !vb.trim().equalsIgnoreCase("off");
                        } catch (Throwable t) {
                            ModuleLog.e("CONF", "live set failed", t);
                        }
                    }
                };
                try {
                    ctx.registerReceiver(recv, f);
                } catch (Throwable t1) {
                    ctx.registerReceiver(recv, f, Context.RECEIVER_EXPORTED);
                }
                sConfReceiverInstalled = true;
                ModuleLog.d("CONF", "conf receiver installed action=" + ACTION_SETCONF);
                ModuleLog.d("VER", "build=" + sBuildTag + RecentsBlur.describe());
            } catch (Throwable t) {
                ModuleLog.e("CONF", "register conf receiver failed", t);
            }
        }
    }
}
