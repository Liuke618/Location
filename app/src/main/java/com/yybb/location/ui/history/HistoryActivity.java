package com.yybb.location.ui.history;

import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.SimpleAdapter;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.view.Gravity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.yybb.location.data.HistoryRepository;
import com.yybb.location.data.HistoryRepository.LocationRow;
import com.yybb.location.BaseActivity;
import com.yybb.location.GoApplication;
import com.yybb.location.R;
import com.yybb.location.utils.GoUtils;
import com.yybb.location.ui.map.MainActivity;

/**
 * 历史记录页：经 HistoryRepository 展示定位/搜索历史列表，支持单条/整表删除与过期记录清理。
 */
public class HistoryActivity extends BaseActivity {
    public static final String KEY_ID = "KEY_ID";
    public static final String KEY_LOCATION = "KEY_LOCATION";
    public static final String KEY_TIME = "KEY_TIME";
    public static final String KEY_LNG_LAT_WGS = "KEY_LNG_LAT_WGS";
    public static final String KEY_LNG_LAT_CUSTOM = "KEY_LNG_LAT_CUSTOM";

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

        setContentView(R.layout.activity_history);

        ActionBar actionBar = getSupportActionBar();
        if(actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        initLocationDataBase();

        initSearchView();

        initRecordListView();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this add items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_history, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == android.R.id.home) {
            this.finish(); // back button
            return true;
        } else if (id ==  R.id.action_delete) {
            new AlertDialog.Builder(HistoryActivity.this)
                    .setTitle("警告")//这里是表头的内容
                    .setMessage("确定要删除全部历史记录吗?")//这里是中间显示的具体信息
                    .setPositiveButton("确定",
                            (dialog, which) -> {
                                if (deleteRecord(-1)) {
                                    GoUtils.DisplayToast(this, getResources().getString(R.string.history_delete_ok));
                                    updateRecordList();
                                } else {
                                    GoUtils.DisplayToast(this, getResources().getString(R.string.history_delete_error));
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

    private void initLocationDataBase() {
        try {
            mHistoryRepository = ((GoApplication) getApplicationContext()).getAppContainer().getHistoryRepository();
        } catch (Exception e) {
            Log.e("HistoryActivity", "ERROR - initLocationDataBase");
        }

        recordArchive();
    }

    //sqlite 操作 查询所有记录
    private List<Map<String, Object>> fetchAllRecord() {
        List<Map<String, Object>> data = new ArrayList<>();

        try {
            for (LocationRow row : mHistoryRepository.getLocationRows()) {
                Map<String, Object> item = new HashMap<>();
                int ID = row.id;
                String Location = row.location;
                String Longitude = row.lngWgs;
                String Latitude = row.latWgs;
                long TimeStamp = row.timestamp;
                String BD09Longitude = row.lngCustom;
                String BD09Latitude = row.latCustom;
                BigDecimal bigDecimalLongitude = BigDecimal.valueOf(Double.parseDouble(Longitude));
                BigDecimal bigDecimalLatitude = BigDecimal.valueOf(Double.parseDouble(Latitude));
                BigDecimal bigDecimalBDLongitude = BigDecimal.valueOf(Double.parseDouble(BD09Longitude));
                BigDecimal bigDecimalBDLatitude = BigDecimal.valueOf(Double.parseDouble(BD09Latitude));
                double doubleLongitude = bigDecimalLongitude.setScale(11, RoundingMode.HALF_UP).doubleValue();
                double doubleLatitude = bigDecimalLatitude.setScale(11, RoundingMode.HALF_UP).doubleValue();
                double doubleBDLongitude = bigDecimalBDLongitude.setScale(11, RoundingMode.HALF_UP).doubleValue();
                double doubleBDLatitude = bigDecimalBDLatitude.setScale(11, RoundingMode.HALF_UP).doubleValue();
                item.put(KEY_ID, Integer.toString(ID));
                item.put(KEY_LOCATION, Location);
                item.put(KEY_TIME, GoUtils.timeStamp2Date(Long.toString(TimeStamp)));
                item.put(KEY_LNG_LAT_WGS, "[经度:" + doubleLongitude + " 纬度:" + doubleLatitude + "]");
                item.put(KEY_LNG_LAT_CUSTOM, "[经度:" + doubleBDLongitude + " 纬度:" + doubleBDLatitude + "]");
                data.add(item);
            }
        } catch (Exception e) {
            data.clear();
            Log.e("HistoryActivity", "ERROR - fetchAllRecord");
        }

        return data;
    }

    private void recordArchive() {
        // 键名以 preferences_main.xml 为准：原读的 setting_pos_history 从未被写入，
        // 设置页的"历史记录有效期"因此一直按默认 7 天生效
        double limits = GoUtils.parseDoubleOrDefault(
                sharedPreferences.getString("setting_history_expiration", getResources().getString(R.string.history_expiration)),
                7);
        // 非正数会把清理截止点推到当下甚至未来从而清空全部历史，按非法输入回退默认值
        if (limits <= 0) {
            limits = 7;
        }
        final long weekSecond = (long) (limits * 24 * 60 * 60);

        try {
            if (!mHistoryRepository.clearExpiredLocationRows(System.currentTimeMillis() / 1000 - weekSecond)) {
                Log.e("HistoryActivity", "ERROR - recordArchive(expired rows not cleared)");
            }
        } catch (Exception e) {
            Log.e("HistoryActivity", "ERROR - recordArchive");
        }
    }

    private boolean deleteRecord(int ID) {
        try {
            if (ID <= -1) {
                return mHistoryRepository.clearAllLocationRows();
            }
            return mHistoryRepository.deleteLocationRow(ID);
        } catch (Exception e) {
            Log.e("HistoryActivity", "ERROR - deleteRecord");
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
                            HistoryActivity.this.getBaseContext(),
                            mAllRecord,
                            R.layout.history_item,
                            new String[]{KEY_ID, KEY_LOCATION, KEY_TIME, KEY_LNG_LAT_WGS, KEY_LNG_LAT_CUSTOM}, // 与下面数组元素要一一对应
                            new int[]{R.id.LocationID, R.id.LocationText, R.id.TimeText, R.id.WGSLatLngText, R.id.BDLatLngText});
                    mRecordListView.setAdapter(simAdapt);
                } else {
                    List<Map<String, Object>> searchRet = new ArrayList<>();
                    for (int i = 0; i < mAllRecord.size(); i++){
                        // indexOf 命中位置 0(首字符即匹配)时返回 0，原 > 0 判定会漏掉这类记录
                        if (mAllRecord.get(i).toString().indexOf(newText) >= 0){
                            searchRet.add(mAllRecord.get(i));
                        }
                    }
                    if (!searchRet.isEmpty()) {
                        SimpleAdapter simAdapt = new SimpleAdapter(
                                HistoryActivity.this.getBaseContext(),
                                searchRet,
                                R.layout.history_item,
                                new String[]{KEY_ID, KEY_LOCATION, KEY_TIME, KEY_LNG_LAT_WGS, KEY_LNG_LAT_CUSTOM}, // 与下面数组元素要一一对应
                                new int[]{R.id.LocationID, R.id.LocationText, R.id.TimeText, R.id.WGSLatLngText, R.id.BDLatLngText});
                        mRecordListView.setAdapter(simAdapt);
                    } else {
                        GoUtils.DisplayToast(HistoryActivity.this, getResources().getString(R.string.history_error_search));
                        SimpleAdapter simAdapt = new SimpleAdapter(
                                HistoryActivity.this.getBaseContext(),
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
        builder.setMessage("确定要删除该项历史记录吗?");
        builder.setPositiveButton("确定", (dialog, whichButton) -> {
            boolean deleteRet = deleteRecord(Integer.parseInt(locID));
            if (deleteRet) {
                GoUtils.DisplayToast(HistoryActivity.this, getResources().getString(R.string.history_delete_ok));
                updateRecordList();
            } else {
                GoUtils.DisplayToast(HistoryActivity.this, getResources().getString(R.string.history_delete_error));
            }
        });
        builder.setNegativeButton("取消", null);

        builder.show();
    }

    // 收藏既有历史行：按 id 取原始行，字段原样复制插入收藏表(收藏是复制不是移动，历史行保留)
    private void favoriteRecord(int ID) {
        try {
            for (LocationRow row : mHistoryRepository.getLocationRows()) {
                if (row.id == ID) {
                    if (mHistoryRepository.addFavoriteRow(row)) {
                        GoUtils.DisplayToast(this, getResources().getString(R.string.app_location_mark));
                    } else {
                        GoUtils.DisplayToast(this, getResources().getString(R.string.app_location_mark_failed));
                    }
                    return;
                }
            }
            // 未找到对应行(极端时序下已被清理/删除)，按失败提示而非谎报成功
            GoUtils.DisplayToast(this, getResources().getString(R.string.app_location_mark_failed));
        } catch (Exception e) {
            Log.e("HistoryActivity", "ERROR - favoriteRecord");
            GoUtils.DisplayToast(this, getResources().getString(R.string.app_location_mark_failed));
        }
    }

    private boolean renameRecord(int ID, String newName) {
        try {
            return mHistoryRepository.renameLocationRow(ID, newName);
        } catch (Exception e) {
            Log.e("HistoryActivity", "ERROR - renameRecord");
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
                GoUtils.DisplayToast(HistoryActivity.this, getResources().getString(R.string.history_rename_error));
            }
        });
        builder.setNegativeButton("取消", null);

        builder.show();
    }

    private String[] randomOffset(String longitude, String latitude) {
        // 偏移算法与默认值收编于 GoUtils(历史/收藏页点击行共用同一套随机偏移)
        return GoUtils.randomOffset(this, sharedPreferences, longitude, latitude);
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
            String bd09LatLng = (String) ((TextView) view.findViewById(R.id.BDLatLngText)).getText();
            bd09LatLng = bd09LatLng.substring(bd09LatLng.indexOf('[') + 1, bd09LatLng.indexOf(']'));
            String[] latLngStr = bd09LatLng.split(" ");
            gcjLongitude = latLngStr[0].substring(latLngStr[0].indexOf(':') + 1);
            gcjLatitude = latLngStr[1].substring(latLngStr[1].indexOf(':') + 1);

            // Random offset
            if(sharedPreferences.getBoolean("setting_random_offset", false)) {
                String[] offsetResult = randomOffset(gcjLongitude, gcjLatitude);
                gcjLongitude = offsetResult[0];
                gcjLatitude = offsetResult[1];
            }

            if (!MainActivity.showLocation(name, gcjLongitude, gcjLatitude)) {
                GoUtils.DisplayToast(this, getResources().getString(R.string.history_error_location));
            }
            this.finish();
        });

        mRecordListView.setOnItemLongClickListener((parent, view, position, id) -> {
            PopupMenu popupMenu = new PopupMenu(HistoryActivity.this, view);
            popupMenu.setGravity(Gravity.END | Gravity.BOTTOM);
            popupMenu.getMenu().add("编辑");
            popupMenu.getMenu().add("收藏");
            popupMenu.getMenu().add("删除");

            popupMenu.setOnMenuItemClickListener(item -> {
                String locID = ((TextView) view.findViewById(R.id.LocationID)).getText().toString();
                String name = ((TextView) view.findViewById(R.id.LocationText)).getText().toString();
                switch (item.getTitle().toString()) {
                    case "编辑":
                        showInputDialog(locID, name);
                        return true;
                    case "收藏":
                        favoriteRecord(Integer.parseInt(locID));
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
                Log.e("HistoryActivity", "ERROR - updateRecordList");
            }
        }
    }
}