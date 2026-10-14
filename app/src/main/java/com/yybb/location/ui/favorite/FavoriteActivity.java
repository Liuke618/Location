package com.yybb.location.ui.favorite;

import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import android.text.InputType;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.SimpleAdapter;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.view.Gravity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.elvishew.xlog.XLog;
import com.yybb.location.data.HistoryRepository;
import com.yybb.location.data.HistoryRepository.LocationRow;
import com.yybb.location.BaseActivity;
import com.yybb.location.GoApplication;
import com.yybb.location.R;
import com.yybb.location.utils.GoUtils;
import com.yybb.location.ui.map.MainActivity;

/**
 * 收藏页：经 HistoryRepository 展示收藏地点列表（收藏不参与历史有效期清理），
 * 支持搜索过滤、手动添加、单条改名/删除与整表清空；点击行把该点回填地图选中，
 * 传送前置路径与历史页一致（随机偏移设置同样生效，共用 GoUtils.randomOffset）。
 */
public class FavoriteActivity extends BaseActivity {
    public static final String KEY_ID = "KEY_ID";
    public static final String KEY_LOCATION = "KEY_LOCATION";
    public static final String KEY_TIME = "KEY_TIME";
    public static final String KEY_LNG_LAT_WGS = "KEY_LNG_LAT_WGS";
    public static final String KEY_LNG_LAT_CUSTOM = "KEY_LNG_LAT_CUSTOM";
    /* 原始 WGS84 坐标（不绑视图）：供"设为默认/取消默认"与删除时判断默认使用 */
    public static final String KEY_LNG_WGS_RAW = "KEY_LNG_WGS_RAW";
    public static final String KEY_LAT_WGS_RAW = "KEY_LAT_WGS_RAW";

    private ListView mRecordListView;
    private TextView noRecordText;
    private LinearLayout mSearchLayout;
    private HistoryRepository mHistoryRepository;
    private List<Map<String, Object>> mAllRecord;
    private SharedPreferences sharedPreferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /* 状态栏颜色由主题统一处理:透明状态栏 + 根布局 fitsSystemWindows,状态栏区域露出窗口背景(surface) */

        setContentView(R.layout.activity_favorite);

