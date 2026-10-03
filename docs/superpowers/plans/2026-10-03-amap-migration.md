# 百度 LBS → 高德 SDK 迁移实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 地图渲染/POI 搜索/逆地理/定位蓝点从百度 LBS SDK 整体切换到高德 Android SDK,坐标系 BD09→GCJ02,历史数据一次性迁移,键注入简化为单键 `AMAP_API_KEY`。

**Architecture:** 三个 commit:① 依赖+键+全部组件替换(编译闭包约束下的单一大提交)→ ② 数据迁移(DB 双库 v1→2 + prefs 迁移)+ JVM 测试 → ③ AGENTS.md 更新 + 全量门槛。虚拟定位核心(TestProvider 写 WGS84)零改动。

**Tech Stack:** 纯 Java Android 单模块,AGP 9.4.1 / Gradle 9.8 / JDK 17;高德 Maven 三件套 `com.amap.api:3dmap:10.0.600` / `search:9.7.1` / `location:11.3.000`;JVM 测试(JUnit4)。

**Spec:** `docs/superpowers/specs/2026-10-03-amap-migration-design.md`(执行者须先读 spec 与本计划)

## Global Constraints

- 构建一律 `sh gradlew ...`(禁止 chmod);JDK 17;纯 Java,禁 Kotlin/Robolectric
- **CRLF 文件**:`MainActivity.java`、`FragmentSettings.java` 每次编辑后验证 `file <path>` 仍含 CRLF 且 `git diff --numstat` 行数合理
- lint 0 error 0 warning;不得新增抑制注解
- org.json 在 JVM 测试中是严格构件——纯函数一律用纯字符串入参,不碰 JSON/SDK POJO
- 所有 commit 落本地 master,不推送
- key 未配置(空串)时必须可构建、测试全绿;运行时地图空白属预期
- 高德统一返回码:**`rCode == 1000` 为成功**

---

### Task 1: 依赖、键注入与全部组件替换(commit ①)

所有 UI 类依赖百度类型,依赖一换必须一次替换完才能编译——本任务是一个编译闭包,改动大但边界清晰。

**Files:**
- Modify: `app/build.gradle`、`app/proguard-rules.pro`、`app/src/main/AndroidManifest.xml`
- Delete: `app/libs/BaiduLBS_Android.jar`、`app/libs/arm64-v8a/`(百度 .so)、`app/lint-baseline.xml`
- Modify: `app/src/main/java/com/zcshou/gogogo/GoApplication.java`
- Rewrite: `app/src/main/java/com/zcshou/ui/map/MapController.java`、`app/src/main/java/com/zcshou/ui/location/CurrentLocationSource.java`
- Modify: `app/src/main/java/com/zcshou/ui/map/MapState.java`、`MainViewModel.java`、`MainActivity.java`(CRLF)、`GeocoderHelper.java`
- Modify: `app/src/main/java/com/zcshou/ui/search/PoiSearchHelper.java`
- Modify: `app/src/main/java/com/zcshou/utils/MapUtils.java`
- Modify: `app/src/main/java/com/zcshou/ui/settings/FragmentSettings.java`(CRLF,死 import)
- Modify: `app/src/main/res/layout/main_map.xml`、`location_input.xml`、`app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/zcshou/ui/map/MainViewModelTest.java`(import 换包)

**Interfaces(后续任务依赖的产出):**
- `MapUtils.gcj02towgs84(double lng, double lat)` → `double[]{lng,lat}`(已存在,不变)
- `MapUtils.wgs2gcj(double lng, double lat)` → `double[]{lng,lat}`(本任务新增)
- `MapUtils.bd09togcj02(double bdLon, double bdLat)` → `double[]{lng,lat}`(保留,Task 2 迁移用)
- `GeocoderHelper.initSdk(Context, SdkGeoCoderListener)`、`reverseBySdk(LatLng)`、`resolveAddress(double lng, double lat, AddressCallback)`;`AddressCallback.onAddressResolved(int rCode, String formatAddress)`
- `CurrentLocationSource(Context, LocationListener)`;`LocationListener.onReceiveLocation(AMapLocation)` / `onDiagnostic(int errorCode, String errorInfo)`;另有 `setCompassDirection(float)`、`stop()`、`requestLocation()`
- `PoiSearchHelper(Context, HistoryRepository, SuggestionListener)`(构造新增 Context);`requestSuggestions(String, String)`;无 init/destroy
- `MapController(MapView, AMap, MapState, MapEventListener)`;`init(LocationSource)`、`getAMap()`、`getMapView()`、`onCreate(Bundle)`、`onResume()`、`onPause()`、`onDestroy()`、`markMap()`、`resetMap(double, double)`、`showLocation(String, String, String)`、`showInfoWindowOnSelectedMarker()`
- `MapEventListener.onMapClick(double, double)` / `onMapLongClick(double, double)` / `onPoiClick(Poi)`(**无双击回调**)

- [ ] **Step 1: build.gradle 键与依赖改造**

头部键读取(替换 :5-15):

```groovy
// 从 local.properties 读取高德地图密钥并注入 manifestPlaceholder(键名与 CI 注入方式保持不变)
def localProps = new Properties()
def localPropsFile = rootProject.file('local.properties')
if (localPropsFile.exists()) {
    localPropsFile.withInputStream { localProps.load(it) }
}
def amapApiKey = localProps.getProperty('AMAP_API_KEY', '')
if (amapApiKey.isEmpty()) {
    logger.warn('AMAP_API_KEY 未在 local.properties 中配置,高德地图运行时将无法初始化')
}
```

`defaultConfig` 内删除两行 `buildConfigField 'String', 'MAPS_API_KEY'...` 与 `MAPS_SAFE_CODE`,改为:

```groovy
        manifestPlaceholders = [AMAP_API_KEY: amapApiKey]
```

删除 `sourceSets { main { jniLibs.srcDirs = ['libs'] } }` 整块(:101-105)。

lint 块(:112-118)整体删除(连同注释——百度 jar 豁免条目随迁移消失)。

