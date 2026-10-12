# GoGoGo(任意门)业务梳理

> 本文以 2026-10-11 工作区实际代码为准,逐文件核对源码后整理;描述的是"代码现在怎么做",而非计划文本。后续改动请同步维护本文。

## 1. 应用定位

GoGoGo(任意门)是一款 **Android 免 Root 虚拟定位 App**:用户在高德地图上选一个点(点选/搜索/手动输入坐标),应用通过系统 `LocationManager` 的 TestProvider 调试 API,以 100ms 周期持续向 GPS 与 NETWORK 两个位置供给源写入该点的 WGS84 坐标,使其他 App 读到伪造的"当前位置"。只做静态瞬移(一键 GO / 运行中传送),不模拟移动轨迹。

- 技术形态:纯 Java 单模块 `:app`,minSdk 31 / targetSdk 36,LBS 能力全部来自高德 3dmap-location-search 组合 SDK。
- 入口链:WelcomeActivity(LAUNCHER,协议门控)→ MainActivity(地图选点主界面)→ ServiceGo(模拟定位前台服务)。
- 包结构:`com.yybb.location` 根包(组合根 AppContainer)+ `ui.map / ui.search / ui.location / ui.history / ui.favorite / ui.update / ui.settings / ui.WelcomeActivity` + `data`(仓库接口与 SQLite)+ `service`(模拟服务)+ `utils`。

## 2. 业务总览

| # | 业务域 | 用户入口 | 核心组件 | 数据落点 |
|---|--------|----------|----------|----------|
| 1 | 协议与隐私同意 | 启动欢迎页 | WelcomeActivity、GoApplication | SharedPreferences 同意位 |
| 2 | 地图选点 | 主界面地图 | MainActivity、MapController、MapState | MapState(AppContainer 单例) |
| 3 | POI 搜索 + 搜索历史 | 主界面搜索框 | PoiSearchHelper | HistorySearch.db |
| 4 | 逆地理编码 | 长按地图 / 保存 / 收藏 | GeocoderHelper | (不落库,供取名) |
| 5 | 模拟定位 | GO 悬浮按钮 | MockLocationController→ServiceGoController→ServiceGo | 系统位置供给(不落库) |
| 6 | 定位历史 | 信息窗"保存"、GO 生效、侧边栏 | MainActivity、HistoryActivity | HistoryLocation.db |
| 7 | 收藏地点 | 信息窗"★"、历史页长按、侧边栏 | MainActivity、FavoriteActivity | FavoriteLocation.db + 默认收藏偏好 |
| 8 | 应用内更新 | 启动自动检测、侧边栏 | UpdateManager | Updates/ 目录 APK |
| 9 | 设置 | 侧边栏 | SettingsActivity、FragmentSettings | 默认 SharedPreferences |
| 10 | 日志与反馈 | 侧边栏 | GoApplication(XLog)、ShareUtils | Logs/GoGoGo.log |

## 3. 协议与隐私同意(启动门控)

**目标**:用户必须先同意自有《用户协议》与《隐私政策》,高德三套 SDK(地图/定位/搜索)才获得隐私授权;不同意则进不了主界面,任何 LBS 能力都不可达。

- **GoApplication.onCreate**:`updatePrivacyShow` 对地图(`MapsInitializer`)、定位(`AMapLocationClient`)、搜索(`ServiceSettings`)三套接口**无条件下发**;`updatePrivacyAgree` 只按持久化同意位决定(经 `applyAmapPrivacyAgreement`,两键 `KEY_ACCEPT_AGREEMENT`/`KEY_ACCEPT_PRIVACY` 同真才下发)。三套接口互相独立,漏调对应能力运行时报错(搜索=1907)。
- **WelcomeActivity 流程**:
  1. 读同意位,已双双同意则直接勾选并跳过弹窗;
  2. 勾选复选框时若未同意完,自动**依序**弹出协议弹窗→隐私弹窗(均不可点外部取消);取消勾选把两个同意位重置为 false;
  3. 弹窗点"同意/拒绝"即落盘同意位;双双同意后**立即**补发高德 `updatePrivacyAgree`(覆盖进程首启场景);
  4. 点"进入应用":勾选校验 → 网络可用校验 → GPS 开启校验 → 运行时权限(仅申请精确定位 FINE/COARSE;Android 13+ 通知权限单独申请且**不参与门控**)→ 进入 MainActivity。
- 同意位的 SharedPreferences 文件名沿用旧值(`KEY_ACCEPT_AGREEMENT`),改名等于让老用户重新同意。

