# 阶段二:代码架构重构(聚焦拆解)— 设计文档

- 日期:2026-10-02
- 状态:已批准(经用户逐项确认:深度=聚焦拆解、测试=纯 JVM、包结构=拆后归位/方案 C)
- 前置:阶段一(构建现代化)已合入 master(`58296b0`),本设计基于该代码形态
- 关联:`docs/superpowers/specs/2026-10-01-build-modernization-design.md`(阶段一,其 §8 预告本阶段)

## 1. 背景与现状

阶段一后代码量 4658 行 Java,测试仅模板文件。三个"巨石":

- `MainActivity.java` 1290 行,混 8 种职责:地图(`initMap`:462、`markMap`:744、`resetMap`:752、`showLocation`:769)、地理编码(`:538-543`)、当前定位(`initMapLocation`:571、`getLocationClientOption`:637)、搜索(`initSearchView`:998、`getMapList`:1138、菜单内 SearchView `:282-392`)、历史存取(`initStoreHistory`:883、`getSearchHistory`:897)、模拟服务控制(ServiceConnection `:208`、`initGoBtn`:790、`startGoLocation`:795、`stopGoLocation`:810)、更新检查/下载/安装(`initUpdateVersion`:1158、`checkUpdateVersion`:1172、`downloadNewVersion`:1248、`installNewVersion`:1272)、传感器转发(`onSensorChanged`:392)。
- `JoyStick.java` 828 行:悬浮窗容器 + **第二个 BaiduMap 实例**(`initBaiduMap`:580)+ 自带搜索/历史面板(`initHistoryView`:658、`fetchAllRecord`:765),与主界面逻辑重复;坐标换算在 `processDirection`:372。
- 测试为零。

## 2. 目标

1. MainActivity 瘦身为纯装配层(目标 ≤ ~300 行):装配、菜单、drawer、按钮接线、权限流转、Sensor 转发。
2. 七个职责各自封装为单一职责协作类(见 §4),UI 永不直接依赖 `ServiceGoBinder`。
3. 坐标换算等纯逻辑抽为 `domain.LocationMath` 并获得全覆盖 JVM 单测;`MainViewModel`(LiveData)、Repository 契约获得单测。
4. 包归位(方案 C 第二步):拆完稳定后一次受控 `git mv`。

## 3. 非目标

- JoyStick 悬浮窗三面板(摇杆/小地图/历史)不彻底拆分——只做换算逻辑共享与 Repository 化去重(YAGNI,彻底拆分留待真实需求)。
- 不引入 DI 框架、Kotlin、Robolectric、RxJava/协程;不引入新错误处理模型;不改任何用户可见功能、交互、UI。
- 不动 `WelcomeActivity`/`SettingsActivity`/`HistoryActivity` 的整体结构(它们体量尚可)。

## 4. 组件分解

从 MainActivity 拆出(新类落 `com.zcshou.gogogo` 子包,行号为现值、实施时按内容锚定):

| 新单元 | 吸收的职责 | 要点 |
|---|---|---|
| `map.MapController` | `initMap`、地图四类点击监听、`markMap`/`resetMap`/`showLocation`、`mMapIndicator` | 持有 MapView/BaiduMap;点击事件以回调接口分发给 ViewModel/Activity 决策;`showLocation` 的 static 改为实例方法 |
| `map.GeocoderHelper` | `onGetGeoCodeResult`/`onGetReverseGeoCodeResult` 及初始化 | 结果经回调交给 ViewModel(地址文本) |
| `location.CurrentLocationSource` | `initMapLocation`、`getLocationClientOption`、`onLocDiagnosticMessage` | 封装 LocationClient 生命周期(start/stop/resume),回调接口输出 BDLocation |
| `search.PoiSearchHelper` | `initSearchView`、SuggestionSearch 初始化、`getMapList`、搜索历史联动 | 输入查询→回调结果列表;历史读写经 HistoryRepository |
| `update.UpdateManager` | `checkUpdateVersion`、`downloadNewVersion`、`installNewVersion`、更新弹窗(`update.xml`)、下载完成 receiver | OkHttp 调用与 dialog 组装内聚;版本号比较逻辑抽纯函数供单测 |
| `data.HistoryRepository`(接口)+ `SQLiteHistoryRepository` | `initStoreHistory`、`getSearchHistory`、两 DataBaseHistory* 的全部调用面 | 方法:搜索历史增/查/清、定位历史增/查、过期清理;实现包住现有两个 DB 类,边界内不抛异常 |
| `gogogo.MainViewModel`(LiveData) | 选中点(经纬度/海拔/地址)、服务运行状态、搜索结果列表、当前定位状态 | Activity 观察;纯 JVM 可测(core-testing InstantTaskExecutorRule) |

ServiceGo 侧:

- 新增 `service.MockLocationController` 接口:`start(lng, lat, alt)` / `stop()` / `setPosition(lng, lat, alt)` / `isRunning()`。
- 绑定管理(ServiceConnection + startForegroundService 流程)封装为实现该接口的绑定控制器;MainActivity 只依赖接口。
- `ServiceGo` 内部逻辑不动(阶段一已适配),仅消费方式变化。

