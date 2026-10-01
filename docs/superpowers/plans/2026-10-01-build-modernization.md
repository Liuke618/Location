# 阶段一:构建体系现代化 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 GoGoGo 从 AGP 8.12.1/Gradle 8.13/minSdk 27/targetSdk 32 升级到 AGP 9.4.1/Gradle 9.8.0/minSdk 31/targetSdk 36,完成全部行为适配并保持用户可见功能不变。

**Architecture:** 单模块 `:app` 纯 Java Android 项目。按 spec 的四分支渐进推进:①工具链升级 → ②targetSdk 36 行为适配 → ③依赖升级 → ④Lint 清零。每个分支独立 PR、CI 绿后合入再开下一分支。

**Tech Stack:** Gradle 9.8.0,AGP 9.4.1,JDK 17,Java(无 Kotlin),百度 LBS 本地 jar。

**Spec:** `docs/superpowers/specs/2026-10-01-build-modernization-design.md`(实施者需同时阅读)

## Global Constraints

- 版本基线(精确值,不得改动):Gradle **9.8.0**、AGP **9.4.1**、JDK **17**、minSdk **31**、compileSdk/targetSdk **36**;appcompat **1.8.0**、material **1.14.0**、constraintlayout **2.2.2**、preference **1.2.1**、lifecycle-viewmodel **2.11.0**
- 保持不动:okhttp 4.12.0、xlog 1.11.1、markwon 4.6.2、junit 4.13.2、androidx.test 1.3.0/1.7.0、espresso 3.7.0、`app/libs/BaiduLBS_Android.jar`
- APK 命名规则不变:`Go_<versionName>_arm64-v8a_<buildType>.apk`
- CI 密钥键名与注入方式不变:`MAPS_API_KEY`、`MAPS_SAFE_CODE`(写入 local.properties)
- 不引入 Kotlin、DI 框架、Robolectric;不修改 versionCode/versionName;不做架构/包结构调整
- 每个分支必须 `./gradlew assembleDebug assembleRelease test lint` 通过且 GitHub Actions(build-check)绿后才开下一分支
- 每个 Task 恰好一个 commit,提交信息用仓库惯例的 conventional 格式(`build:`/`fix:`/`refactor:`/`chore:`/`ci:`)
- 本计划的"测试"均为构建/lint/命令验证(Android 框架绑定行为由 Task 14 真机清单覆盖,spec §6;不引入 Robolectric 是 spec §3 的既定边界)

---

## 分支① `build/toolchain-modernization`

### Task 1: Gradle 9.8 + AGP 9.4.1 升级与 DSL 迁移

**Files:**
- Modify: `gradle/wrapper/gradle-wrapper.properties:6`
- Modify: `build.gradle:10`
- Modify: `app/build.gradle`(多处,见下)

**Interfaces:**
- Consumes: 无
- Produces: AGP 9 新 DSL 的构建脚本;后续所有 Task 在此基线上构建。`BuildConfig` 仍由 secrets 插件生成(Task 3 替换,字段名不变)。

- [ ] **Step 1: 升级 Gradle wrapper**

`gradle/wrapper/gradle-wrapper.properties:6` 改为:

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.8.0-bin.zip
```

- [ ] **Step 2: 升级 AGP classpath**

`build.gradle:10` 改为:

```groovy
        classpath 'com.android.tools.build:gradle:9.4.1'
```

- [ ] **Step 3: 迁移 app/build.gradle 旧 DSL(只改写法和路径,数值暂不动)**

对 `app/build.gradle` 逐项修改:

1. 删除第 8 行 `ndkVersion = '26.2.11394342'`(项目无 externalNativeBuild/jniLibs 编译,jniLibs 走 `sourceSets` 预编译 so,NDK 版本无意义)。
2. 删除第 10 行 `buildToolsVersion = '36.0.0'`(交由 AGP 默认)。
3. 第 30-32 行,只改 DSL 名、数值不动,`//noinspection ExpiredTargetSdkVersion` 注释暂保留:

