package cn.easyreport.sync.core;

import com.alibaba.fastjson2.JSON;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded display snapshots; table checkpoints remain in the progress table. */
public final class SyncProgressPayload {

    private static final int MAX_BYTES = 60 * 1024;
    private static final int MAX_TABLES = 100;
    private static final int MAX_MESSAGE_CODE_POINTS = 1024;

    private SyncProgressPayload() {
    }

    public static String summary(long current, long total, String message) {
        return JSON.toJSONString(summaryFields(current, total, message));
    }

    public static String withTables(long current, long total, String message,
                                    List<String> pending, List<String> completed) {
        List<String> allPending = pending == null ? Collections.emptyList() : pending;
        List<String> allCompleted = completed == null ? Collections.emptyList() : completed;
        List<String> pendingSample = new ArrayList<>(allPending.subList(0, Math.min(MAX_TABLES, allPending.size())));
        List<String> completedSample = new ArrayList<>(allCompleted.subList(
            Math.max(0, allCompleted.size() - MAX_TABLES), allCompleted.size()));
        Map<String, Object> progress = summaryFields(current, total, message);
        progress.put("pendingTables", pendingSample);
        progress.put("completedTables", completedSample);
        progress.put("pendingTableCount", allPending.size());
        progress.put("completedTableCount", allCompleted.size());

        while (true) {
            progress.put("tableListsTruncated", pendingSample.size() < allPending.size()
                || completedSample.size() < allCompleted.size());
            String payload = JSON.toJSONString(progress);
            if (payload.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES) {
                return payload;
            }
            // Keep complete names and valid JSON. Retain a pending prefix and
            // completed suffix; reducing by half avoids repeatedly encoding huge lists.
            if (!pendingSample.isEmpty() && pendingSample.size() >= completedSample.size()) {
                pendingSample.subList(pendingSample.size() / 2, pendingSample.size()).clear();
            } else if (!completedSample.isEmpty()) {
                completedSample.subList(0, (completedSample.size() + 1) / 2).clear();
            }
        }
    }

    private static Map<String, Object> summaryFields(long current, long total, String message) {
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("current", current);
        progress.put("total", total);
        int percent = 0;
        if (current > 0 && total > 0) {
            percent = current >= total ? 100 : BigInteger.valueOf(current)
                .multiply(BigInteger.valueOf(100)).divide(BigInteger.valueOf(total)).intValue();
        }
        progress.put("percent", percent);
        String text = message == null ? "" : message;
        if (text.codePointCount(0, text.length()) > MAX_MESSAGE_CODE_POINTS) {
            text = text.substring(0, text.offsetByCodePoints(0, MAX_MESSAGE_CODE_POINTS));
        }
        progress.put("message", text);
        return progress;
    }
}
