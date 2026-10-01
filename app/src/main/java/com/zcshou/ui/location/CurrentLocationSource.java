package com.zcshou.ui.location;

import android.content.Context;

import androidx.annotation.NonNull;

import com.baidu.location.BDAbstractLocationListener;
import com.baidu.location.BDLocation;
import com.baidu.location.LocationClient;
import com.baidu.location.LocationClientOption;

import com.elvishew.xlog.XLog;

/**
 * 定位数据源：收编原 MainActivity 的百度 LocationClient 构造、配置与启停。
 * 位置与诊断回调经 LocationListener 交还持有者（MainActivity 的回调体原样保留在其实现中）。
 */
public class CurrentLocationSource {
    /**
     * 定位回调
     */
    public interface LocationListener {
        void onReceiveLocation(BDLocation bdLocation);

        /**
         * 错误的状态码
         * <a><a href="http://lbsyun.baidu.com/index.php?title=android-locsdk/guide/addition-func/error-code">...</a></a>
         * <p>
         * 回调定位诊断信息，开发者可以根据相关信息解决定位遇到的一些问题
         *
         * @param locType      当前定位类型
         * @param diagnosticType  诊断类型（1~9）
         * @param diagnosticMessage 具体的诊断信息释义
         */
        void onDiagnostic(int locType, int diagnosticType, String diagnosticMessage);
    }

    private final Context mContext;
    private final LocationListener mListener;
    private LocationClient mLocClient = null;

    public CurrentLocationSource(Context context, LocationListener listener) {
        mContext = context;
        mListener = listener;
    }

    //开启地图的定位图层
    public void init() {
        try {
            // 定位初始化
            mLocClient = new LocationClient(mContext);
            mLocClient.registerLocationListener(new BDAbstractLocationListener() {
                @Override
                public void onReceiveLocation(BDLocation bdLocation) {
                    mListener.onReceiveLocation(bdLocation);
                }

                @Override
                public void onLocDiagnosticMessage(int locType, int diagnosticType, String diagnosticMessage) {
                    mListener.onDiagnostic(locType, diagnosticType, diagnosticMessage);
                }
            });
            LocationClientOption locationOption = getLocationClientOption();
            //需将配置好的LocationClientOption对象，通过setLocOption方法传递给LocationClient对象使用
            mLocClient.setLocOption(locationOption);
            //开始定位
            start();
        } catch (Exception e) {
            XLog.e("ERROR: initMapLocation");
        }
    }

    //开始定位
    public void start() {
        mLocClient.start();
    }

    //停止定位
    public void stop() {
        mLocClient.stop();
    }

    //请求一次定位
    public void requestLocation() {
        mLocClient.requestLocation();
    }

    @NonNull
    private static LocationClientOption getLocationClientOption() {
        LocationClientOption locationOption = new LocationClientOption();
        //可选，默认高精度，设置定位模式，高精度，低功耗，仅设备
        locationOption.setLocationMode(LocationClientOption.LocationMode.Hight_Accuracy);
        //可选，默认gcj02，设置返回的定位结果坐标系，如果配合百度地图使用，建议设置为bd09ll;
        locationOption.setCoorType("bd09ll");
        //可选，默认0，即仅定位一次，设置发起连续定位请求的间隔需要大于等于1000ms才是有效的
        locationOption.setScanSpan(1000);
        //可选，设置是否需要地址信息，默认不需要
        locationOption.setIsNeedAddress(true);
        //可选，设置是否需要设备方向结果
        locationOption.setNeedDeviceDirect(false);
        //可选，默认false，设置是否当gps有效时按照1S1次频率输出GPS结果
        locationOption.setLocationNotify(true);
        //可选，默认true，定位SDK内部是一个SERVICE，并放到了独立进程，设置是否在stop的时候杀死这个进程，默认不杀死
        locationOption.setIgnoreKillProcess(true);
        //可选，默认false，设置是否需要位置语义化结果，可以在BDLocation.getLocationDescribe里得到，结果类似于“在北京天安门附近”
        locationOption.setIsNeedLocationDescribe(false);
        //可选，默认false，设置是否需要POI结果，可以在BDLocation.getPoiList里得到
        locationOption.setIsNeedLocationPoiList(false);
        //可选，默认false，设置是否收集CRASH信息，默认收集
        locationOption.SetIgnoreCacheException(true);
        //可选，默认false，设置是否开启Gps定位
        //locationOption.setOpenGps(true);
        locationOption.setOpenGnss(true);
        //可选，默认false，设置定位时是否需要海拔信息，默认不需要，除基础定位版本都可用
        locationOption.setIsNeedAltitude(false);
        return locationOption;
    }
}
