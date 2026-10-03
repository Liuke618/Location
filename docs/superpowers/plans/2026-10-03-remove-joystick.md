# 移除摇杆子系统(移动模拟 + 悬浮控制面板)实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 整体删除 `joystick` 子系统及其全部挂载(服务托管/通知按钮/设置项/资源/死代码/悬浮窗权限),应用退化为纯静态虚拟定位。

**Architecture:** 四个独立可构建的 commit:① ServiceGo 解耦 + joystick 包删除(编译闭包)→ ② 悬浮窗权限门槛删除 → ③ 设置项与资源清理 → ④ 死代码与文档收尾。`MockLocationController` 接口、TestProvider 供给循环、静态定位 GO 流程全部不动。

**Tech Stack:** 纯 Java Android 单模块(`:app`),AGP 9.4.1 / Gradle 9.8 / JDK 17,Baidu LBS 本地 jar,JVM 测试(JUnit4)。

**Spec:** `docs/superpowers/specs/2026-10-03-remove-joystick-design.md`(执行者须先读 spec;本计划按 spec §6 的 commit 划分展开)

## Global Constraints

- 构建命令一律 `sh gradlew ...`(gradlew 无执行位,**禁止 chmod**);JDK 17
- **CRLF 文件**:`MainActivity.java`、`FragmentSettings.java` 编辑后必须验证行尾未变(见各任务验证步);`ServiceGo.java`/`GoUtils.java`/XML 均为 LF
- lint 标准 0 error 0 warning;不得新增 `@SuppressLint`/`tools:ignore`/`noinspection`
- 注释中文,匹配所在文件风格;纯 Java,不引入新依赖
- 所有 commit 落在本地 master,**不推送**(本机 github.com 不可达,origin 无推送权限)
- `AGENTS.md` 是 **untracked** 文件:任务 4 只编辑其内容,**不要 git add/commit** 它
- 删除类任务无新测试可写——回归保障 = 既有 JVM 测试套件 + `assembleDebug` + 最终全量门槛,不发明新测试

---

### Task 1: ServiceGo 解耦 + joystick 包删除(commit ①)

**Files:**
- Delete: `app/src/main/java/com/zcshou/joystick/`(JoyStick.java、RockerView.java、ButtonView.java 整目录)
- Modify: `app/src/main/java/com/zcshou/service/ServiceGo.java`(多处)
- Modify: `app/src/main/java/com/zcshou/ui/search/PoiSearchHelper.java:29`(注释)
- Modify: `app/src/main/java/com/zcshou/ui/map/MainActivity.java:88`(注释,CRLF)
- Modify: `app/src/main/res/values/strings.xml:88-89`(删 note_show/note_hide)

**Interfaces:**
- Produces: `ServiceGo` 不再依赖 `com.zcshou.joystick.*`;`ServiceGoBinder.setPosition(lng, lat, alt)` 契约不变;后续任务不消费本任务产出

- [ ] **Step 1: 删除 joystick 包**

```bash
git rm -r app/src/main/java/com/zcshou/joystick
```

- [ ] **Step 2: ServiceGo.java — 删除 import**

删除两行:

```java
import com.zcshou.domain.LocationMath;
```

```java
import com.zcshou.joystick.JoyStick;
```

同时删除已无引用的 `import android.content.BroadcastReceiver;` 与 `import android.content.IntentFilter;`(Step 5/8 删光其使用点后)。`ContextCompat`、`Intent`、`Context` 均仍有使用,**保留**。

- [ ] **Step 3: ServiceGo.java — 删除常量与字段**

删除以下各行(保持其余字段不动;`DEFAULT_LAT`/`DEFAULT_LNG`/`DEFAULT_ALT` 保留):

```java
    public static final float DEFAULT_BEA = 0.0F;
```

```java
    private float mCurBea = DEFAULT_BEA;
```
(位于 `mCurAlt` 字段之后;`mCurLat`/`mCurLng`/`mCurAlt` 三个字段保留。)

