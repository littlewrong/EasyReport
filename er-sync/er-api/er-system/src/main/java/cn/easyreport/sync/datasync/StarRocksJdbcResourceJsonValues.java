package cn.easyreport.sync.datasync;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.util.TokenBuffer;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;

/** Compares JSON before/after a Resource scan without rounding it again inside the application. */
final class StarRocksJdbcResourceJsonValues {
    private static final int MAX_NESTING_DEPTH = 256;
    private static final ObjectMapper JSON = jsonMapper();

    private StarRocksJdbcResourceJsonValues() { }

    static void requireEquivalent(String original, String normalized, String logicalColumn) {
        if (original == null || normalized == null) {
            if (original == null && normalized == null) return; // SQL NULL, distinct from the JSON token null.
            throw invalid(logicalColumn);
        }
        try {
            Deque<NodePair> pending = new ArrayDeque<>();
            pending.push(new NodePair(parse(original, logicalColumn), parse(normalized, logicalColumn)));
            while (!pending.isEmpty()) {
                NodePair pair = pending.pop();
                JsonNode left = pair.original;
                JsonNode right = pair.normalized;
                if (left.isNumber() || right.isNumber()) {
                    if (!left.isNumber() || !right.isNumber() || left.decimalValue().compareTo(right.decimalValue()) != 0) {
                        throw invalid(logicalColumn);
                    }
                } else if (left.getNodeType() != right.getNodeType()) {
                    throw invalid(logicalColumn);
                } else if (left.isObject()) {
                    if (left.size() != right.size()) throw invalid(logicalColumn);
                    Iterator<Map.Entry<String, JsonNode>> fields = left.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> field = fields.next();
                        JsonNode other = right.get(field.getKey());
                        if (other == null) throw invalid(logicalColumn);
                        pending.push(new NodePair(field.getValue(), other));
                    }
                } else if (left.isArray()) {
                    if (left.size() != right.size()) throw invalid(logicalColumn);
                    for (int index = 0; index < left.size(); index++) pending.push(new NodePair(left.get(index), right.get(index)));
                } else if (left.isTextual()) {
                    if (!left.textValue().equals(right.textValue())) throw invalid(logicalColumn);
                } else if (left.isBoolean()) {
                    if (left.booleanValue() != right.booleanValue()) throw invalid(logicalColumn);
                } else if (!left.isNull()) {
                    throw invalid(logicalColumn);
                }
            }
        } catch (IOException | RuntimeException | StackOverflowError failure) {
            // Parser exceptions contain snippets and token locations. Never propagate those,
            // their causes, or a serialized JSON value into task diagnostics.
            throw invalid(logicalColumn);
        }
    }

    private static JsonNode parse(String text, String logicalColumn) throws IOException {
        // The project's Jackson versions predate configurable stream depth constraints.
        // Check tokens iteratively before tree materialization can recurse into deeply nested input.
        // Preserve number lexemes before readTree asks for a DOUBLE NumberType: Jackson 2.12
        // otherwise treats a valid exponent such as 1e1000 as Infinity even with USE_BIG_DECIMAL.
        try (JsonParser parser = JSON.getFactory().createParser(text);
                TokenBuffer tokens = new TokenBuffer(JSON, false)) {
            int depth = 0;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                    if (++depth > MAX_NESTING_DEPTH) throw invalid(logicalColumn);
                } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                    depth--;
                }
                if (token == JsonToken.VALUE_NUMBER_FLOAT) tokens.writeNumber(new BigDecimal(parser.getText()));
                else if (token == JsonToken.VALUE_NUMBER_INT) tokens.writeNumber(new BigInteger(parser.getText()));
                else tokens.copyCurrentEvent(parser);
            }
            try (JsonParser exact = tokens.asParser(JSON)) {
                JsonNode value = JSON.readTree(exact);
                if (value == null || value.isMissingNode()) throw invalid(logicalColumn);
                return value;
            }
        }
    }

    private static ObjectMapper jsonMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        mapper.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
        return mapper;
    }

    private static IllegalArgumentException invalid(String logicalColumn) {
        return new IllegalArgumentException("字段 " + String.valueOf(logicalColumn)
                + " 的 JSON 精度或语义无法保留，请将目标字段调整为文本列，尚未清空目标表");
    }

    private static final class NodePair {
        final JsonNode original;
        final JsonNode normalized;
        NodePair(JsonNode original, JsonNode normalized) { this.original = original; this.normalized = normalized; }
    }
}
