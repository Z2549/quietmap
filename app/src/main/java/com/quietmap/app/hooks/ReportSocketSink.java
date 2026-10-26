package com.quietmap.app.hooks;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import com.quietmap.app.ReportSocketProtocol;

import java.io.OutputStream;

/**
 * 目标进程侧（高德）的诊断回传发送端：连接抽象命名空间 LocalSocket，
 * 写「4 字节大端长度 + UTF-8 JSON」，关闭连接。
 *
 * 不依赖存储权限、不依赖 ContentProvider；模块 App 进程未启动/服务端未绑定
 * 时连接立即失败。任何异常都只记日志并返回 false，绝不向 Report 主循环抛出。
 * 超时 1.5 秒，单条 payload 上限 64KB（由 ReportSocketProtocol 校验）。
 */
final class ReportSocketSink {

    private static final String TAG = "QuietMap";
    private static final int READ_TIMEOUT_MS = 1_500;
    private static final long LOG_INTERVAL_MS = 60_000L;

    private static int sFailLogs;
    private static long sLastFailLog;

    private ReportSocketSink() {
    }

    /** 可注入的发送端；JVM 单测用抛异常/返回 false 的假实现验证失败不抛出。 */
    interface Transport {
        boolean send(byte[] frame) throws Exception;
    }

    private static final Transport DEFAULT = ReportSocketSink::sendDefault;

    static boolean write(String json) {
        return write(json, DEFAULT);
    }

    /**
     * @return true 表示已把完整帧写入 socket；编码失败、连接失败、写入失败
     * 一律 false（不抛异常）。
     */
    static boolean write(String json, Transport transport) {
        byte[] frame = ReportSocketProtocol.encode(json);
        if (frame == null || transport == null) {
            return false;
        }
        try {
            return transport.send(frame);
        } catch (Throwable t) {
            logFailure(describe(t));
            return false;
        }
    }

    private static boolean sendDefault(byte[] frame) throws Exception {
        LocalSocket socket = new LocalSocket();
        try {
            socket.setSoTimeout(READ_TIMEOUT_MS);
            socket.connect(new LocalSocketAddress(ReportSocketProtocol.SOCKET_NAME,
                    LocalSocketAddress.Namespace.ABSTRACT));
            OutputStream out = socket.getOutputStream();
            out.write(frame);
            out.flush();
            return true;
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 前 3 次 + 之后每 60 秒一条，避免模块 App 未启动时每 3 秒刷屏。 */
    private static void logFailure(String reason) {
        long now = System.currentTimeMillis();
        if (sFailLogs < 3 || now < sLastFailLog || now - sLastFailLog >= LOG_INTERVAL_MS) {
            sFailLogs++;
            sLastFailLog = now;
            try {
                Log.w(TAG, "qmreport socket sink failed: " + reason);
            } catch (Throwable ignored) {
            }
        }
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "null";
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }
}
