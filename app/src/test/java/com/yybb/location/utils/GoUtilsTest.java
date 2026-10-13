package com.yybb.location.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * GoUtils 纯函数 JVM 单测（不触碰任何 Android API）。
 * 被测路径都是用户可编辑文本：坐标输入框的 digits 放行 "-0123456789."（可写出 "1.2.3"、"-"），
 * 海拔/偏移/有效期偏好来自 EditTextPreference（可粘贴非数字）。原实现直接 Double.parseDouble，
 * 非法值即 NumberFormatException 崩界面；语义要求"回退默认值，且永不返回 NaN/Infinity 兜底值本身
 * 之外的非有限数"，避免 "NaN" 绕过经纬度范围校验。
 */
public class GoUtilsTest {

    @Test
    public void parseDoubleOrDefault_validNumber_returnsParsedValue() {
        assertEquals(116.4, GoUtils.parseDoubleOrDefault("116.4", 0.0), 0.0);
        assertEquals(-39.5, GoUtils.parseDoubleOrDefault("  -39.5  ", 0.0), 0.0);
        assertEquals(55.0, GoUtils.parseDoubleOrDefault("55", 0.0), 0.0);
    }

    @Test
    public void parseDoubleOrDefault_malformedOrNull_returnsFallback() {
        assertEquals(7.0, GoUtils.parseDoubleOrDefault(null, 7.0), 0.0);
        assertEquals(7.0, GoUtils.parseDoubleOrDefault("", 7.0), 0.0);
        assertEquals(7.0, GoUtils.parseDoubleOrDefault("1.2.3", 7.0), 0.0);
        assertEquals(7.0, GoUtils.parseDoubleOrDefault("-", 7.0), 0.0);
        assertEquals(7.0, GoUtils.parseDoubleOrDefault("abc", 7.0), 0.0);
    }

    @Test
    public void parseDoubleOrDefault_nonFiniteLiteral_returnsFallback() {
        // "NaN"/"Infinity" 能被 Double.parseDouble 接受，但会绕过后续范围校验，必须按非法处理
        assertEquals(7.0, GoUtils.parseDoubleOrDefault("NaN", 7.0), 0.0);
        assertEquals(7.0, GoUtils.parseDoubleOrDefault("Infinity", 7.0), 0.0);
    }

    @Test
    public void parseDoubleOrDefault_fallbackMayBeNaN_forCallerSideValidation() {
        // 界面用它把非法输入收敛成"提示用户"而非"取默认值"
        assertTrue(Double.isNaN(GoUtils.parseDoubleOrDefault("1.2.3", Double.NaN)));
        assertEquals(116.4, GoUtils.parseDoubleOrDefault("116.4", Double.NaN), 0.0);
    }

    /*============================== 手动输入经纬度(收藏页) ==============================*/

    @Test
    public void validateManualLatLng_validInputs_returnOk() {
        assertEquals(GoUtils.MANUAL_LATLNG_OK, GoUtils.validateManualLatLng("116.397428", "39.90923"));
        assertEquals(GoUtils.MANUAL_LATLNG_OK, GoUtils.validateManualLatLng("-116.4", "-39.9"));
        // 边界值含端点
        assertEquals(GoUtils.MANUAL_LATLNG_OK, GoUtils.validateManualLatLng("180", "90"));
        assertEquals(GoUtils.MANUAL_LATLNG_OK, GoUtils.validateManualLatLng("-180", "-90"));
        // 首尾空白合法(parseDoubleOrDefault 会 trim)
        assertEquals(GoUtils.MANUAL_LATLNG_OK, GoUtils.validateManualLatLng(" 116.4 ", " 39.9 "));
    }

    @Test
    public void validateManualLatLng_blankOrMalformed_returnsMalformed() {
        // 空/空白走"输入非法"分支;digits 放行可写出 "1.2.3"/"-","NaN"/"Infinity" 可解析但会绕过范围校验
        assertEquals(GoUtils.MANUAL_LATLNG_ERR_MALFORMED, GoUtils.validateManualLatLng(null, "39.9"));
        assertEquals(GoUtils.MANUAL_LATLNG_ERR_MALFORMED, GoUtils.validateManualLatLng("116.4", ""));
        assertEquals(GoUtils.MANUAL_LATLNG_ERR_MALFORMED, GoUtils.validateManualLatLng("  ", "39.9"));
        assertEquals(GoUtils.MANUAL_LATLNG_ERR_MALFORMED, GoUtils.validateManualLatLng("1.2.3", "39.9"));
        assertEquals(GoUtils.MANUAL_LATLNG_ERR_MALFORMED, GoUtils.validateManualLatLng("-", "39.9"));
        assertEquals(GoUtils.MANUAL_LATLNG_ERR_MALFORMED, GoUtils.validateManualLatLng("NaN", "39.9"));
        assertEquals(GoUtils.MANUAL_LATLNG_ERR_MALFORMED, GoUtils.validateManualLatLng("Infinity", "39.9"));
    }

