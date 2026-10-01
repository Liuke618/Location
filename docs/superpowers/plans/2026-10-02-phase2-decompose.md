# 阶段二:聚焦拆解 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 MainActivity(1290 行,8 种职责)拆解为 7 个单一职责协作类 + ViewModel,ServiceGo 通信接口化,历史存取 Repository 化,坐标换算抽为带全覆盖单测的纯函数;随后一次受控包归位。全程行为冻结。

**Architecture:** 原地生长(顶层包先不动,新类落子包)→ 拆完稳定后 `git mv` 归位。手工构造注入:`GoApplication` 持有 `AppContainer`(app 级:Repository、MapState);Activity 级控制器由 MainActivity 创建并传依赖。跨组件静态状态(`mMarkLatLngMap`/`mMarkName`/`mCurrentCity`/`mMapIndicator`)语义已验证为 app 级(外部调用点:HistoryActivity:341、JoyStick:513/647),由 `MapState` 单例按原语义承载。

**Tech Stack:** 纯 Java,JUnit 4 + androidx.arch.core:core-testing(InstantTaskExecutorRule,LiveData JVM 直测);不引 Robolectric/DI/Kotlin。

**Spec:** `docs/superpowers/specs/2026-10-02-phase2-decompose-design.md`(实施者需同时阅读;§4 表格中 `HistoryRepository` 吸收"全部调用面"包含 `recordCurrentLocation` 的保存动作与 `initSearchView` 内的保存/删除,本计划将其解析归属明确化)

## Global Constraints

- **行为冻结**:任何用户可见功能、交互、时序不得变化;`MainViewModel`/各控制器只重组代码路径
- 语言与依赖:纯 Java;新增测试依赖仅 `androidx.arch.core:core-testing:2.2.0`;不引入 Robolectric/DI 框架/Kotlin
- 每个任务完成后 `sh gradlew assembleDebug test` 必须绿(用 `sh gradlew`,勿 chmod);分支收尾跑 `clean assembleDebug assembleRelease test lint`
- lint 保持 0 error 0 warning(3 条既有 baseline 除外);新增 `@SuppressLint` 必须带理由注释
- CRLF 文件(MainActivity/JoyStick/WelcomeActivity 等)保持行尾;提交信息用 conventional 格式
- 每个 commit 单一职责(一个组件一个 commit);测试文件与被测类同 commit
- 分支:`refactor/decompose`(Task 1-10)→ `refactor/package-layout`(Task 11);前者全绿后才开后者
- 测试全部为 JVM 单测(`app/src/test/java/...`),不写 androidTest

---

## Task 1: `domain.LocationMath` 抽取 + 单测 + 两处调用点接入

**Files:**
- Create: `app/src/main/java/com/zcshou/domain/LocationMath.java`
- Create: `app/src/test/java/com/zcshou/domain/LocationMathTest.java`
- Modify: `app/build.gradle`(dependencies 加 core-testing)
- Modify: `app/src/main/java/com/zcshou/joystick/JoyStick.java:263-264,388-389`
- Modify: `app/src/main/java/com/zcshou/service/ServiceGo.java:161-162`(onMoveInfo 内)

**Interfaces:**
- Produces: `LocationMath.moveVectorKm(double speed, double periodSec, double ratio, double angleDeg)` → `double[]{eastKm, northKm}`;`LocationMath.lngDelta(double eastKm, double latDeg)`;`LocationMath.latDelta(double northKm)`。后续 Task 依赖这三个签名。

