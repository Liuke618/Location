# 百度 LBS → 高德开放平台迁移 — 设计文档

- 日期:2026-10-03
- 状态:已批准(经用户逐项确认:逆地理=SDK 单 key、历史数据=一次性迁移、策略=一步到位整体替换)
- 范围:地图渲染 / POI 建议搜索 / 逆地理 / 真实定位蓝点 / 坐标管线 / 键注入 / 历史数据,全部从百度 LBS SDK 切换到高德 Android SDK
- 关联:`docs/superpowers/specs/2026-10-02-phase2-decompose-design.md`(现包结构来源)、`2026-10-03-remove-joystick-design.md`(迁移基于摇杆移除后的代码形态)

## 1. 背景与动机

用户决定弃用百度 LBS SDK(本地 jar,签名 keystore 明文提交等历史包袱),整体迁移到高德开放平台:Maven 依赖、个人开发者免费配额充足、数据质量同级。虚拟定位核心(TestProvider 写 WGS84)是系统 API,与地图 SDK 无关,不在迁移面内。

## 2. 目标

1. 地图渲染、选点/长按/POI 点击、标记与地址气泡、真实定位蓝点全部工作在高德 SDK 上。
2. POI 建议搜索换 `Inputtips`,逆地理收敛为 SDK 单通道 `GeocodeSearch`(单 Android key)。
3. 坐标管线全线 BD09 → GCJ02;GO 流程改 `gcj2wgs`;补 `wgs2gcj`。
4. 历史库 BD09 列与"上次位置记忆"偏好一次性迁移为 GCJ02。
5. 键注入简化为单键 `AMAP_API_KEY`(manifestPlaceholder),CI 三个 workflow 同步。
6. 全量验证门槛四项全绿,lint 保持 0 error 0 warning。

## 3. 非目标

- 不做双 SDK 并存/灰度开关。
- 不动 `ServiceGo`/`MockLocationController`/TestProvider 供给逻辑(纯系统 API)。
- 不动历史页交互结构、随机偏移语义(偏移量单位为米,与坐标系无关)、更新/欢迎流程。
- 不申请 Web 服务类型 key(REST 逆地理通道随 SDK 化删除)。

## 4. 决策记录

| 决策 | 选择 | 理由 |
|---|---|---|
| 逆地理实现 | SDK `GeocodeSearch` 单 key | 删 OkHttp REST 通道与 `MAPS_SAFE_CODE` 概念;search SDK 因 POI 搜索必然引入,边际成本为零;个人应用不需要双 key |
| 历史 BD09 列 | DB 版本 +1 一次性迁移 | `bd09togcj02` 公式精确(可逆),旧记录在新图上位置准确 |
| 迁移策略 | 一步到位整体替换 | 全部 UI 类依赖百度类型,无法按编译闭包分步;并存灰度使包体翻倍,个人工具无必要 |

## 5. 依赖与键注入

### 5.1 依赖

- 删除 `implementation files('libs/BaiduLBS_Android.jar')` 与 jar 文件(包体 -7MB)。
- 新增(Maven Central,当前稳定):
  - `com.amap.api:3dmap:10.0.600`(地图)
  - `com.amap.api:search:9.7.1`(POI/逆地理)
  - `com.amap.api:location:11.3.000`(定位)
- lint baseline:删除 `app/lint-baseline.xml` 中 3 条百度 jar 豁免;若清空后无豁免条目则连文件与 `lint { baseline = ... }` 配置一并移除,以 lint 实际输出为准。

### 5.2 键注入

- `local.properties` 键:`MAPS_API_KEY`/`MAPS_SAFE_CODE` → **`AMAP_API_KEY`**(单键;缺失可构建=空串,运行时地图空白,沿用现状模式,构建告警文案同步)。
- 注入方式:BuildConfig 双字段 → `manifestPlaceholders = [AMAP_API_KEY: "..."]`,manifest 增:
  ```xml
  <meta-data android:name="com.amap.api.v2.apikey" android:value="${AMAP_API_KEY}" />
  ```
- `buildConfigField` 的 `MAPS_API_KEY`/`MAPS_SAFE_CODE` 与 `GeocoderHelper` 的 ak/safeCode 参数链路删除。
- CI:`build-check.yml` / `build-release.yml` / `codeql-analysis.yml` 中 `MAPS_API_KEY=...`/`MAPS_SAFE_CODE=...` 两行 → `AMAP_API_KEY=${{ secrets.AMAP_API_KEY }}`(secrets 由用户在 GitHub 配置)。

### 5.3 用户待办(key 申请)

