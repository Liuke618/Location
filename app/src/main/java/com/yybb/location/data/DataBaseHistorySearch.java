package com.yybb.location.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.elvishew.xlog.XLog;

/**
 * 搜索历史表 HistorySearch 的 SQLiteOpenHelper：记录搜索关键字与搜索结果两类条目（IS_LOCATION 区分），含双坐标列。
 * 上层一律经 HistoryRepository 访问，不直接触碰本类。
 */
public class DataBaseHistorySearch extends SQLiteOpenHelper {
    public static final String TABLE_NAME = "HistorySearch";
    public static final String DB_COLUMN_ID = "DB_COLUMN_ID";
    public static final String DB_COLUMN_KEY = "DB_COLUMN_KEY";
    public static final String DB_COLUMN_DESCRIPTION = "DB_COLUMN_DESCRIPTION";
    public static final String DB_COLUMN_TIMESTAMP = "DB_COLUMN_TIMESTAMP";
    public static final String DB_COLUMN_IS_LOCATION = "DB_COLUMN_IS_LOCATION";
    public static final String DB_COLUMN_LONGITUDE_WGS84 = "DB_COLUMN_LONGITUDE_WGS84";
    public static final String DB_COLUMN_LATITUDE_WGS84 = "DB_COLUMN_LATITUDE_WGS84";
    public static final String DB_COLUMN_LONGITUDE_CUSTOM = "DB_COLUMN_LONGITUDE_CUSTOM";
    public static final String DB_COLUMN_LATITUDE_CUSTOM = "DB_COLUMN_LATITUDE_CUSTOM";
    // 搜索的关键字
    public static final int DB_SEARCH_TYPE_KEY = 0;
    // 搜索结果
    public static final int DB_SEARCH_TYPE_RESULT = 1;

    private static final int DB_VERSION = 2;
    private static final String DB_NAME = "HistorySearch.db";
    private static final String CREATE_TABLE = "create table if not exists " + TABLE_NAME +
            " (DB_COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, DB_COLUMN_KEY TEXT NOT NULL, " +
            "DB_COLUMN_DESCRIPTION TEXT, DB_COLUMN_TIMESTAMP BIGINT NOT NULL, DB_COLUMN_IS_LOCATION INTEGER NOT NULL, " +
            "DB_COLUMN_LONGITUDE_WGS84 TEXT, DB_COLUMN_LATITUDE_WGS84 TEXT, " +
            "DB_COLUMN_LONGITUDE_CUSTOM TEXT, DB_COLUMN_LATITUDE_CUSTOM TEXT)";
            
    public DataBaseHistorySearch(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }
    
    @Override
    public void onCreate(SQLiteDatabase sqLiteDatabase) {
        sqLiteDatabase.execSQL(CREATE_TABLE);
    }
    
    @Override
    public void onUpgrade(SQLiteDatabase sqLiteDatabase, int oldVersion, int newVersion) {
        // V1→V2:CUSTOM 列坐标系 BD09→GCJ02(数据保留;原"DROP 重建"语义废弃)
        if (oldVersion < 2) {
            DbMigrations.migrateBd09CustomToGcj02(sqLiteDatabase, TABLE_NAME,
                    DB_COLUMN_LONGITUDE_CUSTOM, DB_COLUMN_LATITUDE_CUSTOM);
        }
    }

    public static boolean saveHistorySearch(SQLiteDatabase sqLiteDatabase, ContentValues contentValues) {
        try {
            // 先删除原来的记录，再插入新记录
            String searchKey = contentValues.get(DataBaseHistorySearch.DB_COLUMN_KEY).toString();
            sqLiteDatabase.delete(DataBaseHistorySearch.TABLE_NAME, DataBaseHistorySearch.DB_COLUMN_KEY + " = ?", new String[] {searchKey});
            return sqLiteDatabase.insert(DataBaseHistorySearch.TABLE_NAME, null, contentValues) != -1;
        } catch (Exception e) {
            XLog.e("DATABASE: insert error");
            return false;
        }
    }
}