- [ ] **Step 1: 先写失败测试**(`LocationMathTest`,JUnit4,纯断言,无 Android 依赖):
  - `moveVectorKm`:speed=1.2、periodSec=1.0、ratio=1.0、angleDeg=0 → east=1.2/1000*1000*1.0*cos(0)=1.2km?注意现实现为 `speed * periodSec * ratio * cos / 1000 * 1000`——以 JoyStick 现值代入:speed=1.2(m/s)、periodSec=1.0、ratio=1.0(即 mR=1.0)时 eastKm=1.2*1.0*1.0*cos(0)/1000*1000… **先读现实现确认量纲**:JoyStick:263 为 `mSpeed * (DivGo/1000) * mR * cos(θ) / 1000`,DivGo=1000ms,故结果=1.2*1.0*1.0*1.0/1000=0.0012(km)。测试按此锚定:angle=0→east=0.0012、north≈0;angle=90→east≈0、north=0.0012;ratio=0.5 时减半;cos 用弧度换算(`θ*2π/360`)。
  - `lngDelta(eastKm, lat)`:lat=0 → eastKm/111.320;lat=60 → eastKm/(111.320*cos(60°))≈eastKm/55.66;`latDelta(northKm)`=northKm/110.574。
  - 负角/负 ratio 行为与原实现一致(原实现不特判)。
- [ ] **Step 2: 运行确认失败** `sh gradlew test` — Expected: 编译错误(LocationMath 不存在)
- [ ] **Step 3: 实现 `LocationMath`**(final 类 + 私有构造;三个静态方法;把 JoyStick:263-264 的注释「注意安卓中的三角函数使用的是弧度」迁入)
- [ ] **Step 4: 替换三处调用**:JoyStick 计时器 onFinish(:263-265)与 processDirection else 分支(:388-390)改为调 `moveVectorKm` 取 [0]/[1] 赋 `disLng`/`disLat`,`onMoveInfo(...90.0F-mAngle)` 原样;ServiceGo.onMoveInfo(:161-162)改为 `mCurLng += LocationMath.lngDelta(disLng, mCurLat); mCurLat += LocationMath.latDelta(disLat);`(删除原 111.320/110.574 内联式,注释保留)
- [ ] **Step 5: 验证** `sh gradlew assembleDebug test` 全绿;`git diff` 仅预期文件
- [ ] **Step 6: Commit** `refactor: extract LocationMath with unit tests`

## Task 2: `data.HistoryRepository` + 调用面替换

**Files:**
- Create: `app/src/main/java/com/zcshou/data/HistoryRepository.java`(接口 + 内嵌 `SearchRow`/`LocationRow` 值类)
- Create: `app/src/main/java/com/zcshou/data/SQLiteHistoryRepository.java`
- Create: `app/src/main/java/com/zcshou/gogogo/AppContainer.java`
- Create: `app/src/test/java/com/zcshou/data/HistoryRepositoryContractTest.java`(对 fake 的契约测试)
- Modify: `app/src/main/java/com/zcshou/gogogo/GoApplication.java`(持 AppContainer)
- Modify: `MainActivity.java`(:883-921 initStoreHistory/getSearchHistory、:924-995 recordCurrentLocation 的 4 个保存点、initSearchView 内 :1018-1085 区域的保存/删除)、`JoyStick.java`(:765-806 fetchAllRecord)
- Create: `app/src/test/java/com/zcshou/data/InMemoryHistoryRepository.java`(fake,供契约测试与后续 ViewModel 测试复用)

**Interfaces:**
- Produces: `interface HistoryRepository { List<Map<String,Object>> getSearchRows(); void addSearchRow(SearchRow r); List<Map<String,Object>> getLocationRows(); void addLocationRow(LocationRow r); }`;`SearchRow{key,description,isLocation,lngWgs,latWgs,lngCustom,latCustom,timestamp}`、`LocationRow{location,lngWgs,latWgs,timestamp,lngCustom,latCustom}`(public 字段,构造器赋值);`AppContainer.getHistoryRepository()`。读行仍返回 `List<Map<String,Object>>`(SimpleAdapter 既有键契约,键名沿用 `DataBaseHistorySearch.DB_COLUMN_*`/`HistoryActivity.KEY_*`,zero 行为差)。
- Consumes: 无(Task 1 无耦合)。