        ActionBar actionBar = getSupportActionBar();
        if(actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        initRepository();

        initSearchView();

        initRecordListView();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this add items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_favorite, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == android.R.id.home) {
            this.finish(); // back button
            return true;
        } else if (id == R.id.action_add) {
            showAddFavoriteDialog();
            return true;
        } else if (id ==  R.id.action_clear) {
            new AlertDialog.Builder(FavoriteActivity.this)
                    .setTitle("警告")//这里是表头的内容
                    .setMessage("确定要删除全部收藏吗?")//这里是中间显示的具体信息
                    .setPositiveButton("确定",
                            (dialog, which) -> {
                                if (deleteRecord(-1)) {
                                    GoUtils.DisplayToast(this, getResources().getString(R.string.favorite_delete_ok));
                                    updateRecordList();
                                } else {
                                    GoUtils.DisplayToast(this, getResources().getString(R.string.favorite_delete_error));
                                }
                            })
                    .setNegativeButton("取消",
                            (dialog, which) -> {
                            })
                    .show();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void initRepository() {
        try {
            mHistoryRepository = ((GoApplication) getApplicationContext()).getAppContainer().getHistoryRepository();
        } catch (Exception e) {
            XLog.e("FAVORITE: ERROR - initRepository");
        }
    }

    //查询所有收藏(行形与历史页一致，渲染复用 history_item 布局)
    private List<Map<String, Object>> fetchAllRecord() {
        List<Map<String, Object>> data = new ArrayList<>();

        try {
            List<LocationRow> rows = mHistoryRepository.getFavoriteRows();
            if (rows == null) {
                // 存储读取失败:返回 null 交由 updateRecordList 提示,与空表(空列表)区分
                return null;
            }
            for (LocationRow row : rows) {
                Map<String, Object> item = new HashMap<>();
                int ID = row.id;
                String Location = row.location;
                String Longitude = row.lngWgs;
                String Latitude = row.latWgs;
                long TimeStamp = row.timestamp;
                String GCJLongitude = row.lngCustom;
                String GCJLatitude = row.latCustom;
                BigDecimal bigDecimalLongitude = BigDecimal.valueOf(Double.parseDouble(Longitude));
                BigDecimal bigDecimalLatitude = BigDecimal.valueOf(Double.parseDouble(Latitude));
                BigDecimal bigDecimalGCJLongitude = BigDecimal.valueOf(Double.parseDouble(GCJLongitude));
                BigDecimal bigDecimalGCJLatitude = BigDecimal.valueOf(Double.parseDouble(GCJLatitude));
                double doubleLongitude = bigDecimalLongitude.setScale(11, RoundingMode.HALF_UP).doubleValue();
                double doubleLatitude = bigDecimalLatitude.setScale(11, RoundingMode.HALF_UP).doubleValue();
                double doubleGCJLongitude = bigDecimalGCJLongitude.setScale(11, RoundingMode.HALF_UP).doubleValue();
                double doubleGCJLatitude = bigDecimalGCJLatitude.setScale(11, RoundingMode.HALF_UP).doubleValue();
                item.put(KEY_ID, Integer.toString(ID));
                // 默认收藏名称前加 ★ 标识(仅展示层加前缀,存储值不变)
                item.put(KEY_LOCATION, isDefaultFavorite(Longitude, Latitude) ? "★ " + Location : Location);
                item.put(KEY_TIME, GoUtils.timeStamp2Date(Long.toString(TimeStamp)));
                item.put(KEY_LNG_LAT_WGS, "[经度:" + doubleLongitude + " 纬度:" + doubleLatitude + "]");
                item.put(KEY_LNG_LAT_CUSTOM, "[经度:" + doubleGCJLongitude + " 纬度:" + doubleGCJLatitude + "]");
                // SimpleAdapter 只读声明键，多余键无副作用
                item.put(KEY_LNG_WGS_RAW, Longitude);
                item.put(KEY_LAT_WGS_RAW, Latitude);
                data.add(item);
            }
        } catch (Exception e) {
            data.clear();
            XLog.e("FAVORITE: ERROR - fetchAllRecord");
        }

        return data;
    }

    private boolean deleteRecord(int ID) {
        try {
            if (ID <= -1) {
                boolean ok = mHistoryRepository.clearAllFavoriteRows();
                // 清空收藏后默认必失效，同步清偏好，避免同坐标再收藏时默认悄悄复活
                if (ok) {
                    clearDefaultFavoriteKeys();
                }
                return ok;
            }
            // 删除的是默认收藏时同步清偏好(默认以 WGS84 坐标身份存偏好,行删后查询不到但键会残留)
            Map<String, Object> item = findRecordItemById(Integer.toString(ID));
            boolean ok = mHistoryRepository.deleteFavoriteRow(ID);
            if (ok && item != null && isDefaultFavorite((String) item.get(KEY_LNG_WGS_RAW),
                    (String) item.get(KEY_LAT_WGS_RAW))) {
                clearDefaultFavoriteKeys();
            }
            return ok;
        } catch (Exception e) {
            XLog.e("FAVORITE: ERROR - deleteRecord");
            return false;
        }
    }

    private void initSearchView() {
        SearchView mSearchView = findViewById(R.id.searchView);
        mSearchView.onActionViewExpanded();// 当展开无输入内容的时候，没有关闭的图标
        mSearchView.setSubmitButtonEnabled(false);//显示提交按钮
        mSearchView.setFocusable(false);
        mSearchView.clearFocus();
        mSearchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {// 当点击搜索按钮时触发该方法
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {// 当搜索内容改变时触发该方法
                if (TextUtils.isEmpty(newText)) {
                    SimpleAdapter simAdapt = new SimpleAdapter(
                            FavoriteActivity.this.getBaseContext(),
                            mAllRecord,
                            R.layout.history_item,
                            new String[]{KEY_ID, KEY_LOCATION, KEY_TIME, KEY_LNG_LAT_WGS, KEY_LNG_LAT_CUSTOM}, // 与下面数组元素要一一对应
                            new int[]{R.id.LocationID, R.id.LocationText, R.id.TimeText, R.id.WGSLatLngText, R.id.BDLatLngText});
                    mRecordListView.setAdapter(simAdapt);
                } else {
                    List<Map<String, Object>> searchRet = new ArrayList<>();
                    for (int i = 0; i < mAllRecord.size(); i++){
                        // indexOf 命中位置 0(首字符即匹配)时返回 0，与历史页同判定
                        if (mAllRecord.get(i).toString().indexOf(newText) >= 0){
                            searchRet.add(mAllRecord.get(i));
                        }
                    }
                    if (!searchRet.isEmpty()) {
                        SimpleAdapter simAdapt = new SimpleAdapter(
                                FavoriteActivity.this.getBaseContext(),
                                searchRet,
                                R.layout.history_item,
                                new String[]{KEY_ID, KEY_LOCATION, KEY_TIME, KEY_LNG_LAT_WGS, KEY_LNG_LAT_CUSTOM}, // 与下面数组元素要一一对应
                                new int[]{R.id.LocationID, R.id.LocationText, R.id.TimeText, R.id.WGSLatLngText, R.id.BDLatLngText});
                        mRecordListView.setAdapter(simAdapt);
                    } else {
                        GoUtils.DisplayToast(FavoriteActivity.this, getResources().getString(R.string.history_error_search));
                        SimpleAdapter simAdapt = new SimpleAdapter(
                                FavoriteActivity.this.getBaseContext(),
                                mAllRecord,
                                R.layout.history_item,
                                new String[]{KEY_ID, KEY_LOCATION, KEY_TIME, KEY_LNG_LAT_WGS, KEY_LNG_LAT_CUSTOM}, // 与下面数组元素要一一对应
                                new int[]{R.id.LocationID, R.id.LocationText, R.id.TimeText, R.id.WGSLatLngText, R.id.BDLatLngText});
                        mRecordListView.setAdapter(simAdapt);
                    }
                }

                return false;
            }
        });
    }

