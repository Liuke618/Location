# AGENTS.md

## 仓库用途

GoGoGo(任意门)——Android 免 Root 虚拟定位 App(GPL-3.0,仅 arm64-v8a),基于 TestProvider 调试 API + 高德 LBS SDK(Maven 组合包 3dmap-location-search)。

**功能**:地图选点模拟定位(静态选点后一键瞬移,无移动模拟;真实定位受设置项"定位自己的位置"控制,默认关=不激活定位图层不读真实位置,仅搜索/选点);POI 搜索 + 逆地理;定位/搜索历史(SQLite);收藏定位地址(独立表,不参与历史有效期清理,可设默认收藏用于冷启动/未选点 GO 兜底);应用内更新检查/下载/安装(GitHub releases);应用内高德 Key 配置(设置页,重启生效;自定义与内置皆空时主界面启动弹引导,指引含申请步骤、包名与签名 SHA1);欢迎页协议与隐私同意流程。

## 构建与验证

- 工具链:AGP 9.4.1 / Gradle 9.8 / JDK 17 / minSdk 31 / targetSdk 36,纯 Java 单模块 `:app`
- `gradlew` 已带执行位(100755),`./gradlew ...` 与 `sh gradlew ...` 均可
- 本地构建需 `local.properties`(gitignored):`sdk.dir` + `AMAP_API_KEY`(键缺失可构建,值为空串;运行时地图不初始化)+ 签名凭据 `KEY_ALIAS`/`KEY_STORE_PASSWORD`/`KEY_PASSWORD`(缺失则 assemble 在签名阶段失败)。CI 由 workflow 注入同名键,键名不可改
- 坐标系约定:**地图/历史/偏好一律 GCJ02,模拟位置写 WGS84,坐标系转换只经 `MapUtils`**
- 常用:`sh gradlew assembleDebug test lint`;全量验证:`sh gradlew clean assembleDebug assembleRelease test lint`(四项全绿是合入门槛)
- lint 标准 0 error 0 warning(`app/lint-baseline.xml` 仅 2 条 IconLocation 豁免);任何新增 `@SuppressLint`/`tools:ignore`/`noinspection` 必须带理由注释

## 包结构与边界(违反即回归)

```
com.yybb.location          # 应用包名(applicationId/namespace)
├── (根包)                 # GoApplication、BaseActivity、AppContainer(组合根,手工 DI)
├── ui.map      # MainActivity、MainViewModel、MapState、MapController、GeocoderHelper
├── ui.search|location|update|settings|history|favorite、ui.WelcomeActivity
├── data        # HistoryRepository(+SQLite 实现/内存 fake)、DataBaseHistory*
├── service     # MockLocationController 接口、ServiceGoController、ServiceGo(前台服务,按设置项周期经 TestProvider 持续供给固定位置,默认 100ms,档位 100ms~5s;通知栏"停止模拟"按钮经本地广播回 MainActivity 停服——绑定未解时 stopSelf 销不掉服务)
└── utils
```

- UI 层**禁止**直接依赖 `ServiceGoBinder`——服务交互只走 `MockLocationController` 接口
- DB 访问只走 `HistoryRepository`(接口),写侧去重语义继承自 database 包未动的 static helper;读侧存储失败返回 null(区别于空表),历史/收藏/搜索历史入口据此提示
- 跨组件状态(选中点/城市/标记图标)只经 `AppContainer` 的 `MapState` 单例(原 static 语义:selectedPoint 可空)
- `MainViewModel` 是分阶段交付:4 个 LiveData 镜像**无生产观察者**——观察者接线会改变可观察行为,接线前先修 `mockRunning` 在 onDestroy 路径的陈旧值(TODO)

## 约定

