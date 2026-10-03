# 移除移动模拟与悬浮控制面板(摇杆子系统)— 设计文档

- 日期:2026-10-03
- 状态:已批准(方案选择=分步提交四 commit;悬浮窗权限门槛一并删除经用户确认)
- 范围:整体删除 `joystick` 子系统及其在 `ServiceGo`、设置、资源中的全部挂载;连带清理死代码与孤儿资源
- 关联:`docs/superpowers/specs/2026-10-02-phase2-decompose-design.md`(阶段二拆解后的包结构是本次删除的基础)

## 1. 背景与动机

用户要求移除"移动模拟"和"悬浮控制面板"两项业务功能。调研确认二者实为**同一个子系统**:移动完全由悬浮摇杆驱动(摇杆/按键输入 → `LocationMath` 增量位移 → `ServiceGo` 持续供给),没有独立于摇杆的移动逻辑。因此本设计按"整体删除摇杆子系统"执行,应用退化为纯静态虚拟定位。

## 2. 目标

1. 删除 `com.zcshou.joystick` 包全部三个类(JoyStick 822 行 / RockerView 243 行 / ButtonView 441 行),含摇杆/按键、小地图(第二个 BaiduMap 实例)、历史悬浮面板及面板内搜索。
2. 清理 `ServiceGo` 的摇杆托管与移动状态,前台服务保留但只供给固定位置。
3. 移除为摇杆悬浮窗存在的 `SYSTEM_ALERT_WINDOW` 权限及 GO 流程中的授权门槛。
4. 移除摇杆专属设置项与资源,清理失去生产调用方的死代码。
5. 每个 commit 均可构建,最终通过全量验证门槛。

## 3. 非目标

- 不动静态定位核心:选点/POI 搜索/手动输经纬度 → GO 瞬移 → TestProvider 持续供给,全部保留。
- 不动历史记录页(`HistoryActivity`)、随机偏移、海拔设置、日志设置、应用内更新、欢迎页协议流程。
- 不动 `MockLocationController` 接口与 `ServiceGoController`(UI 层与服务交互契约零变化)。
- 不改 `HistoryRepository` 及 `data` 包(历史悬浮面板虽消费它,删除后不影响存储层)。

## 4. 删除范围明细

### 4.1 ServiceGo(app/src/main/java/com/zcshou/service/ServiceGo.java)

| 删除项 | 位置 | 说明 |
|---|---|---|
| `import com.zcshou.joystick.JoyStick` | :35 | 与 joystick 包删除必须同一 commit(编译依赖) |
| `mSpeed`/`mCurBea` 字段 | :47,:46 | 移动状态失去来源 |
| `SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW/HIDE` 及 `mActReceiver`/`NoteActionReceiver` | :56-60,:60,:142-148,:323-337 | 通知栏按钮删除,通知变为纯"服务运行中"展示(前台通知本身保留,FGS 契约要求) |
| `initJoyStick()` 及 `onCreate` 调用 | :179-202,:98 | 摇杆创建与监听回调(onMoveInfo/onPositionInfo) |
| `onStartCommand` 中 `mJoyStick.setCurrentPosition` | :107-109 | |
| `onDestroy` 中 `mJoyStick.destroy()` | :125-127 | |
| `ServiceGoBinder.setPosition` 中 `mJoyStick.setCurrentPosition` | :346 | binder 契约(lng/lat/alt → 更新供给坐标)不变 |
| `loc.setSpeed()`/`loc.setBearing()` 两处调用 | :263,:267,:313,:314 | 静止位置速度/方向均为 0,与 `Location` 默认值一致,无行为差异 |
| `initNotification` 中两个 action 的 PendingIntent 与 `addAction` | :161-164,:171-172 | |

**保留**:100ms TestProvider 供给循环(`initGoLocation`)、GPS/Network 两个 TestProvider 的增删、前台通知、`startForeground` 三参契约。

### 4.2 悬浮窗权限

- `AndroidManifest.xml:41` 删除 `SYSTEM_ALERT_WINDOW` 声明。
- `MainActivity`(:651-655)删除 `canDrawOverlays` 检查及拦截返回——GO 流程变为:网络检查 → GPS 检查 → 模拟位置权限检查(其余不变)。
- `GoUtils.showEnableFloatWindowDialog()`(:192-209)删除。

### 4.3 设置

- `preferences_main.xml`:"移动"分组中摇杆类型、步行/跑步/驾驶速度 4 项删除;海拔保留,分组改名"位置模拟"(`setting_group_move` 字符串改义)。
- `FragmentSettings.java`::44-59 对应 4 个 preference 的查找/校验代码删除(该文件为 **CRLF**,编辑保持行尾)。
- `arrays.xml`:`array_joystick_type`、`array_joystick_type_values` 删除。
- 字符串删除:`setting_joystick_type`、`setting_walk(_default)`、`setting_run(_default)`、`setting_bike(_default)`。

