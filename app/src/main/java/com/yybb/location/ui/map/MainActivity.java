package com.yybb.location.ui.map;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Menu;
import android.view.View;
import android.widget.ImageButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
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
import com.google.android.material.snackbar.Snackbar;

import com.yybb.location.service.MockLocationController;
import com.yybb.location.service.ServiceGoController;
import com.yybb.location.data.HistoryRepository;
import com.yybb.location.BaseActivity;
import com.yybb.location.GoApplication;
import com.yybb.location.R;
import com.yybb.location.ui.location.CurrentLocationSource;
import com.yybb.location.ui.search.PoiSearchHelper;
import com.yybb.location.ui.search.SearchViewController;
import com.yybb.location.ui.update.UpdateManager;
import com.yybb.location.ui.settings.SettingsActivity;
import com.yybb.location.ui.settings.AmapKeyGuide;
import com.yybb.location.utils.AmapUtils;
import com.yybb.location.utils.ShareUtils;
import com.yybb.location.utils.GoUtils;
import com.yybb.location.utils.MapUtils;

import com.elvishew.xlog.XLog;

/**
 * 主界面：地图选点与模拟定位的操作入口——选点/POI 搜索/逆地理经 MapController 与 PoiSearchHelper 完成，
 * 模拟服务经 MockLocationController 启停；本类实现 MapEventListener/LocationListener 接收回调渲染。
 * 功能区收编（原内联体原样迁出，行为冻结）：搜索界面 → SearchViewController，输入坐标对话框 →
 * InputPositionDialog，罗盘传感器 → CompassSensorController，侧边栏菜单 → NavMenuController，
 * 位置记录（上次位置/历史/收藏） → LocationRecordHelper。
 */
public class MainActivity extends BaseActivity implements MapController.MapEventListener, CurrentLocationSource.LocationListener {
    /* 对外（POI_* 键名为搜索结果 SimpleAdapter 列契约，随 getTipList 收编于 PoiSearchHelper） */
    public static final String POI_NAME = PoiSearchHelper.POI_NAME;
    public static final String POI_ADDRESS = PoiSearchHelper.POI_ADDRESS;
    public static final String POI_LONGITUDE = PoiSearchHelper.POI_LONGITUDE;
    public static final String POI_LATITUDE = PoiSearchHelper.POI_LATITUDE;

    //WiFi 警告只提示一次的持久化标记(内部键,不暴露到设置页)
    private static final String PREF_KEY_WIFI_WARNING_SHOWN = "setting_wifi_warning_shown";
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
    //罗盘传感器（注册/注销与方向计算）收编于 CompassSensorController
    private CompassSensorController mCompassSensorController;
    /************** 定位 *****************/
    private CurrentLocationSource mCurrentLocationSource;
    private double mCurrentLat = 0.0;       // 当前位置的 GCJ02 纬度
    private double mCurrentLon = 0.0;       // 当前位置的 GCJ02 经度
    private boolean isFirstLoc = true; // 是否首次定位
    private MockLocationController mMockController;
    private BroadcastReceiver mStopMockReceiver;
    private FloatingActionButton mButtonStart;
    /*============================== 历史记录 相关 ==============================*/
    private HistoryRepository mHistoryRepository;
    //位置记录读写（上次位置记忆/历史落库/收藏与默认收藏）收编于 LocationRecordHelper
    private LocationRecordHelper mLocationRecordHelper;
    /*============================== SearchView 相关 ==============================*/
    //搜索界面（工具栏 SearchView/建议列表/搜索历史面板）收编于 SearchViewController
    private SearchViewController mSearchViewController;
    /*============================== 更新 相关 ==============================*/
    //更新检测/下载/安装管线收编于 UpdateManager（对话框组装随流程迁入，见该类）
    private UpdateManager mUpdateManager;
    //高德 key 缺失标记:onCreate 判定一次,设置页返回(onResume)若已补上则提示重启生效
    private boolean mKeyMissingOnCreate;

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

        //侧边栏导航菜单（条目分发/打赏弹窗）收编于 NavMenuController；"检查更新"经回调转发（点击时才读 mUpdateManager，与原 lambda 一致）
        new NavMenuController(this, () -> mUpdateManager.checkUpdate(true)).init();