dependencies 内删除 `implementation fileTree(include: ['*.jar'], dir: 'libs')` 与 `implementation files('libs/BaiduLBS_Android.jar')`,在 okhttp 行后新增:

```groovy
    // 高德 LBS 三件套(地图/搜索/定位),替代百度本地 jar
    implementation 'com.amap.api:3dmap:10.0.600'
    implementation 'com.amap.api:search:9.7.1'
    implementation 'com.amap.api:location:11.3.000'
```

`buildFeatures { buildConfig = true }` 保留(UpdateManager 等仍可能使用)。

- [ ] **Step 2: 删除百度产物与 lint baseline,改 proguard**

```bash
git rm -q app/libs/BaiduLBS_Android.jar app/lint-baseline.xml
git rm -rq app/libs/arm64-v8a
ls app/libs/ 2>/dev/null   # 若为空目录,git 不跟踪空目录,工作区残留可 rmdir
rmdir app/libs 2>/dev/null || true
```

`app/proguard-rules.pro`:删除百度四行 keep/dontwarn:

```
-keep class com.baidu.** {*;}
-keep class vi.com.** {*;}
-keep class com.baidu.vi.** {*;}
-dontwarn com.baidu.**
```

原位替换为高德官方规则:

```
# 高德 SDK(3dmap/search/location)
-keep class com.amap.api.** {*;}
-keep class com.autonavi.** {*;}
-dontwarn com.amap.api.**
-dontwarn com.autonavi.**
```

- [ ] **Step 3: AndroidManifest.xml**

`<application>` 内新增(放在 FileProvider 之前):

```xml
        <!-- 高德开放平台 key(经 manifestPlaceholder 注入,绑定包名 com.zcshou.gogogo + 签名 SHA1) -->
        <meta-data
            android:name="com.amap.api.v2.apikey"
            android:value="${AMAP_API_KEY}" />
```

删除整个百度 service 块(含两行注释):

```xml
        <!-- 百度地图 间隔定位必须配置此service否则功能无效 -->
        <!-- targetSdk 34+ 前台服务需声明类型；SDK内部若调用startForeground缺少类型会崩溃，未使用时声明无副作用 -->
        <service
            android:name="com.baidu.location.f"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="location"
            android:process=":remote" />
```

`com.zcshou.service.ServiceGo` 的 service 声明**保留不动**。

- [ ] **Step 4: GoApplication 初始化替换**

import 区:`com.baidu.location.LocationClient`、`com.baidu.mapapi.CoordType`、`com.baidu.mapapi.SDKInitializer` 三行替换为:

```java
import com.amap.api.location.AMapLocationClient;
import com.amap.api.maps.MapsInitializer;
```

`onCreate` 中百度五句(:34-42,含注释)替换为:

```java
        // 高德 SDK 2021+ 强制隐私合规:必须先于一切 SDK 调用(与应用自身隐私协议流程同级,行为对齐原百度)
        MapsInitializer.updatePrivacyShow(this, true, true);
        MapsInitializer.updatePrivacyAgree(this, true);
        AMapLocationClient.updatePrivacyShow(this, true, true);
        AMapLocationClient.updatePrivacyAgree(this, true);
```

- [ ] **Step 5: MapUtils——wgs2gcj 新增,bd2wgs/wgs2bd09 删除**

删除 `bd2wgs`(:17-20)与 `wgs2bd09`(:22-48),原位新增(**保留** `bd09togcj02`、`gcj02towgs84` 及私有 transform 函数、常量):

```java
    /**
     * WGS84 转换为 GCJ-02(火星坐标;原 wgs2bd09 的第一段拆出)
     * @return double[] 转换后的经度,纬度 数组
     */
    public static double[] wgs2gcj(double lng, double lat) {
        double dlat = transformLat(lng - 105.0, lat - 35.0);
        double dlng = transformLon(lng - 105.0, lat - 35.0);
        double radlat = lat / 180.0 * pi;
        double magic = Math.sin(radlat);
        magic = 1 - ee * magic * magic;
        double sqrtmagic = Math.sqrt(magic);
        dlat = (dlat * 180.0) / ((a * (1 - ee)) / (magic * sqrtmagic) * pi);
        dlng = (dlng * 180.0) / (a / sqrtmagic * Math.cos(radlat) * pi);
        return new double[] { lng + dlng, lat + dlat };
    }
```

- [ ] **Step 6: MapState——类型与初始点**

import:`com.baidu.mapapi.map.BitmapDescriptor`/`BitmapDescriptorFactory`/`LatLng` 三行替换为:

```java
import com.amap.api.maps.model.BitmapDescriptor;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.LatLng;
```

初始点(换算自原 BD09 占位点,公式与 `MapUtils.bd09togcj02` 一致):

```java
    /* 当前标记的地图点(初始占位点,已由原 BD09 值换算为 GCJ02) */
    private LatLng mSelectedPoint = new LatLng(36.54208893720672, 117.06357864688202);
```

`getMarkerIcon()` 内工厂调用换 `BitmapDescriptorFactory.fromResource(R.drawable.icon_gcoding)`(import 已换,语句本身不变)。

- [ ] **Step 7: 重写 MapController(全文替换)**

```java
package com.zcshou.ui.map;

import android.os.Bundle;
import android.view.View;

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
```

- [ ] **Step 8: 重写 CurrentLocationSource(全文替换)**

