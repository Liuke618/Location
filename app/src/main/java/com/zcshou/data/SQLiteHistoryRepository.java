package com.zcshou.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.elvishew.xlog.XLog;
import com.zcshou.data.DataBaseHistoryLocation;
import com.zcshou.data.DataBaseHistorySearch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HistoryRepository 的 SQLite 实现：包住既有 DataBaseHistoryLocation/DataBaseHistorySearch。
 * 读写语义与原有调用面逐字段一致：保存先删后插（去重覆盖）、ID > 0 过滤、timestamp 降序。
 * 写侧异常仍只记 "DATABASE: ..." 日志，但会把成败回传给调用方——界面据此决定是否显示
 * "已保存/删除成功"，避免存储层静默失败时对用户谎报。
 */
public class SQLiteHistoryRepository implements HistoryRepository {
    private final DataBaseHistoryLocation mLocationDBHelper;
    private final DataBaseHistorySearch mSearchDBHelper;
    private SQLiteDatabase mLocationDB;
    private SQLiteDatabase mSearchDB;

    public SQLiteHistoryRepository(Context context) {
        Context appContext = context.getApplicationContext();
        DataBaseHistoryLocation locationDBHelper = null;
        DataBaseHistorySearch searchDBHelper = null;
        try {
            // 定位历史
            locationDBHelper = new DataBaseHistoryLocation(appContext);
            mLocationDB = locationDBHelper.getWritableDatabase();
            // 搜索历史
            searchDBHelper = new DataBaseHistorySearch(appContext);
            mSearchDB = searchDBHelper.getWritableDatabase();
        } catch (Exception e) {
            XLog.e("DATABASE: sqlite init error");
        }
        mLocationDBHelper = locationDBHelper;
        mSearchDBHelper = searchDBHelper;
    }

    /*============================== 搜索历史 ==============================*/

    @Override
    public List<Map<String, Object>> getSearchRows() {
        List<Map<String, Object>> data = new ArrayList<>();

        try {
            Cursor cursor = mSearchDB.query(DataBaseHistorySearch.TABLE_NAME, null,
                    DataBaseHistorySearch.DB_COLUMN_ID + " > ?", new String[]{"0"},
                    null, null, DataBaseHistorySearch.DB_COLUMN_TIMESTAMP + " DESC", null);

            while (cursor.moveToNext()) {
                Map<String, Object> searchHistoryItem = new HashMap<>();
                searchHistoryItem.put(DB_COLUMN_KEY, cursor.getString(1));
                searchHistoryItem.put(DB_COLUMN_DESCRIPTION, cursor.getString(2));
                searchHistoryItem.put(DB_COLUMN_TIMESTAMP, "" + cursor.getInt(3));
                searchHistoryItem.put(DB_COLUMN_IS_LOCATION, "" + cursor.getInt(4));
                searchHistoryItem.put(DB_COLUMN_LONGITUDE_CUSTOM, cursor.getString(7));
                searchHistoryItem.put(DB_COLUMN_LATITUDE_CUSTOM, cursor.getString(8));
                data.add(searchHistoryItem);
            }
            cursor.close();
        } catch (Exception e) {
            XLog.e("DATABASE: getSearchRows error");
        }

        return data;
    }

    @Override
    public boolean addSearchRow(SearchRow row) {
        try {
            ContentValues contentValues = new ContentValues();
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_KEY, row.key);
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_DESCRIPTION, row.description);
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_TIMESTAMP, row.timestamp);
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_IS_LOCATION, row.isLocation);
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_LONGITUDE_WGS84, row.lngWgs);
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_LATITUDE_WGS84, row.latWgs);
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_LONGITUDE_CUSTOM, row.lngCustom);
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_LATITUDE_CUSTOM, row.latCustom);

            return DataBaseHistorySearch.saveHistorySearch(mSearchDB, contentValues);
        } catch (Exception e) {
            XLog.e("DATABASE: insert error");
            return false;
        }
    }

    @Override
    public boolean deleteSearchRow(String key) {
        try {
            mSearchDB.delete(DataBaseHistorySearch.TABLE_NAME,
                    DataBaseHistorySearch.DB_COLUMN_KEY + " = ?", new String[]{key});
            return true;
        } catch (Exception e) {
            XLog.e("DATABASE: delete error");
            return false;
        }
    }

    /*============================== 定位历史 ==============================*/

    @Override
    public List<LocationRow> getLocationRows() {
        List<LocationRow> data = new ArrayList<>();

        try {
            Cursor cursor = mLocationDB.query(DataBaseHistoryLocation.TABLE_NAME, null,
                    DataBaseHistoryLocation.DB_COLUMN_ID + " > ?", new String[]{"0"},
                    null, null, DataBaseHistoryLocation.DB_COLUMN_TIMESTAMP + " DESC", null);

            while (cursor.moveToNext()) {
                data.add(new LocationRow(cursor.getInt(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getString(3),
                        cursor.getLong(4),
                        cursor.getString(5),
                        cursor.getString(6)));
            }
            cursor.close();
        } catch (Exception e) {
            XLog.e("DATABASE: getLocationRows error");
        }

        return data;
    }

    @Override
    public boolean addLocationRow(LocationRow row) {
        try {
            ContentValues contentValues = new ContentValues();
            contentValues.put(DataBaseHistoryLocation.DB_COLUMN_LOCATION, row.location);
            contentValues.put(DataBaseHistoryLocation.DB_COLUMN_LONGITUDE_WGS84, row.lngWgs);
            contentValues.put(DataBaseHistoryLocation.DB_COLUMN_LATITUDE_WGS84, row.latWgs);
            contentValues.put(DataBaseHistoryLocation.DB_COLUMN_TIMESTAMP, row.timestamp);
            contentValues.put(DataBaseHistoryLocation.DB_COLUMN_LONGITUDE_CUSTOM, row.lngCustom);
            contentValues.put(DataBaseHistoryLocation.DB_COLUMN_LATITUDE_CUSTOM, row.latCustom);

            return DataBaseHistoryLocation.saveHistoryLocation(mLocationDB, contentValues);
        } catch (Exception e) {
            XLog.e("DATABASE: insert error");
            return false;
        }
    }

    @Override
    public boolean clearExpiredLocationRows(long cutoffEpochSeconds) {
        try {
            mLocationDB.delete(DataBaseHistoryLocation.TABLE_NAME,
                    DataBaseHistoryLocation.DB_COLUMN_TIMESTAMP + " < ?",
                    new String[]{Long.toString(cutoffEpochSeconds)});
            return true;
        } catch (Exception e) {
            XLog.e("DATABASE: delete error");
            return false;
        }
    }

    @Override
    public boolean clearAllLocationRows() {
        try {
            mLocationDB.delete(DataBaseHistoryLocation.TABLE_NAME, null, null);
            return true;
        } catch (Exception e) {
            XLog.e("DATABASE: delete error");
            return false;
        }
    }

    @Override
    public boolean deleteLocationRow(int id) {
        try {
            mLocationDB.delete(DataBaseHistoryLocation.TABLE_NAME,
                    DataBaseHistoryLocation.DB_COLUMN_ID + " = ?", new String[]{Integer.toString(id)});
            return true;
        } catch (Exception e) {
            XLog.e("DATABASE: delete error");
            return false;
        }
    }

    @Override
    public boolean renameLocationRow(int id, String newName) {
        try {
            return DataBaseHistoryLocation.updateHistoryLocation(mLocationDB, Integer.toString(id), newName);
        } catch (Exception e) {
            XLog.e("DATABASE: update error");
            return false;
        }
    }
}
