package cn.easyreport.sync.datasync;

import cn.easyreport.sync.core.SyncProgressCallback;
import cn.easyreport.sync.domain.ErDataTransfer;
import cn.easyreport.sync.domain.ErDataTransferLog;
import cn.easyreport.sync.domain.ErDataTransferProgress;
import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.mapper.ErDataTransferLogMapper;
import cn.easyreport.sync.mapper.ErDataTransferMapper;
import cn.easyreport.sync.mapper.ErDataTransferProgressMapper;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
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

import static org.junit.Assert.*;

/** Exercises the shared full-import lifecycle without connecting to a database. */
public class StarRocksExternalTableImportIntegrationTest {
    private static final Timestamp MAX_TS = Timestamp.valueOf("2026-10-21 23:15:00");
    private static final List<String> COLUMNS = Arrays.asList("id", "time_stamp");

    @Test
    public void directImportPreparesBeforeClearAndPreservesTableLogAndCheckpoint() throws Exception {
        Fixture f = new Fixture();

        Object result = f.run();

        assertTrue((Boolean) field(result, "success"));
        assertEquals(MAX_TS, field(result, "maxTs"));
        ErDataTransferLog log = (ErDataTransferLog) field(result, "log");
        assertEquals(Long.valueOf(42), log.getTransferId());
        assertEquals("erp_01.orders", log.getSourceTable());
        assertEquals("`warehouse`.`orders`", log.getTargetTable());
        assertEquals("FULL", log.getSyncAction());
        assertEquals("0", log.getSyncMode());
        assertEquals("0", log.getSyncResult());
        assertEquals(Long.valueOf(17), log.getTotalCount());
        assertEquals(Long.valueOf(17), log.getSuccessCount());
        assertEquals(Long.valueOf(0), log.getFailCount());
        assertEquals(String.valueOf(MAX_TS.getTime()), log.getLastSyncValue());
        assertNotNull(log.getStartTime());
        assertEquals(1, f.checkpoints.size());
        ErDataTransferProgress saved = f.checkpoints.get(0);
        assertEquals(Long.valueOf(42), saved.getTransferId());
        assertEquals("erp_01.orders", saved.getSourceTable());
        assertEquals(String.valueOf(MAX_TS.getTime()), saved.getLastSyncValue());
        assertBefore(f.events, "prepare", "truncate");
        assertBefore(f.events, "truncate", "execute");
        assertBefore(f.events, "before", "execute");
        assertBefore(f.events, "execute", "checkpoint");
        assertBefore(f.events, "execute", "after");
        assertBefore(f.events, "execute", "close-direct");
        assertBefore(f.events, "close-direct", "checkpoint");
        assertEquals(1, Collections.frequency(f.events, "execute"));
        assertEquals(1, Collections.frequency(f.events, "close-direct"));
        assertEquals(0, f.sourceReads);
        assertFalse(f.events.contains("rollback"));
        assertSame(f.sourceDs, f.preparedSource);
    }

    @Test
    public void prepareFailureLeavesExistingTargetAndCheckpointUntouched() throws Exception {
        Fixture f = new Fixture();
        SQLException failure = new SQLException("external table cannot read source");
        f.prepareFailure = failure;

        assertSame(failure, f.failure());

        assertEquals(Collections.singletonList("prepare"), f.events);
        assertTrue(f.checkpoints.isEmpty());
        assertEquals(0, f.sourceReads);
    }

    @Test
    public void directExecutionFailureClosesImportAndDoesNotAdvanceCheckpoint() throws Exception {
        Fixture f = new Fixture();
        SQLException failure = new SQLException("yearly INSERT SELECT failed");
        f.executeFailure = failure;

        assertSame(failure, f.failure());

        assertBefore(f.events, "prepare", "truncate");
        assertBefore(f.events, "truncate", "execute");
        assertBefore(f.events, "execute", "rollback");
        assertBefore(f.events, "execute", "after");
        assertBefore(f.events, "execute", "close-direct");
        assertEquals(1, Collections.frequency(f.events, "close-direct"));
        assertTrue(f.checkpoints.isEmpty());
        assertEquals(0, f.sourceReads);
    }

    @Test
    public void targetClearFailureStillReleasesPreparedExternalImport() throws Exception {
        Fixture f = new Fixture();
        SQLException failure = new SQLException("target truncate denied");
        f.clearFailure = failure;

        assertSame(failure, f.failure());

        assertBefore(f.events, "prepare", "truncate");
        assertBefore(f.events, "truncate", "close-direct");
        assertFalse(f.events.contains("execute"));
        assertTrue(f.checkpoints.isEmpty());
        assertEquals(1, Collections.frequency(f.events, "close-direct"));
    }

