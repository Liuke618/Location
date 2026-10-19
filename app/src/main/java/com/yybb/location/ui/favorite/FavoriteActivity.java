package com.yybb.location.ui.favorite;

import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioButton;

import androidx.appcompat.app.AlertDialog;

import java.util.List;
import java.util.Map;

import com.elvishew.xlog.XLog;
import com.yybb.location.data.HistoryRepository.LocationRow;
import com.yybb.location.GoApplication;
import com.yybb.location.R;
import com.yybb.location.ui.BaseRecordListActivity;
import com.yybb.location.utils.GoUtils;

/**
 * 收藏页：经 HistoryRepository 展示收藏地点列表（收藏不参与历史有效期清理），
 * 支持搜索过滤、手动添加、单条改名/删除与整表清空；点击行把该点回填地图选中，
 * 传送前置路径与历史页一致（随机偏移设置同样生效，共用 GoUtils.randomOffset）。
 * 搜索过滤/空态/删除改名对话框/点击行回填等列表交互共用 BaseRecordListActivity。
 */
public class FavoriteActivity extends BaseRecordListActivity {
    /* 原始 WGS84 坐标（不绑视图）：供"设为默认/取消默认"与删除时判断默认使用 */
    public static final String KEY_LNG_WGS_RAW = "KEY_LNG_WGS_RAW";
    public static final String KEY_LAT_WGS_RAW = "KEY_LAT_WGS_RAW";

    @Override
    protected int getLayoutRes() {
        return R.layout.activity_favorite;
    }

    @Override
    protected int getMenuRes() {
        return R.menu.menu_favorite;
    }

    @Override
    protected String getLogTag() {
        return "FAVORITE";
    }

    @Override
    protected void initRepository() {
        try {
            mHistoryRepository = ((GoApplication) getApplicationContext()).getAppContainer().getHistoryRepository();
        } catch (Exception e) {
            XLog.e("FAVORITE: ERROR - initRepository");
        }
    }

    @Override
    protected List<LocationRow> loadRows() {
        return mHistoryRepository.getFavoriteRows();
    }

    @Override
    protected void decorateItem(Map<String, Object> item, LocationRow row) {
        // 默认收藏名称前加 ★ 标识(仅展示层加前缀,存储值不变)
        item.put(KEY_LOCATION, isDefaultFavorite(row.lngWgs, row.latWgs) ? "★ " + row.location : row.location);
        // SimpleAdapter 只读声明键，多余键无副作用
        item.put(KEY_LNG_WGS_RAW, row.lngWgs);
        item.put(KEY_LAT_WGS_RAW, row.latWgs);
    }

    @Override
    protected boolean deleteRecord(int ID) {
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

    @Override
    protected boolean renameRecord(int ID, String newName) {
        try {
            return mHistoryRepository.renameFavoriteRow(ID, newName);
        } catch (Exception e) {
            XLog.e("FAVORITE: ERROR - renameRecord");
            return false;
        }
    }

    @Override
    protected void onCreateLongClickMenu(Menu menu, String locID) {
        menu.add("编辑");
        menu.add(isDefaultFavoriteByLocId(locID) ? "取消默认" : "设为默认");
        menu.add("删除");
    }

    @Override
    protected boolean onLongClickMenuItem(String title, String locID, String name) {
        switch (title) {
            case "编辑":
                showInputDialog(locID, name, R.string.favorite_rename_error);
                return true;
            case "设为默认":
                setDefaultFavorite(locID);
                return true;
            case "取消默认":
                clearDefaultFavorite();
                return true;
            case "删除":
                showDeleteDialog(locID, "确定要删除该项收藏吗?",
                        R.string.favorite_delete_ok, R.string.favorite_delete_error);
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_add) {
            showAddFavoriteDialog();
            return true;
        }
        if (id == R.id.action_clear) {
            showClearAllDialog("确定要删除全部收藏吗?",
                    R.string.favorite_delete_ok, R.string.favorite_delete_error);
            return true;
        }

        return super.onOptionsItemSelected(item);
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
}