- [ ] **Step 1: 契约测试**(对 `InMemoryHistoryRepository`:add 后 get 回读、同 key 去重覆盖=现 saveHistorySearch 语义、空表返回空 list)
- [ ] **Step 2: 确认失败** → **Step 3: 实现接口/值类/fake**
- [ ] **Step 4: 实现 `SQLiteHistoryRepository`**:包住现有两个 SQLiteOpenHelper(构造器接 Context,内部 `new DataBaseHistoryLocation/Search`);写入=把 Row 转 ContentValues 调既有 static save(去重语义保留);`getLocationRows` 迁移 JoyStick fetchAllRecord 的游标循环(保留 BigDecimal 11 位舍入格式化为显示串的**展示逻辑留在 JoyStick**,repository 只回原始字段行);`getSearchRows` 迁移 MainActivity getSearchHistory 游标循环(键与值形态逐字段保持)。`AppContainer` 持有并惰性创建 repository;`GoApplication.getAppContainer()`。
- [ ] **Step 5: 替换调用面**:MainActivity 删 `mLocationHistoryDB/mSearchHistoryDB` 字段与 initStoreHistory(:883-894,try/catch 语义由 repository 构造器内保留);getSearchHistory → `getAppContainer().getHistoryRepository().getSearchRows()`;recordCurrentLocation 4 个保存点 → 构造 `LocationRow` 调 add(注意 :983-984 的连续两次 put LOCATION 为现状行为,保留同序);initSearchView 内 2 个 save + 1 个 delete → addSearchRow 与 repository 新增 `void deleteSearchRow(String key)`(加进接口与 fake);JoyStick fetchAllRecord 游标循环换 `getLocationRows()`(展示格式化代码原位保留)。**JoyStick:513/647 的 MainActivity.mCurrentCity/mMapIndicator 本任务不动**(Task 4 处理)。
- [ ] **Step 6: 验证** 构建绿 + 契约测试绿;`grep -rn "DataBaseHistory" app/src/main/java` 仅剩 data 包与 HistoryActivity?——HistoryActivity:341 区域若直用 DB 类属既有行为,本任务**不改 HistoryActivity**(记录到报告,Task 9/11 处理)
- [ ] **Step 7: Commit** `refactor: introduce HistoryRepository and replace db call sites`

## Task 3: `service.MockLocationController` 接口化

**Files:**
- Create: `app/src/main/java/com/zcshou/service/MockLocationController.java`
- Create: `app/src/main/java/com/zcshou/service/ServiceGoController.java`
- Modify: `MainActivity.java`(:148-149 字段、:204-216 ServiceConnection、:795-815 start/stop、:817-880 doGoLocation)

**Interfaces:**
- Produces: `interface MockLocationController { void start(double lng,double lat,double alt); void stop(); void setPosition(double lng,double lat,double alt); boolean isRunning(); }`;`ServiceGoController(Context activityContext)` 实现之(内部持 ServiceConnection/ServiceGoBinder/isMockServStart)。
- Consumes: Task 2 的 AppContainer(不需要)。

- [ ] **Step 1: 定义接口 + 实现**:ServiceGoController 把 `startGoLocation`(:795-808,bindService+putExtra+startForegroundService+置 true)、`stopGoLocation`(:810-815)、`mServiceBinder.setPosition`(:843)原样迁入;setPosition 在 binder 未连时记 XLog 并忽略(现状 mServiceBinder 为 null 会 NPE——保持行为**不变**:若现状可 NPE,则照搬可 NPE?不行——行为冻结指用户可见行为,防御性空判断不改变可见行为,允许;注释说明);`isRunning()` 返回 isMockServStart。
- [ ] **Step 2: MainActivity 接线**:onCreate 创建 `mMockController = new ServiceGoController(this)`;doGoLocation 内 `isMockServStart`→`mMockController.isRunning()`、`mServiceBinder.setPosition`→`mMockController.setPosition`;删除 mServiceBinder/mConnection/isMockServStart 字段。LNG/ALT_MSG_ID 常量与 MapUtils.bd2wgs 调用随迁移(ServiceGo onStartCommand 读的是 Intent extra,键名常量留在 MainActivity 并被 controller 引用,或迁至 controller——**迁至 ServiceGoController**,ServiceGo 改引 controller 常量,保持 extra 键字符串不变)。
- [ ] **Step 3: 验证** 构建绿;行为自查:start→传送→stop 流程代码路径逐条比对
- [ ] **Step 4: Commit** `refactor: introduce MockLocationController interface`

