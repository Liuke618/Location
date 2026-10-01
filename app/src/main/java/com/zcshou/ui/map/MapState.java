package com.zcshou.ui.map;

import com.baidu.mapapi.map.BitmapDescriptor;
import com.baidu.mapapi.map.BitmapDescriptorFactory;
import com.baidu.mapapi.model.LatLng;

import com.zcshou.gogogo.R;

/**
 * 应用级地图状态：原 MainActivity 的地图静态状态（选中点、标记名、当前城市、标记图标）收编于此，
 * 经 AppContainer 以单例形式跨页面共享。语义与原静态字段保持一致（选中点可为 null）。
 */
public class MapState {
    /* 当前标记的地图点（初始值与原静态字段一致） */
    private LatLng mSelectedPoint = new LatLng(36.547743718042415, 117.07018449827267);
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