```groovy
        minSdk 27
        //noinspection ExpiredTargetSdkVersion
        targetSdk 32
```

4. 第 36 行 `resourceConfigurations += ['zh', 'zh-rCN', 'en', 'en-rUS']` 删除,在 `android {}` 块内新增:

```groovy
    androidResources {
        localeFilters += ['zh', 'zh-rCN', 'en', 'en-rUS']
    }
```

5. 第 57 行 `proguardFiles getDefaultProguardFile('proguard-android.txt'), 'proguard-rules.pro'` 改为(AGP 9 移除了 `proguard-android.txt`):

```groovy
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
```

6. 第 60 行删除 `multiDexEnabled = true`(minSdk 21+ 原生支持,属遗留)。
7. 第 72-75 行编译目标 Java 11 → 17:

```groovy
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
```

8. 第 77 行 `packagingOptions {` 改为 `packaging {`。
9. 第 15、21 行 keystore 路径修复(现为 Windows 反斜杠,非 Windows 构建必失败):

```groovy
            storeFile file('../keystore/GoGoGo.jks')
```

10. 第 97-111 行整块(注释 + `applicationVariants.configureEach`…)替换为(`applicationVariants`/`output.outputFile` 在 AGP 9 已移除):

注意:`androidComponents` 是与 `android {}` 平级的项目级扩展,放在 `android {}` 块**结束之后**、`dependencies` 之前。版本号在脚本级先取值(闭包作用域内解析不到 `defaultConfig`):

```groovy
def apkBaseName = "Go_${android.defaultConfig.versionName}_arm64-v8a"

androidComponents {
    onVariants { variant ->
        variant.outputs.each { output ->
            output.outputFileName.set("${apkBaseName}_${variant.buildType}.apk")
        }
    }
}
```

- [ ] **Step 4: 验证构建**

Run: `./gradlew --version`
Expected: Gradle 9.8.0,JVM 17(本机已装 17.0.19)。

Run: `./gradlew assembleDebug test`
Expected: BUILD SUCCESSFUL;产物名为 `app/build/outputs/apk/debug/Go_1.12.3_arm64-v8a_debug.apk`(命名迁移成功的判据)。

若 AGP 9 拒绝 `compileSdk = 32`(报 minimum supported compileSdk 类错误):把 `app/build.gradle` 的 `compileSdk = 32` 改为 `compileSdk = 36` 提前在本分支完成(compileSdk 只影响编译期,targetSdk 行为变更仍留分支②),并在 commit message 与 PR 描述中注明。

- [ ] **Step 5: Commit**

```bash
git add gradle/wrapper/gradle-wrapper.properties build.gradle app/build.gradle
git commit -m "build: upgrade to gradle 9.8 and agp 9.4.1"
```

### Task 2: minSdk 31 与 ServiceGo pre-S 分支删除

**Files:**
- Modify: `app/build.gradle:30`
- Modify: `app/src/main/java/com/zcshou/service/ServiceGo.java:205-234,258-289`

**Interfaces:**
- Consumes: Task 1 的新 DSL 基线。
- Produces: minSdk 31 基线;`ServiceGo.addTestProviderGPS()`/`addTestProviderNetwork()` 只保留 `ProviderProperties` 路径。

- [ ] **Step 1: minSdk 27 → 31**

`app/build.gradle` 第 30 行改为:

```groovy
        minSdk 31
```

- [ ] **Step 2: 删除 addTestProviderGPS 的 pre-S 分支**

`ServiceGo.java` 中 `addTestProviderGPS()`(现 216-234 行)整方法替换为(删除 `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` 判断,minSdk 31 下恒真,只留 `ProviderProperties` 路径;`@SuppressLint` 与注释保留):

```java
    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderGPS() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实GPS参数，不是随便写的)
            mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                    false, true, true, true, ProviderProperties.POWER_USAGE_HIGH, ProviderProperties.ACCURACY_FINE);
            if (!mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - addTestProviderGPS");
        }
    }
```

- [ ] **Step 3: 同样删除 addTestProviderNetwork 的 pre-S 分支**

