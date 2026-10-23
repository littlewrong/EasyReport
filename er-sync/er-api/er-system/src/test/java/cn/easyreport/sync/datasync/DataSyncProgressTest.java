package cn.easyreport.sync.datasync;

import cn.easyreport.sync.domain.ErDataTransfer;
import cn.easyreport.sync.mapper.ErDataTransferLogMapper;
import cn.easyreport.sync.mapper.ErDataTransferMapper;
import cn.easyreport.sync.mapper.ErDataTransferProgressMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class DataSyncProgressTest {
    @Test
    public void persistsBoundedPayloadWithFullCountsAndOrderedSamples() throws Exception {
        RecordingMapper mapper = new RecordingMapper();
        TestExecutor executor = new TestExecutor(mapper.proxy());
        List<String> pending = names("pending", 5001);
        List<String> completed = names("completed", 6001);

        update(executor, 42L, 6001, 11002, "同步订单中",
                Collections.unmodifiableList(pending), Collections.unmodifiableList(completed));

        assertEquals(1, mapper.attempts.size());
        assertEquals(1, mapper.successfulWrites);
        ErDataTransfer saved = mapper.attempts.get(0);
        assertEquals(Long.valueOf(42L), saved.getTransferId());
        assertTrue(saved.getSyncProgress().getBytes(StandardCharsets.UTF_8).length <= 60 * 1024);
        JSONObject payload = JSON.parseObject(saved.getSyncProgress());
        assertEquals(6001, payload.getIntValue("current"));
        assertEquals(11002, payload.getIntValue("total"));
        assertEquals("同步订单中", payload.getString("message"));
        assertEquals(5001, payload.getIntValue("pendingTableCount"));
        assertEquals(6001, payload.getIntValue("completedTableCount"));
        assertTrue(payload.getBooleanValue("tableListsTruncated"));
        assertEquals(pending.subList(0, 100), payload.getJSONArray("pendingTables").toJavaList(String.class));
        assertEquals(completed.subList(completed.size() - 100, completed.size()),
                payload.getJSONArray("completedTables").toJavaList(String.class));
        assertEquals(5001, pending.size());
        assertEquals(6001, completed.size());
    }

    @Test
    public void mapperFailureDoesNotEscapeProgressCallbackAndLaterWritesStillWork() throws Exception {
        RecordingMapper mapper = new RecordingMapper();
        mapper.failure = new IllegalStateException("progress storage unavailable");
        TestExecutor executor = new TestExecutor(mapper.proxy());

        update(executor, 42L, 1, 2, "first", Collections.singletonList("erp.orders"), Collections.emptyList());
        assertEquals(1, mapper.attempts.size());
        assertEquals(0, mapper.successfulWrites);
        mapper.failure = null;
        update(executor, 42L, 2, 2, "done", Collections.emptyList(), Collections.singletonList("erp.orders"));

        assertEquals(2, mapper.attempts.size());
        assertEquals(1, mapper.successfulWrites);
        assertEquals("done", JSON.parseObject(mapper.attempts.get(1).getSyncProgress()).getString("message"));
    }

    @Test
    public void nullTransferIdSkipsPersistence() throws Exception {
        RecordingMapper mapper = new RecordingMapper();
        mapper.failure = new IllegalStateException("Mapper must not be called");

        update(new TestExecutor(mapper.proxy()), null, 0, 0, "starting", null, null);

        assertTrue(mapper.attempts.isEmpty());
    }

    private void update(DataSyncExecutor executor, Long id, int current, int total, String message,
                        List<String> pending, List<String> completed) throws Exception {
        Method method = DataSyncExecutor.class.getDeclaredMethod("updateProgressWithTables", Long.class,
                int.class, int.class, String.class, List.class, List.class);
        method.setAccessible(true);
        method.invoke(executor, id, current, total, message, pending, completed);
    }

    private List<String> names(String prefix, int count) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(prefix + "_" + i + ".订单_quality_产品字段_abcdefghijklmnop_" + i);
        return result;
    }

    private static class RecordingMapper {
        final List<ErDataTransfer> attempts = new ArrayList<>();
        RuntimeException failure;
        int successfulWrites;

        ErDataTransferMapper proxy() {
            return (ErDataTransferMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ErDataTransferMapper.class}, (object, method, args) -> {
                        if (!"updateTransferProgress".equals(method.getName())) throw new AssertionError(method.getName());
                        attempts.add((ErDataTransfer) args[0]);
                        if (failure != null) throw failure;
                        successfulWrites++;
                        return 1;
                    });
        }
    }

    private static <T> T unusedMapper(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, method, args) -> { throw new AssertionError("Unexpected mapper call: " + method.getName()); }));
    }

    private static class TestExecutor extends DataSyncExecutor {
        TestExecutor(ErDataTransferMapper mapper) {
            super(mapper, unusedMapper(ErDataTransferLogMapper.class), unusedMapper(ErDataTransferProgressMapper.class));
        }

        @Override
        protected void clearTable(Connection conn, String tableName) {
            throw new AssertionError("No data synchronization should run in a progress test");
        }
    }
}
