package com.yybb.location.ui.settings;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;

import androidx.appcompat.app.AlertDialog;

import com.yybb.location.R;
import com.yybb.location.utils.AmapUtils;
import com.yybb.location.utils.GoUtils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 高德 API Key 申请指引弹窗：申请步骤动态带入本应用包名与当前安装包签名 SHA1
 * （高德 Key 申请表单的两项必填对照值），支持一键跳高德控制台、复制签名 SHA1。
 * 主界面 key 缺失提示与设置页"如何获取"入口共用本弹窗。
 */
public final class AmapKeyGuide {

    /** 高德控制台"应用管理 → 我的 Key"页面，新 Key 的创建入口 */
    private static final String CONSOLE_URL = "https://console.amap.com/dev/key/app";

    private AmapKeyGuide() {
    }

    public static void show(Activity activity) {
        String sha1 = getSigningSha1(activity);
        String message = activity.getString(R.string.amap_key_guide_steps,
                activity.getPackageName(),
                sha1.isEmpty()
                        ? activity.getString(R.string.amap_key_guide_sha1_unknown)
                        : sha1);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.amap_key_guide_title)
                .setMessage(message)
                .setPositiveButton(R.string.amap_key_guide_open_console,
                        (dialog, which) -> openConsole(activity))
                .setNegativeButton(R.string.amap_key_guide_copy_sha1,
                        (dialog, which) -> copySigningSha1(activity, sha1))
                .show();
    }

    private static void openConsole(Activity activity) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(CONSOLE_URL)));
        } catch (Exception e) {
            // 无浏览器/被拦截时兜底提示，不让指引流程死在静默失败上
            GoUtils.DisplayToast(activity, activity.getString(R.string.amap_key_guide_open_console_failed));
        }
    }

    private static void copySigningSha1(Activity activity, String sha1) {
        if (sha1.isEmpty()) {
            GoUtils.DisplayToast(activity, activity.getString(R.string.amap_key_guide_sha1_unknown));
            return;
        }
        ClipboardManager clipboard =
                (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("SHA1", sha1));
            GoUtils.DisplayToast(activity, activity.getString(R.string.amap_key_guide_sha1_copied));
        }
    }

    /**
     * 当前安装包签名证书的 SHA1。高德 Key 的"发布版安全码 SHA1"必须与安装包实际签名一致，
     * 而自编译/换证书后该值随之变化，故动态读取而非写死。
     * minSdk 31，直接走 API 28+ 的 signingInfo（不碰已废弃的 GET_SIGNATURES）；读取失败返回空串。
     */
    private static String getSigningSha1(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.signingInfo == null) {
                return "";
            }
            Signature[] signatures = info.signingInfo.getApkContentsSigners();
            if (signatures == null || signatures.length == 0) {
                return "";
            }
            return AmapUtils.toColonHex(
                    MessageDigest.getInstance("SHA-1").digest(signatures[0].toByteArray()));
        } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException e) {
            return "";
        }
    }
}
