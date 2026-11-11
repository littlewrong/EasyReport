package cn.easyreport.sync.datasync;

import cn.easyreport.sync.domain.ErDataTransfer;
import cn.easyreport.sync.domain.ErDataTransferLog;
import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.mapper.ErDataTransferProgressMapper;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Resource and import lifecycle contracts; no database, driver download, or real Resource is touched. */
public class StarRocksJdbcResourceFullImportTest {
    private static final List<String> COLUMNS = Arrays.asList("Id", "Name", "time_stamp");
    private static final Timestamp MIN_TS = Timestamp.valueOf("2023-12-31 00:00:00");
    private static final Timestamp MAX_TS = Timestamp.valueOf("2024-01-02 12:34:56.123456");

    @Test
    public void allEightSourceFamiliesUsePrebuiltResourceAndPreserveColumnNames() throws Exception {
        for (String sourceType : Arrays.asList("MYSQL", "TIDB", "STARROCKS", "KINGBASE", "DM", "SQLSERVER", "POSTGRESQL", "ORACLE")) {
            Fixture f = new Fixture(sourceType);
            try (StarRocksJdbcResourceFullImport importer = f.prepare(null, 50, "YEAR")) {
                StarRocksJdbcResourceFullImport.Result result = importer.execute();
                assertEquals(7, result.processed);
                assertNull(result.maxTs);
            }
            String ddl = f.targetSql.stream().filter(sql -> sql.startsWith("CREATE EXTERNAL TABLE")).findFirst().get();
            assertTrue(ddl, ddl.contains("ENGINE=JDBC"));
            assertTrue(ddl, ddl.contains("\"resource\"=\"jdbc_prebuilt\""));
            assertTrue(ddl, ddl.toLowerCase(java.util.Locale.ROOT).contains("rf_user"));
            assertTrue(ddl, ddl.toLowerCase(java.util.Locale.ROOT).contains("KINGBASE".equals(sourceType) ? "`er_c0` bigint" : "`id` bigint"));
            assertTrue(f.sourceSql.toString(), f.sourceSql.stream().anyMatch(sql -> sql.startsWith("SELECT ")
                    && sql.toLowerCase(java.util.Locale.ROOT).contains("rf_user") && sql.endsWith("WHERE 1=0")));
            assertEquals(1, f.inserts.size());
            assertEquals(1, f.dropCount());
            f.assertUserResourceUntouched();
        }
    }

    @Test
    public void allEightSourcesAllowIndependentResourceEndpointsAndPreflightBeforeClear() throws Exception {
        Map<String, String> resourceUris = new LinkedHashMap<>();
        resourceUris.put("MYSQL", "jdbc:mysql://resource.internal:13306/resource_db?useSSL=false");
        resourceUris.put("TIDB", "jdbc:mysql://resource.internal:14000/resource_db?useSSL=false");
        resourceUris.put("STARROCKS", "jdbc:mysql://resource.internal:19030/resource_db?useSSL=false");
        resourceUris.put("KINGBASE", "jdbc:kingbase8://resource.internal:15432/resource_db");
        // DM URLs have no database segment; its server and port may still use the BE's address.
        resourceUris.put("DM", "jdbc:dm://resource.internal:15236");
        resourceUris.put("SQLSERVER", "jdbc:sqlserver://resource.internal:11433;databaseName=resource_db;encrypt=false");
        resourceUris.put("POSTGRESQL", "jdbc:postgresql://resource.internal:15432/resource_db");
        resourceUris.put("ORACLE", "jdbc:oracle:thin:@//resource.internal:11521/resource_service");
        for (Map.Entry<String, String> endpoint : resourceUris.entrySet()) {
            Fixture f = new Fixture(endpoint.getKey());
            String actualSourceUrl = f.datasource.getJdbcUrl();
            f.properties.put("jdbc_uri", endpoint.getValue());

            Object result = f.runFullSync("time_stamp", 50, "YEAR");

            assertEquals(Long.valueOf(7), ((ErDataTransferLog) field(result, "log")).getSuccessCount());
            assertEquals(actualSourceUrl, f.datasource.getJdbcUrl());
            assertEquals("appdb", f.datasource.getDatabaseName());
            assertNotEquals(actualSourceUrl, f.properties.get("jdbc_uri"));
            String ddl = f.targetSql.stream().filter(sql -> sql.startsWith("CREATE EXTERNAL TABLE")).findFirst().get();
            String ownedTable = ddl.substring("CREATE EXTERNAL TABLE ".length(), ddl.indexOf(" ("));
            assertTrue(ownedTable, ownedTable.matches("`easyreport_import_tmp`\\.`er_jdbc_import_42_[0-9a-f]{32}`"));
            if ("SQLSERVER".equals(endpoint.getKey())) {
                assertTrue(ddl, ddl.contains("\"table\"=\"[appdb].[dbo].[rf_user]\""));
                assertTrue(f.sourceSql.toString(), f.sourceSql.stream().anyMatch(sql -> sql.contains("[appdb].[dbo].[rf_user]")));
            }
            int clear = indexOf(f.targetSql, "TRUNCATE TABLE");
            assertTrue(endpoint.getKey(), clear > 0);
            List<String> beforeClear = f.targetSql.subList(0, clear);
            assertTrue(beforeClear.toString(), beforeClear.stream().anyMatch(sql -> sql.startsWith("SELECT ")
                    && sql.contains(" FROM " + ownedTable)
                    && ("SQLSERVER".equals(endpoint.getKey()) ? sql.startsWith("SELECT COUNT(*)") : sql.endsWith(" LIMIT 1"))));
            assertTrue(beforeClear.toString(), beforeClear.stream().anyMatch(sql -> sql.startsWith("EXPLAIN INSERT")));
            assertTrue(indexOf(f.targetSql, "INSERT INTO") > clear);
            List<String> drops = new ArrayList<>();
            for (String sql : f.targetSql) if (sql.startsWith("DROP ")) drops.add(sql);
            assertEquals(Collections.singletonList("DROP TABLE IF EXISTS " + ownedTable), drops);
            assertEquals(1, f.inserts.size());
            assertEquals(1, f.checkpoints.size());
            assertTrue(f.events.indexOf("drop") < f.events.indexOf("checkpoint"));
            f.assertUserResourceUntouched();
        }
    }

    @Test
    public void compatibleBitResourcesKeepNumericValuesOnInsertSelectPath() throws Exception {
        for (String sourceType : Arrays.asList("MYSQL", "TIDB")) {
            for (int width : new int[] {8, 64}) {
                Fixture f = new Fixture(sourceType);
                f.unsignedBitWidth = width;
                f.targetIdType = width == 8 ? "INT" : "LARGEINT";
                f.properties.put("driver_class", StarRocksJdbcResourceSource.MYSQL_BIT_COMPATIBLE_DRIVER);

                Object result = f.runFullSync("time_stamp", 50, "YEAR");

                assertEquals(Long.valueOf(7), ((ErDataTransferLog) field(result, "log")).getSuccessCount());
                String ddl = f.targetSql.stream().filter(sql -> sql.startsWith("CREATE EXTERNAL TABLE")).findFirst().get();
                assertTrue(ddl, ddl.contains("`Id` LARGEINT NULL"));
                assertTrue(ddl, ddl.contains("ENGINE=JDBC"));
                int clear = indexOf(f.targetSql, "TRUNCATE TABLE");
                assertTrue(clear > 0);
                assertTrue(f.targetSql.subList(0, clear).stream().anyMatch(sql -> sql.startsWith("SELECT `Id`")
                        && sql.contains(" FROM `easyreport_import_tmp`.")));
                assertTrue(f.targetSql.subList(0, clear).stream().anyMatch(sql -> sql.startsWith("EXPLAIN INSERT")));
                assertEquals(1, f.inserts.size());
                assertTrue(f.inserts.get(0), f.inserts.get(0).contains(" SELECT `Id`, `Name`, `time_stamp` FROM `easyreport_import_tmp`."));
                assertFalse(f.inserts.get(0), f.inserts.get(0).contains(" VALUES "));
                assertFalse(f.sourceSql.stream().anyMatch(sql -> sql.startsWith("SELECT *")));
                assertEquals(1, f.dropCount());
                assertEquals(1, f.checkpoints.size());
                f.assertUserResourceUntouched();
            }
        }
    }

    @Test
    public void multiBitRequiresCompatibleResourceAndSufficientTargetRangeBeforeClear() throws Exception {
        Fixture standard = new Fixture("MYSQL");
        standard.unsignedBitWidth = 8;
        standard.targetIdType = "INT";
        standard.expectPreclearFailure("time_stamp", StarRocksJdbcResourceSource.MYSQL_RESOURCE_COMPATIBLE_DRIVER);
        assertEquals(0, standard.dropCount());
        assertTrue(standard.inserts.isEmpty());
        for (int width : new int[] {8, 64}) {
            Fixture narrow = new Fixture("TIDB");
            narrow.unsignedBitWidth = width;
            narrow.targetIdType = width == 8 ? "TINYINT" : "BIGINT";
            narrow.properties.put("driver_class", StarRocksJdbcResourceSource.MYSQL_BIT_COMPATIBLE_DRIVER);
            narrow.actualIntegerMax = BigInteger.ONE.shiftLeft(width).subtract(BigInteger.ONE).toString();
            narrow.expectPreclearFailure("time_stamp", "实际整数值超出目标类型");
            assertEquals(1, narrow.dropCount());
            assertTrue(narrow.inserts.isEmpty());
            narrow.assertUserResourceUntouched();
        }
    }

    @Test
    public void unsignedTinyintActualResourceValuesCanFitAnExistingSignedTinyint() throws Exception {
        Fixture f = new Fixture("MYSQL");
        f.unsignedTinyint = true;
        f.targetIdType = "TINYINT";
        f.actualIntegerMax = "127";
        // Resource and source may have independent endpoints: only its real scan proves the range.
        f.properties.put("jdbc_uri", "jdbc:mysql://private:13306/other_db?useSSL=false");
        f.runFullSync("time_stamp", 50, "YEAR");
        String rangeSql = f.targetSql.stream().filter(sql -> sql.startsWith("SELECT COUNT(`Id`)"))
                .findFirst().get();
        assertTrue(rangeSql, rangeSql.contains("MIN(CAST(`Id` AS LARGEINT)), MAX(CAST(`Id` AS LARGEINT))"));
        assertTrue(rangeSql, rangeSql.endsWith("WHERE `time_stamp` IS NOT NULL"));
        assertFalse(rangeSql, rangeSql.contains(" LIMIT "));
        assertTrue(f.targetSql.indexOf(rangeSql) < indexOf(f.targetSql, "TRUNCATE TABLE"));
        assertTrue(f.targetSql.stream().anyMatch(sql -> sql.startsWith("CREATE EXTERNAL TABLE") && sql.contains("`Id` INT NULL")));
        String insert = f.inserts.get(0);
        assertTrue(insert, insert.contains("assert_true(`Id` IS NULL OR (CAST(`Id` AS LARGEINT) >= -128 AND CAST(`Id` AS LARGEINT) <= 127)"));
        assertTrue(insert, insert.contains("AS LARGEINT) * CAST(`Id` AS LARGEINT)"));
        assertFalse(insert.substring(insert.indexOf(" WHERE ")), insert.substring(insert.indexOf(" WHERE ")).contains("assert_true"));
        assertEquals(1, f.checkpoints.size());
    }

