package com.zcshou.ui.map;

import com.baidu.mapapi.map.BaiduMap;
import com.baidu.mapapi.map.MapPoi;
import com.baidu.mapapi.map.MapStatus;
import com.baidu.mapapi.map.MapStatusUpdate;
import com.baidu.mapapi.map.MapStatusUpdateFactory;
import com.baidu.mapapi.map.MapView;
import com.baidu.mapapi.map.MarkerOptions;
import com.baidu.mapapi.map.MyLocationData;
import com.baidu.mapapi.model.LatLng;

import com.elvishew.xlog.XLog;

/**
 * 主界面地图控制器：持有 MapView/BaiduMap，负责地图初始化、标记、复位与历史位置展示。
 * 点击事件经 MapEventListener 回调给持有者（MainActivity，监听体逻辑原样保留在其实现中）。
 */
public class MapController {
    /**
     * 地图点击事件回调
     */
    public interface MapEventListener {
        void onMapClick(double lng, double lat);

        void onMapLongClick(double lng, double lat);

        void onMapDoubleClick(double lng, double lat);

        void onPoiClick(MapPoi poi);
    }

    private final MapView mMapView;
    private final BaiduMap mBaiduMap;
    private final MapState mMapState;
    private final MapEventListener mListener;

    public MapController(MapView mapView, BaiduMap baiduMap, MapState mapState, MapEventListener listener) {
        mMapView = mapView;
        mBaiduMap = baiduMap;
        mMapState = mapState;
        mListener = listener;
    }

    public MapView getMapView() {
        return mMapView;
    }

    public BaiduMap getBaiduMap() {
        return mBaiduMap;
    }

    // 地图初始化
    public void init() {
        mBaiduMap.setMapType(BaiduMap.MAP_TYPE_NORMAL);
        mBaiduMap.setMyLocationEnabled(true);
        mBaiduMap.setOnMapTouchListener(event -> {

        });
        mBaiduMap.setOnMapClickListener(new BaiduMap.OnMapClickListener() {
            /**
             * 单击地图
             */
            @Override
            public void onMapClick(LatLng point) {
                mListener.onMapClick(point.longitude, point.latitude);
            }
            /**
             * 单击地图中的POI点
             */
            @Override
            public void onMapPoiClick(MapPoi poi) {
                mListener.onPoiClick(poi);
            }
        });
        mBaiduMap.setOnMapLongClickListener(new BaiduMap.OnMapLongClickListener() {
            /**
             * 长按地图
             */
            @Override
            public void onMapLongClick(LatLng point) {
                mListener.onMapLongClick(point.longitude, point.latitude);
            }
        });
        mBaiduMap.setOnMapDoubleClickListener(new BaiduMap.OnMapDoubleClickListener() {
            /**
             * 双击地图
             */
            @Override
            public void onMapDoubleClick(LatLng point) {
                mListener.onMapDoubleClick(point.longitude, point.latitude);
            }
        });
    }

    //标定选择的位置
    public void markMap() {
        LatLng markLatLng = mMapState.getSelectedPoint();
        if (markLatLng != null) {
            MarkerOptions ooA = new MarkerOptions().position(markLatLng).icon(mMapState.getMarkerIcon());
            mBaiduMap.clear();
            mBaiduMap.addOverlay(ooA);
        }
    }

    public void resetMap(double curLat, double curLon, float dir) {
        mBaiduMap.clear();
        mMapState.clearSelectedPoint();

        MyLocationData locData = new MyLocationData.Builder()
                .latitude(curLat)
                .longitude(curLon)
                .direction(dir)
                .build();
        mBaiduMap.setMyLocationData(locData);

        MapStatus.Builder builder = new MapStatus.Builder();
        builder.target(new LatLng(curLat, curLon)).zoom(18.0f);
        mBaiduMap.animateMapStatus(MapStatusUpdateFactory.newMapStatus(builder.build()));
    }

    // 在地图上显示位置
    public boolean showLocation(String name, String bd09Longitude, String bd09Latitude) {
        boolean ret = true;

        try {
            if (!bd09Longitude.isEmpty() && !bd09Latitude.isEmpty()) {
                mMapState.setMarkName(name);
                LatLng markLatLng = new LatLng(Double.parseDouble(bd09Latitude), Double.parseDouble(bd09Longitude));
                mMapState.setSelectedPoint(markLatLng);
                MarkerOptions ooA = new MarkerOptions().position(markLatLng).icon(mMapState.getMarkerIcon());
                mBaiduMap.clear();
                mBaiduMap.addOverlay(ooA);
                MapStatusUpdate mapstatusupdate = MapStatusUpdateFactory.newLatLng(markLatLng);
                mBaiduMap.setMapStatus(mapstatusupdate);
            }
        } catch (Exception e) {
            ret = false;
            XLog.e("ERROR: showHistoryLocation");
        }

        return ret;
    }
}
