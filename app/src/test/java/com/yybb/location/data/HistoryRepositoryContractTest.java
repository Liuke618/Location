package com.yybb.location.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.yybb.location.data.HistoryRepository.LocationRow;
import com.yybb.location.data.HistoryRepository.SearchRow;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

/**
 * HistoryRepository 契约测试（对 InMemoryHistoryRepository）。
 * 契约复刻既有 SQLite 调用面语义：
 * - 搜索行：同 key 先删后插（去重覆盖），读取按 timestamp 降序，行形为 6 键 Map（无 ID 列）
 * - 定位行：同 WGS84 经纬度先删后插（去重覆盖），读取按 timestamp 降序，行含自增 id
 * - 读失败返回 null 的语义仅存在于 SQLite 实现（InMemory 永不失败，无法在此锚定）
 */
public class HistoryRepositoryContractTest {
    private InMemoryHistoryRepository mRepository;

    @Before
    public void setUp() {
        mRepository = new InMemoryHistoryRepository();
    }

    /*============================== 搜索历史行 ==============================*/

    @Test
    public void getSearchRows_emptyTable_returnsEmptyList() {
        assertTrue(mRepository.getSearchRows().isEmpty());
    }

    @Test
    public void addSearchRow_thenGetSearchRows_roundTripsAllFields() {
        SearchRow row = new SearchRow("天安门", "北京市东城区", 1,
                "116.397", "39.909", "116.407", "39.919", 1700000000L);
        mRepository.addSearchRow(row);

        List<Map<String, Object>> rows = mRepository.getSearchRows();
        assertEquals(1, rows.size());
        Map<String, Object> got = rows.get(0);
        assertEquals("天安门", got.get(HistoryRepository.DB_COLUMN_KEY));
        assertEquals("北京市东城区", got.get(HistoryRepository.DB_COLUMN_DESCRIPTION));
        assertEquals("1700000000", got.get(HistoryRepository.DB_COLUMN_TIMESTAMP));
        assertEquals("1", got.get(HistoryRepository.DB_COLUMN_IS_LOCATION));
        assertEquals("116.407", got.get(HistoryRepository.DB_COLUMN_LONGITUDE_CUSTOM));
        assertEquals("39.919", got.get(HistoryRepository.DB_COLUMN_LATITUDE_CUSTOM));
        assertEquals(6, got.size()); // 旧行形：6 键，无 ID
    }

    @Test
    public void addSearchRow_sameKey_overwritesExistingRow() {
        // saveHistorySearch 语义：同 key 先删后插
        mRepository.addSearchRow(new SearchRow("key1", "old", 0, null, null, null, null, 100L));
        mRepository.addSearchRow(new SearchRow("key1", "new", 0, null, null, null, null, 200L));

        List<Map<String, Object>> rows = mRepository.getSearchRows();
        assertEquals(1, rows.size());
        assertEquals("new", rows.get(0).get(HistoryRepository.DB_COLUMN_DESCRIPTION));
        assertEquals("200", rows.get(0).get(HistoryRepository.DB_COLUMN_TIMESTAMP));
    }

    @Test
    public void addSearchRow_distinctKeys_bothKept_newestFirst() {
        mRepository.addSearchRow(new SearchRow("k1", "d1", 0, null, null, null, null, 100L));
        mRepository.addSearchRow(new SearchRow("k2", "d2", 0, null, null, null, null, 200L));

        List<Map<String, Object>> rows = mRepository.getSearchRows();
        assertEquals(2, rows.size());
        assertEquals("k2", rows.get(0).get(HistoryRepository.DB_COLUMN_KEY)); // ORDER BY timestamp DESC
        assertEquals("k1", rows.get(1).get(HistoryRepository.DB_COLUMN_KEY));
    }

    @Test
    public void deleteSearchRow_removesRowWithKey() {
        mRepository.addSearchRow(new SearchRow("k1", "d1", 0, null, null, null, null, 100L));
        mRepository.addSearchRow(new SearchRow("k2", "d2", 0, null, null, null, null, 200L));

        mRepository.deleteSearchRow("k1");

        List<Map<String, Object>> rows = mRepository.getSearchRows();
        assertEquals(1, rows.size());
        assertEquals("k2", rows.get(0).get(HistoryRepository.DB_COLUMN_KEY));
    }

