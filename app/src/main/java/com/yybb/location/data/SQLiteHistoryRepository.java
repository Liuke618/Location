package com.yybb.location.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.elvishew.xlog.XLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HistoryRepository 的 SQLite 实现：包住既有 DataBaseHistoryLocation/DataBaseHistorySearch。
 * 读写语义与原有调用面逐字段一致：保存先删后插（去重覆盖）、ID > 0 过滤、timestamp 降序。
 * 写侧异常仍只记 "DATABASE: ..." 日志，但会把成败回传给调用方——界面据此决定是否显示
 * "已保存/删除成功"，避免存储层静默失败时对用户谎报；读侧失败返回 null（空表返回空列表），
 * 界面据此区分"没记录"与"读失败"。
 * 游标映射抽为纯函数由 SQLiteHistoryRepositoryMappingTest 锚定列序契约；SQLiteOpenHelper 本体
 * 不可 JVM 实例化，SQL 执行语义（先删后插去重/条件删除）由契约测试的 InMemory 替身覆盖。
 */
public class SQLiteHistoryRepository implements HistoryRepository {
    private final DataBaseHistoryLocation mLocationDBHelper;
    private final DataBaseHistorySearch mSearchDBHelper;
    private final DataBaseFavoriteLocation mFavoriteDBHelper;
    private SQLiteDatabase mLocationDB;
    private SQLiteDatabase mSearchDB;
    private SQLiteDatabase mFavoriteDB;

    public SQLiteHistoryRepository(Context context) {
        Context appContext = context.getApplicationContext();
        DataBaseHistoryLocation locationDBHelper = null;
        DataBaseHistorySearch searchDBHelper = null;
        DataBaseFavoriteLocation favoriteDBHelper = null;
        try {
            // 定位历史
            locationDBHelper = new DataBaseHistoryLocation(appContext);
            mLocationDB = locationDBHelper.getWritableDatabase();
            // 搜索历史
            searchDBHelper = new DataBaseHistorySearch(appContext);
            mSearchDB = searchDBHelper.getWritableDatabase();
            // 收藏
            favoriteDBHelper = new DataBaseFavoriteLocation(appContext);
            mFavoriteDB = favoriteDBHelper.getWritableDatabase();
        } catch (Exception e) {
            XLog.e("DATABASE: sqlite init error");
        }
        mLocationDBHelper = locationDBHelper;
        mSearchDBHelper = searchDBHelper;
        mFavoriteDBHelper = favoriteDBHelper;
    }

    /*============================== 搜索历史 ==============================*/

    @Override
    public List<Map<String, Object>> getSearchRows() {
        List<Map<String, Object>> data = new ArrayList<>();

        try (Cursor cursor = mSearchDB.query(DataBaseHistorySearch.TABLE_NAME, null,
                DataBaseHistorySearch.DB_COLUMN_ID + " > ?", new String[]{"0"},
                null, null, DataBaseHistorySearch.DB_COLUMN_TIMESTAMP + " DESC", null)) {

            while (cursor.moveToNext()) {
                data.add(searchRowFromCursor(cursor));
            }
        } catch (Exception e) {
            XLog.e("DATABASE: getSearchRows error");
            return null; // 存储读取失败与空表(空列表)对调用方可见地区分,由界面提示
        }

        return data;
    }

    /** 游标→搜索历史 Map 行(6 键旧行形:坐标只取 CUSTOM 双列,WGS84 双列不入 Map;int 列转字符串) */
    static Map<String, Object> searchRowFromCursor(Cursor cursor) {
        Map<String, Object> searchHistoryItem = new HashMap<>();
        searchHistoryItem.put(DB_COLUMN_KEY, cursor.getString(1));
        searchHistoryItem.put(DB_COLUMN_DESCRIPTION, cursor.getString(2));
        searchHistoryItem.put(DB_COLUMN_TIMESTAMP, "" + cursor.getInt(3));
        searchHistoryItem.put(DB_COLUMN_IS_LOCATION, "" + cursor.getInt(4));
        searchHistoryItem.put(DB_COLUMN_LONGITUDE_CUSTOM, cursor.getString(7));
        searchHistoryItem.put(DB_COLUMN_LATITUDE_CUSTOM, cursor.getString(8));
        return searchHistoryItem;
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

        try (Cursor cursor = mLocationDB.query(DataBaseHistoryLocation.TABLE_NAME, null,
                DataBaseHistoryLocation.DB_COLUMN_ID + " > ?", new String[]{"0"},
                null, null, DataBaseHistoryLocation.DB_COLUMN_TIMESTAMP + " DESC", null)) {

            while (cursor.moveToNext()) {
                data.add(locationRowFromCursor(cursor));
            }
        } catch (Exception e) {
            XLog.e("DATABASE: getLocationRows error");
            return null; // 存储读取失败与空表(空列表)对调用方可见地区分,由界面提示
        }

        return data;
    }

