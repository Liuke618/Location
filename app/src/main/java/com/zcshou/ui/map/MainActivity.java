package com.zcshou.ui.map;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SimpleAdapter;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;

import com.amap.api.location.AMapLocation;
import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.Poi;
import com.amap.api.services.geocoder.RegeocodeResult;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.util.List;
import java.util.Map;

import com.zcshou.service.MockLocationController;
import com.zcshou.service.ServiceGoController;
import com.zcshou.data.HistoryRepository;
import com.zcshou.data.HistoryRepository.LocationRow;
import com.yybb.location.BaseActivity;
import com.yybb.location.GoApplication;
import com.yybb.location.R;
import com.zcshou.ui.location.CurrentLocationSource;
import com.zcshou.ui.map.GeocoderHelper;
import com.zcshou.ui.map.MapController;
import com.zcshou.ui.map.MapState;
import com.zcshou.ui.search.PoiSearchHelper;
import com.zcshou.ui.update.UpdateManager;
import com.zcshou.ui.history.HistoryActivity;
import com.zcshou.ui.settings.SettingsActivity;
import com.zcshou.utils.ShareUtils;
import com.zcshou.utils.GoUtils;
import com.zcshou.utils.MapUtils;

import com.elvishew.xlog.XLog;

public class MainActivity extends BaseActivity implements SensorEventListener, MapController.MapEventListener, CurrentLocationSource.LocationListener {
    /* 对外（POI_* 键名为搜索结果 SimpleAdapter 列契约，随 getTipList 收编于 PoiSearchHelper） */
    public static final String POI_NAME = PoiSearchHelper.POI_NAME;
    public static final String POI_ADDRESS = PoiSearchHelper.POI_ADDRESS;
    public static final String POI_LONGITUDE = PoiSearchHelper.POI_LONGITUDE;
    public static final String POI_LATITUDE = PoiSearchHelper.POI_LATITUDE;

    private SharedPreferences sharedPreferences;
    //主界面状态持有器（Factory 构造注入 AppContainer 单例 MapState）：仅状态镜像，渲染路径保持直接调用不变
    private MainViewModel mMainViewModel;