## Task 4: `map.MapState`(app 级)+ `map.MapController`

**Files:**
- Create: `app/src/main/java/com/zcshou/gogogo/map/MapState.java`(注意:本阶段新包先落 `gogogo` 下,归位在 Task 11)
- Create: `app/src/main/java/com/zcshou/gogogo/map/MapController.java`
- Modify: `MainActivity.java`(地图区 :126-134 字段、:462-536 initMap、:668-743 initMapButton、:744-766 markMap/resetMap)、`HistoryActivity.java:341`、`JoyStick.java:513,647`、`AppContainer`

**Interfaces:**
- Produces: `MapState`(AppContainer 单例):`LatLng getSelectedPoint()/setSelectedPoint(LatLng)`、`String getMarkName()/setMarkName(String)`、`String getCurrentCity()`、`BitmapDescriptor getMarkerIcon()`、`void clearSelectedPoint()`——**保持原 static 可空语义**(selectedPoint 可 null)。`MapController(MapView, BaiduMap, MapState, listener)`:方法 `init()/markMap()/resetMap(double curLat,double curLon,float dir)/showLocation(name,bd09Lng,bd09Lat)→boolean`;点击事件经 `MapEventListener{onMapClick(lng,lat),onMapLongClick,onMapDoubleClick,onPoiClick}` 回调 MainActivity(现有 :477-536 监听体逻辑原样移入回调实现,含 Geocoder 触发点——Geocoder 本任务仍在 MainActivity,回调里调 `mGeoCoder` 原样,Task 5 再收编)。
- Consumes: Task 2 AppContainer。

- [ ] **Step 1: MapState + AppContainer 注册**;MainActivity 的 4 个 static 字段改读写 MapState(所有 `mMarkLatLngMap`/`mMarkName`/`mCurrentCity`/`mMapIndicator` 引用点逐个替换;`mBaiduMap`/`mMapView` 归 MapController)
- [ ] **Step 2: MapController 建立**,initMap 的监听体(:477-536)按上述回调拆分;MainActivity 持 MapController 并实现 MapEventListener(逻辑原样)
- [ ] **Step 3: 外部调用点接线**:HistoryActivity:341 `MainActivity.showLocation(...)` → 需拿到 MapController——**保持静态门面**:`MainActivity` 保留 `public static boolean showLocation(...)`,内部委托到静态持有的当前 controller 引用(onCreate 赋值/onDestroy 清空, null 时返回 false 并 XLog——与现状 mBaiduMap null 时 catch 返回 false 语义一致);JoyStick:513 `MainActivity.mCurrentCity` → `AppContainer` 经 context 取(application context cast)、:647 `mMapIndicator` → `MapState.getMarkerIcon()`
- [ ] **Step 4: 验证** 构建绿 + `grep -n "static" app/src/main/java/com/zcshou/gogogo/MainActivity.java` 复核仅剩门面与常量
- [ ] **Step 5: Commit** `refactor: extract MapState and MapController`

## Task 5: `map.GeocoderHelper`(SDK 编解码 + REST 逆地理)

**Files:**
- Create: `app/src/main/java/com/zcshou/gogogo/map/GeocoderHelper.java`
- Create: `app/src/test/java/com/zcshou/gogogo/map/GeocoderHelperTest.java`(纯函数)
- Modify: `MainActivity.java`(:134 mGeoCoder、:538-543、:528-536 内 Geocoder 触发点、:924-995 recordCurrentLocation)

**Interfaces:**
- Produces: `GeocoderHelper.okhttpReverseGeocode(...)` 拆两层:`static String parseAddress(String json, String fallbackName, String defaultMessage, String defaultName)`(纯函数,覆盖 :957-991 的三分支:status==0→formatted_address;status!=0→message/mMarkName;JSONException→defaultName,保留 :983-984 连写行为)+ 实例方法 `resolveAddress(double lng,double lat,String ak,String safeCode,AddressCallback cb)`(OkHttp,回调回 UI 线程由调用方处理——**保持现状线程模型**:现状在回调线程直接写 DB,继续);SDK GeoCoder 回调封装 `reverseBySdk(LatLng)`。
- Consumes: Task 2/4。

