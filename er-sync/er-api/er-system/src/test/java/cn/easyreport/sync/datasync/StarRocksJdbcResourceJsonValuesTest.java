package cn.easyreport.sync.datasync;

import org.junit.Test;

import static org.junit.Assert.*;

public class StarRocksJdbcResourceJsonValuesTest {
    @Test
    public void exactNumbersCompareByValueWithoutApplicationFloatingPointRounding() {
        equivalent("0.1", "0.1000000000000000000000000000000000");
        equivalent("1", "1.0");
        equivalent("1e1000", "10e999");
        equivalent("1e-1000", "10e-1001");
        equivalent("{\"large\":[1e1000,-1e1000]}", "{\"large\":[10e999,-10e999]}");
        equivalent("-0.0", "0");
        equivalent("18446744073709551615", "18446744073709551615.000");
        equivalent("123456789012345678901234567890123456789", "123456789012345678901234567890123456789");
    }

    @Test
    public void roundingOverflowAndUnderflowAreRejectedEvenInsideContainers() {
        rejects("1.0000000000000000000001", "1");
        rejects("0.1", "0.10000000000000001");
        rejects("9007199254740993", "9007199254740992");
        rejects("18446744073709551615", "18446744073709552000");
        rejects("123456789012345678901234567890123456789", "123456789012345678901234567890123456788");
        rejects("1e-1000", "0");
        rejects("1e1000", "2e1000");
        rejects("{\"nested\":[{\"price\":1.0000000000000000000001}]}", "{\"nested\":[{\"price\":1}]}");
        rejects("1e1000", "null");
    }

    @Test
    public void objectKeyOrderIsIrrelevantButArrayOrderAndMembersAreSignificant() {
        equivalent("{\"one\":1,\"nested\":{\"a\":true,\"b\":[1,2,null]}}",
                "{\"nested\":{\"b\":[1.0,2.00,null],\"a\":true},\"one\":1.00}");
        rejects("[1,2]", "[2,1]");
        rejects("[1,2]", "[1]");
        rejects("{\"a\":null}", "{}");
        rejects("{\"a\":1}", "{\"b\":1}");
        rejects("{\"A\":1}", "{\"a\":1}");
        rejects("{}", "[]");
    }

    @Test
    public void sqlNullJsonNullAndOtherJsonTypesStayDistinct() {
        equivalent(null, null);
        equivalent("null", " null ");
        equivalent("true", "true");
        equivalent("\"null\"", "\"null\"");
        rejects(null, "null");
        rejects("null", null);
        rejects("null", "\"null\"");
        rejects("1", "\"1\"");
        rejects("true", "\"true\"");
        rejects("true", "1");
        rejects("true", "false");
        rejects("\"01\"", "\"1\"");
    }

    @Test
    public void unicodeEscapesAndStringEscapesCompareAfterJsonDecoding() {
        equivalent("\"\\u0041\\u4e2d\\ud83d\\ude42\"", "\"A中🙂\"");
        equivalent("{\"\\u0061\":\"line\\nquoted\\\"slash\\\\\"}", "{\"a\":\"line\\u000aquoted\\u0022slash\\u005c\"}");
        rejects("\"中\"", "\"文\"");
    }

    @Test
    public void duplicateKeysAndMalformedOrTrailingJsonFailWithSafeDiagnostics() {
        rejects("{\"a\":1,\"a\":2}", "{\"a\":2}");
        rejects("{\"a\":1}", "{\"a\":1,\"a\":1}");
        rejects("{\"a\":1,\"\\u0061\":2}", "{\"a\":2}");
        rejects("", "");
        rejects("  ", "null");
        rejects("{} {}", "{}");
        rejects("{}", "{} []");
        rejects("NaN", "null");
        rejects("{\"private_secret_payload\":", "null");
        rejects("{}", "\"private_secret_payload");
        rejects("01", "1");
    }

    @Test
    public void deeplyNestedJsonIsBoundedBeforeTreeParsingAndComparisonUsesNoRecursion() {
        equivalent(nested(128, "1.0"), nested(128, "1"));
        rejects(nested(2000, "1"), nested(2000, "1"));
    }

    private static String nested(int depth, String value) {
        StringBuilder result = new StringBuilder(depth * 2 + value.length());
        for (int index = 0; index < depth; index++) result.append('[');
        result.append(value);
        for (int index = 0; index < depth; index++) result.append(']');
        return result.toString();
    }

    private static void equivalent(String original, String normalized) {
        StarRocksJdbcResourceJsonValues.requireEquivalent(original, normalized, "payload");
    }

    private static void rejects(String original, String normalized) {
        try {
            StarRocksJdbcResourceJsonValues.requireEquivalent(original, normalized, "payload");
            fail("Expected JSON precision or semantic preservation failure");
        } catch (IllegalArgumentException expected) {
            assertEquals("字段 payload 的 JSON 精度或语义无法保留，请将目标字段调整为文本列，尚未清空目标表", expected.getMessage());
            assertNull("JSON/parser exceptions must not leak through a cause", expected.getCause());
            assertEquals(0, expected.getSuppressed().length);
        }
    }
}
