package com.quietmap.app;

import android.app.Application;

/**
 * 模块 App 的 Application 入口：只在模块自己的进程运行。
 *
 * 启动抽象命名空间 socket 服务端，让高德进程无需 ContentProvider、无需存储权限
 * 就能回传诊断数据。Xposed 入口是 java_init.list 里的 QuietMapModule，与这里无关，
 * 不会注入目标进程。
 */
public final class QuietMapApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        ReportSocketServer.start(this);
    }
}
