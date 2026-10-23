package cn.easyreport.sync.datasync;

import cn.easyreport.sync.domain.ErDataTransfer;
import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.mapper.ErDataTransferLogMapper;
import cn.easyreport.sync.mapper.ErDataTransferMapper;
import cn.easyreport.sync.mapper.ErDataTransferProgressMapper;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.Assert.*;

/** Exercises the real executor/factory/loop with a local JDBC driver and no database. */
public class DataSyncExecutorStopTest {
    @Test public void stopsMysqlFullQueryWithoutCompletingTableOrSavingTimestamp() throws Exception {
        assertStopped("MYSQL", "0", false);
    }

    @Test public void stopsTidbFullQueryWithoutStartingNextTable() throws Exception {
        assertStopped("TIDB", "0", false);
    }

    @Test public void stopsMysqlIncrementalQueryWithoutCompletingTableOrSavingTimestamp() throws Exception {
        assertStopped("MYSQL", "1", false);
    }

    @Test public void stopsTidbIncrementalQueryWithoutStartingNextTable() throws Exception {
        assertStopped("TIDB", "1", false);
    }

    @Test public void stopsDuringStreamingNextAndClosesBothConnections() throws Exception {
        assertStopped("TIDB", "0", true);
    }

    private void assertStopped(String sourceType, String mode, boolean blockInNext) throws Exception {
        Fixture fixture = new Fixture(blockInNext);
        BlockingDriver driver = new BlockingDriver(fixture);
        DriverManager.registerDriver(driver);
        try {
            ErDataTransfer task = new ErDataTransfer();
            task.setTransferId(42L);
            task.setSourceSchemaPattern("source");
            task.setSourceTable("first,second");
            task.setSyncMode(mode);
            task.setExternalTableEnabled("0");
            task.setSyncInsert("1");
            task.setSyncUpdate("1");
            task.setSyncDelete("0");
            task.setBatchSize(10);
            task.setTimestampField("modified_at");
            task.setLastSyncValue(String.valueOf(Timestamp.valueOf("2026-10-01 00:00:00").getTime()));
            TestExecutor executor = new TestExecutor(fixture);
            FutureTask<DataSyncExecutor.DataSyncResult> work = new FutureTask<>(() -> executor.execute(task,
                    datasource(sourceType, driver.url + "source"), datasource("STARROCKS", driver.url + "target"),
                    null, () -> false));
            Thread thread = new Thread(work, "executor-stop-test");
            thread.setDaemon(true);
            thread.start();
            assertTrue("Expected first source query to block", fixture.queryEntered.await(5, TimeUnit.SECONDS));
            executor.requestStop();
            DataSyncExecutor.DataSyncResult result = work.get(5, TimeUnit.SECONDS);
            assertTrue(result.isStopped());
            assertTrue(result.isSuccess());
            assertTrue(result.getCompletedTables().isEmpty());
            assertTrue(result.getPendingTables().containsAll(Arrays.asList("source.first", "source.second")));
            assertEquals(0, fixture.savedTimestamps.get());
            assertEquals(0, fixture.updatedLastSyncValues.get());
            assertEquals(1, fixture.dataQueries.get());
            assertEquals(1, fixture.dataCancels.get());
            assertFalse(fixture.tablesInspected.contains("second"));
            assertTrue(fixture.targetRollbacks.get() > 0);
            assertEquals(1, fixture.sourceCloses.get());
            assertEquals(1, fixture.targetCloses.get());
            assertEquals(0, fixture.targetDataWrites.get());
        } finally {
            fixture.queryReleased.countDown();
            DriverManager.deregisterDriver(driver);
        }
    }

    private static ErDatasource datasource(String type, String url) {
        ErDatasource ds = new ErDatasource();
        ds.setDatasourceType(type);
        ds.setDriverClass(BlockingDriver.class.getName());
        ds.setJdbcUrl(url);
        ds.setUsername("test");
        ds.setPassword("test");
        return ds;
    }

    private static final class TestExecutor extends DataSyncExecutor {
        TestExecutor(Fixture fixture) {
            super(proxy(ErDataTransferMapper.class, (object, method, args) -> {
                if ("updateErDataTransfer".equals(method.getName())) fixture.updatedLastSyncValues.incrementAndGet();
                return defaultValue(method.getReturnType());
            }), proxy(ErDataTransferLogMapper.class, (object, method, args) -> {
                if ("selectErDataTransferLogByTransferId".equals(method.getName())) return Collections.emptyList();
                return defaultValue(method.getReturnType());
            }), proxy(ErDataTransferProgressMapper.class, (object, method, args) -> {
                if ("selectByTransferId".equals(method.getName())) return Collections.emptyList();
                if ("upsert".equals(method.getName())) fixture.savedTimestamps.incrementAndGet();
                return defaultValue(method.getReturnType());
            }));
        }

        @Override protected void clearTable(Connection conn, String tableName) throws Exception {
            try (Statement statement = conn.createStatement()) {
                statement.execute("TRUNCATE TABLE " + tableName);
            }
        }
    }

    public static final class BlockingDriver implements Driver {
        private final Fixture fixture;
        private final String url = "jdbc:easyreport-stop-test:" + UUID.randomUUID() + ":";