```java
package com.zcshou.ui.location;

import android.content.Context;

import androidx.annotation.NonNull;

import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.maps.LocationSource;

import com.elvishew.xlog.XLog;

/**
 * 定位数据源:收编原 MainActivity 的定位构造、配置与启停。
 * 实现 AMap LocationSource 桥接——AMapLocation(高德定位原生返回 GCJ02,且为
 * android.location.Location 子类)直接喂给地图蓝点图层,同时经 LocationListener
 * 交还持有者(MainActivity 的回调体保留在其实现中)。
 */
public class CurrentLocationSource implements LocationSource {
    /**
     * 定位回调
     */
    public interface LocationListener {
        void onReceiveLocation(AMapLocation aMapLocation);

        /**
         * 定位错误回调(errorCode 含义见高德定位 SDK 错误码表)
         */
        void onDiagnostic(int errorCode, String errorInfo);
    }

    private final Context mContext;
    private final LocationListener mListener;
    private OnLocationChangedListener mMapChangedListener;
    private AMapLocationClient mLocClient = null;
    //罗盘方向(0~360),由持有者经传感器回调注入,蓝点随其旋转
    private float mCompassDirection = 0.0f;

    public CurrentLocationSource(Context context, LocationListener listener) {
        mContext = context;
        mListener = listener;
    }

    /** 地图蓝点数据桥(定位图层激活时由 AMap 回调) */
    @Override
    public void activate(@NonNull OnLocationChangedListener onLocationChangedListener) {
        mMapChangedListener = onLocationChangedListener;
        init();
    }

    @Override
    public void deactivate() {
        mMapChangedListener = null;
        stop();
    }

    /** 注入罗盘方向(度),喂给蓝点渲染 */
    public void setCompassDirection(float degrees) {
        mCompassDirection = degrees;
    }

    //开始定位
    public void start() {
        if (mLocClient != null) {
            mLocClient.startLocation();
        }
    }

    //停止定位
    public void stop() {
        if (mLocClient != null) {
            mLocClient.stopLocation();
        }
    }

    //请求一次定位(连续定位模式下触发一次即时请求)
    public void requestLocation() {
        start();
    }

    //初始化定位客户端(隐私合规接口已在 GoApplication 统一调用)
    private void init() {
        try {
            mLocClient = new AMapLocationClient(mContext);
            mLocClient.setLocationListener(amapLocation -> {
                if (amapLocation == null) {
                    return;
                }
                if (amapLocation.getErrorCode() != 0) {
                    mListener.onDiagnostic(amapLocation.getErrorCode(), amapLocation.getErrorInfo());
                } else {
                    //罗盘方向写入 bearing,蓝点随设备朝向旋转
                    amapLocation.setBearing(mCompassDirection);
                    if (mMapChangedListener != null) {
                        mMapChangedListener.onLocationChanged(amapLocation);
                    }
                }
                mListener.onReceiveLocation(amapLocation);
            });
            mLocClient.setLocationOption(getLocationClientOption());
            start();
        } catch (Exception e) {
            XLog.e("ERROR: initMapLocation");
        }
    }

    @NonNull
    private static AMapLocationClientOption getLocationClientOption() {
        AMapLocationClientOption locationOption = new AMapLocationClientOption();
        //高精度定位(对应原百度 Hight_Accuracy)
        locationOption.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
        //连续定位间隔 1000ms(与原百度 setScanSpan(1000) 一致)
        locationOption.setInterval(1000);
        //需要地址信息(取当前城市,与原 setIsNeedAddress(true) 一致)
        locationOption.setNeedAddress(true);
        return locationOption;
    }
}
```

- [ ] **Step 9: PoiSearchHelper——Inputtips 化**

全类改动:import 区换为 `android.content.Context` + `androidx.annotation.NonNull` + `com.amap.api.services.core.LatLonPoint` + `com.amap.api.services.help.Inputtips`/`InputtipsQuery`/`Tip` + 原 data/utils/xlog/java.util import 不变(删 `com.baidu.*` 四行、`ArrayList/HashMap/List/Map` 保留)。

构造加 Context,删除 `mSuggestionSearch` 字段、`init()`、`destroy()`:

```java
    private final Context mContext;
    private final HistoryRepository mHistoryRepository;
    private final SuggestionListener mListener;

    public PoiSearchHelper(@NonNull Context context, @NonNull HistoryRepository historyRepository, @NonNull SuggestionListener listener) {
        mContext = context;
        mHistoryRepository = historyRepository;
        mListener = listener;
    }
```

`requestSuggestions` 替换为(原 `init()`+`requestSuggestion` 职责合并;Inputtips 每查询独立构造):

```java
    /**
     * 下发建议查询(原 onQueryTextSubmit / onQueryTextChange 中的 requestSuggestion 触发点;
     * 城市取自 MapState.getCurrentCity(),由调用方传入;try/catch 与 UI 处理保留在 MainActivity)
     */
    public void requestSuggestions(String query, String city) {
        InputtipsQuery inputtipsQuery = new InputtipsQuery(query, city == null ? "" : city);
        Inputtips inputtips = new Inputtips(mContext, inputtipsQuery);
        inputtips.setInputtipsListener((tips, rCode) -> {
            //rCode==1000 为高德统一成功码
            if (rCode != 1000 || tips == null || tips.isEmpty()) {
                mListener.onSuggestionUnavailable();
            } else {
                mListener.onSuggestionResult(getTipList(tips));
            }
        });
        inputtips.requestInputtipsAsyn();
    }
```

`getMapList(SuggestionResult)` 替换为:

```java
    /**
     * Tip→List&lt;Map&gt; 转换(原 SuggestionResult→Map 转换对应物;
     * 过滤无坐标项,键名与 SimpleAdapter 列契约一致)
     */
    @NonNull
    public static List<Map<String, Object>> getTipList(List<Tip> tips) {
        List<Map<String, Object>> data = new ArrayList<>();

        for (Tip tip : tips) {
            LatLonPoint point = tip.getPoint();
            if (point == null) {
                continue;
            }

            Map<String, Object> poiItem = new HashMap<>();
            poiItem.put(POI_NAME, tip.getName());
            poiItem.put(POI_ADDRESS, tip.getDistrict() + " " + tip.getAddress());
            poiItem.put(POI_LONGITUDE, "" + point.getLongitude());
            poiItem.put(POI_LATITUDE, "" + point.getLatitude());
            data.add(poiItem);
        }
        return data;
    }
```

`savePoiSelection` 换算函数与注释、参数名:

```java
    /**
     * POI 点选保存路径(原 initSearchView 中两处插表段对应物,
     * 逐字段一致:WGS84 经纬度来自 gcj02towgs84 换算,lngCustom/latCustom 为 GCJ02 原坐标)
     */
    public void savePoiSelection(String name, String address, LatLng gcjPoint, String lngCustom, String latCustom) {
        double[] latLng = MapUtils.gcj02towgs84(gcjPoint.longitude, gcjPoint.latitude);
```

(其余语句不变;`LatLng` import 换 `com.amap.api.maps.model.LatLng`;类头 javadoc 中 SuggestionSearch 字样改 Inputtips;`/* POI_* 键名 */` 注释里 `getMapList` 改 `getTipList`。)

- [ ] **Step 10: GeocoderHelper——GeocodeSearch 单通道(全文替换)**

```java
package com.zcshou.ui.map;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.amap.api.services.core.LatLonPoint;
import com.amap.api.services.geocoder.GeocodeSearch;
import com.amap.api.services.geocoder.RegeocodeQuery;
import com.amap.api.services.geocoder.RegeocodeResult;

import com.elvishew.xlog.XLog;

/**
 * 地理编码助手:逆地理编码单通道(高德 SDK GeocodeSearch,客户端内部网络,单 Android key)。
 * 构造与监听注册、请求下发收编于此;SDK 回调在主线程,单行落库由调用方在回调内完成(轻量可接受);
 * 地址提取三分支收敛为纯函数 extractAddress(纯字符串入参,JVM 可测)。
 */
public class GeocoderHelper {

    /**
     * 逆地理结果回调(原 REST 版 AddressCallback 对应物;线程为 SDK 主线程回调)
     */
    public interface AddressCallback {
        /**
         * rCode==1000 且解析成功时 formatAddress 非空,否则为 null(调用方经 extractAddress 取落库值)
         */
        void onAddressResolved(int rCode, @Nullable String formatAddress);
    }

    /**
     * 长按逆地理回调(原 SdkGeoCoderListener 只保留反向通道;正向编码原仅占位日志,随迁移删除)
     */
    public interface SdkGeoCoderListener {
        void onGetReverseGeoCodeResult(RegeocodeResult reverseGeoCodeResult);
    }

    private GeocodeSearch mGeocodeSearch;
    //单飞行回调(resolveAddress 下发与回调一一对应)
    private AddressCallback mPendingCallback;

    /**
     * 构造 GeocodeSearch 并注册结果监听(原 initMap 中 newInstance + setOnGetGeoCodeResultListener 部分)
     */
    public void initSdk(@NonNull Context context, @NonNull SdkGeoCoderListener listener) {
        try {
            mGeocodeSearch = new GeocodeSearch(context);
            mGeocodeSearch.setOnGeocodeSearchListener(new GeocodeSearch.OnGeocodeSearchListener() {
                @Override
                public void onRegeocodeSearched(RegeocodeResult regeocodeResult, int rCode) {
                    listener.onGetReverseGeoCodeResult(regeocodeResult);
                }

                @Override
                public void onGeocodeSearched(com.amap.api.services.geocoder.GeocodeResult geocodeResult, int rCode) {
                    //正地理编码未使用(原百度正向通道仅占位日志,已随迁移删除)
                }
            });
        } catch (Exception e) {
            XLog.e("GeocoderHelper: init failed");
        }
    }

    /**
     * 发起长按逆地理请求(原 onMapLongClick 中的 reverseGeoCode 触发点)
     */
    public void reverseBySdk(@NonNull LatLng point) {
        if (mGeocodeSearch == null) {
            return;
        }
        //高德 ReGeocodeQuery 为"纬度,经度"语序(LatLonPoint(lat, lng)),200 为查找半径(米)
        mGeocodeSearch.getFromLocationAsyn(new RegeocodeQuery(new LatLonPoint(point.latitude, point.longitude), 200, GeocodeSearch.AMAP));
    }

    /**
     * 发起落库逆地理请求(原 REST 版 resolveAddress 对应物,ak/safeCode 概念随 SDK 化删除;
     * 回调在 SDK 主线程,单行落库在回调内完成)
     */
    public void resolveAddress(double lng, double lat, @NonNull AddressCallback cb) {
        if (mGeocodeSearch == null) {
            cb.onAddressResolved(-1, null);
            return;
        }
        mPendingCallback = cb;
        //参数为 GCJ02;LatLonPoint 语序为(纬度,经度)
        mGeocodeSearch.getFromLocationAsyn(new RegeocodeQuery(new LatLonPoint(lat, lng), 200, GeocodeSearch.AMAP));
    }

    /** 纯函数:逆地理原始结果→落库地址(不触碰 android.*,不依赖实例状态)。三分支:
     * 1. rCode==1000 且 formatAddress 非空 → formatAddress;
     * 2. 其余且 markName 非空 → markName(继承原"业务失败回退标记名"语义);
     * 3. 其余 → defaultName(原 REST 版 JSON message 回退无对应物——SDK 以 rCode 表达失败)。 */
    public static String extractAddress(int rCode, @Nullable String formatAddress, @Nullable String markName, @NonNull String defaultName) {
        if (rCode == 1000 && formatAddress != null && !formatAddress.isEmpty()) {
            return formatAddress;
        }
        return markName != null && !markName.isEmpty() ? markName : defaultName;
    }
}
```

注意:`reverseBySdk(LatLng)` 的 `LatLng` 为 `com.amap.api.maps.model.LatLng`(需补 `import com.amap.api.maps.model.LatLng;`);`onGeocodeSearched` 的 `GeocodeResult` 用全限定名或补 import(`com.amap.api.services.geocoder.GeocodeResult`)。

同时全文替换 `app/src/test/java/com/zcshou/ui/map/GeocoderHelperTest.java`(Task 1 删除了 `parseAddress`,旧测试必须随实现同步重写才能编译):

