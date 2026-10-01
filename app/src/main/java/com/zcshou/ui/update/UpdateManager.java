package com.zcshou.ui.update;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;

import com.zcshou.gogogo.R;
import com.zcshou.utils.ShareUtils;
import com.zcshou.utils.GoUtils;

import com.elvishew.xlog.XLog;

import io.noties.markwon.Markwon;

/**
 * 应用更新助手：收编原 MainActivity 的更新检测/下载/安装管线。
 * GitHub releases/latest 检测（OkHttp GET + runOnUiThread）、更新对话框组装
 * （R.layout.update + Markwon，与流程紧耦合，原样随迁，不再拆分）、
 * DownloadManager 下载与下载完成接收器、APK 安装跳转。
 */
public class UpdateManager {

    /**
     * 更新信息值对象：原对话框组装内逐键 getString 读取结果的收编载体。
     */
    public static class UpdateInfo {
        public String name;          // release 名称（如 "影梭 v1.2.3"，同时参与版本号判断）
        public String createdAt;     // 发布时间（原 created_at）
        public String commitish;     // 目标分支（原 target_commitish）
        public String body;          // release 说明（Markdown，经 Markwon 渲染）
        public String downloadUrl;   // APK 下载地址（assets[0].browser_download_url）
        public String filename;      // APK 文件名（assets[0].name，下载目标文件名）
    }

    /**
     * 纯函数：解析 GitHub releases/latest 响应（不触碰 android.*，不依赖实例状态）。
     * 读取路径锚定原 checkUpdateVersion 对话框组装（行为冻结）：
     * name/created_at/target_commitish/body 直接 getString；assets 经 getString 后二次
     * new JSONArray 解析再取 assets[0] 的 browser_download_url/name（原"这里用来保存下载地址"块）。
     * 缺键 / assets 缺失或为空时抛 JSONException，由调用方 runOnUiThread 的
     * catch → XLog 兜底（保持现状，本函数不吞异常）。
     */
    public static UpdateInfo parseRelease(String json) throws JSONException {
        JSONObject getRetJson = new JSONObject(json);
        UpdateInfo info = new UpdateInfo();
        info.name = getRetJson.getString("name");
        info.createdAt = getRetJson.getString("created_at");
        info.commitish = getRetJson.getString("target_commitish");
        info.body = getRetJson.getString("body");

        JSONArray jsonArray = new JSONArray(getRetJson.getString("assets"));
        JSONObject jsonObject = jsonArray.getJSONObject(0);
        info.downloadUrl = jsonObject.getString("browser_download_url");
        info.filename = jsonObject.getString("name");
        return info;
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
        mActivity.unregisterReceiver(mDownloadBdRcv);
    }

    /**
     * 发起更新检测（原 checkUpdateVersion 原样迁入：GitHub releases/latest HTTP + runOnUiThread
     * + 对话框组装；curVersion 的 contains 判断保持原写法，不改版本比较逻辑）。
     *
     * @param manualToast 手动检测（侧边栏"检查更新"）时，已是最新版给出提示
     */
    public void checkUpdate(boolean manualToast) {
        String mapApiUrl = "https://api.github.com/repos/zcshou/gogogo/releases/latest";

        okhttp3.Request request = new okhttp3.Request.Builder().url(mapApiUrl).get().build();
        final Call call = mOkHttpClient.newCall(request);
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
                        try {
                            JSONObject getRetJson = new JSONObject(resp);
                            String curVersion = GoUtils.getVersionName(mActivity);

                            if (curVersion != null
                                    && (!getRetJson.getString("name").contains(curVersion)
                                    || !getRetJson.getString("tag_name").contains(curVersion))) {
                                // 对话框字段与下载资产统一经纯函数 parseRelease 提取（原对话框内逐键
                                // getString 与"这里用来保存下载地址"块收编）；assets 缺失或为空等
                                // JSONException 仍由下方 catch 原样兜底
                                final UpdateInfo info = parseRelease(resp);

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
                                        GoUtils.DisplayToast(mActivity, mActivity.getResources().getString(R.string.update_downloading));
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
     */
    public void download(String url) {
        if (mDownloadManager == null) {
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
                return;
            }
        }
        request.setDestinationUri(Uri.fromFile(file));

        mDownloadId = mDownloadManager.enqueue(request);
    }

    /**
     * 安装已下载的 APK（原 installNewVersion 原样迁入；由下载完成接收器触发）。
     */
    public void install() {
        Intent install = new Intent(Intent.ACTION_VIEW);
        Uri downloadFileUri = mDownloadManager.getUriForDownloadedFile(mDownloadId);
        File file = new File(mActivity.getExternalFilesDir("Updates"), mUpdateFilename);
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
}