纯逻辑:

- `domain.LocationMath`:JoyStick `processDirection`(:372)中的经纬度位移换算(纬度 1°=110.574km、经度 1°=111.320·cos(lat) km)、方向→位移、速度→步长;全部静态纯函数。

JoyStick(最小处理):

- 换算改调 `LocationMath`;历史/搜索数据读取改走 `HistoryRepository`(消除与主界面的重复);悬浮窗三面板结构与交互不动。

依赖组装:手工构造注入,`GoApplication` 持有 `AppContainer`(Repository、各 Helper 的创建与传递),不引 DI 框架。

## 5. 数据流

界面事件 → MainActivity(装配层)→ MainViewModel / 各 Helper →(HistoryRepository | MockLocationController)→ LiveData 回流 → MainActivity 渲染。位置命令路径:Activity → MockLocationController 接口 → 绑定控制器 → `ServiceGoBinder.setPosition`。UI 层零直接依赖 Binder 与 SQLite API。

## 6. 错误处理

沿用现状:边界 try/catch + XLog;Repository 方法边界内不抛异常;不引入新错误模型。

## 7. 测试策略(纯 JVM)

- 基础设施:JUnit 4 + `androidx.arch.core:core-testing`(InstantTaskExecutorRule);**不引入** Robolectric/仪器测试。
- `LocationMath`:四方向、斜向、极值纬度 cos 衰减、速度换算——全覆盖。
- `MainViewModel`:状态迁移与 LiveData 值断言。
- `HistoryRepository`:以接口的内存 fake 测调用方契约(调用方测试针对契约,实现类 SQLite 行为交真机清单)。
- `UpdateManager`:版本号比较/响应解析抽出的纯函数。
- 真 SQLite、百度 SDK 交互:不在 JVM 单测范围,继续由真机清单覆盖。

## 8. 包归位(方案 C 第二步,独立分支)

拆分稳定后一次 `git mv`(纯移动零内容修改,manifest/build 同步):

```
com.zcshou
├── gogogo            # GoApplication、BaseActivity、AppContainer 留根
├── ui
│   ├── map          # MainActivity、MapController、GeocoderHelper
│   ├── search       # PoiSearchHelper
│   ├── location     # CurrentLocationSource
│   ├── update       # UpdateManager
│   ├── settings     # SettingsActivity、FragmentSettings
│   └── history      # HistoryActivity
├── data             # HistoryRepository、SQLiteHistoryRepository、现有 DataBaseHistory*
├── domain           # LocationMath(及后续纯函数)
├── service          # MockLocationController、绑定控制器、ServiceGo
├── joystick         # JoyStick、RockerView、ButtonView
└── utils            # GoUtils、MapUtils、ShareUtils
```

## 9. 分支计划与推进方式

- 分支 `refactor/decompose`:按 §4 拆类 + 接口化 + 单测。每拆一个组件一个 commit(如 `refactor: extract MapController from MainActivity`),每 commit 后 `sh gradlew assembleDebug test` 绿;行为冻结为全程回归判据。
- 分支 `refactor/package-layout`:§8 归位,一个 commit(纯移动 + manifest 同步)。
- 推进方式:沿用阶段一的子代理驱动开发(每任务独立实现者 + 独立审查者 + 台账),任务颗粒=一个组件;最终全分支审查后本地合入 master(推送/PR 仍待网络与仓库权限)。

## 10. 不变量(回归判据)

- 用户可见功能与交互零变化(启动流、地图、搜索、选点模拟、摇杆三面板、通知按钮、设置、历史、更新弹窗)。
- APK 命名、applicationId、签名、CI 键名注入不变;lint 0 error 0 warning(允许新增 baseline 条目但须逐条理由);`clean assembleDebug assembleRelease test lint` 全绿。
- 单测新增且全绿;测试运行时间秒级(无 Robolectric/仪器)。

## 11. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 拆分引入行为回归(静态成员、`static showLocation` 被外部调用、传感器路径) | 拆前 grep 全部外部调用点;每组件独立 commit + 独立审查;行为冻结判据 |
| `MainViewModel` 引入后生命周期错位(configuration change、ServiceConnection 泄漏) | 绑定控制器生命周期与 Activity 对齐;审查者专项核查 |
| JoyStick 的第二个 BaiduMap 实例与主图状态耦合 | 本阶段不改其结构,只替换数据来源,最小 diff |
| 包归位与拆分 diff 混淆 | 严格两个分支两步走,归位分支零内容修改 |
| 手工注入遗漏导致 NPE | AppContainer 集中创建;审查覆盖装配完整性 |

## 12. 后续

阶段三候选(非本阶段):JoyStick 面板彻底拆分、14+ 前台服务守卫路径设计变更(与阶段一遗留请示合并)、DI 框架评估(若 AppContainer 膨胀)。