```java
package com.zcshou.ui.map;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * GeocoderHelper.extractAddress 纯 JVM 单测(纯字符串入参,无 Android/SDK 依赖)。
 * 三分支语义锚定(对应原百度 REST 版 parseAddress 的行为迁移):
 * 1. rCode==1000 且 formatAddress 非空 → formatAddress;
 * 2. 其余且 markName 非空 → markName(原"业务失败回退标记名");
 * 3. 其余 → defaultName(原 JSON message 回退无对应物——SDK 以 rCode 表达失败)。
 */
public class GeocoderHelperTest {

    private static final String MARK = "标记名";
    private static final String DEFAULT_NAME = "默认名称";

    @Test
    public void extractAddress_success_returnsFormatAddress() {
        assertEquals("山东省济南市历下区某某路",
                GeocoderHelper.extractAddress(1000, "山东省济南市历下区某某路", MARK, DEFAULT_NAME));
    }

    @Test
    public void extractAddress_errorCode_returnsMarkName() {
        assertEquals(MARK, GeocoderHelper.extractAddress(1002, "不应被采用", MARK, DEFAULT_NAME));
    }

    @Test
    public void extractAddress_nullFormatAddress_returnsMarkName() {
        assertEquals(MARK, GeocoderHelper.extractAddress(1000, null, MARK, DEFAULT_NAME));
    }

    @Test
    public void extractAddress_emptyFormatAddress_returnsMarkName() {
        assertEquals(MARK, GeocoderHelper.extractAddress(1000, "", MARK, DEFAULT_NAME));
    }

    @Test
    public void extractAddress_nullMarkName_returnsDefaultName() {
        assertEquals(DEFAULT_NAME, GeocoderHelper.extractAddress(1002, null, null, DEFAULT_NAME));
        assertEquals(DEFAULT_NAME, GeocoderHelper.extractAddress(1002, null, "", DEFAULT_NAME));
    }
}
```

- [ ] **Step 10b: CI workflow 键更新**

三个 workflow 中 local.properties 注入行(各两行)统一替换:`.github/workflows/build-check.yml:46-47`、`build-release.yml:36-37`、`codeql-analysis.yml:64-65`:

```yaml
          MAPS_API_KEY=${{ secrets.MAPS_API_KEY }}
          MAPS_SAFE_CODE=${{ secrets.MAPS_SAFE_CODE }}
```
→
```yaml
          AMAP_API_KEY=${{ secrets.AMAP_API_KEY }}
```

(secrets 值由用户在 GitHub 仓库配置,见 spec §5.3。)

- [ ] **Step 11: FragmentSettings 死 import(CRLF)**

删除 `import com.baidu.mapapi.SDKInitializer;` 一行(全文件唯一 Baidu 引用)。编辑后验证 CRLF。

- [ ] **Step 12: MainViewModel / MainViewModelTest 换 LatLng 包**

`MainViewModel.java`:`import com.baidu.mapapi.model.LatLng;` → `import com.amap.api.maps.model.LatLng;`(其余不动,LatLng 构造同为纬度在前)。
`MainViewModelTest.java`:同样只换这一行 import。

- [ ] **Step 13: 资源——地图控件与坐标系文案**

`main_map.xml`:

```xml
        <com.amap.api.maps.MapView
            android:id="@+id/amapView"
            android:layout_width="fill_parent"
            android:layout_height="fill_parent"
            android:clickable="true"
            android:focusable="true" />
```

`strings.xml`:`<string name="input_position_baidu">BD09 坐标系</string>` → `<string name="input_position_gcj">高德(GCJ02) 坐标系</string>`(键一并改名,旧键无其他引用)。

`location_input.xml`:单选框 `android:id="@+id/pos_type_bd"` → `@+id/pos_type_gcj`,`android:text="@string/input_position_baidu"` → `@string/input_position_gcj`(checked="true" 保留)。

- [ ] **Step 14: MainActivity——import 区与生命周期**

import 删除:`com.baidu.location.BDLocation`、`com.baidu.mapapi.SDKInitializer`、`com.baidu.mapapi.map.BaiduMap`、`com.baidu.mapapi.map.InfoWindow`、`com.baidu.mapapi.map.MapPoi`、`com.baidu.mapapi.map.MapStatus`、`com.baidu.mapapi.map.MapStatusUpdate`、`com.baidu.mapapi.map.MapStatusUpdateFactory`、`com.baidu.mapapi.map.MapView`、`com.baidu.mapapi.map.MyLocationConfiguration`、`com.baidu.mapapi.map.MyLocationData`、`com.baidu.mapapi.model.LatLng`、`com.baidu.mapapi.search.core.SearchResult`、`com.baidu.mapapi.search.geocode.GeoCodeResult`、`com.baidu.mapapi.search.geocode.ReverseGeoCodeResult`、`com.zcshou.gogogo.BuildConfig`(确认 :756-757 改后无引用后删)。

import 新增:

```java
import com.amap.api.location.AMapLocation;
import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdate;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.Poi;
import com.amap.api.services.geocoder.RegeocodeResult;
```

(:43-44 的 `com.baidu.location.BDLocation` 同删;`GeocoderHelper` 的 import 保留。)

字段注释:`// 当前位置的百度纬度/经度` → `// 当前位置的 GCJ02 纬度/经度`。

`onCreate`:把定位源创建移到 initMap 之前并传参(替换 :167-171 段):

```java
        //定位数据源提前创建:地图初始化即接线 LocationSource(蓝点桥接需在启用定位图层前就位)
        mCurrentLocationSource = new CurrentLocationSource(this, this);

        initMap(savedInstanceState);
```

`initMap()` 签名改 `private void initMap(Bundle savedInstanceState)`,开头(MapController 构造后)补:

```java
        // AMap MapView 生命周期必须显式回调(百度无此要求)
        mMapController.onCreate(savedInstanceState);
        mMapController.init(mCurrentLocationSource);
```

原 `mCurrentLocationSource = new CurrentLocationSource(this, this); mCurrentLocationSource.init();` 两行删除(位置在原 :170-171)。

`onPause`/`onResume`:替换为 `mMapController.onPause();` / `mMapController.onResume();`(其余不动)。