        BlockingDriver(Fixture fixture) { this.fixture = fixture; }
        @Override public Connection connect(String requested, Properties info) {
            return acceptsURL(requested) ? fixture.connection(requested.endsWith("source")) : null;
        }
        @Override public boolean acceptsURL(String requested) { return requested != null && requested.startsWith(url); }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
        @Override public int getMajorVersion() { return 1; }
        @Override public int getMinorVersion() { return 0; }
        @Override public boolean jdbcCompliant() { return false; }
        @Override public Logger getParentLogger() { return Logger.getLogger("executor-stop-test"); }
    }

    private static final class Fixture {
        final boolean blockInNext;
        final CountDownLatch queryEntered = new CountDownLatch(1);
        final CountDownLatch queryReleased = new CountDownLatch(1);
        final AtomicInteger dataQueries = new AtomicInteger();
        final AtomicInteger dataCancels = new AtomicInteger();
        final AtomicInteger targetRollbacks = new AtomicInteger();
        final AtomicInteger sourceCloses = new AtomicInteger();
        final AtomicInteger targetCloses = new AtomicInteger();
        final AtomicInteger savedTimestamps = new AtomicInteger();
        final AtomicInteger updatedLastSyncValues = new AtomicInteger();
        final AtomicInteger targetDataWrites = new AtomicInteger();
        final List<String> tablesInspected = new ArrayList<>();

        Fixture(boolean blockInNext) { this.blockInNext = blockInNext; }

        Connection connection(boolean source) {
            return proxy(Connection.class, (object, method, args) -> {
                String name = method.getName();
                if ("createStatement".equals(name)) return statement(source, "");
                if ("prepareStatement".equals(name)) return statement(source, (String) args[0]);
                if ("isValid".equals(name)) return true;
                if ("isClosed".equals(name)) return false;
                if ("getCatalog".equals(name)) return "source";
                if ("rollback".equals(name) && !source) targetRollbacks.incrementAndGet();
                if ("close".equals(name)) (source ? sourceCloses : targetCloses).incrementAndGet();
                return defaultValue(method.getReturnType());
            });
        }

        PreparedStatement statement(boolean source, String sql) {
            boolean data = source && sql.startsWith("SELECT *");
            List<Object> parameters = new ArrayList<>();
            return proxy(PreparedStatement.class, (object, method, args) -> {
                String name = method.getName();
                if (name.startsWith("set") && args != null && args.length > 1 && args[0] instanceof Integer) {
                    int index = (Integer) args[0];
                    while (parameters.size() < index) parameters.add(null);
                    parameters.set(index - 1, args[1]);
                }
                if ("cancel".equals(name) && data) {
                    dataCancels.incrementAndGet();
                    queryReleased.countDown();
                }
                if (name.startsWith("execute") && !source) {
                    String executed = sql.isEmpty() && args != null && args.length > 0 ? String.valueOf(args[0]) : sql;
                    if (executed.startsWith("INSERT") || executed.startsWith("UPDATE")) targetDataWrites.incrementAndGet();
                }
                if ("executeQuery".equals(name)) {
                    if (data) {
                        dataQueries.incrementAndGet();
                        if (!blockInNext) blockUntilCancelled();
                        return dataRows();
                    }
                    if (sql.contains("information_schema.tables")) {
                        return rows(Arrays.asList(new Object[]{"source", "first"}, new Object[]{"source", "second"}));
                    }
                    if (sql.contains("information_schema.columns")) {
                        tablesInspected.add(String.valueOf(parameters.get(1)));
                        return rows(Arrays.asList(new Object[]{"id"}, new Object[]{"modified_at"}));
                    }
                    if (sql.contains("information_schema.key_column_usage")) {
                        return rows(Collections.singletonList(new Object[]{"id"}));
                    }
                    if (sql.startsWith("SELECT MIN(")) {
                        Timestamp timestamp = Timestamp.valueOf("2026-10-01 10:00:00");
                        return rows(Collections.singletonList(new Object[]{timestamp, timestamp}));
                    }
                    throw new AssertionError("Unexpected local test SQL: " + sql);
                }
                return defaultValue(method.getReturnType());
            });
        }

        void blockUntilCancelled() throws Exception {
            queryEntered.countDown();
            if (!queryReleased.await(5, TimeUnit.SECONDS)) throw new SQLException("Local test query was not cancelled");
            throw new SQLException("Local test query cancelled", "57014");
        }

        ResultSet dataRows() {
            return proxy(ResultSet.class, (object, method, args) -> {
                if ("next".equals(method.getName())) blockUntilCancelled();
                if ("findColumn".equals(method.getName())) return "id".equals(args[0]) ? 1 : 2;
                if ("getMetaData".equals(method.getName())) {
                    return proxy(ResultSetMetaData.class, (metadata, query, values) -> {
                        if ("getColumnTypeName".equals(query.getName())) return (Integer) values[0] == 1 ? "BIGINT" : "TIMESTAMP";
                        return defaultValue(query.getReturnType());
                    });
                }
                return defaultValue(method.getReturnType());
            });
        }
    }

    private static ResultSet rows(List<Object[]> values) {
        AtomicInteger index = new AtomicInteger(-1);
        return proxy(ResultSet.class, (object, method, args) -> {
            String name = method.getName();
            if ("next".equals(name)) return index.incrementAndGet() < values.size();
            if ("getString".equals(name) || "getTimestamp".equals(name)) {
                int column = args[0] instanceof Integer ? (Integer) args[0]
                        : "table_schema".equals(args[0]) ? 1 : 2;
                Object value = values.get(index.get())[column - 1];
                return "getString".equals(name) ? String.valueOf(value) : value;
            }
            return defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(DataSyncExecutorStopTest.class.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
}
