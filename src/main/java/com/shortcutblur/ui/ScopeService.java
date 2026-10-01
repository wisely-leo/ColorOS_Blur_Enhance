package com.shortcutblur.ui;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Shizuku / Sui 的 UserService —— 本类由 Shizuku 以【shell（或 root）身份】加载运行，
 * 所以这里的 Runtime.exec 就是 ADB 权限（不借用、也不消耗本应用自身的权限）。
 *
 * 协议：手写 binder，不依赖 AIDL 编译 ——
 *   transact(CODE_EXEC, data{String cmd}, reply{int rc, String out})
 *
 * 为什么用 UserService：Shizuku 13 起已移除 newProcess，UserService 是官方推荐路线。
 */
public class ScopeService extends Service {

    /** 执行一条 sh 命令。 */
    public static final int CODE_EXEC = 0x5A01;

    private final IBinder binder = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws android.os.RemoteException {
            if (code != CODE_EXEC) return super.onTransact(code, data, reply, flags);
            String cmd = data.readString();
            int rc = -1;
            String out = "";
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                InputStream is = p.getInputStream();
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                is.close();
                InputStream es = p.getErrorStream();
                while ((n = es.read(buf)) > 0) bo.write(buf, 0, n);
                es.close();
                rc = p.waitFor();
                out = bo.toString("UTF-8");
            } catch (Throwable t) {
                out = "exec 失败: " + t;
            }
            if (reply != null) {
                reply.writeInt(rc);
                reply.writeString(out);
            }
            return true;
        }
    };

    @Override public IBinder onBind(Intent intent) { return binder; }
}