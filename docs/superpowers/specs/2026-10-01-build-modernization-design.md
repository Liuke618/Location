# 阶段一:构建体系现代化 — 设计文档

- 日期:2026-10-01
- 状态:已批准(设计经用户逐项确认:方向=构建+架构分两阶段、顺序=构建先行、minSdk=31、推进方式=渐进式四分支)
- 范围:仅构建体系与 targetSdk 36 行为适配;架构重构(阶段二)另行设计
- 前置调研:AGP 当前稳定线 9.4.x([release 参考](https://developer.android.com/build/releases/gradle-plugin));Play 政策
  2026-08-31 起新提交需 target API
  36([官方说明](https://support.google.com/googleplay/android-developer/answer/11926878))

## 1. 背景与动机

项目当前停留在 AGP 8.12.1 + Gradle 8.13 + compileSdk/targetSdk 32 + minSdk 27,落后当前工具链一整个大版本,targetSdk 距离
Play 政策要求差 4 代。此外代码中存在两处 targetSdk 34+ 必现崩溃点(见 §4.2、§4.3),升级到 targetSdk 36 必须一并处理。

## 2. 目标

1. 工具链现代化:AGP 9.x 最新稳定、Gradle 9.x、JDK 17、编译目标 Java 17。
2. SDK 现代化:minSdk 31,targetSdk 36,compileSdk 36,完成全部对应行为适配。
3. 依赖升级到当前稳定版(百度 jar 除外)。
4. Lint/warning 清零,为阶段二(架构重构)确立干净基线。
5. 全程保持用户可见功能与交互不变。

## 3. 非目标

- 不升级百度 LBS SDK(仍用本地 `app/libs/BaiduLBS_Android.jar`);若真机测试发现其在新系统上崩溃,单独立项处理,不混入本阶段。
- 不引入 Kotlin、DI 框架、新架构组件。
- 不做任何架构/包结构调整(阶段二内容)。
- 不修改 versionCode/versionName(归发布流程管)。

## 4. 分支计划

四个分支按序推进,**每个分支合入前必须本地构建通过且 CI(build-check)绿**,不交叉进行。

### 4.1 分支① `build/toolchain-modernization`(工具链)

| 改动                                                          | 位置                                                | 说明                                                                                                                                                                                                                                                                                         |
|-------------------------------------------------------------|---------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Gradle wrapper 8.13 → 9.x                                   | `gradle/wrapper/gradle-wrapper.properties:6`      | 具体版本实现时按 AGP 兼容表锁定                                                                                                                                                                                                                                                                         |
| AGP classpath → 9.x 最新稳定                                    | `build.gradle:10`                                 |                                                                                                                                                                                                                                                                                            |
| minSdkVersion 27 → 31                                       | `app/build.gradle:30`                             | 同步改 DSL 名为 `minSdk`/`targetSdk`                                                                                                                                                                                                                                                            |
| 删除 pre-S 版本分支                                               | `ServiceGo.java:221-227`、`ServiceGo.java:274-281` | minSdk 31 后 `Build.VERSION_CODES.S` 判断恒真,只保留 `ProviderProperties` 路径,清理 `Criteria` 相关 import                                                                                                                                                                                               |
| 旧 variant API 迁移                                            | `app/build.gradle:98-111`                         | AGP 9 移除 `applicationVariants`/`output.outputFile`;改用 `androidComponents.onVariants` 设置 `outputFileName`,保持 APK 命名规则(`Go_<version>_arm64-v8a_<buildType>.apk`)不变                                                                                                                           |
| `packagingOptions` → `packaging`                            | `app/build.gradle:77`                             | 旧 DSL 名已移除                                                                                                                                                                                                                                                                                 |
| `resourceConfigurations` → `androidResources.localeFilters` | `app/build.gradle:36`                             |                                                                                                                                                                                                                                                                                            |
| 删除 `buildToolsVersion` 固定值                                  | `app/build.gradle:10`                             | 交由 AGP 默认                                                                                                                                                                                                                                                                                  |
| 删除 `multiDexEnabled`(release)                               | `app/build.gradle:60`                             | minSdk 21+ 原生支持,属遗留                                                                                                                                                                                                                                                                        |
| keystore 路径修复                                               | `app/build.gradle:15,21`                          | `'..\\keystore\\GoGoGo.jks'` → `'../keystore/GoGoGo.jks'`(现为 Windows 反斜杠,非 Windows 构建必失败)                                                                                                                                                                                                  |
| `compileOptions` Java 11 → 17                               | `app/build.gradle:72-75`                          |                                                                                                                                                                                                                                                                                            |
| **替换 secrets-gradle-plugin**                                | `build.gradle:12`、`app/build.gradle:4`            | 插件停在 2.0.1(2021 年),与 AGP 9 兼容性存疑。删除插件,改用少量内联 Gradle 脚本从 `local.properties` 读取 `MAPS_API_KEY` 与 `MAPS_SAFE_CODE` 写入 BuildConfig(两键均在用:`GoApplication.java:36`、`MainActivity.java:921-922`)。**键名与 CI 注入方式保持不变**(`build-check.yml:43-48`、`build-release.yml:32-38`),键缺失时给出默认空串并打警告,不阻塞本地无密钥构建 |
| CI JDK 发行版 `adopt` → `temurin`                              | `build-check.yml:40`、`build-release.yml:25`       | adopt 已废弃;JDK 17 版本号不变                                                                                                                                                                                                                                                                     |

验证:`./gradlew assembleDebug assembleRelease test lint` 本地通过 + CI 绿。本分支允许残留 lint warning(分支④统一清)。

### 4.2 分支② `build/targetsdk-36`(SDK 与行为适配)

**Manifest**(`app/src/main/AndroidManifest.xml`):

- `ServiceGo`(:71-73)增加 `android:foregroundServiceType="location"`。
- 新增 `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />`。
- 权限审计:对 `READ_LOGS`(:32)、`MOUNT_UNMOUNT_FILESYSTEMS`(:29)、`WRITE_SETTINGS`(:41)、`READ_PHONE_STATE`(:18)、
  `READ_EXTERNAL_STORAGE`(:19)、`REPLACE_EXISTING_PACKAGE`(:22)、`DOWNLOAD_WITHOUT_NOTIFICATION`(:20)逐个 grep 代码确认无引用后删除;
  **存疑者保留并列出请示**;确认在用的(`SYSTEM_ALERT_WINDOW`、`REQUEST_INSTALL_PACKAGES` 等)不动。
- 删除 `android:requestLegacyExternalStorage`(:55,API 30+ 已无效)。

**代码适配**:

| 问题                         | 位置                                                                    | 适配                                                                                                                                   |
|----------------------------|-----------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| 前台服务无类型(targetSdk 34+ 必崩)  | `ServiceGo.java:148`                                                  | `startForeground` 改三参,传 `ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION`;启动前显式校验定位权限已授予(Android 14+ 要求),未授予则记日志并 `stopSelf()`          |
| 动态 receiver 未声明导出性(34+ 必崩) | `ServiceGo.java:121`                                                  | 改用 `ContextCompat.registerReceiver(this, mActReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)`                                 |
| 通知权限(33+ 运行时)              | `WelcomeActivity.java:120` 附近                                         | `POST_NOTIFICATIONS` 加入现有权限收集数组(仅 API 33+);拒绝后 app 功能不受影响,仅通知不显示                                                                     |
| edge-to-edge 强制(35+)       | `MainActivity`、`WelcomeActivity`、`SettingsActivity`、`HistoryActivity` | insets 适配:`ViewCompat.setOnApplyWindowInsetsListener` 处理系统栏避让(可下沉到 `BaseActivity`);地图/抽屉/搜索框逐界面目视验证                                  |
| predictive back(36 默认启用)   | `MainActivity.java:272`                                               | `onBackPressed()` 重写迁移到 `OnBackPressedDispatcher` + `OnBackPressedCallback`,行为保持不变;`SettingsActivity`/`HistoryActivity` 用系统默认返回,目视验证 |

验证:构建 + lint 通过,CI 绿;另附真机手动清单(§6)。

### 4.3 分支③ `build/dependency-upgrades`(依赖)

- `androidx.appcompat:appcompat` 1.5.1 → 1.7.x
- `com.google.android.material:material` 1.7.0 → 1.13.x
- `androidx.constraintlayout:constraintlayout` 2.1.4 → 2.2.x
- `androidx.preference:preference` 1.2.1 → 当前稳定
- `androidx.lifecycle:lifecycle-viewmodel-ktx:2.5.1` → **`lifecycle-viewmodel`**(非 ktx 制品,纯 Java 项目无需 Kotlin
  stdlib)最新稳定
- 不动:`okhttp` 4.12.0(5.x 尚为 alpha)、`xlog` 1.11.1、`markwon` 4.6.2(已是最新)、junit/espresso 系列(已是新版本)
- 升级后核对 `proguard-rules.pro` 是否需要新增 keep 规则

具体版本号实现时以官方仓库当周稳定版为准。验证:构建 + 手动冒烟(启动、地图、设置、历史、模拟定位)。

### 4.4 分支④ `build/lint-baseline`(Lint 清零)

- 修复全部编译 warning(`-Xlint:unchecked/deprecation` 已在 `build.gradle:25-31` 打开)与 lint 报告项。
- 移除 `app/build.gradle:31` 的 `//noinspection ExpiredTargetSdkVersion`(targetSdk 36 后不再过期)。
- 目标:`./gradlew lint` 0 error 0 warning(确需保留的抑制项须逐条注释理由),作为阶段二重构的基线。

## 5. 不变量(回归判据)

- APK 产物命名规则不变;applicationId、签名不变。
- 全部用户可见功能与交互不变:启动页→地图、POI 搜索、选点模拟、摇杆(移动/按键两种)、通知栏显隐摇杆按钮、设置项、历史记录。
- CI 键名(`MAPS_API_KEY`、`MAPS_SAFE_CODE`)与注入方式不变。
- CI(build-check、build-release、codeql)保持工作。

## 6. 最终真机验证清单(用户执行)

1. 冷启动→欢迎页→地图正常,无状态栏/导航栏遮挡内容。
2. 申请定位权限、通知权限流程正常;拒绝通知权限后 app 仍可正常模拟。
3. 选点→启动模拟:前台通知出现,通知栏"显示/隐藏摇杆"按钮有效。
4. 摇杆两种类型移动正常,位置按速度递增。
5. 设置、历史界面进出与返回手势(含 predictive back 动画)正常。
6. 在 Android 12/13/14/15/16 设备(至少覆盖 14+ 一台)上模拟定位生效(第三方地图类 app 确认位置变化)。
7. `assembleRelease` 产物可安装运行(R8 无新问题)。

## 7. 风险与缓解

| 风险                                                                          | 缓解                                                                       |
|-----------------------------------------------------------------------------|--------------------------------------------------------------------------|
| AGP 9 迁移指南未列全的破坏性变更                                                         | 隔离在分支①,出问题仅回退该分支;实现时先通读官方迁移指南                                            |
| 老版百度 jar 在 Android 15/16 运行时不兼容(尤其 `:remote` 进程的 `com.baidu.location.f` 服务) | 真机清单第 6 项覆盖;若崩,百度 SDK 升级单独立项                                             |
| edge-to-edge 布局回归(4 个界面)                                                    | 分支②逐界面目视验证;不使用 `windowOptOutEdgeToEdgeEnforcement` 逃生门(targetSdk 36 已弃用) |
| 权限误删                                                                        | 只删 grep 确认无引用者;存疑保留并请示                                                   |
| secrets 插件替换引入密钥加载回归                                                        | 键名与 CI 注入不变;本地无 `local.properties` 键时空串默认不阻塞构建                           |

## 8. 后续

阶段二(代码架构重构:渐进式 MVVM、拆分 MainActivity、ServiceGo 通信接口化、Repository 化、补单测)
在本阶段全部合入后另行设计——届时代码形态已随本阶段变化,现在细化必然过时。
