package com.yybb.location.utils;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.elvishew.xlog.XLog;
import com.yybb.location.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 通用静态工具：环境状态检测（开发者选项/WIFI/移动数据/GPS/模拟位置授权）、版本与应用名获取、
 * 时间戳格式化、数值安全解析（parseDoubleOrDefault）及引导弹窗/Toast。
 */
public class GoUtils {
    public static boolean isDeveloperOptionsEnabled(Context context) {
        return Settings.Global.getInt(
                context.getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0
        ) == 1;
    }

    // WIFI是否可用
    public static boolean isWifiConnected(Context context) {
        // 从 API 29 开始，NetworkInfo 被标记为过时，这里更换新方法
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network nw = connectivityManager.getActiveNetwork();
        if (nw == null) {
            return false;
        }
        NetworkCapabilities actNw = connectivityManager.getNetworkCapabilities(nw);
        return actNw != null && (actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
    }

    public static boolean isWifiEnabled(Context context) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        return wifiManager.isWifiEnabled();
    }

    //MOBILE网络是否可用
    public static boolean isMobileConnected(Context context) {
        // 从 API 29 开始，NetworkInfo 被标记为过时，这里更换新方法
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network nw = connectivityManager.getActiveNetwork();
        if (nw == null) {
            return false;
        }
        NetworkCapabilities actNw = connectivityManager.getNetworkCapabilities(nw);
        return actNw != null && (actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
    }

    // 断是否有网络连接，但是如果该连接的网络无法上网，也会返回true
    public static boolean isNetworkConnected(Context context) {
        // 从 API 29 开始，NetworkInfo 被标记为过时，这里更换新方法
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network nw = connectivityManager.getActiveNetwork();
        if (nw == null) return false;
        NetworkCapabilities actNw = connectivityManager.getNetworkCapabilities(nw);
        return actNw != null && (actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) || actNw.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH));
    }

    //网络是否可用
    public static boolean isNetworkAvailable(Context context) {
        return ((isWifiConnected(context) || isMobileConnected(context)) && isNetworkConnected(context));
    }

    //判断GPS是否打开
    public static  boolean isGpsOpened(Context context) {
        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    // 判断是否已在开发者选项中开启模拟位置权限
    public static boolean isAllowMockLocation(Context context) {
        boolean canMockPosition = false;
        int index;

        try {
            LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);//获得LocationManager引用

            List<String> list = locationManager.getAllProviders();
            for (index = 0; index < list.size(); index++) {
                if (list.get(index).equals(LocationManager.GPS_PROVIDER)) {
                    break;
                }
            }

            if (index < list.size()) {
                // minSdk 31，addTestProvider 统一走 ProviderProperties 重载（原 SDK_INT >= S 判断恒真，旧 Criteria 分支已删除）
                locationManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, ProviderProperties.POWER_USAGE_HIGH, ProviderProperties.ACCURACY_FINE);
                canMockPosition = true;
            }

            // 模拟位置可用
            if (canMockPosition) {
                // remove test provider
                locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false);
                locationManager.removeTestProvider(LocationManager.GPS_PROVIDER);
            }
        } catch (SecurityException e) {
            XLog.e("GOUTILS: 检测模拟位置权限失败", e);
        }

        return canMockPosition;
    }

    /**
     * [获取应用程序版本名称]
     * @param context context
     * @return 当前应用的版本名称
     */
    public static synchronized String getVersionName(Context context) {
        try {
            PackageManager packageManager = context.getPackageManager();

            PackageInfo packageInfo = packageManager.getPackageInfo(
                    context.getPackageName(), 0);

            return packageInfo.versionName;

        } catch (Exception e) {
            XLog.e("GOUTILS: 获取版本名称失败", e);
        }

        return null;
    }

    /**
     * 获取App的名称
     * @param context 上下文
     * @return 名称
     */
    public static String getAppName(Context context) {
        PackageManager pm = context.getPackageManager();
        //获取包信息
        try {
            PackageInfo packageInfo = pm.getPackageInfo(context.getPackageName(), 0);
            ApplicationInfo applicationInfo = packageInfo.applicationInfo;
            int labelRes = applicationInfo.labelRes;
            return context.getResources().getString(labelRes);
        } catch (PackageManager.NameNotFoundException e) {
            XLog.e("GOUTILS: 获取应用名称失败", e);
        }

        return null;
    }

    public static  String timeStamp2Date(String seconds) {
        if (seconds == null || seconds.isEmpty() || seconds.equals("null")) {
            return "";
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

        return sdf.format(new Date(Long.parseLong(seconds + "000")));
    }

    /**
     * 解析用户可编辑的数值(偏好项输入框/坐标输入框),非法时回退默认值。
     * 这些入口允许粘贴任意文本(如坐标框 digits 放行 "1.2.3"，海拔偏好可输入空串或非数字)，
     * 直接 Double.parseDouble 会抛 NumberFormatException 崩掉界面；"NaN"/"Infinity" 虽能解析，
     * 但会绕过后续的经纬度范围校验，故一并按非法处理。
     */
    public static double parseDoubleOrDefault(String value, double fallback) {
        if (value == null) {
            return fallback;
        }

        double parsed;
        try {
            parsed = Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }

        return Double.isFinite(parsed) ? parsed : fallback;
    }

    /**
     * 解析库内存量坐标串（收藏/历史行的经纬度列），返回 {经度, 纬度}；任一轴缺失/非数字/非有限即回 null。
     * 这类串不是用户当下输入，而是可能由旧版本写入或被外部改过的表数据：原调用点（默认收藏恢复）直接
     * Double.parseDouble，一条脏行就能让冷启动与"未选点按 GO"两条路径崩在 NumberFormatException。
     * 刻意不做经纬度范围校验——越界值仍能在地图上标出来，属既有的可见行为，这里只拦"解析不出坐标"。
     */
    public static double[] parseStoredCoordinatePair(String lngText, String latText) {
        double lng = parseDoubleOrDefault(lngText, Double.NaN);
        double lat = parseDoubleOrDefault(latText, Double.NaN);
        if (Double.isNaN(lng) || Double.isNaN(lat)) {
            return null;
        }

        return new double[]{lng, lat};
    }

    /*============================== 手动输入经纬度（收藏页添加） ==============================*/

    /** 校验结果码：经纬度合法 */
    public static final int MANUAL_LATLNG_OK = 0;
    /** 校验结果码：空/非数字/非有限值 */
    public static final int MANUAL_LATLNG_ERR_MALFORMED = 1;
    /** 校验结果码：经度超出 [-180,180] */
    public static final int MANUAL_LATLNG_ERR_LONGITUDE = 2;
    /** 校验结果码：纬度超出 [-90,90] */
    public static final int MANUAL_LATLNG_ERR_LATITUDE = 3;

    /**
     * 校验手动输入的经纬度文本（收藏页添加对话框用）。
     * 空/非数字/非有限值按"输入非法"处理（复用 parseDoubleOrDefault 的 NaN 收敛）；
     * 越界时经度先行，与主界面手动输入对话框的提示顺序一致。
     */
    public static int validateManualLatLng(String lngText, String latText) {
        if (isBlank(lngText) || isBlank(latText)) {
            return MANUAL_LATLNG_ERR_MALFORMED;
        }

        double lng = parseDoubleOrDefault(lngText, Double.NaN);
        double lat = parseDoubleOrDefault(latText, Double.NaN);
        if (Double.isNaN(lng) || Double.isNaN(lat)) {
            return MANUAL_LATLNG_ERR_MALFORMED;
        }
        if (lng > 180.0 || lng < -180.0) {
            return MANUAL_LATLNG_ERR_LONGITUDE;
        }
        if (lat > 90.0 || lat < -90.0) {
            return MANUAL_LATLNG_ERR_LATITUDE;
        }
        return MANUAL_LATLNG_OK;
    }

    /**
     * 把通过 validateManualLatLng 的输入坐标换算为落库四元组
     * {经度WGS84, 纬度WGS84, 经度GCJ02, 纬度GCJ02}；坐标系转换只经 MapUtils。
     */
    public static double[] convertManualLatLng(double lng, double lat, boolean inputIsWgs84) {
        if (inputIsWgs84) {
            double[] gcj = MapUtils.wgs2gcj(lng, lat);
            return new double[]{lng, lat, gcj[0], gcj[1]};
        }

        double[] wgs = MapUtils.gcj02towgs84(lng, lat);
        return new double[]{wgs[0], wgs[1], lng, lat};
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    //提醒开启位置模拟的弹框
    public static  void showEnableMockLocationDialog(Context context) {
        new AlertDialog.Builder(context)
                .setTitle("启用位置模拟")//这里是表头的内容
                .setMessage("请在\"开发者选项→选择模拟位置信息应用\"中进行设置")//这里是中间显示的具体信息
                .setPositiveButton("设置",(dialog, which) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(intent);
                    } catch (Exception e) {
                        XLog.e("GOUTILS: 跳转开发者选项失败", e);
                    }
                })
                .setNegativeButton("取消",(dialog, which) -> {
                })
                .show();
    }

    //显示开启GPS的提示
    public static  void showEnableGpsDialog(Context context) {
        new AlertDialog.Builder(context)
                .setTitle("启用定位服务")//这里是表头的内容
                .setMessage("是否开启 GPS 定位服务?")//这里是中间显示的具体信息
                .setPositiveButton("确定",(dialog, which) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                        context.startActivity(intent);
                    } catch (Exception e) {
                        XLog.e("GOUTILS: 跳转定位设置失败", e);
                    }
                })
                .setNegativeButton("取消",(dialog, which) -> {

                })
                .show();
    }

    // 提醒开启位置模拟的弹框
    public static  void showDisableWifiDialog(Context context) {
        new AlertDialog.Builder(context)
                .setTitle("警告")
                .setMessage("开启 WIFI 后（即使没有连接热点）将导致定位闪回真实位置。建议关闭 WIFI，使用移动流量进行游戏！")
                .setPositiveButton("去关闭",(dialog, which) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                        context.startActivity(intent);
                    } catch (Exception e) {
                        XLog.e("GOUTILS: 跳转 WiFi 设置失败", e);
                    }
                })
                .setNegativeButton("忽略",(dialog, which) -> {
                })
                .show();
    }

    /**
     * 对指定 GCJ02 坐标做设置范围内的随机偏移(米)，历史/收藏页点击行共用；
     * 偏移上限来自偏好项(可粘贴非数字)，解析失败回退默认值而非崩溃，偏移量经 toast 呈现。
     */
    public static String[] randomOffset(Context context, SharedPreferences sharedPreferences,
                                        String longitude, String latitude) {
        String max_offset_default = context.getResources().getString(R.string.setting_random_offset_default);
        double default_offset = parseDoubleOrDefault(max_offset_default, 10.0);
        double lon_max_offset = parseDoubleOrDefault(
                sharedPreferences.getString("setting_lon_max_offset", max_offset_default), default_offset);
        double lat_max_offset = parseDoubleOrDefault(
                sharedPreferences.getString("setting_lat_max_offset", max_offset_default), default_offset);
        double lon = Double.parseDouble(longitude);
        double lat = Double.parseDouble(latitude);

        double randomLonOffset = (Math.random() * 2 - 1) * lon_max_offset;  // Longitude offset (meters)
        double randomLatOffset = (Math.random() * 2 - 1) * lat_max_offset;  // Latitude offset (meters)

        lon += randomLonOffset / 111320;    // (meters -> longitude)
        lat += randomLatOffset / 110574;    // (meters -> latitude)

        String offsetMessage = String.format(Locale.US, "经度偏移: %.2f米\n纬度偏移: %.2f米", randomLonOffset, randomLatOffset);
        DisplayToast(context, offsetMessage);

        return new String[]{String.valueOf(lon), String.valueOf(lat)};
    }

    public static  void DisplayToast(Context context, String str) {
        Toast toast = Toast.makeText(context, str, Toast.LENGTH_LONG);
        toast.setGravity(Gravity.TOP, 0, 100);
        toast.show();
    }
}