    @Test
    public void cleanupFailureDoesNotReplaceTheImportFailure() throws Exception {
        Fixture f = new Fixture();
        SQLException execution = new SQLException("yearly import failed");
        SQLException cleanup = new SQLException("temporary table cleanup failed");
        f.executeFailure = execution;
        f.closeFailure = cleanup;

        Throwable actual = f.failure();

        assertSame(execution, actual);
        assertArrayEquals(new Throwable[]{cleanup}, actual.getSuppressed());
        assertTrue(f.checkpoints.isEmpty());
        assertEquals(1, Collections.frequency(f.events, "close-direct"));
    }

    @Test
    public void cleanupFailureAfterSuccessfulImportDoesNotAdvanceCheckpoint() throws Exception {
        Fixture f = new Fixture();
        SQLException failure = new SQLException("temporary table cleanup failed");
        f.closeFailure = failure;

        assertSame(failure, f.failure());

        assertBefore(f.events, "execute", "close-direct");
        assertTrue(f.events.contains("after"));
        assertFalse(f.events.contains("checkpoint"));
        assertTrue(f.checkpoints.isEmpty());
        assertEquals(1, Collections.frequency(f.events, "execute"));
        assertEquals(1, Collections.frequency(f.events, "close-direct"));
    }

    @Test
    public void stopAfterPrepareCleansDirectImportBeforeClearingDestination() throws Exception {
        Fixture f = new Fixture();
        f.stopAfterPrepare = true;

        assertTrue(f.failure() instanceof DataSyncCancellation.SyncStoppedException);

        assertFalse(f.events.contains("truncate"));
        assertFalse(f.events.contains("execute"));
        assertTrue(f.checkpoints.isEmpty());
        assertEquals(1, Collections.frequency(f.events, "close-direct"));
    }

    @Test
    public void stopAsDirectImportReturnsPreventsCheckpointAndKeepsCleanup() throws Exception {
        Fixture f = new Fixture();
        f.stopAfterExecute = true;

        assertTrue(f.failure() instanceof DataSyncCancellation.SyncStoppedException);

        assertTrue(f.events.contains("execute"));
        assertTrue(f.events.contains("after"));
        assertTrue(f.checkpoints.isEmpty());
        assertEquals(1, Collections.frequency(f.events, "close-direct"));
    }

    @Test
    public void stopDuringDirectImportCleanupPreventsCheckpoint() throws Exception {
        Fixture f = new Fixture();
        f.stopOnClose = true;

        assertTrue(f.failure() instanceof DataSyncCancellation.SyncStoppedException);

        assertTrue(f.checkpoints.isEmpty());
        assertEquals(1, Collections.frequency(f.events, "close-direct"));
    }

    @Test
    public void nullDirectImportRetainsJdbcFallbackAndValueTransfer() throws Exception {
        Fixture f = new Fixture();
        f.useDirectImport = false;

        Object result = f.run();

        assertTrue((Boolean) field(result, "success"));
        ErDataTransferLog log = (ErDataTransferLog) field(result, "log");
        assertEquals(Long.valueOf(1), log.getTotalCount());
        assertEquals(Long.valueOf(1), log.getSuccessCount());
        assertEquals(MAX_TS, field(result, "maxTs"));
        assertEquals(1, f.sourceReads);
        assertEquals(1, f.writtenRows.size());
        assertArrayEquals(new Object[]{7L, MAX_TS}, f.writtenRows.get(0));
        assertEquals(1, f.checkpoints.size());
        assertBefore(f.events, "prepare", "truncate");
        assertBefore(f.events, "truncate", "jdbc-read");
        assertBefore(f.events, "jdbc-write", "checkpoint");
        assertTrue(f.events.contains("before"));
        assertTrue(f.events.contains("after"));
        assertFalse(f.events.contains("execute"));
        assertFalse(f.events.contains("close-direct"));
    }

    @Test
    public void emptyDirectImportCompletesWithoutInventingTimestampCheckpoint() throws Exception {
        Fixture f = new Fixture();
        f.processed = 0;
        f.maxTs = null;

        Object result = f.run();

        ErDataTransferLog log = (ErDataTransferLog) field(result, "log");
        assertTrue((Boolean) field(result, "success"));
        assertEquals("0", log.getSyncResult());
        assertEquals(Long.valueOf(0), log.getTotalCount());
        assertNull(log.getLastSyncValue());
        assertNull(field(result, "maxTs"));
        assertTrue(f.checkpoints.isEmpty());
        assertTrue("zero imported rows retain initialization's existing target clear", f.events.contains("truncate"));
        assertEquals(1, Collections.frequency(f.events, "close-direct"));
    }


