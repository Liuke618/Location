package com.yybb.location.ui.search;

import android.app.Activity;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SearchView;

import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.model.LatLng;

import java.util.List;
import java.util.Map;

import com.yybb.location.R;
import com.yybb.location.data.HistoryRepository;
import com.yybb.location.ui.map.MainViewModel;
import com.yybb.location.ui.map.MapController;
import com.yybb.location.ui.map.MapState;
import com.yybb.location.utils.GoUtils;

import com.elvishew.xlog.XLog;

/**
 * 搜索界面（工具栏 SearchView + 建议列表 + 搜索历史面板，原 MainActivity 内联体原样迁出，行为冻结）：
 * 菜单项展开/收起、查询下发接线、建议结果渲染、搜索历史渲染与长按删除、列表点选落状态与保存；
 * 数据侧（Inputtips 管线与搜索历史读写）仍走 PoiSearchHelper。
 * 时序约定：init() 须先于 onCreateOptionsMenu 调用（前者查找面板视图，后者配置菜单项）。
 */
public class SearchViewController {

    private final Activity mActivity;
    private final HistoryRepository mHistoryRepository;
    private final MapState mMapState;
    private final MainViewModel mMainViewModel;
    private final MapController mMapController;

    private SearchView searchView;
    private ListView mSearchList;
    private LinearLayout mSearchLayout;
    private ListView mSearchHistoryList;
    private LinearLayout mHistoryLayout;
    private MenuItem searchItem;
    //建议搜索管线：Inputtips 与搜索历史读写收编于 PoiSearchHelper，渲染回调体保留在本类
    private PoiSearchHelper mPoiSearchHelper;

    public SearchViewController(Activity activity, HistoryRepository historyRepository,
                                MapState mapState, MainViewModel mainViewModel, MapController mapController) {
        mActivity = activity;
        mHistoryRepository = historyRepository;
        mMapState = mapState;
        mMainViewModel = mainViewModel;
        mMapController = mapController;
    }