    /*============================== 定位历史行 ==============================*/

    @Test
    public void getLocationRows_emptyTable_returnsEmptyList() {
        assertTrue(mRepository.getLocationRows().isEmpty());
    }

    @Test
    public void addLocationRow_thenGetLocationRows_roundTripsAllFields_withAssignedId() {
        mRepository.addLocationRow(new LocationRow("位置A", "116.1", "39.1", 100L, "116.2", "39.2"));

        List<LocationRow> rows = mRepository.getLocationRows();
        assertEquals(1, rows.size());
        LocationRow got = rows.get(0);
        assertTrue(got.id > 0); // 自增分配，HistoryActivity 需要此 ID
        assertEquals("位置A", got.location);
        assertEquals("116.1", got.lngWgs);
        assertEquals("39.1", got.latWgs);
        assertEquals(100L, got.timestamp);
        assertEquals("116.2", got.lngCustom);
        assertEquals("39.2", got.latCustom);
    }

    @Test
    public void addLocationRow_sameWgsCoords_overwritesExistingRow() {
        // saveHistoryLocation 语义：同 WGS84 经纬度先删后插
        mRepository.addLocationRow(new LocationRow("旧名", "116.1", "39.1", 100L, "116.2", "39.2"));
        mRepository.addLocationRow(new LocationRow("新名", "116.1", "39.1", 200L, "116.2", "39.2"));

        List<LocationRow> rows = mRepository.getLocationRows();
        assertEquals(1, rows.size());
        assertEquals("新名", rows.get(0).location);
    }

    @Test
    public void getLocationRows_ordersByTimestampDesc() {
        mRepository.addLocationRow(new LocationRow("A", "1.0", "1.0", 100L, "1.0", "1.0"));
        mRepository.addLocationRow(new LocationRow("B", "2.0", "2.0", 300L, "2.0", "2.0"));
        mRepository.addLocationRow(new LocationRow("C", "3.0", "3.0", 200L, "3.0", "3.0"));

        List<LocationRow> rows = mRepository.getLocationRows();
        assertEquals("B", rows.get(0).location);
        assertEquals("C", rows.get(1).location);
        assertEquals("A", rows.get(2).location);
    }

    @Test
    public void clearExpiredLocationRows_removesOnlyRowsOlderThanCutoff() {
        mRepository.addLocationRow(new LocationRow("old", "1.0", "1.0", 100L, "1.0", "1.0"));
        mRepository.addLocationRow(new LocationRow("new", "2.0", "2.0", 200L, "2.0", "2.0"));

        mRepository.clearExpiredLocationRows(200L); // timestamp < cutoff 被删

        List<LocationRow> rows = mRepository.getLocationRows();
        assertEquals(1, rows.size());
        assertEquals("new", rows.get(0).location);
    }

    @Test
    public void clearAllLocationRows_removesEverything() {
        mRepository.addLocationRow(new LocationRow("A", "1.0", "1.0", 100L, "1.0", "1.0"));
        mRepository.addLocationRow(new LocationRow("B", "2.0", "2.0", 200L, "2.0", "2.0"));

        mRepository.clearAllLocationRows();

        assertTrue(mRepository.getLocationRows().isEmpty());
    }

    @Test
    public void deleteLocationRow_removesRowById() {
        mRepository.addLocationRow(new LocationRow("A", "1.0", "1.0", 100L, "1.0", "1.0"));
        mRepository.addLocationRow(new LocationRow("B", "2.0", "2.0", 200L, "2.0", "2.0"));
        int idB = mRepository.getLocationRows().get(0).id; // B 最新在前

        mRepository.deleteLocationRow(idB);

        List<LocationRow> rows = mRepository.getLocationRows();
        assertEquals(1, rows.size());
        assertEquals("A", rows.get(0).location);
    }

