package com.yybb.location.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.yybb.location.data.HistoryRepository.LocationRow;

import org.junit.Test;

import java.util.Map;

/**
 * SQLiteHistoryRepository 游标映射纯函数的 JVM 单测（模式同 DbMigrationsTest：
 * SQLiteOpenHelper 本体不可 JVM 实例化，把纯函数抽出来锚定，DB 循环保持薄）。
 * 锚定列序契约：定位/收藏行 = 自增 id + 6 字段（0..6）；搜索行 = 6 键 Map
 * （只读 1/2/3/4/7/8 列，WGS84 双列 5/6 不入 Map），int 列转字符串。
 */
public class SQLiteHistoryRepositoryMappingTest {

    /* 建表列序（DataBaseHistoryLocation / DataBaseFavoriteLocation 两表同构） */
    private static final String[] LOCATION_COLUMNS = {
            DataBaseHistoryLocation.DB_COLUMN_ID,
            DataBaseHistoryLocation.DB_COLUMN_LOCATION,
            DataBaseHistoryLocation.DB_COLUMN_LONGITUDE_WGS84,
            DataBaseHistoryLocation.DB_COLUMN_LATITUDE_WGS84,
            DataBaseHistoryLocation.DB_COLUMN_TIMESTAMP,
            DataBaseHistoryLocation.DB_COLUMN_LONGITUDE_CUSTOM,
            DataBaseHistoryLocation.DB_COLUMN_LATITUDE_CUSTOM,
    };

    /* 搜索表列序：ID + 4 业务列 + WGS84 双坐标 + CUSTOM 双坐标 */
    private static final String[] SEARCH_COLUMNS = {
            DataBaseHistorySearch.DB_COLUMN_ID,
            DataBaseHistorySearch.DB_COLUMN_KEY,
            DataBaseHistorySearch.DB_COLUMN_DESCRIPTION,
            DataBaseHistorySearch.DB_COLUMN_TIMESTAMP,
            DataBaseHistorySearch.DB_COLUMN_IS_LOCATION,
            DataBaseHistorySearch.DB_COLUMN_LONGITUDE_WGS84,
            DataBaseHistorySearch.DB_COLUMN_LATITUDE_WGS84,
            DataBaseHistorySearch.DB_COLUMN_LONGITUDE_CUSTOM,
            DataBaseHistorySearch.DB_COLUMN_LATITUDE_CUSTOM,
    };

    @Test
    public void locationRowFromCursor_mapsAllSevenColumnsInTableOrder() {
        FakeCursor cursor = new FakeCursor(LOCATION_COLUMNS, new Object[][]{
                {42, "天安门", "116.397", "39.909", 1700000000L, "116.407", "39.919"}});
        cursor.moveToFirst();

        LocationRow row = SQLiteHistoryRepository.locationRowFromCursor(cursor);

        assertEquals(42, row.id);
        assertEquals("天安门", row.location);
        assertEquals("116.397", row.lngWgs);
        assertEquals("39.909", row.latWgs);
        assertEquals(1700000000L, row.timestamp);
        assertEquals("116.407", row.lngCustom);
        assertEquals("39.919", row.latCustom);
    }

    @Test
    public void locationRowFromCursor_worksRowByRowAcrossTwoRows() {
        FakeCursor cursor = new FakeCursor(LOCATION_COLUMNS, new Object[][]{
                {1, "旧行", "1.0", "1.0", 100L, "1.0", "1.0"},
                {2, "新行", "2.0", "2.0", 200L, "2.0", "2.0"}});

        cursor.moveToNext();
        LocationRow first = SQLiteHistoryRepository.locationRowFromCursor(cursor);
        cursor.moveToNext();
        LocationRow second = SQLiteHistoryRepository.locationRowFromCursor(cursor);

        assertEquals("旧行", first.location);
        assertEquals(100L, first.timestamp);
        assertEquals(2, second.id);
        assertEquals("新行", second.location);
        assertEquals(200L, second.timestamp);
    }

    @Test
    public void searchRowFromCursor_mapsSixKeyRowShape_excludingWgsColumns() {
        FakeCursor cursor = new FakeCursor(SEARCH_COLUMNS, new Object[][]{
                {7, "天安门", "北京市东城区", 1700000000, 1,
                        "116.397", "39.909", "116.407", "39.919"}});
        cursor.moveToFirst();

        Map<String, Object> row = SQLiteHistoryRepository.searchRowFromCursor(cursor);

        assertEquals("天安门", row.get(HistoryRepository.DB_COLUMN_KEY));
        assertEquals("北京市东城区", row.get(HistoryRepository.DB_COLUMN_DESCRIPTION));
        assertEquals("1700000000", row.get(HistoryRepository.DB_COLUMN_TIMESTAMP)); // int 列转字符串
        assertEquals("1", row.get(HistoryRepository.DB_COLUMN_IS_LOCATION));
        assertEquals("116.407", row.get(HistoryRepository.DB_COLUMN_LONGITUDE_CUSTOM)); // 只取 CUSTOM 双列
        assertEquals("39.919", row.get(HistoryRepository.DB_COLUMN_LATITUDE_CUSTOM));
        assertEquals(6, row.size()); // 旧行形：6 键，无 ID，无 WGS84
        assertNull(row.get(DataBaseHistorySearch.DB_COLUMN_LONGITUDE_WGS84));
        assertNull(row.get(DataBaseHistorySearch.DB_COLUMN_LATITUDE_WGS84));
    }

    @Test
    public void searchRowFromCursor_nullColumns_stayNullNotStringified() {
        // 关键字行的坐标列可为 NULL：Map 值须保持 null，而不是被转成 "null" 字符串
        FakeCursor cursor = new FakeCursor(SEARCH_COLUMNS, new Object[][]{
                {1, "搜索词", null, 100, 0, null, null, null, null}});
        cursor.moveToFirst();

        Map<String, Object> row = SQLiteHistoryRepository.searchRowFromCursor(cursor);

        assertEquals("搜索词", row.get(HistoryRepository.DB_COLUMN_KEY));
        assertNull(row.get(HistoryRepository.DB_COLUMN_DESCRIPTION));
        assertNull(row.get(HistoryRepository.DB_COLUMN_LONGITUDE_CUSTOM));
        assertNull(row.get(HistoryRepository.DB_COLUMN_LATITUDE_CUSTOM));
    }
}