- [ ] **Step 1: 先写 parseAddress 失败测试**(三分支 + null body 行为)→ 实现 → 测试绿
- [ ] **Step 2: recordCurrentLocation 收编**(:924-995 → MainActivity 留 ~25 行:组 URL 参数调 resolveAddress,回调内构造 LocationRow 调 repository;ContentValues/JSON 全部移入 helper;`mOkHttpClient` 字段移入 helper)
- [ ] **Step 3: SDK GeoCoder 迁移**(:528-536、:538-543 移入,回调输出接口化,MainActivity 实现接收)
- [ ] **Step 4: 验证 + Commit** `refactor: extract GeocoderHelper with tested address parsing`

## Task 6: `location.CurrentLocationSource`

**Files:**
- Create: `app/src/main/java/com/zcshou/gogogo/location/CurrentLocationSource.java`
- Modify: `MainActivity.java`(:143-147 字段、:571-666 initMapLocation/getLocationClientOption、:236-251 onPause/onResume/onStop 中的 mLocClient 调用)

**Interfaces:**
- Produces: `CurrentLocationSource(LocationClient 回调监听)`:方法 `init()/start()/stop()`;监听接口 `LocationListener{onReceiveLocation(BDLocation), onDiagnostic(int,int,String)}`;`getLocationClientOption` 静态方法原样迁入。`mCurrentLat/Lon/Direction/isFirstLoc` 状态迁移:**保留在 MainActivity**(它们被 resetMap/renderUses 使用,是界面显示状态;Task 9 评估是否入 ViewModel,本任务只搬运初始化与回调接线)。
- [ ] **Step 1-3: 迁移 + MainActivity 实现监听(回调体 :577-636 原样)+ 生命周期接线(onPause/onResume/onStop 原调用改 source.start/stop)+ 验证 + Commit** `refactor: extract CurrentLocationSource`

## Task 7: `search.PoiSearchHelper`

**Files:**
- Create: `app/src/main/java/com/zcshou/gogogo/search/PoiSearchHelper.java`
- Modify: `MainActivity.java`(:156-162 字段、:282-391 菜单 SearchView、:998-1155 initSearchView/getMapList)

**Interfaces:**
- Produces: `PoiSearchHelper(HistoryRepository, 回调)`: absorb SuggestionSearch 初始化、SuggestionResult→List<Map> 转换(`getMapList`:1138 原样)、suggestion 点击保存(:1018-1085 的 addSearchRow 路径)、查询历史展示(onMenuItemActionExpand :295-331 → helper 方法 `loadSearchHistoryRows()` 由 Activity 调用后渲染——ListView/SimpleAdapter 属 UI,留在 MainActivity,helper 只供数据)。SearchView 菜单交互(onQueryTextSubmit/Change :332-391)UI 部分留 MainActivity,查询下发走 helper。
- Consumes: Task 2。
- [ ] **Step 1-3: 迁移(逐块对照 move-list)+ 构建绿 + 行为自查(搜索→suggest→点选→保存→历史展示全链)+ Commit** `refactor: extract PoiSearchHelper`

## Task 8: `update.UpdateManager`

**Files:**
- Create: `app/src/main/java/com/zcshou/gogogo/update/UpdateManager.java`
- Create: `app/src/test/java/com/zcshou/gogogo/update/UpdateManagerTest.java`
- Modify: `MainActivity.java`(:163-167 字段、:1158-1289 全部)