```java
    private double mSpeed = 1.2;        /* 默认的速度，单位 m/s */
```

```java
    private static final String SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW = "ShowJoyStick";
    private static final String SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE = "HideJoyStick";
```

```java
    private NoteActionReceiver mActReceiver;
    // 摇杆相关
    private JoyStick mJoyStick;
```

- [ ] **Step 4: ServiceGo.java — onCreate/onStartCommand/onDestroy 去摇杆调用**

onCreate 中:

```java
        initGoLocation();

        initJoyStick();
    }
```
改为:
```java
        initGoLocation();
    }
```

onStartCommand 中删除整块:
```java

        if (mJoyStick != null) {
            mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
        }
```

onDestroy 中删除整块:
```java

        if (mJoyStick != null) {
            mJoyStick.destroy();
        }
```

- [ ] **Step 5: ServiceGo.java — initNotification 去通知按钮**

方法开头删除 receiver 注册段:
```java
        mActReceiver = new NoteActionReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW);
        filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE);
        // androidx.core 1.16.0 起可用 ContextCompat.RECEIVER_NOT_EXPORTED（与平台常量行为一致）
        ContextCompat.registerReceiver(this, mActReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

```

删除 PendingIntent 准备段:
```java
        // NOT_EXPORTED receiver 需要 setPackage 才能收到通知栏按钮的隐式广播(API 34+)
        Intent showIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW).setPackage(getPackageName());
        PendingIntent showPendingPI = PendingIntent.getBroadcast(this, 0, showIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent hideIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE).setPackage(getPackageName());
        PendingIntent hidePendingPI = PendingIntent.getBroadcast(this, 0, hideIntent, PendingIntent.FLAG_IMMUTABLE);
```

删除 builder 中两行:
```java
                .addAction(new NotificationCompat.Action(null, getResources().getString(R.string.note_show), showPendingPI))
                .addAction(new NotificationCompat.Action(null, getResources().getString(R.string.note_hide), hidePendingPI))
```

`clickPI`(点击回 MainActivity)与 `startForeground(SERVICE_GO_NOTE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)` **保留**。

- [ ] **Step 6: ServiceGo.java — 删除 initJoyStick() 方法与 NoteActionReceiver 类**

整段删除(从 `private void initJoyStick() {` 到其闭合 `}`,含监听器回调 onMoveInfo/onPositionInfo——这是 `LocationMath` 的唯一 ServiceGo 使用点):

```java
    private void initJoyStick() {
        mJoyStick = new JoyStick(this);
        mJoyStick.setListener(new JoyStick.JoyStickClickListener() {
            @Override
            public void onMoveInfo(double speed, double disLng, double disLat, double angle) {
                mSpeed = speed;
                // 根据当前的经纬度和距离，计算下一个经纬度
                // Latitude: 1 deg = 110.574 km // 纬度的每度的距离大约为 110.574km
                // Longitude: 1 deg = 111.320*cos(latitude) km  // 经度的每度的距离从0km到111km不等
                // 具体见：http://wp.mlab.tw/?p=2200
                mCurLng += LocationMath.lngDelta(disLng, mCurLat);
                mCurLat += LocationMath.latDelta(disLat);
                mCurBea = (float) angle;
            }

            @Override
            public void onPositionInfo(double lng, double lat, double alt) {
                mCurLng = lng;
                mCurLat = lat;
                mCurAlt = alt;
            }
        });
        mJoyStick.show();
    }
```

整段删除:
```java
    public class NoteActionReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                if (action.equals(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW)) {
                    mJoyStick.show();
                }

                if (action.equals(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE)) {
                    mJoyStick.hide();
                }
            }
        }
    }
```

- [ ] **Step 7: ServiceGo.java — 位置数据去速度/方向**

`setLocationGPS()` 与 `setLocationNetwork()` 中各删除两行(数值语义:静止位置 speed/bearing 由 `Location` 默认 0 承担,与原值一致):

```java
            loc.setBearing(mCurBea);                       // 方向（度）
```
```java
            loc.setSpeed((float) mSpeed);
```

