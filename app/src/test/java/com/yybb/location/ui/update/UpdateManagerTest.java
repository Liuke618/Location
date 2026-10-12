package com.yybb.location.ui.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;

/**
 * UpdateManager 纯函数 JVM 单测（无 Android 依赖）：release 解析、版本比较、下载地址同源校验。
 * 语义锚定原 MainActivity.checkUpdateVersion 更新对话框组装的 JSON 读取（行为冻结）：
 * name/tag_name/created_at/target_commitish/body 直接 getString；assets 经 getString 后二次
 * new JSONArray 解析再取 assets[0] 的 browser_download_url/name；
 * 任何缺失键 / assets 缺失或为空均抛 JSONException，由调用方 runOnUiThread 的
 * catch → XLog.e("ERROR: resolve json") 兜底（保持现状）。
 *
 * 测试构件差异说明：GitHub releases/latest 实际返回的 assets 是 JSON 数组，设备端
 * Android org.json 的 getString 对非字符串值做字符串强转后交给二次解析（原实现依赖
 * 此行为）；JVM 测试构件 json-java 的 getString 为严格类型（仅接受字符串实例）。
 * 故测试统一用字符串形态的 assets（JSON 数组文本），与设备端"getString → 二次
 * JSONArray 解析"走同一路径，实现保持原 getString 写法不因测试构件而改变。
 */
public class UpdateManagerTest {

    private static final String SELF_SLUG = "Liuke618/Location";

    /* 合法 release 响应（字段形态与 GitHub API 一致，assets 用字符串形态锚定二次解析路径） */
    private static final String RELEASE_JSON = """
            {
                "name": "任意门 v1.2.3",
                "tag_name": "v1.2.3",
                "created_at": "2026-01-02T03:04:05Z",
                "target_commitish": "master",
                "body": "## 更新内容\\n- 修复若干问题",
                "assets": "[{\\"browser_download_url\\":\\"https://github.com/Liuke618/Location/releases/download/v1.2.3/gogogo.apk\\",\\"name\\":\\"gogogo.apk\\"}]"
            }
            """;

    @Test
    public void parseRelease_validJson_extractsAllFields() throws JSONException {
        UpdateManager.UpdateInfo info = UpdateManager.parseRelease(RELEASE_JSON);

        assertEquals("任意门 v1.2.3", info.name);
        assertEquals("v1.2.3", info.tagName);
        assertEquals("2026-01-02T03:04:05Z", info.createdAt);
        assertEquals("master", info.commitish);
        assertEquals("## 更新内容\n- 修复若干问题", info.body);
        assertEquals("https://github.com/Liuke618/Location/releases/download/v1.2.3/gogogo.apk", info.downloadUrl);
        assertEquals("gogogo.apk", info.filename);
    }

    @Test
    public void parseRelease_missingAssetsKey_throwsJsonException() {
        // 缺 assets 键 → getString("assets") 抛 JSONException（对话框中止组装，由调用方 catch 兜底）
        String json = """
                {
                    "name": "任意门 v1.2.3",
                    "tag_name": "v1.2.3",
                    "created_at": "2026-01-02T03:04:05Z",
                    "target_commitish": "master",
                    "body": "## 更新内容"
                }
                """;
        assertThrows(JSONException.class, () -> UpdateManager.parseRelease(json));
    }

    @Test
    public void parseRelease_emptyAssetsArray_throwsJsonException() {
        // assets 为空数组（无 APK 资产）→ getJSONObject(0) 抛 JSONException
        String json = """
                {
                    "name": "任意门 v1.2.3",
                    "tag_name": "v1.2.3",
                    "created_at": "2026-01-02T03:04:05Z",
                    "target_commitish": "master",
                    "body": "## 更新内容",
                    "assets": "[]"
                }
                """;
        assertThrows(JSONException.class, () -> UpdateManager.parseRelease(json));
    }

    @Test
    public void parseRelease_missingBodyKey_throwsJsonException() {
        // 缺 body 键 → getString("body") 抛 JSONException
        String json = """
                {
                    "name": "任意门 v1.2.3",
                    "tag_name": "v1.2.3",
                    "created_at": "2026-01-02T03:04:05Z",
                    "target_commitish": "master",
                    "assets": "[{\\"browser_download_url\\":\\"https://example.com/gogogo.apk\\",\\"name\\":\\"gogogo.apk\\"}]"
                }
                """;
        assertThrows(JSONException.class, () -> UpdateManager.parseRelease(json));
    }

    @Test
    public void parseRelease_missingNameKey_throwsJsonException() {
        // 缺 name 键 → getString("name") 抛 JSONException（该值是对话框标题）
        String json = """
                {
                    "tag_name": "v1.2.3",
                    "created_at": "2026-01-02T03:04:05Z",
                    "target_commitish": "master",
                    "body": "## 更新内容",
                    "assets": "[{\\"browser_download_url\\":\\"https://example.com/gogogo.apk\\",\\"name\\":\\"gogogo.apk\\"}]"
                }
                """;
        assertThrows(JSONException.class, () -> UpdateManager.parseRelease(json));
    }

