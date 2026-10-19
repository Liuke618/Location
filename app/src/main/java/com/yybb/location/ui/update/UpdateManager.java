package com.yybb.location.ui.update;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;

import com.yybb.location.BuildConfig;
import com.yybb.location.R;
import com.yybb.location.utils.ShareUtils;
import com.yybb.location.utils.GoUtils;

import com.elvishew.xlog.XLog;

import io.noties.markwon.Markwon;

/**
 * 应用更新助手：收编原 MainActivity 的更新检测/下载/安装管线。
 * GitHub releases/latest 检测（OkHttp GET + runOnUiThread）、更新对话框组装
 * （R.layout.update + Markwon，与流程紧耦合，原样随迁，不再拆分）、
 * DownloadManager 下载与下载完成接收器、APK 安装前自检与安装跳转。
 * 自动检测受多道门控：「自动检查更新」开关（MainActivity 启动处）、已忽略版本
 * （「忽略该版本」按钮写入，同版及更旧版本不再打扰）与 24 小时节流（距上次收到
 * 服务端响应不足一天则跳过）；手动检测（侧边栏）不受节流与忽略限制。
 * 同一时刻只保留一个在途检测请求：新请求取消旧请求，自动/手动叠加或连点不叠窗。
 */
public class UpdateManager {

    /* release 来源仓库由构建注入(BuildConfig.UPDATE_REPO_SLUG),不再硬编码某个固定仓库 */
    private static final String RELEASE_API_URL =
            "https://api.github.com/repos/" + BuildConfig.UPDATE_REPO_SLUG + "/releases/latest";

    /* 自动检测最小间隔(24 小时,以"上次收到服务端响应"为记账点);手动检测不受此限 */
    static final long AUTO_CHECK_MIN_INTERVAL_MS = TimeUnit.HOURS.toMillis(24);

    /* 偏好键(默认 SharedPreferences,与设置项同库):开关由设置页「关于」分组读写、
       MainActivity 启动门控读取;忽略版本由更新对话框「忽略该版本」按钮写入 */
    public static final String PREF_KEY_AUTO_CHECK_UPDATE = "setting_auto_check_update";
    public static final String PREF_KEY_IGNORED_UPDATE_VERSION = "setting_ignored_update_version";

    /* 上次自动检测"收到服务端响应"的毫秒时间戳(内部记账键,无设置页入口) */
    private static final String PREF_KEY_LAST_AUTO_CHECK_TIME = "setting_last_auto_check_update_time";

    /**
     * 更新信息值对象：原对话框组装内逐键 getString 读取结果的收编载体。
     */
    public static class UpdateInfo {
        public String name;          // release 名称（如 "任意门 v1.2.3"，仅用于对话框标题）
        public String tagName;       // release 标签（版本判断依据，取代原先的 name.contains 写法）
        public String createdAt;     // 发布时间（原 created_at）
        public String commitish;     // 目标分支（原 target_commitish）
        public String body;          // release 说明（Markdown，经 Markwon 渲染）
        public String downloadUrl;   // APK 下载地址（assets[0].browser_download_url）
        public String filename;      // APK 文件名（assets[0].name，下载目标文件名）
    }

    /**
     * 纯函数：解析 GitHub releases/latest 响应（不触碰 android.*，不依赖实例状态）。
     * 读取路径锚定原 checkUpdateVersion 对话框组装（行为冻结）：
     * name/tag_name/created_at/target_commitish/body 直接 getString；assets 经 getString 后二次
     * new JSONArray 解析再取 assets[0] 的 browser_download_url/name（原"这里用来保存下载地址"块）。
     * 缺键 / assets 缺失或为空时抛 JSONException，由调用方 runOnUiThread 的
     * catch → XLog 兜底（保持现状，本函数不吞异常）。
     */
    public static UpdateInfo parseRelease(String json) throws JSONException {
        JSONObject getRetJson = new JSONObject(json);
        UpdateInfo info = new UpdateInfo();
        info.name = getRetJson.getString("name");
        info.tagName = getRetJson.getString("tag_name");
        info.createdAt = getRetJson.getString("created_at");
        info.commitish = getRetJson.getString("target_commitish");
        info.body = getRetJson.getString("body");

        JSONArray jsonArray = new JSONArray(getRetJson.getString("assets"));
        JSONObject jsonObject = jsonArray.getJSONObject(0);
        info.downloadUrl = jsonObject.getString("browser_download_url");
        info.filename = jsonObject.getString("name");
        return info;
    }