`addTestProviderNetwork()`(现 269-289 行)整方法替换为(注意 catch 的异常类型保持 `SecurityException`):

```java
    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderNetwork() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实NETWORK参数，不是随便写的)
            mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                    true, true, true, true,
                    true, ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_COARSE);
            if (!mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
            }
        } catch (SecurityException e) {
            XLog.e("SERVICEGO: ERROR - addTestProviderNetwork");
        }
    }
```

- [ ] **Step 4: 清理 import 并验证**

删除 `ServiceGo.java` 中不再使用的 import(检查 `android.os.Build` 与 `android.location.Criteria`:后者在 `setLocationGPS()`/`setLocationNetwork()` 的 `setAccuracy(Criteria.ACCURACY_FINE/COARSE)` 仍在用,**保留**;`Build` 若无其他引用则删除)。

Run: `grep -n "Build\." app/src/main/java/com/zcshou/service/ServiceGo.java`
Expected: 无输出(则删除 `import android.os.Build;`)。

Run: `./gradlew assembleDebug test`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle app/src/main/java/com/zcshou/service/ServiceGo.java
git commit -m "build: raise minSdk to 31 and drop pre-S provider branches"
```

### Task 3: 替换 secrets-gradle-plugin

**Files:**
- Modify: `build.gradle:11-12`
- Modify: `app/build.gradle:3-4,28-42`

**Interfaces:**
- Consumes: 无
- Produces: `BuildConfig.MAPS_API_KEY`、`BuildConfig.MAPS_SAFE_CODE`(String,来源 local.properties,键缺失时为空串)——`GoApplication.java:36`、`MainActivity.java:921-922` 的引用零改动。

- [ ] **Step 1: 移除插件**

`build.gradle` 删除第 12 行 classpath(及第 11 行对应中文注释);`app/build.gradle` 删除第 4 行 `id "com.google.android.libraries.mapsplatform.secrets-gradle-plugin"` 及第 3 行注释。

- [ ] **Step 2: 内联读取 local.properties**

`app/build.gradle` 在 `plugins {}` 块之后、`android {}` 之前新增:

```groovy
// 从 local.properties 读取百度地图密钥并注入 BuildConfig(替代 secrets-gradle-plugin,键名与 CI 注入方式保持不变)
def localProps = new Properties()
def localPropsFile = rootProject.file('local.properties')
if (localPropsFile.exists()) {
    localPropsFile.withInputStream { localProps.load(it) }
}
def mapsApiKey = localProps.getProperty('MAPS_API_KEY', '')
def mapsSafeCode = localProps.getProperty('MAPS_SAFE_CODE', '')
if (mapsApiKey.isEmpty()) {
    logger.warn('MAPS_API_KEY 未在 local.properties 中配置,百度地图运行时将无法初始化')
}
```

- [ ] **Step 3: 注入 BuildConfig 字段**

`android.defaultConfig` 块内(第 35 行 `testInstrumentationRunner` 之后)新增:

```groovy
        buildConfigField 'String', 'MAPS_API_KEY', "\"${mapsApiKey}\""
        buildConfigField 'String', 'MAPS_SAFE_CODE', "\"${mapsSafeCode}\""
