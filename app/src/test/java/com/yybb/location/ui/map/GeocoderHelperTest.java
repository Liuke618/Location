package com.yybb.location.ui.map;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * GeocoderHelper.extractAddress 纯 JVM 单测(纯字符串入参,无 Android/SDK 依赖)。
 * 三分支语义锚定(对应原百度 REST 版 parseAddress 的行为迁移):
 * 1. rCode==1000 且 formatAddress 非空 → formatAddress;
 * 2. 其余且 markName 非空 → markName(原"业务失败回退标记名");
 * 3. 其余 → defaultName(原 JSON message 回退无对应物——SDK 以 rCode 表达失败)。
 */
public class GeocoderHelperTest {

    private static final String MARK = "标记名";
    private static final String DEFAULT_NAME = "默认名称";

    @Test
    public void extractAddress_success_returnsFormatAddress() {
        assertEquals("山东省济南市历下区某某路",
                GeocoderHelper.extractAddress(1000, "山东省济南市历下区某某路", MARK, DEFAULT_NAME));
    }

    @Test
    public void extractAddress_errorCode_returnsMarkName() {
        assertEquals(MARK, GeocoderHelper.extractAddress(1002, "不应被采用", MARK, DEFAULT_NAME));
    }

    @Test
    public void extractAddress_nullFormatAddress_returnsMarkName() {
        assertEquals(MARK, GeocoderHelper.extractAddress(1000, null, MARK, DEFAULT_NAME));
    }

    @Test
    public void extractAddress_emptyFormatAddress_returnsMarkName() {
        assertEquals(MARK, GeocoderHelper.extractAddress(1000, "", MARK, DEFAULT_NAME));
    }

    @Test
    public void extractAddress_nullMarkName_returnsDefaultName() {
        assertEquals(DEFAULT_NAME, GeocoderHelper.extractAddress(1002, null, null, DEFAULT_NAME));
        assertEquals(DEFAULT_NAME, GeocoderHelper.extractAddress(1002, null, "", DEFAULT_NAME));
    }
}
