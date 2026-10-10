package com.yybb.location.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.yybb.location.utils.MapUtils;

import androidx.annotation.Nullable;

import com.elvishew.xlog.XLog;

/**
 * 数据库版本迁移工具(两库共享)。
 * V1→V2:CUSTOM 坐标列由 BD09 迁为 GCJ02(配合高德地图切换);WGS84 列不动。
 * 迁移正确性由 DbMigrationsTest 与 MapUtilsTest.bd09togcj02 锚定共同覆盖;
 * SQLiteOpenHelper 本体不可 JVM 实例化,DB 循环保持薄、不做分支扩展。
 */
public final class DbMigrations {

    private DbMigrations() {
    }

    /** 纯函数:V1 的 BD09 坐标字符串→GCJ02 坐标字符串;解析失败返回 null(调用方保留原值) */
    @Nullable
    public static String[] bd09ToGcj02(@Nullable String lngBd09, @Nullable String latBd09) {
        try {
            double[] gcj = MapUtils.bd09togcj02(Double.parseDouble(lngBd09), Double.parseDouble(latBd09));
            return new String[] { String.valueOf(gcj[0]), String.valueOf(gcj[1]) };
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }

    /** V1→V2:表内 CUSTOM(BD09) 坐标列整体迁为 GCJ02;坏行保留原值并记日志(行级隔离) */
    public static void migrateBd09CustomToGcj02(SQLiteDatabase db, String table, String lngColumn, String latColumn) {
        int migrated = 0;
        int failedRows = 0;
        try (Cursor cursor = db.rawQuery("SELECT rowid, " + lngColumn + ", " + latColumn + " FROM " + table, null)) {
            ContentValues values = new ContentValues();
            while (cursor.moveToNext()) {
                String[] gcj = bd09ToGcj02(cursor.getString(1), cursor.getString(2));
                if (gcj == null) {
                    XLog.e("DATABASE: bd09->gcj02 skip invalid row " + cursor.getLong(0));
                    failedRows++;
                    continue;
                }
                values.put(lngColumn, gcj[0]);
                values.put(latColumn, gcj[1]);
                db.update(table, values, "rowid = ?", new String[] { String.valueOf(cursor.getLong(0)) });
                migrated++;
            }
        } catch (Exception e) {
            XLog.e("DATABASE: bd09->gcj02 migration failed, migrated=" + migrated + ", failedRows=" + failedRows, e);
        }
    }
}