- [ ] **Step 8: ServiceGo.java — ServiceGoBinder.setPosition 去摇杆刷新**

```java
            mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
            mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
        }
```
改为:
```java
            mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
        }
```

- [ ] **Step 9: 修正两处提及 JoyStick 的注释**

`PoiSearchHelper.java`(LF):

```java
    /* 搜索结果 Map 行键名（SimpleAdapter 列契约；MainActivity.POI_* 委托至本类，JoyStick 引用不受影响） */
```
改为:
```java
    /* 搜索结果 Map 行键名（SimpleAdapter 列契约；MainActivity.POI_* 委托至本类） */
```

`MainActivity.java`(**CRLF**)——先记录当前行尾状态:

```bash
file app/src/main/java/com/zcshou/ui/map/MainActivity.java
```
确认输出含 `CRLF`。然后:
```java
    /* 对外（POI_* 键名为搜索结果 SimpleAdapter 列契约，随 getMapList 收编于 PoiSearchHelper，JoyStick 引用不受影响） */
```
改为:
```java
    /* 对外（POI_* 键名为搜索结果 SimpleAdapter 列契约，随 getMapList 收编于 PoiSearchHelper） */
```

- [ ] **Step 10: 删除 note_show / note_hide 字符串**

`strings.xml` 删除:
```xml
    <string name="note_show">显示摇杆</string>
    <string name="note_hide">隐藏摇杆</string>
```
(注释行 `<!-- 通知栏 -->` 删除后为空区块,连同其上空行一并清理;`<!-- 摇杆 -->` 块留待 Task 3。)

- [ ] **Step 11: 验证构建与测试**

```bash
sh gradlew assembleDebug test
```
Expected: BUILD SUCCESSFUL,测试全绿(LocationMathTest 仍在且通过——LocationMath 本任务不删)。

- [ ] **Step 12: CRLF 与残留检查**

```bash
file app/src/main/java/com/zcshou/ui/map/MainActivity.java
git diff --numstat
```
Expected: MainActivity 仍含 `CRLF`;其 diff 行数 ≤ 3(只改了注释行)。若整文件被重写(数百行 diff),`git checkout -- <file>` 后改用 `perl -pi -e 's/，JoyStick 引用不受影响//'` 类单行替换重做。另跑 `grep -rn 'JoyStick\|joystick' app/src/main/java` 确认仅剩 `PoiSearchHelper.java`/`MainActivity.java` 已修正后的注释外的零残留(此时 `FragmentSettings.java` 仍有 `setting_joystick_type` 字符串引用,属 Task 3 范围,不算残留)。

- [ ] **Step 13: Commit**

```bash
git add -A app/src/main/java app/src/main/res
git commit -m "refactor: remove joystick subsystem and movement simulation"
```

---

### Task 2: 悬浮窗权限门槛删除(commit ②)

**Files:**
- Modify: `app/src/main/AndroidManifest.xml:40-41`
- Modify: `app/src/main/java/com/zcshou/ui/map/MainActivity.java:651-655`(CRLF)
- Modify: `app/src/main/java/com/zcshou/utils/GoUtils.java:13,191-209`

**Interfaces:**
- Consumes: Task 1 后的代码形态(无其他依赖)

- [ ] **Step 1: AndroidManifest.xml 删除权限声明**

删除(含上方注释行):
```xml
    <!-- 允许一个程序打开窗口使用 TYPE_SYSTEM_ALERT，显示在其他所有程序的顶层 -->
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```
`ACCESS_MOCK_LOCATION`(:37-39)及其余权限**保留**。

- [ ] **Step 2: MainActivity 删除 canDrawOverlays 门槛(CRLF)**

`doGoLocation` 中删除整块:
```java
        if (!Settings.canDrawOverlays(getApplicationContext())) {//悬浮窗权限判断
            GoUtils.showEnableFloatWindowDialog(this);
            XLog.e("无悬浮窗权限!");
            return;
        }

```

