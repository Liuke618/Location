package com.zcshou.ui.map;

import com.amap.api.maps.model.BitmapDescriptor;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.LatLng;

import com.yybb.location.R;

/**
 * 应用级地图状态：原 MainActivity 的地图静态状态（选中点、标记名、当前城市、标记图标）收编于此，
 * 经 AppContainer 以单例形式跨页面共享。语义与原静态字段保持一致（选中点可为 null）。
 */
public class MapState {
    /* 当前标记的地图点(初始占位点,已由原 BD09 值换算为 GCJ02) */
    private LatLng mSelectedPoint = new LatLng(36.54208893720672, 117.06357864688202);
    private String mMarkName = null;
    private String mCurrentCity = null;
    private BitmapDescriptor mMarkerIcon = null;

    public LatLng getSelectedPoint() {
        return mSelectedPoint;
    }

    public void setSelectedPoint(LatLng selectedPoint) {
        mSelectedPoint = selectedPoint;
    }

    public void clearSelectedPoint() {
        mSelectedPoint = null;
    }

    public String getMarkName() {
        return mMarkName;
    }

    public void setMarkName(String markName) {
        mMarkName = markName;
    }

    public String getCurrentCity() {
        return mCurrentCity;
    }

    public void setCurrentCity(String currentCity) {
        mCurrentCity = currentCity;
    }

    /* 标记图标：与原静态字段一致，整个应用只创建一次 */
    public synchronized BitmapDescriptor getMarkerIcon() {
        if (mMarkerIcon == null) {
            mMarkerIcon = BitmapDescriptorFactory.fromResource(R.drawable.icon_gcoding);
        }
        return mMarkerIcon;
    }
}