    private static final List<String> RESOURCE_SOURCES = Arrays.asList("MYSQL", "TIDB", "STARROCKS", "KINGBASE", "DM",
            "SQLSERVER", "POSTGRESQL", "ORACLE");

    @Test
    public void disabledAndIncrementalTasksRetainApplicationJdbcWithoutInspectingResource() throws Exception {
        for (String type : RESOURCE_SOURCES) {
            for (String[] config : new String[][] {{null, "0"}, {"0", "0"}, {"1", "1"}}) {
                ErDataTransfer task = task(config[0], config[1], ErDataTransfer.DIRECT_IMPORT_JDBC_RESOURCE);
                assertStaysOnJdbcPath(task, datasource(type), DataSyncFactory.buildExtractor(datasource(type)));
            }
        }
    }

    @Test
    public void missingTaskOrModeCannotStartDirectImport() throws Exception {
        assertStaysOnJdbcPath(null, datasource("MYSQL"), new MySqlDataSyncExtractor());
        assertStaysOnJdbcPath(task("1", null, ErDataTransfer.DIRECT_IMPORT_JDBC_RESOURCE),
                datasource("MYSQL"), new MySqlDataSyncExtractor());
    }

    @Test
    public void legacyExternalTasksMustSupplyResourceInsteadOfSelectingMysqlEngineOrFallback() throws Exception {
        for (String type : RESOURCE_SOURCES) {
            for (String method : Arrays.asList(null, ErDataTransfer.DIRECT_IMPORT_EXTERNAL_TABLE)) {
                ErDataTransfer task = task("1", "0", method);
                if (method != null) task.setCatalogName("leftover_catalog");
                List<String> messages = new ArrayList<>();
                StarRocksDataSyncExecutor executor = new StarRocksDataSyncExecutor(null, null, null);
                Connection unused = unusedConnection();
                try {
                    executor.prepareDirectFullImport(task, datasource(type), "erp_base", "orders",
                            "`warehouse`.`orders`", COLUMNS, COLUMNS, DataSyncFactory.buildExtractor(datasource(type)),
                            unused, unused, "time_stamp", (current, total, message) -> messages.add(message));
                    fail("Legacy task must request a prebuilt Resource");
                } catch (IllegalArgumentException expected) {
                    assertTrue(expected.getMessage(), expected.getMessage().contains("Resource"));
                    assertTrue(expected.getMessage(), expected.getMessage().contains("旧外部表任务"));
                }
                assertTrue("Missing Resource never chooses a different import path", messages.isEmpty());
            }
        }
    }

    @Test
    public void resourceWhiteListRejectsOtherSourcesOrMismatchedExtractorsBeforeConnections() throws Exception {
        assertRejectedResourceSource(datasource("UNSUPPORTED"), new FallbackExtractor());
        assertRejectedResourceSource(datasource("MYSQL"), new TiDbDataSyncExtractor());
        assertRejectedResourceSource(datasource("TIDB"), new MySqlDataSyncExtractor());
        assertRejectedResourceSource(datasource("STARROCKS"), new MySqlDataSyncExtractor());
        assertRejectedResourceSource(datasource("DM"), new KingbaseDataSyncExtractor());
        assertRejectedResourceSource(datasource("SQLSERVER"), new PostgreSqlDataSyncExtractor());
        assertRejectedResourceSource(datasource("POSTGRESQL"), new OracleDataSyncExtractor());
        assertRejectedResourceSource(datasource("ORACLE"), new SqlServerDataSyncExtractor());
        assertRejectedResourceSource(null, null);
    }

