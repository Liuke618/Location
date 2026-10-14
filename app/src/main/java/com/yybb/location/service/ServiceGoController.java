package com.yybb.location.service;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;

import androidx.core.content.ContextCompat;

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
                // 服务进程死亡后旧 binder 不可再调用，置空让后续 setPosition 走"未连接"分支而不是打到失效对象上；
                // BIND_AUTO_CREATE 下系统会重启服务并回调 onServiceConnected 恢复引用
                mServiceBinder = null;
                XLog.e("SERVICEGO: onServiceDisconnected");
            }
        };
    }

    @Override
    public boolean start(double lng, double lat, double alt) {
        // 与 ServiceGo.onCreate 的中止条件对齐：缺定位权限时服务会自行 stopSelf，
        // 若在此照常回报成功，界面会弹"模拟位置已启动"而实际什么都没模拟
        if (!hasLocationPermission()) {
            XLog.e("SERVICEGO: start refused - location permission not granted");
            return false;
        }

        Intent serviceGoIntent = new Intent(mActivityContext, ServiceGo.class);
        boolean bound = mActivityContext.bindService(serviceGoIntent, mConnection, Context.BIND_AUTO_CREATE);    // 绑定服务和活动，之后活动就可以去调服务的方法了
        serviceGoIntent.putExtra(LNG_MSG_ID, lng);
        serviceGoIntent.putExtra(LAT_MSG_ID, lat);
        serviceGoIntent.putExtra(ALT_MSG_ID, alt);

        ComponentName started = mActivityContext.startForegroundService(serviceGoIntent);
        XLog.d("startForegroundService: ServiceGo");

        isMockServStart = bound && started != null;
        return isMockServStart;
    }

    @Override
    public void stop() {
        try {
            mActivityContext.unbindService(mConnection); // 解绑服务，服务要记得解绑，不要造成内存泄漏
        } catch (IllegalArgumentException e) {
            // 从未成功 bind（如 start 阶段被权限拒绝后的收尾）时 unbind 会抛 IllegalArgumentException，忽略即可
            XLog.e("SERVICEGO: stop skipped - service not bound");
        }
        mServiceBinder = null;
        Intent serviceGoIntent = new Intent(mActivityContext, ServiceGo.class);
        mActivityContext.stopService(serviceGoIntent);
        isMockServStart = false;
    }

    @Override
    public boolean setPosition(double lng, double lat, double alt) {
        // 防御性判断：服务尚未连接完成时 binder 为空，原实现此处会直接 NPE；改为记日志后忽略，用户可见行为不变
        if (mServiceBinder == null) {
            XLog.e("SERVICEGO: setPosition ignored - service binder not connected yet");
            return false;
        }

        return mServiceBinder.setPosition(lng, lat, alt);
    }

    @Override
    public boolean isRunning() {
        // 本地标志：只反映本控制器的 start/stop 调用史，不查询服务真实存活（服务被杀后仍可能返回 true，
        // 配合 stop() 的 try/catch unbind 与 setPosition 判空兜底，不做跨进程状态查询）
        return isMockServStart;
    }

    /* 定位运行时权限（FINE 或 COARSE 任一）——ServiceGo 以同一条件决定是否建立供给循环 */
    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(mActivityContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(mActivityContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
}