    @Test
    public void actualResourceUnsignedValuesOutsideTinyintFailBeforeTargetClear() throws Exception {
        for (String maximum : new String[]{"128", "255"}) {
            Fixture f = new Fixture("TIDB");
            f.unsignedTinyint = true;
            f.targetIdType = "TINYINT";
            f.actualIntegerMax = maximum;
            f.expectPreclearFailure("time_stamp", "实际整数值超出目标类型 TINYINT");
            assertEquals(1, f.dropCount());
            assertTrue(f.inserts.isEmpty());
        }
    }

    @Test
    public void resourceV2BinaryHexAndTimeTextUseOwnedTextTableAndRealCapacityCheck() throws Exception {
        for (String nativeType : new String[]{"VARBINARY(16)", "TIME(6)"}) {
            Fixture f = new Fixture("MYSQL");
            f.compatibleTextNativeType = nativeType;
            f.properties.put("driver_class", StarRocksJdbcResourceSource.MYSQL_RESOURCE_COMPATIBLE_DRIVER);
            f.textBytes = 32;
            f.actualTextBytes = 20L;
            f.targetTextType = "VARCHAR(20)";
            f.runFullSync("time_stamp", 50, "YEAR");
            assertTrue(f.targetSql.stream().anyMatch(sql -> sql.startsWith("CREATE EXTERNAL TABLE")
                    && sql.contains("`Name` VARCHAR(1048576) NULL")));
            assertTrue(f.inserts.get(0), f.inserts.get(0).contains("SELECT `Id`, `Name`, `time_stamp` FROM"));
            assertTrue(f.targetSql.stream().anyMatch(sql -> sql.startsWith("SELECT MAX(LENGTH(`Name`))")
                    && sql.endsWith("WHERE `time_stamp` IS NOT NULL")));
            assertEquals(1, f.checkpoints.size());
            f.assertUserResourceUntouched();

            Fixture narrow = new Fixture("TIDB");
            narrow.compatibleTextNativeType = nativeType;
            narrow.properties.put("driver_class", StarRocksJdbcResourceSource.MYSQL_RESOURCE_COMPATIBLE_DRIVER);
            narrow.textBytes = 32;
            narrow.actualTextBytes = 21L;
            narrow.targetTextType = "VARCHAR(20)";
            narrow.expectPreclearFailure("time_stamp", "实际 UTF-8 字节数超出目标字段容量");
            assertEquals(1, narrow.dropCount());
        }
    }

    @Test
    public void measuredBitAndLargeIntegerValuesCanUseNarrowerExactTargets() throws Exception {
        Fixture bits = new Fixture("MYSQL");
        bits.unsignedBitWidth = 64;
        bits.properties.put("driver_class", StarRocksJdbcResourceSource.MYSQL_BIT_COMPATIBLE_DRIVER);
        bits.targetIdType = "BIGINT";
        bits.actualIntegerMax = Long.toString(Long.MAX_VALUE);
        try (StarRocksJdbcResourceFullImport importer = bits.prepare(null, 50, "YEAR")) { importer.execute(); }
        assertTrue(bits.inserts.get(0).contains("<= 9223372036854775807"));
        Fixture decimal = new Fixture("STARROCKS");
        decimal.largeIntegerText = true;
        decimal.targetIdType = "DECIMAL(3,1)";
        decimal.actualIntegerMax = "99";
        try (StarRocksJdbcResourceFullImport importer = decimal.prepare(null, 50, "YEAR")) { importer.execute(); }
        assertTrue(decimal.inserts.get(0).contains("CAST(`Id` AS LARGEINT) >= -99 AND CAST(`Id` AS LARGEINT) <= 99"));
        Fixture overflow = new Fixture("STARROCKS");
        overflow.largeIntegerText = true;
        overflow.targetIdType = "DECIMAL(3,1)";
        overflow.actualIntegerMax = "100";
        overflow.expectPreclearFailure(null, "实际整数值超出目标类型 DECIMAL(3,1)");
    }

    @Test
    public void integerRangeScanAllowsAllNullsAndRejectsFailedNumericReads() throws Exception {
        Fixture nulls = new Fixture("MYSQL");
        nulls.targetIdType = "TINYINT";
        nulls.actualIntegerCount = 0L;
        nulls.actualIntegerMin = null;
        nulls.actualIntegerMax = null;
        try (StarRocksJdbcResourceFullImport ignored = nulls.prepare(null, 50, "YEAR")) { }
        Fixture malformed = new Fixture("STARROCKS");
        malformed.largeIntegerText = true;
        malformed.targetIdType = "TINYINT";
        malformed.actualIntegerConvertedCount = 6L;
        malformed.expectPreclearFailure(null, "存在无法无损读取的整数值");
        Fixture fractional = new Fixture("MYSQL");
        fractional.targetIdType = "TINYINT";
        fractional.actualIntegerMin = "1.5";
        fractional.expectPreclearFailure(null, "实际整数范围格式无效");
    }

    @Test
    public void sqlServerNarrowingScansWithoutLimitAndGuardsEveryNaturalWindow() throws Exception {
        Fixture f = new Fixture("SQLSERVER");
        f.targetIdType = "TINYINT";
        f.sourceCount = 20000;
        f.actualIntegerMax = "127";
        f.affected = new long[]{5, 7};
        f.runFullSync("time_stamp", 1, "YEAR");
        assertEquals(2, f.inserts.size());
        assertTrue(f.inserts.stream().allMatch(sql -> sql.contains("assert_true(") && sql.contains("<= 127")));
        assertFalse(f.targetSql.stream().anyMatch(sql -> sql.contains(" LIMIT ")));
        assertTrue(f.targetSql.stream().anyMatch(sql -> sql.startsWith("SELECT COUNT(`Id`)")
                && sql.endsWith("WHERE `time_stamp` IS NOT NULL")));
    }