GO 流程变为:网络检查 → GPS 检查 → (`isRunning` 分支) / 模拟位置权限检查(else 分支),其余不动。**`import android.provider.Settings;` 保留**——:363 的开发者选项跳转仍在使用。编辑后跑 Task 1 Step 12 的 CRLF 验证。

- [ ] **Step 3: GoUtils 删除引导弹窗与 Uri import**

删除整个方法(含注释行):
```java
    //提醒开启悬浮窗的弹框
    public static  void showEnableFloatWindowDialog(Context context) {
        new AlertDialog.Builder(context)
                .setTitle("启用悬浮窗")//这里是表头的内容
                .setMessage("为了模拟定位的稳定性，建议开启\"显示悬浮窗\"选项")//这里是中间显示的具体信息
                .setPositiveButton("设置",(dialog, which) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.getPackageName()));
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(intent);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                })
                .setNegativeButton("取消", (dialog, which) -> {

                })
                .show();
    }

```

删除 import:
```java
import android.net.Uri;
```
(`Settings` import 保留——另有 3 处使用;`TimeCount` 类本任务不动,Task 4 处理。)

- [ ] **Step 4: 验证**

```bash
sh gradlew assembleDebug test
grep -rn 'canDrawOverlays\|showEnableFloatWindowDialog\|SYSTEM_ALERT_WINDOW' app/src/main
```
Expected: BUILD SUCCESSFUL;grep 零命中。

- [ ] **Step 5: Commit**

```bash
git add -A app/src/main
git commit -m "refactor: drop SYSTEM_ALERT_WINDOW permission gate"
```

---

### Task 3: 设置项与资源清理(commit ③)

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/xml/preferences_main.xml`
- Delete: `app/src/main/res/values/arrays.xml`
- Modify: `app/src/main/java/com/zcshou/ui/settings/FragmentSettings.java`(CRLF)
- Delete: `app/src/main/res/layout/joystick.xml`、`joystick_button.xml`、`joystick_history.xml`、`joystick_map.xml`
- Delete: 17 个摇杆独占 drawable(Step 5 清单)
- Modify: `app/src/main/res/layout/location_input.xml` + `MainActivity.java`(CRLF,id 改名)

**Interfaces:**
- Produces: 设置页"移动"分组仅剩海拔;`app/src/main` 中 `joystick` 字样零残留(Step 8 验证)

- [ ] **Step 1: strings.xml 三处删改**

① 删除:
```xml
    <string name="app_search_tips">请输入查找内容</string>
```

② `<!-- 移动相关参数 -->` 区块:注释改为 `<!-- 位置模拟参数 -->`,删除以下 7 行,`setting_group_move` 的值 `移动` 改为 `位置模拟`(key 不改,仅 preferences_main.xml 一处引用):
```xml
    <string name="setting_joystick_type">摇杆类型</string>
    <string name="setting_walk">步行速度(米/秒)</string>
    <string name="setting_walk_default">1.2</string>
    <string name="setting_run">跑步速度(米/秒)</string>
    <string name="setting_run_default">3.6</string>
    <string name="setting_bike">驾驶速度(米/秒)</string>
    <string name="setting_bike_default">10.0</string>
```

③ 删除整个摇杆块(8 行 + 注释):
```xml
    <!-- 摇杆 -->
    <string name="joystick_map_tips">搜索或点击地图位置</string>
    <string name="joystick_history_tips">搜索或点击历史位置</string>
    <string name="joystick_history">历史记录</string>
    <string name="joystick_map">地图</string>
    <string name="joystick_move">移动</string>
    <string name="joystick_walk">步行</string>
    <string name="joystick_run">跑步</string>
    <string name="joystick_bike">自行车</string>
```

- [ ] **Step 2: preferences_main.xml 收缩第一分组**

`setting_group_move` 分组内 4 项(摇杆类型 ListPreference、步行/跑步/驾驶 EditTextPreference)删除,只留海拔。分组最终形态:

```xml
    <PreferenceCategory
        app:title="@string/setting_group_move"
        app:iconSpaceReserved="false">

        <EditTextPreference
            app:key="setting_altitude"
            app:title="@string/setting_altitude"
            app:defaultValue="@string/setting_altitude_default"
            app:summary="@string/setting_altitude_default"
            app:iconSpaceReserved="false"/>
    </PreferenceCategory>
