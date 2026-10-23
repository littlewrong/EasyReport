package cn.easyreport.sync.datasync;

import cn.easyreport.sync.domain.ErDatasource;
import org.junit.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class StarRocksExternalFullImportTest {
    @Test
    public void importsNaturalYearsIncludingLeapYearWithoutNullTimestampRows() throws Exception {
        Fixture f = new Fixture();
        List<Integer> progress = new ArrayList<>();
        StarRocksExternalFullImport.Result result;
        try (StarRocksExternalFullImport importer = f.prepare(" times_tamp ", (current, total, message) -> progress.add(current))) {
            assertTrue(f.targetSql.stream().anyMatch(sql -> sql.startsWith("SELECT `ID`, `times_tamp` FROM `easyreport_import_tmp`.")));
            assertFalse(f.targetSql.stream().anyMatch(sql -> sql.startsWith("INSERT")));
            result = importer.execute();
        }
        assertEquals(12L, result.processed);
        assertEquals(f.max, result.maxTs);
        assertEquals(Arrays.asList(0, 5, 12), progress);
        assertEquals(2, f.commits);
        assertEquals(2, f.inserts.size());
        assertTrue(f.inserts.get(0).startsWith("INSERT INTO `warehouse`.`orders` (`id`, `times_tamp`) SELECT `ID`, `times_tamp` FROM "));
        assertTrue(f.inserts.get(0).endsWith("WHERE `times_tamp` >= '2023-01-01 00:00:00' AND `times_tamp` < '2024-01-01 00:00:00'"));
        assertTrue(f.inserts.get(1).endsWith("WHERE `times_tamp` >= '2024-01-01 00:00:00' AND `times_tamp` < '2025-01-01 00:00:00'"));
        assertFalse(f.inserts.stream().anyMatch(sql -> sql.contains(" IS NULL")));
        assertEquals(1, f.drops());
        assertTrue(f.targetSql.contains("SET enable_insert_strict = true"));
        assertTrue(f.targetSql.contains("SET SESSION query_timeout = 3600"));
        assertTrue(f.sourceQueries.stream().allMatch(sql -> sql.startsWith("SELECT")));
        assertEquals(1, Collections.frequency(f.sourceQueries,
                "SELECT COUNT(*) FROM `source`.`orders` WHERE `times_tamp` IS NOT NULL"));
        assertFalse(f.sourceQueries.stream().anyMatch(sql -> sql.contains(" IS NULL")));
        String create = f.createSql();
        assertTrue(create.contains("ENGINE=MYSQL"));
        assertTrue(create.contains("`ID` BIGINT NOT NULL"));
        assertTrue(create.contains("`times_tamp` DATETIME NULL"));
        assertTrue(create.contains("\"database\"=\"source\""));
        assertTrue(create.contains("\"table\"=\"orders\""));
        assertFalse(f.targetSql.stream().anyMatch(sql -> sql.startsWith("DROP DATABASE")));
        assertTrue(f.targetSql.stream().anyMatch(sql -> sql.startsWith("EXPLAIN INSERT INTO `warehouse`.`orders`")
                && sql.endsWith(" WHERE 1=0")));
    }

    @Test
    public void effectiveRowCountUsesFiveHundredThousandBoundary() throws Exception {
        for (long sourceCount : new long[]{499999, 500000, 500001}) {
            Fixture f = new Fixture();
            f.sourceCount = sourceCount;
            f.counts = sourceCount < 500000 ? new long[]{sourceCount}
                    : new long[]{250000, sourceCount - 250000};
            try (StarRocksExternalFullImport importer = f.prepare("times_tamp", null)) {
                StarRocksExternalFullImport.Result result = importer.execute();
                assertEquals(sourceCount, result.processed);
                assertEquals("single imports must still preserve the timestamp checkpoint", f.max, result.maxTs);
            }
            assertEquals(1, Collections.frequency(f.sourceQueries,
                    "SELECT COUNT(*) FROM `source`.`orders` WHERE `times_tamp` IS NOT NULL"));
            if (sourceCount < 500000) {
                assertEquals(1, f.inserts.size());
                assertTrue("small table import must exclude NULL timestamps",
                        f.inserts.get(0).endsWith("WHERE `times_tamp` IS NOT NULL"));
            } else {
                assertEquals(2, f.inserts.size());
                assertTrue(f.inserts.get(0).contains(" >= '2023-01-01 00:00:00'"));
                assertTrue(f.inserts.get(1).contains(" >= '2024-01-01 00:00:00'"));
            }
            assertFalse(f.sourceQueries.stream().anyMatch(sql -> sql.contains(" IS NULL")));
            assertFalse(f.inserts.stream().anyMatch(sql -> sql.contains(" IS NULL")));
        }
    }

    @Test
    public void customThresholdUsesWholeImportBelowAndMonthWindowsAtTheBoundary() throws Exception {
        for (long sourceCount : new long[]{99999, 100000, 100001}) {
            Fixture f = new Fixture();
            f.sourceCount = sourceCount;
            f.max = Timestamp.valueOf("2024-01-01 00:00:00");
            f.counts = sourceCount < 100000 ? new long[]{sourceCount} : new long[]{50000, sourceCount - 50000};
            try (StarRocksExternalFullImport importer = f.prepare("times_tamp", 10, "MONTH", null)) {
                StarRocksExternalFullImport.Result result = importer.execute();
                assertEquals(sourceCount, result.processed);
                assertEquals(f.max, result.maxTs);
            }
            assertEquals(1, Collections.frequency(f.sourceQueries,
                    "SELECT COUNT(*) FROM `source`.`orders` WHERE `times_tamp` IS NOT NULL"));
            if (sourceCount < 100000) {
                assertEquals(1, f.inserts.size());
                assertTrue(f.inserts.get(0).endsWith("WHERE `times_tamp` IS NOT NULL"));
            } else {
                assertEquals(2, f.inserts.size());
                assertWindow(f.inserts.get(0), "2023-12-01", "2024-01-01");
                assertWindow(f.inserts.get(1), "2024-01-01", "2024-02-01");
            }
        }
    }

    @Test
    public void maximumIntegerWanThresholdUsesLongArithmetic() throws Exception {
        Fixture f = new Fixture();
        f.sourceCount = Integer.MAX_VALUE * 10_000L - 1;
        f.counts = new long[]{f.sourceCount};
        try (StarRocksExternalFullImport importer = f.prepare("times_tamp", Integer.MAX_VALUE, "DAY", null)) {
            assertEquals(f.sourceCount.longValue(), importer.execute().processed);
        }
        assertEquals(1, f.inserts.size());
        assertTrue(f.inserts.get(0).endsWith("WHERE `times_tamp` IS NOT NULL"));
    }

    @Test
    public void monthWindowsCrossTheYearAndIncludeLeapFebruaryWithoutGaps() throws Exception {
        Fixture f = new Fixture();
        f.max = Timestamp.valueOf("2024-03-01 00:00:00");
        f.counts = new long[]{1, 2, 3, 4};
        try (StarRocksExternalFullImport importer = f.prepare("times_tamp", 1, "MONTH", null)) {
            assertEquals(10L, importer.execute().processed);
        }
        assertEquals(4, f.inserts.size());
        assertWindow(f.inserts.get(0), "2023-12-01", "2024-01-01");
        assertWindow(f.inserts.get(1), "2024-01-01", "2024-02-01");
        assertWindow(f.inserts.get(2), "2024-02-01", "2024-03-01");
        assertWindow(f.inserts.get(3), "2024-03-01", "2024-04-01");
        assertEquals(4, f.commits);
    }

    @Test
    public void dayWindowsIncludeLeapDayAndTheLastMidnightWithoutOverlap() throws Exception {
        Fixture f = new Fixture();
        f.min = Timestamp.valueOf("2024-02-28 23:59:59");
        f.max = Timestamp.valueOf("2024-03-01 00:00:00");
        f.counts = new long[]{1, 2, 3};
        try (StarRocksExternalFullImport importer = f.prepare("times_tamp", 1, "DAY", null)) {
            StarRocksExternalFullImport.Result result = importer.execute();
            assertEquals(6L, result.processed);
            assertEquals(f.max, result.maxTs);
        }
        assertEquals(3, f.inserts.size());
        assertWindow(f.inserts.get(0), "2024-02-28", "2024-02-29");
        assertWindow(f.inserts.get(1), "2024-02-29", "2024-03-01");
        assertWindow(f.inserts.get(2), "2024-03-01", "2024-03-02");
    }

    @Test
    public void invalidPolicyFailsBeforeAnySourceOrTargetQuery() throws Exception {
        Object[][] policies = {{0, "YEAR"}, {-1, "YEAR"}, {50, "WEEK"}, {50, ""},
                {50, "year"}, {50, " YEAR "}};
        for (Object[] policy : policies) {
            Fixture f = new Fixture();
            try {
                f.prepare("times_tamp", (Integer) policy[0], (String) policy[1], null);
                fail("Expected invalid import policy to fail");
            } catch (IllegalArgumentException expected) {
                assertFalse(expected instanceof StarRocksExternalFullImport.UnsupportedImportException);
                assertTrue(f.sourceQueries.isEmpty());
                assertTrue(f.targetSql.isEmpty());
            }
        }
    }

    @Test
    public void finalBoundaryUsesTheSelectedUnitRatherThanRejectingAllOfYear9999() throws Exception {
        String[][] supported = {{"MONTH", "9999-11-30 23:59:59", "9999-11-01", "9999-12-01"},
                {"DAY", "9999-12-30 23:59:59", "9999-12-30", "9999-12-31"}};
        for (String[] policy : supported) {
            Fixture f = new Fixture();
            f.min = Timestamp.valueOf(policy[1]);
            f.max = f.min;
            try (StarRocksExternalFullImport importer = f.prepare("times_tamp", 1, policy[0], null)) {
                assertEquals(5L, importer.execute().processed);
            }
            assertEquals(1, f.inserts.size());
            assertWindow(f.inserts.get(0), policy[2], policy[3]);
        }
        for (String unit : Arrays.asList("MONTH", "DAY")) {
            Fixture f = new Fixture();
            f.min = Timestamp.valueOf("9999-12-31 23:59:59");
            f.max = f.min;
            try {
                f.prepare("times_tamp", 1, unit, null);
                fail("Expected an unrepresentable exclusive window boundary");
            } catch (StarRocksExternalFullImport.UnsupportedImportException expected) {
                assertTrue(f.targetSql.isEmpty());
                assertTrue(f.inserts.isEmpty());
            }
        }
    }

    @Test
    public void stopBetweenMonthOrDayWindowsDoesNotReturnACompletedCheckpoint() throws Exception {
        for (String unit : Arrays.asList("MONTH", "DAY")) {
            Fixture f = new Fixture();
            f.min = Timestamp.valueOf("2024-02-28 23:59:59");
            f.max = Timestamp.valueOf("2024-03-01 00:00:00");
            List<Integer> progress = new ArrayList<>();
            try (StarRocksExternalFullImport importer = f.prepare("times_tamp", 1, unit, (current, total, message) -> {
                progress.add(current);
                if (current > 0) f.cancellation.requestStop();
            })) {
                importer.execute();
                fail("An interrupted import must not return its maximum timestamp checkpoint");
            } catch (DataSyncCancellation.SyncStoppedException expected) {
                assertEquals(Arrays.asList(0, 5), progress);
            }
            assertEquals(1, f.inserts.size());
            assertEquals(1, f.commits);
            assertEquals(1, f.drops());
        }
    }

    @Test
    public void timestampAtNewYearBoundaryImportsItsOwnYearWithoutOverlap() throws Exception {
        Fixture f = new Fixture();
        f.nullable = false;
        f.max = Timestamp.valueOf("2025-01-01 00:00:00");
        f.counts = new long[]{1, 2, 3};
        try (StarRocksExternalFullImport importer = f.prepare("times_tamp", null)) {
            assertEquals(6L, importer.execute().processed);
        }
        assertEquals(3, f.inserts.size());
        for (int index = 0; index < 3; index++) {
            int year = 2023 + index;
            assertTrue(f.inserts.get(index).endsWith("WHERE `times_tamp` >= '" + year
                    + "-01-01 00:00:00' AND `times_tamp` < '" + (year + 1) + "-01-01 00:00:00'"));
        }
    }

    @Test
    public void unrepresentableExclusiveYearBoundaryFallsBackBeforeTargetDdl() throws Exception {
        Fixture f = new Fixture();
        f.min = Timestamp.valueOf("9998-12-31 23:59:59");
        f.max = Timestamp.valueOf("9999-01-01 00:00:00");
        try {
            f.prepare("times_tamp", null);
            fail("Expected unsupported exclusive year boundary");
        } catch (StarRocksExternalFullImport.UnsupportedImportException expected) {
            assertTrue(f.targetSql.isEmpty());
            assertTrue(f.inserts.isEmpty());
        }
    }

    @Test
    public void countFailureLeavesTargetUntouchedBeforeCreatingExternalTable() throws Exception {
        Fixture f = new Fixture();
        f.failCount = true;
        try {
            f.prepare("times_tamp", null);
            fail("Expected exact source count failure");
        } catch (SQLException expected) {
            assertEquals(321, expected.getErrorCode());
        }
        assertTrue(f.targetSql.isEmpty());
        assertTrue(f.inserts.isEmpty());
    }

    @Test
    public void missingNullOrNegativeCountCannotSilentlySelectTheSmallTablePath() throws Exception {
        for (int invalid = 0; invalid < 3; invalid++) {
            Fixture f = new Fixture();
            if (invalid == 0) f.countHasRow = false;
            if (invalid == 1) f.sourceCount = null;
            if (invalid == 2) f.sourceCount = -1L;
            try {
                f.prepare("times_tamp", null);
                fail("Expected invalid exact source count to fail");
            } catch (IllegalStateException expected) {
                assertTrue(f.targetSql.isEmpty());
            }
        }
    }

    @Test
    public void stopCancelsBlockedCountBeforeAnyTargetObjectOrImport() throws Exception {
        Fixture f = new Fixture();
        f.blockCount = true;
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> running = worker.submit(() -> {
                try (StarRocksExternalFullImport ignored = f.prepare("times_tamp", null)) {
                    throw new AssertionError("Cancelled COUNT must not return an import");
                }
            });
            assertTrue("COUNT did not start", f.countEntered.await(2, TimeUnit.SECONDS));
            f.cancellation.requestStop();
            try {
                running.get(2, TimeUnit.SECONDS);
                fail("Expected stopped import");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause().toString(),
                        expected.getCause() instanceof DataSyncCancellation.SyncStoppedException);
            }
            assertEquals(1, f.cancelCalls);
            assertTrue(f.targetSql.isEmpty());
            assertTrue(f.inserts.isEmpty());
        } finally {
            f.countReleased.countDown();
            worker.shutdownNow();
            f.cancellation.close();
        }
    }

    @Test
    public void zeroEffectiveRowsSkipsInsertAndBoundsForAllNullOrEmptyTables() throws Exception {
        for (boolean allNull : new boolean[]{false, true}) {
            Fixture f = new Fixture();
            f.sourceCount = 0L;
            if (allNull) { f.min = null; f.max = null; }
            f.counts = new long[0];
            try (StarRocksExternalFullImport importer = f.prepare("times_tamp", null)) {
                StarRocksExternalFullImport.Result result = importer.execute();
                assertEquals(0L, result.processed);
                assertNull(result.maxTs);
            }
            assertTrue(f.inserts.isEmpty());
            assertEquals(0, f.commits);
            assertEquals(1, Collections.frequency(f.sourceQueries,
                    "SELECT COUNT(*) FROM `source`.`orders` WHERE `times_tamp` IS NOT NULL"));
            assertFalse(f.sourceQueries.stream().anyMatch(sql -> sql.contains("MIN(")));
            assertFalse(f.sourceQueries.stream().anyMatch(sql -> sql.contains(" IS NULL")));
        }
    }

    @Test
    public void positiveEffectiveCountWithMissingTimestampBoundsFailsBeforeTargetDdl() throws Exception {
        for (long sourceCount : new long[]{499999, 500000}) {
            for (int missing = 0; missing < 3; missing++) {
                Fixture f = new Fixture();
                f.sourceCount = sourceCount;
                if (missing == 0) f.min = null;
                if (missing == 1) f.max = null;
                if (missing == 2) f.boundsHasRow = false;
                try {
                    f.prepare("times_tamp", null);
                    fail("A positive effective count must not fall back to an unfiltered import");
                } catch (IllegalStateException expected) {
                    assertTrue(f.targetSql.isEmpty());
                    assertTrue(f.inserts.isEmpty());
                }
            }
        }
    }

    @Test
    public void missingTimestampConfigurationUsesOneUnconditionalInsert() throws Exception {
        Fixture f = new Fixture();
        try (StarRocksExternalFullImport importer = f.prepare(null, null)) {
            assertEquals(5L, importer.execute().processed);
        }
        assertEquals(1, f.inserts.size());
        assertFalse(f.inserts.get(0).contains("WHERE"));
        assertFalse(f.sourceQueries.stream().anyMatch(sql -> sql.contains("MIN(")));
        assertEquals(1, Collections.frequency(f.sourceQueries, "SELECT COUNT(*) FROM `source`.`orders`"));
    }

    @Test
    public void bothNullableAndNonNullableTimestampColumnsSkipNullLookupAndImport() throws Exception {
        for (boolean nullable : new boolean[]{false, true}) {
            Fixture f = new Fixture();
            f.nullable = nullable;
            try (StarRocksExternalFullImport importer = f.prepare("times_tamp", null)) {
                assertEquals(12L, importer.execute().processed);
            }
            assertEquals(2, f.inserts.size());
            assertFalse(f.sourceQueries.stream().anyMatch(sql -> sql.contains(" IS NULL")));
        }
    }

    @Test
    public void preflightFailureDropsOnlyOwnedTableAndDoesNotExposeDdlOrPassword() throws Exception {
        Fixture f = new Fixture();
        f.failPreflight = true;
        try {
            f.prepare("times_tamp", null);
            fail("Expected preflight failure");
        } catch (SQLException expected) {
            StringWriter output = new StringWriter();
            expected.printStackTrace(new PrintWriter(output));
            assertFalse(output.toString().contains(f.ds.getPassword()));
            assertFalse(output.toString().contains("CREATE EXTERNAL TABLE"));
            assertNull(expected.getCause());
            assertEquals(123, expected.getErrorCode());
        }
        assertEquals(1, f.drops());
        assertTrue(f.inserts.isEmpty());
        String tableName = f.createSql().substring("CREATE EXTERNAL TABLE ".length()).split(" ", 2)[0];
        assertTrue(f.targetSql.contains("DROP TABLE IF EXISTS " + tableName));
    }

    @Test
    public void explainFailureCleansExternalTableBeforeAnyDestinationWrite() throws Exception {
        Fixture f = new Fixture();
        f.failExplain = true;
        try {
            f.prepare("times_tamp", null);
            fail("Expected target planning failure");
        } catch (SQLException expected) {
            assertNull(expected.getCause());
        }
        assertTrue(f.targetSql.stream().anyMatch(sql -> sql.startsWith("EXPLAIN INSERT")));
        assertTrue(f.inserts.isEmpty());
        assertFalse(f.targetSql.stream().anyMatch(sql -> sql.startsWith("TRUNCATE")));
        assertEquals(1, f.drops());
    }

    @Test
    public void insertFailureStopsFurtherWindowsAndProgress() throws Exception {
        Fixture f = new Fixture();
        f.failInsertAt = 2;
        List<Integer> progress = new ArrayList<>();
        try (StarRocksExternalFullImport importer = f.prepare("times_tamp", (current, total, message) -> progress.add(current))) {
            importer.execute();
            fail("Expected insert failure");
        } catch (SQLException expected) {
            assertNull(expected.getCause());
        }
        assertEquals(Arrays.asList(0, 5), progress);
        assertEquals(1, f.commits);
        assertEquals(2, f.inserts.size());
        assertEquals(1, f.drops());
    }

    @Test
    public void stopBeforePreparingDoesNotReadSourceOrCreateTargetObjects() throws Exception {
        Fixture f = new Fixture();
        f.cancellation.requestStop();
        try {
            f.prepare("times_tamp", null);
            fail("Expected stopped import");
        } catch (DataSyncCancellation.SyncStoppedException expected) {
            assertTrue(f.sourceQueries.isEmpty());
            assertTrue(f.targetSql.isEmpty());
        }
    }

    @Test
    public void stopAfterCreatingExternalTableStillDropsOnlyOwnedTable() throws Exception {
        Fixture f = new Fixture();
        f.stopAfterCreate = true;
        try {
            f.prepare("times_tamp", null);
            fail("Expected stopped preflight");
        } catch (DataSyncCancellation.SyncStoppedException expected) {
            assertEquals("57014", expected.getSQLState());
        }
        assertTrue(f.inserts.isEmpty());
        assertEquals(0, f.commits);
        assertEquals(1, f.drops());
        String tableName = f.createSql().substring("CREATE EXTERNAL TABLE ".length()).split(" ", 2)[0];
        assertTrue(f.targetSql.contains("DROP TABLE IF EXISTS " + tableName));
        assertFalse(f.targetSql.stream().anyMatch(sql -> sql.startsWith("DROP DATABASE")));
    }

    @Test
    public void stopAfterInsertReturnsCannotCommitOrReportSuccess() throws Exception {
        Fixture f = new Fixture();
        f.stopAfterInsertAt = 1;
        List<Integer> progress = new ArrayList<>();
        try (StarRocksExternalFullImport importer = f.prepare("times_tamp",
                (current, total, message) -> progress.add(current))) {
            importer.execute();
            fail("Expected stopped import");
        } catch (DataSyncCancellation.SyncStoppedException expected) {
            assertEquals(Collections.singletonList(0), progress);
        }
        assertEquals(1, f.inserts.size());
        assertEquals(0, f.commits);
        assertEquals(1, f.drops());
    }

    @Test
    public void stopAfterFirstYearDoesNotImportFollowingYears() throws Exception {
        Fixture f = new Fixture();
        List<Integer> progress = new ArrayList<>();
        try (StarRocksExternalFullImport importer = f.prepare("times_tamp", (current, total, message) -> {
            progress.add(current);
            if (current > 0) f.cancellation.requestStop();
        })) {
            importer.execute();
            fail("Expected stopped import");
        } catch (DataSyncCancellation.SyncStoppedException expected) {
            assertEquals(Arrays.asList(0, 5), progress);
        }
        assertEquals(1, f.inserts.size());
        assertEquals(1, f.commits);
        assertEquals(1, f.drops());
    }

    @Test
    public void stopAfterLastYearDoesNotReportCompletedImport() throws Exception {
        Fixture f = new Fixture();
        List<Integer> progress = new ArrayList<>();
        try (StarRocksExternalFullImport importer = f.prepare("times_tamp", (current, total, message) -> {
            progress.add(current);
            if (current == 12) f.cancellation.requestStop();
        })) {
            importer.execute();
            fail("Expected stopped import");
        } catch (DataSyncCancellation.SyncStoppedException expected) {
            assertEquals(Arrays.asList(0, 5, 12), progress);
        }
        assertEquals(2, f.inserts.size());
        assertEquals(2, f.commits);
        assertEquals(1, f.drops());
    }

    @Test
    public void stopCancelsBlockedInsertAndStillCleansExternalTable() throws Exception {
        Fixture f = new Fixture();
        f.jsonColumn = true;
        f.blockInsert = true;
        List<Integer> progress = new ArrayList<>();
        StarRocksExternalFullImport importer = f.prepare("times_tamp",
                (current, total, message) -> progress.add(current));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> running = worker.submit(() -> {
                try (StarRocksExternalFullImport owned = importer) {
                    owned.execute();
                    return null;
                }
            });
            assertTrue("INSERT SELECT did not start", f.insertEntered.await(2, TimeUnit.SECONDS));
            f.cancellation.requestStop();
            try {
                running.get(2, TimeUnit.SECONDS);
                fail("Expected stopped import");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause().toString(),
                        expected.getCause() instanceof DataSyncCancellation.SyncStoppedException);
            }
            assertEquals(1, f.cancelCalls);
            assertEquals(1, f.inserts.size());
            assertEquals(0, f.commits);
            assertEquals(Collections.singletonList(0), progress);
            assertEquals(1, f.drops());
        } finally {
            f.insertReleased.countDown();
            worker.shutdownNow();
            f.cancellation.close();
        }
    }

    @Test
    public void unsupportedTypeFailsBeforeCreatingAnyTargetObjects() throws Exception {
        Fixture f = new Fixture();
        f.idType = "geometry";
        try {
            f.prepare("times_tamp", null);
            fail("Expected unsupported type");
        } catch (StarRocksExternalFullImport.UnsupportedImportException expected) {
            assertTrue(expected.getMessage().contains("geometry"));
        }
        assertTrue(f.targetSql.isEmpty());
    }

    @Test
    public void ordinaryTimeoutParametersPermitExternalImport() throws Exception {
        Fixture f = new Fixture();
        f.ds.setJdbcUrl("jdbc:mysql://source.test:4000/source?socketTimeout=600000&connectTimeout=10000&useSSL=false");
        try (StarRocksExternalFullImport ignored = f.prepare(null, null)) {
            assertFalse(f.createSql().isEmpty());
        }
    }

    @Test
    public void defaultMySqlPreferredTlsParametersPermitExternalImport() throws Exception {
        Fixture f = new Fixture();
        f.ds.setDatasourceType("MYSQL");
        f.ds.setJdbcUrl("jdbc:mysql://source.test:4000/source?useUnicode=true&characterEncoding=utf8"
                + "&zeroDateTimeBehavior=convertToNull&useSSL=true&serverTimezone=GMT%2B8");
        StarRocksExternalFullImport.Endpoint endpoint = StarRocksExternalFullImport.parseEndpoint(f.ds.getJdbcUrl());
        assertEquals("source.test", endpoint.host);
        assertEquals(4000, endpoint.port);
        try (StarRocksExternalFullImport ignored = f.prepare(null, null)) {
            assertFalse(f.createSql().isEmpty());
        }
    }

    @Test
    public void explicitNonRequiredTlsModeOverridesLegacyTlsFlagsInEitherOrder() throws Exception {
        for (String parameter : Arrays.asList(
                "sslMode=DISABLED&useSSL=true&requireSSL=true&verifyServerCertificate=true",
                "useSSL=true&requireSSL=true&verifyServerCertificate=true&sslMode=DISABLED",
                "sslMode=PREFERRED&useSSL=true&requireSSL=true&verifyServerCertificate=true")) {
            StarRocksExternalFullImport.Endpoint endpoint = StarRocksExternalFullImport.parseEndpoint(
                    "jdbc:mysql://source.test:4000/source?" + parameter);
            assertEquals("source.test", endpoint.host);
            assertEquals(4000, endpoint.port);
        }
    }

    @Test
    public void disabledLegacyTlsIgnoresLegacyRequireAndVerifyFlags() throws Exception {
        StarRocksExternalFullImport.Endpoint endpoint = StarRocksExternalFullImport.parseEndpoint(
                "jdbc:mysql://source.test:4000/source?useSSL=false&requireSSL=true&verifyServerCertificate=true");
        assertEquals("source.test", endpoint.host);
        assertEquals(4000, endpoint.port);
    }

    @Test
    public void doesNotLowerExistingLongerQueryTimeoutOrChangeGlobalSettings() throws Exception {
        Fixture f = new Fixture();
        f.queryTimeout = 7200;
        try (StarRocksExternalFullImport ignored = f.prepare(null, null)) {
            assertFalse(f.targetSql.stream().anyMatch(sql -> sql.startsWith("SET SESSION query_timeout")));
            assertFalse(f.targetSql.stream().anyMatch(sql -> sql.startsWith("SET GLOBAL")));
        }
    }

    @Test
    public void sourceTextDeterminesYearWindowsWhileCheckpointKeepsJdbcTimestamp() throws Exception {
        Fixture f = new Fixture();
        f.min = Timestamp.valueOf("2025-01-01 07:00:00");
        f.max = Timestamp.valueOf("2026-01-01 07:00:00");
        f.rawMin = "2024-12-31 23:00:00";
        f.rawMax = "2025-12-31 23:00:00";
        try (StarRocksExternalFullImport importer = f.prepare("times_tamp", null)) {
            assertEquals(f.max, importer.execute().maxTs);
        }
        assertTrue(f.inserts.get(0).contains(" >= '2024-01-01 00:00:00'"));
        assertTrue(f.inserts.get(1).contains(" >= '2025-01-01 00:00:00'"));
        assertEquals(2, f.inserts.size());
    }

    @Test
    public void differentTimestampSessionZoneFallsBackBeforeTargetDdl() throws Exception {
        Fixture f = new Fixture();
        f.sessionZone = "+00:00";
        try {
            f.prepare("times_tamp", null);
            fail("Expected unsupported session time zone");
        } catch (StarRocksExternalFullImport.UnsupportedImportException expected) {
            assertTrue(expected.getMessage().contains("时区"));
            assertTrue(f.targetSql.isEmpty());
        }
    }

    @Test
    public void jdbcSessionOverridesFallBackBeforeTargetDdl() throws Exception {
        for (String parameter : Arrays.asList("sessionVariables=sql_mode%3DANSI_QUOTES", "forceConnectionTimeZoneToSession=true")) {
            Fixture f = new Fixture();
            f.ds.setJdbcUrl("jdbc:mysql://source.test:4000/source?" + parameter);
            try {
                f.prepare(null, null);
                fail("Expected unsupported JDBC session override");
            } catch (StarRocksExternalFullImport.UnsupportedImportException expected) {
                assertNull(expected.getCause());
                assertTrue(f.targetSql.isEmpty());
            }
        }
    }

    @Test
    public void requiredTlsFallsBackBeforeAnyTargetDdl() throws Exception {
        for (String parameter : Arrays.asList("requireSSL=true", "useSSL=true&requireSSL=true",
                "verifyServerCertificate=true", "useSSL=true&verifyServerCertificate=true",
                "sslMode=REQUIRED", "sslMode=VERIFY_CA", "sslMode=VERIFY_IDENTITY")) {
            Fixture f = new Fixture();
            f.ds.setJdbcUrl("jdbc:mysql://source.test:4000/source?" + parameter);
            try {
                f.prepare(null, null);
                fail("Expected unsupported TLS configuration");
            } catch (StarRocksExternalFullImport.UnsupportedImportException expected) {
                assertTrue(expected.getMessage().contains("TLS"));
                assertNull(expected.getCause());
                assertTrue(f.targetSql.isEmpty());
            }
        }
    }

    @Test
    public void malformedUrlEncodingDoesNotExposeOriginalValue() throws Exception {
        Fixture f = new Fixture();
        f.ds.setJdbcUrl("jdbc:mysql://source.test:4000/source?characterEncoding=%zz-sensitive-value");
        try {
            f.prepare(null, null);
            fail("Expected invalid URL encoding");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().contains("sensitive-value"));
            assertNull(expected.getCause());
            assertTrue(f.targetSql.isEmpty());
        }
    }

    @Test
    public void invalidOrOverriddenConnectionCannotReachTargetDdl() throws Exception {
        for (String url : Arrays.asList("jdbc:mysql:loadbalance://source.test:4000/source",
                "jdbc:mysql://source.test:4000,other.test:4000/source",
                "jdbc:mysql://source.test:4000/source?socketFactory=custom.Factory",
                "jdbc:mysql://source.test:4000/source?user=another")) {
            Fixture f = new Fixture();
            f.ds.setJdbcUrl(url);
            try {
                f.prepare(null, null);
                fail("Expected invalid source URL");
            } catch (IllegalArgumentException expected) {
                assertTrue(f.targetSql.isEmpty());
            }
        }
        Fixture f = new Fixture();
        f.ds.setHost("other.test");
        try {
            f.prepare(null, null);
            fail("Expected host mismatch");
        } catch (IllegalArgumentException expected) {
            assertTrue(f.targetSql.isEmpty());
        }
    }

    @Test
    public void preservesNumericRangeAndRejectsUnrepresentableDecimal() {
        assertEquals("BIGINT", type("int", "int(10) unsigned", 10, 0));
        assertEquals("DECIMAL(20,0)", type("bigint", "bigint(20) unsigned", 20, 0));
        assertEquals("DECIMAL(38,9)", type("decimal", "decimal(38,9)", 38, 9));
        assertEquals("DOUBLE", type("double", "double", 53, 0));
        assertEquals("DATETIME", StarRocksExternalFullImport.mapType(new StarRocksExternalFullImport.Column(
                "timestamp", "timestamp(6)", 0, 0, 6, true)));
        try {
            type("decimal", "decimal(65,30)", 65, 30);
            fail("Expected precision rejection");
        } catch (StarRocksExternalFullImport.UnsupportedImportException expected) {
            assertTrue(expected.getMessage().contains("38"));
        }
    }

    @Test
    public void binaryBitAndTimeCannotSilentlyBecomeNull() {
        for (String raw : Arrays.asList("bit(1)", "bit(8)", "bit(64)", "blob", "binary", "varbinary", "time")) {
            String type = raw.contains("(") ? raw.substring(0, raw.indexOf('(')) : raw;
            try {
                type(type, raw, 0, 0);
                fail("Expected unsafe scanner type to use JDBC");
            } catch (StarRocksExternalFullImport.UnsupportedImportException expected) {
                assertNull(expected.getCause());
            }
        }
    }

    @Test
    public void jsonUsesRawExternalTextAndOneGuardedProjectionForPreflightExplainAndInsert() throws Exception {
        Fixture f = new Fixture();
        f.jsonColumn = true;
        try (StarRocksExternalFullImport importer = f.prepare("times_tamp", null)) {
            assertEquals(12L, importer.execute().processed);
        }
        assertTrue(f.createSql().contains("`payload` VARCHAR(1048576) NULL"));
        assertEquals(1, Collections.frequency(f.sourceQueries,
                "SELECT COUNT(*), MAX(OCTET_LENGTH(CAST(`payload` AS CHAR CHARACTER SET utf8mb4))) FROM `source`.`orders` WHERE `times_tamp` IS NOT NULL"));
        assertTrue(f.targetSql.contains(StarRocksExternalFullImport.JSON_CONVERSION_PROBE_SQL));
        assertTrue(f.targetSql.contains("DESCRIBE `warehouse`.`orders`"));
        String insert = f.inserts.get(0);
        String projection = insert.substring(insert.indexOf(" SELECT ") + 8, insert.indexOf(" FROM "));
        assertTrue(projection.startsWith("`ID`, `times_tamp`, IF(ASSERT_TRUE(`payload` IS NULL OR "));
        String parsed = "JSON_QUERY(PARSE_JSON(CONCAT('[', `payload`, ']')), '$[0]')";
        // StarRocks 3.2.6's native JSON IS NOT NULL rejects JSON null as well as SQL NULL.
        // Serializing first keeps valid JSON null (the text "null") distinguishable from parse failure.
        assertTrue(projection.contains("OR JSON_STRING(" + parsed + ") IS NOT NULL, '"));
        assertFalse(projection.contains(parsed + " IS NOT NULL"));
        assertTrue(f.targetSql.stream().anyMatch(sql -> sql.startsWith("SELECT " + projection + " FROM `easyreport_import_tmp`.")));
        assertTrue(f.targetSql.stream().anyMatch(sql -> sql.startsWith("SELECT " + projection + " FROM `easyreport_import_tmp`.")
                && sql.endsWith(" WHERE `times_tamp` IS NOT NULL LIMIT 1")));
        assertTrue(f.targetSql.stream().anyMatch(sql -> sql.startsWith("EXPLAIN INSERT") && sql.contains(" SELECT " + projection + " FROM ")));
        assertTrue(f.inserts.stream().allMatch(sql -> sql.contains(" SELECT " + projection + " FROM ")));
        assertFalse(f.inserts.stream().anyMatch(sql -> sql.contains("WHERE ASSERT_TRUE")));
        assertEquals(1, f.drops());
    }

    @Test
    public void jsonAndTextTargetsKeepEffectiveRowThresholdAndNullTimestampFiltering() throws Exception {
        for (String targetType : Arrays.asList("JSON", "STRING", "VARCHAR")) {
            for (long sourceCount : new long[]{499999, 500000}) {
                Fixture f = new Fixture();
                f.jsonColumn = true;
                f.jsonTargetType = targetType;
                f.sourceCount = sourceCount;
                f.counts = sourceCount < 500000 ? new long[]{sourceCount} : new long[]{250000, 250000};
                try (StarRocksExternalFullImport importer = f.prepare("times_tamp", null)) {
                    StarRocksExternalFullImport.Result result = importer.execute();
                    assertEquals(sourceCount, result.processed);
                    assertEquals(f.max, result.maxTs);
                }
                assertEquals(sourceCount < 500000 ? 1 : 2, f.inserts.size());
                if (sourceCount < 500000) assertTrue(f.inserts.get(0).endsWith("WHERE `times_tamp` IS NOT NULL"));
                else assertTrue(f.inserts.get(0).contains("WHERE `times_tamp` >= '2023-01-01"));
                if (!"JSON".equals(targetType)) {
                    assertTrue(f.inserts.get(0).contains(" SELECT `ID`, `times_tamp`, `payload` FROM "));
                    assertFalse(f.targetSql.contains(StarRocksExternalFullImport.JSON_CONVERSION_PROBE_SQL));
                }
                assertFalse(f.inserts.stream().anyMatch(sql -> sql.contains("WHERE `times_tamp` IS NULL")));
            }
        }
    }

    @Test
    public void jsonLengthPreflightUsesBytesAndReservesWrapperSpaceBeforeCreatingExternalTable() throws Exception {
        String escapedUnicodeJson = "\"中文🙂\\\\\\\"行\\n\"";
        int utf8Bytes = escapedUnicodeJson.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(utf8Bytes > escapedUnicodeJson.length());
        Object[][] cases = {
                {"JSON", 0, 1048574L, null}, {"JSON", 0, 1048575L, StarRocksExternalFullImport.UnsupportedImportException.class},
                {"STRING", 0, 65533L, null}, {"STRING", 1048576, 65534L, IllegalArgumentException.class},
                {"VARCHAR", 1048576, 1048576L, null}, {"VARCHAR", 1048576, 1048577L, IllegalArgumentException.class},
                {"VARCHAR", 2097152, 1048577L, StarRocksExternalFullImport.UnsupportedImportException.class},
                {"VARCHAR", utf8Bytes, (long) utf8Bytes, null},
                {"VARCHAR", utf8Bytes - 1, (long) utf8Bytes, IllegalArgumentException.class},
                {"VARCHAR", 0, 1L, IllegalArgumentException.class}, {"JSON", 0, null, null}
        };
        for (Object[] testCase : cases) {
            Fixture f = new Fixture();
            f.jsonColumn = true;
            f.jsonTargetType = (String) testCase[0];
            f.jsonTargetPrecision = (Integer) testCase[1];
            f.jsonMaxBytes = (Long) testCase[2];
            Class<?> expectedFailure = (Class<?>) testCase[3];
            try (StarRocksExternalFullImport ignored = f.prepare("times_tamp", null)) {
                assertNull("Oversize JSON must be rejected before external table creation", expectedFailure);
            } catch (IllegalArgumentException expected) {
                assertEquals("Target capacity errors must not trigger JDBC fallback", expectedFailure, expected.getClass());
                assertTrue(f.createSql().isEmpty());
                assertTrue(f.inserts.isEmpty());
                assertEquals(0, f.drops());
            }
        }
    }

    @Test
    public void jsonScalarAndNullProbeMustSucceedBeforeExternalTableCreation() throws Exception {
        String[][] incorrectResults = {
                {"\"123\"", "true", "null", null}, {"123", "\"true\"", "null", null},
                {"123", "true", null, null}, {"123", "true", "null", "null"}
        };
        for (String[] result : incorrectResults) {
            Fixture f = new Fixture();
            f.jsonColumn = true;
            f.jsonProbeValues = result;
            try {
                f.prepare("times_tamp", null);
                fail("Scalar JSON types and SQL NULL must not be silently changed");
            } catch (IllegalStateException expected) {
                assertTrue(f.createSql().isEmpty());
                assertTrue(f.inserts.isEmpty());
                assertEquals(0, f.drops());
                if (result[2] == null) {
                    assertTrue(expected.getMessage(), expected.getMessage().contains("JSON null"));
                    assertTrue(expected.getMessage(), expected.getMessage().contains("SQL NULL"));
                }
            }
        }
        Fixture unavailable = new Fixture();
        unavailable.jsonColumn = true;
        unavailable.failJsonProbe = true;
        try {
            unavailable.prepare("times_tamp", null);
            fail("Unavailable JSON functions must fail before target clear");
        } catch (SQLException expected) {
            assertEquals(456, expected.getErrorCode());
            assertTrue(unavailable.createSql().isEmpty());
        }
    }

    @Test
    public void incompatibleJsonTargetTypesFailBeforeDdlRatherThanSilentlyFallingBack() throws Exception {
        for (String type : Arrays.asList("CHAR", "INT")) {
            Fixture f = new Fixture();
            f.jsonColumn = true;
            f.jsonTargetType = type;
            try {
                f.prepare("times_tamp", null);
                fail("Incompatible JSON target must be rejected");
            } catch (IllegalArgumentException expected) {
                assertFalse(expected instanceof StarRocksExternalFullImport.UnsupportedImportException);
                assertTrue(f.createSql().isEmpty());
                assertTrue(f.inserts.isEmpty());
            }
        }
        Fixture missing = new Fixture();
        missing.jsonColumn = true;
        missing.missingJsonTarget = true;
        try {
            missing.prepare("times_tamp", null);
            fail("Missing JSON target column must be rejected");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected instanceof StarRocksExternalFullImport.UnsupportedImportException);
            assertTrue(missing.createSql().isEmpty());
        }
    }

    @Test
    public void escapesIdentifiersAndCredentialProperties() {
        assertEquals("`a``b`", StarRocksExternalFullImport.quoteIdentifier("a`b"));
        assertEquals("\"a\\\"b\\\\c\\n\"", StarRocksExternalFullImport.property("a\"b\\c\n"));
    }

    private String type(String type, String raw, int precision, int scale) {
        return StarRocksExternalFullImport.mapType(new StarRocksExternalFullImport.Column(type, raw, precision, scale, 0, true));
    }

    private static void assertWindow(String insert, String start, String end) {
        assertTrue(insert, insert.endsWith("WHERE `times_tamp` >= '" + start
                + " 00:00:00' AND `times_tamp` < '" + end + " 00:00:00'"));
    }

    private static final class Fixture {
        final ErDatasource ds = new ErDatasource();
        final DataSyncCancellation cancellation = new DataSyncCancellation();
        final CountDownLatch insertEntered = new CountDownLatch(1);
        final CountDownLatch insertReleased = new CountDownLatch(1);
        final CountDownLatch countEntered = new CountDownLatch(1);
        final CountDownLatch countReleased = new CountDownLatch(1);
        final List<String> sourceQueries = new ArrayList<>();
        final List<String> targetSql = new ArrayList<>();
        final List<String> inserts = new ArrayList<>();
        Timestamp min = Timestamp.valueOf("2023-12-31 23:59:59");
        Timestamp max = Timestamp.valueOf("2024-12-31 12:00:00.123456");
        Long sourceCount = 500000L;
        boolean jsonColumn;
        String jsonTargetType = "JSON";
        int jsonTargetPrecision = 1048576;
        Long jsonMaxBytes = 128L;
        boolean failJsonProbe;
        String[] jsonProbeValues = {"123", "true", "null", null};
        boolean missingJsonTarget;
        String rawMin;
        String rawMax;
        String sessionZone = "+08:00";
        String globalZone = "+08:00";
        long[] counts = {5, 7};
        int commits;
        int failInsertAt;
        int stopAfterInsertAt;
        int cancelCalls;
        boolean stopAfterCreate;
        boolean blockInsert;
        boolean blockCount;
        boolean failCount;
        boolean countHasRow = true;
        boolean boundsHasRow = true;
        boolean nullable = true;
        boolean failPreflight;
        boolean failExplain;
        long queryTimeout = 300;
        String idType = "bigint";

        Fixture() {
            ds.setJdbcUrl("jdbc:mysql://source.test:4000/source?useUnicode=true&serverTimezone=Asia%2FShanghai");
            ds.setHost("source.test");
            ds.setPort(4000);
            ds.setUsername("import_test_user");
            ds.setPassword("test-password-\"'\\value");
        }

        StarRocksExternalFullImport prepare(String ts, cn.easyreport.sync.core.SyncProgressCallback callback) throws Exception {
            return StarRocksExternalFullImport.prepare(ds, cancellation.wrap(source()), cancellation.wrap(target()),
                    "source", "orders", "`warehouse`.`orders`", jsonColumn ? Arrays.asList("ID", "times_tamp", "payload") : Arrays.asList("ID", "times_tamp"),
                    jsonColumn ? Arrays.asList("id", "times_tamp", "payload") : Arrays.asList("id", "times_tamp"), ts, 42L, 1, callback, cancellation);
        }

        StarRocksExternalFullImport prepare(String ts, Integer thresholdWan, String unit,
                cn.easyreport.sync.core.SyncProgressCallback callback) throws Exception {
            return StarRocksExternalFullImport.prepare(ds, cancellation.wrap(source()), cancellation.wrap(target()),
                    "source", "orders", "`warehouse`.`orders`", jsonColumn ? Arrays.asList("ID", "times_tamp", "payload") : Arrays.asList("ID", "times_tamp"),
                    jsonColumn ? Arrays.asList("id", "times_tamp", "payload") : Arrays.asList("id", "times_tamp"), ts, 42L,
                    thresholdWan, unit, callback, cancellation);
        }

        Connection source() {
            return proxy(Connection.class, (object, method, args) -> {
                switch (method.getName()) {
                    case "getMetaData": return proxy(DatabaseMetaData.class, (o, m, a) -> "getURL".equals(m.getName()) ? ds.getJdbcUrl() : null);
                    case "prepareStatement":
                        sourceQueries.add((String) args[0]);
                        return proxy(PreparedStatement.class, (o, m, a) -> {
                            if ("executeQuery".equals(m.getName())) {
                                List<Map<Object, Object>> columns = new ArrayList<>(Arrays.asList(
                                        column("ID", idType, idType + "(20)", false),
                                        column("times_tamp", "timestamp", "timestamp(6)", nullable)));
                                if (jsonColumn) columns.add(column("payload", "json", "json", true));
                                return rows(columns);
                            }
                            return null;
                        });
                    case "createStatement": return proxy(Statement.class, (o, m, a) -> {
                        if ("executeQuery".equals(m.getName())) {
                            String sql = (String) a[0];
                            sourceQueries.add(sql);
                            if (sql.contains("@@session.time_zone")) {
                                return rows(Collections.singletonList(indexed(sessionZone, globalZone)));
                            }
                            if (sql.startsWith("SELECT COUNT(*)")) {
                                if (blockCount) {
                                    countEntered.countDown();
                                    if (!countReleased.await(5, TimeUnit.SECONDS)) throw new AssertionError("COUNT was not cancelled");
                                    throw new SQLException("Count query cancelled", "HY000", 1317);
                                }
                                if (failCount) throw new SQLException("source count failed", "HY000", 321);
                                return rows(countHasRow ? Collections.singletonList(jsonColumn
                                        ? indexed(sourceCount, jsonMaxBytes) : indexed(sourceCount)) : Collections.emptyList());
                            }
                            if (sql.contains("MIN(")) {
                                Map<Object, Object> row = indexed(min, max);
                                if (rawMin != null) row.put("raw:1", rawMin);
                                if (rawMax != null) row.put("raw:2", rawMax);
                                return rows(boundsHasRow ? Collections.singletonList(row) : Collections.emptyList());
                            }
                            return rows(Collections.singletonList(indexed(1)));
                        }
                        if ("cancel".equals(m.getName())) {
                            cancelCalls++;
                            countReleased.countDown();
                        }
                        return null;
                    });
                    default: return null;
                }
            });
        }

        Connection target() {
            return proxy(Connection.class, (object, method, args) -> {
                switch (method.getName()) {
                    case "getAutoCommit": return false;
                    case "commit": commits++; return null;
                    case "createStatement": return proxy(Statement.class, (o, m, a) -> {
                        if ("execute".equals(m.getName())) {
                            String sql = (String) a[0];
                            targetSql.add(sql);
                            if (stopAfterCreate && sql.startsWith("CREATE EXTERNAL TABLE")) cancellation.requestStop();
                            return false;
                        }
                        if ("executeQuery".equals(m.getName())) {
                            String sql = (String) a[0];
                            targetSql.add(sql);
                            if (jsonColumn && sql.equals("DESCRIBE `warehouse`.`orders`")) {
                                Map<Object, Object> id = new HashMap<>(); id.put("Field", "id"); id.put("Type", "BIGINT");
                                Map<Object, Object> timestamp = new HashMap<>(); timestamp.put("Field", "times_tamp"); timestamp.put("Type", "DATETIME");
                                Map<Object, Object> payload = new HashMap<>(); payload.put("Field", "payload");
                                payload.put("Type", "VARCHAR".equals(jsonTargetType) ? "VARCHAR(" + jsonTargetPrecision + ")" : jsonTargetType);
                                return rows(missingJsonTarget ? Arrays.asList(id, timestamp) : Arrays.asList(id, timestamp, payload));
                            }
                            if (jsonColumn && sql.equals(StarRocksExternalFullImport.JSON_CONVERSION_PROBE_SQL)) {
                                if (failJsonProbe) throw new SQLException("JSON probe unavailable", "HY000", 456);
                                return rows(Collections.singletonList(indexed((Object[]) jsonProbeValues)));
                            }
                            if (sql.equals("SHOW VARIABLES LIKE 'enable_insert_strict'")) return rows(Collections.singletonList(indexed("enable_insert_strict", "true")));
                            if (sql.equals("SHOW VARIABLES LIKE 'query_timeout'")) return rows(Collections.singletonList(indexed("query_timeout", queryTimeout)));
                            if (sql.startsWith("EXPLAIN")) {
                                if (failExplain) throw new SQLException("target column mismatch", "HY000", 124);
                                return rows(Collections.singletonList(indexed("INSERT PLAN")));
                            }
                            if (failPreflight) throw new SQLException("Driver echoed " + createSql() + ds.getPassword(), "HY000", 123);
                            return rows(Collections.singletonList(indexed(1L, max)));
                        }
                        if ("executeLargeUpdate".equals(m.getName())) {
                            inserts.add((String) a[0]);
                            if (blockInsert) {
                                insertEntered.countDown();
                                if (!insertReleased.await(5, TimeUnit.SECONDS)) throw new AssertionError("INSERT was not cancelled");
                                throw new SQLException("Query was cancelled by driver", "HY000", 1317);
                            }
                            if (inserts.size() == stopAfterInsertAt) cancellation.requestStop();
                            if (inserts.size() == failInsertAt) throw new SQLException("insert failed", "HY000", 234);
                            return counts[inserts.size() - 1];
                        }
                        if ("cancel".equals(m.getName())) {
                            cancelCalls++;
                            insertReleased.countDown();
                            return null;
                        }
                        return null;
                    });
                    default: return null;
                }
            });
        }

        String createSql() {
            return targetSql.stream().filter(sql -> sql.startsWith("CREATE EXTERNAL TABLE")).findFirst().orElse("");
        }

        long drops() { return targetSql.stream().filter(sql -> sql.startsWith("DROP TABLE")).count(); }

        Map<Object, Object> column(String name, String type, String raw, boolean nullable) {
            Map<Object, Object> row = new HashMap<>();
            row.put("COLUMN_NAME", name);
            row.put("DATA_TYPE", type);
            row.put("COLUMN_TYPE", raw);
            row.put("NUMERIC_PRECISION", 20);
            row.put("NUMERIC_SCALE", 0);
            row.put("DATETIME_PRECISION", "timestamp".equals(type) ? 6 : 0);
            row.put("IS_NULLABLE", nullable ? "YES" : "NO");
            return row;
        }
    }

    private static Map<Object, Object> indexed(Object... values) {
        Map<Object, Object> row = new HashMap<>();
        for (int i = 0; i < values.length; i++) row.put(i + 1, values[i]);
        return row;
    }

    private static ResultSet rows(List<Map<Object, Object>> rows) {
        int[] index = {-1};
        boolean[] wasNull = {false};
        return proxy(ResultSet.class, (object, method, args) -> {
            String name = method.getName();
            if ("next".equals(name)) return ++index[0] < rows.size();
            if ("wasNull".equals(name)) return wasNull[0];
            if ("getMetaData".equals(name)) throw new AssertionError("JSON target types must come from DESCRIBE, not MySQL protocol metadata");
            if (name.startsWith("get")) {
                Object value = rows.get(index[0]).get(args[0]);
                wasNull[0] = value == null;
                if ("getInt".equals(name)) return value == null ? 0 : ((Number) value).intValue();
                if ("getLong".equals(name)) return value == null ? 0L : ((Number) value).longValue();
                if ("getString".equals(name)) {
                    Object raw = rows.get(index[0]).get("raw:" + args[0]);
                    return raw != null ? raw.toString() : value == null ? null : value.toString();
                }
                return value;
            }
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