## 4. 地图选点(主界面核心)

**组件**:`MainActivity`(事件回调与 UI)+ `MapController`(持有 MapView/AMap,注册点击监听、加标记、移相机)+ `MapState`(跨页面共享单例:选中点/标记名/当前城市/标记图标)。

- **选点途径**:
  - 单击地图空白处 / 单击地图上任意 POI 建筑点 → 选中该坐标并上标记;
  - **长按**地图 → 选中并上标记,同时发起逆地理,成功后在选中标记上方弹自定义信息窗;
  - 手动输入坐标对话框(GPS 图标):经度 ±180 / 纬度 ±90 范围校验,可选 GCJ02 直用或 WGS84(经 `MapUtils.wgs2gcj` 换算)入图,标记名固定"手动输入的坐标";
  - 搜索建议点选、历史/收藏页点击行回填(见对应业务);
  - 冷启动恢复(见下)。
- **地图功能键**:普通/卫星图层切换、放大/缩小、回到当前位置(清覆盖物+清选中点+移相机到当前蓝点;仅"定位自己的位置"开关开启时显示)。
- **蓝点(真实位置,受设置项"定位自己的位置"控制,默认关)**:开关**开启**时,`CurrentLocationSource` 作为 AMap `LocationSource` 桥接高德定位客户端——高精度模式、1000ms 连续定位、需要地址(用于取**当前城市**,供搜索限定范围);罗盘传感器(加速度+地磁)方向注入 `bearing`,蓝点随设备朝向旋转;首次定位成功后自动选中当前位置并缩放到 18 级。开关**关闭(默认)**:定位图层不激活(`MapController.init` 只接线数据源,启用与否由持有者经 `setMyLocationEnabled` 决定)——不创建定位客户端、不发起任何真实定位请求、无蓝点,首次定位自动选中亦无从触发;"回到当前位置"按钮隐藏;`MainActivity.onResume` 每次重读该偏好,设置页返回即时生效。关闭时定位回调拿不到当前城市,搜索建议(Inputtips)按空城市=全国范围发起。开关切回开启时定位源重复激活,`CurrentLocationSource` 复用既有客户端避免泄漏。
- **信息窗**(长按后弹出,复用 `location_poi_info` 布局):地址文本、经纬度、五个按钮——
  - **保存**:落定位历史(见 §7),带成败 Toast;
  - **复制**:选中点 `toString` 入剪贴板;
  - **分享**:经纬度文本经系统分享;
  - **传送**:等价于 GO 按钮的 `doGoLocation`;
  - **★收藏**:落收藏表(见 §8)。
- **冷启动恢复**(`onCreate`,仅 `savedInstanceState == null` 时):**默认收藏优先**——已设默认收藏则直接选中该点上标记;否则读偏好 `setting_last_lat/lng`(上次模拟位置,GCJ02),选中并移相机。首次使用无存值则保持地图默认位置("定位自己的位置"默认关,不再等待首次定位自动缩放;开启开关后随首次定位缩放)。V1 老版本的"上次位置"是 BD09,经 `setting_coord_migrated` 标记一次性迁为 GCJ02。
- **GO 按钮状态机**(`doGoLocation`,信息窗"传送"同路径):
  1. 前置校验:网络可用 → GPS 开启(否则分别提示/弹引导);
  2. **未在模拟**:先探测"开发者选项→模拟位置信息应用"授权(`GoUtils.isAllowMockLocation`,实际 addTestProvider 试探),未授权弹引导框;选中点为空时尝试选**默认收藏**,仍无则提示"请先点击地图位置或者搜索位置";然后取选中点 `gcj02towgs84` 换算 + 偏好海拔(`setting_altitude`,非法回退 55.0)调用 `start()`——**start 返回 false(权限缺失/服务未起)时不动任何界面状态**,避免制造"已在模拟"假象;成功后:落定位历史(失败仅日志)、存上次位置、清覆盖物与选中点、按钮换"传送"图标,若 WiFi 开着再弹"定位闪回"警告;
  3. **在模拟中**:选中点为空 → `stop()` 结束模拟(按钮复位);有选中点 → `setPosition()` 传送到新位置,返回 false(服务未连上)则提示并不清状态,成功则同 2 的收尾动作。