```

- [ ] **Step 4: 验证**

Run: `./gradlew assembleDebug && grep -c "MAPS" app/build/generated/buildConfig/debug/com/zcshou/gogogo/BuildConfig.java`
Expected: 输出 ≥ 2(两个字段都生成);构建日志出现 Step 2 的 warn(本机 local.properties 无键时属预期)。

- [ ] **Step 5: Commit**

```bash
git add build.gradle app/build.gradle
git commit -m "build: replace secrets plugin with inline buildConfigField"
```

### Task 4: CI 切换 temurin

**Files:**
- Modify: `.github/workflows/build-check.yml:40`
- Modify: `.github/workflows/build-release.yml:25`

- [ ] **Step 1: 两个 workflow 的 `distribution: 'adopt'` 均改为 `distribution: 'temurin'`**(adopt 已废弃;JDK 版本号 17 不变)

- [ ] **Step 2: Commit**(本地无 CI 可验,Task 11 推送后由 Actions 验证)

```bash
git add .github/workflows/build-check.yml .github/workflows/build-release.yml
git commit -m "ci: switch java distribution to temurin"
```

### Task 5: 分支①收尾:推送、PR、CI、合入

- [ ] **Step 1: 全量验证** Run: `./gradlew assembleDebug assembleRelease test lint` — Expected: 全部 BUILD SUCCESSFUL(release 需 R8 通过,验证 `proguard-android-optimize.txt` 与现有 keep 规则兼容)。
- [ ] **Step 2: 推送并建 PR**(本地 master 上已有本分支的 4 个 commit 与 spec 提交,一并入 PR,合并后 master 即同步):

```bash
git checkout -b build/toolchain-modernization
git push -u origin build/toolchain-modernization
gh pr create --title "build: modernize toolchain (gradle 9.8 / agp 9.4.1 / minSdk 31)" --body "Implements spec §4.1, see docs/superpowers/specs/2026-10-01-build-modernization-design.md. Also carries the spec doc commit."
```

- [ ] **Step 3: CI 绿后合入** Run: `gh pr checks --watch` — Expected: Build Check 通过。Run: `gh pr merge --merge` 并 `git checkout master && git pull`。
- **只有本分支合入后才能开始分支②。**

---

## 分支② `build/targetsdk-36`

### Task 6: compileSdk/targetSdk 36 + Manifest 前台服务声明

**Files:**
- Modify: `app/build.gradle:9,29-31`
- Modify: `app/src/main/AndroidManifest.xml:17,55,71-73`

**Interfaces:**
- Consumes: 分支①的构建基线。
- Produces: Manifest 中 `ServiceGo` 带 `foregroundServiceType="location"`——Task 7 的三参 `startForeground` 依赖此声明。

- [ ] **Step 1: 开分支** Run: `git checkout -b build/targetsdk-36`

- [ ] **Step 2: SDK 数值**

`app/build.gradle`:`compileSdk = 32` → `compileSdk = 36`;`targetSdk 32` → `targetSdk 36`;删除其上的 `//noinspection ExpiredTargetSdkVersion` 注释行。

- [ ] **Step 3: Manifest**

第 17 行 `<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />` 之后新增:

```xml
    <!-- targetSdk 34+ 前台定位服务必需 -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
```

第 55 行删除 `android:requestLegacyExternalStorage="true"`(API 30+ 已无效)。

第 71-73 行 ServiceGo 声明改为:

```xml
        <service
            android:exported="false"
            android:foregroundServiceType="location"
            android:name="com.zcshou.service.ServiceGo" />
```

- [ ] **Step 4: 验证与提交** Run: `./gradlew assembleDebug` — Expected: BUILD SUCCESSFUL。

```bash
git add app/build.gradle app/src/main/AndroidManifest.xml
git commit -m "build: target sdk 36 and declare location foreground service type"
```

### Task 7: ServiceGo 适配 Android 14+(前台服务三参 + receiver 导出性)

**Files:**
- Modify: `app/src/main/java/com/zcshou/service/ServiceGo.java`(imports、onCreate、initNotification:121,148)

**Interfaces:**
- Consumes: Task 6 的 Manifest `foregroundServiceType="location"`(三参 startForeground 的前置条件)。
- Produces: 无跨任务接口。

- [ ] **Step 1: 新增 import**

```java
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import androidx.core.content.ContextCompat;
```

- [ ] **Step 2: onCreate 顶部加定位权限守卫**(Android 14+ 启动 location 类型 FGS 前必须已授予定位权限,否则 SecurityException)

`onCreate()` 的 `super.onCreate();` 之后插入:

```java
        // Android 14+ 要求启动 location 类型前台服务前定位权限已授予
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            XLog.e("SERVICEGO: ERROR - location permission not granted, stop service");
            stopSelf();
            return;
        }
```

- [ ] **Step 3: initNotification 两处修改**

`registerReceiver(mActReceiver, filter);`(现第 121 行)改为(targetSdk 34+ 未声明导出性直接崩溃):

