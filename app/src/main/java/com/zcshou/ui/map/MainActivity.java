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

import com.baidu.location.BDLocation;
import com.baidu.mapapi.SDKInitializer;
import com.baidu.mapapi.map.BaiduMap;
import com.baidu.mapapi.map.InfoWindow;
import com.baidu.mapapi.map.MapPoi;
import com.baidu.mapapi.map.MapStatus;
import com.baidu.mapapi.map.MapStatusUpdate;
import com.baidu.mapapi.map.MapStatusUpdateFactory;
import com.baidu.mapapi.map.MapView;
import com.baidu.mapapi.map.MyLocationConfiguration;
import com.baidu.mapapi.map.MyLocationData;
import com.baidu.mapapi.model.LatLng;
import com.baidu.mapapi.search.core.SearchResult;
import com.baidu.mapapi.search.geocode.GeoCodeResult;
import com.baidu.mapapi.search.geocode.ReverseGeoCodeResult;
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
import com.zcshou.gogogo.BaseActivity;
import com.zcshou.gogogo.BuildConfig;
import com.zcshou.gogogo.GoApplication;
import com.zcshou.gogogo.R;
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
    /* 对外（POI_* 键名为搜索结果 SimpleAdapter 列契约，随 getMapList 收编于 PoiSearchHelper，JoyStick 引用不受影响） */
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
    private double mCurrentLat = 0.0;       // 当前位置的百度纬度
    private double mCurrentLon = 0.0;       // 当前位置的百度经度
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
    //建议搜索管线：SuggestionSearch 与搜索历史读写收编于 PoiSearchHelper，渲染回调体保留在本类
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

        //MapState 获取自 initMap 提前至此：主界面状态持有器构造注入同一 AppContainer 单例
        mMapState = ((GoApplication) getApplicationContext()).getAppContainer().getMapState();
        mMainViewModel = new ViewModelProvider(this, new MainViewModel.Factory(mMapState)).get(MainViewModel.class);

        initNavigationView();

        initMap();

        //定位数据源：LocationClient 构造/配置/启停收编于 CurrentLocationSource，回调体保留在本类（原样）
        mCurrentLocationSource = new CurrentLocationSource(this, this);
        mCurrentLocationSource.init();

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
        mMapController.getMapView().onPause();
        mSensorManager.unregisterListener(this);
        super.onPause();
    }

    @Override
    protected void onResume() {
        XLog.i("MainActivity: onResume");
        mMapController.getMapView().onResume();
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
        mMapController.getBaiduMap().setMyLocationEnabled(false);
        mMapController.getMapView().onDestroy();

        //poi search destroy：SuggestionSearch 销毁收编于 PoiSearchHelper
        mPoiSearchHelper.destroy();

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
                    mMapController.getBaiduMap().clear();
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
    private void initMap() {
        // 地图初始化（MapView/BaiduMap 的配置与点击监听注册收编于 MapController）
        MapView mapView = findViewById(R.id.bdMapView);
        mapView.showZoomControls(false);
        mMapController = new MapController(mapView, mapView.getMap(), mMapState, this);
        // showLocation 静态门面委托到当前控制器
        sMapController = mMapController;
        mMapController.init();

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
        // SDK GeoCoder 构造与监听注册收编于 GeocoderHelper，回调体保留在此（渲染与状态写入逻辑原样）
        mGeocoderHelper = new GeocoderHelper();
        mGeocoderHelper.initSdk(new GeocoderHelper.SdkGeoCoderListener() {
            @Override
            public void onGetGeoCodeResult(GeoCodeResult geoCodeResult) {
                XLog.i(geoCodeResult.getLocation());
            }

            @Override
            public void onGetReverseGeoCodeResult(ReverseGeoCodeResult reverseGeoCodeResult) {
                if (reverseGeoCodeResult == null || reverseGeoCodeResult.error != SearchResult.ERRORNO.NO_ERROR) {
                    XLog.i("逆地理位置失败!");
                } else {
                    mMapState.setMarkName(String.valueOf(reverseGeoCodeResult.getAddress()));
                    //镜像 GeocoderHelper SDK 逆地理结果（行为冻结：渲染路径不变，仅补状态镜像）
                    mMainViewModel.postCurrentAddress(String.valueOf(reverseGeoCodeResult.getAddress()));
                    poiLatitude.setText(String.valueOf(reverseGeoCodeResult.getLocation().latitude));
                    poiLongitude.setText(String.valueOf(reverseGeoCodeResult.getLocation().longitude));
                    poiAddress.setText(reverseGeoCodeResult.getAddress());
                    final InfoWindow mInfoWindow = new InfoWindow(poiView, reverseGeoCodeResult.getLocation(), -100);
                    mMapController.getBaiduMap().showInfoWindow(mInfoWindow);
                }
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
    public void onPoiClick(MapPoi poi) {
        mMainViewModel.selectPoint(poi.getPosition());
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
     * 双击地图
     */
    @Override
    public void onMapDoubleClick(double lng, double lat) {
        mMapController.getBaiduMap().animateMapStatus(MapStatusUpdateFactory.zoomIn());
    }

    /**
     * 定位回调（回调体原样保留）
     */
    @Override
    public void onReceiveLocation(BDLocation bdLocation) {
        if (bdLocation == null || mMapController == null || mMapController.getMapView() == null) {// mapview 销毁后不在处理新接收的位置
            return;
        }

        mMapState.setCurrentCity(bdLocation.getCity());
        mCurrentLat = bdLocation.getLatitude();
        mCurrentLon = bdLocation.getLongitude();
        MyLocationData locData = new MyLocationData.Builder()
                .accuracy(bdLocation.getRadius())
                .direction(mCurrentDirection)// 此处设置开发者获取到的方向信息，顺时针0-360
                .latitude(bdLocation.getLatitude())
                .longitude(bdLocation.getLongitude()).build();
        mMapController.getBaiduMap().setMyLocationData(locData);
        MyLocationConfiguration configuration = new MyLocationConfiguration(MyLocationConfiguration.LocationMode.NORMAL, true, null);
        mMapController.getBaiduMap().setMyLocationConfiguration(configuration);

        /* 如果出现错误，则需要重新请求位置 */
        int err = bdLocation.getLocType();
        if (err == BDLocation.TypeCriteriaException || err == BDLocation.TypeNetWorkException) {
            mCurrentLocationSource.requestLocation();   /* 请求位置 */
        } else {
            if (isFirstLoc) {
                isFirstLoc = false;
                // 这里记录百度地图返回的位置
                mMainViewModel.selectPoint(new LatLng(bdLocation.getLatitude(), bdLocation.getLongitude()));
                MapStatus.Builder builder = new MapStatus.Builder();
                builder.target(mMapState.getSelectedPoint()).zoom(18.0f);
                mMapController.getBaiduMap().animateMapStatus(MapStatusUpdateFactory.newMapStatus(builder.build()));

                XLog.i("First Baidu LatLng: " + mMapState.getSelectedPoint());
            }
        }
    }

    /**
     * 定位诊断信息回调（错误状态码说明见 CurrentLocationSource.LocationListener）
     */
    @Override
    public void onDiagnostic(int locType, int diagnosticType, String diagnosticMessage) {
        XLog.i("Baidu ERROR: " + locType + "-" + diagnosticType + "-" + diagnosticMessage);
    }

    //地图上各按键的监听
    private void initMapButton() {
        RadioGroup mGroupMapType = this.findViewById(R.id.RadioGroupMapType);
        mGroupMapType.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.mapNormal) {
                mMapController.getBaiduMap().setMapType(BaiduMap.MAP_TYPE_NORMAL);
            }

            if (checkedId == R.id.mapSatellite) {
                mMapController.getBaiduMap().setMapType(BaiduMap.MAP_TYPE_SATELLITE);
            }
        });

        ImageButton curPosBtn = this.findViewById(R.id.cur_position);
        curPosBtn.setOnClickListener(v -> mMapController.resetMap(mCurrentLat, mCurrentLon, mCurrentDirection));

        ImageButton zoomInBtn = this.findViewById(R.id.zoom_in);
        zoomInBtn.setOnClickListener(v -> mMapController.getBaiduMap().animateMapStatus(MapStatusUpdateFactory.zoomIn()));

        ImageButton zoomOutBtn = this.findViewById(R.id.zoom_out);
        zoomOutBtn.setOnClickListener(v -> mMapController.getBaiduMap().animateMapStatus(MapStatusUpdateFactory.zoomOut()));

        ImageButton inputPosBtn = this.findViewById(R.id.input_pos);
        inputPosBtn.setOnClickListener(v -> {
            AlertDialog dialog;
            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
            builder.setTitle("请输入经度和纬度");
            View view = LayoutInflater.from(MainActivity.this).inflate(R.layout.location_input, null);
            builder.setView(view);
            dialog = builder.show();

            EditText dialog_lng = view.findViewById(R.id.joystick_longitude);
            EditText dialog_lat = view.findViewById(R.id.joystick_latitude);
            RadioButton rbBD = view.findViewById(R.id.pos_type_bd);

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
                            if (rbBD.isChecked()) {
                                mMainViewModel.selectPoint(new LatLng(dialog_lat_double, dialog_lng_double));
                            } else {
                                double[] bdLonLat = MapUtils.wgs2bd09(dialog_lat_double, dialog_lng_double);
                                mMainViewModel.selectPoint(new LatLng(bdLonLat[1], bdLonLat[0]));
                            }
                            mMapState.setMarkName("手动输入的坐标");

                            mMapController.markMap();

                            MapStatusUpdate mapstatusupdate = MapStatusUpdateFactory.newLatLng(mMapState.getSelectedPoint());
                            mMapController.getBaiduMap().setMapStatus(mapstatusupdate);

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
            // 与原实现中 mBaiduMap 为 null 时 NPE 进 catch 返回 false 的语义一致
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

        if (!Settings.canDrawOverlays(getApplicationContext())) {//悬浮窗权限判断
            GoUtils.showEnableFloatWindowDialog(this);
            XLog.e("无悬浮窗权限!");
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
                double[] latLng = MapUtils.bd2wgs(mMapState.getSelectedPoint().longitude, mMapState.getSelectedPoint().latitude);
                double alt = Double.parseDouble(sharedPreferences.getString("setting_altitude", "55.0"));
                mMockController.setPosition(latLng[0], latLng[1], alt);
                Snackbar.make(v, "已传送到新位置", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();

                recordCurrentLocation(mMapState.getSelectedPoint().longitude, mMapState.getSelectedPoint().latitude);

                mMapController.getBaiduMap().clear();
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
                    double[] latLng = MapUtils.bd2wgs(mMapState.getSelectedPoint().longitude, mMapState.getSelectedPoint().latitude);
                    double alt = Double.parseDouble(sharedPreferences.getString("setting_altitude", "55.0"));
                    mMockController.start(latLng[0], latLng[1], alt);
                    //镜像模拟运行状态（MockLocationController 无回调，由调用点通知）
                    mMainViewModel.setMockRunning(true);
                    mButtonStart.setImageResource(R.drawable.ic_fly);
                    Snackbar.make(v, "模拟位置已启动", Snackbar.LENGTH_LONG)
                            .setAction("Action", null).show();

                    recordCurrentLocation(mMapState.getSelectedPoint().longitude, mMapState.getSelectedPoint().latitude);
                    mMapController.getBaiduMap().clear();
                    mMainViewModel.clearSelectedPoint();

                    if (GoUtils.isWifiEnabled(MainActivity.this)) {
                        GoUtils.showDisableWifiDialog(MainActivity.this);
                    }
                }
            }
        }
    }

    /*============================== 历史记录 相关 ==============================*/

    // 记录请求的位置信息（REST 逆地理 HTTP 与解析收编于 GeocoderHelper，落库行构造保留在此）
    private void recordCurrentLocation(double lng, double lat) {
        //参数坐标系：bd09
        final String safeCode = BuildConfig.MAPS_SAFE_CODE;
        final String ak = sharedPreferences.getString("setting_map_key", BuildConfig.MAPS_API_KEY);
        final double[] latLng = MapUtils.bd2wgs(lng, lat);

        mGeocoderHelper.resolveAddress(lng, lat, ak, safeCode, new GeocoderHelper.AddressCallback() {
            @Override
            public void onHttpFailure() {
                //http 请求失败：与原 onFailure 一致，以标记名落库（回调线程模型不变，不切 UI 线程）
                addLocationHistoryRow(mMapState.getMarkName(), latLng, lng, lat);
            }

            @Override
            public void onResponseBody(String resp) {
                // 响应解析三分支收敛于纯函数 parseAddress；回退默认名与原实现一致取自资源
                String address = GeocoderHelper.parseAddress(resp, mMapState.getMarkName(),
                        getResources().getString(R.string.history_location_default_name),
                        getResources().getString(R.string.history_location_default_name));
                //镜像 GeocoderHelper REST 逆地理结果（行为冻结：落库路径不变，仅补状态镜像）
                mMainViewModel.postCurrentAddress(address);
                addLocationHistoryRow(address, latLng, lng, lat);
            }
        });
    }

    // 插表参数：LocationRow 字段与原 ContentValues puts 逐字段对应（WGS84 经纬度、秒级时间戳、bd09 原坐标）
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
            MapStatusUpdate mapstatusupdate = MapStatusUpdateFactory.newLatLng(mMapState.getSelectedPoint());
            mMapController.getBaiduMap().setMapStatus(mapstatusupdate);

            mMapController.markMap();

            // mSearchList.setVisibility(View.GONE);
            //搜索历史 插表参数：POI 点选保存收编于 PoiSearchHelper（bd2wgs 换算 + SearchRow 构造 + 落库）
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
                MapStatusUpdate mapstatusupdate = MapStatusUpdateFactory.newLatLng(mMapState.getSelectedPoint());
                mMapController.getBaiduMap().setMapStatus(mapstatusupdate);

                mMapController.markMap();

                //设置列表不可见
                mHistoryLayout.setVisibility(View.INVISIBLE);
                searchItem.collapseActionView();
                //更新表：搜索历史定位点选保存收编于 PoiSearchHelper（bd2wgs 换算 + SearchRow 构造 + 落库）
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
        //设置搜索建议返回值监听：SuggestionSearch 构造与监听注册收编于 PoiSearchHelper（建议搜索管线），
        //无结果提示与列表渲染回调体保留在本类（原样）
        mPoiSearchHelper = new PoiSearchHelper(mHistoryRepository, new PoiSearchHelper.SuggestionListener() {
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
        mPoiSearchHelper.init();
    }

}