`onDestroy`::239 `mMapController.getBaiduMap().setMyLocationEnabled(false);` → `mMapController.getAMap().setMyLocationEnabled(false);`,:240 `getMapView().onDestroy()` → `mMapController.onDestroy();`,:243 删除 `mPoiSearchHelper.destroy();` 及注释行。

- [ ] **Step 15: MainActivity——initMap 内部**

初始化段替换(:393-461 现文本基础上):

```java
    private void initMap(Bundle savedInstanceState) {
        // 地图初始化(MapView/AMap 的配置与点击监听注册收编于 MapController)
        MapView mapView = findViewById(R.id.amapView);
        mMapController = new MapController(mapView, mapView.getMap(), mMapState, this);
        // showLocation 静态门面委托到当前控制器
        sMapController = mMapController;
        mMapController.onCreate(savedInstanceState);

        View poiView = View.inflate(MainActivity.this, R.layout.location_poi_info, null);
        TextView poiAddress = poiView.findViewById(R.id.poi_address);
        TextView poiLongitude = poiView.findViewById(R.id.poi_longitude);
        TextView poiLatitude = poiView.findViewById(R.id.poi_latitude);
        ImageButton ibSave = poiView.findViewById(R.id.poi_save);
        ... (ibSave/ibCopy/ibShare/ibFly 四个监听体原样保留不动)

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
                    poiLatitude.setText(String.valueOf(reverseGeoCodeResult.getRegeocodeAddress().getLatLonPoint().getLatitude()));
                    poiLongitude.setText(String.valueOf(reverseGeoCodeResult.getRegeocodeAddress().getLatLonPoint().getLongitude()));
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
        ... (传感器注册段原样保留)
    }
```

(注意:原百度 `new InfoWindow(poiView, location, -100)` + `showInfoWindow(mInfoWindow)` 的直接挂载方式,改为回调里 `showInfoWindowOnSelectedMarker()`;`poiView` 由上面的适配器提供。`-100` 偏移不再可用,气泡锚点差异属 spec §13 允许项。)

- [ ] **Step 16: MainActivity——地图回调与定位回调**

`onMapClick`/`onMapLongClick` 不变;`onPoiClick(MapPoi poi)` → `onPoiClick(Poi poi)`(`poi.getPosition()` 同名);**整个 `onMapDoubleClick` 方法删除**。

`onReceiveLocation` 全文替换:

```java
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
```

(蓝点渲染已由 CurrentLocationSource 桥接承担,原 `MyLocationData`/`MyLocationConfiguration` 段删除。)

`onDiagnostic` 替换:

```java
    /**
     * 定位诊断信息回调(错误码说明见 CurrentLocationSource.LocationListener)
     */
    @Override
    public void onDiagnostic(int errorCode, String errorInfo) {
        XLog.i("AMap ERROR: " + errorCode + "-" + errorInfo);
    }
```

`onSensorChanged` 末尾(:341 后)追加:

```java
        //罗盘方向注入定位源,蓝点随设备朝向旋转
        if (mCurrentLocationSource != null) {
            mCurrentLocationSource.setCompassDirection(mCurrentDirection);
        }
```

- [ ] **Step 17: MainActivity——地图按键、输入对话框与 Camera 调用点**

`initMapButton` 内:

- 地图类型:`mMapController.getBaiduMap().setMapType(BaiduMap.MAP_TYPE_NORMAL)` → `mMapController.getAMap().setMapType(AMap.MAP_TYPE_NORMAL)`;`BaiduMap.MAP_TYPE_SATELLITE` → `AMap.MAP_TYPE_SATELLITE`
- 缩放:`animateMapStatus(MapStatusUpdateFactory.zoomIn())` → `mMapController.getAMap().animateCamera(CameraUpdateFactory.zoomIn())`(zoomOut 同理)
- 回当前位置:`mMapController.resetMap(mCurrentLat, mCurrentLon, mCurrentDirection)` → `mMapController.resetMap(mCurrentLat, mCurrentLon)`
- 对话框:`RadioButton rbBD = view.findViewById(R.id.pos_type_bd);` → `RadioButton rbGcj = view.findViewById(R.id.pos_type_gcj);`;选择分支:

```java
                            if (rbGcj.isChecked()) {
                                mMainViewModel.selectPoint(new LatLng(dialog_lat_double, dialog_lng_double));
                            } else {
                                double[] gcjLonLat = MapUtils.wgs2gcj(dialog_lng_double, dialog_lat_double);
                                mMainViewModel.selectPoint(new LatLng(gcjLonLat[1], gcjLonLat[0]));
                            }
```

- :615-616 `MapStatusUpdate mapstatusupdate = MapStatusUpdateFactory.newLatLng(...); mMapController.getBaiduMap().setMapStatus(mapstatusupdate);` → `mMapController.getAMap().moveCamera(CameraUpdateFactory.changeLatLng(mMapState.getSelectedPoint()));`

其余 `setMapStatus(newLatLng)` 调用点(:825-826 搜索建议点击、:851-852 搜索历史点击)同样替换为 `moveCamera(CameraUpdateFactory.changeLatLng(...))`。

:284 `mMapController.getBaiduMap().clear()` → `mMapController.getAMap().clear()`;:674 与 :694(doGoLocation 两处 clear)同样替换。

`initSearchView` 内 PoiSearchHelper 构造(:899)加 Context 实参:

```java
        mPoiSearchHelper = new PoiSearchHelper(this, mHistoryRepository, new PoiSearchHelper.SuggestionListener() {
```

- [ ] **Step 18: MainActivity——GO 流程、恢复与落库**

`doGoLocation` 两处 `MapUtils.bd2wgs(...)` → `MapUtils.gcj02towgs84(...)`(:665、:691)。

`restoreLastPosition`(:742-744):

```java
            mMapController.getAMap().animateCamera(CameraUpdateFactory.newLatLngZoom(lastPoint, 18.0f));
```

`recordCurrentLocation` 全文替换(:753-778):

```java
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
```

