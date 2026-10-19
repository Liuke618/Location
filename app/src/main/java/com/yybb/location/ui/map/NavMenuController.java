package com.yybb.location.ui.map;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;

import java.io.File;

import com.yybb.location.GoApplication;
import com.yybb.location.R;
import com.yybb.location.ui.favorite.FavoriteActivity;
import com.yybb.location.ui.history.HistoryActivity;
import com.yybb.location.ui.settings.SettingsActivity;
import com.yybb.location.utils.GoUtils;
import com.yybb.location.utils.ShareUtils;

import com.elvishew.xlog.XLog;

/**
 * 侧边栏导航菜单：条目分发（收藏/历史/设置/开发者选项/检查更新/日志分享/联系/打赏；
 * 原 MainActivity.initNavigationView 内联体原样迁出，行为冻结）、打赏弹窗与版本号标签。
 */
public class NavMenuController {

    /**
     * “检查更新”点击回调：更新管线由 Activity 持有并注入；点击时才读取（与原 lambda 直接读
     * MainActivity.mUpdateManager 的时序一致，构造期不要求已就绪）。
     */
    public interface CheckUpdateListener {
        void onCheckUpdate();
    }

    private final Activity mActivity;
    private final CheckUpdateListener mCheckUpdateListener;

    public NavMenuController(Activity activity, CheckUpdateListener checkUpdateListener) {
        mActivity = activity;
        mCheckUpdateListener = checkUpdateListener;
    }

    /** 挂接导航菜单监听与头部版本号（原 initNavigationView 原样迁出） */
    public void init() {
        NavigationView mNavigationView = mActivity.findViewById(R.id.nav_view);
        mNavigationView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == R.id.nav_favorite) {
                Intent intent = new Intent(mActivity, FavoriteActivity.class);
                mActivity.startActivity(intent);
            } else if (id == R.id.nav_history) {
                Intent intent = new Intent(mActivity, HistoryActivity.class);

                mActivity.startActivity(intent);
            } else if (id == R.id.nav_settings) {
                Intent intent = new Intent(mActivity, SettingsActivity.class);
                mActivity.startActivity(intent);
            } else if (id == R.id.nav_dev) {
                if (!GoUtils.isDeveloperOptionsEnabled(mActivity)) {
                    GoUtils.DisplayToast(mActivity, mActivity.getResources().getString(R.string.app_error_dev));
                } else {
                    try {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                        mActivity.startActivity(intent);
                    } catch (Exception e) {
                        GoUtils.DisplayToast(mActivity, mActivity.getResources().getString(R.string.app_error_dev));
                    }
                }
            } else if (id == R.id.nav_update) {
                mCheckUpdateListener.onCheckUpdate();
            } else if (id == R.id.nav_feedback) {
                // 关闭日志或尚未落盘时文件不存在,直接把缺失文件交给 FileProvider 会抛异常,先给可读提示
                File logFile = new File(mActivity.getExternalFilesDir("Logs"), GoApplication.LOG_FILE_NAME);
                if (!logFile.exists()) {
                    GoUtils.DisplayToast(mActivity, mActivity.getResources().getString(R.string.app_error_log_missing));
                } else {
                    ShareUtils.shareFile(mActivity, logFile, item.getTitle().toString());
                }
            } else if (id == R.id.nav_contact) {
                Uri uri = Uri.parse("https://github.com/Liuke618/Location/issues");
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                mActivity.startActivity(intent);
            } else if (id == R.id.nav_donate) {
                showDonateDialog();
            }

            DrawerLayout drawer = mActivity.findViewById(R.id.drawer_layout);
            drawer.closeDrawer(GravityCompat.START);

            return true;
        });

        // 直接获取第 0 个头部视图
        View headerView = mNavigationView.getHeaderView(0);
        TextView app_version = headerView.findViewById(R.id.app_version);
        app_version.setText(GoUtils.getVersionName(mActivity));
    }

    /*============================== 请作者喝杯咖啡 ==============================*/

    /* 支付宝收款码内容,换收款码图片时须与此处及 drawable-nodpi/donate_alipay.jpg 一并更新 */
    private static final String DONATE_ALIPAY_QR_URL = "https://qr.alipay.com/fkx12229xxaytmszvol0w70";

    /* 弹出打赏弹窗:大图展示支付宝收款码,长按图片经系统分发跳转支付宝付款 */
    private void showDonateDialog() {
        View view = LayoutInflater.from(mActivity).inflate(R.layout.dialog_donate, null);
        ImageView qrView = view.findViewById(R.id.iv_donate_alipay);
        qrView.setOnLongClickListener(v -> {
            try {
                mActivity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(DONATE_ALIPAY_QR_URL)));
            } catch (Exception e) {
                // 既无浏览器又未装支付宝的极端场景兜底:提示改用截图扫码,不打断弹窗
                XLog.e("MainActivity: ERROR - open donate alipay");
                GoUtils.DisplayToast(mActivity, mActivity.getResources().getString(R.string.donate_jump_failed));
            }
            return true;
        });
        new AlertDialog.Builder(mActivity)
                .setTitle(R.string.donate_dialog_title)
                .setView(view)
                .show();
    }
}
