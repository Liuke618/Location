package com.yybb.location.ui.search;

import android.content.Context;

import androidx.annotation.NonNull;

import com.amap.api.maps.model.LatLng;
import com.amap.api.services.core.LatLonPoint;
import com.amap.api.services.help.Inputtips;
import com.amap.api.services.help.InputtipsQuery;
import com.amap.api.services.help.Tip;

import com.yybb.location.R;
import com.yybb.location.data.HistoryRepository;
import com.yybb.location.data.HistoryRepository.SearchRow;
import com.yybb.location.utils.MapUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * POI 搜索助手：收编原 MainActivity 的 suggestion 搜索管线（数据侧）。
 * Inputtips 构造与结果监听注册、Tip→List&lt;Map&gt; 转换（getTipList）、
 * 查询下发（原 onQueryTextSubmit/Change 中的 requestSuggestion 触发点）、
 * 搜索历史读写（关键字保存、POI 点选保存、历史行读取、删除）收编于此；
 * SearchView 菜单交互与 ListView/SimpleAdapter 渲染属 UI，保留在 MainActivity，
 * 建议结果经 {@link SuggestionListener} 回调交还 Activity 渲染。
 */
public class PoiSearchHelper {

    /* 搜索结果 Map 行键名（SimpleAdapter 列契约；MainActivity.POI_* 委托至本类） */
    public static final String POI_NAME = "POI_NAME";
    public static final String POI_ADDRESS = "POI_ADDRESS";
    public static final String POI_LONGITUDE = "POI_LONGITUDE";
    public static final String POI_LATITUDE = "POI_LATITUDE";

    /**
     * 建议结果回调（原 setOnGetSuggestionResultListener 的两个分支拆分：
     * 无结果提示与列表渲染均保留在 MainActivity，null 判定与数据转换在本类完成）
     */
    public interface SuggestionListener {
        /**
         * 结果为 null 或 getAllSuggestions() 为 null（原 toast app_search_null 分支，由 Activity 提示）
         */
        void onSuggestionUnavailable();

        /**
         * 有效建议结果（data 已过滤无坐标项；SimpleAdapter 渲染与可见性设置由 Activity 原样完成）
         */
        void onSuggestionResult(List<Map<String, Object>> data);
    }

    private final Context mContext;
    private final HistoryRepository mHistoryRepository;
    private final SuggestionListener mListener;

    public PoiSearchHelper(@NonNull Context context, @NonNull HistoryRepository historyRepository, @NonNull SuggestionListener listener) {
        mContext = context;
        mHistoryRepository = historyRepository;
        mListener = listener;
    }

    /**
     * 下发建议查询(原 onQueryTextSubmit / onQueryTextChange 中的 requestSuggestion 触发点;
     * 城市取自 MapState.getCurrentCity(),由调用方传入;try/catch 与 UI 处理保留在 MainActivity)
     *
     * @param notifyUnavailable true=无结果/失败时回调 onSuggestionUnavailable(提交搜索路径,
     *                          Activity 弹"没有匹配结果"提示);false=静默(输入过程路径,中间态
     *                          查询本来就可能无结果,逐键弹窗会干扰打字)
     */
    public void requestSuggestions(String query, String city, boolean notifyUnavailable) {
        InputtipsQuery inputtipsQuery = new InputtipsQuery(query, city == null ? "" : city);
        Inputtips inputtips = new Inputtips(mContext, inputtipsQuery);
        inputtips.setInputtipsListener((tips, rCode) -> {
            //rCode==1000 为高德统一成功码
            if (rCode != 1000 || tips == null || tips.isEmpty()) {
                if (notifyUnavailable) {
                    mListener.onSuggestionUnavailable();
                }
            } else {
                mListener.onSuggestionResult(getTipList(tips));
            }
        });
        inputtips.requestInputtipsAsyn();
    }

    /**
     * 关键字搜索保存路径（原 onQueryTextSubmit 中的插表段原样：
     * 纯关键字搜索，经纬度字段为空，与原插表缺省列语义一致）
     */
    public void saveQueryHistory(String query) {
        //搜索历史 插表参数（纯关键字搜索，经纬度字段为空，与原插表缺省列语义一致）
        SearchRow searchRow = new SearchRow(query,
                mContext.getResources().getString(R.string.search_keyword_desc), HistoryRepository.DB_SEARCH_TYPE_KEY,
                null, null, null, null, System.currentTimeMillis() / 1000);

        mHistoryRepository.addSearchRow(searchRow);
    }

    /**
     * POI 点选保存路径(原 initSearchView 中两处插表段对应物,
     * 逐字段一致:WGS84 经纬度来自 gcj02towgs84 换算,lngCustom/latCustom 为 GCJ02 原坐标)
     */
    public void savePoiSelection(String name, String address, LatLng gcjPoint, String lngCustom, String latCustom) {
        double[] latLng = MapUtils.gcj02towgs84(gcjPoint.longitude, gcjPoint.latitude);

        //搜索历史 插表参数
        SearchRow searchRow = new SearchRow(name, address,
                HistoryRepository.DB_SEARCH_TYPE_RESULT,
                String.valueOf(latLng[0]), String.valueOf(latLng[1]), lngCustom, latCustom,
                System.currentTimeMillis() / 1000);

        mHistoryRepository.addSearchRow(searchRow);
    }

    /**
     * 搜索历史行读取（原 onMenuItemActionExpand 展示与长按删除后重取的 getSearchRows 调用；
     * SimpleAdapter 渲染属 UI，由调用方完成）。存储读取失败透传 null,由调用方提示。
     */
    public List<Map<String, Object>> loadSearchHistoryRows() {
        return mHistoryRepository.getSearchRows();
    }

    /**
     * 删除指定 key 的搜索历史行（原搜索历史长按删除确认分支中的 deleteSearchRow 调用）
     */
    public void deleteSearchHistory(String key) {
        mHistoryRepository.deleteSearchRow(key);
    }

    /**
     * Tip→List&lt;Map&gt; 转换(原 SuggestionResult→Map 转换对应物;
     * 过滤无坐标项,键名与 SimpleAdapter 列契约一致)
     */
    @NonNull
    public static List<Map<String, Object>> getTipList(List<Tip> tips) {
        List<Map<String, Object>> data = new ArrayList<>();

        for (Tip tip : tips) {
            LatLonPoint point = tip.getPoint();
            if (point == null) {
                continue;
            }

            Map<String, Object> poiItem = new HashMap<>();
            poiItem.put(POI_NAME, tip.getName());
            poiItem.put(POI_ADDRESS, tip.getDistrict() + " " + tip.getAddress());
            poiItem.put(POI_LONGITUDE, "" + point.getLongitude());
            poiItem.put(POI_LATITUDE, "" + point.getLatitude());
            data.add(poiItem);
        }
        return data;
    }
}
