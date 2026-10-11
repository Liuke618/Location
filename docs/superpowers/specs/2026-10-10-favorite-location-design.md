# 收藏定位地址功能设计(2026-10-10)

## 背景与目标

当前定位/搜索历史有有效期清理(默认 7 天),用户看中的地点会被自动清掉。
本功能提供**收藏**:把选中点或既有历史记录永久保存(不参与过期清理),在收藏列表中
查看、搜索、改名,并一键回地图选中(与历史页一致的传送前置路径)。

## 方案选型

**独立收藏表**(选定) vs 历史表加 `IS_FAVORITE` 标志位:

- 标志位方案的硬伤:`DataBaseHistoryLocation.saveHistoryLocation` 是"同 WGS84 坐标
  先删后插"的去重覆盖,重存同一点会丢掉收藏标志;且"清空历史/过期清理/删除单条"
  都要重新定义与收藏行的交互,触碰既有行为冻结区。
- 独立表方案:收藏与历史语义互不干扰,历史页"收藏"= 把该行字段复制一份插入收藏表;
  既有历史行为零改动。接口扩展是纯新增方法,不破坏既有调用面。

## 数据层(`data` 包)

1. 新建 `DataBaseFavoriteLocation extends SQLiteOpenHelper`:
   - 表名 `FavoriteLocation`,DB_VERSION 1(新表即 GCJ02 语义,无迁移)。
   - 列与 `DataBaseHistoryLocation` 完全同构:`DB_COLUMN_ID`(自增主键)、
     `DB_COLUMN_LOCATION`、`DB_COLUMN_LONGITUDE_WGS84`、`DB_COLUMN_LATITUDE_WGS84`、
     `DB_COLUMN_TIMESTAMP`、`DB_COLUMN_LONGITUDE_CUSTOM`、`DB_COLUMN_LATITUDE_CUSTOM`
     (CUSTOM 两列为 GCJ02,与历史表 V2 语义一致)。
   - 静态 `saveFavoriteLocation`:同 WGS84 经纬度先删后插(去重覆盖,与历史一致);
     `updateFavoriteLocation`:按 id 改名。
2. `HistoryRepository` 接口新增"收藏"段(复用 `LocationRow` 值对象):
   - `getFavoriteRows()` — timestamp 降序,含自增 id;
   - `addFavoriteRow(LocationRow)` — 同 WGS84 先删后插,写侧回传成败;
   - `deleteFavoriteRow(int id)`、`renameFavoriteRow(int id, String newName)`
     (目标行不存在回传 false,与历史改名语义一致)、`clearAllFavoriteRows()`。
   - **不提供**过期清理——收藏即永久。
3. `SQLiteHistoryRepository` 实现上述方法(读写模式与定位历史段逐字段对应,
   异常记 "DATABASE: ..." 日志并回传 false)。
4. 测试:`InMemoryHistoryRepository` 同步实现;`HistoryRepositoryContractTest`
   新增收藏段契约用例(空表/round trip/同坐标覆盖/降序/删除/改名成败/清空/写侧回传)。

## UI 层

1. **地图信息窗**(`location_poi_info.xml`):按钮行加第 4 个按钮 `poi_mark`
   (星形图标 `ic_star`)。点击 = 收藏当前选中点:GCJ02→WGS84 换算 +
   `GeocoderHelper.resolveAddress` 取地址(与"保存"按钮同构)+
   `addFavoriteRow`,Toast "已收藏/收藏失败,请重试"。
   已知风险:高德 InfoWindow 位图渲染不派发触摸事件(既有发布阻断冒烟项),
   本按钮与现有保存/复制/分享/传送四按钮同等地位,不加重也不解决该风险。
2. **历史页**(`HistoryActivity`):长按菜单加"收藏"项——按行 id 从
   `getLocationRows()` 取原始行,字段原样复制插入收藏表;成功 Toast "已收藏"。
   收藏是复制不是移动,历史行保留。
