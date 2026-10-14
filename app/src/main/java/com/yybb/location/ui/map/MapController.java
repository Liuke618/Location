package com.yybb.location.ui.map;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.LocationSource;
import com.amap.api.maps.MapView;
import com.amap.api.maps.UiSettings;
import com.amap.api.maps.model.BitmapDescriptor;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.maps.model.MyLocationStyle;
import com.amap.api.maps.model.Poi;

import com.yybb.location.R;
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

    // 地图初始化(定位数据源在此接线;图层是否启用由持有者按"定位自己的位置"开关决定,见 setMyLocationEnabled)
    public void init(LocationSource locationSource) {
        mAMap.setMapType(AMap.MAP_TYPE_NORMAL);
        //缩放按钮用 main_map.xml 右侧悬浮组的自定义实现,高德内置缩放控件默认开启,不关会与之重复
        UiSettings uiSettings = mAMap.getUiSettings();
        uiSettings.setZoomControlsEnabled(false);
        mAMap.setMyLocationStyle(new MyLocationStyle().myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER));
        mAMap.setLocationSource(locationSource);
        mAMap.setOnMapClickListener(point -> mListener.onMapClick(point.longitude, point.latitude));
        mAMap.setOnMapLongClickListener(point -> mListener.onMapLongClick(point.longitude, point.latitude));
        mAMap.setOnPOIClickListener(poi -> mListener.onPoiClick(poi));
    }

    //定位图层开关(真实定位):关闭时不激活 LocationSource,不发起任何真实定位请求
    public void setMyLocationEnabled(boolean enabled) {
        mAMap.setMyLocationEnabled(enabled);
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

    //清空覆盖物(持有者直接清图时经此走,保持 mMarker 引用一致)
    public void clearOverlays() {
        mAMap.clear();
        mMarker = null;
    }

    //GO 成功后以"模拟中"图标在传送点原位保留标记:选中点已由持有者消费置空,此标记只指示当前模拟位置
    //(再选新点经 markMap 顶掉;停服时由持有者经 clearOverlays 清除)。锚点取中心,区别于底部尖端的选中图钉
    public void markMockingPosition(LatLng gcjPoint) {
        mAMap.clear();
        mMarker = mAMap.addMarker(new MarkerOptions()
                .position(gcjPoint)
                .anchor(0.5f, 0.5f)
                .icon(BitmapDescriptorFactory.fromBitmap(rasterize(R.drawable.ic_fly))));
    }

    //高德 fromResource 按 BitmapFactory 解码、不认向量资源,ic_fly 需经 Canvas 手动栅格化;
    //原色纯白在浅色底图上对比不足,按主题青色(colorPrimary)着色
    private Bitmap rasterize(int drawableRes) {
        Drawable drawable = AppCompatResources.getDrawable(mMapView.getContext(), drawableRes).mutate();
        drawable.setTint(ContextCompat.getColor(mMapView.getContext(), R.color.colorPrimary));
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(new Canvas(bitmap));
        return bitmap;
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