    @Test
    public void laterNumericGuardFailureStopsInsertAndDoesNotAdvanceCheckpoint() throws Exception {
        Fixture f = new Fixture("MYSQL");
        f.targetIdType = "TINYINT";
        f.failNumericInsert = true;
        try { f.runFullSync("time_stamp", 50, "YEAR"); fail("Expected changed data to fail the guarded INSERT"); }
        catch (SQLException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("INSERT SELECT 导入失败"));
            assertFalse(expected.getMessage().contains("driver-secret"));
        }
        assertEquals(1, f.inserts.size());
        assertTrue(f.inserts.get(0).contains("assert_true("));
        assertTrue(f.checkpoints.isEmpty());
        assertEquals(1, f.dropCount());
    }

    @Test
    public void compatibleBitDriverReadFailureLeavesTargetUntouchedAndDoesNotFallback() throws Exception {
        Fixture f = new Fixture("MYSQL");
        f.unsignedBitWidth = 64;
        f.targetIdType = "LARGEINT";
        f.properties.put("driver_class", StarRocksJdbcResourceSource.MYSQL_BIT_COMPATIBLE_DRIVER);
        f.probeFailure = new SQLException("driver-secret-on-bit-read", "HY000", 999);
        f.expectPreclearFailure("time_stamp", "实际读取预检失败");
        assertTrue(f.inserts.isEmpty());
        assertFalse(f.sourceSql.stream().anyMatch(sql -> sql.startsWith("SELECT *")));
        assertEquals(1, f.dropCount());
        f.assertUserResourceUntouched();
    }

    @Test
    public void sqlServerProbeReadsEveryProjectionWithoutLimitOrStatementMaxRows() throws Exception {
        Fixture f = new Fixture("SQLSERVER");
        f.sourceCount = 20000;
        f.affected = new long[] {5, 7};
        f.runFullSync("time_stamp", 1, "YEAR");
        List<String> probes = new ArrayList<>();
        for (String sql : f.targetSql) if (sql.startsWith("SELECT COUNT(*)") && sql.contains("`easyreport_import_tmp`.")) probes.add(sql);
        assertEquals("global and both natural-year windows must actually scan", 3, probes.size());
        for (String sql : probes) {
            assertFalse(sql, sql.contains(" LIMIT "));
            assertTrue(sql, sql.contains("MAX(LENGTH(CAST(`Id` AS VARCHAR(1048576))))"));
            assertTrue(sql, sql.contains("MAX(LENGTH(CAST(`Name` AS VARCHAR(1048576))))"));
            assertTrue(sql, sql.contains("MAX(LENGTH(CAST(`time_stamp` AS VARCHAR(1048576))))"));
        }
        assertEquals(2, f.inserts.size());
        assertEquals(1, f.checkpoints.size());
        f.assertUserResourceUntouched();
    }

    @Test
    public void sqlServerWindowDriverFailureOccursBeforeTruncate() throws Exception {
        Fixture f = new Fixture("SQLSERVER");
        f.sourceCount = 20000;
        f.failSecondWindowProbe = true;
        f.expectPreclearFailure("time_stamp", "实际读取预检失败");
        assertTrue(f.inserts.isEmpty());
        assertEquals(1, f.dropCount());
        assertFalse(f.targetSql.stream().anyMatch(sql -> sql.contains(" LIMIT ")));
    }

    @Test
    public void oracleWindowsMustBeReadBeforeClearAndNlsFailurePreservesDestination() throws Exception {
        Fixture success = new Fixture("ORACLE");
        success.sourceCount = 20000;
        success.affected = new long[] {5, 7};
        success.runFullSync("time_stamp", 1, "YEAR");
        int clear = indexOf(success.targetSql, "TRUNCATE TABLE");
        assertTrue(success.targetSql.subList(0, clear).stream().anyMatch(sql -> sql.startsWith("SELECT ")
                && sql.contains(" >= '2023-01-01 00:00:00'") && sql.endsWith("LIMIT 1")));
        assertTrue(success.targetSql.subList(0, clear).stream().anyMatch(sql -> sql.startsWith("SELECT ")
                && sql.contains(" >= '2024-01-01 00:00:00'") && sql.endsWith("LIMIT 1")));
        Fixture failure = new Fixture("ORACLE");
        failure.sourceCount = 20000;
        failure.failSecondWindowProbe = true;
        failure.expectPreclearFailure("time_stamp", "Oracle JDBC Resource 时间窗口实际读取预检失败");
        assertTrue(failure.inserts.isEmpty());
        assertEquals(1, failure.dropCount());
        failure.assertUserResourceUntouched();
    }

    @Test
    public void mysqlTiDbAndStarRocksJsonUseGuardedScalarProjectionThroughJdbcResource() throws Exception {
        for (String type : Arrays.asList("MYSQL", "TIDB", "STARROCKS")) {
            Fixture f = new Fixture(type);
            f.jsonColumn = true;
            try (StarRocksJdbcResourceFullImport importer = f.prepare(null, 50, "YEAR")) { importer.execute(); }
            assertTrue(f.targetSql.contains(StarRocksExternalFullImport.JSON_CONVERSION_PROBE_SQL));
            assertTrue(f.inserts.get(0), f.inserts.get(0).contains(StarRocksExternalFullImport.jsonProjection("`Name`")));
            assertTrue("Only Kingbase receives the extra JSON semantic scan", f.jsonComparisonFetchSizes.isEmpty());
            assertFalse(f.targetSql.stream().anyMatch(sql -> sql.contains("ENGINE=MYSQL")));
            f.assertUserResourceUntouched();
        }
    }

    @Test
    public void kingbaseJsonbDerivedScanUsesAliasesAndWritesOriginalTargetColumns() throws Exception {
        Fixture f = new Fixture("KINGBASE");
        f.kingbaseTextNativeType = "JSONB";
        f.jsonColumn = true;

        Object result = f.runFullSync("time_stamp", 50, "YEAR");

        assertEquals(Long.valueOf(7), ((ErDataTransferLog) field(result, "log")).getSuccessCount());
        String ddl = f.targetSql.stream().filter(sql -> sql.startsWith("CREATE EXTERNAL TABLE")).findFirst().get();
        assertTrue(ddl, ddl.contains("`er_c0` BIGINT NULL"));
        assertTrue(ddl, ddl.contains("`er_c1` VARCHAR(1048576) NULL"));
        assertTrue(ddl, ddl.contains("`er_c2` DATETIME NULL"));
        assertFalse(ddl, ddl.contains("`Name`"));
        assertTrue(f.sourceSql.toString(), f.sourceSql.stream().anyMatch(sql -> sql.startsWith("SELECT er_c0")
                && sql.contains("CAST(\"Name\" AS TEXT) AS er_c1") && sql.endsWith("WHERE 1=0")));
        assertTrue(f.targetSql.contains(StarRocksExternalFullImport.JSON_CONVERSION_PROBE_SQL));
        String insert = f.inserts.get(0);
        assertTrue(insert, insert.startsWith("INSERT INTO `warehouse`.`users` (`Id`, `Name`, `time_stamp`) SELECT `er_c0`, "));
        assertTrue(insert, insert.contains(StarRocksExternalFullImport.jsonProjection("`er_c1`")));
        assertTrue(insert, insert.endsWith("WHERE `er_c2` IS NOT NULL"));
        int clear = indexOf(f.targetSql, "TRUNCATE TABLE");
        assertTrue(f.targetSql.subList(0, clear).stream().anyMatch(sql -> sql.startsWith("SELECT MAX(LENGTH(`er_c1`))")
                && sql.endsWith("WHERE `er_c2` IS NOT NULL")));
        assertTrue(f.targetSql.subList(0, clear).stream().anyMatch(sql -> sql.startsWith("EXPLAIN INSERT")));
        assertEquals(1, f.dropCount());
        f.assertUserResourceUntouched();
    }

    @Test
    public void kingbaseJsonComparisonStreamsEveryEligibleValueBeforeClearing() throws Exception {
        Fixture f = new Fixture("KINGBASE");
        f.kingbaseTextNativeType = "JSONB";
        f.jsonColumn = true;
        f.jsonComparisons = Arrays.asList(
                new String[] {null, null},
                new String[] {"null", "null"},
                new String[] {"123", "123.0"},
                new String[] {"\"123\"", "\"123\""},
                new String[] {"true", "true"},
                new String[] {"{\"n\":1,\"items\":[true,\"2\",null]}", "{\"items\":[true,\"2\",null],\"n\":1.0}"});

        f.runFullSync("time_stamp", 50, "YEAR");

        String comparison = f.targetSql.stream().filter(sql -> sql.startsWith("SELECT `er_c1`, JSON_STRING(")).findFirst().get();
        assertTrue(comparison, comparison.contains("JSON_STRING(" + StarRocksExternalFullImport.jsonProjection("`er_c1`") + ")"));
        assertTrue(comparison, comparison.endsWith("WHERE `er_c2` IS NOT NULL"));
        assertFalse(comparison, comparison.contains(" LIMIT "));
        assertEquals(Collections.singletonList(Integer.MIN_VALUE), f.jsonComparisonFetchSizes);
        int comparisonIndex = f.targetSql.indexOf(comparison);
        assertTrue(comparisonIndex > indexOf(f.targetSql, "CREATE EXTERNAL TABLE"));
        assertTrue(comparisonIndex < indexOf(f.targetSql, "TRUNCATE TABLE"));
        assertEquals(f.jsonComparisons.size(), f.jsonComparisonRowsRead);
        assertEquals(1, f.inserts.size());
        assertEquals(1, f.dropCount());
        f.assertUserResourceUntouched();
    }

    @Test
    public void kingbaseJsonRoundingOrTypeChangesStopBeforeClearEvenAfterValidRows() throws Exception {
        List<String[]> changedValues = Arrays.asList(
                new String[] {"12345678901234567890.1234567890123456789", "1.2345678901234567e19"},
                new String[] {"{\"amount\":1.0000000000000000000001}", "{\"amount\":1}"},
                new String[] {"\"123\"", "123"},
                new String[] {null, "null"},
                new String[] {"null", null});
        for (String[] changed : changedValues) {
            Fixture f = new Fixture("KINGBASE");
            f.kingbaseTextNativeType = "JSONB";
            f.jsonColumn = true;
            f.jsonComparisons = Arrays.asList(new String[] {"{\"ok\":1}", "{\"ok\":1}"}, changed);
            try { f.runFullSync("time_stamp", 50, "YEAR"); fail("Every JSON row must retain its value before target clear"); }
            catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("Name"));
                assertTrue(expected.getMessage().contains("文本列"));
                assertTrue(expected.getMessage().contains("尚未清空目标表"));
                assertFalse(expected.getMessage().contains("er_c1"));
            }
            assertEquals(2, f.jsonComparisonRowsRead);
            assertEquals(-1, indexOf(f.targetSql, "TRUNCATE TABLE"));
            assertTrue(f.inserts.isEmpty());
            assertTrue(f.checkpoints.isEmpty());
            assertEquals(1, f.dropCount());
            f.assertUserResourceUntouched();
        }
    }

    @Test
    public void kingbaseJsonTextDestinationKeepsRawTextWithoutSemanticScan() throws Exception {
        Fixture f = new Fixture("KINGBASE");
        f.kingbaseTextNativeType = "JSONB";
        f.jsonColumn = true;
        f.jsonTargetType = "VARCHAR(200000)";
        f.jsonComparisons = Collections.singletonList(new String[] {"1.0000000000000000000001", "1"});
        f.runFullSync("time_stamp", 50, "YEAR");
        assertTrue(f.jsonComparisonFetchSizes.isEmpty());
        assertEquals(0, f.jsonComparisonRowsRead);
        assertFalse(f.targetSql.stream().anyMatch(sql -> sql.startsWith("SELECT `er_c1`, JSON_STRING(")));
        assertFalse(f.inserts.get(0).contains("PARSE_JSON"));
        assertTrue(f.inserts.get(0).contains("SELECT `er_c0`, `er_c1`, `er_c2` FROM"));
    }

    @Test
    public void stopDuringKingbaseJsonStreamingPreflightStillDropsOwnedTableWithoutClearing() throws Exception {
        Fixture f = new Fixture("KINGBASE");
        f.kingbaseTextNativeType = "JSONB";
        f.jsonColumn = true;
        f.jsonComparisons = Arrays.asList(new String[] {"1", "1"}, new String[] {"2", "2"});
        f.stopOnJsonComparisonRow = 1;
        try { f.prepare("time_stamp", 50, "YEAR"); fail("Streaming JSON validation must observe cancellation"); }
        catch (DataSyncCancellation.SyncStoppedException expected) { }
        assertEquals(1, f.jsonComparisonRowsRead);
        assertEquals(-1, indexOf(f.targetSql, "TRUNCATE TABLE"));
        assertTrue(f.inserts.isEmpty());
        assertEquals(1, f.dropCount());
        f.assertUserResourceUntouched();
    }

    @Test
    public void kingbaseByteaDerivedHexUsesTextCapacityAndOriginalDestinationNames() throws Exception {
        Fixture f = new Fixture("KINGBASE");
        f.kingbaseTextNativeType = "BYTEA";
        f.targetTextType = "VARCHAR(4)";
        f.actualTextBytes = 4L;
        f.runFullSync("time_stamp", 50, "YEAR");
        assertTrue(f.sourceSql.toString(), f.sourceSql.stream().anyMatch(sql -> sql.startsWith("SELECT er_c0")
                && sql.contains("UPPER(ENCODE(\"Name\", 'hex')) AS er_c1")));
        assertTrue(f.inserts.get(0), f.inserts.get(0).contains("(`Id`, `Name`, `time_stamp`) SELECT `er_c0`, `er_c1`, `er_c2` FROM"));
        assertFalse(f.targetSql.contains(StarRocksExternalFullImport.JSON_CONVERSION_PROBE_SQL));
        assertFalse(f.targetSql.contains(StarRocksJdbcResourceFullImport.BINARY_CONVERSION_PROBE_SQL));
        assertFalse(f.inserts.get(0).contains("TO_BINARY"));

        for (String nativeType : Arrays.asList("JSONB", "BYTEA")) {
            Fixture narrow = new Fixture("KINGBASE");
            narrow.kingbaseTextNativeType = nativeType;
            narrow.jsonColumn = "JSONB".equals(nativeType);
            narrow.jsonTargetType = "VARCHAR(3)";
            narrow.targetTextType = "VARCHAR(3)";
            narrow.actualTextBytes = 4L;
            try { narrow.runFullSync("time_stamp", 50, "YEAR"); fail("Aliased text must fit the original destination column"); }
            catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("Name"));
                assertTrue(expected.getMessage().contains("超出目标字段容量"));
                assertFalse(expected.getMessage().contains("er_c1"));
            }
            assertEquals(-1, indexOf(narrow.targetSql, "TRUNCATE TABLE"));
            assertTrue(narrow.inserts.isEmpty());
            assertTrue(narrow.checkpoints.isEmpty());
            assertEquals(1, narrow.dropCount());
            narrow.assertUserResourceUntouched();
        }
    }

    @Test
    public void binaryHexDecodesIntoExistingBinaryTargetsWithRoundTripAndCapacityGuards() throws Exception {
        for (String type : Arrays.asList("BINARY", "VARBINARY", "BINARY(16)", "VARBINARY(16)")) {
            Fixture f = new Fixture("KINGBASE");
            f.kingbaseTextNativeType = "BYTEA";
            f.targetTextType = type;
            f.actualTextBytes = 32L;
            f.actualBinaryBytes = 16L;
            f.runFullSync("time_stamp", 50, "YEAR");
            String insert = f.inserts.get(0);
            long encodedCapacity = type.contains("(") ? 32 : 2_097_152;
            assertTrue(insert, insert.contains("TO_BINARY(`er_c1`, 'hex')"));
            assertTrue(insert, insert.contains("IF(ASSERT_TRUE(`er_c1` IS NULL OR (LENGTH(`er_c1`) <= " + encodedCapacity));
            assertTrue(insert, insert.contains("IFNULL(HEX(TO_BINARY(`er_c1`, 'hex')) = UPPER(`er_c1`), FALSE)"));
            assertFalse(insert, insert.contains("UNHEX("));
            assertFalse(insert, insert.contains("AS VARBINARY"));
            assertFalse(insert, insert.contains("AS BINARY"));
            assertFalse(f.targetSql.stream().anyMatch(sql -> sql.startsWith("ALTER")));
            String aggregate = f.targetSql.stream().filter(sql -> sql.startsWith("SELECT MAX(LENGTH(HEX(IF(ASSERT_TRUE(")).findFirst().get();
            assertFalse(aggregate, aggregate.contains(" LIMIT "));
            assertTrue(aggregate, aggregate.endsWith("WHERE `er_c2` IS NOT NULL"));
            assertTrue(f.targetSql.indexOf(aggregate) < indexOf(f.targetSql, "TRUNCATE TABLE"));
            assertTrue(f.targetSql.indexOf(StarRocksJdbcResourceFullImport.BINARY_CONVERSION_PROBE_SQL)
                    < indexOf(f.targetSql, "CREATE EXTERNAL TABLE"));
            assertEquals(1, f.inserts.size());
            assertEquals(1, f.dropCount());
            f.assertUserResourceUntouched();
        }
    }

    @Test
    public void binaryCapacityUsesDecodedBytesAndStillHonorsTheHexChannelLimit() throws Exception {
        Fixture overflow = new Fixture("KINGBASE");
        overflow.kingbaseTextNativeType = "BYTEA";
        overflow.targetTextType = "VARBINARY(16)";
        overflow.actualTextBytes = 34L;
        overflow.expectPreclearFailure("time_stamp", "解码后超出目标字段容量（16 字节）");
        assertTrue(overflow.inserts.isEmpty());
        assertEquals(1, overflow.dropCount());

        Fixture channel = new Fixture("KINGBASE");
        channel.kingbaseTextNativeType = "BYTEA";
        channel.targetTextType = "VARBINARY";
        channel.actualTextBytes = StarRocksExternalFullImport.MAX_JSON_TEXT_BYTES + 2L;
        channel.expectPreclearFailure("time_stamp", "超出外表通道容量");
        assertTrue(channel.inserts.isEmpty());
        assertEquals(1, channel.dropCount());
    }

    @Test
    public void binaryGuardScansLaterRowsAndKeepsDriverErrorsSafeBeforeClear() throws Exception {
        for (String invalidHex : Arrays.asList("GG", "F", "00 FF")) {
            Fixture f = new Fixture("KINGBASE");
            f.kingbaseTextNativeType = "BYTEA";
            f.targetTextType = "VARBINARY(16)";
            f.actualTextBytes = (long) invalidHex.length();
            f.binaryGuardFailure = new SQLException("private-binary-value-" + invalidHex, "HY000", 712);
            try { f.runFullSync("time_stamp", 50, "YEAR"); fail("A later invalid HEX row must fail before clear"); }
            catch (SQLException expected) {
                assertTrue(expected.getMessage().contains("实际读取预检失败"));
                assertFalse(expected.getMessage().contains("private-binary-value"));
                assertNull(expected.getCause());
            }
            assertTrue(f.targetSql.stream().anyMatch(sql -> sql.startsWith("SELECT `er_c0`, IF(ASSERT_TRUE(")));
            assertTrue(f.targetSql.stream().anyMatch(sql -> sql.startsWith("SELECT MAX(LENGTH(HEX(IF(ASSERT_TRUE(")));
            assertEquals(-1, indexOf(f.targetSql, "TRUNCATE TABLE"));
            assertTrue(f.inserts.isEmpty());
            assertTrue(f.checkpoints.isEmpty());
            assertEquals(1, f.dropCount());
            f.assertUserResourceUntouched();
        }
    }

    @Test
    public void binaryTargetsPreserveNullAndEmptyAndVerifyDecoderSemanticsBeforeCreatingTable() throws Exception {
        for (Long bytes : Arrays.asList(null, 0L)) {
            Fixture f = new Fixture("KINGBASE");
            f.kingbaseTextNativeType = "BYTEA";
            f.targetTextType = "BINARY(16)";
            f.actualTextBytes = bytes;
            f.actualBinaryBytes = bytes;
            f.runFullSync("time_stamp", 50, "YEAR");
            assertEquals(1, f.inserts.size());
            assertTrue(f.inserts.get(0).contains("`er_c1` IS NULL OR"));
            assertEquals(1, f.dropCount());
        }
        for (String broken : Arrays.asList("hex", "empty", "null")) {
            Fixture f = new Fixture("KINGBASE");
            f.kingbaseTextNativeType = "BYTEA";
            f.targetTextType = "VARBINARY(16)";
            f.brokenBinaryDecoderSemantic = broken;
            f.expectPreclearFailure("time_stamp", "TO_BINARY 未保留");
            assertEquals(0, f.dropCount());
            assertFalse(f.targetSql.stream().anyMatch(sql -> sql.startsWith("CREATE EXTERNAL TABLE")));
        }
    }

    @Test
    public void binaryTargetsRejectInvalidCapacitiesAndNonBinarySourcesBeforeCreatingTable() throws Exception {
        for (String type : Arrays.asList("VARBINARY(0)", "BINARY(1048577)", "VARBINARY(999999999999999999999)", "BINARY(-1)")) {
            Fixture f = new Fixture("KINGBASE");
            f.kingbaseTextNativeType = "BYTEA";
            f.targetTextType = type;
            f.expectPreclearFailure(null, "目标二进制字段容量无效");
            assertEquals(0, f.dropCount());
        }
        for (String source : Arrays.asList("text", "JSONB", "TIME(6)")) {
            Fixture f = new Fixture("KINGBASE");
            if (!"text".equals(source)) f.kingbaseTextNativeType = source;
            f.jsonColumn = "JSONB".equals(source);
            f.jsonTargetType = "VARBINARY(16)";
            f.targetTextType = "VARBINARY(16)";
            f.expectPreclearFailure(null, "文本列");
            assertEquals(0, f.dropCount());
        }
    }

    @Test
    public void kingbaseTimeUsesExistingDriverTextProjectionAndValidatesDestinationCapacity() throws Exception {
        Fixture f = new Fixture("KINGBASE");
        f.kingbaseTextNativeType = "TIME(6)";
        f.targetTextType = "VARCHAR(40)";
        f.targetSampleText = "23:59:59.123456";
        f.actualTextBytes = (long) f.targetSampleText.length();
        f.runFullSync("time_stamp", 50, "YEAR");
        assertEquals("com.kingbase8.Driver", f.properties.get("driver_class"));
        assertTrue(f.sourceSql.toString(), f.sourceSql.stream().anyMatch(sql -> sql.startsWith("SELECT er_c0")
                && sql.contains("CAST(\"Name\" AS TEXT) AS er_c1")));
        assertTrue(f.inserts.get(0).contains("SELECT `er_c0`, `er_c1`, `er_c2` FROM"));
        assertFalse(f.inserts.get(0).contains("TO_BINARY"));
        assertFalse(f.targetSql.contains(StarRocksJdbcResourceFullImport.BINARY_CONVERSION_PROBE_SQL));

        Fixture narrow = new Fixture("KINGBASE");
        narrow.kingbaseTextNativeType = "TIME(6)";
        narrow.targetTextType = "VARCHAR(8)";
        narrow.actualTextBytes = 15L;
        narrow.expectPreclearFailure("time_stamp", "超出目标字段容量");
        assertEquals(1, narrow.dropCount());
        assertTrue(narrow.inserts.isEmpty());
    }

    @Test
    public void kingbaseJsonbSemanticOrAliasScanFailuresDoNotClearDestination() throws Exception {
        Fixture semantics = new Fixture("KINGBASE");
        semantics.kingbaseTextNativeType = "JSONB";
        semantics.jsonColumn = true;
        semantics.jsonProbeValid = false;
        semantics.expectPreclearFailure("time_stamp", "JSON 导入表达式未保留");
        assertEquals(0, semantics.dropCount());

        Fixture scan = new Fixture("KINGBASE");
        scan.kingbaseTextNativeType = "JSONB";
        scan.jsonColumn = true;
        scan.probeFailure = new SQLException("private-driver-alias-syntax", "42601", 0);
        try { scan.runFullSync("time_stamp", 50, "YEAR"); fail("The actual Resource read must pass before clearing"); }
        catch (SQLException expected) {
            assertEquals("42601", expected.getSQLState());
            assertTrue(expected.getMessage().contains("实际读取预检失败"));
            assertFalse(expected.getMessage().contains("private-driver"));
        }
        assertEquals(-1, indexOf(scan.targetSql, "TRUNCATE TABLE"));
        assertTrue(scan.inserts.isEmpty());
        assertTrue(scan.checkpoints.isEmpty());
        assertEquals(1, scan.dropCount());
        scan.assertUserResourceUntouched();
    }

    @Test
    public void kingbaseIntegerRangeGuardsReferenceScanAliasAndKeepLogicalTargetColumn() throws Exception {
        Fixture f = new Fixture("KINGBASE");
        f.targetIdType = "TINYINT";
        f.runFullSync("time_stamp", 50, "YEAR");
        assertTrue(f.targetSql.stream().anyMatch(sql -> sql.startsWith("SELECT COUNT(`er_c0`)")
                && sql.contains("MIN(CAST(`er_c0` AS LARGEINT))")));
        String insert = f.inserts.get(0);
        assertTrue(insert, insert.startsWith("INSERT INTO `warehouse`.`users` (`Id`, `Name`, `time_stamp`)"));
        assertTrue(insert, insert.contains("assert_true(`er_c0` IS NULL OR"));
        assertFalse(insert, insert.contains("CAST(`Id` AS LARGEINT)"));
    }

    @Test
    public void jsonTextTargetsKeepRawJsonAndStillCheckActualByteCapacity() throws Exception {
        Fixture text = new Fixture("MYSQL");
        text.jsonColumn = true;
        text.jsonTargetType = "VARCHAR(200000)";
        try (StarRocksJdbcResourceFullImport importer = text.prepare(null, 50, "YEAR")) { importer.execute(); }
        assertFalse(text.targetSql.contains(StarRocksExternalFullImport.JSON_CONVERSION_PROBE_SQL));
        assertFalse(text.inserts.get(0).contains("PARSE_JSON"));
        Fixture narrow = new Fixture("TIDB");
        narrow.jsonColumn = true;
        narrow.jsonTargetType = "VARCHAR(36)";
        narrow.actualTextBytes = 37L;
        narrow.expectPreclearFailure(null, "超出目标字段容量");
    }

    @Test
    public void jsonWrapperCapacityAndScalarSemanticsFailBeforeClear() throws Exception {
        Fixture large = new Fixture("STARROCKS");
        large.jsonColumn = true;
        large.textBytes = StarRocksExternalFullImport.MAX_JSON_TEXT_BYTES - 1L;
        large.actualTextBytes = large.textBytes;
        large.expectPreclearFailure(null, "超出目标字段容量");
        Fixture semantic = new Fixture("MYSQL");
        semantic.jsonColumn = true;
        semantic.jsonProbeValid = false;
        semantic.expectPreclearFailure(null, "JSON 导入表达式未保留");
        assertEquals(0, semantic.dropCount());
    }

    @Test
    public void starRocksLargeintReportedAsTextIsCastExplicitlyBeforeNativeInsert() throws Exception {
        Fixture f = new Fixture("STARROCKS");
        f.largeIntegerText = true;
        f.targetIdType = "LARGEINT";
        try (StarRocksJdbcResourceFullImport importer = f.prepare(null, 50, "YEAR")) { importer.execute(); }
        assertTrue(f.inserts.get(0), f.inserts.get(0).contains("CAST(`Id` AS LARGEINT)"));
        String ddl = f.targetSql.stream().filter(sql -> sql.startsWith("CREATE EXTERNAL TABLE")).findFirst().get();
        assertTrue(ddl, ddl.contains("`Id` VARCHAR(40)"));
        assertFalse(ddl.contains("ENGINE=MYSQL"));
    }

    @Test
    public void mysqlAndTiDbTimestampWallClockOffsetsFailBeforeTargetClear() throws Exception {
        for (String type : Arrays.asList("MYSQL", "TIDB")) {
            Fixture f = new Fixture(type);
            f.mysqlTimestamp = true;
            f.targetMinText = "2023-12-31 08:00:00";
            f.targetMaxText = "2024-01-02 20:34:56.123456";
            f.expectPreclearFailure("time_stamp", "墙钟时间不一致");
            assertEquals(1, f.dropCount());
            assertTrue(f.inserts.isEmpty());
        }
    }

    @Test
    public void timestampWallClocksNormalizeEquivalentFractionsAndNullRanges() throws Exception {
        Fixture f = new Fixture("MYSQL");
        f.mysqlTimestamp = true;
        f.targetMinText = "2023-12-31 00:00:00.000000";
        f.targetMaxText = "2024-01-02 12:34:56.1234560";
        try (StarRocksJdbcResourceFullImport ignored = f.prepare("time_stamp", 50, "YEAR")) { }
        Fixture nulls = new Fixture("TIDB");
        nulls.mysqlTimestamp = true;
        nulls.min = null;
        nulls.max = null;
        nulls.targetMinText = null;
        nulls.targetMaxText = null;
        // This TIMESTAMP column is not the task watermark and may be NULL in every eligible row.
        try (StarRocksJdbcResourceFullImport ignored = nulls.prepare(null, 50, "YEAR")) { }
        Fixture empty = new Fixture("MYSQL");
        empty.mysqlTimestamp = true;
        empty.sourceCount = 0;
        empty.sampleVisible = false;
        try (StarRocksJdbcResourceFullImport ignored = empty.prepare("time_stamp", 50, "YEAR")) { }
        assertFalse(empty.targetSql.stream().anyMatch(sql -> sql.startsWith("SELECT MIN(")));
    }

    @Test
    public void preparesEveryNaturalWindowBeforeTruncateAndPreservesCheckpointLifecycle() throws Exception {
        Fixture f = new Fixture("KINGBASE");
        f.sourceCount = 20000;
        f.affected = new long[] {5, 7};

        Object result = f.runFullSync("time_stamp", 1, "YEAR");

        ErDataTransferLog log = (ErDataTransferLog) field(result, "log");
        assertEquals(Long.valueOf(12), log.getSuccessCount());
        assertEquals(MAX_TS, field(result, "maxTs"));
        assertEquals(1, f.checkpoints.size());
        int truncate = indexOf(f.targetSql, "TRUNCATE TABLE");
        assertTrue(truncate > 0);
        List<String> beforeClear = f.targetSql.subList(0, truncate);
        assertTrue(beforeClear.toString(), beforeClear.stream().anyMatch(sql -> sql.startsWith("SELECT ")
                && sql.contains(" >= '2023-01-01 00:00:00'") && sql.contains(" < '2024-01-01 00:00:00'")));
        assertTrue(beforeClear.toString(), beforeClear.stream().anyMatch(sql -> sql.startsWith("SELECT ")
                && sql.contains(" >= '2024-01-01 00:00:00'") && sql.contains(" < '2025-01-01 00:00:00'")));
        assertTrue(beforeClear.stream().anyMatch(sql -> sql.startsWith("EXPLAIN INSERT")));
        assertTrue(indexOf(f.targetSql, "INSERT INTO") > truncate);
        assertTrue(f.events.indexOf("drop") < f.events.indexOf("checkpoint"));
        assertEquals(2, f.inserts.size());
        f.assertUserResourceUntouched();
    }

    @Test
    public void monthAndDayWindowsUseTheSamePredicatesInProbeAndInsert() throws Exception {
        for (String unit : Arrays.asList("MONTH", "DAY")) {
            Fixture f = new Fixture("DM");
            f.sourceCount = 20000;
            f.min = Timestamp.valueOf("2024-02-28 23:59:59");
            f.max = Timestamp.valueOf("2024-03-01 12:00:00");
            f.affected = new long[] {1, 1, 1};
            try (StarRocksJdbcResourceFullImport importer = f.prepare("time_stamp", 1, unit)) {
                importer.execute();
            }
            for (String insert : f.inserts) {
                String predicate = insert.substring(insert.indexOf(" WHERE "));
                assertTrue(predicate, f.targetSql.stream().anyMatch(sql -> sql.startsWith("SELECT ")
                        && sql.endsWith(predicate + " LIMIT 1")));
            }
            assertEquals("MONTH".equals(unit) ? 2 : 3, f.inserts.size());
        }
    }

    @Test
    public void sourceWithOnlyNullTimestampsRetainsExistingEmptyInitializationBehavior() throws Exception {
        Fixture f = new Fixture("DM");
        f.sourceCount = 0;
        f.sampleVisible = false;
        Object result = f.runFullSync("time_stamp", 1, "DAY");
        assertEquals(Long.valueOf(0), ((ErDataTransferLog) field(result, "log")).getSuccessCount());
        assertTrue(f.inserts.isEmpty());
        assertTrue(f.checkpoints.isEmpty());
        assertTrue(indexOf(f.targetSql, "TRUNCATE TABLE") >= 0);
        assertEquals(1, f.dropCount());
    }

    @Test
    public void resourceVisibilityAndTypeMustBeVerifiedBeforeSourceReads() throws Exception {
        for (String resourceType : Arrays.asList(null, "spark")) {
            Fixture f = new Fixture("KINGBASE");
            f.resourceType = resourceType;
            try { f.prepare(null, 50, "YEAR"); fail("Expected Resource failure"); }
            catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("Resource")); }
            assertTrue(f.sourceSql.isEmpty());
            assertEquals(Collections.singletonList("SHOW RESOURCES"), f.targetSql);
        }
    }

    @Test
    public void missingOrMismatchedResourceBindingLeavesExistingTargetUntouched() throws Exception {
        for (String key : Arrays.asList("driver_url", "driver_class", "jdbc_uri", "user")) {
            Fixture missing = new Fixture("KINGBASE");
            missing.properties.remove(key);
            missing.expectPreclearFailure(null, "Resource");
            assertFalse(missing.targetSql.stream().anyMatch(sql -> sql.startsWith("CREATE")));
        }
        Fixture wrongUser = new Fixture("DM");
        wrongUser.properties.put("user", "someone_else");
        wrongUser.expectPreclearFailure(null, "用户");
    }

    @Test
    public void resourceMutationDuringPreflightStopsBeforeClearingAndOnlyDropsOwnedTable() throws Exception {
        Fixture f = new Fixture("KINGBASE");
        f.mutateOnResourceRead = 2;
        f.expectPreclearFailure("time_stamp", "发生变化");
        assertEquals(1, f.dropCount());
        f.assertUserResourceUntouched();
    }

    @Test
    public void secondWindowCompatibilityFailureIsDetectedBeforeTruncate() throws Exception {
        Fixture f = new Fixture("DM");
        f.sourceCount = 20000;
        f.failSecondWindowProbe = true;
        f.expectPreclearFailure("time_stamp", "实际读取预检失败");
        assertTrue(f.inserts.isEmpty());
        assertEquals(1, f.dropCount());
        f.assertUserResourceUntouched();
    }

    @Test
    public void resourceThatCannotReadSourceRowsFailsWithoutClearingTarget() throws Exception {
        Fixture f = new Fixture("KINGBASE");
        f.sampleVisible = false;
        f.expectPreclearFailure("time_stamp", "未读取到数据");
        assertEquals(1, f.dropCount());
    }

    @Test
    public void explainFailureAndDriverErrorsCannotLeakConnectionDetails() throws Exception {
        Fixture f = new Fixture("DM");
        f.failExplain = true;
        try { f.runFullSync("time_stamp", 1, "YEAR"); fail("Expected EXPLAIN failure"); }
        catch (SQLException expected) {
            assertFalse(expected.getMessage().contains("driver-secret"));
            assertNull(expected.getCause());
        }
        assertEquals(1, f.dropCount());
        assertTrue(f.checkpoints.isEmpty());
        assertEquals(-1, indexOf(f.targetSql, "TRUNCATE TABLE"));
    }

    @Test
    public void largeTextTargetCapacityIsCheckedWithoutAnySchemaAlter() throws Exception {
        Fixture f = new Fixture("DM");
        f.textBytes = 150000;
        f.actualTextBytes = 150000L;
        f.targetTextType = "VARCHAR(200000)";
        try (StarRocksJdbcResourceFullImport ignored = f.prepare(null, 50, "YEAR")) { }
        assertFalse(f.targetSql.stream().anyMatch(sql -> sql.startsWith("ALTER")));
        Fixture narrow = new Fixture("KINGBASE");
        narrow.textBytes = 150000;
        narrow.actualTextBytes = 150000L;
        narrow.targetTextType = "STRING";
        narrow.expectPreclearFailure(null, "超出目标字段容量");
        assertEquals(1, narrow.dropCount());
        assertFalse(narrow.targetSql.stream().anyMatch(sql -> sql.startsWith("ALTER")));
    }

    @Test
    public void actualUtf8ScanAcceptsAscii36ButRejectsChinese36IntoVarchar36() throws Exception {
        Fixture ascii = new Fixture("KINGBASE");
        ascii.textBytes = 36 * 4;
        ascii.actualTextBytes = 36L;
        ascii.targetTextType = "VARCHAR(36)";
        try (StarRocksJdbcResourceFullImport ignored = ascii.prepare(null, 50, "YEAR")) { }
        assertTrue(ascii.targetSql.stream().anyMatch(sql -> sql.startsWith("SELECT MAX(LENGTH(`er_c1`))")));
        Fixture chinese = new Fixture("DM");
        chinese.textBytes = 36 * 4;
        chinese.actualTextBytes = (long) String.join("", Collections.nCopies(36, "中"))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        chinese.targetTextType = "VARCHAR(36)";
        chinese.expectPreclearFailure("time_stamp", "实际 UTF-8 字节数超出目标字段容量");
        assertTrue(chinese.targetSql.stream().anyMatch(sql -> sql.startsWith("SELECT MAX(LENGTH(`Name`))")
                && sql.endsWith("WHERE `time_stamp` IS NOT NULL")));
        assertEquals(1, chinese.dropCount());
    }

    @Test
    public void actualUtf8ScanAllowsSqlNullAndEmptyText() throws Exception {
        for (Long bytes : Arrays.asList(null, 0L)) {
            Fixture f = new Fixture("DM");
            f.textBytes = 0;
            f.actualTextBytes = bytes;
            f.targetTextType = "VARCHAR(36)";
            try (StarRocksJdbcResourceFullImport ignored = f.prepare(null, 50, "YEAR")) { }
            assertEquals(1, f.dropCount());
        }
    }

    @Test
    public void strictAndPipelineSettingsMustBeConfirmedBeforeCreatingExternalTable() throws Exception {
        for (String variable : Arrays.asList("enable_insert_strict", "enable_pipeline_engine")) {
            Fixture f = new Fixture("DM");
            f.disabledVariable = variable;
            f.expectPreclearFailure(null, variable);
            assertFalse(f.targetSql.stream().anyMatch(sql -> sql.startsWith("CREATE EXTERNAL TABLE")));
            assertEquals(0, f.dropCount());
        }
    }

    @Test
    public void stopDuringProbeStillCleansOnlyOwnedExternalTable() throws Exception {
        Fixture f = new Fixture("KINGBASE");
        f.stopDuringProbe = true;
        try { f.prepare("time_stamp", 1, "YEAR"); fail("Expected stop"); }
        catch (DataSyncCancellation.SyncStoppedException expected) {
            assertNull(expected.getCause());
            assertEquals(0, expected.getSuppressed().length);
            assertFalse(expected.getMessage().contains("driver-secret"));
        }
        finally { f.cancellation.close(); }
        assertEquals(1, f.dropCount());
        assertTrue(f.inserts.isEmpty());
        f.assertUserResourceUntouched();
    }

    @Test
    public void stoppedProbeKeepsOnlySanitizedCleanupFailureAsSuppressed() throws Exception {
        Fixture f = new Fixture("KINGBASE");
        f.stopDuringProbe = true;
        f.failDrop = true;
        try { f.prepare("time_stamp", 1, "YEAR"); fail("Expected stop"); }
        catch (DataSyncCancellation.SyncStoppedException expected) {
            assertNull(expected.getCause());
            assertEquals(1, expected.getSuppressed().length);
            assertTrue(expected.getSuppressed()[0].getMessage().contains("清理"));
            assertFalse(expected.getSuppressed()[0].getMessage().contains("driver-secret"));
            assertNull(expected.getSuppressed()[0].getCause());
        } finally { f.cancellation.close(); }
        assertEquals(1, f.dropCount());
        f.assertUserResourceUntouched();
    }

    @Test
    public void cancellationSqlStateWithoutStopRequestRemainsAReadFailure() throws Exception {
        Fixture f = new Fixture("KINGBASE");
        f.probeFailure = new SQLException("driver-secret-on-server-cancel", "57014", 1317);
        try { f.prepare("time_stamp", 1, "YEAR"); fail("Expected read failure"); }
        catch (SQLException expected) {
            assertFalse(expected instanceof DataSyncCancellation.SyncStoppedException);
            assertTrue(expected.getMessage().contains("实际读取预检失败"));
            assertFalse(expected.getMessage().contains("driver-secret"));
        }
        assertEquals(1, f.dropCount());
    }

    @Test
    public void stopBetweenWindowsAndCleanupFailureDoNotAdvanceCheckpoint() throws Exception {
        Fixture stopped = new Fixture("KINGBASE");
        stopped.sourceCount = 20000;
        try (StarRocksJdbcResourceFullImport importer = stopped.prepare("time_stamp", 1, "YEAR",
                (current, total, message) -> { if (current > 0) stopped.cancellation.requestStop(); })) {
            try { importer.execute(); fail("Expected stop after first window"); }
            catch (DataSyncCancellation.SyncStoppedException expected) { }
        } finally { stopped.cancellation.close(); }
        assertEquals(1, stopped.inserts.size());
        assertEquals(1, stopped.dropCount());
        Fixture cleanup = new Fixture("DM");
        cleanup.failDrop = true;
        try { cleanup.runFullSync("time_stamp", 50, "YEAR"); fail("Expected cleanup failure"); }
        catch (SQLException expected) { assertTrue(expected.getMessage().contains("清理")); }
        assertTrue(cleanup.checkpoints.isEmpty());
        assertEquals(1, cleanup.inserts.size());
        cleanup.assertUserResourceUntouched();
    }

    @Test
    public void resourceChangeAfterPrepareDoesNotCauseResourceRecreationOrJdbcFallback() throws Exception {
        Fixture f = new Fixture("KINGBASE");
        try (StarRocksJdbcResourceFullImport importer = f.prepare("time_stamp", 50, "YEAR")) {
            f.mutateOnResourceRead = f.resourceReads + 1;
            try { importer.execute(); fail("Expected changed Resource failure"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("发生变化")); }
        }
        assertTrue(f.inserts.isEmpty());
        assertEquals(1, f.dropCount());
        f.assertUserResourceUntouched();
    }

    @Test
    public void importerNamesAreUniqueAndCloseIsIdempotent() throws Exception {
        Fixture f = new Fixture("KINGBASE");
        StarRocksJdbcResourceFullImport first = f.prepare(null, 50, "YEAR");
        first.close();
        first.close();
        StarRocksJdbcResourceFullImport second = f.prepare(null, 50, "YEAR");
        second.close();
        List<String> creates = new ArrayList<>();
        for (String sql : f.targetSql) if (sql.startsWith("CREATE EXTERNAL TABLE")) creates.add(sql);
        assertEquals(2, creates.size());
        assertNotEquals(creates.get(0), creates.get(1));
        assertEquals(2, f.dropCount());
    }

    private static int indexOf(List<String> values, String prefix) {
        for (int index = 0; index < values.size(); index++) if (values.get(index).startsWith(prefix)) return index;
        return -1;
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static final class Fixture {
        final ErDatasource datasource = new ErDatasource();
        final Map<String, String> properties = new LinkedHashMap<>();
        final List<String> sourceSql = new ArrayList<>();
        final List<String> targetSql = new ArrayList<>();
        final List<String> inserts = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<Object> checkpoints = new ArrayList<>();
        final DataSyncCancellation cancellation = new DataSyncCancellation();
        String resourceType = "jdbc";
        String disabledVariable;
        String targetTextType = "VARCHAR(200000)";
        String targetIdType = "BIGINT";
        String jsonTargetType = "JSON";
        boolean jsonColumn;
        boolean largeIntegerText;
        boolean unsignedTinyint;
        String compatibleTextNativeType;
        String kingbaseTextNativeType;
        List<String[]> jsonComparisons = Collections.singletonList(new String[] {"{\"small\":1}", "{\"small\":1}"});
        final List<Integer> jsonComparisonFetchSizes = new ArrayList<>();
        int jsonComparisonRowsRead;
        int stopOnJsonComparisonRow = -1;
        Long actualBinaryBytes;
        String brokenBinaryDecoderSemantic;
        SQLException binaryGuardFailure;
        String targetSampleText = "verified";
        int unsignedBitWidth;
        String actualIntegerMin = "0";
        String actualIntegerMax = "127";
        Long actualIntegerCount;
        Long actualIntegerConvertedCount;
        boolean failNumericInsert;
        boolean jsonProbeValid = true;
        boolean mysqlTimestamp;
        String targetMinText = MIN_TS.toString();
        String targetMaxText = MAX_TS.toString();
        long sourceCount = 7;
        long textBytes = 512;
        Long actualTextBytes = 128L;
        Timestamp min = MIN_TS;
        Timestamp max = MAX_TS;
        long[] affected = {7, 7, 7};
        int resourceReads;
        int mutateOnResourceRead = Integer.MAX_VALUE;
        boolean sampleVisible = true;
        boolean failSecondWindowProbe;
        boolean failExplain;
        boolean stopDuringProbe;
        SQLException probeFailure;
        boolean failDrop;

        Fixture(String sourceType) {
            datasource.setDatasourceType(sourceType);
            datasource.setUsername("reader");
            datasource.setPassword("fixture-password");
            datasource.setDatabaseName("appdb");
            datasource.setDriverClass("KINGBASE".equals(sourceType) ? "com.kingbase8.Driver"
                    : "DM".equals(sourceType) ? "dm.jdbc.driver.DmDriver"
                    : "SQLSERVER".equals(sourceType) ? "com.microsoft.sqlserver.jdbc.SQLServerDriver"
                    : "POSTGRESQL".equals(sourceType) ? "org.postgresql.Driver"
                    : "ORACLE".equals(sourceType) ? "oracle.jdbc.OracleDriver" : "com.mysql.cj.jdbc.Driver");
            datasource.setJdbcUrl("KINGBASE".equals(sourceType) ? "jdbc:kingbase8://source.test:54321/appdb"
                    : "DM".equals(sourceType) ? "jdbc:dm://source.test:5236"
                    : "SQLSERVER".equals(sourceType) ? "jdbc:sqlserver://source.test:1433;databaseName=appdb;encrypt=false"
                    : "POSTGRESQL".equals(sourceType) ? "jdbc:postgresql://source.test:5432/appdb"
                    : "ORACLE".equals(sourceType) ? "jdbc:oracle:thin:@//source.test:1521/erpservice"
                    : "jdbc:mysql://source.test:" + ("STARROCKS".equals(sourceType) ? "9030" : "TIDB".equals(sourceType) ? "4000" : "3306")
                        + "/erp_base?useSSL=false");
            properties.put("type", "jdbc");
            properties.put("jdbc_uri", datasource.getJdbcUrl());
            properties.put("user", "reader");
            properties.put("driver_class", datasource.getDriverClass());
            properties.put("driver_url", "http://drivers.test/verified-driver.jar");
            properties.put("checksum", "0123456789abcdef0123456789abcdef");
        }

        boolean mysqlProtocol() {
            return Arrays.asList("MYSQL", "TIDB", "STARROCKS").contains(datasource.getDatasourceType());
        }

        List<String> sourceColumns() {
            return "POSTGRESQL".equals(datasource.getDatasourceType()) ? Arrays.asList("id", "name", "time_stamp")
                    : "ORACLE".equals(datasource.getDatasourceType()) ? Arrays.asList("ID", "NAME", "TIME_STAMP") : COLUMNS;
        }

        String sourceSchema() {
            return "SQLSERVER".equals(datasource.getDatasourceType()) ? "appdb"
                    : "ORACLE".equals(datasource.getDatasourceType()) ? "ERP_BASE" : "erp_base";
        }

        String sourceTable() { return "ORACLE".equals(datasource.getDatasourceType()) ? "RF_USER" : "rf_user"; }

        StarRocksJdbcResourceFullImport prepare(String timestamp, Integer threshold, String unit) throws Exception {
            return prepare(timestamp, threshold, unit, null);
        }

        StarRocksJdbcResourceFullImport prepare(String timestamp, Integer threshold, String unit,
                cn.easyreport.sync.core.SyncProgressCallback callback) throws Exception {
            return StarRocksJdbcResourceFullImport.prepare(datasource, cancellation.wrap(source()), cancellation.wrap(target()),
                    "jdbc_prebuilt", sourceSchema(), sourceTable(), "`warehouse`.`users`", sourceColumns(), COLUMNS, timestamp,
                    42L, threshold, unit, callback, cancellation);
        }

        void expectPreclearFailure(String timestamp, String text) throws Exception {
            try { runFullSync(timestamp, 1, "YEAR"); fail("Expected preclear failure: " + text); }
            catch (IllegalArgumentException | IllegalStateException | SQLException expected) {
                assertTrue(expected.toString(), expected.getMessage().contains(text));
            }
            assertEquals(-1, indexOf(targetSql, "TRUNCATE TABLE"));
            assertTrue(checkpoints.isEmpty());
        }

        Object runFullSync(String timestamp, Integer threshold, String unit) throws Exception {
            ErDataTransfer task = new ErDataTransfer();
            task.setTransferId(42L);
            task.setSyncMode("0");
            task.setExternalTableEnabled("1");
            task.setDirectImportMethod(ErDataTransfer.DIRECT_IMPORT_JDBC_RESOURCE);
            task.setResourceName("jdbc_prebuilt");
            task.setCatalogName("leftover_catalog");
            task.setExternalTableThresholdWan(threshold);
            task.setExternalTableWindowUnit(unit);
            ErDataTransferProgressMapper progress = proxy(ErDataTransferProgressMapper.class, (method, args) -> {
                assertEquals("upsert", method.getName());
                checkpoints.add(args[0]);
                events.add("checkpoint");
                return 1;
            });
            StarRocksDataSyncExecutor executor = new StarRocksDataSyncExecutor(null, null, progress);
            Method sync = DataSyncExecutor.class.getDeclaredMethod("syncFull", ErDataTransfer.class, ErDatasource.class,
                    String.class, String.class, String.class, List.class, List.class, List.class,
                    DataSyncExtractor.class, DataSyncLoader.class, Connection.class, Connection.class,
                    int.class, String.class, boolean.class, boolean.class, cn.easyreport.sync.core.SyncProgressCallback.class);
            sync.setAccessible(true);
            try {
                return sync.invoke(executor, task, datasource, sourceSchema(), sourceTable(), "`warehouse`.`users`",
                        sourceColumns(), COLUMNS, Collections.singletonList("Id"), DataSyncFactory.buildExtractor(datasource),
                        null, source(), target(), 1000, timestamp, false, false, null);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
                if (failure.getCause() instanceof Error) throw (Error) failure.getCause();
                throw failure;
            }
        }

        void assertUserResourceUntouched() {
            assertFalse(targetSql.toString(), targetSql.stream().anyMatch(sql ->
                    sql.startsWith("CREATE RESOURCE") || sql.startsWith("CREATE EXTERNAL RESOURCE")
                    || sql.startsWith("DROP RESOURCE") || sql.startsWith("ALTER RESOURCE") || sql.contains("CATALOG")));
        }

        int dropCount() { return (int) targetSql.stream().filter(sql -> sql.startsWith("DROP TABLE")).count(); }

        Connection source() {
            return proxy(Connection.class, (method, args) -> {
                if (method.getName().equals("getCatalog")) return mysqlProtocol() ? "erp_base" : datasource.getDatabaseName();
                if (method.getName().equals("getMetaData")) return proxy(DatabaseMetaData.class, (call, values) -> {
                    switch (call.getName()) {
                        case "getURL": return datasource.getJdbcUrl();
                        case "getSQLKeywords": return "";
                        case "getUserName": return datasource.getUsername();
                        case "storesUpperCaseIdentifiers": case "storesLowerCaseIdentifiers": return false;
                        default: return defaultValue(call.getReturnType());
                    }
                });
                if (method.getName().equals("createStatement")) return proxy(Statement.class, (call, values) -> {
                    if (call.getName().equals("executeQuery")) {
                        String sql = (String) values[0]; sourceSql.add(sql);
                        if (sql.startsWith("DESCRIBE ")) {
                            List<Map<Object, Object>> fields = new ArrayList<>();
                            for (int index = 0; index < COLUMNS.size(); index++) {
                                Map<Object, Object> field = new HashMap<>();
                                field.put("Field", COLUMNS.get(index));
                                field.put("Type", index == 0 ? (unsignedBitWidth > 0 ? "BIT(" + unsignedBitWidth + ")" : unsignedTinyint ? "TINYINT UNSIGNED" : largeIntegerText ? "LARGEINT" : "BIGINT")
                                        : index == 1 ? (compatibleTextNativeType != null ? compatibleTextNativeType : jsonColumn ? "JSON" : "VARCHAR(200000)")
                                            : mysqlTimestamp ? "TIMESTAMP" : "DATETIME");
                                field.put("Null", "YES"); fields.add(field);
                            }
                            return rows(fields);
                        }
                        if (sql.endsWith("WHERE 1=0")) return sourceMetadataRows(sql.startsWith("SELECT er_c"));
                        if (sql.startsWith("SELECT COUNT(*)") || sql.startsWith("SELECT COUNT_BIG(*)")) return rows(Collections.singletonList(
                                "BYTEA".equals(kingbaseTextNativeType)
                                        ? indexed(sourceCount, sourceCount == 0 ? null : textBytes, 0L)
                                        : indexed(sourceCount, sourceCount == 0 ? null : textBytes)));
                        if (sql.startsWith("SELECT MIN(")) return rows(Collections.singletonList(
                                "ORACLE".equals(datasource.getDatasourceType())
                                        ? indexed(min, max, min == null ? null : min.toString(), max == null ? null : max.toString())
                                        : indexed(min, max)));
                        if (sql.equals("SELECT @@session.time_zone, @@global.time_zone, @@system_time_zone")) {
                            return rows(Collections.singletonList(indexed("+08:00", "+08:00", "Asia/Shanghai")));
                        }
                        throw new AssertionError("Unexpected source SQL: " + sql);
                    }
                    return defaultValue(call.getReturnType());
                });
                return defaultValue(method.getReturnType());
            });
        }

        ResultSet sourceMetadataRows(boolean scanProjection) {
            return proxy(ResultSet.class, (method, args) -> {
                if (method.getName().equals("getMetaData")) return proxy(ResultSetMetaData.class, (call, values) -> {
                    if (call.getName().equals("getColumnCount")) return 3;
                    int index = values != null && values.length > 0 && values[0] instanceof Integer ? (Integer) values[0] : 1;
                    boolean kingbaseTime = kingbaseTextNativeType != null && kingbaseTextNativeType.startsWith("TIME");
                    switch (call.getName()) {
                        case "getColumnType": return index == 1 ? (unsignedBitWidth > 0 ? Types.BIT : unsignedTinyint ? Types.TINYINT : largeIntegerText ? Types.VARCHAR : Types.BIGINT)
                                : index == 2 ? (kingbaseTextNativeType != null ? scanProjection ? Types.LONGVARCHAR
                                        : kingbaseTime ? Types.TIME : "BYTEA".equals(kingbaseTextNativeType) ? Types.BINARY : Types.OTHER
                                        : compatibleTextNativeType != null
                                        ? compatibleTextNativeType.startsWith("TIME") ? Types.TIME : Types.VARBINARY
                                        : jsonColumn ? Types.LONGVARCHAR : Types.VARCHAR) : Types.TIMESTAMP;
                        case "getColumnTypeName": return index == 1 ? (unsignedBitWidth > 0 ? "BIT" : unsignedTinyint ? "TINYINT UNSIGNED" : largeIntegerText ? "VARCHAR" : "BIGINT")
                                : index == 2 ? (kingbaseTextNativeType != null ? scanProjection ? "TEXT" : kingbaseTextNativeType
                                        : compatibleTextNativeType != null ? compatibleTextNativeType : jsonColumn ? "JSON" : "VARCHAR")
                                    : "SQLSERVER".equals(datasource.getDatasourceType()) ? "DATETIME2"
                                    : mysqlProtocol() && !mysqlTimestamp ? "DATETIME" : "TIMESTAMP";
                        case "getColumnClassName": return index == 1 ? (unsignedBitWidth > 0 ? "java.lang.Boolean" : unsignedTinyint ? "java.lang.Integer" : largeIntegerText ? "java.lang.String" : "java.lang.Long")
                                : index == 2 ? (kingbaseTextNativeType != null ? scanProjection ? "java.lang.String"
                                        : kingbaseTime ? "java.sql.Time" : "BYTEA".equals(kingbaseTextNativeType) ? "[B" : "com.kingbase8.util.KBobject"
                                        : compatibleTextNativeType != null
                                        ? compatibleTextNativeType.startsWith("TIME") ? "java.sql.Time" : "[B"
                                        : "java.lang.String") : "java.sql.Timestamp";
                        case "getColumnName": case "getColumnLabel": return scanProjection ? "er_c" + (index - 1) : sourceColumns().get(index - 1);
                        case "getPrecision": return index == 1 ? (unsignedBitWidth > 0 ? 1 : unsignedTinyint ? 3 : largeIntegerText ? 40 : 19) : index == 2 ? 200000 : 29;
                        case "getScale": return index == 3 || index == 2 && kingbaseTime && !scanProjection ? 6 : 0;
                        case "isNullable": return ResultSetMetaData.columnNullable;
                        default: return defaultValue(call.getReturnType());
                    }
                });
                return defaultValue(method.getReturnType());
            });
        }

        Connection target() {
            return proxy(Connection.class, (method, args) -> {
                if (method.getName().equals("getAutoCommit")) return false;
                if (method.getName().equals("createStatement")) {
                    int[] fetchSize = {0};
                    return proxy(Statement.class, (call, values) -> {
                    if ("SQLSERVER".equals(datasource.getDatasourceType()) && call.getName().equals("setMaxRows")) {
                        throw new AssertionError("Connector/J setMaxRows can reintroduce a pushed LIMIT");
                    }
                    if (call.getName().equals("setFetchSize")) { fetchSize[0] = (Integer) values[0]; return null; }
                    if (call.getName().startsWith("execute")) {
                        String sql = (String) values[0]; targetSql.add(sql);
                        if (call.getName().equals("execute")) {
                            if (sql.startsWith("DROP TABLE")) {
                                events.add("drop");
                                if (failDrop) throw new SQLException("driver-secret-on-drop", "HY000", 111);
                            }
                            return false;
                        }
                        if (call.getName().equals("executeLargeUpdate")) {
                            inserts.add(sql);
                            if (failNumericInsert) {
                                assertTrue(sql, sql.contains("assert_true("));
                                throw new SQLException("driver-secret-numeric-range-overflow", "HY000", 999);
                            }
                            return affected[inserts.size() - 1];
                        }
                        if (sql.equals("SHOW RESOURCES")) return resourceRows();
                        if (sql.equals("DESCRIBE `warehouse`.`users`")) return targetTypes();
                        if (sql.startsWith("SHOW VARIABLES LIKE '")) {
                            String variable = sql.substring("SHOW VARIABLES LIKE '".length(), sql.length() - 1);
                            return rows(Collections.singletonList(indexed(variable, variable.equals("query_timeout") ? 300L
                                    : variable.equals(disabledVariable) ? "false" : "true")));
                        }
                        if (sql.startsWith("EXPLAIN")) {
                            if (failExplain) throw new SQLException("driver-secret-on-explain", "HY000", 222);
                            return rows(Collections.singletonList(indexed("INSERT PLAN")));
                        }
                        if (sql.equals(StarRocksExternalFullImport.JSON_CONVERSION_PROBE_SQL)) {
                            return rows(Collections.singletonList(indexed(jsonProbeValid ? "123" : "\"123\"", "true", "null", null)));
                        }
                        if (sql.equals(StarRocksJdbcResourceFullImport.BINARY_CONVERSION_PROBE_SQL)) {
                            return rows(Collections.singletonList(indexed("hex".equals(brokenBinaryDecoderSemantic) ? "30304646" : "00FF",
                                    "empty".equals(brokenBinaryDecoderSemantic) ? null : 0L,
                                    !"null".equals(brokenBinaryDecoderSemantic))));
                        }
                        if ((sql.startsWith("SELECT COUNT(`Id`)") || sql.startsWith("SELECT COUNT(`er_c0`)")) && sql.contains("`easyreport_import_tmp`.")) {
                            assertFalse("Integer range scans must not have a pushed LIMIT", sql.contains(" LIMIT "));
                            long count = actualIntegerCount == null ? sourceCount : actualIntegerCount;
                            long converted = actualIntegerConvertedCount == null ? count : actualIntegerConvertedCount;
                            return rows(Collections.singletonList(indexed(count, converted, actualIntegerMin, actualIntegerMax)));
                        }
                        if (sql.startsWith("SELECT COUNT(*)") && sql.contains("`easyreport_import_tmp`.")) {
                            assertFalse("SQLServer Resource must not receive a pushed LIMIT", sql.contains(" LIMIT "));
                            if (failSecondWindowProbe && sql.contains(" >= '2024-01-01")) {
                                throw new SQLException("driver-secret-window", "42000", 333);
                            }
                            return rows(Collections.singletonList(indexed(sampleVisible ? sourceCount : 0L,
                                    sampleVisible ? 2L : null, sampleVisible ? actualTextBytes : null,
                                    sampleVisible ? 26L : null)));
                        }
                        if (sql.startsWith("SELECT MAX(LENGTH(HEX(IF(ASSERT_TRUE(")) {
                            assertFalse("Binary round-trip guards must inspect every eligible row", sql.contains(" LIMIT "));
                            if (binaryGuardFailure != null) throw binaryGuardFailure;
                            return rows(Collections.singletonList(indexed(actualBinaryBytes != null ? Long.valueOf(actualBinaryBytes * 2)
                                    : actualTextBytes)));
                        }
                        if (sql.startsWith("SELECT MAX(LENGTH(")) {
                            return rows(Collections.singletonList(indexed(sourceCount == 0 ? null : actualTextBytes)));
                        }
                        if (sql.startsWith("SELECT MIN(") && sql.contains("`easyreport_import_tmp`.")) {
                            return rows(Collections.singletonList(indexed(targetMinText, targetMaxText)));
                        }
                        if (sql.startsWith("SELECT `er_c1`, JSON_STRING(") && sql.contains("`easyreport_import_tmp`.")) {
                            assertEquals("JSON comparison must stream using Connector/J's fetch mode", Integer.MIN_VALUE, fetchSize[0]);
                            jsonComparisonFetchSizes.add(fetchSize[0]);
                            return jsonComparisonRows();
                        }
                        if (sql.startsWith("SELECT ") && sql.contains("`easyreport_import_tmp`.")) {
                            if (stopDuringProbe) { cancellation.requestStop(); throw new SQLException("driver-secret-on-cancel", "57014"); }
                            if (probeFailure != null) throw probeFailure;
                            if (failSecondWindowProbe && sql.contains(" >= '2024-01-01")) {
                                throw new SQLException("driver-secret-window", "42000", 333);
                            }
                            return rows(sampleVisible ? Collections.singletonList(indexed(unsignedBitWidth > 0
                                    ? BigInteger.ONE.shiftLeft(unsignedBitWidth).subtract(BigInteger.ONE) : 7L, targetSampleValue(), MAX_TS)) : Collections.emptyList());
                        }
                        throw new AssertionError("Unexpected target SQL: " + sql);
                    }
                    return defaultValue(call.getReturnType());
                    });
                }
                return defaultValue(method.getReturnType());
            });
        }

        Object targetSampleValue() {
            if (targetTextType.startsWith("BINARY") || targetTextType.startsWith("VARBINARY")) {
                return actualTextBytes == null ? null : actualTextBytes == 0 ? new byte[0] : new byte[] {0, (byte) 255};
            }
            return targetSampleText;
        }

        ResultSet jsonComparisonRows() {
            int[] cursor = {-1};
            return proxy(ResultSet.class, (method, args) -> {
                if (method.getName().equals("next")) {
                    cursor[0]++;
                    if (cursor[0] == stopOnJsonComparisonRow) cancellation.requestStop();
                    return cursor[0] < jsonComparisons.size();
                }
                if (method.getName().equals("getString")) {
                    int index = (Integer) args[0];
                    if (index == 2) jsonComparisonRowsRead++;
                    return jsonComparisons.get(cursor[0])[index - 1];
                }
                return defaultValue(method.getReturnType());
            });
        }

        ResultSet resourceRows() {
            resourceReads++;
            List<Map<Object, Object>> result = new ArrayList<>();
            if (resourceType != null) {
                Map<String, String> visible = new LinkedHashMap<>(properties);
                if (resourceReads >= mutateOnResourceRead) visible.put("driver_url", "http://drivers.test/replaced.jar");
                for (Map.Entry<String, String> item : visible.entrySet()) {
                    Map<Object, Object> row = new HashMap<>();
                    row.put("Name", "jdbc_prebuilt"); row.put("ResourceType", resourceType);
                    row.put("Key", item.getKey()); row.put("Value", item.getValue());
                    result.add(row);
                }
            }
            return rows(result);
        }

        ResultSet targetTypes() {
            List<Map<Object, Object>> result = new ArrayList<>();
            for (int index = 0; index < COLUMNS.size(); index++) {
                Map<Object, Object> row = new HashMap<>();
                row.put("Field", COLUMNS.get(index)); row.put("Type", index == 0 ? targetIdType
                        : index == 1 ? (jsonColumn ? jsonTargetType : targetTextType) : "DATETIME");
                result.add(row);
            }
            return rows(result);
        }
    }

    private static Map<Object, Object> indexed(Object... values) {
        Map<Object, Object> row = new HashMap<>();
        for (int index = 0; index < values.length; index++) row.put(index + 1, values[index]);
        return row;
    }

    private static ResultSet rows(List<Map<Object, Object>> values) {
        int[] index = {-1};
        boolean[] wasNull = {false};
        return proxy(ResultSet.class, (method, args) -> {
            if (method.getName().equals("next")) return ++index[0] < values.size();
            if (method.getName().equals("wasNull")) return wasNull[0];
            if (method.getName().startsWith("get") && args != null && args.length > 0) {
                Object value = values.get(index[0]).get(args[0]); wasNull[0] = value == null;
                if (method.getName().equals("getLong")) return value == null ? 0L : ((Number) value).longValue();
                if (method.getName().equals("getString")) return value == null ? null : value.toString();
                return value;
            }
            return defaultValue(method.getReturnType());
        });
    }

    private interface Invocation { Object invoke(Method method, Object[] args) throws Throwable; }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (object, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if (method.getName().equals("toString")) return type.getSimpleName() + " Resource fixture";
                if (method.getName().equals("hashCode")) return System.identityHashCode(object);
                if (method.getName().equals("equals")) return object == args[0];
            }
            return invocation.invoke(method, args);
        }));
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
        throw new AssertionError(type);
    }
}
