package com.yybb.location.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * MapUtils 坐标转换纯 JVM 单测(无 Android 依赖)。
 * bd09togcj02 锚定值与 MapState 初始占位点换算同源(该函数被 DB 迁移路径永久复用)。
 * gcj02towgs84/wgs2gcj 为近似互逆(gcj02towgs84 采用 lng*2-mglng 近似),往返容差放宽到 1e-5 度。
 */
public class MapUtilsTest {
    private static final double DELTA = 1e-12;
    private static final double ROUND_TRIP_DELTA = 1e-5;

    @Test
    public void bd09togcj02_knownAnchor() {
        double[] gcj = MapUtils.bd09togcj02(117.07018449827267, 36.547743718042415);
        assertEquals(117.06357864688202, gcj[0], DELTA);
        assertEquals(36.54208893720672, gcj[1], DELTA);
    }

    @Test
    public void gcj02towgs84_then_wgs2gcj_roundTrip_beijing() {
        double[] wgs = MapUtils.gcj02towgs84(116.397428, 39.90923);
        double[] gcj = MapUtils.wgs2gcj(wgs[0], wgs[1]);
        assertEquals(116.397428, gcj[0], ROUND_TRIP_DELTA);
        assertEquals(39.90923, gcj[1], ROUND_TRIP_DELTA);
    }

    @Test
    public void gcj02towgs84_then_wgs2gcj_roundTrip_jinan() {
        double[] wgs = MapUtils.gcj02towgs84(117.120, 36.651);
        double[] gcj = MapUtils.wgs2gcj(wgs[0], wgs[1]);
        assertEquals(117.120, gcj[0], ROUND_TRIP_DELTA);
        assertEquals(36.651, gcj[1], ROUND_TRIP_DELTA);
    }

    @Test
    public void wgs2gcj_offsetsArePositiveInChina() {
        double[] gcj = MapUtils.wgs2gcj(116.397428, 39.90923);
        assertTrue(gcj[0] > 116.397428);
        assertTrue(gcj[1] > 39.90923);
    }

    @Test
    public void bd09togcj02_returnsLngLatOrder() {
        double[] gcj = MapUtils.bd09togcj02(117.07018449827267, 36.547743718042415);
        assertNotNull(gcj);
        assertTrue("经度应在纬度之前且经度>纬度(中国境内)", gcj[0] > gcj[1]);
    }
}