    /** 游标→定位/收藏行(自增 id + 6 字段,列序 0..6 与建表语句一致) */
    static LocationRow locationRowFromCursor(Cursor cursor) {
        return new LocationRow(cursor.getInt(0),
                cursor.getString(1),
                cursor.getString(2),
                cursor.getString(3),
                cursor.getLong(4),
                cursor.getString(5),
                cursor.getString(6));
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

    /*============================== 收藏 ==============================*/

    @Override
    public List<LocationRow> getFavoriteRows() {
        List<LocationRow> data = new ArrayList<>();

        try (Cursor cursor = mFavoriteDB.query(DataBaseFavoriteLocation.TABLE_NAME, null,
                DataBaseFavoriteLocation.DB_COLUMN_ID + " > ?", new String[]{"0"},
                null, null, DataBaseFavoriteLocation.DB_COLUMN_TIMESTAMP + " DESC", null)) {

            while (cursor.moveToNext()) {
                data.add(locationRowFromCursor(cursor));
            }
        } catch (Exception e) {
            XLog.e("DATABASE: getFavoriteRows error");
            return null; // 存储读取失败与空表(空列表)对调用方可见地区分,由界面提示
        }

        return data;
    }

    @Override
    public boolean addFavoriteRow(LocationRow row) {
        try {
            ContentValues contentValues = new ContentValues();
            contentValues.put(DataBaseFavoriteLocation.DB_COLUMN_LOCATION, row.location);
            contentValues.put(DataBaseFavoriteLocation.DB_COLUMN_LONGITUDE_WGS84, row.lngWgs);
            contentValues.put(DataBaseFavoriteLocation.DB_COLUMN_LATITUDE_WGS84, row.latWgs);
            contentValues.put(DataBaseFavoriteLocation.DB_COLUMN_TIMESTAMP, row.timestamp);
            contentValues.put(DataBaseFavoriteLocation.DB_COLUMN_LONGITUDE_CUSTOM, row.lngCustom);
            contentValues.put(DataBaseFavoriteLocation.DB_COLUMN_LATITUDE_CUSTOM, row.latCustom);

            return DataBaseFavoriteLocation.saveFavoriteLocation(mFavoriteDB, contentValues);
        } catch (Exception e) {
            XLog.e("DATABASE: insert error");
            return false;
        }
    }

    @Override
    public boolean deleteFavoriteRow(int id) {
        try {
            mFavoriteDB.delete(DataBaseFavoriteLocation.TABLE_NAME,
                    DataBaseFavoriteLocation.DB_COLUMN_ID + " = ?", new String[]{Integer.toString(id)});
            return true;
        } catch (Exception e) {
            XLog.e("DATABASE: delete error");
            return false;
        }
    }

    @Override
    public boolean renameFavoriteRow(int id, String newName) {
        try {
            return DataBaseFavoriteLocation.updateFavoriteLocation(mFavoriteDB, Integer.toString(id), newName);
        } catch (Exception e) {
            XLog.e("DATABASE: update error");
            return false;
        }
    }

    @Override
    public boolean clearAllFavoriteRows() {
        try {
            mFavoriteDB.delete(DataBaseFavoriteLocation.TABLE_NAME, null, null);
            return true;
        } catch (Exception e) {
            XLog.e("DATABASE: delete error");
            return false;
        }
    }

    @Override
    public LocationRow findFavoriteRowByWgsCoords(String lngWgs, String latWgs) {
        try (Cursor cursor = mFavoriteDB.query(DataBaseFavoriteLocation.TABLE_NAME, null,
                DataBaseFavoriteLocation.DB_COLUMN_LONGITUDE_WGS84 + " = ? AND " +
                        DataBaseFavoriteLocation.DB_COLUMN_LATITUDE_WGS84 + " = ?",
                new String[]{lngWgs, latWgs},
                null, null, null, null)) {

            if (cursor.moveToNext()) {
                return locationRowFromCursor(cursor);
            }
            return null;
        } catch (Exception e) {
            XLog.e("DATABASE: findFavoriteRowByWgsCoords error");
            return null;
        }
    }
}
