package com.zcshou.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * GoUtils.parseDoubleOrDefault 纯 JVM 单测（不触碰任何 Android API）。
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
}