    @Test
    public void renameLocationRow_updatesLocationName() {
        mRepository.addLocationRow(new LocationRow("A", "1.0", "1.0", 100L, "1.0", "1.0"));
        int id = mRepository.getLocationRows().get(0).id;

        mRepository.renameLocationRow(id, "改名后");

        List<LocationRow> rows = mRepository.getLocationRows();
        assertEquals(1, rows.size());
        assertEquals("改名后", rows.get(0).location);
        assertEquals(100L, rows.get(0).timestamp); // 其余字段不变
    }

    @Test
    public void renameLocationRow_unknownId_reportsFailure() {
        mRepository.addLocationRow(new LocationRow("A", "1.0", "1.0", 100L, "1.0", "1.0"));

        // 目标行不存在 = 存储层没改动任何行，界面要能据此提示失败而不是谎报改名成功
        assertFalse(mRepository.renameLocationRow(9999, "不存在"));
    }

    /*============================== 收藏行 ==============================*/

    @Test
    public void getFavoriteRows_emptyTable_returnsEmptyList() {
        assertTrue(mRepository.getFavoriteRows().isEmpty());
    }

    @Test
    public void addFavoriteRow_thenGetFavoriteRows_roundTripsAllFields_withAssignedId() {
        mRepository.addFavoriteRow(new LocationRow("收藏点A", "116.1", "39.1", 100L, "116.2", "39.2"));

        List<LocationRow> rows = mRepository.getFavoriteRows();
        assertEquals(1, rows.size());
        LocationRow got = rows.get(0);
        assertTrue(got.id > 0); // 自增分配，FavoriteActivity 需要此 ID
        assertEquals("收藏点A", got.location);
        assertEquals("116.1", got.lngWgs);
        assertEquals("39.1", got.latWgs);
        assertEquals(100L, got.timestamp);
        assertEquals("116.2", got.lngCustom);
        assertEquals("39.2", got.latCustom);
    }

    @Test
    public void addFavoriteRow_sameWgsCoords_overwritesExistingRow() {
        // saveFavoriteLocation 语义：同 WGS84 经纬度先删后插
        mRepository.addFavoriteRow(new LocationRow("旧名", "116.1", "39.1", 100L, "116.2", "39.2"));
        mRepository.addFavoriteRow(new LocationRow("新名", "116.1", "39.1", 200L, "116.2", "39.2"));

        List<LocationRow> rows = mRepository.getFavoriteRows();
        assertEquals(1, rows.size());
        assertEquals("新名", rows.get(0).location);
    }

    @Test
    public void getFavoriteRows_ordersByTimestampDesc() {
        mRepository.addFavoriteRow(new LocationRow("A", "1.0", "1.0", 100L, "1.0", "1.0"));
        mRepository.addFavoriteRow(new LocationRow("B", "2.0", "2.0", 300L, "2.0", "2.0"));
        mRepository.addFavoriteRow(new LocationRow("C", "3.0", "3.0", 200L, "3.0", "3.0"));

        List<LocationRow> rows = mRepository.getFavoriteRows();
        assertEquals("B", rows.get(0).location);
        assertEquals("C", rows.get(1).location);
        assertEquals("A", rows.get(2).location);
    }

    @Test
    public void favoriteRows_areIndependentFromLocationRows() {
        // 收藏与定位历史互不干扰：清空历史不影响收藏，清空收藏不影响历史
        mRepository.addLocationRow(new LocationRow("历史", "1.0", "1.0", 100L, "1.0", "1.0"));
        mRepository.addFavoriteRow(new LocationRow("收藏", "1.0", "1.0", 100L, "1.0", "1.0"));

        mRepository.clearAllLocationRows();
        assertEquals(1, mRepository.getFavoriteRows().size());

        mRepository.clearAllFavoriteRows();
        assertTrue(mRepository.getLocationRows().isEmpty());
        assertTrue(mRepository.getFavoriteRows().isEmpty());
    }