(:780 注释 `// 插表参数:...bd09 原坐标` → `...GCJ02 原坐标`。)

搜索建议点击(:824)与搜索历史点击(:850)的 `selectPoint` 语句不变(LatLng 类型随 import 生效)。

- [ ] **Step 19: 验证(CRLF + 构建 + 测试)**

```bash
file app/src/main/java/com/zcshou/ui/map/MainActivity.java app/src/main/java/com/zcshou/ui/settings/FragmentSettings.java
git diff --numstat
sh gradlew assembleDebug test
grep -rn 'com\.baidu\|BaiduMap\|BDLocation\|MapStatusUpdateFactory' app/src/main/java app/src/main/res --include='*.java' --include='*.xml' || echo '(baidu clean)'
```

Expected: 两个文件仍 CRLF;MainActivity diff 行数与改动范围相称(数百行,非整文件重写——若 numstat 显示删除行数≈全文件行数说明行尾被重写,`git checkout` 回滚该文件重做);BUILD SUCCESSFUL;baidu 零残留。

- [ ] **Step 20: Commit ①**

```bash
git add -A app
git commit -m "feat: migrate from Baidu LBS to AMap SDK"
```

---

### Task 2: 数据迁移与 JVM 测试(commit ②)

**Files:**
- Create: `app/src/main/java/com/zcshou/data/DbMigrations.java`
- Create: `app/src/test/java/com/zcshou/data/DbMigrationsTest.java`
- Create: `app/src/test/java/com/zcshou/utils/MapUtilsTest.java`
- Modify: `app/src/main/java/com/zcshou/data/DataBaseHistoryLocation.java`、`DataBaseHistorySearch.java`(DB_VERSION 与 onUpgrade)
- Modify: `app/src/main/java/com/zcshou/ui/map/MainActivity.java`(CRLF,prefs 迁移)

**Interfaces:**
- Consumes: Task 1 的 `MapUtils.bd09togcj02`、`GeocoderHelper.extractAddress`
- Produces: `DbMigrations.bd09ToGcj02(String, String)` → `String[]{lng,lat}` 或 `null`;`DbMigrations.migrateBd09CustomToGcj02(SQLiteDatabase, String table, String lngCol, String latCol)`

- [ ] **Step 1: 写失败测试——MapUtilsTest**

```java
package com.zcshou.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * MapUtils 坐标转换纯 JVM 单测(无 Android 依赖)。
 * bd09togcj02 锚定值与 MapState 初始占位点换算同源(该函数被 DB 迁移路径永久复用)。
 * gcj02towgs84/wgs2gcj 为近似互逆(gcj02towgs84 采用 lng*2-mglng 近似),往返容差放宽到 1e-5 度。
 */
public class MapUtilsTest {
    private static final double DELTA = 1e-12;
    private static final double ROUND_TRIP_DELTA = 1e-5;

    @Test
    public void bd09togcj02_knownAnchor() {
        double[] gcj = MapUtils.bd09togcj02(117.07018449827267, 36.547743718042415);
        assertEquals(117.06357864688202, gcj[0], DELTA);
        assertEquals(36.54208893720672, gcj[1], DELTA);
    }

    @Test
    public void gcj02towgs84_then_wgs2gcj_roundTrip_beijing() {
        double[] wgs = MapUtils.gcj02towgs84(116.397428, 39.90923);
        double[] gcj = MapUtils.wgs2gcj(wgs[0], wgs[1]);
        assertEquals(116.397428, gcj[0], ROUND_TRIP_DELTA);
        assertEquals(39.90923, gcj[1], ROUND_TRIP_DELTA);
    }

    @Test
    public void gcj02towgs84_then_wgs2gcj_roundTrip_jinan() {
        double[] wgs = MapUtils.gcj02towgs84(117.120, 36.651);
        double[] gcj = MapUtils.wgs2gcj(wgs[0], wgs[1]);
        assertEquals(117.120, gcj[0], ROUND_TRIP_DELTA);
        assertEquals(36.651, gcj[1], ROUND_TRIP_DELTA);
    }

    @Test
    public void wgs2gcj_offsetsArePositiveInChina() {
        double[] gcj = MapUtils.wgs2gcj(116.397428, 39.90923);
        assertTrue(gcj[0] > 116.397428);
        assertTrue(gcj[1] > 39.90923);
    }

    @Test
    public void bd09togcj02_returnsLngLatOrder() {
        double[] gcj = MapUtils.bd09togcj02(117.07018449827267, 36.547743718042415);
        assertNotNull(gcj);
        assertTrue("经度应在纬度之前且经度>纬度(中国境内)", gcj[0] > gcj[1]);
    }
}
```

- [ ] **Step 2: 写失败测试——DbMigrationsTest**

```java
package com.zcshou.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * DbMigrations 纯函数 JVM 单测(无 Android 依赖;SQLiteOpenHelper 本体不可 JVM 实例化,
 * 迁移正确性由本测试 + MapUtils.bd09togcj02 锚定共同覆盖,DB 循环保持薄)。
 */
public class DbMigrationsTest {

    @Test
    public void bd09ToGcj02_knownAnchor() {
        String[] gcj = DbMigrations.bd09ToGcj02("117.07018449827267", "36.547743718042415");
        assertEquals("117.06357864688202", gcj[0]);
        assertEquals("36.54208893720672", gcj[1]);
    }

    @Test
    public void bd09ToGcj02_invalidNumber_returnsNull() {
        assertNull(DbMigrations.bd09ToGcj02("abc", "36.5"));
        assertNull(DbMigrations.bd09ToGcj02("117.0", "xyz"));
        assertNull(DbMigrations.bd09ToGcj02(null, "36.5"));
    }
}
```

- [ ] **Step 3: 运行测试验证失败**

```bash
sh gradlew test -q 2>&1 | tail -20
```

Expected: FAIL——`DbMigrations` 不存在、`MapUtils.wgs2gcj` 不存在(`MapUtilsTest`/`DbMigrationsTest` 编译失败;`GeocoderHelperTest` 已随 Task 1 重写并通过)。