3. **收藏列表页**(`ui.favorite.FavoriteActivity`,镜像 HistoryActivity 结构):
   - 复用 `history_item.xml` 行布局(键契约同历史页:名称/时间/WGS 坐标展示,
     id 与 CUSTOM 坐标走 gone TextView);
   - 顶部 SearchView 过滤(过滤逻辑与历史页一致);
   - 点击行 → 同历史页:`setting_random_offset` 生效 →
     `MainActivity.showLocation(name, gcjLng, gcjLat)` → finish();
   - 长按菜单:编辑(改名)/ 删除(确认对话框);
   - 工具栏:清空全部(确认对话框,镜像历史页)。
4. **导航抽屉**(`menu_nav.xml`):第一组 `nav_history` 上方加 `nav_favorite`
   (星形图标 `ic_menu_favorite`,标题"收藏"),MainActivity 接线跳转。
5. Manifest 注册 `FavoriteActivity`(`android:label="@string/app_favorite"`)。

## 字符串资源(新增)

`app_favorite`(收藏)、`nav_menu_favorite`(收藏)、`favorite_idle`(暂无收藏)、
`app_location_mark`(已收藏)、`app_location_mark_failed`(收藏失败,请重试)、
`favorite_delete_ok`/`favorite_delete_error`/`favorite_rename_error`。

## 不做的事(YAGNI)

- 收藏不参与任何过期/容量清理;
- 不做地图上一次渲染全部收藏标记;
- 不做收藏分组、置顶、排序自定义;
- 不做收藏导出/导入。

## 验证门槛

`sh gradlew clean assembleDebug assembleRelease test lint` 四项全绿
(lint 0 error 0 warning);契约测试覆盖存储语义,UI 层与既有 recordCurrentLocation
同等对待(纯 JVM 无 UI 测试设施)。

## 约束回顾

- UI 不直接触碰 SQLiteOpenHelper，一律经 `HistoryRepository`；
- 坐标系:表内 WGS84 + CUSTOM(GCJ02) 双列,与历史表一致,转换只经 `MapUtils`;
- MainActivity/HistoryActivity 为 CRLF 文件,编辑保持行尾;
- 新增 `@SuppressLint`/`tools:ignore` 需理由注释(本设计预期不新增)。

## 追加:默认收藏(2026-10-11)

在收藏功能之上的增量:**可把一个收藏设为默认,模拟时默认用它**。

- **身份**:默认收藏以 WGS84 坐标字符串存默认 SharedPreferences
  (`setting_default_favorite_lng_wgs`/`setting_default_favorite_lat_wgs`),
  与收藏表"同 WGS84 先删后插"的去重键同构——改名不失效;收藏行被删/清空则
  回查不到即视为无默认(界面同步清偏好键,避免同坐标再收藏时默认悄悄复活)。
- **入口**:收藏页长按菜单"设为默认/取消默认"(互斥,动态标题);列表中默认项
  名称前加 `★` 展示标识(仅展示层前缀,存储值不变)。
- **生效点(语义定夺)**:
  1. 冷启动恢复:默认收藏**优先于**"上次位置"——已设默认则选中默认点上标记+移视野,
     一键 GO 即模拟默认点;无默认时保持原 last-position 行为。
  2. 未选点按 GO(未运行):不再提示"请先点击地图位置或者搜索位置",而是选中默认收藏
     并按正常流程启动模拟;无默认时提示原样保留。
  3. 运行中未选点按 GO:**保持"停止模拟"语义不变**(默认收藏不接管该分支,
     停止手势必须保留)。
- **不做**:默认收藏不套随机偏移(确定性目标,与手选点 GO 同待遇);不在地图信息窗
  加"设为默认"按钮(收藏页长按足够,信息窗按钮已有真机触摸事件风险);默认收藏唯一,
  设新默认即替换旧默认(无确认弹窗,操作可逆)。
- **数据层**:`HistoryRepository` 新增 `findFavoriteRowByWgsCoords(lng, lat)`
  (精确字符串匹配,契约测试覆盖命中/不命中/空表);MainActivity 的仓库初始化从
  initSearchView 前提前到 restoreLastPosition 之前(恢复路径需要查库,对象本身仍是
  AppContainer 同一单例,无行为差异)。