        //定位数据源提前创建:地图初始化即接线 LocationSource(蓝点桥接需在启用定位图层前就位)
        mCurrentLocationSource = new CurrentLocationSource(this, this);

        initMap(savedInstanceState);

        //历史仓库提前初始化:冷启动恢复要先查默认收藏(原在 initSearchView 前初始化,时序上晚于恢复路径)
        mHistoryRepository = ((GoApplication) getApplicationContext()).getAppContainer().getHistoryRepository();

        //位置记录读写（上次位置记忆/历史落库/收藏与默认收藏）收编于 LocationRecordHelper
        mLocationRecordHelper = new LocationRecordHelper(this, sharedPreferences, mGeocoderHelper,
                mMapState, mMainViewModel, mHistoryRepository, mMapController);

        //启动恢复:仅冷启动执行,把上次模拟位置直接就绪(上标记+移视野,选中点可一键 GO)
        if (savedInstanceState == null) {
            mLocationRecordHelper.restoreLastPosition();
        }

        initMapButton();

        initGoBtn();

        //模拟位置控制器：随 Activity 创建，随 Activity 一起废弃（生命周期约定见 ServiceGoController）
        mMockController = new ServiceGoController(this);

        //通知栏"停止模拟"动作：经本地广播(setPackage+NOT_EXPORTED)由界面侧停服并复位按钮镜像——
        //服务自身无从更新 UI 状态,且完整停服须由持绑方解绑(注销与 onCreate 注册成对,见 onDestroy)
        mStopMockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (mMockController.isRunning()) {
                    mMockController.stop();
                    //镜像模拟运行状态（MockLocationController 无回调，由调用点通知）
                    mMainViewModel.setMockRunning(false);
                    mButtonStart.setImageResource(R.drawable.ic_position);
                    //地图仅剩"模拟中"标记(无待传送选中点)时一并清掉;有新选中点则保留红点
                    if (mMapState.getSelectedPoint() == null) {
                        mMapController.clearOverlays();
                    }
                }
            }
        };
        ContextCompat.registerReceiver(this, mStopMockReceiver,
                new IntentFilter(ServiceGoController.ACTION_STOP_MOCK), ContextCompat.RECEIVER_NOT_EXPORTED);

        //搜索界面（工具栏 SearchView/建议列表/搜索历史面板）收编于 SearchViewController
        mSearchViewController = new SearchViewController(this, mHistoryRepository, mMapState, mMainViewModel, mMapController);
        mSearchViewController.init();

        //更新管线（检测/下载/安装）收编于 UpdateManager，随 Activity 创建并注册下载完成接收器
        mUpdateManager = new UpdateManager();
        mUpdateManager.init(this);

        //自动检查更新(默认开,设置页「关于」分组可关):关闭时启动不发起检测;
        //侧边栏"检查更新"是用户主动行为,始终可用
        if (sharedPreferences.getBoolean(UpdateManager.PREF_KEY_AUTO_CHECK_UPDATE, true)) {
            mUpdateManager.checkUpdate(false);
        }

        //高德 Key 缺失引导:生效 key(设置自定义→清单内置)为空时地图空白/搜索报错,构建不拦,只能入口处提示
        maybePromptAmapKey();
    }

    @Override
    protected void onPause() {
        XLog.i("MainActivity: onPause");
        mMapController.onPause();
        mCompassSensorController.unregister();
        super.onPause();
    }

    @Override
    protected void onResume() {
        XLog.i("MainActivity: onResume");
        mMapController.onResume();
        applyLocateSelfSetting();
        maybeToastKeyConfigured();
        mCompassSensorController.register();
        super.onResume();
    }

    //应用"定位自己的位置"开关(默认关):关闭时不激活定位图层(不发起真实定位、无蓝点)并隐藏"回到定位点"按钮,
    //选择点只来自搜索/选点/输入坐标;设置页返回后经 onResume 重读即时生效
    private void applyLocateSelfSetting() {
        boolean locateSelf = sharedPreferences.getBoolean("setting_locate_self", false);
        mMapController.setMyLocationEnabled(locateSelf);
        findViewById(R.id.cur_position).setVisibility(locateSelf ? View.VISIBLE : View.GONE);
    }

    //高德 key 缺失引导(onCreate 判定一次):生效 key 为空时弹窗,可跳设置页锚定 key 项、或先看申请指引;
    //点"稍后"不打断使用,未配置则下次进主界面仍会提示
    private void maybePromptAmapKey() {
        mKeyMissingOnCreate = AmapUtils.getEffectiveApiKey(this).isEmpty();
        if (!mKeyMissingOnCreate) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.amap_key_missing_title)
                .setMessage(R.string.amap_key_missing_message)
                .setPositiveButton(R.string.amap_key_missing_go_settings, (dialog, which) -> {
                    Intent intent = new Intent(this, SettingsActivity.class);
                    intent.putExtra(SettingsActivity.EXTRA_ANCHOR_KEY, AmapUtils.PREF_KEY_AMAP_API_KEY);
                    startActivity(intent);
                })
                .setNegativeButton(R.string.amap_key_missing_how_to_get,
                        (dialog, which) -> AmapKeyGuide.show(this))
                .setNeutralButton(R.string.amap_key_missing_later, null)
                .show();
    }

    //key 下发在 Application 启动期,设置页补配后需重启进程才生效;onResume 检测到补配即提示一次
    private void maybeToastKeyConfigured() {
        if (mKeyMissingOnCreate && !AmapUtils.getEffectiveApiKey(this).isEmpty()) {
            mKeyMissingOnCreate = false;
            GoUtils.DisplayToast(this, getResources().getString(R.string.amap_key_configured_restart));
        }
    }

    @Override
    protected void onStop() {
        XLog.i("MainActivity: onStop");
        //取消注册传感器监听
        mCompassSensorController.unregister();
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

        //通知栏停止动作接收器,与 onCreate 注册成对
        unregisterReceiver(mStopMockReceiver);

        mCompassSensorController.unregister();

        // showLocation 静态门面不再委托本界面的控制器
        sMapController = null;

        // 退出时销毁定位
        mCurrentLocationSource.destroy();
        // 关闭定位图层
        mMapController.getAMap().setMyLocationEnabled(false);
        mMapController.onDestroy();

        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        //搜索界面（菜单项展开/收起、建议列表、搜索历史面板）收编于 SearchViewController
        mSearchViewController.setupSearchItem(menu.findItem(R.id.action_search));

        return true;
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
        ibSave.setOnClickListener(v -> mLocationRecordHelper.recordCurrentLocation(mMapState.getSelectedPoint().longitude,
                mMapState.getSelectedPoint().latitude, true));
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
        ibShare.setOnClickListener(v -> ShareUtils.shareText(MainActivity.this, getResources().getString(R.string.main_share_position), poiLongitude.getText()+","+poiLatitude.getText()));
        ImageButton ibFly = poiView.findViewById(R.id.poi_fly);
        ibFly.setOnClickListener(this::doGoLocation);
        ImageButton ibMark = poiView.findViewById(R.id.poi_mark);
        ibMark.setOnClickListener(v -> mLocationRecordHelper.favoriteCurrentLocation(mMapState.getSelectedPoint().longitude,
                mMapState.getSelectedPoint().latitude));
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

        //罗盘传感器（注册/注销与方向计算）收编于 CompassSensorController；方向注入定位源（蓝点随设备朝向旋转）
        mCompassSensorController = new CompassSensorController(this, direction -> {
            if (mCurrentLocationSource != null) {
                mCurrentLocationSource.setCompassDirection(direction);
            }
        });
        mCompassSensorController.register();
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
        //输入坐标对话框收编于 InputPositionDialog；确认后按原顺序落选中点/标记名/视野（selectPoint→setMarkName→markMap→moveCamera）
        inputPosBtn.setOnClickListener(v -> InputPositionDialog.show(this, gcj02Point -> {
            mMainViewModel.selectPoint(gcj02Point);
            mMapState.setMarkName(getResources().getString(R.string.input_position_mark_name));

            mMapController.markMap();

            mMapController.getAMap().moveCamera(CameraUpdateFactory.changeLatLng(mMapState.getSelectedPoint()));
        }));
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
                mMapController.clearOverlays(); //模拟中标记随停服移除
                Snackbar.make(v, getResources().getString(R.string.main_snack_mock_stopped), Snackbar.LENGTH_LONG).show();
                mButtonStart.setImageResource(R.drawable.ic_position);
            } else {
                double[] latLng = MapUtils.gcj02towgs84(mMapState.getSelectedPoint().longitude, mMapState.getSelectedPoint().latitude);
                // 海拔来自 EditTextPreference，非法值按默认处理而非抛 NumberFormatException
                double alt = GoUtils.parseDoubleOrDefault(sharedPreferences.getString("setting_altitude", "55.0"), 55.0);
                if (!mMockController.setPosition(latLng[0], latLng[1], alt)) {
                    // 供给循环未建立(服务尚未连上或权限缺失)，此时清标记只会让用户以为已传送
                    GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_mock_service));
                    return;
                }
                Snackbar.make(v, getResources().getString(R.string.main_snack_moved), Snackbar.LENGTH_LONG).show();

                mLocationRecordHelper.recordCurrentLocation(mMapState.getSelectedPoint().longitude, mMapState.getSelectedPoint().latitude, false);
                //记住本次模拟位置(冷启动恢复用),须在选中点清除前落盘
                mLocationRecordHelper.saveLastPosition();

                //GO 成功:选中点消费置空,原位保留"模拟中"标记指示当前模拟位置
                LatLng mockedPoint = mMapState.getSelectedPoint();
                mMainViewModel.clearSelectedPoint();
                mMapController.markMockingPosition(mockedPoint);

                maybeWarnWifiOnce();
            }
        } else {
            if (!GoUtils.isAllowMockLocation(this)) {
                GoUtils.showEnableMockLocationDialog(this);
                XLog.e("无模拟位置权限!");
            } else {
                if (mMapState.getSelectedPoint() == null && !mLocationRecordHelper.selectDefaultFavorite()) {
                    Snackbar.make(v, getResources().getString(R.string.main_snack_select_first), Snackbar.LENGTH_LONG).show();
                } else {
                    double[] latLng = MapUtils.gcj02towgs84(mMapState.getSelectedPoint().longitude, mMapState.getSelectedPoint().latitude);
                    double alt = GoUtils.parseDoubleOrDefault(sharedPreferences.getString("setting_altitude", "55.0"), 55.0);
                    if (!mMockController.start(latLng[0], latLng[1], alt)) {
                        // 服务没有真的起来就不改任何界面状态：落历史、清标记、换图标都会构成"已在模拟"的假象
                        XLog.e("模拟位置服务启动失败");
                        GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_mock_service));
                        return;
                    }
                    //镜像模拟运行状态（MockLocationController 无回调，由调用点通知）
                    mMainViewModel.setMockRunning(true);
                    mButtonStart.setImageResource(R.drawable.ic_fly);
                    Snackbar.make(v, getResources().getString(R.string.main_snack_mock_started), Snackbar.LENGTH_LONG).show();

                    mLocationRecordHelper.recordCurrentLocation(mMapState.getSelectedPoint().longitude, mMapState.getSelectedPoint().latitude, false);
                    //记住本次模拟位置(冷启动恢复用),须在选中点清除前落盘
                    mLocationRecordHelper.saveLastPosition();
                    //GO 成功:选中点消费置空,原位保留"模拟中"标记指示当前模拟位置
                    LatLng mockedPoint = mMapState.getSelectedPoint();
                    mMainViewModel.clearSelectedPoint();
                    mMapController.markMockingPosition(mockedPoint);

                    maybeWarnWifiOnce();
                }
            }
        }
    }

    //WiFi 开启时定位会闪回真实位置:首次成功模拟后提示一次并持久化,此后不再打断
    private void maybeWarnWifiOnce() {
        if (!GoUtils.isWifiEnabled(this) || sharedPreferences.getBoolean(PREF_KEY_WIFI_WARNING_SHOWN, false)) {
            return;
        }
        sharedPreferences.edit().putBoolean(PREF_KEY_WIFI_WARNING_SHOWN, true).apply();
        GoUtils.showDisableWifiDialog(this);
    }

}
