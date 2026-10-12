package com.yybb.location.utils;

/**
 * 高德运行时配置工具：API key 的生效值解析与相关常量。
 */
public final class AmapUtils {

    /** 设置页自定义 key 的偏好键（存默认 SharedPreferences，与其它设置项同库） */
    public static final String PREF_KEY_AMAP_API_KEY = "setting_amap_api_key";

    /** 高德 SDK 约定从 manifest 读取的 meta-data 名，即构建期 placeholder 的注入点 */
    public static final String META_KEY_AMAP_API_KEY = "com.amap.api.v2.apikey";

    private AmapUtils() {
    }

    public static String resolveEffectiveApiKey(String userConfigured, String builtIn) {
        String user = userConfigured == null ? "" : userConfigured.trim();
        if (!user.isEmpty()) {
            return user;
        }
        return builtIn == null ? "" : builtIn.trim();
    }
}
