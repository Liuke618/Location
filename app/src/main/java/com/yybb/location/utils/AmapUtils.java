package com.yybb.location.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import androidx.preference.PreferenceManager;

/**
 * 高德运行时配置工具：API key 的生效值解析与相关常量。
 */
public final class AmapUtils {

    /** 设置页自定义 key 的偏好键（存默认 SharedPreferences，与其它设置项同库） */
    public static final String PREF_KEY_AMAP_API_KEY = "setting_amap_api_key";

    /** 高德 SDK 约定从 manifest 读取的 meta-data 名，即构建期 placeholder 的注入点 */
    public static final String META_KEY_AMAP_API_KEY = "com.amap.api.v2.apikey";

    /** 大写十六进制字符表（签名 SHA1 等哈希串的展示格式） */
    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    private AmapUtils() {
    }

    public static String resolveEffectiveApiKey(String userConfigured, String builtIn) {
        String user = userConfigured == null ? "" : userConfigured.trim();
        if (!user.isEmpty()) {
            return user;
        }
        return builtIn == null ? "" : builtIn.trim();
    }

    /**
     * 生效 key 的运行时读取侧：设置页自定义优先，回落构建期经清单注入的内置值。
     * 下发（GoApplication）与主界面缺失检测共用本入口，保证判定单一事实源。
     */
    public static String getEffectiveApiKey(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        return resolveEffectiveApiKey(
                preferences.getString(PREF_KEY_AMAP_API_KEY, ""),
                readManifestApiKey(context));
    }

    /**
     * 读取构建期 placeholder 注入到清单 meta-data 的内置 key；读取失败一律按缺失处理。
     */
    public static String readManifestApiKey(Context context) {
        try {
            ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            if (info.metaData == null) {
                return "";
            }
            return info.metaData.getString(META_KEY_AMAP_API_KEY, "");
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /**
     * 字节数组转大写冒号分隔十六进制串（签名 SHA1 展示格式）；空数组返回空串。
     */
    public static String toColonHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder(bytes.length * 3 - 1);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                builder.append(':');
            }
            byte b = bytes[i];
            builder.append(HEX_DIGITS[(b >> 4) & 0xF]).append(HEX_DIGITS[b & 0xF]);
        }
        return builder.toString();
    }
}
