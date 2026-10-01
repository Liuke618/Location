package com.zcshou.gogogo.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * GeocoderHelper.parseAddress 纯 JVM 单测（无 Android 依赖）。
 * 语义锚定原 MainActivity.recordCurrentLocation onResponse 解析逻辑（行为冻结）：
 * 1. status==0 → result.formatted_address；
 * 2. status!=0 → markName 优先，否则 JSON message；
 * 3. JSONException（响应非法/缺键）→ markName —— 原代码连续两次 put LOCATION（先
 *    markName==null?默认名:markName，再被 markName 无条件覆盖），最终落库值即 markName（含 null）。
 * 传参 defaultName/defaultMessage 用不同哨兵值，验证它们不参与返回值（对应原 :984 无条件覆盖）。
 *
 * 测试构件差异说明：百度 REST 实际返回数值型 status，设备端 Android org.json 的 getString
 * 对数值做字符串强转（原实现依赖此行为）；JVM 测试构件 json-java 的 getString 为严格类型，
 * 数值形态会落入回退分支。故测试统一用字符串形态 status 锚定同一分支逻辑，实现保持原
 * getString 写法不因测试构件而改变。
 */
public class GeocoderHelperTest {

    private static final String MARK = "标记名";
    private static final String DEFAULT_MSG = "默认消息";
    private static final String DEFAULT_NAME = "默认名称";

    @Test
    public void parseAddress_statusZero_returnsFormattedAddress() {
        String json = "{\"status\":\"0\",\"result\":{\"formatted_address\":\"北京市海淀区中关村大街27号\"}}";
        assertEquals("北京市海淀区中关村大街27号",
                GeocoderHelper.parseAddress(json, MARK, DEFAULT_MSG, DEFAULT_NAME));
    }

    @Test
    public void parseAddress_statusZero_ignoresMarkName() {
        // 成功分支优先于标记名（原逻辑 status==0 时直接取 formatted_address）
        String json = "{\"status\":\"0\",\"result\":{\"formatted_address\":\"济南市历下区经十路\"}}";
        assertEquals("济南市历下区经十路",
                GeocoderHelper.parseAddress(json, MARK, DEFAULT_MSG, DEFAULT_NAME));
    }

    @Test
    public void parseAddress_statusNonZero_withMarkName_returnsMarkName() {
        String json = "{\"status\":1,\"message\":\"服务不可用\"}";
        assertEquals(MARK, GeocoderHelper.parseAddress(json, MARK, DEFAULT_MSG, DEFAULT_NAME));
    }

    @Test
    public void parseAddress_statusNonZero_withoutMarkName_returnsMessage() {
        String json = "{\"status\":\"2\",\"message\":\"请求参数错误\"}";
        assertEquals("请求参数错误",
                GeocoderHelper.parseAddress(json, null, DEFAULT_MSG, DEFAULT_NAME));
    }

    @Test
    public void parseAddress_malformedJson_withMarkName_returnsMarkName() {
        assertEquals(MARK, GeocoderHelper.parseAddress("not-json{", MARK, DEFAULT_MSG, DEFAULT_NAME));
    }

    @Test
    public void parseAddress_malformedJson_withoutMarkName_returnsNull() {
        // 原代码 :983 先写默认名、:984 被 markName 无条件覆盖 → 最终落库值 = markName（含 null），
        // defaultName/defaultMessage 不参与返回值（哨兵值断言冻结该语义）
        assertNull(GeocoderHelper.parseAddress("not-json{", null, DEFAULT_MSG, DEFAULT_NAME));
    }

    @Test
    public void parseAddress_emptyBody_withMarkName_returnsMarkName() {
        // 空响应体：new JSONObject("") 抛 JSONException → 回退分支
        assertEquals(MARK, GeocoderHelper.parseAddress("", MARK, DEFAULT_MSG, DEFAULT_NAME));
    }

    @Test
    public void parseAddress_missingStatusKey_returnsMarkName() {
        // 缺 status 键 → getString("status") 抛 JSONException → 回退分支
        assertEquals(MARK, GeocoderHelper.parseAddress("{}", MARK, DEFAULT_MSG, DEFAULT_NAME));
    }

    @Test
    public void parseAddress_missingResultKey_returnsMarkName() {
        // status==0 但缺 result 键 → getJSONObject("result") 抛 JSONException → 回退分支
        assertEquals(MARK,
                GeocoderHelper.parseAddress("{\"status\":0}", MARK, DEFAULT_MSG, DEFAULT_NAME));
    }

    @Test
    public void parseAddress_missingMessageKey_withoutMarkName_returnsNull() {
        // status!=0、markName 为 null 且缺 message 键 → 回退分支最终值 = markName（null）
        assertNull(GeocoderHelper.parseAddress("{\"status\":1}", null, DEFAULT_MSG, DEFAULT_NAME));
    }
}
