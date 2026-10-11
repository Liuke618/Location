package com.yybb.location.data;

import java.util.List;
import java.util.Map;

/**
 * 历史记录数据仓库：统一搜索历史与定位历史的读写入口。
 * 读行保持既有 SimpleAdapter 键契约（键名沿用 DataBaseHistorySearch.DB_COLUMN_*），
 * 行形与值形态与原有游标读取逐字段一致。
 */
public interface HistoryRepository {
    /* 搜索历史 Map 行键名（与 DataBaseHistorySearch.DB_COLUMN_* 一致，供 SimpleAdapter 使用） */
    String DB_COLUMN_KEY = DataBaseHistorySearch.DB_COLUMN_KEY;
    String DB_COLUMN_DESCRIPTION = DataBaseHistorySearch.DB_COLUMN_DESCRIPTION;
    String DB_COLUMN_TIMESTAMP = DataBaseHistorySearch.DB_COLUMN_TIMESTAMP;
    String DB_COLUMN_IS_LOCATION = DataBaseHistorySearch.DB_COLUMN_IS_LOCATION;
    String DB_COLUMN_LONGITUDE_CUSTOM = DataBaseHistorySearch.DB_COLUMN_LONGITUDE_CUSTOM;
    String DB_COLUMN_LATITUDE_CUSTOM = DataBaseHistorySearch.DB_COLUMN_LATITUDE_CUSTOM;

    /* 搜索记录类型（与 DataBaseHistorySearch.DB_SEARCH_TYPE_* 一致） */
    int DB_SEARCH_TYPE_KEY = DataBaseHistorySearch.DB_SEARCH_TYPE_KEY;
    int DB_SEARCH_TYPE_RESULT = DataBaseHistorySearch.DB_SEARCH_TYPE_RESULT;

    /*============================== 搜索历史 ==============================*/

    /** 按时间降序返回搜索历史行（行形与原 getSearchHistory 游标读取一致） */
    List<Map<String, Object>> getSearchRows();

    /** 保存搜索历史行（同 key 先删后插，去重覆盖语义） */
    boolean addSearchRow(SearchRow row);

    /** 删除指定 key 的搜索历史行 */
    boolean deleteSearchRow(String key);

    /*============================== 定位历史 ==============================*/

    /** 按时间降序返回定位历史原始行（含自增 id 与全部 6 个字段；展示格式化由调用方完成） */
    List<LocationRow> getLocationRows();

    /** 保存定位历史行（同 WGS84 经纬度先删后插，去重覆盖语义；id 由存储层自增分配） */
    boolean addLocationRow(LocationRow row);

    /** 删除时间早于 cutoffEpochSeconds 的定位历史行 */
    boolean clearExpiredLocationRows(long cutoffEpochSeconds);

    /** 删除全部定位历史行 */
    boolean clearAllLocationRows();

    /** 删除指定 id 的定位历史行 */
    boolean deleteLocationRow(int id);

    /** 修改指定 id 的定位历史行名称 */
    boolean renameLocationRow(int id, String newName);

    /*============================== 收藏 ==============================*/

    /** 按时间降序返回收藏行（行形与定位历史一致，含自增 id；收藏不参与过期清理） */
    List<LocationRow> getFavoriteRows();

    /** 保存收藏行（同 WGS84 经纬度先删后插，去重覆盖语义；id 由存储层自增分配） */
    boolean addFavoriteRow(LocationRow row);

    /** 删除指定 id 的收藏行 */
    boolean deleteFavoriteRow(int id);

    /** 修改指定 id 的收藏行名称（目标行不存在回传 false） */
    boolean renameFavoriteRow(int id, String newName);

    /** 删除全部收藏行 */
    boolean clearAllFavoriteRows();

    /** 按 WGS84 坐标精确匹配收藏行（字符串相等，与先删后插去重键同构；无命中返回 null） */
    LocationRow findFavoriteRowByWgsCoords(String lngWgs, String latWgs);

    /** 搜索历史行值对象（字段与 DataBaseHistorySearch 表列一一对应） */
    class SearchRow {
        public final String key;
        public final String description;
        public final int isLocation;
        public final String lngWgs;
        public final String latWgs;
        public final String lngCustom;
        public final String latCustom;
        public final long timestamp;

        public SearchRow(String key, String description, int isLocation,
                         String lngWgs, String latWgs, String lngCustom, String latCustom,
                         long timestamp) {
            this.key = key;
            this.description = description;
            this.isLocation = isLocation;
            this.lngWgs = lngWgs;
            this.latWgs = latWgs;
            this.lngCustom = lngCustom;
            this.latCustom = latCustom;
            this.timestamp = timestamp;
        }
    }

    /** 定位历史行值对象（字段与 DataBaseHistoryLocation 表列一一对应；id 为 0 表示待存储层分配） */
    class LocationRow {
        public int id;
        public final String location;
        public final String lngWgs;
        public final String latWgs;
        public final long timestamp;
        public final String lngCustom;
        public final String latCustom;

        public LocationRow(String location, String lngWgs, String latWgs, long timestamp,
                           String lngCustom, String latCustom) {
            this(0, location, lngWgs, latWgs, timestamp, lngCustom, latCustom);
        }

        public LocationRow(int id, String location, String lngWgs, String latWgs, long timestamp,
                           String lngCustom, String latCustom) {
            this.id = id;
            this.location = location;
            this.lngWgs = lngWgs;
            this.latWgs = latWgs;
            this.timestamp = timestamp;
            this.lngCustom = lngCustom;
            this.latCustom = latCustom;
        }

        public LocationRow copy() {
            return new LocationRow(id, location, lngWgs, latWgs, timestamp, lngCustom, latCustom);
        }
    }
}