    private void showDeleteDialog(String locID) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("警告");
        builder.setMessage("确定要删除该项收藏吗?");
        builder.setPositiveButton("确定", (dialog, whichButton) -> {
            boolean deleteRet = deleteRecord(Integer.parseInt(locID));
            if (deleteRet) {
                GoUtils.DisplayToast(FavoriteActivity.this, getResources().getString(R.string.favorite_delete_ok));
                updateRecordList();
            } else {
                GoUtils.DisplayToast(FavoriteActivity.this, getResources().getString(R.string.favorite_delete_error));
            }
        });
        builder.setNegativeButton("取消", null);

        builder.show();
    }

    /*============================== 默认收藏 ==============================*/

    // 在当前列表数据里按行 id 找条目(长按菜单经 LocationID 取原始坐标,搜索过滤态下也能命中)
    private Map<String, Object> findRecordItemById(String locID) {
        if (mAllRecord == null) {
            return null;
        }
        for (Map<String, Object> item : mAllRecord) {
            if (locID.equals(item.get(KEY_ID))) {
                return item;
            }
        }
        return null;
    }

    private boolean isDefaultFavorite(String lngWgs, String latWgs) {
        return sharedPreferences.getString("setting_default_favorite_lng_wgs", "").equals(lngWgs)
                && sharedPreferences.getString("setting_default_favorite_lat_wgs", "").equals(latWgs);
    }

    private boolean isDefaultFavoriteByLocId(String locID) {
        Map<String, Object> item = findRecordItemById(locID);
        if (item == null) {
            return false;
        }
        return isDefaultFavorite((String) item.get(KEY_LNG_WGS_RAW), (String) item.get(KEY_LAT_WGS_RAW));
    }

    // 设默认以 WGS84 坐标为身份写入偏好；改名不影响判定，主界面冷启动/未选点 GO 时按此坐标回查
    private void setDefaultFavorite(String locID) {
        Map<String, Object> item = findRecordItemById(locID);
        if (item == null) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.favorite_default_failed));
            return;
        }
        sharedPreferences.edit()
                .putString("setting_default_favorite_lng_wgs", (String) item.get(KEY_LNG_WGS_RAW))
                .putString("setting_default_favorite_lat_wgs", (String) item.get(KEY_LAT_WGS_RAW))
                .apply();
        GoUtils.DisplayToast(this, getResources().getString(R.string.favorite_default_ok));
        updateRecordList();
    }

    private void clearDefaultFavorite() {
        clearDefaultFavoriteKeys();
        GoUtils.DisplayToast(this, getResources().getString(R.string.favorite_default_cleared));
        updateRecordList();
    }

    private void clearDefaultFavoriteKeys() {
        sharedPreferences.edit()
                .remove("setting_default_favorite_lng_wgs")
                .remove("setting_default_favorite_lat_wgs")
                .apply();
    }

    private boolean renameRecord(int ID, String newName) {
        try {
            return mHistoryRepository.renameFavoriteRow(ID, newName);
        } catch (Exception e) {
            XLog.e("FAVORITE: ERROR - renameRecord");
            return false;
        }
    }

    private void showInputDialog(String locID, String name) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(name);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("名称");
        builder.setView(input);
        builder.setPositiveButton("确认", (dialog, whichButton) -> {
            String userInput = input.getText().toString();
            if (renameRecord(Integer.parseInt(locID), userInput)) {
                updateRecordList();
            } else {
                GoUtils.DisplayToast(FavoriteActivity.this, getResources().getString(R.string.favorite_rename_error));
            }
        });
        builder.setNegativeButton("取消", null);

        builder.show();
    }

    /*============================== 手动添加 ==============================*/

    // 手动添加收藏对话框：名称可空(回退默认名)，经纬度按所选坐标系经 MapUtils 换算成双坐标落库
    // (同 WGS84 坐标先删后插，重复添加同一点=改名)；错误提示分支与主界面手动输入对话框一致
    private void showAddFavoriteDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_favorite_add, null);
        EditText nameInput = view.findViewById(R.id.favorite_add_name);
        EditText lngInput = view.findViewById(R.id.favorite_add_longitude);
        EditText latInput = view.findViewById(R.id.favorite_add_latitude);
        RadioButton wgsRadio = view.findViewById(R.id.favorite_add_wgs);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getResources().getString(R.string.favorite_add_title))
                .setView(view)
                .show();

        view.findViewById(R.id.favorite_add_save).setOnClickListener(v -> {
            String lngText = lngInput.getText().toString();
            String latText = latInput.getText().toString();
            int err = GoUtils.validateManualLatLng(lngText, latText);
            if (err == GoUtils.MANUAL_LATLNG_ERR_MALFORMED) {
                GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_input));
                return;
            } else if (err == GoUtils.MANUAL_LATLNG_ERR_LONGITUDE) {
                GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_longitude));
                return;
            } else if (err == GoUtils.MANUAL_LATLNG_ERR_LATITUDE) {
                GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_latitude));
                return;
            }

            double[] quad = GoUtils.convertManualLatLng(
                    GoUtils.parseDoubleOrDefault(lngText, Double.NaN),
                    GoUtils.parseDoubleOrDefault(latText, Double.NaN),
                    wgsRadio.isChecked());
            String name = nameInput.getText().toString().trim();
            if (name.isEmpty()) {
                name = getResources().getString(R.string.favorite_add_default_name);
            }

            if (addFavoriteRecord(name, quad)) {
                GoUtils.DisplayToast(this, getResources().getString(R.string.favorite_add_ok));
                dialog.dismiss();
                updateRecordList();
            } else {
                GoUtils.DisplayToast(this, getResources().getString(R.string.favorite_add_error));
            }
        });

        view.findViewById(R.id.favorite_add_cancel).setOnClickListener(v -> dialog.dismiss());
    }

    // 落库字段与 MainActivity.addFavoriteRow 逐字段对应(WGS84 经纬度、秒级时间戳、GCJ02 原坐标)
    private boolean addFavoriteRecord(String name, double[] quad) {
        try {
            LocationRow favoriteRow = new LocationRow(name, String.valueOf(quad[0]), String.valueOf(quad[1]),
                    System.currentTimeMillis() / 1000, Double.toString(quad[2]), Double.toString(quad[3]));
            return mHistoryRepository.addFavoriteRow(favoriteRow);
        } catch (Exception e) {
            XLog.e("FAVORITE: ERROR - addFavoriteRecord");
            return false;
        }
    }

    private void initRecordListView() {
        noRecordText = findViewById(R.id.record_no_textview);
        mSearchLayout = findViewById(R.id.search_linear);
        mRecordListView = findViewById(R.id.record_list_view);
        mRecordListView.setOnItemClickListener((adapterView, view, i, l) -> {
            String gcjLongitude;
            String gcjLatitude;
            String name;
            name = (String) ((TextView) view.findViewById(R.id.LocationText)).getText();
            String gcjLatLng = (String) ((TextView) view.findViewById(R.id.BDLatLngText)).getText();
            gcjLatLng = gcjLatLng.substring(gcjLatLng.indexOf('[') + 1, gcjLatLng.indexOf(']'));
            String[] latLngStr = gcjLatLng.split(" ");
            gcjLongitude = latLngStr[0].substring(latLngStr[0].indexOf(':') + 1);
            gcjLatitude = latLngStr[1].substring(latLngStr[1].indexOf(':') + 1);

            // Random offset
            if(sharedPreferences.getBoolean("setting_random_offset", false)) {
                String[] offsetResult = GoUtils.randomOffset(this, sharedPreferences, gcjLongitude, gcjLatitude);
                gcjLongitude = offsetResult[0];
                gcjLatitude = offsetResult[1];
            }

            if (!MainActivity.showLocation(name, gcjLongitude, gcjLatitude)) {
                GoUtils.DisplayToast(this, getResources().getString(R.string.history_error_location));
            }
            this.finish();
        });

        mRecordListView.setOnItemLongClickListener((parent, view, position, id) -> {
            PopupMenu popupMenu = new PopupMenu(FavoriteActivity.this, view);
            popupMenu.setGravity(Gravity.END | Gravity.BOTTOM);
            String locID = ((TextView) view.findViewById(R.id.LocationID)).getText().toString();
            popupMenu.getMenu().add("编辑");
            popupMenu.getMenu().add(isDefaultFavoriteByLocId(locID) ? "取消默认" : "设为默认");
            popupMenu.getMenu().add("删除");

            popupMenu.setOnMenuItemClickListener(item -> {
                String name = ((TextView) view.findViewById(R.id.LocationText)).getText().toString();
                switch (item.getTitle().toString()) {
                    case "编辑":
                        showInputDialog(locID, name);
                        return true;
                    case "设为默认":
                        setDefaultFavorite(locID);
                        return true;
                    case "取消默认":
                        clearDefaultFavorite();
                        return true;
                    case "删除":
                        showDeleteDialog(locID);
                        return true;
                    default:
                        return false;
                }
            });

            popupMenu.show();
            return true;
        });

        updateRecordList();
    }

    private void updateRecordList() {
        mAllRecord = fetchAllRecord();
        if (mAllRecord == null) {
            // 存储读取失败:提示后按空态渲染,用户可区分"没记录"与"读失败"
            GoUtils.DisplayToast(this, getResources().getString(R.string.app_data_read_failed));
            mAllRecord = new ArrayList<>();
        }

        if (mAllRecord.isEmpty()) {
            mRecordListView.setVisibility(View.GONE);
            mSearchLayout.setVisibility(View.GONE);
            noRecordText.setVisibility(View.VISIBLE);
        } else {
            noRecordText.setVisibility(View.GONE);
            mRecordListView.setVisibility(View.VISIBLE);
            mSearchLayout.setVisibility(View.VISIBLE);

            try {
                SimpleAdapter simAdapt = new SimpleAdapter(
                        this,
                        mAllRecord,
                        R.layout.history_item,
                        new String[]{KEY_ID, KEY_LOCATION, KEY_TIME, KEY_LNG_LAT_WGS, KEY_LNG_LAT_CUSTOM},
                        new int[]{R.id.LocationID, R.id.LocationText, R.id.TimeText, R.id.WGSLatLngText, R.id.BDLatLngText});
                mRecordListView.setAdapter(simAdapt);
            } catch (Exception e) {
                XLog.e("FAVORITE: ERROR - updateRecordList");
            }
        }
    }
}
