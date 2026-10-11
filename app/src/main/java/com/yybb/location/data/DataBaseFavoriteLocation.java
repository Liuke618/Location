package com.yybb.location.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.elvishew.xlog.XLog;

/**
 * 收藏表 FavoriteLocation 的 SQLiteOpenHelper：负责建表（当前 v1，WGS84/GCJ02 双坐标列，收藏不参与过期清理）。
 * 上层一律经 HistoryRepository 访问，不直接触碰本类。
 */
public class DataBaseFavoriteLocation extends SQLiteOpenHelper {
    public static final String TABLE_NAME = "FavoriteLocation";
    public static final String DB_COLUMN_ID = "DB_COLUMN_ID";
    public static final String DB_COLUMN_LOCATION = "DB_COLUMN_LOCATION";
    public static final String DB_COLUMN_LONGITUDE_WGS84 = "DB_COLUMN_LONGITUDE_WGS84";
    public static final String DB_COLUMN_LATITUDE_WGS84 = "DB_COLUMN_LATITUDE_WGS84";
    public static final String DB_COLUMN_TIMESTAMP = "DB_COLUMN_TIMESTAMP";
    public static final String DB_COLUMN_LONGITUDE_CUSTOM = "DB_COLUMN_LONGITUDE_CUSTOM";
    public static final String DB_COLUMN_LATITUDE_CUSTOM = "DB_COLUMN_LATITUDE_CUSTOM";

    private static final int DB_VERSION = 1;
    private static final String DB_NAME = "FavoriteLocation.db";
    private static final String CREATE_TABLE = "create table if not exists " + TABLE_NAME +
            " (DB_COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, DB_COLUMN_LOCATION TEXT, " +
            "DB_COLUMN_LONGITUDE_WGS84 TEXT NOT NULL, DB_COLUMN_LATITUDE_WGS84 TEXT NOT NULL, " +
            "DB_COLUMN_TIMESTAMP BIGINT NOT NULL, DB_COLUMN_LONGITUDE_CUSTOM TEXT NOT NULL, DB_COLUMN_LATITUDE_CUSTOM TEXT NOT NULL)";

    public DataBaseFavoriteLocation(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase sqLiteDatabase) {
        sqLiteDatabase.execSQL(CREATE_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase sqLiteDatabase, int oldVersion, int newVersion) {
        // 当前 v1，暂无升级路径；后续版本在此按 oldVersion 逐级迁移（参照 DataBaseHistoryLocation）
    }

    // 保存收藏的位置（同 WGS84 经纬度先删后插，重复收藏同一点视为更新名称）
    public static boolean saveFavoriteLocation(SQLiteDatabase sqLiteDatabase, ContentValues contentValues) {
        try {
            String longitudeWgs84 = contentValues.getAsString(DB_COLUMN_LONGITUDE_WGS84);
            String latitudeWgs84 = contentValues.getAsString(DB_COLUMN_LATITUDE_WGS84);
            sqLiteDatabase.delete(TABLE_NAME,
                            DB_COLUMN_LONGITUDE_WGS84 + " = ? AND " +
                            DB_COLUMN_LATITUDE_WGS84 + " = ?",
                    new String[] {longitudeWgs84, latitudeWgs84});
            return sqLiteDatabase.insert(TABLE_NAME, null, contentValues) != -1;
        } catch (Exception e) {
            XLog.e("DATABASE: insert error");
            return false;
        }
    }

    // 修改收藏名称
    public static boolean updateFavoriteLocation(SQLiteDatabase sqLiteDatabase, String favID, String location) {
        try {
            ContentValues contentValues = new ContentValues();
            contentValues.put(DB_COLUMN_LOCATION, location);
            return sqLiteDatabase.update(TABLE_NAME, contentValues, DB_COLUMN_ID + " = ?", new String[]{favID}) > 0;
        } catch (Exception e) {
            XLog.e("DATABASE: update error");
            return false;
        }
    }
}
