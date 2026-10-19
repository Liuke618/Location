package com.yybb.location.ui.map;

import android.app.Activity;
import android.content.SharedPreferences;

import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.model.LatLng;

import com.yybb.location.R;
import com.yybb.location.data.HistoryRepository;
import com.yybb.location.data.HistoryRepository.LocationRow;
import com.yybb.location.utils.GoUtils;
import com.yybb.location.utils.MapUtils;

import com.elvishew.xlog.XLog;

/**
 * 位置记录读写：上次位置记忆（落盘/冷启动恢复）、定位历史落库、收藏与默认收藏
 * （原 MainActivity“上次位置记忆/历史记录/收藏”三段内联体原样迁出，行为冻结）。
 */
public class LocationRecordHelper {

    private final Activity mActivity;
    private final SharedPreferences sharedPreferences;
    private final GeocoderHelper mGeocoderHelper;
    private final MapState mMapState;
    private final MainViewModel mMainViewModel;
    private final HistoryRepository mHistoryRepository;
    private final MapController mMapController;

    public LocationRecordHelper(Activity activity, SharedPreferences sharedPreferences,
                                GeocoderHelper geocoderHelper, MapState mapState, MainViewModel mainViewModel,
                                HistoryRepository historyRepository, MapController mapController) {
        mActivity = activity;
        this.sharedPreferences = sharedPreferences;
        mGeocoderHelper = geocoderHelper;
        mMapState = mapState;
        mMainViewModel = mainViewModel;
        mHistoryRepository = historyRepository;
        mMapController = mapController;
    }

    /*============================== 上次位置记忆 ==============================*/

    //落盘当前选中点(GCJ02),作为"上一次定位的位置";GO 两个生效分支调用,须在选中点清除前执行
    public void saveLastPosition() {
        LatLng point = mMapState.getSelectedPoint();
        if (point == null) {
            return;
        }

        sharedPreferences.edit()
                .putString("setting_last_lat", String.valueOf(point.latitude))
                .putString("setting_last_lng", String.valueOf(point.longitude))
                .apply();
    }

    //冷启动恢复:有存值则选中上次模拟位置并移动视野(街道级,与"返回当前位置"一致);无存值保持原默认行为
    public void restoreLastPosition() {
        //默认收藏优先于"上次位置":已设默认收藏则冷启动直接就绪默认地址(一键 GO 即模拟默认点)
        if (selectDefaultFavorite()) {
            mMapController.getAMap().animateCamera(CameraUpdateFactory.newLatLngZoom(mMapState.getSelectedPoint(), 18.0f));
            return;
        }

        String lat = sharedPreferences.getString("setting_last_lat", "");
        String lng = sharedPreferences.getString("setting_last_lng", "");
        if (lat.isEmpty() || lng.isEmpty()) {
            return;
        }

        try {
            LatLng lastPoint = new LatLng(Double.parseDouble(lat), Double.parseDouble(lng));
            mMainViewModel.selectPoint(lastPoint);
            mMapController.markMap();

            mMapController.getAMap().animateCamera(CameraUpdateFactory.newLatLngZoom(lastPoint, 18.0f));
        } catch (NumberFormatException e) {
            //存值损坏时按无存值处理,不阻断启动
            XLog.e("MainActivity: last position invalid, skip restore");
        }
    }

    /*============================== 历史记录 相关 ==============================*/

    // 记录请求的位置信息(逆地理收编于 GeocoderHelper SDK 通道,落库行构造保留在此)
    // reportResult:仅信息窗"保存"按钮需要成/败提示;"传送"路径的落库是附带动作,失败只记日志,
    // 不打断"已传送到新位置"主提示
    public void recordCurrentLocation(double lng, double lat, boolean reportResult) {
        //参数坐标系:gcj02
        final double[] latLng = MapUtils.gcj02towgs84(lng, lat);

        mGeocoderHelper.resolveAddress(lng, lat, new GeocoderHelper.AddressCallback() {
            @Override
            public void onAddressResolved(int rCode, String formatAddress) {
                //地址提取三分支收敛于纯函数 extractAddress(失败回退标记名,语义与原 REST 版一致)
                String address = GeocoderHelper.extractAddress(rCode, formatAddress, mMapState.getMarkName(),
                        mActivity.getResources().getString(R.string.history_location_default_name));
                //镜像逆地理结果(行为冻结:落库路径不变,仅补状态镜像)
                mMainViewModel.postCurrentAddress(address);
                addLocationHistoryRow(address, latLng, lng, lat, reportResult);
            }
        });
    }

