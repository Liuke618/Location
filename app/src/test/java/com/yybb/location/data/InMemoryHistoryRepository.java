package com.yybb.location.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * HistoryRepository 的内存实现：供契约测试与后续 ViewModel 测试复用。
 * 复刻 SQLite 实现语义：
 * - 搜索行同 key 先删后插（去重覆盖，重插视为最新）
 * - 定位行同 WGS84 经纬度先删后插（去重覆盖），id 自增分配
 * - 读取按 timestamp 降序（稳定排序，同时间戳保持保存序）
 */
public class InMemoryHistoryRepository implements HistoryRepository {
    private final Map<String, SearchRow> mSearchRowsByKey = new LinkedHashMap<>();
    private final List<LocationRow> mLocationRows = new ArrayList<>();
    private int mNextLocationId = 1;

    @Override
    public List<Map<String, Object>> getSearchRows() {
        List<SearchRow> rows = new ArrayList<>(mSearchRowsByKey.values());
        rows.sort((a, b) -> Long.compare(b.timestamp, a.timestamp)); // ORDER BY timestamp DESC

        List<Map<String, Object>> data = new ArrayList<>();
        for (SearchRow row : rows) {
            Map<String, Object> item = new HashMap<>();
            item.put(DB_COLUMN_KEY, row.key);
            item.put(DB_COLUMN_DESCRIPTION, row.description);
            item.put(DB_COLUMN_TIMESTAMP, "" + row.timestamp);
            item.put(DB_COLUMN_IS_LOCATION, "" + row.isLocation);
            item.put(DB_COLUMN_LONGITUDE_CUSTOM, row.lngCustom);
            item.put(DB_COLUMN_LATITUDE_CUSTOM, row.latCustom);
            data.add(item);
        }
        return data;
    }

    @Override
    public boolean addSearchRow(SearchRow row) {
        mSearchRowsByKey.remove(row.key); // 同 key 先删后插
        mSearchRowsByKey.put(row.key, row);
        return true;
    }

    @Override
    public boolean deleteSearchRow(String key) {
        mSearchRowsByKey.remove(key);
        return true;
    }

    @Override
    public List<LocationRow> getLocationRows() {
        List<LocationRow> rows = new ArrayList<>();
        for (LocationRow row : mLocationRows) {
            rows.add(row.copy());
        }
        rows.sort((a, b) -> Long.compare(b.timestamp, a.timestamp)); // ORDER BY timestamp DESC
        return rows;
    }

    @Override
    public boolean addLocationRow(LocationRow row) {
        mLocationRows.removeIf(r -> Objects.equals(r.lngWgs, row.lngWgs)
                && Objects.equals(r.latWgs, row.latWgs)); // 同 WGS84 经纬度先删后插
        mLocationRows.add(new LocationRow(mNextLocationId++, row.location, row.lngWgs,
                row.latWgs, row.timestamp, row.lngCustom, row.latCustom));
        return true;
    }

    @Override
    public boolean clearExpiredLocationRows(long cutoffEpochSeconds) {
        mLocationRows.removeIf(r -> r.timestamp < cutoffEpochSeconds);
        return true;
    }

    @Override
    public boolean clearAllLocationRows() {
        mLocationRows.clear();
        return true;
    }

    @Override
    public boolean deleteLocationRow(int id) {
        mLocationRows.removeIf(r -> r.id == id);
        return true;
    }

    @Override
    public boolean renameLocationRow(int id, String newName) {
        for (int i = 0; i < mLocationRows.size(); i++) {
            LocationRow row = mLocationRows.get(i);
            if (row.id == id) {
                mLocationRows.set(i, new LocationRow(row.id, newName, row.lngWgs,
                        row.latWgs, row.timestamp, row.lngCustom, row.latCustom));
                return true;
            }
        }
        // 与 SQLite 实现的 update 影响行数语义一致：目标行不存在即失败
        return false;
    }
}
