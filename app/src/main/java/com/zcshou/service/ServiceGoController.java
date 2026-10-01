package com.zcshou.service;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import com.elvishew.xlog.XLog;

/**
 * {@link MockLocationController} 的默认实现，基于 {@link ServiceGo} 前台服务。
 *
 * <p><b>生命周期约定：</b>本控制器持有创建时传入的 Activity Context 用于 bind/unbind 服务，
 * 须由 Activity 在 onCreate 中创建，并随该 Activity 一起废弃（onDestroy 后不得再使用）。
 * 不要以 Application Context 创建做全局或跨 Activity 复用，否则会持有失效的绑定方造成泄漏。
 */
public class ServiceGoController implements MockLocationController {
    /* 对外：ServiceGo 的 Intent extra 键（自 MainActivity 迁入，字符串值不可变更） */
    public static final String LAT_MSG_ID = "LAT_VALUE";
    public static final String LNG_MSG_ID = "LNG_VALUE";
    public static final String ALT_MSG_ID = "ALT_VALUE";

    private final Context mActivityContext;
    private final ServiceConnection mConnection;
    private ServiceGo.ServiceGoBinder mServiceBinder;
    private boolean isMockServStart = false;

    public ServiceGoController(Context activityContext) {
        mActivityContext = activityContext;

        mConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                mServiceBinder = (ServiceGo.ServiceGoBinder) service;
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {

            }
        };
    }

    @Override
    public void start(double lng, double lat, double alt) {
        Intent serviceGoIntent = new Intent(mActivityContext, ServiceGo.class);
        mActivityContext.bindService(serviceGoIntent, mConnection, Context.BIND_AUTO_CREATE);    // 绑定服务和活动，之后活动就可以去调服务的方法了
        serviceGoIntent.putExtra(LNG_MSG_ID, lng);
        serviceGoIntent.putExtra(LAT_MSG_ID, lat);
        serviceGoIntent.putExtra(ALT_MSG_ID, alt);

        mActivityContext.startForegroundService(serviceGoIntent);
        XLog.d("startForegroundService: ServiceGo");

        isMockServStart = true;
    }

    @Override
    public void stop() {
        mActivityContext.unbindService(mConnection); // 解绑服务，服务要记得解绑，不要造成内存泄漏
        Intent serviceGoIntent = new Intent(mActivityContext, ServiceGo.class);
        mActivityContext.stopService(serviceGoIntent);
        isMockServStart = false;
    }

    @Override
    public void setPosition(double lng, double lat, double alt) {
        // 防御性判断：服务尚未连接完成时 binder 为空，原实现此处会直接 NPE；改为记日志后忽略，用户可见行为不变
        if (mServiceBinder == null) {
            XLog.e("SERVICEGO: setPosition ignored - service binder not connected yet");
            return;
        }

        mServiceBinder.setPosition(lng, lat, alt);
    }

    @Override
    public boolean isRunning() {
        return isMockServStart;
    }
}
