package com.yybb.location.ui.history;

import android.view.Menu;
import android.view.MenuItem;

import java.util.List;

import com.elvishew.xlog.XLog;
import com.yybb.location.data.HistoryRepository.LocationRow;
import com.yybb.location.GoApplication;
import com.yybb.location.R;
import com.yybb.location.ui.BaseRecordListActivity;
import com.yybb.location.utils.GoUtils;

/**
 * 历史记录页：经 HistoryRepository 展示定位/搜索历史列表，支持单条/整表删除与过期记录清理。
 * 搜索过滤/空态/删除改名对话框/点击行回填等列表交互共用 BaseRecordListActivity。
 */
public class HistoryActivity extends BaseRecordListActivity {

    @Override
    protected int getLayoutRes() {
        return R.layout.activity_history;
    }

    @Override
    protected int getMenuRes() {
        return R.menu.menu_history;
    }

    @Override
    protected String getLogTag() {
        return "HISTORY";
    }

    @Override
    protected void initRepository() {
        try {
            mHistoryRepository = ((GoApplication) getApplicationContext()).getAppContainer().getHistoryRepository();
        } catch (Exception e) {
            XLog.e("HISTORY: ERROR - initRepository");
        }

        recordArchive();
    }

    @Override
    protected List<LocationRow> loadRows() {
        return mHistoryRepository.getLocationRows();
    }

    @Override
    protected boolean deleteRecord(int ID) {
        try {
            if (ID <= -1) {
                return mHistoryRepository.clearAllLocationRows();
            }
            return mHistoryRepository.deleteLocationRow(ID);
        } catch (Exception e) {
            XLog.e("HISTORY: ERROR - deleteRecord");
            return false;
        }
    }

    @Override
    protected boolean renameRecord(int ID, String newName) {
        try {
            return mHistoryRepository.renameLocationRow(ID, newName);
        } catch (Exception e) {
            XLog.e("HISTORY: ERROR - renameRecord");
            return false;
        }
    }

    /* 长按菜单项 id（菜单每次新建，id 仅在本页内区分动作，点击分发不依赖显示文案） */
    private static final int MENU_ID_EDIT = 1;
    private static final int MENU_ID_FAVORITE = 2;
    private static final int MENU_ID_DELETE = 3;

    @Override
    protected void onCreateLongClickMenu(Menu menu, String locID) {
        menu.add(Menu.NONE, MENU_ID_EDIT, Menu.NONE, getResources().getString(R.string.record_menu_edit));
        menu.add(Menu.NONE, MENU_ID_FAVORITE, Menu.NONE, getResources().getString(R.string.history_menu_favorite));
        menu.add(Menu.NONE, MENU_ID_DELETE, Menu.NONE, getResources().getString(R.string.record_menu_delete));
    }

    @Override
    protected boolean onLongClickMenuItem(int itemId, String locID, String name) {
        if (itemId == MENU_ID_EDIT) {
            showInputDialog(locID, name, R.string.history_rename_error);
            return true;
        }
        if (itemId == MENU_ID_FAVORITE) {
            favoriteRecord(Integer.parseInt(locID));
            return true;
        }
        if (itemId == MENU_ID_DELETE) {
            showDeleteDialog(locID, getResources().getString(R.string.history_delete_one_confirm),
                    R.string.history_delete_ok, R.string.history_delete_error);
            return true;
        }
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_delete) {
            showClearAllDialog(getResources().getString(R.string.history_delete_all_confirm),
                    R.string.history_delete_ok, R.string.history_delete_error);
            return true;
        }

        return super.onOptionsItemSelected(item);
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
                XLog.e("HISTORY: ERROR - recordArchive(expired rows not cleared)");
            }
        } catch (Exception e) {
            XLog.e("HISTORY: ERROR - recordArchive");
        }
    }

    // 收藏既有历史行：按 id 取原始行，字段原样复制插入收藏表(收藏是复制不是移动，历史行保留)
    private void favoriteRecord(int ID) {
        try {
            List<LocationRow> rows = mHistoryRepository.getLocationRows();
            if (rows == null) {
                // 存储读取失败按既有失败提示收口,与"行不存在"同文案
                GoUtils.DisplayToast(this, getResources().getString(R.string.app_location_mark_failed));
                return;
            }
            for (LocationRow row : rows) {
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
            XLog.e("HISTORY: ERROR - favoriteRecord");
            GoUtils.DisplayToast(this, getResources().getString(R.string.app_location_mark_failed));
        }
    }
}