    /*============================== 主界面地图 相关 ==============================*/
    /************** 地图 *****************/
    private MapState mMapState;
    private MapController mMapController;
    // showLocation 静态门面委托的当前控制器（onCreate 中赋值，onDestroy 中清空）
    private static MapController sMapController = null;
    private GeocoderHelper mGeocoderHelper;
    private SensorManager mSensorManager;
    private Sensor mSensorAccelerometer;
    private Sensor mSensorMagnetic;
    private float[] mAccValues = new float[3];//加速度传感器数据
    private float[] mMagValues = new float[3];//地磁传感器数据
    private final float[] mR = new float[9];//旋转矩阵，用来保存磁场和加速度的数据
    private final float[] mDirectionValues = new float[3];//模拟方向传感器的数据（原始数据为弧度）
    /************** 定位 *****************/
    private CurrentLocationSource mCurrentLocationSource;
    private double mCurrentLat = 0.0;       // 当前位置的 GCJ02 纬度
    private double mCurrentLon = 0.0;       // 当前位置的 GCJ02 经度
    private float mCurrentDirection = 0.0f;
    private boolean isFirstLoc = true; // 是否首次定位
    private MockLocationController mMockController;
    private FloatingActionButton mButtonStart;
    /*============================== 历史记录 相关 ==============================*/
    private HistoryRepository mHistoryRepository;
    /*============================== SearchView 相关 ==============================*/
    private SearchView searchView;
    private ListView mSearchList;
    private LinearLayout mSearchLayout;
    private ListView mSearchHistoryList;
    private LinearLayout mHistoryLayout;
    private MenuItem searchItem;
    //建议搜索管线：Inputtips 与搜索历史读写收编于 PoiSearchHelper，渲染回调体保留在本类
    private PoiSearchHelper mPoiSearchHelper;
    /*============================== 更新 相关 ==============================*/
    //更新检测/下载/安装管线收编于 UpdateManager（对话框组装随流程迁入，见该类）
    private UpdateManager mUpdateManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // predictive back(targetSdk 36 默认启用):迁移到 OnBackPressedDispatcher,保持"返回即退后台"行为
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                moveTaskToBack(false);
            }
        });

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawer, toolbar, R.string.nav_drawer_open, R.string.nav_drawer_close);
        drawer.addDrawerListener(toggle);
        toggle.syncState();

        XLog.i("MainActivity: onCreate");

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        //坐标系迁移:V1 的"上次位置"存的是 BD09,一次性转为 GCJ02(标记防重入)
        if (!sharedPreferences.getBoolean("setting_coord_migrated", false)) {
            String lastLat = sharedPreferences.getString("setting_last_lat", "");
            String lastLng = sharedPreferences.getString("setting_last_lng", "");
            if (!lastLat.isEmpty() && !lastLng.isEmpty()) {
                try {
                    double[] gcj = MapUtils.bd09togcj02(Double.parseDouble(lastLng), Double.parseDouble(lastLat));
                    sharedPreferences.edit()
                            .putString("setting_last_lng", String.valueOf(gcj[0]))
                            .putString("setting_last_lat", String.valueOf(gcj[1]))
                            .putBoolean("setting_coord_migrated", true)
                            .apply();
                } catch (NumberFormatException e) {
                    //存值损坏:放弃迁移但置位标记,按无存值处理
                    sharedPreferences.edit().putBoolean("setting_coord_migrated", true).apply();
                }
            } else {
                sharedPreferences.edit().putBoolean("setting_coord_migrated", true).apply();
            }
        }

        //MapState 获取自 initMap 提前至此：主界面状态持有器构造注入同一 AppContainer 单例
        mMapState = ((GoApplication) getApplicationContext()).getAppContainer().getMapState();
        mMainViewModel = new ViewModelProvider(this, new MainViewModel.Factory(mMapState)).get(MainViewModel.class);

        initNavigationView();

        //定位数据源提前创建:地图初始化即接线 LocationSource(蓝点桥接需在启用定位图层前就位)
        mCurrentLocationSource = new CurrentLocationSource(this, this);

        initMap(savedInstanceState);

        //启动恢复:仅冷启动执行,把上次模拟位置直接就绪(上标记+移视野,选中点可一键 GO)
        if (savedInstanceState == null) {
            restoreLastPosition();
        }

        initMapButton();

        initGoBtn();

        //模拟位置控制器：随 Activity 创建，随 Activity 一起废弃（生命周期约定见 ServiceGoController）
        mMockController = new ServiceGoController(this);

        mHistoryRepository = ((GoApplication) getApplicationContext()).getAppContainer().getHistoryRepository();

        initSearchView();

        //更新管线（检测/下载/安装）收编于 UpdateManager，随 Activity 创建并注册下载完成接收器
        mUpdateManager = new UpdateManager();
        mUpdateManager.init(this);

        mUpdateManager.checkUpdate(false);
    }

    @Override
    protected void onPause() {
        XLog.i("MainActivity: onPause");
        mMapController.onPause();
        mSensorManager.unregisterListener(this);
        super.onPause();
    }

    @Override
    protected void onResume() {
        XLog.i("MainActivity: onResume");
        mMapController.onResume();
        mSensorManager.registerListener(this, mSensorAccelerometer, SensorManager.SENSOR_DELAY_UI);
        mSensorManager.registerListener(this, mSensorMagnetic, SensorManager.SENSOR_DELAY_UI);
        super.onResume();
    }

    @Override
    protected void onStop() {
        XLog.i("MainActivity: onStop");
        //取消注册传感器监听
        mSensorManager.unregisterListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        XLog.i("MainActivity: onDestroy");

        if (mMockController.isRunning()) {
            mMockController.stop(); // 解绑服务并停止前台服务，服务要记得解绑，不要造成内存泄漏
        }
        //下载完成接收器注销收编于 UpdateManager（与 init 注册成对）
        mUpdateManager.destroy();

        mSensorManager.unregisterListener(this);

        // showLocation 静态门面不再委托本界面的控制器
        sMapController = null;

        // 退出时销毁定位
        mCurrentLocationSource.stop();
        // 关闭定位图层
        mMapController.getAMap().setMyLocationEnabled(false);
        mMapController.onDestroy();

        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        //找到searchView
        searchItem = menu.findItem(R.id.action_search);
        searchItem.setOnActionExpandListener(new  MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                mSearchLayout.setVisibility(View.INVISIBLE);
                mHistoryLayout.setVisibility(View.INVISIBLE);
                return true;  // Return true to collapse action view
            }
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                mSearchLayout.setVisibility(View.INVISIBLE);
                //展示搜索历史（历史行读取收编于 PoiSearchHelper，SimpleAdapter 渲染属 UI 保留在此；
                //渲染块与长按删除后重取的渲染逐字一致，收编为共用方法 displaySearchHistory）
                displaySearchHistory();

                return true;  // Return true to expand action view
            }
        });

        searchView = (SearchView) searchItem.getActionView();
        searchView.setIconified(false);// 设置searchView处于展开状态
        searchView.onActionViewExpanded();// 当展开无输入内容的时候，没有关闭的图标
        searchView.setIconifiedByDefault(true);//默认为true在框内，设置false则在框外
        searchView.setSubmitButtonEnabled(false);//显示提交按钮
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                try {
                    //查询下发与关键字保存收编于 PoiSearchHelper（try/catch 与 UI 处理保留在此，异常语义不变）
                    mPoiSearchHelper.requestSuggestions(query, mMapState.getCurrentCity());
                    mPoiSearchHelper.saveQueryHistory(query);
                    mMapController.clearOverlays();
                    mSearchLayout.setVisibility(View.INVISIBLE);
                } catch (Exception e) {
                    GoUtils.DisplayToast(MainActivity.this, getResources().getString(R.string.app_error_search));
                    XLog.d(getResources().getString(R.string.app_error_search));
                }

                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                //当输入框内容改变的时候回调
                //搜索历史置为不可见
                mHistoryLayout.setVisibility(View.INVISIBLE);

                if (newText != null && !newText.isEmpty()) {
                    try {
                        //查询下发收编于 PoiSearchHelper
                        mPoiSearchHelper.requestSuggestions(newText, mMapState.getCurrentCity());
                    } catch (Exception e) {
                        GoUtils.DisplayToast(MainActivity.this, getResources().getString(R.string.app_error_search));
                        XLog.d(getResources().getString(R.string.app_error_search));
                    }
                }

                return true;
            }
        });

        // 搜索框的清除按钮(该按钮属于安卓系统图标)
        ImageView closeButton = searchView.findViewById(androidx.appcompat.R.id.search_close_btn);
        closeButton.setOnClickListener(v -> {
            EditText et = findViewById(androidx.appcompat.R.id.search_src_text);
            et.setText("");
            searchView.setQuery("", false);
            mSearchLayout.setVisibility(View.INVISIBLE);
            mHistoryLayout.setVisibility(View.VISIBLE);
        });

        return true;
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        if(sensorEvent.sensor.getType() == Sensor.TYPE_ACCELEROMETER){
            mAccValues = sensorEvent.values;
        }
        else if(sensorEvent.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD){
            mMagValues = sensorEvent.values;
        }

        SensorManager.getRotationMatrix(mR, null, mAccValues, mMagValues);
        SensorManager.getOrientation(mR, mDirectionValues);
        mCurrentDirection = (float) Math.toDegrees(mDirectionValues[0]);    // 弧度转角度
        if (mCurrentDirection < 0) {    // 由 -180 ~ + 180 转为 0 ~ 360
            mCurrentDirection += 360;
        }

        //罗盘方向注入定位源,蓝点随设备朝向旋转
        if (mCurrentLocationSource != null) {
            mCurrentLocationSource.setCompassDirection(mCurrentDirection);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    /*============================== NavigationView 相关 ==============================*/
    private void initNavigationView() {
        /*============================== NavigationView 相关 ==============================*/
        NavigationView mNavigationView = findViewById(R.id.nav_view);
        mNavigationView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == R.id.nav_history) {
                Intent intent = new Intent(MainActivity.this, HistoryActivity.class);

                startActivity(intent);
            } else if (id == R.id.nav_settings) {
                Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
                startActivity(intent);
            } else if (id == R.id.nav_dev) {
                if (!GoUtils.isDeveloperOptionsEnabled(this)) {
                    GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_dev));
                } else {
                    try {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                        startActivity(intent);
                    } catch (Exception e) {
                        GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_dev));
                    }
                }
            } else if (id == R.id.nav_update) {
                mUpdateManager.checkUpdate(true);
            } else if (id == R.id.nav_feedback) {
                File file = new File(getExternalFilesDir("Logs"), GoApplication.LOG_FILE_NAME);
                ShareUtils.shareFile(this, file, item.getTitle().toString());
            } else if (id == R.id.nav_contact) {
                Uri uri = Uri.parse("https://gitee.com/itexp/gogogo/issues");
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                startActivity(intent);
            }

            DrawerLayout drawer = findViewById(R.id.drawer_layout);
            drawer.closeDrawer(GravityCompat.START);

            return true;
        });

        // 直接获取第 0 个头部视图
        View headerView = mNavigationView.getHeaderView(0);
        TextView app_version = headerView.findViewById(R.id.app_version);
        app_version.setText(GoUtils.getVersionName(this));
    }

    /*============================== 主界面地图 相关 ==============================*/
    private void initMap(Bundle savedInstanceState) {
        // 地图初始化(MapView/AMap 的配置与点击监听注册收编于 MapController)
        MapView mapView = findViewById(R.id.amapView);
        mMapController = new MapController(mapView, mapView.getMap(), mMapState, this);
        // showLocation 静态门面委托到当前控制器
        sMapController = mMapController;
        // AMap MapView 生命周期必须显式回调(百度无此要求)
        mMapController.onCreate(savedInstanceState);
        mMapController.init(mCurrentLocationSource);

        View poiView = View.inflate(MainActivity.this, R.layout.location_poi_info, null);
        TextView poiAddress = poiView.findViewById(R.id.poi_address);
        TextView poiLongitude = poiView.findViewById(R.id.poi_longitude);
        TextView poiLatitude = poiView.findViewById(R.id.poi_latitude);
        ImageButton ibSave = poiView.findViewById(R.id.poi_save);
        ibSave.setOnClickListener(v -> {
            recordCurrentLocation(mMapState.getSelectedPoint().longitude, mMapState.getSelectedPoint().latitude);
            GoUtils.DisplayToast(this, getResources().getString(R.string.app_location_save));
        });
        ImageButton ibCopy = poiView.findViewById(R.id.poi_copy);
        ibCopy.setOnClickListener(v -> {
            //获取剪贴板管理器：
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            // 创建普通字符型ClipData
            ClipData mClipData = ClipData.newPlainText("Label", mMapState.getSelectedPoint().toString());
            // 将 ClipData内容放到系统剪贴板里。
            cm.setPrimaryClip(mClipData);

            GoUtils.DisplayToast(this,  getResources().getString(R.string.app_location_copy));
        });
        ImageButton ibShare = poiView.findViewById(R.id.poi_share);
        ibShare.setOnClickListener(v -> ShareUtils.shareText(MainActivity.this, "分享位置", poiLongitude.getText()+","+poiLatitude.getText()));
        ImageButton ibFly = poiView.findViewById(R.id.poi_fly);
        ibFly.setOnClickListener(this::doGoLocation);
        // SDK 逆地理构造与监听注册收编于 GeocoderHelper(仅反向通道)
        mGeocoderHelper = new GeocoderHelper();
        mGeocoderHelper.initSdk(this, new GeocoderHelper.SdkGeoCoderListener() {
            @Override
            public void onGetReverseGeoCodeResult(RegeocodeResult reverseGeoCodeResult) {
                if (reverseGeoCodeResult == null || reverseGeoCodeResult.getRegeocodeAddress() == null
                        || reverseGeoCodeResult.getRegeocodeAddress().getFormatAddress() == null) {
                    XLog.i("逆地理位置失败!");
                } else {
                    String formatAddress = reverseGeoCodeResult.getRegeocodeAddress().getFormatAddress();
                    mMapState.setMarkName(formatAddress);
                    //镜像逆地理结果(行为冻结:渲染路径不变,仅补状态镜像)
                    mMainViewModel.postCurrentAddress(formatAddress);
                    //SDK 结果无 getLatLonPoint,以逆地理查询点(即长按点)作为展示坐标,语义同原百度 getLocation()
                    poiLatitude.setText(String.valueOf(reverseGeoCodeResult.getRegeocodeQuery().getPoint().getLatitude()));
                    poiLongitude.setText(String.valueOf(reverseGeoCodeResult.getRegeocodeQuery().getPoint().getLongitude()));
                    poiAddress.setText(formatAddress);
                    //高德信息窗经 InfoWindowAdapter 挂载,窗体复用 location_poi_info 布局
                    mMapController.showInfoWindowOnSelectedMarker();
                }
            }
        });
        //信息窗适配器:任意标记的信息窗整体替换为 POI 信息视图
        mMapController.getAMap().setInfoWindowAdapter(new AMap.InfoWindowAdapter() {
            @Override
            public View getInfoWindow(Marker marker) {
                return poiView;
            }

            @Override
            public View getInfoContents(Marker marker) {
                return null;
            }
        });

        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);// 获取传感器管理服务
        if (mSensorManager != null) {
            mSensorAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (mSensorAccelerometer != null) {
                mSensorManager.registerListener(this, mSensorAccelerometer, SensorManager.SENSOR_DELAY_UI);
            }
            mSensorMagnetic = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (mSensorMagnetic != null) {
                mSensorManager.registerListener(this, mSensorMagnetic, SensorManager.SENSOR_DELAY_UI);
            }
        }
    }

    /*============================== 地图点击回调（原 initMap 监听体逻辑原样移入） ==============================*/
    /**
     * 单击地图
     */
    @Override
    public void onMapClick(double lng, double lat) {
        mMainViewModel.selectPoint(new LatLng(lat, lng));
        mMapController.markMap();
    }

    /**
     * 单击地图中的POI点
     */
    @Override
    public void onPoiClick(Poi poi) {
        mMainViewModel.selectPoint(poi.getCoordinate());
        mMapController.markMap();
    }

    /**
     * 长按地图
     */
    @Override
    public void onMapLongClick(double lng, double lat) {
        mMainViewModel.selectPoint(new LatLng(lat, lng));
        mMapController.markMap();
        mGeocoderHelper.reverseBySdk(new LatLng(lat, lng));
    }

    /**
     * 定位回调(回调体对应物;AMapLocation 为 GCJ02)
     */
    @Override
    public void onReceiveLocation(AMapLocation aMapLocation) {
        if (aMapLocation == null || mMapController == null || mMapController.getMapView() == null) {// mapview 销毁后不在处理新接收的位置
            return;
        }

        /* 出错时只记录诊断并请求重试,不污染当前位置缓存 */
        if (aMapLocation.getErrorCode() != 0) {
            mCurrentLocationSource.requestLocation();
            return;
        }

        mMapState.setCurrentCity(aMapLocation.getCity());
        mCurrentLat = aMapLocation.getLatitude();
        mCurrentLon = aMapLocation.getLongitude();

        if (isFirstLoc) {
            isFirstLoc = false;
            // 这里记录高德地图返回的位置(GCJ02)
            mMainViewModel.selectPoint(new LatLng(aMapLocation.getLatitude(), aMapLocation.getLongitude()));
            mMapController.getAMap().animateCamera(CameraUpdateFactory.newLatLngZoom(mMapState.getSelectedPoint(), 18.0f));

            XLog.i("First AMap LatLng: " + mMapState.getSelectedPoint());
        }
    }

    /**
     * 定位诊断信息回调(错误码说明见 CurrentLocationSource.LocationListener)
     */
    @Override
    public void onDiagnostic(int errorCode, String errorInfo) {
        XLog.i("AMap ERROR: " + errorCode + "-" + errorInfo);
    }

    //地图上各按键的监听
    private void initMapButton() {
        RadioGroup mGroupMapType = this.findViewById(R.id.RadioGroupMapType);
        mGroupMapType.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.mapNormal) {
                mMapController.getAMap().setMapType(AMap.MAP_TYPE_NORMAL);
            }

            if (checkedId == R.id.mapSatellite) {
                mMapController.getAMap().setMapType(AMap.MAP_TYPE_SATELLITE);
            }
        });

        ImageButton curPosBtn = this.findViewById(R.id.cur_position);
        curPosBtn.setOnClickListener(v -> mMapController.resetMap(mCurrentLat, mCurrentLon));

        ImageButton zoomInBtn = this.findViewById(R.id.zoom_in);
        zoomInBtn.setOnClickListener(v -> mMapController.getAMap().animateCamera(CameraUpdateFactory.zoomIn()));

        ImageButton zoomOutBtn = this.findViewById(R.id.zoom_out);
        zoomOutBtn.setOnClickListener(v -> mMapController.getAMap().animateCamera(CameraUpdateFactory.zoomOut()));

        ImageButton inputPosBtn = this.findViewById(R.id.input_pos);
        inputPosBtn.setOnClickListener(v -> {
            AlertDialog dialog;
            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
            builder.setTitle("请输入经度和纬度");
            View view = LayoutInflater.from(MainActivity.this).inflate(R.layout.location_input, null);
            builder.setView(view);
            dialog = builder.show();

            EditText dialog_lng = view.findViewById(R.id.input_longitude);
            EditText dialog_lat = view.findViewById(R.id.input_latitude);
            RadioButton rbGcj = view.findViewById(R.id.pos_type_gcj);

            Button btnGo = view.findViewById(R.id.input_position_ok);
            btnGo.setOnClickListener(v2 -> {
                String dialog_lng_str = dialog_lng.getText().toString();
                String dialog_lat_str = dialog_lat.getText().toString();

                if (TextUtils.isEmpty(dialog_lng_str) || TextUtils.isEmpty(dialog_lat_str)) {
                    GoUtils.DisplayToast(MainActivity.this,getResources().getString(R.string.app_error_input));
                } else {
                    double dialog_lng_double = Double.parseDouble(dialog_lng_str);
                    double dialog_lat_double = Double.parseDouble(dialog_lat_str);

                    if (dialog_lng_double > 180.0 || dialog_lng_double < -180.0) {
                        GoUtils.DisplayToast(MainActivity.this,  getResources().getString(R.string.app_error_longitude));
                    } else {
                        if (dialog_lat_double > 90.0 || dialog_lat_double < -90.0) {
                            GoUtils.DisplayToast(MainActivity.this,  getResources().getString(R.string.app_error_latitude));
                        } else {
                            if (rbGcj.isChecked()) {
                                mMainViewModel.selectPoint(new LatLng(dialog_lat_double, dialog_lng_double));
                            } else {
                                double[] gcjLonLat = MapUtils.wgs2gcj(dialog_lng_double, dialog_lat_double);
                                mMainViewModel.selectPoint(new LatLng(gcjLonLat[1], gcjLonLat[0]));
                            }
                            mMapState.setMarkName("手动输入的坐标");

                            mMapController.markMap();

                            mMapController.getAMap().moveCamera(CameraUpdateFactory.changeLatLng(mMapState.getSelectedPoint()));

                            dialog.dismiss();
                        }
                    }
                }
            });

            Button btnCancel = view.findViewById(R.id.input_position_cancel);
            btnCancel.setOnClickListener(v1 -> dialog.dismiss());
        });
    }

    // 在地图上显示位置（静态门面：委托到当前 MapController，保持原静态调用点不变）
    public static boolean showLocation(String name, String bd09Longitude, String bd09Latitude) {
        if (sMapController == null) {
            // 与原实现中地图对象为 null 时 NPE 进 catch 返回 false 的语义一致
            XLog.e("ERROR: showHistoryLocation");
            return false;
        }

        return sMapController.showLocation(name, bd09Longitude, bd09Latitude);
    }

    private void initGoBtn() {
        mButtonStart = findViewById(R.id.faBtnStart);
        mButtonStart.setOnClickListener(this::doGoLocation);
    }

    private void doGoLocation(View v) {
        if (!GoUtils.isNetworkAvailable(this)) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_network));
            return;
        }

        if (!GoUtils.isGpsOpened(this)) {
            GoUtils.showEnableGpsDialog(this);
            return;
        }

        if (mMockController.isRunning()) {
            if (mMapState.getSelectedPoint() == null) {
                mMockController.stop();
                //镜像模拟运行状态（MockLocationController 无回调，由调用点通知）
                mMainViewModel.setMockRunning(false);
                Snackbar.make(v, "模拟位置已终止", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
                mButtonStart.setImageResource(R.drawable.ic_position);
            } else {
                double[] latLng = MapUtils.gcj02towgs84(mMapState.getSelectedPoint().longitude, mMapState.getSelectedPoint().latitude);
                double alt = Double.parseDouble(sharedPreferences.getString("setting_altitude", "55.0"));
                if (!mMockController.setPosition(latLng[0], latLng[1], alt)) {
                    // 供给循环未建立(服务尚未连上或权限缺失)，此时清标记只会让用户以为已传送
                    GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_mock_service));
                    return;
                }
                Snackbar.make(v, "已传送到新位置", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();

                recordCurrentLocation(mMapState.getSelectedPoint().longitude, mMapState.getSelectedPoint().latitude);
                //记住本次模拟位置(冷启动恢复用),须在选中点清除前落盘
                saveLastPosition();

                mMapController.clearOverlays();
                mMainViewModel.clearSelectedPoint();

                if (GoUtils.isWifiEnabled(MainActivity.this)) {
                    GoUtils.showDisableWifiDialog(MainActivity.this);
                }
            }
        } else {
            if (!GoUtils.isAllowMockLocation(this)) {
                GoUtils.showEnableMockLocationDialog(this);
                XLog.e("无模拟位置权限!");
            } else {
                if (mMapState.getSelectedPoint() == null) {
                    Snackbar.make(v, "请先点击地图位置或者搜索位置", Snackbar.LENGTH_LONG)
                            .setAction("Action", null).show();
                } else {
                    double[] latLng = MapUtils.gcj02towgs84(mMapState.getSelectedPoint().longitude, mMapState.getSelectedPoint().latitude);
                    double alt = Double.parseDouble(sharedPreferences.getString("setting_altitude", "55.0"));
                    if (!mMockController.start(latLng[0], latLng[1], alt)) {
                        // 服务没有真的起来就不改任何界面状态：落历史、清标记、换图标都会构成"已在模拟"的假象
                        XLog.e("模拟位置服务启动失败");
                        GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_mock_service));
                        return;
                    }
                    //镜像模拟运行状态（MockLocationController 无回调，由调用点通知）
                    mMainViewModel.setMockRunning(true);
                    mButtonStart.setImageResource(R.drawable.ic_fly);
                    Snackbar.make(v, "模拟位置已启动", Snackbar.LENGTH_LONG)
                            .setAction("Action", null).show();

                    recordCurrentLocation(mMapState.getSelectedPoint().longitude, mMapState.getSelectedPoint().latitude);
                    //记住本次模拟位置(冷启动恢复用),须在选中点清除前落盘
                    saveLastPosition();
                    mMapController.clearOverlays();
                    mMainViewModel.clearSelectedPoint();

                    if (GoUtils.isWifiEnabled(MainActivity.this)) {
                        GoUtils.showDisableWifiDialog(MainActivity.this);
                    }
                }
            }
        }
    }

    /*============================== 上次位置记忆 ==============================*/

    //落盘当前选中点(GCJ02),作为"上一次定位的位置";GO 两个生效分支调用,须在选中点清除前执行
    private void saveLastPosition() {
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
    private void restoreLastPosition() {
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
    private void recordCurrentLocation(double lng, double lat) {
        //参数坐标系:gcj02
        final double[] latLng = MapUtils.gcj02towgs84(lng, lat);

        mGeocoderHelper.resolveAddress(lng, lat, new GeocoderHelper.AddressCallback() {
            @Override
            public void onAddressResolved(int rCode, String formatAddress) {
                //地址提取三分支收敛于纯函数 extractAddress(失败回退标记名,语义与原 REST 版一致)
                String address = GeocoderHelper.extractAddress(rCode, formatAddress, mMapState.getMarkName(),
                        getResources().getString(R.string.history_location_default_name));
                //镜像逆地理结果(行为冻结:落库路径不变,仅补状态镜像)
                mMainViewModel.postCurrentAddress(address);
                addLocationHistoryRow(address, latLng, lng, lat);
            }
        });
    }

    // 插表参数：LocationRow 字段与原 ContentValues puts 逐字段对应（WGS84 经纬度、秒级时间戳、GCJ02 原坐标）
    private void addLocationHistoryRow(String location, double[] wgsLatLng, double lng, double lat) {
        LocationRow locationRow = new LocationRow(location, String.valueOf(wgsLatLng[0]), String.valueOf(wgsLatLng[1]),
                System.currentTimeMillis() / 1000, Double.toString(lng), Double.toString(lat));

        mHistoryRepository.addLocationRow(locationRow);
    }

    /*============================== SearchView 相关 ==============================*/
    //展示搜索历史（onMenuItemActionExpand 与长按删除后重取两处渲染块逐字一致，原样收编为共用方法）
    private void displaySearchHistory() {
        List<Map<String, Object>> data = mPoiSearchHelper.loadSearchHistoryRows();

        if (!data.isEmpty()) {
            SimpleAdapter simAdapt = new SimpleAdapter(
                    MainActivity.this,
                    data,
                    R.layout.search_item,
                    new String[] {HistoryRepository.DB_COLUMN_KEY,
                            HistoryRepository.DB_COLUMN_DESCRIPTION,
                            HistoryRepository.DB_COLUMN_TIMESTAMP,
                            HistoryRepository.DB_COLUMN_IS_LOCATION,
                            HistoryRepository.DB_COLUMN_LONGITUDE_CUSTOM,
                            HistoryRepository.DB_COLUMN_LATITUDE_CUSTOM},
                    new int[] {R.id.search_key,
                            R.id.search_description,
                            R.id.search_timestamp,
                            R.id.search_isLoc,
                            R.id.search_longitude,
                            R.id.search_latitude});
            mSearchHistoryList.setAdapter(simAdapt);
            mHistoryLayout.setVisibility(View.VISIBLE);
        }
    }

    private void initSearchView() {
        mSearchLayout = findViewById(R.id.search_linear);
        mHistoryLayout = findViewById(R.id.search_history_linear);

        mSearchList = findViewById(R.id.search_list_view);
        mSearchList.setOnItemClickListener((parent, view, position, id) -> {
            String lng = ((TextView) view.findViewById(R.id.poi_longitude)).getText().toString();
            String lat = ((TextView) view.findViewById(R.id.poi_latitude)).getText().toString();
            mMapState.setMarkName(((TextView) view.findViewById(R.id.poi_name)).getText().toString());
            mMainViewModel.selectPoint(new LatLng(Double.parseDouble(lat), Double.parseDouble(lng)));
            mMapController.getAMap().moveCamera(CameraUpdateFactory.changeLatLng(mMapState.getSelectedPoint()));

            mMapController.markMap();

            // mSearchList.setVisibility(View.GONE);
            //搜索历史 插表参数：POI 点选保存收编于 PoiSearchHelper（gcj02towgs84 换算 + SearchRow 构造 + 落库）
            mPoiSearchHelper.savePoiSelection(mMapState.getMarkName(),
                    ((TextView) view.findViewById(R.id.poi_address)).getText().toString(),
                    mMapState.getSelectedPoint(), lng, lat);
            mSearchLayout.setVisibility(View.INVISIBLE);
            searchItem.collapseActionView();
        });
        //搜索历史列表的点击监听
        mSearchHistoryList = findViewById(R.id.search_history_list_view);
        mSearchHistoryList.setOnItemClickListener((parent, view, position, id) -> {
            String searchDescription = ((TextView) view.findViewById(R.id.search_description)).getText().toString();
            String searchKey = ((TextView) view.findViewById(R.id.search_key)).getText().toString();
            String searchIsLoc = ((TextView) view.findViewById(R.id.search_isLoc)).getText().toString();

            //如果是定位搜索
            if (searchIsLoc.equals("1")) {
                String lng = ((TextView) view.findViewById(R.id.search_longitude)).getText().toString();
                String lat = ((TextView) view.findViewById(R.id.search_latitude)).getText().toString();
                // mMarkName = ((TextView) view.findViewById(R.id.poi_name)).getText().toString();
                mMainViewModel.selectPoint(new LatLng(Double.parseDouble(lat), Double.parseDouble(lng)));
                mMapController.getAMap().moveCamera(CameraUpdateFactory.changeLatLng(mMapState.getSelectedPoint()));

                mMapController.markMap();

                //设置列表不可见
                mHistoryLayout.setVisibility(View.INVISIBLE);
                searchItem.collapseActionView();
                //更新表：搜索历史定位点选保存收编于 PoiSearchHelper（gcj02towgs84 换算 + SearchRow 构造 + 落库）
                mPoiSearchHelper.savePoiSelection(searchKey, searchDescription,
                        mMapState.getSelectedPoint(), lng, lat);
            } else if (searchIsLoc.equals("0")) { //如果仅仅是搜索
                try {
                    searchView.setQuery(searchKey, true);
                } catch (Exception e) {
                    GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_search));
                    XLog.e(getResources().getString(R.string.app_error_search));
                }
            } else {
                XLog.e(getResources().getString(R.string.app_error_param));
            }
        });
        mSearchHistoryList.setOnItemLongClickListener((parent, view, position, id) -> {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("警告")//这里是表头的内容
                    .setMessage("确定要删除该项搜索记录吗?")//这里是中间显示的具体信息
                    .setPositiveButton("确定",(dialog, which) -> {
                        String searchKey = ((TextView) view.findViewById(R.id.search_key)).getText().toString();

                        try {
                            //删除与历史行重取收编于 PoiSearchHelper，SimpleAdapter 渲染属 UI 保留在此
                            mPoiSearchHelper.deleteSearchHistory(searchKey);
                            //删除成功
                            //展示搜索历史（渲染块与 onMenuItemActionExpand 逐字一致，收编为共用方法）
                            displaySearchHistory();
                        } catch (Exception e) {
                            XLog.e("ERROR: delete database error");
                            GoUtils.DisplayToast(MainActivity.this,getResources().getString(R.string.history_delete_error));
                        }
                    })
                    .setNegativeButton("取消",
                            (dialog, which) -> {
                            })
                    .show();
            return true;
        });
        //设置搜索建议返回值监听：Inputtips 构造与监听注册收编于 PoiSearchHelper（建议搜索管线），
        //无结果提示与列表渲染回调体保留在本类（原样）
        mPoiSearchHelper = new PoiSearchHelper(this, mHistoryRepository, new PoiSearchHelper.SuggestionListener() {
            @Override
            public void onSuggestionUnavailable() {
                GoUtils.DisplayToast(MainActivity.this, getResources().getString(R.string.app_search_null));
            }

            @Override
            public void onSuggestionResult(List<Map<String, Object>> data) {
                //镜像建议结果（行为冻结：渲染保持本回调线程直接渲染路径，时序不变，仅补状态镜像）
                mMainViewModel.postSearchResults(data);
                SimpleAdapter simAdapt = new SimpleAdapter(
                        MainActivity.this,
                        data,
                        R.layout.search_poi_item,
                        new String[] {POI_NAME, POI_ADDRESS, POI_LONGITUDE, POI_LATITUDE}, // 与下面数组元素要一一对应
                        new int[] {R.id.poi_name, R.id.poi_address, R.id.poi_longitude, R.id.poi_latitude});
                mSearchList.setAdapter(simAdapt);
                // mSearchList.setVisibility(View.VISIBLE);
                mSearchLayout.setVisibility(View.VISIBLE);
            }
        });
    }

}