    /** 工具栏搜索菜单项配置（原 onCreateOptionsMenu 内联体原样迁出；菜单膨胀仍由 Activity 完成） */
    public void setupSearchItem(MenuItem menuItem) {
        searchItem = menuItem;
        searchItem.setOnActionExpandListener(new  MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                mSearchLayout.setVisibility(View.INVISIBLE);
                mHistoryLayout.setVisibility(View.INVISIBLE);
                return true;  // Return true to collapse action view
            }
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                mSearchLayout.setVisibility(View.INVISIBLE);
                //展示搜索历史（历史行读取收编于 PoiSearchHelper，SimpleAdapter 渲染属 UI 保留在此；
                //渲染块与长按删除后重取的渲染逐字一致，收编为共用方法 displaySearchHistory）
                displaySearchHistory();

                return true;  // Return true to expand action view
            }
        });

        searchView = (SearchView) searchItem.getActionView();
        searchView.setIconified(false);// 设置searchView处于展开状态
        searchView.onActionViewExpanded();// 当展开无输入内容的时候，没有关闭的图标
        searchView.setIconifiedByDefault(true);//默认为true在框内，设置false则在框外
        searchView.setSubmitButtonEnabled(false);//显示提交按钮
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                try {
                    //查询下发与关键字保存收编于 PoiSearchHelper（try/catch 与 UI 处理保留在此，异常语义不变）
                    mPoiSearchHelper.requestSuggestions(query, mMapState.getCurrentCity(), true);
                    mPoiSearchHelper.saveQueryHistory(query);
                    mMapController.clearOverlays();
                    mSearchLayout.setVisibility(View.INVISIBLE);
                } catch (Exception e) {
                    GoUtils.DisplayToast(mActivity, mActivity.getResources().getString(R.string.app_error_search));
                    XLog.d(mActivity.getResources().getString(R.string.app_error_search));
                }

                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                //当输入框内容改变的时候回调
                //搜索历史置为不可见
                mHistoryLayout.setVisibility(View.INVISIBLE);

                if (newText != null && !newText.isEmpty()) {
                    try {
                        //查询下发收编于 PoiSearchHelper
                        mPoiSearchHelper.requestSuggestions(newText, mMapState.getCurrentCity(), false);
                    } catch (Exception e) {
                        GoUtils.DisplayToast(mActivity, mActivity.getResources().getString(R.string.app_error_search));
                        XLog.d(mActivity.getResources().getString(R.string.app_error_search));
                    }
                }

                return true;
            }
        });

        // 搜索框的清除按钮(该按钮属于安卓系统图标)
        ImageView closeButton = searchView.findViewById(androidx.appcompat.R.id.search_close_btn);
        closeButton.setOnClickListener(v -> {
            EditText et = mActivity.findViewById(androidx.appcompat.R.id.search_src_text);
            et.setText("");
            searchView.setQuery("", false);
            mSearchLayout.setVisibility(View.INVISIBLE);
            mHistoryLayout.setVisibility(View.VISIBLE);
        });
    }

    /** 视图查找与列表交互接线（原 initSearchView 原样迁出），须在 onCreateOptionsMenu 之前调用 */
    public void init() {
        mSearchLayout = mActivity.findViewById(R.id.search_linear);
        mHistoryLayout = mActivity.findViewById(R.id.search_history_linear);

        mSearchList = mActivity.findViewById(R.id.search_list_view);
        mSearchList.setOnItemClickListener((parent, view, position, id) -> {
            String lng = ((TextView) view.findViewById(R.id.poi_longitude)).getText().toString();
            String lat = ((TextView) view.findViewById(R.id.poi_latitude)).getText().toString();
            mMapState.setMarkName(((TextView) view.findViewById(R.id.poi_name)).getText().toString());
            mMainViewModel.selectPoint(new LatLng(Double.parseDouble(lat), Double.parseDouble(lng)));
            mMapController.getAMap().moveCamera(CameraUpdateFactory.changeLatLng(mMapState.getSelectedPoint()));

            mMapController.markMap();

            //搜索历史 插表参数：POI 点选保存收编于 PoiSearchHelper（gcj02towgs84 换算 + SearchRow 构造 + 落库）
            mPoiSearchHelper.savePoiSelection(mMapState.getMarkName(),
                    ((TextView) view.findViewById(R.id.poi_address)).getText().toString(),
                    mMapState.getSelectedPoint(), lng, lat);
            mSearchLayout.setVisibility(View.INVISIBLE);
            searchItem.collapseActionView();
        });
        //搜索历史列表的点击监听
        mSearchHistoryList = mActivity.findViewById(R.id.search_history_list_view);
        mSearchHistoryList.setOnItemClickListener((parent, view, position, id) -> {
            String searchDescription = ((TextView) view.findViewById(R.id.search_description)).getText().toString();
            String searchKey = ((TextView) view.findViewById(R.id.search_key)).getText().toString();
            String searchIsLoc = ((TextView) view.findViewById(R.id.search_isLoc)).getText().toString();

            //如果是定位搜索
            if (searchIsLoc.equals("1")) {
                String lng = ((TextView) view.findViewById(R.id.search_longitude)).getText().toString();
                String lat = ((TextView) view.findViewById(R.id.search_latitude)).getText().toString();
                mMainViewModel.selectPoint(new LatLng(Double.parseDouble(lat), Double.parseDouble(lng)));
                mMapController.getAMap().moveCamera(CameraUpdateFactory.changeLatLng(mMapState.getSelectedPoint()));

                mMapController.markMap();

                //设置列表不可见
                mHistoryLayout.setVisibility(View.INVISIBLE);
                searchItem.collapseActionView();
                //更新表：搜索历史定位点选保存收编于 PoiSearchHelper（gcj02towgs84 换算 + SearchRow 构造 + 落库）
                mPoiSearchHelper.savePoiSelection(searchKey, searchDescription,
                        mMapState.getSelectedPoint(), lng, lat);
            } else if (searchIsLoc.equals("0")) { //如果仅仅是搜索
                try {
                    searchView.setQuery(searchKey, true);
                } catch (Exception e) {
                    GoUtils.DisplayToast(mActivity, mActivity.getResources().getString(R.string.app_error_search));
                    XLog.e(mActivity.getResources().getString(R.string.app_error_search));
                }
            } else {
                XLog.e(mActivity.getResources().getString(R.string.app_error_param));
            }
        });
        mSearchHistoryList.setOnItemLongClickListener((parent, view, position, id) -> {
            new AlertDialog.Builder(mActivity)
                    .setTitle(mActivity.getResources().getString(R.string.dialog_title_warning))//这里是表头的内容
                    .setMessage(mActivity.getResources().getString(R.string.main_search_delete_confirm))//这里是中间显示的具体信息
                    .setPositiveButton(mActivity.getResources().getString(R.string.dialog_button_ok),(dialog, which) -> {
                        String searchKey = ((TextView) view.findViewById(R.id.search_key)).getText().toString();

                        try {
                            //删除与历史行重取收编于 PoiSearchHelper，SimpleAdapter 渲染属 UI 保留在此
                            mPoiSearchHelper.deleteSearchHistory(searchKey);
                            //删除成功
                            //展示搜索历史（渲染块与 onMenuItemActionExpand 逐字一致，收编为共用方法）
                            displaySearchHistory();
                        } catch (Exception e) {
                            XLog.e("ERROR: delete database error");
                            GoUtils.DisplayToast(mActivity,mActivity.getResources().getString(R.string.history_delete_error));
                        }
                    })
                    .setNegativeButton(mActivity.getResources().getString(R.string.dialog_button_cancel),
                            (dialog, which) -> {
                            })
                    .show();
            return true;
        });
        //设置搜索建议返回值监听：Inputtips 构造与监听注册收编于 PoiSearchHelper（建议搜索管线），
        //无结果提示与列表渲染回调体保留在本类（原样）
        mPoiSearchHelper = new PoiSearchHelper(mActivity, mHistoryRepository, new PoiSearchHelper.SuggestionListener() {
            @Override
            public void onSuggestionUnavailable() {
                GoUtils.DisplayToast(mActivity, mActivity.getResources().getString(R.string.app_search_null));
            }

            @Override
            public void onSuggestionResult(List<Map<String, Object>> data) {
                //镜像建议结果（行为冻结：渲染保持本回调线程直接渲染路径，时序不变，仅补状态镜像）
                mMainViewModel.postSearchResults(data);
                SimpleAdapter simAdapt = new SimpleAdapter(
                        mActivity,
                        data,
                        R.layout.search_poi_item,
                        new String[] {PoiSearchHelper.POI_NAME, PoiSearchHelper.POI_ADDRESS, PoiSearchHelper.POI_LONGITUDE, PoiSearchHelper.POI_LATITUDE}, // 与下面数组元素要一一对应
                        new int[] {R.id.poi_name, R.id.poi_address, R.id.poi_longitude, R.id.poi_latitude});
                mSearchList.setAdapter(simAdapt);
                mSearchLayout.setVisibility(View.VISIBLE);
            }
        });
    }

    //展示搜索历史（onMenuItemActionExpand 与长按删除后重取两处渲染块逐字一致，原样收编为共用方法）
    private void displaySearchHistory() {
        List<Map<String, Object>> data = mPoiSearchHelper.loadSearchHistoryRows();
        if (data == null) {
            // 存储读取失败:提示且不渲染历史面板(区别于"无搜索历史"的静默空态)
            GoUtils.DisplayToast(mActivity, mActivity.getResources().getString(R.string.app_data_read_failed));
            return;
        }

        if (!data.isEmpty()) {
            SimpleAdapter simAdapt = new SimpleAdapter(
                    mActivity,
                    data,
                    R.layout.search_item,
                    new String[] {HistoryRepository.DB_COLUMN_KEY,
                            HistoryRepository.DB_COLUMN_DESCRIPTION,
                            HistoryRepository.DB_COLUMN_TIMESTAMP,
                            HistoryRepository.DB_COLUMN_IS_LOCATION,
                            HistoryRepository.DB_COLUMN_LONGITUDE_CUSTOM,
                            HistoryRepository.DB_COLUMN_LATITUDE_CUSTOM},
                    new int[] {R.id.search_key,
                            R.id.search_description,
                            R.id.search_timestamp,
                            R.id.search_isLoc,
                            R.id.search_longitude,
                            R.id.search_latitude});
            mSearchHistoryList.setAdapter(simAdapt);
            mHistoryLayout.setVisibility(View.VISIBLE);
        }
    }
}
