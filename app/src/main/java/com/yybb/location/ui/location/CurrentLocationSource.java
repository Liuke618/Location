package com.yybb.location.ui.location;

import android.content.Context;

import androidx.annotation.NonNull;

import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.maps.LocationSource;

import com.elvishew.xlog.XLog;

/**
 * 定位数据源:收编原 MainActivity 的定位构造、配置与启停。
 * 实现 AMap LocationSource 桥接——AMapLocation(高德定位原生返回 GCJ02,且为
 * android.location.Location 子类)直接喂给地图蓝点图层,同时经 LocationListener
 * 交还持有者(MainActivity 的回调体保留在其实现中)。
 */
public class CurrentLocationSource implements LocationSource {
    /**
     * 定位回调
     */
    public interface LocationListener {
        void onReceiveLocation(AMapLocation aMapLocation);

        /**
         * 定位错误回调(errorCode 含义见高德定位 SDK 错误码表)
         */
        void onDiagnostic(int errorCode, String errorInfo);
    }

    private final Context mContext;
    private final LocationListener mListener;
    private OnLocationChangedListener mMapChangedListener;
    private AMapLocationClient mLocClient = null;
    //罗盘方向(0~360),由持有者经传感器回调注入,蓝点随其旋转
    private float mCompassDirection = 0.0f;

    public CurrentLocationSource(Context context, LocationListener listener) {
        mContext = context;
        mListener = listener;
    }

    /** 地图蓝点数据桥(定位图层激活时由 AMap 回调) */
    @Override
    public void activate(@NonNull OnLocationChangedListener onLocationChangedListener) {
        mMapChangedListener = onLocationChangedListener;
        init();
    }

    @Override
    public void deactivate() {
        mMapChangedListener = null;
        stop();
    }

    /** 注入罗盘方向(度),喂给蓝点渲染 */
    public void setCompassDirection(float degrees) {
        mCompassDirection = degrees;
    }

    //开始定位
    public void start() {
        if (mLocClient != null) {
            mLocClient.startLocation();
        }
    }

    //停止定位
    public void stop() {
        if (mLocClient != null) {
            mLocClient.stopLocation();
        }
    }

    /**
     * 彻底释放定位客户端(客户端持有 Activity Context,只在持有者销毁时调用)。
     * 运行中"定位自己的位置"开关切换仍走 stop(),复用客户端避免反复重建。
     */
    public void destroy() {
        if (mLocClient != null) {
            mLocClient.stopLocation();
            mLocClient.onDestroy();
            mLocClient = null;
        }
    }

    //请求一次定位(连续定位模式下触发一次即时请求)
    public void requestLocation() {
        start();
    }

    //初始化定位客户端(隐私合规接口已在 GoApplication 统一调用)
    private void init() {
        //重复激活(定位图层开关切回):复用既有客户端,避免重复构造泄漏
        if (mLocClient != null) {
            start();
            return;
        }
        try {
            mLocClient = new AMapLocationClient(mContext);
            mLocClient.setLocationListener(amapLocation -> {
                if (amapLocation == null) {
                    return;
                }
                if (amapLocation.getErrorCode() != 0) {
                    mListener.onDiagnostic(amapLocation.getErrorCode(), amapLocation.getErrorInfo());
                } else {
                    //罗盘方向写入 bearing,蓝点随设备朝向旋转
                    amapLocation.setBearing(mCompassDirection);
                    if (mMapChangedListener != null) {
                        mMapChangedListener.onLocationChanged(amapLocation);
                    }
                }
                mListener.onReceiveLocation(amapLocation);
            });
            mLocClient.setLocationOption(getLocationClientOption());
            start();
        } catch (Exception e) {
            XLog.e("ERROR: initMapLocation");
        }
    }

    @NonNull
    private static AMapLocationClientOption getLocationClientOption() {
        AMapLocationClientOption locationOption = new AMapLocationClientOption();
        //高精度定位(对应原百度 Hight_Accuracy)
        locationOption.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
        //连续定位间隔 1000ms(与原百度 setScanSpan(1000) 一致)
        locationOption.setInterval(1000);
        //需要地址信息(取当前城市,与原 setIsNeedAddress(true) 一致)
        locationOption.setNeedAddress(true);
        return locationOption;
    }
}