```

- [ ] **Step 3: arrays.xml 删除文件**

两个 string-array 均为摇杆类型专属,全仓无其他 `@array/` 引用,文件清空即删:

```bash
grep -rn '@array/' app/src/main/res --include='*.xml'   # 应仅 preferences_main.xml 两行(即将删除的摇杆项)
git rm app/src/main/res/values/arrays.xml
```
(先跑 grep 确认;若出现意外引用,停止并上报。)

- [ ] **Step 4: FragmentSettings 删摇杆代码(CRLF)**

onCreatePreferences 中删除整块:
```java
        ListPreference pfJoystick = findPreference("setting_joystick_type");
        if (pfJoystick != null) {
            // 使用自定义 SummaryProvider
            pfJoystick.setSummaryProvider((Preference.SummaryProvider<ListPreference>) preference -> Objects.requireNonNull(preference.getEntry()));
            pfJoystick.setOnPreferenceChangeListener((preference, newValue) -> !newValue.toString().trim().isEmpty());
        }

        EditTextPreference pfWalk = findPreference("setting_walk");
        setupDecimalEditTextPreference(pfWalk);

        EditTextPreference pfRun = findPreference("setting_run");
        setupDecimalEditTextPreference(pfRun);

        EditTextPreference pfBike = findPreference("setting_bike");
        setupDecimalEditTextPreference(pfBike);

```

删除两个 import(`Objects` 仅此块使用;`Preference` 另有 :24/:93 使用,保留):
```java
import androidx.preference.ListPreference;
```
```java
import java.util.Objects;
```
编辑后跑 CRLF 验证(同 Task 1 Step 12,对象换 FragmentSettings.java)。

- [ ] **Step 5: 删 4 个布局 + 17 个独占 drawable**

```bash
git rm app/src/main/res/layout/joystick.xml app/src/main/res/layout/joystick_button.xml \
      app/src/main/res/layout/joystick_history.xml app/src/main/res/layout/joystick_map.xml
```

逐个确认无外部引用后删除(共享者 `border_window`/`ic_position`/`ic_home_position` **不在**清单,勿删):

```bash
for d in ic_close ic_bike ic_down ic_history ic_left ic_left_down ic_left_up ic_lock_close \
         ic_map ic_move ic_right ic_right_down ic_right_up ic_run ic_up ic_walk circle_shape_bg; do
  echo "== $d =="
  grep -rln "$d" app/src/main/res --include='*.xml' | grep -v "/$d.xml" || echo "  (无外部引用,可删)"
done
```
Expected: 每项均无可保留引用(4 个 joystick 布局已删,不会出现)。若某项仍被非摇杆文件引用,**从删除清单剔除并上报**。然后:

```bash
for d in ic_close ic_bike ic_down ic_history ic_left ic_left_down ic_left_up ic_lock_close \
         ic_map ic_move ic_right ic_right_down ic_right_up ic_run ic_up ic_walk circle_shape_bg; do
  find app/src/main/res \( -name "$d.xml" -o -name "$d.png" -o -name "$d.webp" \) -delete
done
git add -A app/src/main/res
```

- [ ] **Step 6: location_input.xml 与 MainActivity 的 id 改名(CRLF)**

`location_input.xml` 中 `android:id="@+id/joystick_longitude"` → `@+id/input_longitude`,`@+id/joystick_latitude` → `@+id/input_latitude`。`MainActivity.java`(CRLF)两行:

```java
            EditText dialog_lng = view.findViewById(R.id.joystick_longitude);
            EditText dialog_lat = view.findViewById(R.id.joystick_latitude);
```
改为:
```java
            EditText dialog_lng = view.findViewById(R.id.input_longitude);
            EditText dialog_lat = view.findViewById(R.id.input_latitude);