```java
        ContextCompat.registerReceiver(this, mActReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
```

`startForeground(SERVICE_GO_NOTE_ID, notification);`(现第 148 行)改为(minSdk 31 > 29,三参 API 无需版本分支):

```java
        startForeground(SERVICE_GO_NOTE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
```

- [ ] **Step 4: 验证与提交** Run: `./gradlew assembleDebug test` — Expected: BUILD SUCCESSFUL。

```bash
git add app/src/main/java/com/zcshou/service/ServiceGo.java
git commit -m "fix: adapt foreground service and receiver for android 14+"
```

### Task 8: POST_NOTIFICATIONS 运行时申请(Android 13+)

**Files:**
- Modify: `app/src/main/java/com/zcshou/gogogo/WelcomeActivity.java`(imports:3-30,常量:38,checkDefaultPermissions:94-122,onRequestPermissionsResult:81-92)

**Interfaces:**
- Consumes: 现有权限流程(`SDK_PERMISSION_REQUEST = 127` 全有全无门控)。
- Produces: 新回调码 `SDK_NOTIFICATION_REQUEST = 128`,**不参与** `isPermission` 门控——拒绝通知权限不得阻塞进入主界面(spec §4.2:拒绝后 app 功能不受影响,仅通知不显示)。

- [ ] **Step 1: 常量与方法**

第 38 行 `SDK_PERMISSION_REQUEST = 127` 之后新增常量:

```java
    private static final int SDK_NOTIFICATION_REQUEST = 128;
```

新增 import `android.os.Build`。在 `checkDefaultPermissions()` 方法之后新增:

```java
    // 通知权限仅影响前台通知显示,拒绝后不影响 app 功能,故不参与 isPermission 门控
    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, SDK_NOTIFICATION_REQUEST);
        }
    }
```

- [ ] **Step 2: 接入现有流程**

`checkDefaultPermissions()` 中第 117-121 行的 if/else 改为(两个分支都追加通知权限申请):

```java
        if (ReqPermissions.isEmpty()) {
            isPermission = true;
            checkNotificationPermission();
        } else {
            requestPermissions(ReqPermissions.toArray(new String[0]), SDK_PERMISSION_REQUEST);
        }
```

`onRequestPermissionsResult` 中,第 82 行 `if (requestCode == SDK_PERMISSION_REQUEST) {` 块的 **末尾**(第 89 行 `isPermission = true;` 之后)追加一行:

```java
                checkNotificationPermission();
```

即:关键权限全部授予后紧接着申请通知权限;128 的回调落入现有 `super.onRequestPermissionsResult(...)`,不做任何处理(拒绝不 toast、不置 false)。

- [ ] **Step 3: 验证与提交** Run: `./gradlew assembleDebug test` — Expected: BUILD SUCCESSFUL。

```bash
git add app/src/main/java/com/zcshou/gogogo/WelcomeActivity.java
git commit -m "fix: request notification permission on android 13+"
```

### Task 9: edge-to-edge 适配(targetSdk 35+ 强制)

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml:16-22`
- Modify: `app/src/main/res/layout/activity_history.xml:2-8`
- Modify: `app/src/main/res/layout/activity_settings.xml:1-3`
- Modify: `app/src/main/java/com/zcshou/gogogo/WelcomeActivity.java`(onCreate:46-58)

**Interfaces:**
- Consumes: 无
- Produces: 无跨任务接口。MainActivity/HistoryActivity/SettingsActivity 均继承 `BaseActivity`,但本任务用布局属性即可覆盖三者,无需改 BaseActivity;WelcomeActivity 单独代码处理。

- [ ] **Step 1: activity_main.xml** — NavigationView(第 16-22 行)加 `android:fitsSystemWindows="true"`,与根 DrawerLayout(第 8 行已有)配合,使抽屉内容避开状态栏、内容区由 DrawerLayout 自行消费 insets:

```xml
    <com.google.android.material.navigation.NavigationView
        android:id="@+id/nav_view"
        android:layout_width="wrap_content"
        android:layout_height="match_parent"
        android:layout_gravity="start"
        android:fitsSystemWindows="true"
        app:headerLayout="@layout/nav_header"
        app:menu="@menu/menu_nav" />
