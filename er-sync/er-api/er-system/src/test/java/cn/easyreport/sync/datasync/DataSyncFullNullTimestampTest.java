package cn.easyreport.sync.datasync;

import cn.easyreport.sync.core.SyncProgressCallback;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class DataSyncFullNullTimestampTest {
    private static final List<String> COLUMNS = Arrays.asList("id", "modified_at");
    private static final List<String> KEYS = Collections.singletonList("id");

    @Test public void nullTimestampBoundsSkipWithoutFallingBackToFullQuery() throws Exception {
        assertMissingBoundsSkipped(true);
    }

    @Test public void emptyTimestampBoundsSkipWithoutReadingRows() throws Exception {
        assertMissingBoundsSkipped(false);
    }

    @Test public void bufferedFullImportSkipsOnlyRowsWithRawNullTimestamp() throws Exception {
        assertMixedRowsFiltered(true);
    }

    @Test public void jdbcBatchFullImportSkipsNullBeforeBindingOrAddingBatch() throws Exception {
        assertMixedRowsFiltered(false);
    }

    @Test public void bufferedFullImportWithoutConfiguredTimestampKeepsEveryRow() throws Exception {
        assertUnconfiguredTimestampKeepsRows(true);
    }

    @Test public void jdbcBatchFullImportWithoutConfiguredTimestampKeepsEveryRow() throws Exception {
        assertUnconfiguredTimestampKeepsRows(false);
    }

    @Test public void nonNullTimestampRetainedEvenWhenConverterCannotUseItAsCheckpoint() throws Exception {
        for (boolean buffered : new boolean[]{true, false}) {
            Timestamp future = Timestamp.valueOf("2042-01-01 00:00:00");
            Fixture fixture = new Fixture(Collections.singletonList(new Object[]{7, future}));
            try (TestExecutor executor = new TestExecutor(buffered, fixture)) {
                DataSyncExecutor.FullSyncStats stats = query(executor, fixture, "modified_at");
                assertEquals(1L, stats.processed);
                assertNull(stats.maxTs);
                assertEquals(Collections.singletonList(7), fixture.writtenIds());
                assertEquals("2042-01-01 00:00:00", fixture.writtenRows.get(0)[1]);
            }
        }
    }

    private void assertMissingBoundsSkipped(boolean boundsRowExists) throws Exception {
        Fixture fixture = new Fixture(Collections.emptyList());
        fixture.boundsRowExists = boundsRowExists;
        List<String> messages = new ArrayList<>();
        SyncProgressCallback progress = (current, total, message) -> {
            assertEquals(0, current);
            assertEquals(0, total);
            messages.add(message);
        };
        DataSyncExtractor extractor = new MySqlDataSyncExtractor() {
            @Override public String buildSelectTimestampBounds(String schema, String table, String field) { return "BOUNDS"; }
            @Override public String buildSelectFull(String schema, String table, String field, List<String> keys) {
                throw new AssertionError("All-NULL bounds must not fall back to a full-table query");
            }
            @Override public String buildSelectFullWindow(String schema, String table, String field, List<String> keys) {
                throw new AssertionError("All-NULL bounds must not query time windows");
            }
        };
        try (TestExecutor executor = new TestExecutor(true, fixture)) {
            DataSyncExecutor.FullSyncStats stats = (DataSyncExecutor.FullSyncStats) invoke(executor,
                    "syncFullByTimestampWindow", "source", "orders", "target.orders", COLUMNS, COLUMNS, KEYS,
                    KEYS, extractor, fixture.sourceConnection(), fixture.targetConnection(), fixture.insertStatement(),
                    2, "modified_at", progress, "source.orders");
            assertEquals(0L, stats.processed);
            assertNull(stats.maxTs);
            assertEquals(Collections.singletonList("BOUNDS"), fixture.queries);
            assertTrue(fixture.writtenRows.isEmpty());
            assertEquals(0, fixture.commits);
            assertEquals(1, messages.size());
            assertTrue(messages.get(0).contains("跳过初始化"));
        }
    }

    private void assertMixedRowsFiltered(boolean buffered) throws Exception {
        Fixture fixture = new Fixture(mixedRows());
        try (TestExecutor executor = new TestExecutor(buffered, fixture)) {
            DataSyncExecutor.FullSyncStats stats = query(executor, fixture, "modified_at");
            assertEquals(3L, stats.processed);
            assertEquals(Timestamp.valueOf("2026-10-03 10:00:00"), stats.maxTs);
            assertEquals(Arrays.asList(2, 4, 5), fixture.writtenIds());
            assertEquals(0, fixture.nullRowsReadBeyondTimestamp);
            assertEquals(2, fixture.commits);
            if (!buffered) assertEquals("Only accepted rows may bind parameters", 6, fixture.parameterBindings);
        }
    }

    private void assertUnconfiguredTimestampKeepsRows(boolean buffered) throws Exception {
        for (String timestampField : new String[]{null, "", "   "}) {
            Fixture fixture = new Fixture(mixedRows());
            try (TestExecutor executor = new TestExecutor(buffered, fixture)) {
                DataSyncExecutor.FullSyncStats stats = query(executor, fixture, timestampField);
                assertEquals(5L, stats.processed);
                assertNull(stats.maxTs);
                assertEquals(Arrays.asList(1, 2, 3, 4, 5), fixture.writtenIds());
                assertEquals(3, fixture.commits);
            }
        }
    }

    private DataSyncExecutor.FullSyncStats query(TestExecutor executor, Fixture fixture, String field) throws Exception {
        return (DataSyncExecutor.FullSyncStats) invoke(executor, "syncFullQuery", "FULL", null,
                "target.orders", COLUMNS, COLUMNS, KEYS, fixture.sourceConnection(), fixture.targetConnection(),
                fixture.insertStatement(), 2, field, null, "source.orders", "Full sync", new DataSyncExecutor.FullSyncStats());
    }

    private static Object invoke(DataSyncExecutor executor, String name, Object... arguments) throws Exception {
        for (Method method : DataSyncExecutor.class.getDeclaredMethods()) {
            if (name.equals(method.getName())) {
                method.setAccessible(true);
                try {
                    return method.invoke(executor, arguments);
                } catch (InvocationTargetException error) {
                    if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
                    throw (Error) error.getCause();
                }
            }
        }
        throw new AssertionError("Missing executor method " + name);
    }

    private static List<Object[]> mixedRows() {
        return Arrays.asList(new Object[]{1, null}, new Object[]{2, Timestamp.valueOf("2026-10-01 10:00:00")},
                new Object[]{3, null}, new Object[]{4, Timestamp.valueOf("2026-10-02 10:00:00")},
                new Object[]{5, Timestamp.valueOf("2026-10-03 10:00:00")});
    }

    private static final class TestExecutor extends DataSyncExecutor implements AutoCloseable {
        final boolean buffered;
        final Fixture fixture;

        TestExecutor(boolean buffered, Fixture fixture) {
            super(null, null, null);
            this.buffered = buffered;
            this.fixture = fixture;
        }
        @Override protected void clearTable(Connection conn, String name) { throw new AssertionError("Unexpected clear"); }
        @Override protected boolean useBufferedFullInsert() { return buffered; }
        @Override protected void executeBufferedFullInsert(Connection conn, List<Object[]> rows, String table,
                                                         List<String> columns, List<String> primaryKeys) {
            for (Object[] row : rows) fixture.writtenRows.add(row.clone());
        }
        @Override public void close() { cancellationContext().close(); }
    }

    private static final class Fixture {
        final List<Object[]> sourceRows;
        final List<Object[]> writtenRows = new ArrayList<>();
        final List<String> queries = new ArrayList<>();
        boolean boundsRowExists = true;
        int commits;
        int parameterBindings;
        int nullRowsReadBeyondTimestamp;

        Fixture(List<Object[]> rows) { sourceRows = rows; }

        Connection sourceConnection() {
            return proxy(Connection.class, (object, method, args) -> {
                if ("prepareStatement".equals(method.getName())) {
                    String sql = (String) args[0];
                    queries.add(sql);
                    return proxy(PreparedStatement.class, (statement, query, values) -> {
                        if ("executeQuery".equals(query.getName())) return "BOUNDS".equals(sql) ? bounds() : rows();
                        return defaultValue(query.getReturnType());
                    });
                }
                return defaultValue(method.getReturnType());
            });
        }

        Connection targetConnection() {
            return proxy(Connection.class, (object, method, args) -> {
                if ("commit".equals(method.getName())) commits++;
                return defaultValue(method.getReturnType());
            });
        }

        PreparedStatement insertStatement() {
            Object[] parameters = new Object[2];
            List<Object[]> batch = new ArrayList<>();
            return proxy(PreparedStatement.class, (object, method, args) -> {
                if ("setObject".equals(method.getName())) {
                    parameterBindings++;
                    parameters[(Integer) args[0] - 1] = args[1];
                }
                if ("addBatch".equals(method.getName())) batch.add(parameters.clone());
                if ("executeBatch".equals(method.getName())) {
                    int size = batch.size();
                    writtenRows.addAll(batch);
                    batch.clear();
                    return new int[size];
                }
                return defaultValue(method.getReturnType());
            });
        }

        ResultSet bounds() {
            AtomicInteger row = new AtomicInteger();
            return proxy(ResultSet.class, (object, method, args) -> {
                if ("next".equals(method.getName())) return boundsRowExists && row.getAndIncrement() == 0;
                if ("getTimestamp".equals(method.getName())) return null;
                return defaultValue(method.getReturnType());
            });
        }

        ResultSet rows() {
            AtomicInteger row = new AtomicInteger(-1);
            return proxy(ResultSet.class, (object, method, args) -> {
                String name = method.getName();
                if ("next".equals(name)) return row.incrementAndGet() < sourceRows.size();
                if ("findColumn".equals(name)) return "id".equals(args[0]) ? 1 : 2;
                if ("getObject".equals(name)) {
                    int column = "id".equals(args[0]) ? 0 : 1;
                    Object[] values = sourceRows.get(row.get());
                    if (column == 0 && values[1] == null) nullRowsReadBeyondTimestamp++;
                    return values[column];
                }
                if ("getMetaData".equals(name)) {
                    return proxy(ResultSetMetaData.class, (metadata, query, values) -> {
                        if ("getColumnTypeName".equals(query.getName())) return (Integer) values[0] == 1 ? "BIGINT" : "TIMESTAMP";
                        return defaultValue(query.getReturnType());
                    });
                }
                return defaultValue(method.getReturnType());
            });
        }

        List<Integer> writtenIds() {
            List<Integer> ids = new ArrayList<>();
            for (Object[] row : writtenRows) ids.add((Integer) row[0]);
            return ids;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(DataSyncFullNullTimestampTest.class.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
}