```

- [ ] **Step 7: 验证**

```bash
sh gradlew assembleDebug test lint
```
Expected: BUILD SUCCESSFUL;lint 0 error 0 warning(资源删除若触发 UnusedResources 类 warning,说明有漏删,回 Step 5 补删,而非压制)。

- [ ] **Step 8: 残留终检**

```bash
grep -rni 'joystick\|rocker' app/src/main/java app/src/main/res
```
Expected: 零命中(`setting_group_move` key 保留属预期,不含 joystick 字样)。

- [ ] **Step 9: Commit**

```bash
git add -A app/src/main
git commit -m "refactor: clean up joystick settings and resources"
```

---

### Task 4: 死代码与文档收尾 + 全量门槛(commit ④)

**Files:**
- Delete: `app/src/main/java/com/zcshou/domain/LocationMath.java`、`app/src/test/java/com/zcshou/domain/LocationMathTest.java`
- Modify: `app/src/main/java/com/zcshou/utils/GoUtils.java`(TimeCount 类)
- Modify: `AGENTS.md`(仅编辑,**不提交**)

**Interfaces:**
- Consumes: Task 1 已移除 LocationMath/TimeCount 的全部生产调用方

- [ ] **Step 1: 删除 LocationMath 与其测试**

```bash
git rm app/src/main/java/com/zcshou/domain/LocationMath.java \
       app/src/test/java/com/zcshou/domain/LocationMathTest.java
```
`domain` 包随之消失(目录内仅此一个类)。

- [ ] **Step 2: GoUtils 删除 TimeCount**

删除 import:
```java
import android.os.CountDownTimer;
```
删除文件尾部整个内部类(含 `/* 计数器类 */` 注释,至类闭合 `}` 前):
```java
    /* 计数器类 */
    public static class TimeCount extends CountDownTimer {
        private TimeCountListener mListener;

        public TimeCount(long millisInFuture, long countDownInterval) {
            super(millisInFuture, countDownInterval);//参数依次为总时长,和计时的时间间隔
        }

        @Override
        public void onFinish() {//计时完毕时触发
            mListener.onFinish();
        }

        @Override
        public void onTick(long millisUntilFinished) { //计时过程显示
            mListener.onTick(millisUntilFinished);
        }

        public void setListener(TimeCountListener mListener) {
            this.mListener = mListener;
        }

        public interface TimeCountListener {
            void onTick(long millisUntilFinished);
            void onFinish();
        }
    }
```

- [ ] **Step 3: 更新 AGENTS.md(不提交)**

包结构图中删除 `joystick`、`domain` 两行;`service` 行描述改为"MockLocationController 接口、ServiceGoController、ServiceGo(持续供给固定位置)";仓库用途段删除"悬浮摇杆控制移动(摇杆/按键两种类型 + 小地图/历史两个悬浮面板)"与"步行/跑步/驾驶速度、摇杆类型"相关表述,海拔/随机偏移表述保留。**明确不 `git add`**(文件保持 untracked,是否纳入版本控制由用户决定)。

- [ ] **Step 4: 全量验证门槛**

```bash
sh gradlew clean assembleDebug assembleRelease test lint
```
Expected: 四项全绿(lint 0 error 0 warning,`app/lint-baseline.xml` 仍只有 3 条 jar/二进制条目)。

- [ ] **Step 5: Commit**

```bash
git add -A app/src/main/java
git commit -m "chore: remove dead code left by joystick removal"
git log --oneline -5
```
Expected: 本计划四个 commit 依次可见;工作区仅剩 untracked 的 AGENTS.md。

---

## 验证与交接(所有任务完成后)

1. `git status` 干净(仅 `?? AGENTS.md`);`git log --oneline -6` 显示 4 个新 commit + spec commit。
2. 真机冒烟清单(需用户配合,非执行者可完成):选点→GO 瞬移生效、再按 GO 停止模拟、通知栏展示且无按钮、设置页四分组(位置模拟/位置偏移/记录/关于)、历史记录写入与复用。