    /**
     * 纯函数：语义化版本号比较，数字段逐位对齐（容忍 v/V 前缀、段数不等、-rc/+build 后缀）。
     * 返回值 &gt; 0 表示 candidate 比 current 新。
     * 取代原先 `name.contains(curVersion) || tag_name.contains(curVersion)` 的判断：
     * contains 既会把降级当成更新（旧 release 标题不含当前版本号即弹框），也会在
     * 标题格式变动时漏判真正的更新，且完全没有"必须更新"的语义。
     */
    static int compareVersionNames(String candidate, String current) {
        int[] left = versionSegments(candidate);
        int[] right = versionSegments(current);
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int a = i < left.length ? left[i] : 0;
            int b = i < right.length ? right[i] : 0;
            if (a != b) {
                return a - b;
            }
        }
        return 0;
    }

    private static int[] versionSegments(String version) {
        if (version == null) {
            return new int[0];
        }

        String normalized = version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        // 预发布(-rc1)与构建元数据(+build9)后缀不参与主版本比较
        normalized = normalized.split("[-+]")[0];

        String[] parts = normalized.split("\\.");
        int[] segments = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                segments[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                segments[i] = 0;
            }
        }
        return segments;
    }

    /**
     * 纯函数：检测到的更新是否已被用户忽略（自动检测可静默跳过）。
     * 忽略语义按版本号比较而非字符串全等：忽略 v1.2.3 后，远端撤包回退到更旧版本
     * 同样不再提醒；出现更高版本视为新更新，恢复自动提醒。未忽略过任何版本时恒为 false。
     */
    static boolean isUpdateIgnored(String latestTag, String ignoredVersion) {
        return ignoredVersion != null && !ignoredVersion.isEmpty()
                && compareVersionNames(latestTag, ignoredVersion) <= 0;
    }

    /**
     * 纯函数：自动检测是否已到最小间隔（以「上次收到服务端响应」为记账点）。
     * 从未记账(0/负)视为到期；时钟回拨导致的负差按未到期处理，不因此重复打扰服务端。
     */
    static boolean isAutoCheckDue(long nowMs, long lastAutoCheckMs) {
        if (lastAutoCheckMs <= 0L) {
            return true;
        }
        return nowMs - lastAutoCheckMs >= AUTO_CHECK_MIN_INTERVAL_MS;
    }

    /**
     * 纯函数：校验 release 响应给出的下载地址与更新源同仓库且走 https。
     * browser_download_url 是响应体里的字段，不校验就等于让响应方决定下载哪个文件。
     * GitHub release 资产的下载路径形如 /{owner}/{repo}/releases/download/{tag}/{file}。
     */
    static boolean isTrustedDownloadUrl(String downloadUrl, String repoSlug) {
        if (downloadUrl == null || repoSlug == null || repoSlug.isEmpty()) {
            return false;
        }

        try {
            URI uri = new URI(downloadUrl);
            String path = uri.getPath();
            return "https".equals(uri.getScheme())
                    && "github.com".equals(uri.getHost())
                    && path != null
                    && path.startsWith("/" + repoSlug + "/");
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /* 下载状态轮询的判定结果：继续等下一轮 / 触发安装 / 按失败收口 */
    static final int POLL_KEEP_WAITING = 0;
    static final int POLL_INSTALL = 1;
    static final int POLL_FAIL = 2;

    /**
     * 纯函数：一次系统下载状态 → 后续动作。
     * SUCCESSFUL 直接触发安装（与完成广播互为兜底，安装侧另有只放行一次的闸）；
     * FAILED 收口报错——DownloadManager 失败不发 ACTION_DOWNLOAD_COMPLETE，只听广播等于失败即静默；
     * PENDING/RUNNING/PAUSED（含等网络）由系统自行推进，继续等。
     */
    static int classifyDownloadStatus(int status) {
        if (status == DownloadManager.STATUS_SUCCESSFUL) {
            return POLL_INSTALL;
        }
        if (status == DownloadManager.STATUS_FAILED) {
            return POLL_FAIL;
        }
        return POLL_KEEP_WAITING;
    }

    /* HTTP 客户端收编决策：与 GeocoderHelper（Task 5）同构——助手各自持有 OkHttpClient，
       原 MainActivity.mOkHttpClient 的两处使用（REST 逆地理 / 更新检测）分别收编，
       MainActivity 字段随之删除；不共享实例以保持各助手自洽 */
    private final OkHttpClient mOkHttpClient = new OkHttpClient.Builder()
            // GitHub API 偶发长尾挂起:显式整体超时,超时走既有失败分支(自动检测静默/手动检测提示),不无限等待
            .callTimeout(15, TimeUnit.SECONDS)
            .build();
    private Activity mActivity;
    private DownloadManager mDownloadManager = null;
    private long mDownloadId;
    private BroadcastReceiver mDownloadBdRcv;
    private String mUpdateFilename;
    // 检测请求在 OkHttp 线程池里跑，回包时宿主 Activity 可能早已销毁：继续弹窗会抛 BadTokenException
    private Call mPendingCall;
    private boolean mDestroyed = false;

    /* 下载阶段轮询：DownloadManager 失败只改自家数据库和通知，不给应用发广播，
       不轮询就无法把失败告诉用户（现象即"下载中"之后再无下文） */
    private static final long DOWNLOAD_POLL_INTERVAL_MS = 1000L;
    private final Handler mDownloadPollHandler = new Handler(Looper.getMainLooper());
    private boolean mDownloadActive = false;
    // 轮询与完成广播互为兜底，可能先后触发安装：只放行先到的一个，避免叠两个安装页
    private boolean mInstallStarted = false;
    private final Runnable mDownloadPollTask = new Runnable() {
        @Override
        public void run() {
            if (!mDownloadActive || mDestroyed) {
                return;
            }
            pollDownloadStatus();
            if (mDownloadActive && !mDestroyed) {
                mDownloadPollHandler.postDelayed(this, DOWNLOAD_POLL_INTERVAL_MS);
            }
        }
    };

    /**
     * 下载管理器获取与下载完成接收器注册（原 initUpdateVersion 原样迁入）。
     * 须在 checkUpdate 前调用（与原 onCreate 的调用顺序一致）。
     */
    public void init(Activity activity) {
        mActivity = activity;
        mDownloadManager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);

        // 用于监听下载完成后，转到安装界面
        mDownloadBdRcv = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // ACTION_DOWNLOAD_COMPLETE 对本应用任意一次下载都会送达，不比对 id 就会在无关
                // 下载完成时拉起安装流程（此时 mUpdateFilename 可能是上一个版本留下的文件名）
                long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                if (completedId != mDownloadId) {
                    return;
                }
                install();
            }
        };
        // targetSdk 34+ 非保护广播必须声明 exported 状态;DOWNLOAD_COMPLETE 由系统 DownloadManager 发出,NOT_EXPORTED 不影响接收
        ContextCompat.registerReceiver(activity, mDownloadBdRcv, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    /**
     * 注销下载完成接收器（原 MainActivity.onDestroy 的 unregisterReceiver 调用点，与 init 成对）。
     */
    public void destroy() {
        mDestroyed = true;
        stopDownloadPolling();
        if (mPendingCall != null) {
            mPendingCall.cancel();
        }
        mActivity.unregisterReceiver(mDownloadBdRcv);
    }

    /**
     * 发起更新检测（原 checkUpdateVersion 迁入：GitHub releases/latest HTTP + runOnUiThread
     * + 对话框组装；版本判断改为语义化比较，并新增下载地址同源校验）。
     * 自动检测遇已忽略版本静默跳过；手动检测是用户主动行为，照常弹窗。
     *
     * @param manualToast 手动检测（侧边栏"检查更新"）时，已是最新版给出提示
     */
    public void checkUpdate(boolean manualToast) {
        // 自动检测节流:距上次收到服务端响应不足 24 小时则跳过(手动检测是用户主动行为,不受此限)
        if (!manualToast && !isAutoCheckDue(System.currentTimeMillis(), readLastAutoCheckTime())) {
            XLog.d("UPDATE: 自动检测距上次检测不足 24 小时, 跳过");
            return;
        }

        // 防重入:自动/手动叠加或连点"检查更新"时取消在途请求,只保留最后一次请求的语义,避免并发回包叠窗
        if (mPendingCall != null) {
            mPendingCall.cancel();
            mPendingCall = null;
        }

        okhttp3.Request request = new okhttp3.Request.Builder().url(RELEASE_API_URL).get().build();
        final Call call = mOkHttpClient.newCall(request);
        mPendingCall = call;
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                // destroy() 会取消在途请求，主动取消不算检测失败
                if (call.isCanceled()) {
                    return;
                }
                reportCheckFailure("network: " + e, manualToast);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull okhttp3.Response response) throws IOException {
                if (call.isCanceled()) {
                    // 已被新检测取代:结果整体作废,不记账不弹窗(取消与已到回包之间存在竞态窗口)
                    response.close();
                    return;
                }
                ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    reportCheckFailure("empty body", manualToast);
                    return;
                }
                String resp = responseBody.string();
                if (!manualToast) {
                    // 以"收到服务端响应"记账(含 403/429 等 HTTP 错误):离线无响应不记账,下次启动立即重试
                    persistLastAutoCheckTime();
                }
                if (!response.isSuccessful()) {
                    // 403/429 多为 GitHub 未认证 API 限流，响应体不是 release JSON；
                    // 原先把这段文本直接丢给 JSON 解析，失败后只留一行日志，用户看不出区别
                    reportCheckFailure("http " + response.code(), manualToast);
                    return;
                }
                // 注意，该请求在子线程，不能直接操作界面
                mActivity.runOnUiThread(() -> {
                    // 宿主已销毁时什么都不做：对话框与提示都会打到失效的窗口令牌上
                    if (mDestroyed) {
                        return;
                    }
                    try {
                        final UpdateInfo info = parseRelease(resp);
                        String curVersion = GoUtils.getVersionName(mActivity);

                        boolean isNewer = compareVersionNames(info.tagName, curVersion) > 0;
                        boolean sameSource = isTrustedDownloadUrl(info.downloadUrl, BuildConfig.UPDATE_REPO_SLUG);
                        //忽略门控只作用于自动检测:自动弹窗不打扰已明确拒绝过的版本
                        boolean ignored = !manualToast
                                && isUpdateIgnored(info.tagName, readIgnoredUpdateVersion());

                        if (isNewer && sameSource && !ignored) {
                            final android.app.AlertDialog alertDialog = new android.app.AlertDialog.Builder(mActivity).create();
                            alertDialog.show();
                            alertDialog.setCancelable(false);
                            Window window = alertDialog.getWindow();
                            if (window != null) {
                                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);      // 防止出现闪屏
                                window.setContentView(R.layout.update);
                                window.setGravity(Gravity.CENTER);
                                window.setWindowAnimations(R.style.DialogAnimFadeInFadeOut);

                                TextView updateTitle = window.findViewById(R.id.update_title);
                                updateTitle.setText(info.name);
                                TextView updateTime = window.findViewById(R.id.update_time);
                                updateTime.setText(info.createdAt);
                                TextView updateCommit = window.findViewById(R.id.update_commit);
                                updateCommit.setText(info.commitish);

                                TextView updateContent = window.findViewById(R.id.update_content);
                                final Markwon markwon = Markwon.create(mActivity);
                                markwon.setMarkdown(updateContent, info.body);

                                Button updateCancel = window.findViewById(R.id.update_ignore);
                                //「忽略该版本」:记下当前 release,自动检测不再提醒同版及更旧版本
                                updateCancel.setOnClickListener(v -> {
                                    persistIgnoredUpdateVersion(info.tagName);
                                    alertDialog.cancel();
                                });

                                /* 这里用来保存下载地址 */
                                mUpdateFilename = info.filename;

                                Button updateAgree = window.findViewById(R.id.update_agree);
                                updateAgree.setOnClickListener(v -> {
                                    alertDialog.cancel();
                                    download(info.downloadUrl);
                                });
                            }
                        } else {
                            if (manualToast) {
                                GoUtils.DisplayToast(mActivity, mActivity.getResources().getString(R.string.update_last));
                            }
                        }
                    } catch (JSONException e) {
                        reportCheckFailure("unexpected payload: " + e, manualToast);
                    }
                });
            }
        });
    }

    /**
     * 已忽略版本的存取:挂在默认 SharedPreferences(与设置项同库),读写均在主线程决策点。
     */
    private String readIgnoredUpdateVersion() {
        return PreferenceManager.getDefaultSharedPreferences(mActivity)
                .getString(PREF_KEY_IGNORED_UPDATE_VERSION, null);
    }

    private void persistIgnoredUpdateVersion(String tagName) {
        PreferenceManager.getDefaultSharedPreferences(mActivity)
                .edit()
                .putString(PREF_KEY_IGNORED_UPDATE_VERSION, tagName)
                .apply();
    }

    /** 自动检测记账读取:挂在默认 SharedPreferences(与设置项同库),读在主线程决策点 */
    private long readLastAutoCheckTime() {
        return PreferenceManager.getDefaultSharedPreferences(mActivity)
                .getLong(PREF_KEY_LAST_AUTO_CHECK_TIME, 0L);
    }

    /** 自动检测记账写入:OkHttp 回调线程调用,SharedPreferences 读写线程安全,apply 异步落盘 */
    private void persistLastAutoCheckTime() {
        PreferenceManager.getDefaultSharedPreferences(mActivity)
                .edit()
                .putLong(PREF_KEY_LAST_AUTO_CHECK_TIME, System.currentTimeMillis())
                .apply();
    }

    /**
     * 检测失败统一收口：日志始终记录原因；仅手动检测（侧边栏"检查更新"）才打扰界面，
     * 自动检测保持静默，与原行为一致。
     */
    private void reportCheckFailure(String reason, boolean manualToast) {
        XLog.e("UPDATE: 更新检测失败 " + reason);
        if (!manualToast) {
            return;
        }
        mActivity.runOnUiThread(() -> {
            // 宿主已销毁时提示同样打不出去：回调可能晚于 Activity 生命周期
            if (mDestroyed) {
                return;
            }
            GoUtils.DisplayToast(mActivity, mActivity.getResources().getString(R.string.update_check_failed));
        });
    }

    /**
     * 经 DownloadManager 下载新版本 APK（原 downloadNewVersion 原样迁入）。
     * "下载中"提示改在真正入队成功后给出：下载管理器缺失或旧文件删不掉时都会静默返回，
     * 先提示再说等于承诺了一次不会发生的下载。
     */
    public void download(String url) {
        if (mDownloadManager == null) {
            XLog.e("UPDATE: DownloadManager 不可用, 取消下载");
            return;
        }

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setAllowedOverRoaming(false);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setTitle(GoUtils.getAppName(mActivity));
        request.setDescription("正在下载新版本...");
        request.setMimeType("application/vnd.android.package-archive");

        // DownloadManager不会覆盖已有的同名文件，需要自己来删除已存在的文件
        File file = new File(mActivity.getExternalFilesDir("Updates"), mUpdateFilename);
        if (file.exists()) {
            if(!file.delete()) {
                XLog.e("UPDATE: 旧安装包删除失败, 取消下载");
                return;
            }
        }
        request.setDestinationUri(Uri.fromFile(file));

        mDownloadId = mDownloadManager.enqueue(request);
        // 成功路径与完成广播互为冗余，失败路径由此主动收口；新一轮下载重置安装闸
        mInstallStarted = false;
        mDownloadActive = true;
        mDownloadPollHandler.postDelayed(mDownloadPollTask, DOWNLOAD_POLL_INTERVAL_MS);
        GoUtils.DisplayToast(mActivity, mActivity.getResources().getString(R.string.update_downloading));
    }

    private void stopDownloadPolling() {
        mDownloadActive = false;
        mDownloadPollHandler.removeCallbacks(mDownloadPollTask);
    }

    /**
     * 查一次系统下载状态并按 {@link #classifyDownloadStatus(int)} 推进：
     * 继续等（不动）/ 触发安装 / 失败收口。
     */
    private void pollDownloadStatus() {
        Cursor cursor = mDownloadManager.query(new DownloadManager.Query().setFilterById(mDownloadId));
        if (cursor == null) {
            return; // 管理器查询异常：留待下一轮再查
        }
        try {
            if (!cursor.moveToFirst()) {
                // 记录没了（如在系统下载管理里被清）：永远等不到完成，按失败收口
                reportDownloadFailure("download record missing");
                return;
            }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            int action = classifyDownloadStatus(status);
            if (action == POLL_INSTALL) {
                stopDownloadPolling();
                install();
            } else if (action == POLL_FAIL) {
                int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                reportDownloadFailure("status " + status + ", reason " + reason);
            }
        } finally {
            cursor.close();
        }
    }

    /**
     * 下载失败统一收口：停轮询、删掉下载记录与半截文件（DownloadManager 不覆盖同名文件，
     * 残留会挡住重下）、提示用户。错误码只进日志，不拿数字打扰用户。
     */
    private void reportDownloadFailure(String reason) {
        stopDownloadPolling();
        XLog.e("UPDATE: 新版本下载失败 " + reason);
        mDownloadManager.remove(mDownloadId);
        if (!mDestroyed) {
            GoUtils.DisplayToast(mActivity, mActivity.getResources().getString(R.string.update_download_failed));
        }
    }

    /**
     * 安装已下载的 APK（由下载完成接收器或状态轮询触发，mInstallStarted 保证只放行先到的一个）。
     * 拉起系统安装界面前先过 {@link #isSelfUpgradeApk(File)}：远端 JSON 与文件名都不足以证明
     * "这个包是升级本应用"，校验不通过直接放弃，不让用户走到安装页再被签名/包名报错劝退。
     */
    public void install() {
        if (mInstallStarted) {
            return;
        }
        mInstallStarted = true;

        File file = new File(mActivity.getExternalFilesDir("Updates"), mUpdateFilename);
        if (!isSelfUpgradeApk(file)) {
            GoUtils.DisplayToast(mActivity, mActivity.getResources().getString(R.string.update_verify_failed));
            return;
        }

        Intent install = new Intent(Intent.ACTION_VIEW);
        Uri downloadFileUri = mDownloadManager.getUriForDownloadedFile(mDownloadId);
        if (downloadFileUri != null) {
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            // 在Broadcast中启动活动需要添加Intent.FLAG_ACTIVITY_NEW_TASK
            install.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);    //添加这一句表示对目标应用临时授权该Uri所代表的文件
            install.addCategory("android.intent.category.DEFAULT");
            install.setDataAndType(ShareUtils.getUriFromFile(mActivity, file), "application/vnd.android.package-archive");
            mActivity.startActivity(install);
        } else {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + mActivity.getPackageName()));
            intent.addCategory("android.intent.category.DEFAULT");
            mActivity.startActivity(intent);
        }
    }

    /**
     * 安装自检：APK 可解析、包名与本应用一致、versionCode 高于当前，三者缺一即拒绝。
     * 包名不一致是最需要拦的情形——上游 release 与本应用同号不同包，装上的是另一个应用。
     */
    private boolean isSelfUpgradeApk(File file) {
        if (mUpdateFilename == null || mUpdateFilename.isEmpty() || !file.exists()) {
            XLog.e("UPDATE: 安装目标文件不存在, 取消安装");
            return false;
        }

        PackageManager pm = mActivity.getPackageManager();
        // API 33 才提供 getPackageArchiveInfo(File,int) 重载，minSdk 31 需覆盖 31/32 设备，故沿用字符串重载
        //noinspection deprecation
        PackageInfo archive = pm.getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_META_DATA);
        if (archive == null) {
            XLog.e("UPDATE: APK 解析失败, 取消安装");
            return false;
        }

        if (!BuildConfig.APPLICATION_ID.equals(archive.packageName)) {
            XLog.e("UPDATE: 安装包应用标识与本应用不一致, 取消安装");
            return false;
        }

        if (archive.getLongVersionCode() <= BuildConfig.VERSION_CODE) {
            XLog.e("UPDATE: 安装包版本号不高于当前, 取消安装");
            return false;
        }

        return true;
    }
}