### 4.4 资源

- 布局删除:`joystick.xml`、`joystick_button.xml`、`joystick_history.xml`、`joystick_map.xml`。
- drawable 删除(仅摇杆布局引用,实施时逐个 grep 复核,含被 layer-list 复合引用的可能):`ic_close`、`ic_bike`、`ic_down`、`ic_history`、`ic_left`、`ic_left_down`、`ic_left_up`、`ic_lock_close`、`ic_map`、`ic_move`、`ic_right`、`ic_right_down`、`ic_right_up`、`ic_run`、`ic_up`、`ic_walk`、`circle_shape_bg`。
- **共享保留**(已核实其他处引用):`border_window`(location_poi_info)、`ic_position`(MainActivity)、`ic_home_position`(main_map)、`history_idle`(activity_history)、`ic_fly` 等。
- 字符串删除:`note_show`、`note_hide`、`joystick_move/walk/run/bike/map/history/history_tips/map_tips`、`app_search_tips`(仅 joystick_map.xml 引用)。
- 项目仅 `values/` 一个 locale,无翻译同步负担。

### 4.5 死代码

- `domain/LocationMath.java` + `app/src/test/.../LocationMathTest.java`:删除后唯一生产调用方是 ServiceGo 的摇杆回调(随之消失),`domain` 包消失。
- `GoUtils.TimeCount` 内部类:仅 JoyStick 使用。
- 注释修正:`PoiSearchHelper.java:29`、`MainActivity.java:88` 两处"JoyStick 引用不受影响"的说明改为不再提及摇杆。

### 4.6 顺手项

- `location_input.xml` 的 `joystick_longitude`/`joystick_latitude` id 改名(仅 MainActivity :579-580 引用),消除误导命名。
- `AGENTS.md` 包结构图:去 `joystick`、`domain` 两行,service 描述改为"持续供给固定位置";悬浮摇杆相关功能描述同步删减。

## 5. 删除后的应用形态

静态虚拟定位:地图选点 / POI 搜索 / 手动输入经纬度 → GO 一键瞬移 → 前台服务 100ms 周期经 TestProvider 供给固定位置(GPS + Network 双通道)。导航抽屉、历史、搜索、设置(余下项)、更新、欢迎页交互均与现状一致。

**用户可见变化**:① 无任何悬浮窗;② 通知栏无"显示/隐藏摇杆"按钮;③ GO 启动不再要求悬浮窗授权;④ 设置页少 4 项,"移动"分组改名"位置模拟"仅含海拔;⑤ 模拟位置速度恒为 0。

## 6. 提交划分(方案 A:分步提交,每步 `assembleDebug` 绿)

| Commit | 内容 | 边界理由 |
|---|---|---|
| ① | 删除 joystick 包 + ServiceGo 全部摇杆托管/通知按钮/移动状态 + note_show/note_hide 字符串 + PoiSearchHelper/MainActivity 注释修正 | 编译最小闭包(ServiceGo import JoyStick) |
| ② | 悬浮窗权限:manifest 声明 + MainActivity 门槛 + GoUtils 弹窗 | 独立行为变化,单独可 review |
| ③ | 设置 4 项 + 分组改名 + arrays/strings + 4 布局 + 独占 drawable + location_input id 改名 | 资源清理聚合一处,逐项 grep 复核 |
| ④ | LocationMath + 测试 + TimeCount + AGENTS.md 更新 | 死代码收尾,文档随代码定稿 |

## 7. 验证门槛

1. 每 commit:`sh gradlew assembleDebug`(注意 `gradlew` 无执行位,用 `sh`)。
2. 最终:`sh gradlew clean assembleDebug assembleRelease test lint` 四项全绿(合入门槛),lint 保持 0 error 0 warning。
3. 资源核查:删除前对每个 drawable/字符串全仓 grep;删除后 `assembleRelease` 全绿兜底(资源缺失在此暴露)。
4. 真机冒烟:选点→GO 瞬移生效、停止模拟恢复、通知展示、历史记录写入/复用、设置页无残留项。

## 8. 风险与注意

- **CRLF**:MainActivity、FragmentSettings 在 CRLF 名单内,编辑必须保持行尾。
- **lint 纪律**:删除不引入新抑制;若资源清理触发 UnusedResources 类 warning,以彻底删除解决而非压制。
- **百度 jar 坑**:`SetIgnoreCacheException` 大写 S 的注意事项随摇杆删除自然消失,但主地图(BaiduMap 实例唯一化)不受影响,无需触碰 `MapController`。
- **MainViewModel TODO 不受影响**:`mockRunning` 陈旧值问题(mirror LiveData 无生产观察者)不在本次范围。
