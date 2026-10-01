package com.zcshou.gogogo.search;

import androidx.annotation.NonNull;

import com.baidu.mapapi.model.LatLng;
import com.baidu.mapapi.search.sug.SuggestionResult;
import com.baidu.mapapi.search.sug.SuggestionSearch;
import com.baidu.mapapi.search.sug.SuggestionSearchOption;

import com.zcshou.data.HistoryRepository;
import com.zcshou.data.HistoryRepository.SearchRow;
import com.zcshou.utils.MapUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * POI 搜索助手：收编原 MainActivity 的 suggestion 搜索管线（数据侧）。
 * SuggestionSearch 构造与结果监听注册、SuggestionResult→List&lt;Map&gt; 转换（原 getMapList 原样）、
 * 查询下发（原 onQueryTextSubmit/Change 中的 requestSuggestion 触发点）、
 * 搜索历史读写（关键字保存、POI 点选保存、历史行读取、删除）收编于此；
 * SearchView 菜单交互与 ListView/SimpleAdapter 渲染属 UI，保留在 MainActivity，
 * 建议结果经 {@link SuggestionListener} 回调交还 Activity 渲染。
 */
public class PoiSearchHelper {

    /* 搜索结果 Map 行键名（SimpleAdapter 列契约；MainActivity.POI_* 委托至本类，JoyStick 引用不受影响） */
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

    private final HistoryRepository mHistoryRepository;
    private final SuggestionListener mListener;
    private SuggestionSearch mSuggestionSearch;

    public PoiSearchHelper(@NonNull HistoryRepository historyRepository, @NonNull SuggestionListener listener) {
        mHistoryRepository = historyRepository;
        mListener = listener;
    }

    /**
     * SuggestionSearch 构造与建议结果监听注册（原 initSearchView 末段"设置搜索建议返回值监听"原样）
     */
    public void init() {
        //设置搜索建议返回值监听
        mSuggestionSearch = SuggestionSearch.newInstance();
        mSuggestionSearch.setOnGetSuggestionResultListener(suggestionResult -> {
            if (suggestionResult == null || suggestionResult.getAllSuggestions() == null) {
                mListener.onSuggestionUnavailable();
            } else {
                mListener.onSuggestionResult(getMapList(suggestionResult));
            }
        });
    }

    /**
     * 下发 suggestion 查询（原 onQueryTextSubmit / onQueryTextChange 中的 requestSuggestion 触发点；
     * 城市取自 MapState.getCurrentCity()，由调用方传入以保持本类与地图状态解耦；
     * try/catch 与 UI 处理保留在 MainActivity，异常语义不变）
     */
    public void requestSuggestions(String query, String city) {
        mSuggestionSearch.requestSuggestion((new SuggestionSearchOption())
                .keyword(query)
                .city(city)
        );
    }

    /**
     * 关键字搜索保存路径（原 onQueryTextSubmit 中的插表段原样：
     * 纯关键字搜索，经纬度字段为空，与原插表缺省列语义一致）
     */
    public void saveQueryHistory(String query) {
        //搜索历史 插表参数（纯关键字搜索，经纬度字段为空，与原插表缺省列语义一致）
        SearchRow searchRow = new SearchRow(query, "搜索关键字", HistoryRepository.DB_SEARCH_TYPE_KEY,
                null, null, null, null, System.currentTimeMillis() / 1000);

        mHistoryRepository.addSearchRow(searchRow);
    }

    /**
     * POI 点选保存路径（原 initSearchView 中 suggestion 列表点击与搜索历史定位点击两处的插表段，
     * 逐字段一致：WGS84 经纬度来自 bd2wgs 换算，lngCustom/latCustom 为 bd09 原坐标）
     */
    public void savePoiSelection(String name, String address, LatLng bd09Point, String lngCustom, String latCustom) {
        double[] latLng = MapUtils.bd2wgs(bd09Point.longitude, bd09Point.latitude);

        //搜索历史 插表参数
        SearchRow searchRow = new SearchRow(name, address,
                HistoryRepository.DB_SEARCH_TYPE_RESULT,
                String.valueOf(latLng[0]), String.valueOf(latLng[1]), lngCustom, latCustom,
                System.currentTimeMillis() / 1000);

        mHistoryRepository.addSearchRow(searchRow);
    }

    /**
     * 搜索历史行读取（原 onMenuItemActionExpand 展示与长按删除后重取的 getSearchRows 调用；
     * SimpleAdapter 渲染属 UI，由调用方完成）
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
     * 销毁 SuggestionSearch（原 onDestroy 的 //poi search destroy 段）
     */
    public void destroy() {
        mSuggestionSearch.destroy();
    }

    /**
     * SuggestionResult→List&lt;Map&gt; 转换（原 MainActivity.getMapList 静态方法原样迁入；
     * 过滤无坐标项，键名与 SimpleAdapter 列契约一致）
     */
    @NonNull
    public static List<Map<String, Object>> getMapList(SuggestionResult suggestionResult) {
        List<Map<String, Object>> data = new ArrayList<>();
        int retCnt = suggestionResult.getAllSuggestions().size();

        for (int i = 0; i < retCnt; i++) {
            if (suggestionResult.getAllSuggestions().get(i).pt == null) {
                continue;
            }

            Map<String, Object> poiItem = new HashMap<>();
            poiItem.put(POI_NAME, suggestionResult.getAllSuggestions().get(i).key);
            poiItem.put(POI_ADDRESS, suggestionResult.getAllSuggestions().get(i).city + " " + suggestionResult.getAllSuggestions().get(i).district);
            poiItem.put(POI_LONGITUDE, "" + suggestionResult.getAllSuggestions().get(i).pt.longitude);
            poiItem.put(POI_LATITUDE, "" + suggestionResult.getAllSuggestions().get(i).pt.latitude);
            data.add(poiItem);
        }
        return data;
    }
}