**Interfaces:**
- Produces: `static UpdateInfo parseRelease(String json)`(纯函数:提取 name/created_at/target_commitish/body/assets[0].browser_download_url/name,JSONException 抛出由调用方处理——保持现状 catch+XLog);实例:`init(Activity)`(receiver 注册,原样含 NOT_EXPORTED 注释)、`checkUpdate(boolean manualToast)`(HTTP+runOnUiThread+dialog 组装原样迁入;`curVersion` 判断逻辑保留 :1194-1196 的 contains 写法**不改**为 compareVersion——行为冻结)、`download(url)`、`install()`。`UpdateInfo` 为 UpdateManager 内嵌 public static 值类。
- [ ] **Step 1: parseRelease 失败测试(合法 JSON/assets 空数组/缺字段)** → 实现 → 绿
- [ ] **Step 2: 其余原样迁入 + MainActivity 只留 `mUpdateManager.init(this); mUpdateManager.checkUpdate(false);` + 验证 + Commit** `refactor: extract UpdateManager with tested release parsing`

## Task 9: `MainViewModel` + MainActivity 收尾

**Files:**
- Create: `app/src/main/java/com/zcshou/gogogo/MainViewModel.java`
- Create: `app/src/test/java/com/zcshou/gogogo/MainViewModelTest.java`(core-testing InstantTaskExecutorRule)
- Modify: `app/build.gradle`(若无:core-testing 已在 Task 1 加)、`MainActivity.java`(状态字段收编)

**Interfaces:**
- Produces: `MainViewModel` 持:`LiveData<LatLng> selectedPoint`(桥接 MapState,含 clear)、`LiveData<Boolean> mockRunning`(桥接 MockLocationController 状态,由 controller 回调/Activity 通知)、`LiveData<String> currentAddress`(GeocoderHelper 回调)、`LiveData<List<Map<String,Object>>> searchResults`(PoiSearchHelper 回调)。Factory 构造注入依赖。Activity observe 渲染,原渲染代码原样进 observer。
- [ ] **Step 1: ViewModel 单测**(setValue 后 getValue 断言、桥接语义)→ **Step 2: 实现 + Activity 接线**(只重组,不改渲染逻辑)→ **Step 3: 行数自查** `wc -l MainActivity.java`(目标 ≤~350;超出则列明剩余职责属装配/菜单,报告说明)→ **Step 4: 全量** `sh gradlew clean assembleDebug assembleRelease test lint` 绿 → **Commit** `refactor: introduce MainViewModel and slim MainActivity`

## Task 10: 分支收尾(行为冻结自查 + 全量验证)

- [ ] `sh gradlew clean assembleDebug assembleRelease test lint` 全绿;lint 0/0(新增抑制需理由)
- [ ] 行为自查清单(对照 spec §10 不变量,逐条在代码路径层核对并写入报告):启动流/地图四类点击/搜索链/选点模拟三态/摇杆(两种类型+移动)/通知按钮/设置/历史/更新弹窗
- [ ] `git log --oneline origin/master..HEAD` 核对 commit 划分与消息
- [ ] 无新 commit(验证型任务)

## Task 11: 分支 `refactor/package-layout` 包归位

**Files:** 全部 `git mv`;Modify: `app/src/main/AndroidManifest.xml`(类名)、`app/build.gradle`(若 namespace 无影响则不改)、各文件 package 行与 import

- [ ] **Step 1: 开分支** `git checkout -b refactor/package-layout`(自 refactor/decompose)
- [ ] **Step 2: 按.spec §8 目标树 `git mv`**:gogogo→{留根 GoApplication/BaseActivity/AppContainer, ui.map(含 MainViewModel), ui.search, ui.location, ui.update, ui.settings, ui.history};database→data;domain/service/joystick/utils 已就位。**每个文件只改 package 声明与受影响 import,零逻辑修改**
- [ ] **Step 3: manifest 四个 activity/service 类名同步**(WelcomeActivity/MainActivity/SettingsActivity/HistoryActivity/ServiceGo)
- [ ] **Step 4: 全量** `sh gradlew clean assembleDebug assembleRelease test lint` 绿(测试的 package 同步移动)
- [ ] **Step 5: Commit** `refactor: reorganize packages into ui/data/domain layout`(单 commit,纯移动)
- [ ] **Step 6: `git diff refactor/decompose refactor/package-layout --stat` 核对:仅 rename + package/import/manifest 行**
