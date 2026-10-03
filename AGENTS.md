# AGENTS.md

## 仓库用途

GoGoGo(影梭)——Android 免 Root 虚拟定位 App(GPL-3.0,仅 arm64-v8a),基于 TestProvider 调试 API + 百度 LBS SDK(本地 jar `app/libs/BaiduLBS_Android.jar`)。

**功能**:地图选点模拟定位(静态选点后一键瞬移,无移动模拟);POI 搜索 + 逆地理;定位/搜索历史(SQLite);应用内更新检查/下载/安装(GitHub releases);欢迎页协议与隐私同意流程。

## 构建与验证

- 工具链:AGP 9.4.1 / Gradle 9.8 / JDK 17 / minSdk 31 / targetSdk 36,纯 Java 单模块 `:app`
- `gradlew` 无执行位——用 `sh gradlew ...`,**不要 chmod**(会弄脏 index)
- 本地构建需 `local.properties`(gitignored):`sdk.dir` + `MAPS_API_KEY`/`MAPS_SAFE_CODE`(键缺失可构建,值为空串;运行时地图不初始化)。CI 由 workflow 注入同名键,键名不可改
- 常用:`sh gradlew assembleDebug test lint`;全量验证:`sh gradlew clean assembleDebug assembleRelease test lint`(四项全绿是合入门槛)
- lint 标准 0 error 0 warning(`app/lint-baseline.xml` 仅 3 条 jar/二进制条目);任何新增 `@SuppressLint`/`tools:ignore`/`noinspection` 必须带理由注释

## 包结构与边界(违反即回归)

```
com.zcshou
├── gogogo      # GoApplication、BaseActivity、AppContainer(组合根,手工 DI)
├── ui.map      # MainActivity、MainViewModel、MapState、MapController、GeocoderHelper
├── ui.search|location|update|settings|history、ui.WelcomeActivity
├── data        # HistoryRepository(+SQLite 实现/内存 fake)、DataBaseHistory*
├── service     # MockLocationController 接口、ServiceGoController、ServiceGo(前台服务,100ms 周期经 TestProvider 持续供给固定位置)
└── utils
```

- UI 层**禁止**直接依赖 `ServiceGoBinder`——服务交互只走 `MockLocationController` 接口
- DB 访问只走 `HistoryRepository`(接口),写侧去重语义继承自 database 包未动的 static helper
- 跨组件状态(选中点/城市/标记图标)只经 `AppContainer` 的 `MapState` 单例(原 static 语义:selectedPoint 可空)
- `MainViewModel` 是分阶段交付:4 个 LiveData 镜像**无生产观察者**——观察者接线会改变可观察行为,接线前先修 `mockRunning` 在 onDestroy 路径的陈旧值(TODO)

## 约定

- 纯 Java(无 Kotlin/DI 框架/Robolectric);测试仅 JVM(JUnit4 + core-testing),新测试放 `app/src/test` 镜像包
- 注释为中文,匹配所在文件风格
- **CRLF 文件**(WelcomeActivity/MainActivity/SettingsActivity/FragmentSettings/HistoryActivity/DataBaseHistory×2)——编辑时保持行尾
- 重构纪律:行为冻结(用户可见行为/时序零变化),以 git 实际行为为准而非计划文本(本项目有过计划文本与旧代码双写语义不一致的先例)

## 已知坑

- 百度 jar 的方法名是 `SetIgnoreCacheException`(大写 S,勿"纠正"为小写——jar 里只有这个拼写)
- JVM 单测中 org.json 是 stub 且 json-java 的 `getString` 严格(不强转)——JSON 测试用字符串形值锚定;`testImplementation 'org.json:json'` 带 `//noinspection NewerVersionAvailable`
- targetSdk 36 行为敏感点:前台服务三参 startForeground + `foregroundServiceType="location"`、动态 receiver 必须 NOT_EXPORTED、自发自收广播需 `setPackage`
- 本开发机 github.com 不可达(Google 系域名可达);origin 指向上游 ZCShou/GoGoGo(无推送权限),master 领先 origin 41 个提交未推送
- 签名 keystore(`keystore/GoGoGo.jks`)与密码明文提交在 `app/build.gradle`——上游既有事实,勿外传

## 文档

改敏感区域前先读:`docs/superpowers/specs/`(两阶段设计文档)、`docs/superpowers/plans/`(实施计划);阶段一=构建现代化(targetSdk 36 适配细节在 §4.2),阶段二=聚焦拆解(组件边界在 §4/§8)。
