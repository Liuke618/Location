package com.zcshou.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * DbMigrations 纯函数 JVM 单测(无 Android 依赖;SQLiteOpenHelper 本体不可 JVM 实例化,
 * 迁移正确性由本测试 + MapUtils.bd09togcj02 锚定共同覆盖,DB 循环保持薄)。
 */
public class DbMigrationsTest {

    @Test
    public void bd09ToGcj02_knownAnchor() {
        String[] gcj = DbMigrations.bd09ToGcj02("117.07018449827267", "36.547743718042415");
        assertEquals("117.06357864688202", gcj[0]);
        assertEquals("36.54208893720672", gcj[1]);
    }

    @Test
    public void bd09ToGcj02_invalidNumber_returnsNull() {
        assertNull(DbMigrations.bd09ToGcj02("abc", "36.5"));
        assertNull(DbMigrations.bd09ToGcj02("117.0", "xyz"));
        assertNull(DbMigrations.bd09ToGcj02(null, "36.5"));
    }
}