    @Test
    public void allEightSourcesReachResourceValidationAndNeverFallbackOnResourceFailure() throws Exception {
        for (String type : RESOURCE_SOURCES) {
            ErDataTransfer task = task("1", "0", ErDataTransfer.DIRECT_IMPORT_JDBC_RESOURCE);
            task.setResourceName("jdbc_prebuilt");
            task.setCatalogName("leftover_catalog");
            List<String> sql = new ArrayList<>();
            Connection target = proxy(Connection.class, (method, args) -> {
                if ("createStatement".equals(method.getName())) return proxy(Statement.class, (call, values) -> {
                    if ("close".equals(call.getName())) return null;
                    assertEquals("executeQuery", call.getName());
                    sql.add((String) values[0]);
                    assertEquals("SHOW RESOURCES", values[0]);
                    throw new SQLException("driver-secret-that-must-not-leak", "42000", 1044);
                });
                throw new AssertionError(method.getName());
            });
            List<String> messages = new ArrayList<>();
            try {
                new StarRocksDataSyncExecutor(null, null, null).prepareDirectFullImport(task, datasource(type),
                        "db", "orders", "`warehouse`.`orders`", COLUMNS, COLUMNS,
                        DataSyncFactory.buildExtractor(datasource(type)), unusedConnection(), target,
                        "time_stamp", (current, total, message) -> messages.add(message));
                fail("Resource failure must propagate for " + type);
            } catch (SQLException expected) {
                assertTrue(expected.getMessage().contains("Resource"));
                assertFalse(expected.getMessage().contains("driver-secret"));
            }
            assertEquals(Collections.singletonList("SHOW RESOURCES"), sql);
            assertTrue(messages.isEmpty());
        }
    }

    @Test
    public void resourceSchemaPreservationOnlyAppliesToEnabledInitializationAcrossEightSources() throws Exception {
        for (String type : RESOURCE_SOURCES) {
            for (String[] config : new String[][] {
                    {"1", "0", ErDataTransfer.DIRECT_IMPORT_JDBC_RESOURCE, "0"},
                    {"1", "0", ErDataTransfer.DIRECT_IMPORT_EXTERNAL_TABLE, "0"},
                    {"1", "0", ErDataTransfer.DIRECT_IMPORT_JDBC_CATALOG, "1"},
                    {"1", "1", ErDataTransfer.DIRECT_IMPORT_JDBC_RESOURCE, "1"},
                    {"0", "0", ErDataTransfer.DIRECT_IMPORT_JDBC_RESOURCE, "1"}}) {
                int[] calls = {0};
                StarRocksDataSyncExecutor executor = new StarRocksDataSyncExecutor(null, null, null) {
                    @Override protected void prepareTargetSchema(Connection source, Connection target,
                            String schema, String table, String destination) { calls[0]++; }
                };
                ErDataTransfer task = task(config[0], config[1], config[2]);
                executor.prepareTargetSchema(task, datasource(type), unusedConnection(), unusedConnection(),
                        "db", "orders", "`warehouse`.`orders`");
                assertEquals(type + " " + Arrays.toString(config), Integer.parseInt(config[3]), calls[0]);
            }
        }
    }

    @Test
    public void stoppedResourceImportsDoNotTouchAnyConnectionOrReportFallback() throws Exception {
        for (String type : RESOURCE_SOURCES) {
            StarRocksDataSyncExecutor executor = new StarRocksDataSyncExecutor(null, null, null);
            executor.requestStop();
            ErDataTransfer task = task("1", "0", ErDataTransfer.DIRECT_IMPORT_JDBC_RESOURCE);
            task.setResourceName("jdbc_prebuilt");
            try {
                executor.prepareDirectFullImport(task, datasource(type), "db", "orders", "`warehouse`.`orders`",
                        COLUMNS, COLUMNS, DataSyncFactory.buildExtractor(datasource(type)), unusedConnection(),
                        unusedConnection(), "time_stamp", null);
                fail("Expected stop");
            } catch (DataSyncCancellation.SyncStoppedException expected) {
            } finally { executor.cancellationContext().close(); }
        }
    }

