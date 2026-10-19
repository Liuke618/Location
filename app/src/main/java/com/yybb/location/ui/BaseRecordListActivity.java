package com.yybb.location.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.SearchView;
import android.widget.SimpleAdapter;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import com.elvishew.xlog.XLog;
import com.yybb.location.BaseActivity;
import com.yybb.location.R;
import com.yybb.location.data.HistoryRepository;
import com.yybb.location.data.HistoryRepository.LocationRow;
import com.yybb.location.ui.map.MainActivity;
import com.yybb.location.utils.GoUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 历史/收藏列表页共用基类：两页除数据源、行装饰与长按菜单动作外流程一致，
 * 搜索本地过滤、空态与列表可见性、删除/改名/清空对话框、点击行回填主界面集中于此。
 * 行为冻结：提示文案、控件可见性、适配器绑定与调用顺序均与原两页各自实现一致；
 * 子类只提供布局/菜单资源、数据读取、行装饰与具体动作。
 */
public abstract class BaseRecordListActivity extends BaseActivity {
    public static final String KEY_ID = "KEY_ID";
    public static final String KEY_LOCATION = "KEY_LOCATION";
    public static final String KEY_TIME = "KEY_TIME";
    public static final String KEY_LNG_LAT_WGS = "KEY_LNG_LAT_WGS";
    public static final String KEY_LNG_LAT_CUSTOM = "KEY_LNG_LAT_CUSTOM";

    private ListView mRecordListView;
    private TextView noRecordText;
    private LinearLayout mSearchLayout;
    protected HistoryRepository mHistoryRepository;
    protected List<Map<String, Object>> mAllRecord;
    protected SharedPreferences sharedPreferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /* 状态栏颜色由主题统一处理:透明状态栏 + 根布局 fitsSystemWindows,状态栏区域露出窗口背景(surface) */

        setContentView(getLayoutRes());

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        initRepository();

        initSearchView();

