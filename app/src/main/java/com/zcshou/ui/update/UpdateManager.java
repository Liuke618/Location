package com.zcshou.ui.update;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;

import com.yybb.location.BuildConfig;
import com.yybb.location.R;
import com.zcshou.utils.ShareUtils;
import com.zcshou.utils.GoUtils;

import com.elvishew.xlog.XLog;

import io.noties.markwon.Markwon;

/**
 * 应用更新助手：收编原 MainActivity 的更新检测/下载/安装管线。
 * GitHub releases/latest 检测（OkHttp GET + runOnUiThread）、更新对话框组装
 * （R.layout.update + Markwon，与流程紧耦合，原样随迁，不再拆分）、
 * DownloadManager 下载与下载完成接收器、APK 安装前自检与安装跳转。
 */
public class UpdateManager {

    /* release 来源仓库由构建注入(BuildConfig.UPDATE_REPO_SLUG),不再硬编码某个固定仓库 */
    private static final String RELEASE_API_URL =
            "https://api.github.com/repos/" + BuildConfig.UPDATE_REPO_SLUG + "/releases/latest";

    /**
     * 更新信息值对象：原对话框组装内逐键 getString 读取结果的收编载体。
     */
    public static class UpdateInfo {
        public String name;          // release 名称（如 "影梭 v1.2.3"，仅用于对话框标题）
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

    /* HTTP 客户端收编决策：与 GeocoderHelper（Task 5）同构——助手各自持有 OkHttpClient，
       原 MainActivity.mOkHttpClient 的两处使用（REST 逆地理 / 更新检测）分别收编，
       MainActivity 字段随之删除；不共享实例以保持各助手自洽 */
    private final OkHttpClient mOkHttpClient = new OkHttpClient();
    private Activity mActivity;
    private DownloadManager mDownloadManager = null;
    private long mDownloadId;
    private BroadcastReceiver mDownloadBdRcv;
    private String mUpdateFilename;
    // 检测请求在 OkHttp 线程池里跑，回包时宿主 Activity 可能早已销毁：继续弹窗会抛 BadTokenException
    private Call mPendingCall;
    private boolean mDestroyed = false;

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
        if (mPendingCall != null) {
            mPendingCall.cancel();
        }
        mActivity.unregisterReceiver(mDownloadBdRcv);
    }

    /**
     * 发起更新检测（原 checkUpdateVersion 迁入：GitHub releases/latest HTTP + runOnUiThread
     * + 对话框组装；版本判断改为语义化比较，并新增下载地址同源校验）。
     *
     * @param manualToast 手动检测（侧边栏"检查更新"）时，已是最新版给出提示
     */
    public void checkUpdate(boolean manualToast) {
        okhttp3.Request request = new okhttp3.Request.Builder().url(RELEASE_API_URL).get().build();
        final Call call = mOkHttpClient.newCall(request);
        mPendingCall = call;
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                XLog.i("更新检测失败");
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull okhttp3.Response response) throws IOException {
                ResponseBody responseBody = response.body();
                if (responseBody != null) {
                    String resp = responseBody.string();
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

                            if (isNewer && sameSource) {
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
                                    updateCancel.setOnClickListener(v -> alertDialog.cancel());

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
                            XLog.e("ERROR: resolve json");
                        }
                    });
                }
            }
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
        GoUtils.DisplayToast(mActivity, mActivity.getResources().getString(R.string.update_downloading));
    }

    /**
     * 安装已下载的 APK（由下载完成接收器触发）。
     * 拉起系统安装界面前先过 {@link #isSelfUpgradeApk(File)}：远端 JSON 与文件名都不足以证明
     * "这个包是升级本应用"，校验不通过直接放弃，不让用户走到安装页再被签名/包名报错劝退。
     */
    public void install() {
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