    private static void assertRejectedResourceSource(ErDatasource source, DataSyncExtractor extractor) throws Exception {
        ErDataTransfer task = task("1", "0", ErDataTransfer.DIRECT_IMPORT_JDBC_RESOURCE);
        task.setResourceName("jdbc_prebuilt");
        try {
            new StarRocksDataSyncExecutor(null, null, null).prepareDirectFullImport(task, source, "db", "orders",
                    "`warehouse`.`orders`", COLUMNS, COLUMNS, extractor, unusedConnection(), unusedConnection(),
                    "time_stamp", null);
            fail("Unsupported Resource source/extractor must fail");
        } catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("JDBC Resource")); }
    }

    private static void assertStaysOnJdbcPath(ErDataTransfer task, ErDatasource source, DataSyncExtractor extractor) throws Exception {
        assertNull(new StarRocksDataSyncExecutor(null, null, null).prepareDirectFullImport(task, source,
                "db", "orders", "`warehouse`.`orders`", COLUMNS, COLUMNS, extractor,
                unusedConnection(), unusedConnection(), "time_stamp", null));
    }

    private static ErDataTransfer task(String enabled, String mode, String method) {
        ErDataTransfer task = new ErDataTransfer();
        task.setExternalTableEnabled(enabled); task.setSyncMode(mode); task.setDirectImportMethod(method);
        return task;
    }

    private static ErDatasource datasource(String type) {
        ErDatasource source = new ErDatasource(); source.setDatasourceType(type); return source;
    }

    private static Connection unusedConnection() {
        return proxy(Connection.class, (method, args) -> { throw new AssertionError("Unexpected connection call: " + method.getName()); });
    }

    private static void assertBefore(List<String> events, String first, String second) {
        assertTrue(first + " missing: " + events, events.contains(first));
        assertTrue(second + " missing: " + events, events.contains(second));
        assertTrue(first + " must precede " + second + ": " + events, events.indexOf(first) < events.indexOf(second));
    }

    private static Object field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(instance);
    }

    private static class Fixture {
        final List<String> events = new ArrayList<>();
        final List<ErDataTransferProgress> checkpoints = new ArrayList<>();
        final List<Object[]> writtenRows = new ArrayList<>();
        final ErDatasource sourceDs = datasource("TIDB");
        final ErDataTransfer task = new ErDataTransfer();
        final LifecycleExecutor executor;
        boolean useDirectImport = true;
        boolean stopAfterPrepare, stopAfterExecute, stopOnClose;
        SQLException prepareFailure, executeFailure, closeFailure, clearFailure;
        ErDatasource preparedSource;
        long processed = 17;
        Timestamp maxTs = MAX_TS;
        int sourceReads;

        Fixture() {
            task.setTransferId(42L); task.setSyncMode("0");
            executor = new LifecycleExecutor(this, proxy(ErDataTransferProgressMapper.class, (method, args) -> {
                if ("upsert".equals(method.getName())) {
                    events.add("checkpoint"); checkpoints.add((ErDataTransferProgress) args[0]); return 1;
                }
                throw new AssertionError(method.getName());
            }));
        }

        Object run() throws Exception {
            Method method = DataSyncExecutor.class.getDeclaredMethod("syncFull", ErDataTransfer.class, ErDatasource.class,
                    String.class, String.class, String.class, List.class, List.class, List.class, DataSyncExtractor.class,
                    DataSyncLoader.class, Connection.class, Connection.class, int.class, String.class,
                    boolean.class, boolean.class, SyncProgressCallback.class);
            method.setAccessible(true);
            try {
                return method.invoke(executor, task, sourceDs, "erp_01", "orders", "`warehouse`.`orders`", COLUMNS,
                        COLUMNS, Collections.singletonList("id"), new FallbackExtractor(), new FallbackLoader(),
                        sourceConnection(), targetConnection(), 3000, "time_stamp", false, false, null);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
                if (failure.getCause() instanceof Error) throw (Error) failure.getCause();
                throw failure;
            }
        }

        Throwable failure() throws Exception {
            try { run(); throw new AssertionError("Import should fail"); }
            catch (SQLException expected) { return expected; }
        }

        Connection sourceConnection() {
            return proxy(Connection.class, (method, args) -> {
                if ("prepareStatement".equals(method.getName())) {
                    if (useDirectImport) throw new AssertionError("Direct import must not fetch application-side rows");
                    return proxy(PreparedStatement.class, (call, values) -> {
                        if ("executeQuery".equals(call.getName())) { sourceReads++; events.add("jdbc-read"); return singleSourceRow(); }
                        return defaultValue(call.getReturnType());
                    });
                }
                return defaultValue(method.getReturnType());
            });
        }

        Connection targetConnection() {
            return proxy(Connection.class, (method, args) -> {
                if ("commit".equals(method.getName()) || "rollback".equals(method.getName())) events.add(method.getName());
                if ("prepareStatement".equals(method.getName())) return proxy(PreparedStatement.class,
                        (call, values) -> defaultValue(call.getReturnType()));
                return defaultValue(method.getReturnType());
            });
        }

        ResultSet singleSourceRow() {
            int[] row = {-1};
            return proxy(ResultSet.class, (method, args) -> {
                switch (method.getName()) {
                    case "next": return ++row[0] == 0;
                    case "getMetaData": return proxy(ResultSetMetaData.class, (call, values) -> {
                        if ("getColumnTypeName".equals(call.getName())) return (Integer) values[0] == 1 ? "BIGINT" : "TIMESTAMP";
                        return defaultValue(call.getReturnType());
                    });
                    case "findColumn": return "id".equals(args[0]) ? 1 : 2;
                    case "getObject": return "id".equals(args[0]) ? 7L : MAX_TS;
                    default: return defaultValue(method.getReturnType());
                }
            });
        }
    }

    /** Tests the shared DirectFullImport lifecycle independently of any physical scanner implementation. */
    private static class LifecycleExecutor extends StarRocksDataSyncExecutor {
        final Fixture fixture;
        LifecycleExecutor(Fixture fixture, ErDataTransferProgressMapper progress) {
            super(unusedMapper(ErDataTransferMapper.class), unusedMapper(ErDataTransferLogMapper.class), progress); this.fixture = fixture;
        }
        @Override protected DirectFullImport prepareDirectFullImport(ErDataTransfer task, ErDatasource source,
                String schema, String table, String target, List<String> sourceColumns, List<String> targetColumns,
                DataSyncExtractor extractor, Connection sourceConnection, Connection targetConnection,
                String timestamp, SyncProgressCallback callback) throws Exception {
            fixture.events.add("prepare"); fixture.preparedSource = source;
            if (fixture.prepareFailure != null) throw fixture.prepareFailure;
            if (!fixture.useDirectImport) return null;
            if (fixture.stopAfterPrepare) requestStop();
            return new DirectFullImport() {
                @Override public FullSyncStats execute() throws Exception {
                    fixture.events.add("execute");
                    if (fixture.executeFailure != null) throw fixture.executeFailure;
                    FullSyncStats stats = new FullSyncStats(); stats.processed = fixture.processed; stats.maxTs = fixture.maxTs;
                    if (fixture.stopAfterExecute) requestStop(); return stats;
                }
                @Override public void close() throws Exception {
                    fixture.events.add("close-direct");
                    if (fixture.closeFailure != null) throw fixture.closeFailure;
                    if (fixture.stopOnClose) requestStop();
                }
            };
        }
        @Override protected void clearTable(Connection connection, String table) throws Exception {
            fixture.events.add("truncate"); if (fixture.clearFailure != null) throw fixture.clearFailure;
        }
        @Override protected void beforeBatchInsert(Connection connection, String table) { fixture.events.add("before"); }
        @Override protected void afterBatchInsert(Connection connection, String table) { fixture.events.add("after"); }
        @Override protected void executeBufferedFullInsert(Connection connection, List<Object[]> rows,
                String table, List<String> columns, List<String> keys) {
            fixture.events.add("jdbc-write"); for (Object[] row : rows) fixture.writtenRows.add(row.clone());
        }
    }

    private static class FallbackExtractor implements DataSyncExtractor {
        @Override public List<String> listTables(DatabaseMetaData metadata, String type, String schema, String table) { throw new AssertionError("Unexpected enumeration"); }
        @Override public List<String> listColumns(Connection connection, String schema, String table) { return COLUMNS; }
        @Override public List<String> listPrimaryKeys(Connection connection, String schema, String table) { return Collections.singletonList("id"); }
        @Override public String buildSelectFull(String schema, String table, String timestamp, List<String> keys) { return "SELECT id,time_stamp FROM erp_01.orders"; }
        @Override public String buildSelectIncremental(String schema, String table, String timestamp, List<String> keys) { throw new AssertionError("Not incremental"); }
    }
    private static class FallbackLoader implements DataSyncLoader {
        @Override public String buildUpsertSql(String table, List<String> columns, boolean upsert) { assertFalse(upsert); return "INSERT INTO " + table + "(id,time_stamp) VALUES (?,?)"; }
        @Override public String buildDeleteSql(String table, String key, List<String> ids) { throw new AssertionError("Not incremental"); }
    }
    private interface Invocation { Object invoke(Method method, Object[] args) throws Throwable; }
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (object, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if ("toString".equals(method.getName())) return type.getSimpleName() + " fixture";
                if ("hashCode".equals(method.getName())) return System.identityHashCode(object);
                if ("equals".equals(method.getName())) return object == args[0];
            }
            return invocation.invoke(method, args);
        }));
    }
    private static <T> T unusedMapper(Class<T> type) { return proxy(type, (method, args) -> { throw new AssertionError(method.getName()); }); }
    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false; if (type == int.class) return 0; if (type == long.class) return 0L;
        if (type == double.class) return 0d; if (type == float.class) return 0f; if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0; if (type == char.class) return (char) 0;
        throw new AssertionError(type);
    }
}