    @Test
    public void deleteFavoriteRow_removesRowById() {
        mRepository.addFavoriteRow(new LocationRow("A", "1.0", "1.0", 100L, "1.0", "1.0"));
        mRepository.addFavoriteRow(new LocationRow("B", "2.0", "2.0", 200L, "2.0", "2.0"));
        int idB = mRepository.getFavoriteRows().get(0).id; // B 最新在前

        mRepository.deleteFavoriteRow(idB);

        List<LocationRow> rows = mRepository.getFavoriteRows();
        assertEquals(1, rows.size());
        assertEquals("A", rows.get(0).location);
    }

    @Test
    public void renameFavoriteRow_updatesFavoriteName() {
        mRepository.addFavoriteRow(new LocationRow("A", "1.0", "1.0", 100L, "1.0", "1.0"));
        int id = mRepository.getFavoriteRows().get(0).id;

        mRepository.renameFavoriteRow(id, "改名后");

        List<LocationRow> rows = mRepository.getFavoriteRows();
        assertEquals(1, rows.size());
        assertEquals("改名后", rows.get(0).location);
        assertEquals(100L, rows.get(0).timestamp); // 其余字段不变
    }

    @Test
    public void renameFavoriteRow_unknownId_reportsFailure() {
        mRepository.addFavoriteRow(new LocationRow("A", "1.0", "1.0", 100L, "1.0", "1.0"));

        // 目标行不存在 = 存储层没改动任何行，界面要能据此提示失败而不是谎报改名成功
        assertFalse(mRepository.renameFavoriteRow(9999, "不存在"));
    }

    @Test
    public void clearAllFavoriteRows_removesEverything() {
        mRepository.addFavoriteRow(new LocationRow("A", "1.0", "1.0", 100L, "1.0", "1.0"));
        mRepository.addFavoriteRow(new LocationRow("B", "2.0", "2.0", 200L, "2.0", "2.0"));

        mRepository.clearAllFavoriteRows();

        assertTrue(mRepository.getFavoriteRows().isEmpty());
    }

    @Test
    public void findFavoriteRowByWgsCoords_matchesExactRow() {
        // 默认收藏按 WGS84 坐标字符串精确匹配（与先删后插去重键同构）
        mRepository.addFavoriteRow(new LocationRow("A", "1.0", "1.0", 100L, "1.0", "1.0"));

        LocationRow got = mRepository.findFavoriteRowByWgsCoords("1.0", "1.0");
        assertNotNull(got);
        assertEquals("A", got.location);
        assertEquals("1.0", got.lngWgs);
        assertEquals("1.0", got.latWgs);
    }

    @Test
    public void findFavoriteRowByWgsCoords_noMatch_returnsNull() {
        mRepository.addFavoriteRow(new LocationRow("A", "1.0", "1.0", 100L, "1.0", "1.0"));

        assertNull(mRepository.findFavoriteRowByWgsCoords("2.0", "2.0"));
        assertNull(mRepository.findFavoriteRowByWgsCoords("1.0", "1.1"));
    }

    @Test
    public void findFavoriteRowByWgsCoords_emptyTable_returnsNull() {
        assertNull(mRepository.findFavoriteRowByWgsCoords("1.0", "1.0"));
    }

    /*============================== 写侧成败回传（界面提示的依据） ==============================*/

    @Test
    public void writeMethods_reportSuccess_whenStorageAcceptedThem() {
        assertTrue(mRepository.addSearchRow(new SearchRow("k1", "d1", 0, null, null, null, null, 100L)));
        assertTrue(mRepository.addLocationRow(new LocationRow("A", "1.0", "1.0", 100L, "1.0", "1.0")));
        assertTrue(mRepository.renameLocationRow(1, "改名后"));
        assertTrue(mRepository.clearExpiredLocationRows(50L));
        assertTrue(mRepository.deleteLocationRow(1));
        assertTrue(mRepository.clearAllLocationRows());
        assertTrue(mRepository.deleteSearchRow("k1"));
        assertTrue(mRepository.addFavoriteRow(new LocationRow("收藏", "1.0", "1.0", 100L, "1.0", "1.0")));
        assertTrue(mRepository.renameFavoriteRow(1, "改名后"));
        assertTrue(mRepository.deleteFavoriteRow(1));
        assertTrue(mRepository.clearAllFavoriteRows());
    }
}