- **生命周期约定**:MainActivity 声明 `configChanges`(orientation|screenSize|…)旋转不重建——重建路径会走 `onDestroy → mMockController.stop()`,转屏即静默停模拟;`onDestroy` 时若模拟在跑则停服务,并停定位、关蓝点、销毁地图。返回键=退后台(`moveTaskToBack`),模拟继续。
- **静态门面**:`MainActivity.showLocation(name, lng, lat)` 委托当前 MapController,供历史/收藏页把行内坐标回填主界面选中点(参数语义已是 GCJ02,`bd09` 命名为历史遗留)。
- **MainViewModel**:4 个 LiveData(选中点/mockRunning/当前地址/搜索结果)目前只是状态镜像,**无生产观察者**——渲染仍走"具体流程"直接调用(行为冻结);`mockRunning` 在 onDestroy 路径存在陈旧值问题(TODO)。

## 5. POI 搜索与搜索历史

**组件**:`PoiSearchHelper`(数据侧:Inputtips 建议管线 + 搜索历史读写)+ MainActivity(UI 渲染)。

- **建议搜索**:SearchView 输入变化/提交即按**当前城市**(来自定位回调)发起高德 Inputtips;`Tip.getPoint()` 可空,入列表前**过滤无坐标项**;结果(名称/区划+地址/经纬度)经 SimpleAdapter 渲染,无结果 Toast。
- **点选建议项**:选中该 POI 上标记并移相机,同时落**搜索历史**(类型=结果:名称、地址、WGS84+GCJ02 双坐标、秒级时间戳),收起搜索面板。
- **搜索历史两类行**(同表,`IS_LOCATION` 区分):
  - 关键字行(提交搜索词时落,坐标列为空):点击回填搜索框重新搜索;
  - 结果行(POI 点选时落):点击直接选中该点回填地图。
  - 展开搜索框时按时间降序展示;**长按**单条确认后删除。搜索历史保存按 key 先删后插(同关键字去重覆盖)。

## 6. 逆地理编码

**组件**:`GeocoderHelper`,单通道高德 SDK `GeocodeSearch`(半径 200 米)。

- **两条触发路径**:① 长按地图 → 弹信息窗(地址写入标记名,窗体刷新);② 保存/收藏落库前取名(`resolveAddress`,回调内轻量落库)。
- **落库名称三分支**(纯函数 `extractAddress`):成功(rCode==1000 且地址非空)用格式化地址;失败回退当前标记名;标记名也空回退默认名("地图位置")。
- 正地理编码未使用(占位空实现)。

## 7. 模拟定位(前台服务)

**链路**:UI 只依赖 `MockLocationController` 接口(禁止直接碰 ServiceGoBinder)→ `ServiceGoController`(bind + startForegroundService,先自查定位权限,缺权拒启动)→ `ServiceGo`。

- **ServiceGo.onCreate**:先 `startForeground`(三参,`foregroundServiceType="location"`,常驻通知点击回主界面)再检查定位权限(缺权则 stopSelf,满足 FGS 契约);随后移除并重建 GPS/NETWORK 两个 **TestProvider**(provider 已存在时复用而非崩溃),最后在专用 HandlerThread 上启动 **100ms 周期供给循环**。
- **供给内容**:每周期向两个 TestProvider 各写一条固定位置——GPS(精度 1m,附"7 颗卫星" extras)、NETWORK(精度 2m),时间为实时(当前毫秒/elapsedRealtime),坐标为 GO 时换算好的 **WGS84**。
- **运行中传送**:`setPosition` 更新坐标并立即触发下一轮供给。
- **可靠性约定**:`onStartCommand` 刻意 `START_NOT_STICKY`——进程被回收后**不自动恢复**模拟(避免无人知情地持续供给假定位);停止标志 `volatile`(主线程 onDestroy 写、供给线程读);默认坐标为济南(36.667662, 117.027707, 海拔 55)。
- **系统前提**:用户须在开发者选项把本应用选为"模拟位置信息应用"(GoUtils 试探检测,manifest 声明 `ACCESS_MOCK_LOCATION`)。

## 8. 定位历史

**数据**:独立库 `HistoryLocation.db` v2,行 = 名称 + WGS84 经纬度 + 秒级时间戳 + CUSTOM 列(**GCJ02**,历史遗留叫 BD09)。