1. [lbs.amap.com](https://lbs.amap.com) 注册并完成个人开发者认证。
2. 控制台创建 **Android 平台** key:发布版安全码 SHA1 `32:2F:E6:05:6C:2F:11:6D:CD:ED:AC:FF:23:02:5A:29:68:FD:45:D2`(仓库 keystore debug/release 通用),包名 `com.zcshou.gogogo`。
3. 本地写 `local.properties` 的 `AMAP_API_KEY`;GitHub 仓库 secrets 新建 `AMAP_API_KEY`(旧的 `MAPS_API_KEY`/`MAPS_SAFE_CODE` 可删)。

## 6. 初始化与隐私合规

`GoApplication.onCreate` 百度四行(`SDKInitializer.setAgreePrivacy`、`LocationClient.setAgreePrivacy`、`setApiKey`、`initialize`、`setCoordType`)替换为高德隐私接口(**必须先于一切 SDK 使用**):

```java
MapsInitializer.updatePrivacyShow(this, true, true);
MapsInitializer.updatePrivacyAgree(this, true);
AMapLocationClient.updatePrivacyShow(this, true, true);
AMapLocationClient.updatePrivacyAgree(this, true);
```

与百度现状同级(Application 级同意,在应用自身隐私协议流程之外);key 经 meta-data 读取,无需代码注入。

## 7. 组件替换映射

| 组件 | 现状(百度) | 迁移后(高德) |
|---|---|---|
| `main_map.xml` | `com.baidu.mapapi.map.MapView`(id `bdMapView`) | `com.amap.api.maps.MapView`(id `amapView`) |
| `MapController` | `BaiduMap`/`MarkerOptions`/`MapStatusUpdateFactory`/`MyLocationData` | `AMap`/`Marker`/`CameraUpdateFactory`/`MyLocationStyle`;点击/长按/POI 点击三回调经 `MapEventListener` 不变;**`onMapDoubleClick` 删除**(高德内置双击缩放手势),接口缩为 3 回调;长按地址气泡改 `InfoWindowAdapter`(复用 `location_poi_info` 布局)+ `marker.showInfoWindow()`;蓝点:`setMyLocationEnabled(true)` + `MyLocationStyle.myLocationType(LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER)`(显示蓝点带方向、视野不跟随,与现状一致)+ `setLocationSource(...)` 桥接 |
| `CurrentLocationSource` | 百度 `LocationClient`/`BDAbstractLocationListener`/`LocationClientOption`(`setCoorType("bd09ll")`) | `AMapLocationClient`/`AMapLocationListener`/`AMapLocationClientOption`(高德定位原生返回 GCJ02);类实现 AMap `LocationSource`,把 `AMapLocation` 转 `android.location.Location`(带传感器方向 bearing)喂给 `OnLocationChangedListener`;诊断回调简化为 `getErrorCode()/getErrorInfo()`;`LocationListener` 接口的 `BDLocation` 参数换 `AMapLocation` |
| `PoiSearchHelper` | `SuggestionSearch`/`SuggestionResult` | `Inputtips`/`InputtipsQuery(keyword, city)`;`Tip.getName()/getAddress()/getPoint()`(point 可空,空则跳过该行);行键契约(`POI_*`)不变 |
| `GeocoderHelper` | SDK `GeoCoder` + OkHttp REST(api.map.baidu.com reverse_geocoding v3,ak+mcode) | 单通道 `GeocodeSearch`:`initSdk(listener)` → 构造 `GeocodeSearch` + `OnGeocodeSearchListener`;`reverseBySdk` → `getFromLocationAsyn(new ReGeocodeQuery(LatLonPoint, 200, GeocodeSearch.AMAP))`;`resolveAddress(lng, lat, cb)`(SDK 内部网络,单 Android key);**回调线程为 SDK 主线程回调,单行落库在回调内完成——原 OkHttp 后台线程模型不保留**(轻量单行插入,可接受);地址提取三分支收敛为纯函数 `extractAddress(rCode, formatAddress, markName, defaultName)`(纯字符串入参,规避 SDK POJO 的 JVM 可测性问题),JVM 测试重锚定 |
| `MapUtils` | `bd2wgs`/`wgs2bd09`/`bd09togcj02` | 新增 `wgs2gcj`(自现 `wgs2bd09` 拆出第一段,`wgs2bd09` 随之删除);`gcj02towgs84` 保留;`bd2wgs` 删除;**`bd09togcj02` 保留**(§9 数据迁移的升级路径永久依赖它,不随业务层退役) |
| 手动输入对话框 | "BD09 坐标系 / GPS 坐标系"单选(`input_position_baidu`) | "高德(GCJ02)/ GPS(WGS84)"(键改 `input_position_gcj`);GPS 分支换 `wgs2gcj` |
| `FragmentSettings` | 死 import `SDKInitializer` | 删除 |
| 历史回传 | HistoryActivity 从 TextView 文本解析 BD09 | 结构不变,语义换 GCJ02 |

附带:`MapState`/`PoiSearchHelper`/`MainViewModel`/`MainActivity` 的 `com.baidu.mapapi.model.LatLng` 全部换 `com.amap.api.maps.model.LatLng`(构造同为纬度在前);高德 LatLng 无 `toString()` 地址语义,剪贴板分享处用经纬度拼接替代。

## 8. 坐标管线(迁移后)

```
地图交互(点击/长按/POI/搜索建议/历史回传)  → GCJ02(selectedPoint)
GO 流程:  gcj2wgs(selectedPoint)          → TestProvider(WGS84)
真实蓝点:  AMapLocation(GCJ02 原生)        → 蓝点渲染(无转换)
冷启动恢复/上次位置记忆(prefs)             → GCJ02
历史库:    WGS84 列(落库时 gcj2wgs) + GCJ02 列(显示/复用)
```

## 9. 数据迁移(一次付清)

1. **SQLite**:两个库 `DataBaseHistoryLocation` 与 `DataBaseHistorySearch`(搜索历史保存 POI 点选时同样存坐标)**版本均 1→2**;`onUpgrade` 中逐行读取 CUSTOM 列(即 BD09)→ 转换 → 回写(Java 层计算,非 SQL);WGS84 列不动;两库迁移共用 **`data/DbMigrations`** 工具类。注意:现 `onUpgrade` 是"DROP 重建"(丢数据),必须重写为真迁移,不得沿用。
2. **上次位置记忆**:`setting_last_lat`/`setting_last_lng` 存的是 BD09;以一次性标记偏好 `setting_coord_migrated`(bool,默认 false)守卫:首次启动执行 BD09→GCJ02 转换并置位。与 DB 迁移同 commit。
3. 迁移代码不设降级路径;转换函数为精确公式,失败仅可能因存量数据非法(逐行 try-catch,坏行保留原值并 XLog 记录)。

## 10. 测试策略

- **新增 JVM 测试**(`app/src/test` 镜像包,禁 Robolectric):
  - `MapUtilsTest`:`gcj02towgs84`/`wgs2gcj` 往返锚定(北京天安门、济南泉城广场已知锚点,容差 1e-5 度量级);`bd09togcj02` 锚定(迁移代码永久复用它,测试保护迁移正确性)。
  - `DbMigrationsTest`:`bd09ToGcj02` 纯函数(合法锚定值 + 非法串返回 null)。
  - `GeocoderHelperTest` 重写:`extractAddress` 三分支(成功/业务失败/输入非法),纯字符串入参(规避 org.json stub 与 SDK POJO 的 JVM 风险)。
  - **不可行项说明**:原计划的"契约测试补 DB 迁移用例"不可行——`SQLiteOpenHelper` 无法在 JVM 实例化且禁 Robolectric;迁移正确性由 `bd09togcj02` 锚定测试 + `DbMigrations` 纯函数测试共同覆盖,DB 循环本身保持薄。
- MainViewModel/UpdateManager 既有测试不动。

## 11. 提交划分(编译闭包约束下的最小分步)

| Commit | 内容 | 理由 |
|---|---|---|
| ① | 依赖替换(jar→Maven 三件套)+ 键注入改造 + manifest + GoApplication + 全部七处组件代码替换 + MapUtils 改造 + 字符串/布局调整 + lint baseline 清理 | 全部 UI 类依赖百度类型,依赖一换必须一次替换完才能编译 |
| ② | 数据迁移(DB 版本+1、prefs 迁移与标记)+ MapUtils bd 系函数删除 + 新增/重写 JVM 测试 | 迁移与测试独立可 review;依赖①的 `wgs2gcj` 等产出 |
| ③ | AGENTS.md 键名与 SDK 描述更新 + 全量门槛 | 文档随代码定稿 |

## 12. 验证门槛

1. 每 commit:`sh gradlew assembleDebug test`。
2. 最终:`sh gradlew clean assembleDebug assembleRelease test lint` 四项全绿;lint 0 error 0 warning(无 baseline 豁免)。
3. 真机冒烟(key 未就绪时地图空白属预期,key 配好后复测):出图、选点/长按地址气泡、POI 搜索出建议、蓝点显示与方向、GO 瞬移(游戏/其他 App 读到 WGS84)、历史复用位置准确、旧历史记录位置准确(迁移生效)、冷启动恢复上次位置、停止模拟恢复真实定位。

## 13. 风险与注意

- **key 无效/SHA1 不匹配 → 地图空白且定位/搜索报 error**:构建不拦,属配置问题;冒烟时先验证出图。
- **`Tip.point` 可空**:POI 建议行跳过空 point,不留死行。
- **高德与百度 InfoWindow 形态差异**:百度 InfoWindow 直接挂 View,高德经 `InfoWindowAdapter` 挂 Marker;气泡偏移参数按高德 API 重调,允许像素级差异。
- **双击行为差异**:百度靠回调主动 zoomIn,高德手势内置;功能等价,无迁移动作。
- **org.json stub 坑**:新 `parseAddress` 测试沿用字符串形值锚定纪律。
- **CRLF**:MainActivity/FragmentSettings 编辑保持行尾(延续既有纪律)。
