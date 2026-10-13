package com.yybb.location.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * AmapUtils 纯函数 JVM 单测（不触碰任何 Android API）。
 * 生效 key 解析：设置页自定义 key 优先（null/空白一律视为未配置），否则回落构建期注入的清单值；
 * 两者皆缺省时返回空串，由调用方跳过对三套 SDK setter 的下发。
 */
public class AmapUtilsTest {

    @Test
    public void resolveEffectiveApiKey_userKeySet_returnsTrimmedUserKey() {
        assertEquals("user-key", AmapUtils.resolveEffectiveApiKey("user-key", "built-in-key"));
        assertEquals("user-key", AmapUtils.resolveEffectiveApiKey("  user-key  ", "built-in-key"));
    }

    @Test
    public void resolveEffectiveApiKey_userKeyUnset_fallsBackToBuiltInKey() {
        assertEquals("built-in-key", AmapUtils.resolveEffectiveApiKey(null, "built-in-key"));
        assertEquals("built-in-key", AmapUtils.resolveEffectiveApiKey("", "built-in-key"));
        assertEquals("built-in-key", AmapUtils.resolveEffectiveApiKey("   ", "built-in-key"));
    }

    @Test
    public void resolveEffectiveApiKey_bothUnset_returnsEmptyString() {
        assertEquals("", AmapUtils.resolveEffectiveApiKey(null, null));
        assertEquals("", AmapUtils.resolveEffectiveApiKey("", "   "));
    }

    @Test
    public void resolveEffectiveApiKey_trimsBuiltInKey() {
        assertEquals("built-in-key", AmapUtils.resolveEffectiveApiKey("", " built-in-key "));
    }

    @Test
    public void toColonHex_bytesToUppercaseColonSeparatedHex() {
        assertEquals("D3:67:71", AmapUtils.toColonHex(new byte[]{(byte) 0xd3, 0x67, 0x71}));
        assertEquals("0A:FF:00", AmapUtils.toColonHex(new byte[]{0x0a, (byte) 0xff, 0x00}));
    }

    @Test
    public void toColonHex_emptyInput_returnsEmptyString() {
        assertEquals("", AmapUtils.toColonHex(new byte[0]));
    }
}