- **落库时机**:信息窗"保存"按钮(带成败 Toast);GO 启动/传送成功时(附带动作,失败仅日志不打断主提示)。同 WGS84 坐标先删后插(同点重存=覆盖)。
- **过期清理**:每次进入历史页,按偏好 `setting_history_expiration`(天,默认 7;≤0 视非法回退 7)删除更早的记录。
- **列表交互**:
  - 展示:名称、时间(yyyy-MM-dd HH:mm:ss)、WGS84 与 GCJ02 坐标(各 11 位小数);本地关键字过滤(contains);空态占位文案;
  - **点击行**:取 GCJ02 坐标 → 若开启随机偏移(见 §11)则偏移并 Toast 偏移量 → 经静态门面回填主界面选中点并返回地图;
  - **长按行**:编辑(改名)/ 收藏(整行复制进收藏表,历史行保留)/ 删除(确认框);
  - **菜单**:确认后清空全部。

## 9. 收藏地点

**数据**:独立库 `FavoriteLocation.db` v1,行结构与定位历史完全一致;**不参与**历史有效期清理。

- **收藏入口**:① 主界面信息窗"★"按钮——逆地理取地址后落收藏表(成败 Toast);② 历史页长按"收藏"——按 id 取原始行整行复制;③ 收藏页菜单"添加"——对话框手动输入名称(可空,回退"手动地点")/经度/纬度,坐标系单选(默认高德 GCJ02,可切 GPS 原始 WGS84),经 `GoUtils.validateManualLatLng` 校验(错误提示分支与主界面手动输入对话框一致)、`convertManualLatLng` 换算成双坐标落库。同 WGS84 坐标先删后插(重复收藏同一点=改名)。
- **列表交互**:行形与历史页一致(复用 history_item 布局);默认收藏名称前加"★"前缀(仅展示);点击行回填地图(随机偏移同样生效);长按:编辑/ 设为默认(或"取消默认")/ 删除;菜单:清空全部(确认框)。
- **默认收藏**:身份是 **WGS84 坐标字符串对**(存偏好 `setting_default_favorite_lng_wgs/lat_wgs`,与收藏表去重键同构,改名不失效)。生效点有两处:主界面**冷启动恢复**直接选中默认点;**未选点时按 GO** 自动选中默认点再启动(默认点不套随机偏移——确定性目标)。对应行被删除(单删或清空)时同步清偏好,避免同坐标再收藏时默认"悄悄复活"。

## 10. 应用内更新

**组件**:`UpdateManager`(随 MainActivity 创建,`onDestroy` 注销)。

- **检测**:GET `api.github.com/repos/{UPDATE_REPO_SLUG}/releases/latest`(仓库由构建注入 BuildConfig)。两个入口:启动**自动检测**(失败静默,只记日志)、侧边栏**手动检测**(失败/已是最新均有 Toast)。
- **判定**:语义化版本比较(tag_name vs 当前 versionName,容忍 v 前缀、段数不等、`-rc`/`+build` 后缀)且 **下载地址同源校验**(https + github.com + 同仓库路径)双通过才弹窗——防降级误报与伪造下载地址。
- **弹窗**:自定义 update 布局,Markwon 渲染 Markdown release 说明;忽略 / 更新。
- **下载**:系统 DownloadManager(通知可见),存 `Updates/` 目录,入队前删除同名旧文件(删不掉则取消),入队成功才提示"下载中";完成接收器(NOT_EXPORTED)**比对 downloadId** 防无关下载误触发。
- **安装前自检**(`isSelfUpgradeApk`):APK 可解析、包名与本应用一致、versionCode 更高,三者缺一拒绝安装;通过后经 FileProvider URI 拉起系统安装器,拿不到 Uri 则跳"未知来源"设置页。需要 `REQUEST_INSTALL_PACKAGES` 权限。
- **健壮性**:检测请求在 OkHttp 线程回包时宿主可能已销毁——`mDestroyed` 门控 + `destroy()` 取消在途请求,避免 BadTokenException;HTTP 非 200(如 GitHub 限流 403/429)直接按失败收口,不把错误页喂给 JSON 解析。

## 11. 设置

`SettingsActivity` + `FragmentSettings`(PreferenceFragmentCompat),键以 `preferences_main.xml` 为准:

| 分组 | 键 | 含义 | 消费方 |
|------|----|------|--------|
| 移动 | `setting_altitude` | 模拟海拔(默认 55.0) | GO 时写入 WGS84 高度 |
| 位置偏移 | `setting_random_offset` | 随机偏移开关(默认关) | 历史/收藏页点击行 |
| 位置偏移 | `setting_lat_max_offset` / `setting_lon_max_offset` | 纬/经度偏移上限米(默认 10) | 同上 |
| 地图定位 | `setting_locate_self` | 定位自己的位置开关(默认关) | MainActivity.onResume(定位图层启停 + 回到当前位置按钮显隐,见 §4) |
| 日志 | `setting_log_off` | 关闭日志写入(**下次进程启动生效**) | GoApplication.initXlog |
| 日志 | `setting_history_expiration` | 历史有效期天数(默认 7) | 历史页过期清理 |
| 关于 | `setting_version` / `setting_author` | 版本号(动态)/作者 | 展示 |