- [ ] **Step 4: 实现——DbMigrations 与 DB 版本升级**

新建 `app/src/main/java/com/zcshou/data/DbMigrations.java`:

```java
package com.zcshou.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.zcshou.utils.MapUtils;

import androidx.annotation.Nullable;

import com.elvishew.xlog.XLog;

/**
 * 数据库版本迁移工具(两库共享)。
 * V1→V2:CUSTOM 坐标列由 BD09 迁为 GCJ02(配合高德地图切换);WGS84 列不动。
 * 迁移正确性由 DbMigrationsTest 与 MapUtilsTest.bd09togcj02 锚定共同覆盖;
 * SQLiteOpenHelper 本体不可 JVM 实例化,DB 循环保持薄、不做分支扩展。
 */
public final class DbMigrations {

    private DbMigrations() {
    }

    /** 纯函数:V1 的 BD09 坐标字符串→GCJ02 坐标字符串;解析失败返回 null(调用方保留原值) */
    @Nullable
    public static String[] bd09ToGcj02(@Nullable String lngBd09, @Nullable String latBd09) {
        try {
            double[] gcj = MapUtils.bd09togcj02(Double.parseDouble(lngBd09), Double.parseDouble(latBd09));
            return new String[] { String.valueOf(gcj[0]), String.valueOf(gcj[1]) };
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }

    /** V1→V2:表内 CUSTOM(BD09) 坐标列整体迁为 GCJ02;坏行保留原值并记日志(行级隔离) */
    public static void migrateBd09CustomToGcj02(SQLiteDatabase db, String table, String lngColumn, String latColumn) {
        try (Cursor cursor = db.rawQuery("SELECT rowid, " + lngColumn + ", " + latColumn + " FROM " + table, null)) {
            ContentValues values = new ContentValues();
            while (cursor.moveToNext()) {
                String[] gcj = bd09ToGcj02(cursor.getString(1), cursor.getString(2));
                if (gcj == null) {
                    XLog.e("DATABASE: bd09->gcj02 skip invalid row " + cursor.getLong(0));
                    continue;
                }
                values.put(lngColumn, gcj[0]);
                values.put(latColumn, gcj[1]);
                db.update(table, values, "rowid = ?", new String[] { String.valueOf(cursor.getLong(0)) });
            }
        } catch (Exception e) {
            XLog.e("DATABASE: bd09->gcj02 migration failed");
        }
    }
}
```

两个 DB 类同改:`DB_VERSION = 1` → `DB_VERSION = 2`,`onUpgrade` 由 DROP 重建替换为:

```java
    @Override
    public void onUpgrade(SQLiteDatabase sqLiteDatabase, int oldVersion, int newVersion) {
        // V1→V2:CUSTOM 列坐标系 BD09→GCJ02(数据保留;原"DROP 重建"语义废弃)
        if (oldVersion < 2) {
            DbMigrations.migrateBd09CustomToGcj02(sqLiteDatabase, TABLE_NAME,
                    DB_COLUMN_LONGITUDE_CUSTOM, DB_COLUMN_LATITUDE_CUSTOM);
        }
    }
```

(两类的列常量名相同,均可用;`DataBaseHistorySearch` 的列常量在同包同名单。)

- [ ] **Step 5: MainActivity——prefs 坐标一次性迁移(CRLF)**

`onCreate` 中 `sharedPreferences = PreferenceManager...`(:159)之后、`initMap(savedInstanceState)` 之前插入:

```java
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
```

编辑后验证 CRLF(`file` + numstat)。

- [ ] **Step 6: 运行测试与构建**

```bash
sh gradlew assembleDebug test
```

Expected: BUILD SUCCESSFUL,全部测试通过(含三个新/重写测试类与既有契约测试)。

- [ ] **Step 7: Commit ②**

```bash
git add -A app
git commit -m "feat: migrate history coordinates bd09 to gcj02 for amap"
```

---

### Task 3: 文档收尾与全量门槛(commit ③)

**Files:**
- Modify: `AGENTS.md`

**Interfaces:** 无(纯文档)

- [ ] **Step 1: AGENTS.md 更新**

- 「仓库用途」:`基于 TestProvider 调试 API + 百度 LBS SDK(本地 jar app/libs/BaiduLBS_Android.jar)` → `基于 TestProvider 调试 API + 高德 LBS SDK(Maven 三件套 3dmap/search/location)`
- 「构建与验证」local.properties 键:`MAPS_API_KEY`/`MAPS_SAFE_CODE` → `AMAP_API_KEY`(删除"键缺失可构建,值为空串"保留语义;"CI 由 workflow 注入同名键,键名不可改"更新为 `AMAP_API_KEY`);补一句坐标系约定:**界面/历史/偏好一律 GCJ02,模拟位置写 WGS84,转换只经 `MapUtils`**
- 「已知坑」:删除百度 `SetIgnoreCacheException` 条目(方法已随 SDK 消失);新增:高德 key 绑定包名+SHA1(key 无效=地图空白/定位搜索报错,构建不拦);`Tip.point` 可空须过滤
- 「文档」节补一行:高德迁移 spec/plan 路径

- [ ] **Step 2: 全量验证门槛**

```bash
sh gradlew clean assembleDebug assembleRelease test lint
```

Expected: 四项全绿;lint 0 error 0 warning(无 baseline);release 构建验证高德 R8 keep 规则有效。

- [ ] **Step 3: Commit ③**

```bash
git add AGENTS.md
git commit -m "docs: switch AGENTS.md to amap sdk conventions"
git log --oneline -4
```

Expected: 三个迁移 commit 依次可见;工作区干净。

---

## 验证与交接(所有任务完成后)

真机冒烟(key 就绪后):出图、选点/长按地址气泡、POI 搜索建议、蓝点与方向、GO 瞬移(WGS84 生效)、历史复用与旧记录位置准确(迁移生效)、冷启动恢复、停止模拟。key 未就绪时:全部构建/测试/lint 门仍然有效,地图空白属预期。