        initRecordListView();
    }

    /** 布局资源（activity_history / activity_favorite） */
    protected abstract int getLayoutRes();

    /** 菜单资源（menu_history / menu_favorite） */
    protected abstract int getMenuRes();

    /**
     * 初始化仓库（子类转调 AppContainer）；历史页在此顺带执行过期清理。
     * 调用时 sharedPreferences 已就绪、列表控件尚未初始化，与原两页 onCreate 顺序一致。
     */
    protected abstract void initRepository();

    /** 读取整表：null=存储读取失败（与空列表=空表区分） */
    protected abstract List<LocationRow> loadRows();

    /** 日志前缀（"HISTORY"/"FAVORITE"），日志文案与改造前保持一致 */
    protected abstract String getLogTag();

    /** 行装饰钩子：收藏页补默认 ★ 与原始 WGS84 键；历史页无需装饰 */
    protected void decorateItem(Map<String, Object> item, LocationRow row) {
    }

    /** 删除单行；id<=-1 表示清空整表（子类实现保留原语义） */
    protected abstract boolean deleteRecord(int id);

    /** 改名 */
    protected abstract boolean renameRecord(int id, String newName);

    /** 填充长按菜单项（子类决定条目、文案与顺序;itemId 供点击分发,不依赖显示文案） */
    protected abstract void onCreateLongClickMenu(Menu menu, String locID);

    /** 长按菜单点击分发（按菜单项 id;资源化后逻辑不再与显示文案耦合） */
    protected abstract boolean onLongClickMenuItem(int itemId, String locID, String name);

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(getMenuRes(), menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            this.finish(); // back button
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void initSearchView() {
        SearchView searchView = findViewById(R.id.searchView);
        searchView.onActionViewExpanded();// 当展开无输入内容的时候，没有关闭的图标
        searchView.setSubmitButtonEnabled(false);//显示提交按钮
        searchView.setFocusable(false);
        searchView.clearFocus();
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {// 当点击搜索按钮时触发该方法
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {// 当搜索内容改变时触发该方法
                if (TextUtils.isEmpty(newText)) {
                    mRecordListView.setAdapter(createAdapter(BaseRecordListActivity.this.getBaseContext(), mAllRecord));
                } else {
                    List<Map<String, Object>> searchRet = new ArrayList<>();
                    for (int i = 0; i < mAllRecord.size(); i++) {
                        // indexOf 命中位置 0(首字符即匹配)时返回 0，原 > 0 判定会漏掉这类记录
                        if (mAllRecord.get(i).toString().indexOf(newText) >= 0) {
                            searchRet.add(mAllRecord.get(i));
                        }
                    }
                    if (!searchRet.isEmpty()) {
                        mRecordListView.setAdapter(createAdapter(BaseRecordListActivity.this.getBaseContext(), searchRet));
                    } else {
                        GoUtils.DisplayToast(BaseRecordListActivity.this, getResources().getString(R.string.history_error_search));
                        mRecordListView.setAdapter(createAdapter(BaseRecordListActivity.this.getBaseContext(), mAllRecord));
                    }
                }

                return false;
            }
        });
    }

    private SimpleAdapter createAdapter(Context context, List<Map<String, Object>> data) {
        return new SimpleAdapter(
                context,
                data,
                R.layout.history_item,
                new String[]{KEY_ID, KEY_LOCATION, KEY_TIME, KEY_LNG_LAT_WGS, KEY_LNG_LAT_CUSTOM}, // 与下面数组元素要一一对应
                new int[]{R.id.LocationID, R.id.LocationText, R.id.TimeText, R.id.WGSLatLngText, R.id.BDLatLngText});
    }

    private void initRecordListView() {
        noRecordText = findViewById(R.id.record_no_textview);
        mSearchLayout = findViewById(R.id.search_linear);
        mRecordListView = findViewById(R.id.record_list_view);
        mRecordListView.setOnItemClickListener((adapterView, view, i, l) -> {
            String name = (String) ((TextView) view.findViewById(R.id.LocationText)).getText();
            String latLng = (String) ((TextView) view.findViewById(R.id.BDLatLngText)).getText();
            latLng = latLng.substring(latLng.indexOf('[') + 1, latLng.indexOf(']'));
            String[] latLngStr = latLng.split(" ");
            String gcjLongitude = latLngStr[0].substring(latLngStr[0].indexOf(':') + 1);
            String gcjLatitude = latLngStr[1].substring(latLngStr[1].indexOf(':') + 1);

            // Random offset
            if (sharedPreferences.getBoolean("setting_random_offset", false)) {
                String[] offsetResult = GoUtils.randomOffset(BaseRecordListActivity.this, sharedPreferences, gcjLongitude, gcjLatitude);
                gcjLongitude = offsetResult[0];
                gcjLatitude = offsetResult[1];
            }

            if (!MainActivity.showLocation(name, gcjLongitude, gcjLatitude)) {
                GoUtils.DisplayToast(BaseRecordListActivity.this, getResources().getString(R.string.history_error_location));
            }
            BaseRecordListActivity.this.finish();
        });

        mRecordListView.setOnItemLongClickListener((parent, view, position, id) -> {
            PopupMenu popupMenu = new PopupMenu(BaseRecordListActivity.this, view);
            popupMenu.setGravity(Gravity.END | Gravity.BOTTOM);
            String locID = ((TextView) view.findViewById(R.id.LocationID)).getText().toString();
            onCreateLongClickMenu(popupMenu.getMenu(), locID);

            popupMenu.setOnMenuItemClickListener(item -> {
                String name = ((TextView) view.findViewById(R.id.LocationText)).getText().toString();
                return onLongClickMenuItem(item.getItemId(), locID, name);
            });

            popupMenu.show();
            return true;
        });

        updateRecordList();
    }

    //sqlite 操作 查询所有记录（数据源由子类提供）
    private List<Map<String, Object>> fetchAllRecord() {
        List<LocationRow> rows;
        try {
            rows = loadRows();
        } catch (Exception e) {
            XLog.e(getLogTag() + ": ERROR - fetchAllRecord");
            return null;
        }
        if (rows == null) {
            // 存储读取失败:返回 null 交由 updateRecordList 提示,与空表(空列表)区分
            return null;
        }

        List<Map<String, Object>> data = new ArrayList<>();
        for (LocationRow row : rows) {
            try {
                Map<String, Object> item = new HashMap<>();
                item.put(KEY_ID, Integer.toString(row.id));
                item.put(KEY_LOCATION, row.location);
                item.put(KEY_TIME, GoUtils.timeStamp2Date(Long.toString(row.timestamp)));
                item.put(KEY_LNG_LAT_WGS, "[经度:" + GoUtils.formatCoordinate(row.lngWgs) + " 纬度:" + GoUtils.formatCoordinate(row.latWgs) + "]");
                item.put(KEY_LNG_LAT_CUSTOM, "[经度:" + GoUtils.formatCoordinate(row.lngCustom) + " 纬度:" + GoUtils.formatCoordinate(row.latCustom) + "]");
                decorateItem(item, row);
                data.add(item);
            } catch (Exception e) {
                // 单行数据损坏(如坐标串解析不出)只跳过该行记日志:整表清空会让"有记录"误显示成"没记录"
                XLog.e(getLogTag() + ": skip unparsable row id=" + row.id);
            }
        }

        return data;
    }

    protected void showDeleteDialog(String locID, String message, int okToastRes, int errorToastRes) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getResources().getString(R.string.dialog_title_warning));
        builder.setMessage(message);
        builder.setPositiveButton(getResources().getString(R.string.dialog_button_ok), (dialog, whichButton) -> {
            boolean deleteRet = deleteRecord(Integer.parseInt(locID));
            if (deleteRet) {
                GoUtils.DisplayToast(BaseRecordListActivity.this, getResources().getString(okToastRes));
                updateRecordList();
            } else {
                GoUtils.DisplayToast(BaseRecordListActivity.this, getResources().getString(errorToastRes));
            }
        });
        builder.setNegativeButton(getResources().getString(R.string.dialog_button_cancel), null);

        builder.show();
    }

    protected void showClearAllDialog(String message, int okToastRes, int errorToastRes) {
        new AlertDialog.Builder(this)
                .setTitle(getResources().getString(R.string.dialog_title_warning))//这里是表头的内容
                .setMessage(message)//这里是中间显示的具体信息
                .setPositiveButton(getResources().getString(R.string.dialog_button_ok),
                        (dialog, which) -> {
                            if (deleteRecord(-1)) {
                                GoUtils.DisplayToast(this, getResources().getString(okToastRes));
                                updateRecordList();
                            } else {
                                GoUtils.DisplayToast(this, getResources().getString(errorToastRes));
                            }
                        })
                .setNegativeButton(getResources().getString(R.string.dialog_button_cancel),
                        (dialog, which) -> {
                        })
                .show();
    }

    protected void showInputDialog(String locID, String name, int renameErrorRes) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(name);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getResources().getString(R.string.record_rename_title));
        builder.setView(input);
        builder.setPositiveButton(getResources().getString(R.string.record_rename_confirm), (dialog, whichButton) -> {
            String userInput = input.getText().toString();
            if (renameRecord(Integer.parseInt(locID), userInput)) {
                updateRecordList();
            } else {
                GoUtils.DisplayToast(BaseRecordListActivity.this, getResources().getString(renameErrorRes));
            }
        });
        builder.setNegativeButton(getResources().getString(R.string.dialog_button_cancel), null);

        builder.show();
    }

    protected final void updateRecordList() {
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
                mRecordListView.setAdapter(createAdapter(this, mAllRecord));
            } catch (Exception e) {
                XLog.e(getLogTag() + ": ERROR - updateRecordList");
            }
        }
    }
}