    @Test
    public void validateManualLatLng_longitudeOutOfRange_reportsLongitudeFirst() {
        assertEquals(GoUtils.MANUAL_LATLNG_ERR_LONGITUDE, GoUtils.validateManualLatLng("180.5", "39.9"));
        assertEquals(GoUtils.MANUAL_LATLNG_ERR_LONGITUDE, GoUtils.validateManualLatLng("-180.0001", "39.9"));
        // 双双越界时经度先行(与主界面手动输入对话框的提示顺序一致)
        assertEquals(GoUtils.MANUAL_LATLNG_ERR_LONGITUDE, GoUtils.validateManualLatLng("999", "999"));
    }

    @Test
    public void validateManualLatLng_latitudeOutOfRange_returnsLatitudeError() {
        assertEquals(GoUtils.MANUAL_LATLNG_ERR_LATITUDE, GoUtils.validateManualLatLng("116.4", "90.1"));
        assertEquals(GoUtils.MANUAL_LATLNG_ERR_LATITUDE, GoUtils.validateManualLatLng("-116.4", "-90.1"));
    }

    @Test
    public void convertManualLatLng_gcjInput_wgsDerived_gcjPreserved() {
        double[] quad = GoUtils.convertManualLatLng(116.397428, 39.90923, false);
        // 四元组 {经度WGS84, 纬度WGS84, 经度GCJ02, 纬度GCJ02}:GCJ02 列原样保留,WGS84 列为负偏移换算值
        assertEquals(116.397428, quad[2], 0.0);
        assertEquals(39.90923, quad[3], 0.0);
        assertTrue(quad[0] < quad[2]);
        assertTrue(quad[1] < quad[3]);
        // wgs2gcj 回转应回到输入(gcj02towgs84 为近似换算,往返容差同 MapUtilsTest 的 1e-5 度)
        double[] back = MapUtils.wgs2gcj(quad[0], quad[1]);
        assertEquals(116.397428, back[0], 1e-5);
        assertEquals(39.90923, back[1], 1e-5);
    }

    @Test
    public void convertManualLatLng_wgsInput_wgsPreserved_gcjDerived() {
        double[] quad = GoUtils.convertManualLatLng(116.397428, 39.90923, true);
        // WGS84 列原样保留,GCJ02 列为正偏移换算值(境内偏移为正,同 MapUtilsTest);gcj02towgs84 回转应回到输入
        assertEquals(116.397428, quad[0], 0.0);
        assertEquals(39.90923, quad[1], 0.0);
        assertTrue(quad[2] > quad[0]);
        assertTrue(quad[3] > quad[1]);
        double[] back = MapUtils.gcj02towgs84(quad[2], quad[3]);
        assertEquals(116.397428, back[0], 1e-5);
        assertEquals(39.90923, back[1], 1e-5);
    }

    /*============================== 库内存量坐标解析(默认收藏恢复) ==============================*/

    @Test
    public void parseStoredCoordinatePair_validNumbers_returnLngLatPair() {
        double[] pair = GoUtils.parseStoredCoordinatePair("116.397428", "39.90923");

        assertNotNull(pair);
        assertEquals(116.397428, pair[0], 0.0);
        assertEquals(39.90923, pair[1], 0.0);
        // 首尾空白容许(与 parseDoubleOrDefault 同语义)
        assertEquals(-39.9, GoUtils.parseStoredCoordinatePair(" -116.4 ", " -39.9")[1], 0.0);
    }

    @Test
    public void parseStoredCoordinatePair_malformedOrNull_returnNull() {
        // 坐标串来自收藏表:旧版本写入/外部改库都可能是空串或非数字。原调用点直接 Double.parseDouble,
        // 冷启动恢复默认收藏与未选点按 GO 两条路径都会 NumberFormatException 崩溃,故非法必须回 null
        assertNull(GoUtils.parseStoredCoordinatePair(null, "39.9"));
        assertNull(GoUtils.parseStoredCoordinatePair("116.4", null));
        assertNull(GoUtils.parseStoredCoordinatePair("", "39.9"));
        assertNull(GoUtils.parseStoredCoordinatePair("116.4", "  "));
        assertNull(GoUtils.parseStoredCoordinatePair("1.2.3", "39.9"));
        assertNull(GoUtils.parseStoredCoordinatePair("abc", "39.9"));
    }

    @Test
    public void parseStoredCoordinatePair_nonFinite_returnNull() {
        // "NaN"/"Infinity" 可被 Double.parseDouble 接受,但选中点会落到地图上不存在的位置
        assertNull(GoUtils.parseStoredCoordinatePair("NaN", "39.9"));
        assertNull(GoUtils.parseStoredCoordinatePair("116.4", "Infinity"));
    }
}