    // 插表参数：LocationRow 字段与原 ContentValues puts 逐字段对应（WGS84 经纬度、秒级时间戳、GCJ02 原坐标）
    private void addLocationHistoryRow(String location, double[] wgsLatLng, double lng, double lat, boolean reportResult) {
        LocationRow locationRow = new LocationRow(location, String.valueOf(wgsLatLng[0]), String.valueOf(wgsLatLng[1]),
                System.currentTimeMillis() / 1000, Double.toString(lng), Double.toString(lat));

        if (!mHistoryRepository.addLocationRow(locationRow)) {
            XLog.e("DATABASE: 定位历史落库失败");
            if (reportResult) {
                GoUtils.DisplayToast(mActivity, mActivity.getResources().getString(R.string.app_location_save_failed));
            }
            return;
        }

        if (reportResult) {
            GoUtils.DisplayToast(mActivity, mActivity.getResources().getString(R.string.app_location_save));
        }
    }

    /*============================== 收藏 相关 ==============================*/

    // 收藏当前选中点(与"保存"同构：GCJ02 入参，逆地理取地址后落收藏表；WGS84 换算只经 MapUtils)
    public void favoriteCurrentLocation(double lng, double lat) {
        //参数坐标系:gcj02
        final double[] latLng = MapUtils.gcj02towgs84(lng, lat);

        mGeocoderHelper.resolveAddress(lng, lat, new GeocoderHelper.AddressCallback() {
            @Override
            public void onAddressResolved(int rCode, String formatAddress) {
                //地址提取收敛于纯函数 extractAddress(失败回退标记名,与"保存"路径一致)
                String address = GeocoderHelper.extractAddress(rCode, formatAddress, mMapState.getMarkName(),
                        mActivity.getResources().getString(R.string.history_location_default_name));
                addFavoriteRow(address, latLng, lng, lat);
            }
        });
    }

    // 插表参数与 addLocationHistoryRow 逐字段对应(WGS84 经纬度、秒级时间戳、GCJ02 原坐标)
    private void addFavoriteRow(String location, double[] wgsLatLng, double lng, double lat) {
        LocationRow favoriteRow = new LocationRow(location, String.valueOf(wgsLatLng[0]), String.valueOf(wgsLatLng[1]),
                System.currentTimeMillis() / 1000, Double.toString(lng), Double.toString(lat));

        if (!mHistoryRepository.addFavoriteRow(favoriteRow)) {
            XLog.e("DATABASE: 收藏落库失败");
            GoUtils.DisplayToast(mActivity, mActivity.getResources().getString(R.string.app_location_mark_failed));
            return;
        }

        GoUtils.DisplayToast(mActivity, mActivity.getResources().getString(R.string.app_location_mark));
    }

    // 选中默认收藏点(GCJ02 入图)：已设默认收藏则选中上标记并返回 true，否则不动状态返回 false。
    // 不套随机偏移——默认地址是确定性目标，与手选点 GO 同待遇
    public boolean selectDefaultFavorite() {
        LocationRow def = resolveDefaultFavorite();
        if (def == null) {
            return false;
        }
        // 坐标串来自收藏表,可能由旧版本写入或被外部改坏:解析不出即按"无默认收藏"处理,
        // 而不是让冷启动恢复与未选点按 GO 两条路径崩在 NumberFormatException 上
        double[] gcjLngLat = GoUtils.parseStoredCoordinatePair(def.lngCustom, def.latCustom);
        if (gcjLngLat == null) {
            XLog.e("MainActivity: 默认收藏坐标不可解析, 按无默认收藏处理");
            return false;
        }
        mMainViewModel.selectPoint(new LatLng(gcjLngLat[1], gcjLngLat[0]));
        mMapState.setMarkName(def.location);
        mMapController.markMap();
        return true;
    }

    // 默认收藏以 WGS84 坐标字符串为身份存于偏好(与收藏表先删后插的去重键同构，改名不失效)；
    // 对应收藏行被删后查不到，即视为无默认
    private LocationRow resolveDefaultFavorite() {
        String lng = sharedPreferences.getString("setting_default_favorite_lng_wgs", "");
        String lat = sharedPreferences.getString("setting_default_favorite_lat_wgs", "");
        if (lng.isEmpty() || lat.isEmpty()) {
            return null;
        }
        return mHistoryRepository.findFavoriteRowByWgsCoords(lng, lat);
    }
}