    @Test
    public void parseRelease_missingTagNameKey_throwsJsonException() {
        // 缺 tag_name 键 → 版本判断无依据，抛 JSONException 由调用方 catch 兜底
        String json = """
                {
                    "name": "任意门 v1.2.3",
                    "created_at": "2026-01-02T03:04:05Z",
                    "target_commitish": "master",
                    "body": "## 更新内容",
                    "assets": "[{\\"browser_download_url\\":\\"https://example.com/gogogo.apk\\",\\"name\\":\\"gogogo.apk\\"}]"
                }
                """;
        assertThrows(JSONException.class, () -> UpdateManager.parseRelease(json));
    }

    @Test
    public void compareVersionNames_higherMinor_isPositive() {
        assertTrue(UpdateManager.compareVersionNames("1.13.0", "1.12.3") > 0);
        assertTrue(UpdateManager.compareVersionNames("1.12.4", "1.12.3") > 0);
        assertTrue(UpdateManager.compareVersionNames("2.0.0", "1.99.99") > 0);
    }

    @Test
    public void compareVersionNames_lowerOrEqual_isNotPositive() {
        // 降级必须判为非更新：原 contains 写法会把标题不含当前版本号的旧 release 当成新版
        assertTrue(UpdateManager.compareVersionNames("1.12.2", "1.12.3") < 0);
        assertEquals(0, UpdateManager.compareVersionNames("1.12.3", "1.12.3"));
        assertFalse(UpdateManager.compareVersionNames("1.12.2", "1.12.3") > 0);
    }

    @Test
    public void compareVersionNames_toleratesPrefixAndSegmentAndSuffixForms() {
        assertEquals(0, UpdateManager.compareVersionNames("v1.12.3", "1.12.3"));
        assertTrue(UpdateManager.compareVersionNames("v1.12", "1.11.9") > 0);
        // 段数不等按缺失段补 0：1.12 与 1.12.0 同版
        assertEquals(0, UpdateManager.compareVersionNames("1.12", "1.12.0"));
        // 预发布/构建后缀被裁掉，主版本决定结果
        assertEquals(0, UpdateManager.compareVersionNames("1.12.3-rc1", "1.12.3"));
        assertTrue(UpdateManager.compareVersionNames("1.12.3+build9", "1.12.2") > 0);
    }

    @Test
    public void compareVersionNames_malformedOrMissing_treatedAsOldest() {
        // 畸形/空值不抛异常，按 0 段处理，判为"不是更新"，宁可不弹也不误导下载
        assertFalse(UpdateManager.compareVersionNames("", "1.12.3") > 0);
        assertFalse(UpdateManager.compareVersionNames(null, "1.12.3") > 0);
        assertFalse(UpdateManager.compareVersionNames("任意门最新版", "1.12.3") > 0);
    }

    @Test
    public void isTrustedDownloadUrl_sameRepoHttps_isAccepted() {
        assertTrue(UpdateManager.isTrustedDownloadUrl(
                "https://github.com/Liuke618/Location/releases/download/v1.2.3/app.apk", SELF_SLUG));
    }

    @Test
    public void isTrustedDownloadUrl_otherRepoOrSchemeOrHost_isRejected() {
        assertFalse(UpdateManager.isTrustedDownloadUrl(
                "https://github.com/zcshou/gogogo/releases/download/v1.2.3/app.apk", SELF_SLUG));
        assertFalse(UpdateManager.isTrustedDownloadUrl(
                "http://github.com/Liuke618/Location/releases/download/v1.2.3/app.apk", SELF_SLUG));
        assertFalse(UpdateManager.isTrustedDownloadUrl(
                "https://evil.example.com/Liuke618/Location/releases/download/v1.2.3/app.apk", SELF_SLUG));
        assertFalse(UpdateManager.isTrustedDownloadUrl(
                "https://github.com/other/thing/releases/download/v1.2.3/app.apk", SELF_SLUG));
    }

    @Test
    public void isTrustedDownloadUrl_repoPrefixLookalike_isRejected() {
        // 路径必须是 "/{owner}/{repo}/"，多一段尾巴的同名伪装仓库要拦住
        assertFalse(UpdateManager.isTrustedDownloadUrl(
                "https://github.com/Liuke618/LocationEvil/releases/download/v1.2.3/app.apk", SELF_SLUG));
        assertFalse(UpdateManager.isTrustedDownloadUrl(
                "https://github.com/Liuke618/LocationX/releases/download/v1.2.3/app.apk", SELF_SLUG));
    }

    @Test
    public void isTrustedDownloadUrl_nullOrUnparsable_isRejected() {
        assertFalse(UpdateManager.isTrustedDownloadUrl(null, SELF_SLUG));
        assertFalse(UpdateManager.isTrustedDownloadUrl("", SELF_SLUG));
        assertFalse(UpdateManager.isTrustedDownloadUrl("https://github.com/Liuke618/Location/a b.apk", SELF_SLUG));
        assertFalse(UpdateManager.isTrustedDownloadUrl(
                "https://github.com/Liuke618/Location/releases/download/v1.2.3/app.apk", ""));
    }
}