```

- [ ] **Step 2: activity_history.xml 与 activity_settings.xml** — 两个根 `<LinearLayout>` 均加 `android:fitsSystemWindows="true"`(两界面用默认 AppTheme 自带 ActionBar,根布局消费 insets 后 ActionBar 不与状态栏重叠)。

- [ ] **Step 3: WelcomeActivity** — 全屏图背景不变;底部 CheckBox(边距 20dp)可能压入导航栏,在其 `onCreate()` 的 `setContentView(...)` 之后新增(需 import `android.view.View`、`androidx.core.view.ViewCompat`、`androidx.core.view.WindowInsetsCompat`、`androidx.core.graphics.Insets`):

```java
        // edge-to-edge 强制后,底部协议勾选框避开导航栏
        View checkAgreement = findViewById(R.id.check_agreement);
        int margin20dp = (int) (20 * getResources().getDisplayMetrics().density);
        int margin8dp = (int) (8 * getResources().getDisplayMetrics().density);
        ViewCompat.setOnApplyWindowInsetsListener(checkAgreement, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            lp.bottomMargin = Math.max(margin20dp, bars.bottom + margin8dp);
            v.setLayoutParams(lp);
            return windowInsets;
        });
```

需补 import `android.widget.FrameLayout`(merge 子视图挂载在 content FrameLayout 下,LayoutParams 为 FrameLayout.LayoutParams)。启动按钮下边距 100dp 大于常见导航栏高度,无需处理。

- [ ] **Step 4: 条件回退**(仅当 Task 14 目视验证发现 MainActivity 顶部 Toolbar 与状态栏重叠时应用,否则跳过):在 `MainActivity.onCreate()` 的 `setContentView(R.layout.activity_main);`(第 170 行)后加:

```java
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.toolbar), (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), bars.top + v.getPaddingTop(), v.getPaddingRight(), v.getPaddingBottom());
            return WindowInsetsCompat.CONSUMED;
        });
```

- [ ] **Step 5: 验证与提交** Run: `./gradlew assembleDebug` — Expected: BUILD SUCCESSFUL(布局视觉效果在 Task 14 真机验证)。

```bash
git add app/src/main/res/layout/activity_main.xml app/src/main/res/layout/activity_history.xml app/src/main/res/layout/activity_settings.xml app/src/main/java/com/zcshou/gogogo/WelcomeActivity.java
git commit -m "fix: adapt layouts for edge-to-edge enforcement"
```

### Task 10: 返回键迁移 OnBackPressedDispatcher(targetSdk 36 默认启用 predictive back)

**Files:**
- Modify: `app/src/main/java/com/zcshou/gogogo/MainActivity.java`(onCreate ~170 行区域、onBackPressed:271-274)

**Interfaces:**
- Consumes: androidx.activity(随 appcompat 1.8.0 传递依赖,含 OnBackPressedDispatcher)。
- Produces: 无。

- [ ] **Step 1:** 删除第 271-274 行的 `onBackPressed()` 重写整块:

```java
    @Override
    public void onBackPressed() {
        moveTaskToBack(false);
    }
```

- [ ] **Step 2:** 在 `onCreate()` 中 `setContentView(R.layout.activity_main);` 之后新增(行为等价:按返回 = 退到后台而非销毁):

```java
        // predictive back(targetSdk 36 默认启用):迁移到 OnBackPressedDispatcher,保持"返回即退后台"行为
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                moveTaskToBack(false);
            }
        });
