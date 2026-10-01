package com.zcshou.ui.map;

import androidx.annotation.NonNull;

import com.baidu.mapapi.model.LatLng;
import com.baidu.mapapi.search.geocode.GeoCodeResult;
import com.baidu.mapapi.search.geocode.GeoCoder;
import com.baidu.mapapi.search.geocode.OnGetGeoCoderResultListener;
import com.baidu.mapapi.search.geocode.ReverseGeoCodeOption;
import com.baidu.mapapi.search.geocode.ReverseGeoCodeResult;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import com.elvishew.xlog.XLog;

/**
 * 地理编码助手：收编原 MainActivity 的两块地理编码职责。
 * 1. SDK GeoCoder（正/逆地理编码）：构造与监听注册收编于此，结果经 SdkGeoCoderListener
 *    回调持有者（MainActivity 的渲染与状态写入逻辑原样保留在其监听实现中）；
 * 2. REST 逆地理（百度 reverse_geocoding v3）：OkHttp GET 与响应解析收编于此，
 *    响应解析三分支收敛为纯函数 parseAddress，落库由调用方在回调中完成。
 */
public class GeocoderHelper {

    /**
     * SDK GeoCoder 结果回调（原 OnGetGeoCoderResultListener 的两个回调体保留在 MainActivity）
     */
    public interface SdkGeoCoderListener {
        void onGetGeoCodeResult(GeoCodeResult geoCodeResult);

        void onGetReverseGeoCodeResult(ReverseGeoCodeResult reverseGeoCodeResult);
    }

    /**
     * REST 逆地理结果回调。沿用原 OkHttp 回调线程模型（不切 UI 线程，DB 写入保持在回调线程）。
     */
    public interface AddressCallback {
        /**
         * HTTP 请求失败（原 onFailure 分支）：调用方以标记名落库
         */
        void onHttpFailure();

        /**
         * 收到响应体（原 onResponse 且 body 非空分支）：调用方经 parseAddress 解析后落库。
         * 与原实现一致，body 为 null 时本回调不触发（不落库）。
         */
        void onResponseBody(@NonNull String body);
    }

    /* REST 逆地理 HTTP 客户端（原 MainActivity.mOkHttpClient 在本路径的使用收编于此；
       更新检测流程仍使用 MainActivity 自身客户端，待 Task 8 收编） */
    private final OkHttpClient mOkHttpClient = new OkHttpClient();
    private GeoCoder mGeoCoder;

    /**
     * 构造 SDK GeoCoder 并注册结果监听（原 initMap 中 newInstance + setOnGetGeoCodeResultListener 部分）
     */
    public void initSdk(@NonNull SdkGeoCoderListener listener) {
        mGeoCoder = GeoCoder.newInstance();
        mGeoCoder.setOnGetGeoCodeResultListener(new OnGetGeoCoderResultListener() {
            @Override
            public void onGetGeoCodeResult(GeoCodeResult geoCodeResult) {
                listener.onGetGeoCodeResult(geoCodeResult);
            }

            @Override
            public void onGetReverseGeoCodeResult(ReverseGeoCodeResult reverseGeoCodeResult) {
                listener.onGetReverseGeoCodeResult(reverseGeoCodeResult);
            }
        });
    }

    /**
     * 发起 SDK 逆地理编码请求（原 onMapLongClick 中的 mGeoCoder.reverseGeoCode 触发点，
     * 调用时机与顺序由持有者保持不变）
     */
    public void reverseBySdk(@NonNull LatLng point) {
        mGeoCoder.reverseGeoCode(new ReverseGeoCodeOption().location(point));
    }

    /**
     * 发起 REST 逆地理请求（URL 拼接与原实现逐字节一致；回调运行在 OkHttp 线程，
     * 线程模型与原实现一致——DB 写入不切 UI 线程）
     */
    public void resolveAddress(double lng, double lat, String ak, String safeCode, @NonNull AddressCallback cb) {
        //bd09坐标的位置信息
        String mapApiUrl = "https://api.map.baidu.com/reverse_geocoding/v3/?ak=" + ak + "&output=json&coordtype=bd09ll" + "&location=" + lat + "," + lng + "&mcode=" + safeCode;

        Request request = new Request.Builder().url(mapApiUrl).get().build();
        final Call call = mOkHttpClient.newCall(request);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                //http 请求失败
                XLog.e("HTTP: HTTP GET FAILED");
                cb.onHttpFailure();
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                ResponseBody responseBody = response.body();
                if (responseBody != null) {
                    String resp = responseBody.string();
                    cb.onResponseBody(resp);
                }
            }
        });
    }

    /**
     * 纯函数：解析 REST 逆地理响应，返回落库地址（不触碰 android.*，不依赖实例状态）。
     * 三分支锚定原 recordCurrentLocation onResponse 解析逻辑：
     * 1. status==0 → result.formatted_address；
     * 2. status!=0 → markName 优先，否则 JSON message（message 缺键时落回 JSONException 分支）；
     * 3. JSONException（响应非法/缺键）→ markName —— 原代码连续两次 put LOCATION（先
     *    markName==null?默认名:markName，再被 markName 无条件覆盖），最终落库值即 markName（含 null），
     *    故 defaultName/defaultMessage 不参与返回值，仅镜像原 :983 的先写默认名。
     * 响应体在设备上来自 OkHttp ResponseBody.string()，永不为 null。
     */
    public static String parseAddress(String json, String markName, String defaultMessage, String defaultName) {
        try {
            JSONObject getRetJson = new JSONObject(json);

            if (Integer.parseInt(getRetJson.getString("status")) == 0) { // 位置获取成功
                JSONObject posInfoJson = getRetJson.getJSONObject("result");
                return posInfoJson.getString("formatted_address");
            } else {
                return markName != null ? markName : getRetJson.getString("message");
            }
        } catch (JSONException e) {
            // 原 catch 分支的 XLog.e("JSON: resolve json error") 日志无法随行：parseAddress 为纯函数
            // 且 XLog 未初始化时抛 IllegalStateException（JVM 单测实测），此处仅保留落库值语义
            return markName;
        }
    }
}
