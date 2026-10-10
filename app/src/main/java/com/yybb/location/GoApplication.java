package com.yybb.location;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.amap.api.location.AMapLocationClient;
import com.google.android.material.color.DynamicColors;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.services.core.ServiceSettings;

import com.elvishew.xlog.LogConfiguration;
import com.elvishew.xlog.LogLevel;
import com.elvishew.xlog.XLog;
import com.elvishew.xlog.printer.ConsolePrinter;
import com.elvishew.xlog.printer.Printer;
import com.elvishew.xlog.printer.file.FilePrinter;
import com.elvishew.xlog.printer.file.backup.FileSizeBackupStrategy2;
import com.elvishew.xlog.printer.file.clean.FileLastModifiedCleanStrategy;
import com.elvishew.xlog.printer.file.naming.ChangelessFileNameGenerator;

import java.io.File;

public class GoApplication extends Application {
    public static final String APP_NAME = "GoGoGo";
    public static final String LOG_FILE_NAME = APP_NAME + ".log";
    private static final long MAX_TIME = 1000 * 60 * 60 * 24 * 3; // 3 days
    // 日志目录原先只按"最后修改时间 3 天"清理，单文件从不轮转：服务在跑时一次连续几小时的会话
    // 就能把目录撑到任意大小。改为单文件到 LOG_ROLL_BYTES 轮转、最多保留 LOG_BACKUP_COUNT 份备份，
    // 总量上限约 (1 + LOG_BACKUP_COUNT) * LOG_ROLL_BYTES，轮转时由 xlog 自己删溢出项。
    private static final long LOG_ROLL_BYTES = 2L * 1024 * 1024;
    private static final int LOG_BACKUP_COUNT = 4;

    // 用户协议/隐私政策同意状态(键名原为 WelcomeActivity 私有的，上移后启动期也能按同一状态判断)
    public static final String KEY_ACCEPT_AGREEMENT = "KEY_ACCEPT_AGREEMENT";
    public static final String KEY_ACCEPT_PRIVACY = "KEY_ACCEPT_PRIVACY";
    // 同意状态所在的 SharedPreferences 文件名沿用原值，改名等于让老用户重新同意一次
    private static final String PREFS_AGREEMENT_NAME = KEY_ACCEPT_AGREEMENT;

    // 设置页开关：关闭日志写入(变更在下次进程启动生效，XLog 只初始化一次)
    private static final String SETTING_LOG_OFF = "setting_log_off";

    private AppContainer mAppContainer;

    @Override
    public void onCreate() {
        super.onCreate();

        initXlog();

        // 高德 SDK 2021+ 强制隐私合规:必须先于一切 SDK 调用，且地图/定位/搜索三套接口互相独立
        // (合成包各自校验,漏调搜索= POI/逆地理报 1907)。"已向用户展示隐私政策"可以无条件声明，
        // "用户已同意"必须等用户在自有协议流程里点头，不能替用户做这个决定。
        MapsInitializer.updatePrivacyShow(this, true, true);
        AMapLocationClient.updatePrivacyShow(this, true, true);
        ServiceSettings.getInstance().updatePrivacyShow(this, true, true);
        applyAmapPrivacyAgreement(this);

        mAppContainer = new AppContainer(this);

        // Material You 动态取色:为全部 Activity 叠加设备取色覆盖层(基于 M3 主题,系统壁纸决定实际配色)
        DynamicColors.applyToActivitiesIfAvailable(this);
    }

    public AppContainer getAppContainer() {
        return mAppContainer;
    }

    /**
     * 用户已同意《用户协议》与《隐私政策》时，才向高德三套 SDK 授予隐私合规同意。
     * 未同意时高德地图/定位/搜索不可调用，而本应用在同意前也进不到任何用到它们的界面
     * (WelcomeActivity 以勾选与两次弹窗为门控)，因此不会形成"功能突然不能用"的新路径。
     */
    public static void applyAmapPrivacyAgreement(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS_AGREEMENT_NAME, Context.MODE_PRIVATE);
        if (!preferences.getBoolean(KEY_ACCEPT_AGREEMENT, false)
                || !preferences.getBoolean(KEY_ACCEPT_PRIVACY, false)) {
            return;
        }

        MapsInitializer.updatePrivacyAgree(context, true);
        AMapLocationClient.updatePrivacyAgree(context, true);
        ServiceSettings.getInstance().updatePrivacyAgree(context, true);
    }

    /**
     * Initialize XLog.
     */
    private void initXlog() {
        // 设置页的"关闭日志"原先没有任何读取方(纯装饰开关)，在此生效：不建文件打印器、级别置 NONE
        if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean(SETTING_LOG_OFF, false)) {
            XLog.init(new LogConfiguration.Builder().logLevel(LogLevel.NONE).build(), new ConsolePrinter());
            return;
        }

        File logPath = getExternalFilesDir("Logs");
        if (logPath != null) {
            LogConfiguration config = new LogConfiguration.Builder()
                    .logLevel(LogLevel.ALL)
                    .tag(APP_NAME)                                         // 指定 TAG，默认为 "X-LOG"
                    .enableThreadInfo()                                    // 允许打印线程信息，默认禁止
                    .enableStackTrace(2)                                   // 允许打印深度为 2 的调用栈信息，默认禁止
                    .enableBorder()                                        // 允许打印日志边框，默认禁止
                    .build();

            Printer consolePrinter = new ConsolePrinter();                  // 通过 System.out 打印日志到控制台的打印器
            Printer filePrinter = new FilePrinter                           // 打印日志到文件的打印器
                    .Builder(logPath.getPath())                             // 指定保存日志文件的路径
                    .fileNameGenerator(new ChangelessFileNameGenerator(LOG_FILE_NAME))         // 指定日志文件名生成器，默认为 ChangelessFileNameGenerator("log")
                    .backupStrategy(new FileSizeBackupStrategy2(LOG_ROLL_BYTES, LOG_BACKUP_COUNT))   // 单文件超限即按索引轮转，溢出份数由 xlog 删除
                    .cleanStrategy(new FileLastModifiedCleanStrategy(MAX_TIME))                             // 保留原有 3 天过期清理
                    .build();
            XLog.init(config, consolePrinter, filePrinter);
        }
    }
}