- 数值输入统一非空校验;读取侧统一走 `GoUtils.parseDoubleOrDefault`(空串/非数字/NaN/Infinity 一律回退默认值,不崩界面)。
- 随机偏移算法(`GoUtils.randomOffset`):对 GCJ02 坐标做经/纬各自上限内的均匀随机偏移(米→度换算 111320/110574),Toast 展示实际偏移量;作用在"历史/收藏页点击行回填"路径,默认收藏与手选点 GO 不偏移。

## 12. 横切约定

### 12.1 坐标系(全项目铁律)

**地图 / 历史 / 收藏 / 偏好一律 GCJ02;模拟位置写 WGS84;一切转换只经 `MapUtils`**(wgs2gcj / gcj02towgs84 / bd09togcj02)。数据库存双坐标列:WGS84(模拟用)+ CUSTOM=GCJ02(地图用)。V1 时代 CUSTOM 列是 BD09,升级 V2 时经 `DbMigrations` 行级迁移(BD09→GCJ02,坏行保留原值记日志,数据不重建)。

### 12.2 数据存储

三库一偏好,全部只经 `HistoryRepository` 接口访问(SQLite 实现包住三个 SQLiteOpenHelper;写侧去重统一"先删后插";写操作成败回传 UI,失败不谎报成功):

| 库 | 版本 | 内容 |
|----|------|------|
| HistoryLocation.db | v2 | 定位历史(参与过期清理) |
| HistorySearch.db | v2 | 搜索历史(关键字行 + 结果行) |
| FavoriteLocation.db | v1 | 收藏(不清理) |
| 默认 SharedPreferences | — | 设置项 + 同意位 + 上次位置 + 默认收藏身份 |

位置类敏感数据双保险:`allowBackup=false` + `dataExtractionRules` 清空云端备份/设备迁移白名单。

### 12.3 隐私合规与权限

- 高德三套隐私接口独立且都要调(见 §3);`updatePrivacyAgree` 只能跟在用户同意之后,改回无条件调用=替用户点头。
- 权限:FINE/COARSE 定位(启动时申请,参与门控;模拟服务自检必需,地图侧仅在"定位自己的位置"开启时读取真实位置)、POST_NOTIFICATIONS(13+ 单独申请不门控)、`ACCESS_MOCK_LOCATION`(系统权限,需用户在开发者选项授权)、`REQUEST_INSTALL_PACKAGES`(更新安装)。**不声明** `ACCESS_BACKGROUND_LOCATION`(无后台定位场景)。

### 12.4 日志

XLog:文件 `Logs/GoGoGo.log`,单文件 2MB 轮转、最多 4 份备份、3 天过期;侧边栏"反馈"经 FileProvider 分享该文件(不存在则提示);设置项可关写盘(重启生效)。

## 13. 已知风险与运行时约束(排障索引)

1. **高德 key 绑定**包名 `com.yybb.location` + 签名 SHA1——key 无效 = 地图空白/定位搜索报错,构建不拦。换签名证书必须同步 CI secret,否则本地与 CI 是两个签名身份。
2. **信息窗按钮触摸**:高德自定义 InfoWindow 以位图渲染、不向子 View 派发触摸事件——长按信息窗的保存/复制/分享/传送/收藏五按钮能否点击需真机验证(发布阻断冒烟项);失效则需改整窗 OnInfoWindowClickListener+对话框方案。
3. **模拟是否生效**依赖开发者选项授权 + GPS 开启 + WiFi 关闭(WiFi 会让定位闪回真实位置,GO 后有警告弹窗,但只是提示不强制)。
4. MainViewModel 四个 LiveData 无生产观察者(接线属行为变更,需专门评审);`mockRunning` 在 onDestroy 路径有陈旧值 TODO。
5. 历史/收藏点击行解析的是列表渲染串(从 `[经度:… 纬度:…]` 文本反取坐标),视图即契约——改动 history_item 布局或 `KEY_LNG_LAT_CUSTOM` 格式会破坏回填。
6. JVM 单测中 org.json 是 stub,JSON 测试用字符串形值锚定(UpdateManager.parseRelease 等纯函数即为此设计)。
