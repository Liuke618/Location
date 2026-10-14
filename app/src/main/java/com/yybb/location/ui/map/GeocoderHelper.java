package com.yybb.location.ui.map;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.amap.api.maps.model.LatLng;
import com.amap.api.services.core.LatLonPoint;
import com.amap.api.services.geocoder.GeocodeSearch;
import com.amap.api.services.geocoder.RegeocodeQuery;
import com.amap.api.services.geocoder.RegeocodeResult;

import com.elvishew.xlog.XLog;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * 地理编码助手:逆地理编码单通道(高德 SDK GeocodeSearch,客户端内部网络,单 Android key)。
 * 构造与监听注册、请求下发收编于此;SDK 回调在主线程,单行落库由调用方在回调内完成(轻量可接受);
 * 地址提取三分支收敛为纯函数 extractAddress(纯字符串入参,JVM 可测)。
 */
public class GeocoderHelper {

    /**
     * 逆地理结果回调(原 REST 版 AddressCallback 对应物;线程为 SDK 主线程回调)
     */
    public interface AddressCallback {
        /**
         * rCode==1000 且解析成功时 formatAddress 非空,否则为 null(调用方经 extractAddress 取落库值)
         */
        void onAddressResolved(int rCode, @Nullable String formatAddress);
    }

    /**
     * 长按逆地理回调(原 SdkGeoCoderListener 只保留反向通道;正向编码原仅占位日志,随迁移删除)
     */
    public interface SdkGeoCoderListener {
        void onGetReverseGeoCodeResult(RegeocodeResult reverseGeoCodeResult);
    }

    private GeocodeSearch mGeocodeSearch;
    //落库逆地理的待回回调队列(请求点→回调)。保存/收藏可快速连点产生并发请求,SDK 回包也可能与长按通道混行甚至乱序:
    //按请求点配对消费,替代旧单飞行槽(新请求直接覆盖旧回调=旧保存静默丢失)。仅主线程读写(下发与 SDK 回调均在主线程)
    private final ArrayDeque<PendingReverse> mPendingCallbacks = new ArrayDeque<>();

    /** 一条待回的落库逆地理请求(请求点用于回包配对) */
    private static final class PendingReverse {
        final LatLonPoint point;
        final AddressCallback callback;

        PendingReverse(LatLonPoint point, AddressCallback callback) {
            this.point = point;
            this.callback = callback;
        }
    }

    /**
     * 构造 GeocodeSearch 并注册结果监听(原 initMap 中 newInstance + setOnGetGeoCodeResultListener 部分)
     */
    public void initSdk(@NonNull Context context, @NonNull SdkGeoCoderListener listener) {
        try {
            mGeocodeSearch = new GeocodeSearch(context);
            mGeocodeSearch.setOnGeocodeSearchListener(new GeocodeSearch.OnGeocodeSearchListener() {
                @Override
                public void onRegeocodeSearched(RegeocodeResult regeocodeResult, int rCode) {
                    //落库通道:resolveAddress 的待回回调(rCode==1000 且解析成功时 formatAddress 非空,否则 null)
                    String formatAddress = null;
                    if (rCode == 1000 && regeocodeResult != null && regeocodeResult.getRegeocodeAddress() != null) {
                        formatAddress = regeocodeResult.getRegeocodeAddress().getFormatAddress();
                    }
                    AddressCallback pending = takePendingCallback(regeocodeResult);
                    if (pending != null) {
                        pending.onAddressResolved(rCode, formatAddress);
                    }
                    //长按信息窗通道:与落库通道互不排斥,并存派发
                    listener.onGetReverseGeoCodeResult(regeocodeResult);
                }

                @Override
                public void onGeocodeSearched(com.amap.api.services.geocoder.GeocodeResult geocodeResult, int rCode) {
                    //正地理编码未使用(原百度正向通道仅占位日志,已随迁移删除)
                }
            });
        } catch (Exception e) {
            XLog.e("GeocoderHelper: init failed");
        }
    }

    /**
     * 发起长按逆地理请求(原 onMapLongClick 中的 reverseGeoCode 触发点)
     */
    public void reverseBySdk(@NonNull LatLng point) {
        if (mGeocodeSearch == null) {
            return;
        }
        //高德 ReGeocodeQuery 为"纬度,经度"语序(LatLonPoint(lat, lng)),200 为查找半径(米)
        mGeocodeSearch.getFromLocationAsyn(new RegeocodeQuery(new LatLonPoint(point.latitude, point.longitude), 200, GeocodeSearch.AMAP));
    }

    /**
     * 发起落库逆地理请求(原 REST 版 resolveAddress 对应物,ak/safeCode 概念随 SDK 化删除;
     * 回调在 SDK 主线程,单行落库在回调内完成)。并发请求按请求点配对回包,互不覆盖。
     */
    public void resolveAddress(double lng, double lat, @NonNull AddressCallback cb) {
        if (mGeocodeSearch == null) {
            cb.onAddressResolved(-1, null);
            return;
        }
        // 队列上限保护:回包异常丢失(网络层吞掉)时最老的请求按失败回调兜底,不无限积压也不静默悬挂
        if (mPendingCallbacks.size() >= 8) {
            mPendingCallbacks.removeFirst().callback.onAddressResolved(-1, null);
        }
        LatLonPoint point = new LatLonPoint(lat, lng);
        mPendingCallbacks.addLast(new PendingReverse(point, cb));
        //参数为 GCJ02;LatLonPoint 语序为(纬度,经度)
        mGeocodeSearch.getFromLocationAsyn(new RegeocodeQuery(point, 200, GeocodeSearch.AMAP));
    }

    /** 按请求点取走配对的待回回调;无点信息或无配对(长按通道/孤儿回包)不动队列 */
    private AddressCallback takePendingCallback(RegeocodeResult result) {
        LatLonPoint point = result != null && result.getRegeocodeQuery() != null
                ? result.getRegeocodeQuery().getPoint() : null;
        if (point == null) {
            return null;
        }
        for (Iterator<PendingReverse> iterator = mPendingCallbacks.iterator(); iterator.hasNext(); ) {
            PendingReverse pending = iterator.next();
            if (pending.point.getLatitude() == point.getLatitude()
                    && pending.point.getLongitude() == point.getLongitude()) {
                iterator.remove();
                return pending.callback;
            }
        }
        return null;
    }

    /** 纯函数:逆地理原始结果→落库地址(不触碰 android.*,不依赖实例状态)。三分支:
     * 1. rCode==1000 且 formatAddress 非空 → formatAddress;
     * 2. 其余且 markName 非空 → markName(继承原"业务失败回退标记名"语义);
     * 3. 其余 → defaultName(原 REST 版 JSON message 回退无对应物——SDK 以 rCode 表达失败)。 */
    public static String extractAddress(int rCode, @Nullable String formatAddress, @Nullable String markName, @NonNull String defaultName) {
        if (rCode == 1000 && formatAddress != null && !formatAddress.isEmpty()) {
            return formatAddress;
        }
        return markName != null && !markName.isEmpty() ? markName : defaultName;
    }
}