```

新增 import `androidx.activity.OnBackPressedCallback`。

- [ ] **Step 3: 验证与提交** Run: `./gradlew assembleDebug test` — Expected: BUILD SUCCESSFUL。

```bash
git add app/src/main/java/com/zcshou/gogogo/MainActivity.java
git commit -m "refactor: migrate back handling to OnBackPressedDispatcher"
```

### Task 11: 权限审计

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: 无
- Produces: 无。

- [ ] **Step 1: 逐权限取证**(全部在 `app/src/main/java/` 范围内 grep;Manifest 自身不算引用):

```bash
grep -rn "MANAGE_WRITE_SETTINGS\|Settings.System\|isWriteSettings" app/src/main/java/   # WRITE_SETTINGS
grep -rn "MOUNT_UNMOUNT\|MountService" app/src/main/java/                                # MOUNT_UNMOUNT_FILESYSTEMS
grep -rn "READ_LOGS\|Log.isLoggable\|logcat" app/src/main/java/                          # READ_LOGS
grep -rn "REPLACE_EXISTING" app/src/main/java/                                           # REPLACE_EXISTING_PACKAGE
grep -rn "DOWNLOAD_WITHOUT_NOTIFICATION\|DOWNLOAD_MANAGER" app/src/main/java/            # DOWNLOAD_WITHOUT_NOTIFICATION
grep -rn "REQUEST_INSTALL\|installApk\|ACTION_INSTALL" app/src/main/java/                # REQUEST_INSTALL_PACKAGES(MainActivity 有更新弹窗流程,预期在用)
grep -rn "READ_EXTERNAL_STORAGE\|READ_PHONE_STATE" app/src/main/java/                    # 预期命中 WelcomeActivity:108-115
grep -rn "ACCESS_BACKGROUND_LOCATION\|setIsFrom\|BaiduLocation" app/src/main/java/       # 后台定位
```

- [ ] **Step 2: 决策执行**

- 预期零引用、直接从 Manifest 删除:`WRITE_SETTINGS`、`MOUNT_UNMOUNT_FILESYSTEMS`、`READ_LOGS`、`REPLACE_EXISTING_PACKAGE`、`DOWNLOAD_WITHOUT_NOTIFICATION`。**若任一 grep 实际命中,该权限不删,记入汇报。**
- 确认在用、保留:`REQUEST_INSTALL_PACKAGES`(更新弹窗→安装 APK 流程)、`SYSTEM_ALERT_WINDOW`(摇杆悬浮窗)、`ACCESS_MOCK_LOCATION`(核心功能)、`INTERNET`/`ACCESS_NETWORK_STATE`/`ACCESS_WIFI_STATE`/`CHANGE_WIFI_STATE`(网络与 GPS 检测)、`VIBRATE`/`WAKE_LOCK`(摇杆/服务)、四个定位权限与 `FOREGROUND_SERVICE*`。
- **不删、列入 Task 15 向用户请示**(代码在用但疑似过时):`READ_EXTERNAL_STORAGE`、`READ_PHONE_STATE`(WelcomeActivity:108-115 运行时申请;33+ 前者已无效果)、`ACCESS_BACKGROUND_LOCATION`(Manifest 声明但从未运行时申请;模拟定位走前台服务,前台定位已足够)。

- [ ] **Step 3: 验证与提交** Run: `./gradlew assembleDebug` — Expected: BUILD SUCCESSFUL。

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "chore: remove unused manifest permissions"
```

### Task 12: 分支②收尾

- [ ] **Step 1:** Run: `./gradlew assembleDebug assembleRelease test lint` — Expected: 全部通过。
- [ ] **Step 2:**

```bash
git push -u origin build/targetsdk-36
gh pr create --title "build: target sdk 36 with android 14+ adaptations" --body "Implements spec §4.2, see docs/superpowers/specs/2026-10-01-build-modernization-design.md"
gh pr checks --watch && gh pr merge --merge && git checkout master && git pull
```

---

## 分支③ `build/dependency-upgrades`

### Task 13: androidx 依赖升级

**Files:**
- Modify: `app/build.gradle:114-131`(dependencies 块)

**Interfaces:**
- Consumes: Task 10 使用的 `androidx.activity`(升级 appcompat 后传递版本更高,`OnBackPressedDispatcher` API 不变)。
- Produces: 无。

- [ ] **Step 1: 开分支** Run: `git checkout -b build/dependency-upgrades`

