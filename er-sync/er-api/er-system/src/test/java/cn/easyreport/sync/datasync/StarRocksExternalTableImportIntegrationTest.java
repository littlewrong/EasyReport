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

    @Test
    public void absentOrDisabledSwitchLeavesTiDbAndMySqlOnExistingImportPath() throws Exception {
        for (String enabled : new String[]{null, "0"}) {
            assertStaysOnJdbcPath(externalTableTask(enabled, "0"), tlsSource("TIDB"),
                    new TiDbDataSyncExtractor());
            assertStaysOnJdbcPath(externalTableTask(enabled, "0"), tlsSource("MYSQL"),
                    new MySqlDataSyncExtractor());
        }
    }

    @Test
    public void enabledSwitchDoesNotChangeIncrementalImports() throws Exception {
        assertStaysOnJdbcPath(externalTableTask("1", "1"), tlsSource("TIDB"),
                new TiDbDataSyncExtractor());
        assertStaysOnJdbcPath(externalTableTask("1", "1"), tlsSource("MYSQL"),
                new MySqlDataSyncExtractor());
    }

    @Test
    public void missingTaskOrSyncModeLeavesExistingImportPath() throws Exception {
        assertStaysOnJdbcPath(null, tlsSource("TIDB"), new TiDbDataSyncExtractor());
        assertStaysOnJdbcPath(externalTableTask("1", null), tlsSource("TIDB"),
                new TiDbDataSyncExtractor());
    }

    @Test
    public void starRocksLeavesNonMySqlOrTiDbSourcesOnExistingImportPath() throws Exception {
        ErDataTransfer task = externalTableTask("1", "0");
        assertStaysOnJdbcPath(task, tlsSource("POSTGRESQL"), new FallbackExtractor());
        assertStaysOnJdbcPath(task, null, new TiDbDataSyncExtractor());
    }

    @Test
    public void mismatchedSourceTypeAndExtractorLeavesExistingImportPath() throws Exception {
        ErDataTransfer task = externalTableTask("1", "0");
        assertStaysOnJdbcPath(task, tlsSource("TIDB"), new MySqlDataSyncExtractor());
        assertStaysOnJdbcPath(task, tlsSource("MYSQL"), new TiDbDataSyncExtractor());
        assertStaysOnJdbcPath(task, tlsSource("MYSQL"), new FallbackExtractor());
        assertStaysOnJdbcPath(task, tlsSource("TIDB"), null);
    }

    @Test
    public void tlsSourceFallsBackBeforeTouchingEitherDatabaseAndReportsJdbcPath() throws Exception {
        assertTlsFallbackReportsJdbcPath("TIDB", new TiDbDataSyncExtractor());
    }

    @Test
    public void enabledMySqlFullImportReachesExternalTableHelper() throws Exception {
        assertTlsFallbackReportsJdbcPath("MYSQL", new MySqlDataSyncExtractor());
    }

    @Test
    public void stoppedExternalImportCannotFallBackToJdbcOrReportProgress() throws Exception {
        for (String type : Arrays.asList("MYSQL", "TIDB")) {
            StarRocksDataSyncExecutor executor = new StarRocksDataSyncExecutor(
                    unusedMapper(ErDataTransferMapper.class), unusedMapper(ErDataTransferLogMapper.class),
                    unusedMapper(ErDataTransferProgressMapper.class));
            executor.requestStop();
            List<String> messages = new ArrayList<>();
            Connection unused = proxy(Connection.class, (method, args) -> {
                throw new AssertionError("Stopped import must not access JDBC: " + method.getName());
            });
            try {
                executor.prepareDirectFullImport(externalTableTask("1", "0"), tlsSource(type), "db", "orders",
                        "`warehouse`.`orders`", COLUMNS, COLUMNS,
                        "MYSQL".equals(type) ? new MySqlDataSyncExtractor() : new TiDbDataSyncExtractor(),
                        unused, unused, "time_stamp", (current, total, message) -> messages.add(message));
                fail("Expected stopped import");
            } catch (DataSyncCancellation.SyncStoppedException expected) {
                assertTrue(messages.isEmpty());
            }
        }
    }

    @Test
    public void realCountFailureCannotFallBackToJdbcOrClearDestination() throws Exception {
        for (String type : Arrays.asList("MYSQL", "TIDB")) {
            Fixture f = new Fixture();
            f.enableRealHelper(type);
            f.countFailure = new SQLException("exact source count denied", "HY000", 321);

            Throwable failure = f.failure();

            assertTrue(failure instanceof SQLException);
            assertEquals(321, ((SQLException) failure).getErrorCode());
            assertTrue(f.events.contains("count"));
            assertFalse(f.events.contains("truncate"));
            assertFalse(f.events.contains("jdbc-read"));
            assertFalse(f.events.contains("execute"));
            assertTrue(f.checkpoints.isEmpty());
            assertEquals(0, f.sourceReads);
        }
    }

    @Test
    public void stopWhileCountingCannotFallBackToJdbcOrClearDestination() throws Exception {
        for (String type : Arrays.asList("MYSQL", "TIDB")) {
            Fixture f = new Fixture();
            f.enableRealHelper(type);
            f.stopDuringCount = true;

            assertTrue(f.failure() instanceof DataSyncCancellation.SyncStoppedException);

            assertTrue(f.events.contains("count"));
            assertFalse(f.events.contains("truncate"));
            assertFalse(f.events.contains("jdbc-read"));
            assertFalse(f.events.contains("execute"));
            assertTrue(f.checkpoints.isEmpty());
            assertEquals(0, f.sourceReads);
        }
    }

    @Test
    public void mysqlAndTiDbJsonTablesUseInsertSelectAndKeepTheEffectiveRowThreshold() throws Exception {
        for (String sourceType : Arrays.asList("MYSQL", "TIDB")) {
            for (long sourceCount : new long[]{499999, 500000}) {
                Fixture f = new Fixture();
                f.enableRealHelper(sourceType);
                f.jsonColumn = true;
                f.sourceCount = sourceCount;
                f.directCounts = sourceCount < 500000 ? new long[]{sourceCount} : new long[]{250000, 250000};

                Object result = f.run();

                assertTrue((Boolean) field(result, "success"));
                assertEquals(MAX_TS, field(result, "maxTs"));
                assertEquals(Long.valueOf(sourceCount), ((ErDataTransferLog) field(result, "log")).getSuccessCount());
                assertEquals(sourceCount < 500000 ? 1 : 2, f.directInserts);
                assertEquals(0, f.sourceReads);
                assertTrue(f.writtenRows.isEmpty());
                assertEquals(1, f.checkpoints.size());
                assertEquals(String.valueOf(MAX_TS.getTime()), f.checkpoints.get(0).getLastSyncValue());
                assertBefore(f.events, "json-probe", "truncate");
                assertBefore(f.events, "truncate", "insert-select");
                assertBefore(f.events, "drop-external", "checkpoint");
                assertEquals(1, Collections.frequency(f.events, "drop-external"));
                assertTrue(f.directSql.stream().anyMatch(sql -> sql.startsWith("CREATE EXTERNAL TABLE")
                        && sql.contains("ENGINE=MYSQL")));
                assertFalse(f.directSql.stream().anyMatch(sql -> sql.contains("CATALOG")
                        || sql.contains("INFORMATION_SCHEMA.PARTITIONS")));
                assertTrue(f.directSql.stream().filter(sql -> sql.startsWith("INSERT INTO"))
                        .allMatch(sql -> sql.contains("JSON_QUERY(PARSE_JSON(CONCAT('[', `payload`, ']')), '$[0]')")));
            }
        }
    }

    @Test
    public void taskPolicyReachesTheRealHelperForMySqlAndTiDb() throws Exception {
        for (String sourceType : Arrays.asList("MYSQL", "TIDB")) {
            Fixture f = new Fixture();
            f.enableRealHelper(sourceType);
            f.task.setExternalTableThresholdWan(10);
            f.task.setExternalTableWindowUnit("MONTH");
            f.sourceCount = 100000;
            // December 2025 through October 2026, including empty natural windows.
            f.directCounts = new long[11];
            Arrays.fill(f.directCounts, 10000);
            f.directCounts[10] = 0;

            Object result = f.run();

            assertTrue((Boolean) field(result, "success"));
            assertEquals(11, f.directInserts);
            assertEquals(Long.valueOf(100000), ((ErDataTransferLog) field(result, "log")).getSuccessCount());
            List<String> inserts = new ArrayList<>();
            for (String sql : f.directSql) if (sql.startsWith("INSERT INTO")) inserts.add(sql);
            assertTrue(inserts.get(0).endsWith("WHERE `time_stamp` >= '2025-12-01 00:00:00' AND `time_stamp` < '2026-01-01 00:00:00'"));
            assertTrue(inserts.get(10).endsWith("WHERE `time_stamp` >= '2026-10-01 00:00:00' AND `time_stamp` < '2026-11-01 00:00:00'"));
            assertEquals(0, f.sourceReads);
            assertEquals(1, f.checkpoints.size());
            assertEquals(String.valueOf(MAX_TS.getTime()), f.checkpoints.get(0).getLastSyncValue());
            assertBefore(f.events, "count", "truncate");
            assertBefore(f.events, "drop-external", "checkpoint");
        }
    }

    @Test
    public void zeroEffectiveRowsWithCustomDailyPolicyDoesNotAdvanceTheCheckpoint() throws Exception {
        Fixture f = new Fixture();
        f.enableRealHelper("TIDB");
        f.task.setExternalTableThresholdWan(1);
        f.task.setExternalTableWindowUnit("DAY");
        f.sourceCount = 0;
        f.directCounts = new long[0];

        Object result = f.run();

        assertTrue((Boolean) field(result, "success"));
        assertNull(field(result, "maxTs"));
        assertEquals(0, f.directInserts);
        assertTrue(f.checkpoints.isEmpty());
        assertEquals(0, f.sourceReads);
        assertEquals(1, Collections.frequency(f.events, "drop-external"));
    }

    @Test
    public void jsonProbeAndExternalScanFailuresDoNotClearTargetOrAdvanceCheckpoint() throws Exception {
        for (boolean failProbe : new boolean[]{true, false}) {
            Fixture f = new Fixture();
            f.enableRealHelper("MYSQL");
            f.jsonColumn = true;
            if (failProbe) f.jsonProbeFailure = new SQLException("JSON functions unavailable", "HY000", 614);
            else f.jsonScanFailure = new SQLException("JSON conversion rejected", "HY000", 615);

            SQLException failure = (SQLException) f.failure();

            assertEquals(failProbe ? 614 : 615, failure.getErrorCode());
            assertFalse(f.events.contains("truncate"));
            assertFalse(f.events.contains("insert-select"));
            assertTrue(f.checkpoints.isEmpty());
            assertEquals(0, f.sourceReads);
            assertEquals(failProbe ? 0 : 1, Collections.frequency(f.events, "drop-external"));
            if (!failProbe) {
                String created = f.directSql.stream().filter(sql -> sql.startsWith("CREATE EXTERNAL TABLE ")).findFirst().get();
                String ownedTable = created.substring("CREATE EXTERNAL TABLE ".length()).split(" ", 2)[0];
                assertTrue(f.directSql.contains("DROP TABLE IF EXISTS " + ownedTable));
            }
        }
    }

    @Test
    public void jsonNullSemanticMismatchKeepsMySqlAndTiDbTargetsAndCheckpointsUntouched() throws Exception {
        for (String sourceType : Arrays.asList("MYSQL", "TIDB")) {
            Fixture f = new Fixture();
            f.enableRealHelper(sourceType);
            f.jsonColumn = true;
            // Simulate a projection result that conflates JSON null with SQL NULL.
            f.jsonProbeValues = new String[]{"123", "true", null, null};

            try {
                f.run();
                fail("JSON null must remain distinct from SQL NULL before clearing the target");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("JSON null"));
                assertTrue(expected.getMessage(), expected.getMessage().contains("SQL NULL"));
            }

            assertTrue(f.events.contains("json-probe"));
            assertFalse(f.events.contains("create-external"));
            assertFalse(f.events.contains("truncate"));
            assertFalse(f.events.contains("insert-select"));
            assertFalse(f.events.contains("jdbc-read"));
            assertTrue(f.checkpoints.isEmpty());
            assertEquals(0, f.sourceReads);
            assertEquals(0, Collections.frequency(f.events, "drop-external"));
        }
    }

    @Test
    public void stopAfterPreparingRealJsonImportDropsOwnedTableWithoutClearingTarget() throws Exception {
        Fixture f = new Fixture();
        f.enableRealHelper("TIDB");
        f.jsonColumn = true;
        f.stopAfterPrepare = true;

        assertTrue(f.failure() instanceof DataSyncCancellation.SyncStoppedException);

        assertFalse(f.events.contains("truncate"));
        assertFalse(f.events.contains("insert-select"));
        assertEquals(0, f.sourceReads);
        assertTrue(f.checkpoints.isEmpty());
        assertEquals(1, Collections.frequency(f.events, "drop-external"));
    }

    private static void assertStaysOnJdbcPath(ErDataTransfer task, ErDatasource source,
            DataSyncExtractor extractor) throws Exception {
        StarRocksDataSyncExecutor executor = new StarRocksDataSyncExecutor(
                unusedMapper(ErDataTransferMapper.class), unusedMapper(ErDataTransferLogMapper.class),
                unusedMapper(ErDataTransferProgressMapper.class));
        List<String> messages = new ArrayList<>();
        Connection unusedConnection = proxy(Connection.class, (method, args) -> {
            throw new AssertionError("Rejected external import must not access JDBC: " + method.getName());
        });

        assertNull(executor.prepareDirectFullImport(task, source, "db", "orders",
                "`warehouse`.`orders`", COLUMNS, COLUMNS, extractor,
                unusedConnection, unusedConnection, "time_stamp",
                (current, total, message) -> messages.add(message)));

        assertTrue("Rejected imports must not reach the external-table helper: " + messages,
                messages.isEmpty());
    }

    private static ErDataTransfer externalTableTask(String enabled, String syncMode) {
        ErDataTransfer task = new ErDataTransfer();
        task.setTransferId(42L);
        task.setExternalTableEnabled(enabled);
        task.setSyncMode(syncMode);
        return task;
    }

    private static ErDatasource tlsSource(String type) {
        ErDatasource source = new ErDatasource();
        source.setDatasourceType(type);
        source.setJdbcUrl("jdbc:mysql://source.test:4000/db?sslMode=REQUIRED");
        source.setHost("source.test");
        source.setPort(4000);
        source.setDatabaseName("db");
        source.setUsername("import_reader");
        source.setPassword("not-a-real-password");
        return source;
    }

    private static void assertTlsFallbackReportsJdbcPath(String type, DataSyncExtractor extractor)
            throws Exception {
        StarRocksDataSyncExecutor executor = new StarRocksDataSyncExecutor(
                unusedMapper(ErDataTransferMapper.class), unusedMapper(ErDataTransferLogMapper.class),
                unusedMapper(ErDataTransferProgressMapper.class));
        ErDatasource source = tlsSource(type);
        ErDataTransfer task = externalTableTask("1", "0");
        List<String> messages = new ArrayList<>();
        Connection unusedConnection = proxy(Connection.class, (method, args) -> {
            throw new AssertionError("TLS fallback must precede JDBC/DDL: " + method.getName());
        });

        assertNull(executor.prepareDirectFullImport(task, source, "db", "orders",
                "`warehouse`.`orders`", COLUMNS, COLUMNS, extractor,
                unusedConnection, unusedConnection, "time_stamp",
                (current, total, message) -> messages.add(message)));

        assertEquals(1, messages.size());
        assertTrue(messages.get(0), messages.get(0).contains("原 JDBC 初始化"));
        assertTrue(messages.get(0), messages.get(0).contains("TLS"));
        assertFalse(messages.get(0), messages.get(0).contains(source.getPassword()));
    }

    private static void assertBefore(List<String> events, String first, String second) {
        assertTrue(first + " missing: " + events, events.contains(first));
        assertTrue(second + " missing: " + events, events.contains(second));
        assertTrue(first + " must precede " + second + ": " + events,
                events.indexOf(first) < events.indexOf(second));
    }

    private static Object field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static class Fixture {
        final List<String> events = new ArrayList<>();
        final List<ErDataTransferProgress> checkpoints = new ArrayList<>();
        final List<Object[]> writtenRows = new ArrayList<>();
        final List<String> directSql = new ArrayList<>();
        final ErDatasource sourceDs = new ErDatasource();
        final ErDataTransfer task = new ErDataTransfer();
        final LifecycleExecutor executor;
        boolean useDirectImport = true;
        boolean stopAfterPrepare;
        boolean stopAfterExecute;
        boolean stopOnClose;
        boolean realHelper;
        boolean jsonColumn;
        long sourceCount = 500000L;
        long[] directCounts = {250000, 250000};
        int directInserts;
        SQLException jsonProbeFailure;
        String[] jsonProbeValues = {"123", "true", "null", null};
        SQLException jsonScanFailure;
        boolean stopDuringCount;
        SQLException countFailure;
        SQLException prepareFailure;
        SQLException executeFailure;
        SQLException closeFailure;
        SQLException clearFailure;
        ErDatasource preparedSource;
        long processed = 17;
        Timestamp maxTs = MAX_TS;
        int sourceReads;

        Fixture() {
            task.setTransferId(42L);
            task.setSyncMode("0");
            sourceDs.setDatasourceType("TIDB");
            executor = new LifecycleExecutor(this, proxy(ErDataTransferProgressMapper.class, (method, args) -> {
                if ("upsert".equals(method.getName())) {
                    events.add("checkpoint");
                    checkpoints.add((ErDataTransferProgress) args[0]);
                    return 1;
                }
                throw new AssertionError("Unexpected progress mapper call: " + method.getName());
            }));
        }

        void enableRealHelper(String sourceType) {
            realHelper = true;
            task.setExternalTableEnabled("1");
            sourceDs.setDatasourceType(sourceType);
            sourceDs.setJdbcUrl("jdbc:mysql://source.test:4000/erp_01?useSSL=false");
            sourceDs.setHost("source.test");
            sourceDs.setPort(4000);
            sourceDs.setDatabaseName("erp_01");
            sourceDs.setUsername("import_reader");
            sourceDs.setPassword("not-a-real-password");
        }

        List<String> columns() {
            return jsonColumn ? Arrays.asList("id", "time_stamp", "payload") : COLUMNS;
        }

        Object run() throws Exception {
            Method method = DataSyncExecutor.class.getDeclaredMethod("syncFull", ErDataTransfer.class,
                    ErDatasource.class, String.class, String.class, String.class, List.class, List.class,
                    List.class, DataSyncExtractor.class, DataSyncLoader.class, Connection.class,
                    Connection.class, int.class, String.class, boolean.class, boolean.class,
                    SyncProgressCallback.class);
            method.setAccessible(true);
            try {
                return method.invoke(executor, task, sourceDs, "erp_01", "orders", "`warehouse`.`orders`",
                        columns(), columns(), Collections.singletonList("id"), realHelper
                                ? ("TIDB".equals(sourceDs.getDatasourceType()) ? new TiDbDataSyncExtractor() : new MySqlDataSyncExtractor())
                                : new FallbackExtractor(),
                        new FallbackLoader(), sourceConnection(), targetConnection(), 3000,
                        "time_stamp", false, false, null);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw e;
            }
        }

        Throwable failure() throws Exception {
            try {
                run();
                throw new AssertionError("Import should fail");
            } catch (SQLException e) {
                return e;
            }
        }

        Connection sourceConnection() {
            return proxy(Connection.class, (method, args) -> {
                if (realHelper && "getMetaData".equals(method.getName())) {
                    return proxy(DatabaseMetaData.class, (metadataMethod, metadataArgs) ->
                            "getURL".equals(metadataMethod.getName()) ? sourceDs.getJdbcUrl()
                                    : defaultValue(metadataMethod.getReturnType()));
                }
                if (realHelper && "createStatement".equals(method.getName())) {
                    return proxy(Statement.class, (statementMethod, statementArgs) -> {
                        if ("executeQuery".equals(statementMethod.getName())) {
                            String sql = (String) statementArgs[0];
                            if (sql.startsWith("SELECT COUNT(*)")) {
                                assertEquals(jsonColumn
                                        ? "SELECT COUNT(*), MAX(OCTET_LENGTH(CAST(`payload` AS CHAR CHARACTER SET utf8mb4))) FROM `erp_01`.`orders` WHERE `time_stamp` IS NOT NULL"
                                        : "SELECT COUNT(*) FROM `erp_01`.`orders` WHERE `time_stamp` IS NOT NULL", sql);
                                events.add("count");
                                if (stopDuringCount) {
                                    executor.requestStop();
                                    throw new SQLException("COUNT cancelled", "HY000", 1317);
                                }
                                if (countFailure != null) throw countFailure;
                                return jsonColumn ? valuesRow(sourceCount, 128L) : valuesRow(sourceCount);
                            }
                            if (sql.startsWith("SELECT MIN(")) return valuesRow(Timestamp.valueOf("2025-12-31 23:59:59"), MAX_TS);
                            throw new AssertionError("Unexpected source SQL: " + sql);
                        }
                        return defaultValue(statementMethod.getReturnType());
                    });
                }
                if ("prepareStatement".equals(method.getName())) {
                    if (realHelper && ((String) args[0]).contains("information_schema.COLUMNS")) {
                        return proxy(PreparedStatement.class, (statementMethod, statementArgs) ->
                                "executeQuery".equals(statementMethod.getName()) ? sourceColumnMetadata()
                                        : defaultValue(statementMethod.getReturnType()));
                    }
                    if (useDirectImport) throw new AssertionError("Direct import must not fetch source rows");
                    return proxy(PreparedStatement.class, (statementMethod, statementArgs) -> {
                        if ("executeQuery".equals(statementMethod.getName())) {
                            sourceReads++;
                            events.add("jdbc-read");
                            return singleSourceRow();
                        }
                        return defaultValue(statementMethod.getReturnType());
                    });
                }
                return defaultValue(method.getReturnType());
            });
        }

        Connection targetConnection() {
            return proxy(Connection.class, (method, args) -> {
                if (realHelper && "createStatement".equals(method.getName())) {
                    if (countFailure != null || stopDuringCount) throw new AssertionError("Failed/cancelled COUNT must precede any target statement");
                    return proxy(Statement.class, (statementMethod, statementArgs) -> {
                        if ("execute".equals(statementMethod.getName())) {
                            String sql = (String) statementArgs[0]; directSql.add(sql);
                            if (sql.startsWith("CREATE EXTERNAL TABLE")) events.add("create-external");
                            if (sql.startsWith("DROP TABLE")) events.add("drop-external");
                            return false;
                        }
                        if ("executeQuery".equals(statementMethod.getName())) {
                            String sql = (String) statementArgs[0]; directSql.add(sql);
                            if (sql.equals("DESCRIBE `warehouse`.`orders`")) return targetDescribe();
                            if (sql.equals(StarRocksExternalFullImport.JSON_CONVERSION_PROBE_SQL)) {
                                events.add("json-probe");
                                if (jsonProbeFailure != null) throw jsonProbeFailure;
                                return valuesRow((Object[]) jsonProbeValues);
                            }
                            if (sql.equals("SHOW VARIABLES LIKE 'enable_insert_strict'")) return valuesRow("enable_insert_strict", "true");
                            if (sql.equals("SHOW VARIABLES LIKE 'query_timeout'")) return valuesRow("query_timeout", 3600L);
                            if (sql.startsWith("EXPLAIN")) return valuesRow("INSERT PLAN");
                            if (sql.startsWith("SELECT") && sql.contains("FROM `easyreport_import_tmp`.")) {
                                events.add("json-scan-preflight");
                                if (jsonScanFailure != null) throw jsonScanFailure;
                                return valuesRow(7L, MAX_TS, "{\"中文\":[1,true,null]}");
                            }
                            throw new AssertionError("Unexpected target SQL: " + sql);
                        }
                        if ("executeLargeUpdate".equals(statementMethod.getName())) {
                            directSql.add((String) statementArgs[0]);
                            events.add("insert-select");
                            return directCounts[directInserts++];
                        }
                        return defaultValue(statementMethod.getReturnType());
                    });
                }
                if (realHelper && "prepareStatement".equals(method.getName())) {
                    throw new AssertionError("External import must not use JDBC row writes");
                }
                if ("commit".equals(method.getName()) || "rollback".equals(method.getName())) {
                    events.add(method.getName());
                }
                if ("prepareStatement".equals(method.getName())) {
                    return proxy(PreparedStatement.class, (statementMethod, statementArgs) ->
                            defaultValue(statementMethod.getReturnType()));
                }
                return defaultValue(method.getReturnType());
            });
        }

        ResultSet targetDescribe() {
            String[][] fields = {{"id", "BIGINT"}, {"time_stamp", "DATETIME"}, {"payload", "JSON"}};
            int[] row = {-1};
            return proxy(ResultSet.class, (method, args) -> {
                if ("next".equals(method.getName())) return ++row[0] < fields.length;
                if ("getString".equals(method.getName())) return "Field".equals(args[0]) ? fields[row[0]][0] : fields[row[0]][1];
                if ("getMetaData".equals(method.getName())) throw new AssertionError("SR JSON protocol metadata is not the real column type");
                return defaultValue(method.getReturnType());
            });
        }

        ResultSet sourceColumnMetadata() {
            int[] row = {-1};
            return proxy(ResultSet.class, (method, args) -> {
                switch (method.getName()) {
                    case "next": return ++row[0] < columns().size();
                    case "getString":
                        switch ((String) args[0]) {
                            case "COLUMN_NAME": return columns().get(row[0]);
                            case "DATA_TYPE": return row[0] == 0 ? "bigint" : row[0] == 1 ? "datetime" : "json";
                            case "COLUMN_TYPE": return row[0] == 0 ? "bigint(20)" : row[0] == 1 ? "datetime" : "json";
                            case "IS_NULLABLE": return row[0] == 0 ? "NO" : "YES";
                            default: throw new AssertionError("Unexpected metadata column: " + args[0]);
                        }
                    case "getInt": return "NUMERIC_PRECISION".equals(args[0]) && row[0] == 0 ? 20 : 0;
                    default: return defaultValue(method.getReturnType());
                }
            });
        }

        ResultSet valuesRow(Object... values) {
            int[] row = {-1};
            boolean[] wasNull = {false};
            return proxy(ResultSet.class, (method, args) -> {
                if ("next".equals(method.getName())) return ++row[0] == 0;
                if ("wasNull".equals(method.getName())) return wasNull[0];
                if (method.getName().startsWith("get") && args != null && args.length > 0 && args[0] instanceof Integer) {
                    Object value = values[(Integer) args[0] - 1];
                    wasNull[0] = value == null;
                    if ("getLong".equals(method.getName())) return value == null ? 0L : ((Number) value).longValue();
                    if ("getString".equals(method.getName())) return value == null ? null : value.toString();
                    return value;
                }
                return defaultValue(method.getReturnType());
            });
        }

        ResultSet singleSourceRow() {
            final int[] row = {-1};
            return proxy(ResultSet.class, (method, args) -> {
                switch (method.getName()) {
                    case "next": return ++row[0] == 0;
                    case "getMetaData": return proxy(ResultSetMetaData.class, (metaMethod, metaArgs) -> {
                        if ("getColumnTypeName".equals(metaMethod.getName())) {
                            return ((Integer) metaArgs[0]) == 1 ? "BIGINT" : "TIMESTAMP";
                        }
                        return defaultValue(metaMethod.getReturnType());
                    });
                    case "findColumn": return "id".equals(args[0]) ? 1 : 2;
                    case "getObject": return "id".equals(args[0]) ? 7L : MAX_TS;
                    default: return defaultValue(method.getReturnType());
                }
            });
        }
    }

    private static class LifecycleExecutor extends StarRocksDataSyncExecutor {
        private final Fixture fixture;

        LifecycleExecutor(Fixture fixture, ErDataTransferProgressMapper progressMapper) {
            super(unusedMapper(ErDataTransferMapper.class), unusedMapper(ErDataTransferLogMapper.class), progressMapper);
            this.fixture = fixture;
        }

        @Override
        protected DirectFullImport prepareDirectFullImport(ErDataTransfer task, ErDatasource sourceDs,
                String schema, String table, String targetTable, List<String> sourceCols,
                List<String> targetCols, DataSyncExtractor extractor, Connection sourceConn,
                Connection targetConn, String tsField, SyncProgressCallback callback) throws Exception {
            fixture.events.add("prepare");
            fixture.preparedSource = sourceDs;
            if (fixture.realHelper) {
                DirectFullImport direct = super.prepareDirectFullImport(task, sourceDs, schema, table, targetTable,
                        sourceCols, targetCols, extractor, sourceConn, targetConn, tsField, callback);
                if (fixture.stopAfterPrepare) requestStop();
                return direct;
            }
            if (fixture.prepareFailure != null) throw fixture.prepareFailure;
            if (!fixture.useDirectImport) return null;
            if (fixture.stopAfterPrepare) requestStop();
            return new DirectFullImport() {
                @Override
                public FullSyncStats execute() throws Exception {
                    fixture.events.add("execute");
                    if (fixture.executeFailure != null) throw fixture.executeFailure;
                    FullSyncStats stats = new FullSyncStats();
                    stats.processed = fixture.processed;
                    stats.maxTs = fixture.maxTs;
                    if (fixture.stopAfterExecute) requestStop();
                    return stats;
                }

                @Override
                public void close() throws Exception {
                    fixture.events.add("close-direct");
                    if (fixture.closeFailure != null) throw fixture.closeFailure;
                    if (fixture.stopOnClose) requestStop();
                }
            };
        }

        @Override
        protected void clearTable(Connection conn, String tableName) throws Exception {
            fixture.events.add("truncate");
            if (fixture.clearFailure != null) throw fixture.clearFailure;
        }

        @Override
        protected void beforeBatchInsert(Connection targetConn, String tableName) {
            fixture.events.add("before");
        }

        @Override
        protected void afterBatchInsert(Connection targetConn, String tableName) {
            fixture.events.add("after");
        }

        @Override
        protected void executeBufferedFullInsert(Connection conn, List<Object[]> rows,
                String targetTable, List<String> cols, List<String> pkCols) {
            fixture.events.add("jdbc-write");
            for (Object[] row : rows) fixture.writtenRows.add(row.clone());
        }
    }

    private static class FallbackExtractor implements DataSyncExtractor {
        @Override public List<String> listTables(DatabaseMetaData meta, String dbType, String schema, String table) {
            throw new AssertionError("Table enumeration does not belong to the full-import hook");
        }
        @Override public List<String> listColumns(Connection conn, String schema, String table) {
            return COLUMNS;
        }
        @Override public List<String> listPrimaryKeys(Connection conn, String schema, String table) {
            return Collections.singletonList("id");
        }
        @Override public String buildSelectFull(String schema, String table, String tsField, List<String> pkCols) {
            return "SELECT id,time_stamp FROM erp_01.orders";
        }
        @Override public String buildSelectIncremental(String schema, String table, String tsField, List<String> pkCols) {
            throw new AssertionError("Full initialization must not use incremental selection");
        }
    }

    private static class FallbackLoader implements DataSyncLoader {
        @Override public String buildUpsertSql(String table, List<String> columns, boolean upsert) {
            assertFalse("Initialization uses INSERT into an emptied target", upsert);
            return "INSERT INTO " + table + "(id,time_stamp) VALUES (?,?)";
        }
        @Override public String buildDeleteSql(String table, String pkColumn, List<String> ids) {
            throw new AssertionError("Initialization must not execute incremental delete alignment");
        }
    }

    private interface Invocation {
        Object invoke(Method method, Object[] args) throws Throwable;
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if ("toString".equals(method.getName())) return type.getSimpleName() + " fixture";
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(object);
                        if ("equals".equals(method.getName())) return object == args[0];
                    }
                    return invocation.invoke(method, args);
                }));
    }

    private static <T> T unusedMapper(Class<T> type) {
        return proxy(type, (method, args) -> { throw new AssertionError("Unexpected mapper call: " + method.getName()); });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        throw new AssertionError("Unsupported primitive: " + type);
    }
}
