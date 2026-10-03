package com.zcshou.ui.map;

import android.os.Bundle;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.LocationSource;
import com.amap.api.maps.MapView;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.maps.model.MyLocationStyle;
import com.amap.api.maps.model.Poi;

import com.elvishew.xlog.XLog;

/**
 * 主界面地图控制器:持有 MapView/AMap,负责地图初始化、标记、复位与历史位置展示。
 * 点击事件经 MapEventListener 回调给持有者(MainActivity);双击缩放为高德内置手势,无回调。
 * 蓝点数据经 LocationSource 由 CurrentLocationSource 桥接(高德定位原生返回 GCJ02)。
 */
public class MapController {
    /**
     * 地图点击事件回调
     */
    public interface MapEventListener {
        void onMapClick(double lng, double lat);

        void onMapLongClick(double lng, double lat);

        void onPoiClick(Poi poi);
    }

    private final MapView mMapView;
    private final AMap mAMap;
    private final MapState mMapState;
    private final MapEventListener mListener;
    //当前选中点标记(markMap/showLocation 复用;长按逆地理完成后在其上弹信息窗)
    private Marker mMarker;

    public MapController(MapView mapView, AMap aMap, MapState mapState, MapEventListener listener) {
        mMapView = mapView;
        mAMap = aMap;
        mMapState = mapState;
        mListener = listener;
    }

    public MapView getMapView() {
        return mMapView;
    }

    public AMap getAMap() {
        return mAMap;
    }

    public void onCreate(Bundle savedInstanceState) {
        mMapView.onCreate(savedInstanceState);
    }

    public void onResume() {
        mMapView.onResume();
    }

    public void onPause() {
        mMapView.onPause();
    }

    public void onDestroy() {
        mMapView.onDestroy();
    }

    // 地图初始化(蓝点:显示带方向、视野不跟随,与原百度图层行为一致)
    public void init(LocationSource locationSource) {
        mAMap.setMapType(AMap.MAP_TYPE_NORMAL);
        mAMap.setMyLocationStyle(new MyLocationStyle().myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER));
        mAMap.setLocationSource(locationSource);
        mAMap.setMyLocationEnabled(true);
        mAMap.setOnMapClickListener(point -> mListener.onMapClick(point.longitude, point.latitude));
        mAMap.setOnMapLongClickListener(point -> mListener.onMapLongClick(point.longitude, point.latitude));
        mAMap.setOnPOIClickListener(poi -> mListener.onPoiClick(poi));
    }

    //标定选择的位置
    public void markMap() {
        LatLng markLatLng = mMapState.getSelectedPoint();
        if (markLatLng != null) {
            mAMap.clear();
            mMarker = mAMap.addMarker(new MarkerOptions().position(markLatLng).icon(mMapState.getMarkerIcon()));
        }
    }

    public void resetMap(double curLat, double curLon) {
        mAMap.clear();
        mMarker = null;
        mMapState.clearSelectedPoint();
        mAMap.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(curLat, curLon), 18.0f));
    }

    //长按逆地理完成后在选中标记上弹自定义信息窗(窗体视图由持有者经 InfoWindowAdapter 提供)
    public void showInfoWindowOnSelectedMarker() {
        if (mMarker != null) {
            mMarker.showInfoWindow();
        }
    }

    // 在地图上显示位置
    public boolean showLocation(String name, String gcjLongitude, String gcjLatitude) {
        boolean ret = true;

        try {
            if (!gcjLongitude.isEmpty() && !gcjLatitude.isEmpty()) {
                mMapState.setMarkName(name);
                LatLng markLatLng = new LatLng(Double.parseDouble(gcjLatitude), Double.parseDouble(gcjLongitude));
                mMapState.setSelectedPoint(markLatLng);
                mAMap.clear();
                mMarker = mAMap.addMarker(new MarkerOptions().position(markLatLng).icon(mMapState.getMarkerIcon()));
                mAMap.moveCamera(CameraUpdateFactory.changeLatLng(markLatLng));
            }
        } catch (Exception e) {
            ret = false;
            XLog.e("ERROR: showHistoryLocation");
        }

        return ret;
    }
}