- [ ] **Step 2: 逐项替换版本**(只动版本号与制品名,顺序位置不变):

```groovy
    implementation 'androidx.lifecycle:lifecycle-viewmodel:2.11.0'   // 原 lifecycle-viewmodel-ktx:2.5.1(纯 Java 项目无需 ktx 制品)
    implementation 'androidx.appcompat:appcompat:1.8.0'              // 原 1.5.1
    implementation 'androidx.constraintlayout:constraintlayout:2.2.2' // 原 2.1.4
    implementation 'androidx.preference:preference:1.2.1'            // 已是最新,不动
    implementation 'com.google.android.material:material:1.14.0'     // 原 1.7.0
```

其余(okhttp 4.12.0、xlog 1.11.1、markwon 4.6.2、test 系列及 BaiduLBS jar)保持不变,与 Global Constraints 一致。

- [ ] **Step 3: 验证** Run: `./gradlew assembleDebug assembleRelease test` — Expected: BUILD SUCCESSFUL(R8 release 通过说明 `proguard-rules.pro` 无需新增 keep;若 R8 报 missing class,按报错补 `-dontwarn` 并在 commit message 注明)。
- [ ] **Step 4:**

```bash
git add app/build.gradle
git commit -m "build: upgrade androidx dependencies"
git push -u origin build/dependency-upgrades
gh pr create --title "build: upgrade androidx dependencies" --body "Implements spec §4.3"
gh pr checks --watch && gh pr merge --merge && git checkout master && git pull
```

---

## 分支④ `build/lint-baseline`

### Task 14: Lint 清零

**Files:**
- Modify: 视 lint 报告而定(`app/src/main/java/` 与 `app/src/main/res/`)

**Interfaces:**
- Consumes: 分支①-③全部产出。
- Produces: 0 error 0 warning 的 lint 基线(阶段二重构的前提)。

- [ ] **Step 1: 开分支并取报告** Run: `git checkout -b build/lint-baseline && ./gradlew lint` — 记录 `app/build/reports/lint-results-debug.html` 全部 error/warning。
- [ ] **Step 2: 逐项修复**。已知预期项:
  - `ServiceGo.addTestProviderGPS/addTestProviderNetwork` 上的 `@SuppressLint("wrongconstant")`:minSdk 31 + `ProviderProperties` 直接写法后 lint 应已不再报错,可删除两处注解验证。
  - Java 17/SDK 36 下新增的 deprecation(如 `getSerializableExtra` 系、`Color.parseColor` 等,以报告为准):按 lint 建议的最小等价改法处理,**不改变行为**。
  - 确需保留的抑制项:每条 `@SuppressLint`/`tools:ignore` 注释必须写明理由。
- [ ] **Step 3: 收敛验证** Run: `./gradlew lint` — Expected: 0 error,0 warning(HTML 报告全绿)。
- [ ] **Step 4:**

```bash
git add -A
git commit -m "chore: fix all lint warnings"
git push -u origin build/lint-baseline
gh pr create --title "chore: zero lint warnings as refactor baseline" --body "Implements spec §4.4"
gh pr checks --watch && gh pr merge --merge && git checkout master && git pull
```

---

## 收尾

### Task 15: 真机验证与阶段汇报

- [ ] **Step 1: 用户真机清单**(spec §6,需要用户提供 Android 14+ 设备):冷启动与四界面布局无遮挡;定位/通知权限流程,拒绝通知权限后模拟功能仍可用;前台通知与摇杆显隐按钮;摇杆移动;返回手势(含 predictive back 动画);Android 12/14+/15/16 设备上第三方地图确认模拟生效;release APK 安装运行。
- [ ] **Step 2: 向用户请示三项权限去留**(Task 11 Step 2 记录的 `READ_EXTERNAL_STORAGE`、`READ_PHONE_STATE`、`ACCESS_BACKGROUND_LOCATION`,附 grep 取证),按用户决定执行清理(走独立小 PR)。
- [ ] **Step 3: 汇报阶段一完成状态,提议启动阶段二(架构重构)的 brainstorming。**
