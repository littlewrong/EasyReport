package cn.easyreport.sync.core;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class SyncProgressPayloadTest {
    private static final int MAX_BYTES = 60 * 1024;

    @Test
    public void smallListsKeepExistingFieldsAndExactEscapedValues() {
        List<String> pending = Collections.unmodifiableList(Arrays.asList("erp_a.订单\"\\\n", "erp_b.orders"));
        List<String> completed = Collections.singletonList("erp_a.completed");
        String message = "正在同步 \"订单\"\\path\n\t\u0001 \uD83D\uDE00";

        JSONObject payload = parseBounded(SyncProgressPayload.withTables(1, 3, message, pending, completed));

        assertProgress(payload, 1, 3, 33, message);
        assertEquals(pending, payload.getJSONArray("pendingTables").toJavaList(String.class));
        assertEquals(completed, payload.getJSONArray("completedTables").toJavaList(String.class));
        assertEquals(2, payload.getIntValue("pendingTableCount"));
        assertEquals(1, payload.getIntValue("completedTableCount"));
        assertTrue(payload.containsKey("tableListsTruncated"));
        assertFalse(payload.getBooleanValue("tableListsTruncated"));
    }

    @Test
    public void thousandsOfMixedUnicodeNamesKeepFullCountsAndFirstAndLastHundredSamples() {
        List<String> pending = names("pending", 5001);
        List<String> completed = names("completed", 6003);
        List<String> pendingSnapshot = new ArrayList<>(pending);
        List<String> completedSnapshot = new ArrayList<>(completed);
        Map<String, Object> oldPayload = new LinkedHashMap<>();
        oldPayload.put("pendingTables", pending);
        oldPayload.put("completedTables", completed);
        assertTrue("Fixture must reproduce the former TEXT overflow",
                JSON.toJSONString(oldPayload).getBytes(StandardCharsets.UTF_8).length > 65535);

        JSONObject payload = parseBounded(SyncProgressPayload.withTables(6003, 11004, "同步中",
                Collections.unmodifiableList(pending), Collections.unmodifiableList(completed)));

        assertProgress(payload, 6003, 11004, 54, "同步中");
        assertEquals(5001, payload.getIntValue("pendingTableCount"));
        assertEquals(6003, payload.getIntValue("completedTableCount"));
        assertTrue(payload.getBooleanValue("tableListsTruncated"));
        assertEquals(pending.subList(0, 100), payload.getJSONArray("pendingTables").toJavaList(String.class));
        assertEquals(completed.subList(completed.size() - 100, completed.size()),
                payload.getJSONArray("completedTables").toJavaList(String.class));
        assertEquals(pendingSnapshot, pending);
        assertEquals(completedSnapshot, completed);
        for (int i = 0; i < pending.size(); i++) assertSame(pendingSnapshot.get(i), pending.get(i));
        for (int i = 0; i < completed.size(); i++) assertSame(completedSnapshot.get(i), completed.get(i));
    }

    @Test
    public void oversizedSingleNameAndEscapedEmojiMessageRemainValidAndWithinUtf8Limit() {
        String hugeName = repeat("数据库_表\"\\\n\t\u0002", 20000);
        List<String> pending = Collections.singletonList(hugeName);
        String prefix = "\"\\\n\r\t\u0001";
        String message = prefix + repeat("中", 1023 - prefix.codePointCount(0, prefix.length())) + "\uD83D\uDE00tail";

        JSONObject payload = parseBounded(SyncProgressPayload.withTables(0, 1, message, pending, Collections.emptyList()));

        assertEquals(1, payload.getIntValue("pendingTableCount"));
        assertEquals(0, payload.getIntValue("completedTableCount"));
        assertTrue(payload.getBooleanValue("tableListsTruncated"));
        assertTrue("A single name larger than the entire payload cannot be included intact",
                payload.getJSONArray("pendingTables").isEmpty());
        assertTrue(payload.getJSONArray("completedTables").isEmpty());
        String shortened = payload.getString("message");
        assertTrue(shortened.startsWith(prefix));
        assertTrue(shortened.codePointCount(0, shortened.length()) <= 1024);
        assertValidUnicode(shortened);
        assertSame(hugeName, pending.get(0));
    }

    @Test
    public void summaryKeepsLongCountersWithoutTableLists() {
        String json = SyncProgressPayload.summary(2500000000L, 5000000000L, "批量导入中");
        JSONObject payload = parseBounded(json);

        assertProgress(payload, 2500000000L, 5000000000L, 50, "批量导入中");
        assertEquals(4, payload.size());
        assertFalse(payload.containsKey("pendingTables"));
        assertFalse(payload.containsKey("completedTables"));
        assertTrue("Summary callbacks should stay small", json.getBytes(StandardCharsets.UTF_8).length < 512);
    }

    private JSONObject parseBounded(String json) {
        assertNotNull(json);
        assertTrue("Payload exceeds the UTF-8 byte budget", json.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES);
        assertValidUnicode(json);
        return JSON.parseObject(json);
    }

    private void assertProgress(JSONObject payload, long current, long total, int percent, String message) {
        assertEquals(current, payload.getLongValue("current"));
        assertEquals(total, payload.getLongValue("total"));
        assertEquals(percent, payload.getIntValue("percent"));
        assertEquals(message, payload.getString("message"));
    }

    private void assertValidUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                assertTrue("Unpaired high surrogate", i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1)));
                i++;
            } else assertFalse("Unpaired low surrogate", Character.isLowSurrogate(ch));
        }
    }

    private List<String> names(String prefix, int count) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(prefix + "_" + i + ".订单_quality_产品字段_abcdefghijklmnop_" + i);
        return result;
    }

    private String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