- 纯 Java(无 Kotlin/DI 框架/Robolectric);测试仅 JVM(JUnit4 + core-testing),新测试放 `app/src/test` 镜像包
- 注释为中文,匹配所在文件风格
- **行尾混杂**:约半数文件为 CRLF(WelcomeActivity/MainActivity/ServiceGo/HistoryActivity/GoUtils/MapUtils/多数 res xml/build 脚本等),另一部分为 LF(如 FavoriteActivity/MainViewModel)——**编辑任何文件前先检测行尾并保持原样**,勿整文件重写
- 重构纪律:行为冻结(用户可见行为/时序零变化),以 git 实际行为为准而非计划文本(本项目有过计划文本与旧代码双写语义不一致的先例)

## 已知坑

- 高德 key 绑定包名 com.yybb.location + 签名 SHA1(key 无效 = 地图空白/定位搜索报错,构建不拦);key 缺失(设置自定义与清单内置皆空)时主界面 onCreate 弹引导跳设置页锚定 key 项(生效值判定统一走 `AmapUtils.getEffectiveApiKey`,2026-10-13 起)
- 高德隐私合规三套接口(地图 MapsInitializer / 定位 AMapLocationClient / 搜索 ServiceSettings)**各自独立、三套都要调**，漏调对应能力运行时报错(搜索=1907)且构建不拦；`updatePrivacyShow` 无条件调用，`updatePrivacyAgree` **只能在用户同意自有协议/隐私政策之后下发**(经 `GoApplication.applyAmapPrivacyAgreement`，同意位持久化在 SharedPreferences 键 `KEY_ACCEPT_AGREEMENT`/`KEY_ACCEPT_PRIVACY`)——把它改回无条件调用等于替用户点头，隐私声明与实际行为不符
- 高德自定义 InfoWindow 窗体以位图渲染、**不向子 View 派发触摸事件**——长按地址气泡的保存/复制/分享/传送四按钮能否点击需真机验证(发布阻断冒烟项);失效则需改整窗 OnInfoWindowClickListener+对话框方案
- 高德 POI 建议的 `Tip.getPoint()` 可空——入列表前须过滤无坐标项(PoiSearchHelper 已过滤)
- JVM 单测中 org.json 是 stub 且 json-java 的 `getString` 严格(不强转)——JSON 测试用字符串形值锚定;`testImplementation 'org.json:json'` 带 `//noinspection NewerVersionAvailable`
- targetSdk 36 行为敏感点:前台服务三参 startForeground + `foregroundServiceType="location"`、动态 receiver 必须 NOT_EXPORTED、自发自收广播需 `setPackage`
- origin 是自有私有仓库 `git@github.com:Liuke618/Location.git`(SSH 推送可达;本机走 HTTPS 的 GitHub API 不通,未认证探测返回 404),推送仍需用户确认
- 签名 keystore:`keystore/Location.jks`(PKCS12,别名 `locationkey`,DN `CN=Liuke618, O=Liuke618, C=CN`,SHA1 `D3:67:71:54:6A:52:21:AA:00:3A:BA:E6:D7:81:46:BB:60:AC:98:5B`)。2026-10-10 重签自上游证书(SHA1 `32:2F:E6:05:…:45:D2`,已删,仅存 git 历史)——**新旧签名不兼容,已装机用户须卸载重装**;口令不再入库(`KEY_ALIAS`/`KEY_STORE_PASSWORD`/`KEY_PASSWORD` 读自 local.properties;PKCS12 不区分 store 与 key 口令,两键必须同值,否则 `packageDebug/packageRelease` 报 "Given final block not properly padded" 且构建不拦原因);CI 最终签名以 `secrets.SIGNING_KEY` 为准,换证书后必须同步该 secret 与上面三个键,否则本地与 CI 是两个签名身份,而高德 key 绑的是 APK 实际那张证书的 SHA1

## 文档

业务全景梳理(按业务域逐文件核对,随改动同步维护)见 `docs/business-overview.md`。改敏感区域前先读:`docs/superpowers/specs/`(两阶段设计文档)、`docs/superpowers/plans/`(实施计划);阶段一=构建现代化(targetSdk 36 适配细节在 §4.2),阶段二=聚焦拆解(组件边界在 §4/§8);高德迁移(2026-10-03,spec/plan 见 `2026-10-03-amap-migration-*`)。
