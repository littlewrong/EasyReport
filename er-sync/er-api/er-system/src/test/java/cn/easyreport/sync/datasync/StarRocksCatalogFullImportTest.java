package cn.easyreport.sync.datasync;

import cn.easyreport.sync.domain.ErDataTransfer;
import cn.easyreport.sync.domain.ErDatasource;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
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
import java.util.List;

import static org.junit.Assert.*;

/** SQL contract tests: neither a source server nor a StarRocks deployment is contacted. */
public class StarRocksCatalogFullImportTest {
    @Test public void smallImportReadsCountOnSourceAndRetainsSharedCatalog() throws Exception {
        Fixture f = new Fixture();
        try (StarRocksCatalogFullImport importer = f.prepare(50, "YEAR")) {
            assertTrue(f.inserts.isEmpty());
            StarRocksCatalogFullImport.Result result = importer.execute();
            assertEquals(17L, result.processed);
            assertEquals(f.max, result.maxTs);
        }
        assertEquals(1, f.inserts.size());
        assertEquals("INSERT INTO `default_catalog`.`warehouse`.`orders` (`id`, `time_stamp`) SELECT `id`, `time_stamp`"
                + " FROM `src_mysql`.`erp_base`.`orders` WHERE `time_stamp` IS NOT NULL", f.inserts.get(0));
        assertTrue(f.sourceSql.contains("SELECT COUNT(*) FROM `erp_base`.`orders` WHERE `time_stamp` IS NOT NULL"));
        assertTrue(f.sourceSql.contains("SELECT MIN(`time_stamp`), MAX(`time_stamp`) FROM `erp_base`.`orders` WHERE `time_stamp` IS NOT NULL"));
        assertFalse(f.targetSql.stream().anyMatch(sql -> sql.contains("COUNT(") || sql.contains("COUNT_BIG(")));
        assertFalse(f.targetSql.stream().anyMatch(sql -> sql.startsWith("CREATE ") || sql.startsWith("DROP ") || sql.startsWith("SET CATALOG")));
        assertEquals(1, f.commits);
    }

    @Test public void thresholdBoundaryUsesNaturalYearAndSkipsNulls() throws Exception {
        for (long count : new long[]{499999, 500000}) {
            Fixture f = new Fixture();
            f.count = count;
            try (StarRocksCatalogFullImport importer = f.prepare(50, "YEAR")) { importer.execute(); }
            if (count == 499999) {
                assertEquals(1, f.inserts.size());
                assertTrue(f.inserts.get(0).endsWith("IS NOT NULL"));
            } else {
                assertEquals(2, f.inserts.size());
                assertTrue(f.inserts.get(0).endsWith("`time_stamp` >= '2023-01-01 00:00:00' AND `time_stamp` < '2024-01-01 00:00:00'"));
                assertTrue(f.inserts.get(1).endsWith("`time_stamp` >= '2024-01-01 00:00:00' AND `time_stamp` < '2025-01-01 00:00:00'"));
            }
            assertFalse(f.inserts.stream().anyMatch(sql -> sql.contains(" IS NULL")));
        }
    }

    @Test public void monthAndLeapDayWindowsUseHalfOpenNaturalBoundaries() throws Exception {
        Fixture month = new Fixture();
        month.count = 10000;
        month.min = Timestamp.valueOf("2024-02-29 23:59:59");
        month.max = Timestamp.valueOf("2024-03-01 00:00:00");
        try (StarRocksCatalogFullImport importer = month.prepare(1, "MONTH")) { importer.execute(); }
        assertEquals(2, month.inserts.size());
        assertTrue(month.inserts.get(0).contains(">= '2024-02-01 00:00:00'"));
        assertTrue(month.inserts.get(0).contains("< '2024-03-01 00:00:00'"));
        Fixture day = new Fixture();
        day.count = 10000;
        day.min = month.min;
        day.max = month.max;
        try (StarRocksCatalogFullImport importer = day.prepare(1, "DAY")) { importer.execute(); }
        assertEquals(2, day.inserts.size());
        assertTrue(day.inserts.get(0).contains(">= '2024-02-29 00:00:00'"));
        assertTrue(day.inserts.get(1).contains("< '2024-03-02 00:00:00'"));
    }

    @Test public void allNullTimestampsDoNotInventWatermarkOrInsert() throws Exception {
        Fixture f = new Fixture();
        f.count = 0;
        try (StarRocksCatalogFullImport importer = f.prepare(50, "YEAR")) {
            StarRocksCatalogFullImport.Result result = importer.execute();
            assertEquals(0L, result.processed);
            assertNull(result.maxTs);
        }
        assertTrue(f.inserts.isEmpty());
        assertFalse(f.sourceSql.stream().anyMatch(sql -> sql.contains("MIN(")));
    }

    @Test public void postgresUsesSchemaAndValidatesFixedDatabase() throws Exception {
        Fixture f = new Fixture("POSTGRESQL", "jdbc:postgresql://source:5432/erp_base", "public");
        try (StarRocksCatalogFullImport importer = f.prepare(50, "YEAR")) { importer.execute(); }
        assertTrue(f.sourceSql.contains("SELECT COUNT(*) FROM \"public\".\"orders\" WHERE \"time_stamp\" IS NOT NULL"));
        assertTrue(f.inserts.get(0).contains("FROM `src_mysql`.`public`.`orders`"));
        f = new Fixture("POSTGRESQL", "jdbc:postgresql://source:5432/erp_base", "public");
        f.catalogUrl = "jdbc:postgresql://source:5432/other_db";
        assertFailure(f, "数据库不一致");
        assertTrue(f.sourceSql.isEmpty());
    }

    @Test public void sqlServerUsesDatabaseDboAndCountBig() throws Exception {
        Fixture f = new Fixture("SQLSERVER", "jdbc:sqlserver://source:1433;databaseName=erp_base", "erp_base");
        try (StarRocksCatalogFullImport importer = f.prepare(50, "YEAR")) { importer.execute(); }
        assertTrue(f.sourceSql.contains("SELECT COUNT_BIG(*) FROM [erp_base].[dbo].[orders] WHERE [time_stamp] IS NOT NULL"));
        assertTrue(f.inserts.get(0).contains("FROM `src_mysql`.`dbo`.`orders`"));
        Fixture wrong = new Fixture("SQLSERVER", "jdbc:sqlserver://source:1433;databaseName=erp_base", "erp_base");
        wrong.catalogUrl = "jdbc:sqlserver://source:1433;databaseName=other_db";
        assertFailure(wrong, "数据库不一致");
    }

    @Test public void oracleUsesUppercaseSchemaAndTable() throws Exception {
        Fixture f = new Fixture("ORACLE", "jdbc:oracle:thin:@//source:1521/erp_service", "erp_base");
        try (StarRocksCatalogFullImport importer = f.prepare(50, "YEAR")) { importer.execute(); }
        assertTrue(f.sourceSql.contains("SELECT COUNT(*) FROM \"ERP_BASE\".\"ORDERS\" WHERE \"time_stamp\" IS NOT NULL"));
        assertTrue(f.inserts.get(0).contains("FROM `src_mysql`.`ERP_BASE`.`ORDERS`"));
    }

    @Test public void starRocksSourceUsesMysqlCatalogAndItsOwnSourceSql() throws Exception {
        Fixture f = new Fixture("STARROCKS", "jdbc:mysql://public-fe:19030/erp_base?serverTimezone=GMT%2B8", "erp_base");
        f.catalogUrl = "jdbc:mysql://private-fe:9030";
        f.count = 500000;
        try (StarRocksCatalogFullImport importer = f.prepare(50, "YEAR")) { importer.execute(); }
        assertEquals(2, f.inserts.size());
        assertTrue(f.sourceSql.contains("DESCRIBE `erp_base`.`orders`"));
        assertTrue(f.sourceSql.contains("SELECT COUNT(*) FROM `erp_base`.`orders` WHERE `time_stamp` IS NOT NULL"));
        assertTrue(f.inserts.get(0).contains("FROM `src_mysql`.`erp_base`.`orders`"));
        assertFalse(f.sourceSql.stream().anyMatch(sql -> sql.contains("@@")));
        assertFalse(f.targetSql.stream().anyMatch(sql -> sql.startsWith("CREATE ") || sql.startsWith("DROP ") || sql.contains("COUNT(")));
        Fixture defaultPort = new Fixture("STARROCKS", "jdbc:mysql://source/erp_base", "erp_base");
        defaultPort.metadataUrl = "jdbc:mysql://source:3306/erp_base";
        try (StarRocksCatalogFullImport importer = defaultPort.prepare(50, "YEAR")) { importer.execute(); }
        assertEquals(1, defaultPort.inserts.size());
    }

    @Test public void starRocksNativeJsonMeasuresSerializedBytesAndKeepsJsonScalarGuard() throws Exception {
        for (String nativeType : new String[]{"JSON", "VARCHAR(65533)"}) {
            Fixture f = new Fixture("STARROCKS", "jdbc:mysql://source:9030/erp_base", "erp_base");
            f.jsonColumn = true;
            f.nativeJsonType = nativeType;
            try (StarRocksCatalogFullImport importer = f.prepare(50, "YEAR")) { importer.execute(); }
            String length = "JSON".equals(nativeType) ? "LENGTH(JSON_STRING(`payload`))" : "LENGTH(CAST(`payload` AS VARCHAR))";
            assertTrue(f.sourceSql.contains("SELECT COUNT(*), MAX(" + length + ") FROM `erp_base`.`orders` WHERE `time_stamp` IS NOT NULL"));
            assertTrue(f.inserts.get(0).contains("JSON_QUERY(PARSE_JSON(CONCAT('[', `payload`, ']')), '$[0]')"));
            assertFalse(f.sourceSql.stream().anyMatch(sql -> sql.contains("CHARACTER SET")));
        }
    }

    @Test public void starRocksCannotTruncateItsOwnSourceTable() throws Exception {
        for (String sourceUrl : new String[]{"jdbc:mysql://target-fe:9030/warehouse", "jdbc:mysql://public-fe:19030/warehouse"}) {
            Fixture f = new Fixture("STARROCKS", sourceUrl, "warehouse");
            f.catalogUrl = "jdbc:mysql://target-fe:9030";
            f.targetMetadataUrl = "jdbc:mysql://target-fe:9030/warehouse";
            assertFailure(f, "源表与目标表为同一表");
            assertTrue(f.sourceSql.isEmpty());
            assertTrue(f.inserts.isEmpty());
        }
    }

    @Test public void starRocksSameTableThroughDifferentFeChecksClusterIdentity() throws Exception {
        Fixture same = new Fixture("STARROCKS", "jdbc:mysql://public-fe:19030/warehouse", "warehouse");
        same.catalogUrl = "jdbc:mysql://private-fe-1:9030";
        same.targetMetadataUrl = "jdbc:mysql://private-fe-2:9030/warehouse";
        same.targetClusterId = same.sourceClusterId;
        assertFailure(same, "源表与目标表为同一表");
        assertEquals(Collections.singletonList("SHOW FRONTENDS"), same.sourceSql);
        assertTrue(same.inserts.isEmpty());
        Fixture separate = new Fixture("STARROCKS", "jdbc:mysql://cluster-one:9030/warehouse", "warehouse");
        separate.targetMetadataUrl = "jdbc:mysql://cluster-two:9030/warehouse";
        try (StarRocksCatalogFullImport importer = separate.prepare(50, "YEAR")) { importer.execute(); }
        assertTrue(separate.sourceSql.contains("SHOW FRONTENDS"));
        assertTrue(separate.targetSql.contains("SHOW FRONTENDS"));
        assertEquals(1, separate.inserts.size());
    }

    @Test public void starRocksSameNamedTableWithoutClusterMetadataPreservesTarget() throws Exception {
        Fixture f = new Fixture("STARROCKS", "jdbc:mysql://public-fe:19030/warehouse", "warehouse");
        f.catalogUrl = "jdbc:mysql://private-fe-1:9030";
        f.targetMetadataUrl = "jdbc:mysql://private-fe-2:9030/warehouse";
        f.failClusterProbe = true;
        try { f.prepare(50, "YEAR"); fail("Cannot prove that the source and target are different clusters"); }
        catch (SQLException expected) {
            assertTrue(expected.getMessage().contains("SHOW FRONTENDS"));
            assertTrue(expected.getMessage().contains("尚未清空目标表"));
            assertFalse(expected.getMessage().contains("secret-in-driver"));
        }
        assertTrue(f.inserts.isEmpty());
        assertFalse(f.sourceSql.stream().anyMatch(sql -> sql.contains("COUNT(")));
    }

    @Test public void starRocksComplexSourceTypeFailsBeforeCountOrTargetClear() throws Exception {
        for (String nativeType : new String[]{"ARRAY<INT>", "MAP<VARCHAR,INT>", "STRUCT<a INT>", "HLL", "BITMAP", "PERCENTILE", "VARBINARY"}) {
            Fixture f = new Fixture("STARROCKS", "jdbc:mysql://source:9030/erp_base", "erp_base");
            f.nativeIdType = nativeType;
            f.catalogColumnType = "VARCHAR(65533)";
            assertFailure(f, "不支持通过 MySQL JDBC Catalog 无损导入");
            assertFalse(f.sourceSql.stream().anyMatch(sql -> sql.contains("COUNT(")));
            assertTrue(f.inserts.isEmpty());
            assertFalse(f.targetSql.stream().anyMatch(sql -> sql.startsWith("TRUNCATE")));
        }
    }

    @Test public void oracleDateMappingCannotLoseClockOrTimestampWatermark() throws Exception {
        for (String timestamp : new String[]{"time_stamp", null}) {
            Fixture f = new Fixture("ORACLE", "jdbc:oracle:thin:@//source:1521/erp_service", "erp_base");
            f.timestampTypeName = "DATE";
            f.catalogTimestampType = "DATE";
            f.timestampField = timestamp;
            assertFailure(f, "会丢失时分秒");
            assertFalse(f.sourceSql.stream().anyMatch(sql -> sql.contains("COUNT(")));
            assertTrue(f.inserts.isEmpty());
        }
        Fixture intentional = new Fixture("ORACLE", "jdbc:oracle:thin:@source:1521:ERP", "erp_base");
        intentional.timestampTypeName = "DATE";
        intentional.catalogTimestampType = "DATE";
        intentional.targetTimestampType = "DATE";
        intentional.timestampField = null;
        try (StarRocksCatalogFullImport importer = intentional.prepare(50, "YEAR")) { importer.execute(); }
        assertEquals(1, intentional.inserts.size());
    }

    @Test public void oracleTextTimestampUsesGuardedWholeImportButCannotWindow() throws Exception {
        Fixture small = new Fixture("ORACLE", "jdbc:oracle:thin:@source:1521:ERP", "erp_base");
        small.timestampTypeName = "TIMESTAMP";
        small.catalogTimestampType = "VARCHAR(64)";
        try (StarRocksCatalogFullImport importer = small.prepare(50, "YEAR")) { importer.execute(); }
        assertTrue(small.inserts.get(0).contains("ASSERT_TRUE(`time_stamp` IS NULL OR CAST(`time_stamp` AS DATETIME) IS NOT NULL"));
        assertTrue(small.inserts.get(0).endsWith("WHERE `time_stamp` IS NOT NULL"));
        Fixture large = new Fixture("ORACLE", "jdbc:oracle:thin:@source:1521:ERP", "erp_base");
        large.timestampTypeName = "TIMESTAMP";
        large.catalogTimestampType = "VARCHAR(64)";
        large.count = 500000;
        assertFailure(large, "无法保证日期过滤正确下推");
        assertTrue(large.inserts.isEmpty());
    }

    @Test public void oracleNonWindowTimezoneColumnsCannotSilentlyLoseTimezone() throws Exception {
        Fixture f = new Fixture("ORACLE", "jdbc:oracle:thin:@//source:1521/erp_service", "erp_base");
        f.nativeIdType = "TIMESTAMP WITH LOCAL TIME ZONE";
        f.catalogColumnType = "DATETIME";
        assertFailure(f, "需要显式时区转换");
        assertFalse(f.sourceSql.stream().anyMatch(sql -> sql.contains("COUNT(")));
    }

    @Test public void oracleSubMicrosecondPrecisionFailsBeforeTargetClear() throws Exception {
        Fixture f = new Fixture("ORACLE", "jdbc:oracle:thin:@//source:1521/erp_service", "erp_base");
        f.timestampTypeName = "TIMESTAMP(9)";
        assertFailure(f, "超过 StarRocks DATETIME 支持的微秒精度");
        assertTrue(f.inserts.isEmpty());
    }

    @Test public void oracleNativeWindowRunsBeforeClearAndNlsErrorsPreserveTarget() throws Exception {
        Fixture f = new Fixture("ORACLE", "jdbc:oracle:thin:@//source:1521/erp_service", "erp_base");
        f.timestampTypeName = "DATE";
        f.count = 500000;
        List<Object> checkpoints = new ArrayList<>();
        runFullSync(f, checkpoints);
        int clear = f.targetSql.indexOf("TRUNCATE TABLE `warehouse`.`orders`");
        assertTrue(clear > 0);
        assertTrue(f.targetSql.stream().limit(clear).anyMatch(sql -> sql.startsWith("SELECT ")
                && sql.contains("WHERE `time_stamp` >= '2023-01-01 00:00:00'") && sql.endsWith("LIMIT 1")));
        assertEquals(2, f.inserts.size());
        assertEquals(1, checkpoints.size());
        Fixture bad = new Fixture("ORACLE", "jdbc:oracle:thin:@//source:1521/erp_service", "erp_base");
        bad.count = 500000;
        bad.failWindowProbe = true;
        checkpoints.clear();
        try { runFullSync(bad, checkpoints); fail("Oracle NLS error must be found before target clear"); }
        catch (SQLException expected) {
            assertTrue(expected.getMessage().contains("尚未清空目标表"));
            assertTrue(expected.getMessage().contains("NLS"));
            assertFalse(expected.getMessage().contains("secret-in-driver"));
            assertNull(expected.getCause());
        }
        assertFalse(bad.targetSql.stream().anyMatch(sql -> sql.startsWith("TRUNCATE")));
        assertTrue(bad.inserts.isEmpty());
        assertTrue(checkpoints.isEmpty());
    }

    @Test public void oracleServiceAndSidMustIdentifySameSource() throws Exception {
        Fixture f = new Fixture("ORACLE", "jdbc:oracle:thin:@source:1521:ERP", "erp_base");
        f.catalogUrl = "jdbc:oracle:thin:@//source:1521/ERP";
        assertFailure(f, "Oracle 服务或 SID");
        assertTrue(f.sourceSql.isEmpty());
    }

    @Test public void catalogAllowsDifferentNetworkEndpointsAndKeepsStatisticsOnSource() throws Exception {
        String[][] endpoints = {
                {"jdbc:mysql://203.0.113.10:4000/erp_base", "jdbc:mysql://10.0.0.10:4000"},
                {"jdbc:mysql://tidb-public.example:4000/erp_base", "jdbc:mysql://10.0.0.10:4000"},
                {"jdbc:mysql://203.0.113.10:14000/erp_base", "jdbc:mysql://10.0.0.10:4000"}
        };
        for (String[] endpoint : endpoints) {
            Fixture f = new Fixture("TIDB", endpoint[0], "erp_base");
            f.catalogUrl = endpoint[1];
            try (StarRocksCatalogFullImport importer = f.prepare(50, "YEAR")) { importer.execute(); }
            assertEquals(endpoint[0], f.datasource.getJdbcUrl());
            assertEquals(endpoint[1], f.catalogUrl);
            assertTrue(f.sourceSql.contains("SELECT COUNT(*) FROM `erp_base`.`orders` WHERE `time_stamp` IS NOT NULL"));
            assertFalse(f.targetSql.stream().anyMatch(sql -> sql.contains("COUNT(") || sql.contains("COUNT_BIG(")));
            assertEquals(1, f.inserts.size());
            assertTrue(f.inserts.get(0).contains("FROM `src_mysql`.`erp_base`.`orders`"));
            assertFalse(f.targetSql.stream().anyMatch(sql -> sql.startsWith("CREATE ") || sql.startsWith("DROP ")));
        }
    }

    @Test public void actualSourceJdbcEndpointMismatchFailsBeforeCountOrTargetChanges() throws Exception {
        Fixture f = new Fixture();
        f.metadataUrl = "jdbc:mysql://different-connection:3306/erp_base";
        assertFailure(f, "实际源 JDBC 连接与源数据源配置");
        assertTrue(f.sourceSql.isEmpty());
        assertTrue(f.inserts.isEmpty());
        assertEquals(Collections.singletonList("SHOW CREATE CATALOG `src_mysql`"), f.targetSql);
    }

    @Test public void systemShanghaiTimestampWithDefaultCatalogUsesActualDataRange() throws Exception {
        Fixture f = new Fixture("TIDB", "jdbc:mysql://203.0.113.10:14000/erp_base?serverTimezone=GMT%2B8", "erp_base");
        f.catalogUrl = "jdbc:mysql://10.0.0.10:4000";
        f.timestampTypeName = "TIMESTAMP";
        List<Object> checkpoints = new ArrayList<>();
        runFullSync(f, checkpoints);
        assertEquals(1, f.inserts.size());
        assertEquals(1, checkpoints.size());
        String zoneSql = "SELECT @@session.time_zone, @@global.time_zone, @@system_time_zone";
        assertTrue(f.sourceSql.contains(zoneSql));
        assertTrue(f.sourceSql.indexOf(zoneSql) > f.sourceSql.indexOf(
                "SELECT MIN(`time_stamp`), MAX(`time_stamp`) FROM `erp_base`.`orders` WHERE `time_stamp` IS NOT NULL"));

        Fixture empty = new Fixture();
        empty.timestampTypeName = "TIMESTAMP";
        empty.count = 0;
        try (StarRocksCatalogFullImport importer = empty.prepare(50, "YEAR")) {
            assertNull(importer.execute().maxTs);
        }
        assertFalse(empty.sourceSql.contains(zoneSql));
        assertTrue(empty.inserts.isEmpty());
    }

    @Test public void rowversionAndNumericTimestampAreRejectedBeforeCount() throws Exception {
        Fixture f = new Fixture("SQLSERVER", "jdbc:sqlserver://source:1433;databaseName=erp_base", "erp_base");
        f.timestampType = Types.BINARY;
        f.timestampTypeName = "timestamp";
        assertFailure(f, "rowversion");
        assertFalse(f.sourceSql.stream().anyMatch(sql -> sql.contains("COUNT")));
        Fixture numeric = new Fixture();
        numeric.timestampType = Types.BIGINT;
        numeric.timestampTypeName = "BIGINT";
        assertFailure(numeric, "数值时间戳");
    }

    @Test public void unsupportedColumnOrExplainFailurePreventsPreparedImport() throws Exception {
        Fixture f = new Fixture();
        f.catalogColumnType = "UNKNOWN_TYPE";
        assertFailure(f, "类型不受支持");
        assertTrue(f.inserts.isEmpty());
        Fixture explain = new Fixture();
        explain.failExplain = true;
        try { explain.prepare(50, "YEAR"); fail("Expected failed preflight"); }
        catch (SQLException expected) {
            assertTrue(expected.getMessage().contains("尚未清空目标表"));
            assertFalse(expected.getMessage().contains("secret-in-driver"));
            assertNull(expected.getCause());
        }
        assertTrue(explain.inserts.isEmpty());
        assertFalse(explain.targetSql.stream().anyMatch(sql -> sql.startsWith("TRUNCATE") || sql.startsWith("DROP")));
    }

    @Test public void sourceHasRowsButCatalogCannotSeeThemFailsBeforeClear() throws Exception {
        Fixture f = new Fixture();
        f.catalogSeesRows = false;
        try { f.prepare(50, "YEAR"); fail("Catalog must expose the counted source rows"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("Catalog 未读取到数据")); }
        assertTrue(f.inserts.isEmpty());
        assertFalse(f.targetSql.stream().anyMatch(sql -> sql.startsWith("TRUNCATE") || sql.startsWith("DROP")));
    }

    @Test public void differentCatalogAccountIsRejectedBeforeSourceCount() throws Exception {
        Fixture f = new Fixture();
        f.catalogUser = "another_user";
        assertFailure(f, "连接账号与源数据源账号不一致");
        assertTrue(f.sourceSql.isEmpty());
    }

    @Test public void catalogTextToJsonUsesTypedScalarProjectionAndSourceBytePreflight() throws Exception {
        Fixture f = new Fixture();
        f.jsonColumn = true;
        try (StarRocksCatalogFullImport importer = f.prepare(50, "YEAR")) { importer.execute(); }
        assertTrue(f.sourceSql.contains("SELECT COUNT(*), MAX(OCTET_LENGTH(CAST(`payload` AS CHAR CHARACTER SET utf8mb4)))"
                + " FROM `erp_base`.`orders` WHERE `time_stamp` IS NOT NULL"));
        assertTrue(f.targetSql.contains(StarRocksExternalFullImport.JSON_CONVERSION_PROBE_SQL));
        assertTrue(f.inserts.get(0).contains("JSON_QUERY(PARSE_JSON(CONCAT('[', `payload`, ']')), '$[0]')"));
        String projection = f.inserts.get(0).substring(f.inserts.get(0).indexOf(" SELECT ") + 8,
                f.inserts.get(0).indexOf(" FROM "));
        assertTrue(f.targetSql.stream().anyMatch(sql -> sql.startsWith("EXPLAIN") && sql.contains("SELECT " + projection)));
        assertTrue(f.targetSql.stream().anyMatch(sql -> sql.startsWith("SELECT " + projection + " FROM ")));
    }

    @Test public void jsonSourceCapacityAndNullSemanticFailuresHappenBeforeClear() throws Exception {
        Fixture f = new Fixture();
        f.jsonColumn = true;
        f.jsonBytes = 65534;
        assertFailure(f, "超出 Catalog/JSON 表达式容量");
        assertTrue(f.inserts.isEmpty());
        Fixture nullSemantics = new Fixture();
        nullSemantics.jsonColumn = true;
        nullSemantics.jsonProbe = new Object[]{"123", "true", null, null};
        try { nullSemantics.prepare(50, "YEAR"); fail("JSON null must remain JSON null"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("JSON null")); }
        assertTrue(nullSemantics.inserts.isEmpty());
        Fixture wrapperLimit = new Fixture();
        wrapperLimit.jsonColumn = true;
        wrapperLimit.catalogJsonType = "VARCHAR(2097152)";
        wrapperLimit.jsonBytes = 1048575;
        assertFailure(wrapperLimit, "1048574 字节");
    }

    @Test public void nativeCatalogJsonKeepsNativeProjection() throws Exception {
        Fixture f = new Fixture();
        f.jsonColumn = true;
        f.catalogJsonType = "JSON";
        try (StarRocksCatalogFullImport importer = f.prepare(50, "YEAR")) { importer.execute(); }
        assertFalse(f.inserts.get(0).contains("PARSE_JSON"));
        assertFalse(f.targetSql.contains(StarRocksExternalFullImport.JSON_CONVERSION_PROBE_SQL));
        assertFalse(f.sourceSql.stream().anyMatch(sql -> sql.contains("OCTET_LENGTH")));
    }

    @Test public void catalogHelperThroughFullSyncValidatesBeforeTruncateAndAdvancesCheckpoint() throws Exception {
        Fixture f = new Fixture();
        List<Object> checkpoints = new ArrayList<>();
        runFullSync(f, checkpoints);
        assertEquals(1, checkpoints.size());
        int clear = f.targetSql.indexOf("TRUNCATE TABLE `warehouse`.`orders`");
        assertTrue(clear > 0);
        assertTrue(f.targetSql.stream().limit(clear).anyMatch(sql -> sql.startsWith("EXPLAIN INSERT")));
        assertTrue(f.targetSql.indexOf(f.inserts.get(0)) > clear);
        assertFalse(f.targetSql.stream().anyMatch(sql -> sql.startsWith("DROP ") || sql.startsWith("CREATE ")));
        Fixture failure = new Fixture();
        failure.failExplain = true;
        checkpoints.clear();
        try { runFullSync(failure, checkpoints); fail("Preflight failure must propagate"); }
        catch (SQLException expected) { assertTrue(expected.getMessage().contains("尚未清空目标表")); }
        assertFalse(failure.targetSql.stream().anyMatch(sql -> sql.startsWith("TRUNCATE")));
        assertTrue(checkpoints.isEmpty());
    }

    private static Object runFullSync(Fixture f, List<Object> checkpoints) throws Exception {
        cn.easyreport.sync.mapper.ErDataTransferProgressMapper progress = proxy(
                cn.easyreport.sync.mapper.ErDataTransferProgressMapper.class, (name, args) -> {
                    if (name.equals("upsert")) { checkpoints.add(args[0]); return 1; }
                    throw new AssertionError(name);
                });
        StarRocksDataSyncExecutor executor = new StarRocksDataSyncExecutor(null, null, progress);
        ErDataTransfer task = new ErDataTransfer();
        task.setTransferId(42L);
        task.setSyncMode("0");
        task.setExternalTableEnabled("1");
        task.setCatalogName("src_mysql");
        Method method = DataSyncExecutor.class.getDeclaredMethod("syncFull", ErDataTransfer.class,
                ErDatasource.class, String.class, String.class, String.class, List.class, List.class,
                List.class, DataSyncExtractor.class, DataSyncLoader.class, Connection.class,
                Connection.class, int.class, String.class, boolean.class, boolean.class,
                cn.easyreport.sync.core.SyncProgressCallback.class);
        method.setAccessible(true);
        try {
            return method.invoke(executor, task, f.datasource, f.schema, "orders", "`warehouse`.`orders`",
                    f.columns(), f.columns(), Collections.singletonList("id"), new MySqlDataSyncExtractor(),
                    null, f.source(), f.target(), 1000, "time_stamp", false, false, null);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            throw failure;
        }
    }

    @Test public void stopBetweenWindowsDoesNotContinueOrCommitAnotherWindow() throws Exception {
        Fixture f = new Fixture();
        f.count = 500000;
        DataSyncCancellation cancellation = new DataSyncCancellation();
        try (StarRocksCatalogFullImport importer = f.prepare(50, "YEAR", cancellation,
                (current, total, message) -> { if (current > 0) cancellation.requestStop(); })) {
            try { importer.execute(); fail("Expected stop"); }
            catch (DataSyncCancellation.SyncStoppedException expected) { }
        } finally { cancellation.close(); }
        assertEquals(1, f.inserts.size());
        assertEquals(1, f.commits);
        assertFalse(f.targetSql.stream().anyMatch(sql -> sql.startsWith("DROP")));
    }

    @Test public void disabledAndIncrementalTasksWithCatalogDoNotTouchConnections() throws Exception {
        for (String[] config : new String[][]{{"0", "0"}, {"1", "1"}}) {
            ErDataTransfer task = new ErDataTransfer();
            task.setExternalTableEnabled(config[0]);
            task.setSyncMode(config[1]);
            task.setCatalogName("src_mysql");
            Connection unused = proxy(Connection.class, (method, args) -> { throw new AssertionError(method); });
            StarRocksDataSyncExecutor executor = new StarRocksDataSyncExecutor(null, null, null);
            assertNull(executor.prepareDirectFullImport(task, null, "erp_base", "orders", "`warehouse`.`orders`",
                    Arrays.asList("id", "time_stamp"), Arrays.asList("id", "time_stamp"), null, unused, unused, "time_stamp", null));
        }
    }

    @Test public void explicitCatalogFailureDoesNotFallBackToLegacyMysqlImport() throws Exception {
        Fixture f = new Fixture();
        f.catalogType = "hive";
        List<Object> checkpoints = new ArrayList<>();
        try {
            runFullSync(f, checkpoints);
            fail("Explicit Catalog failure must propagate");
        } catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("必须为 JDBC Catalog")); }
        assertTrue(f.sourceSql.isEmpty());
        assertTrue(f.inserts.isEmpty());
        assertTrue(checkpoints.isEmpty());
        assertEquals(Collections.singletonList("SHOW CREATE CATALOG `src_mysql`"), f.targetSql);
    }

    private static void assertFailure(Fixture fixture, String message) throws Exception {
        try { fixture.prepare(50, "YEAR"); fail("Expected " + message); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains(message)); }
    }

    private static final class Fixture {
        final ErDatasource datasource = new ErDatasource();
        final List<String> sourceSql = new ArrayList<>();
        final List<String> targetSql = new ArrayList<>();
        final List<String> inserts = new ArrayList<>();
        final String schema;
        String catalogUrl;
        String metadataUrl;
        String targetMetadataUrl;
        String catalogType = "jdbc";
        String catalogColumnType = "BIGINT";
        int timestampType = Types.TIMESTAMP;
        String timestampTypeName = "DATETIME";
        Timestamp min = Timestamp.valueOf("2023-12-31 23:59:59");
        Timestamp max = Timestamp.valueOf("2024-01-01 00:00:00");
        long count = 17;
        int commits;
        boolean failExplain;
        boolean failWindowProbe;
        boolean failClusterProbe;
        long sourceClusterId = 1234;
        long targetClusterId = 5678;
        boolean catalogSeesRows = true;
        String catalogUser = "erp_base";
        boolean jsonColumn;
        long jsonBytes = 128;
        String catalogJsonType = "VARCHAR(65533)";
        Object[] jsonProbe = {"123", "true", "null", null};
        String nativeIdType = "BIGINT";
        String nativeJsonType = "JSON";
        String catalogTimestampType = "DATETIME";
        String targetTimestampType = "DATETIME";
        String timestampField = "time_stamp";

        Fixture() { this("MYSQL", "jdbc:mysql://source:3306/erp_base", "erp_base"); }
        Fixture(String type, String url, String schema) {
            this.schema = schema;
            datasource.setDatasourceType(type);
            datasource.setDatabaseName("erp_base");
            datasource.setUsername("erp_base");
            datasource.setJdbcUrl(url);
            catalogUrl = url;
        }
        StarRocksCatalogFullImport prepare(Integer threshold, String unit) throws Exception {
            return prepare(threshold, unit, null, null);
        }
        StarRocksCatalogFullImport prepare(Integer threshold, String unit, DataSyncCancellation cancellation,
                cn.easyreport.sync.core.SyncProgressCallback callback) throws Exception {
            return StarRocksCatalogFullImport.prepare(datasource, source(), target(), "src_mysql", schema,
                    "orders", "`warehouse`.`orders`", columns(), columns(),
                    timestampField, threshold, unit, callback, cancellation);
        }
        List<String> columns() { return jsonColumn ? Arrays.asList("id", "time_stamp", "payload") : Arrays.asList("id", "time_stamp"); }
        Connection source() {
            return proxy(Connection.class, (method, args) -> {
                if (method.equals("getCatalog")) return "erp_base";
                if (method.equals("getMetaData")) return proxy(DatabaseMetaData.class, (name, values) -> {
                    if (name.equals("getURL")) return metadataUrl == null ? datasource.getJdbcUrl() : metadataUrl;
                    if (name.equals("getUserName")) return "erp_base";
                    return null;
                });
                if (method.equals("createStatement")) return proxy(Statement.class, (name, values) -> {
                    if (name.equals("executeQuery")) {
                        String sql = (String) values[0];
                        sourceSql.add(sql);
                        if (sql.equals("SHOW FRONTENDS")) {
                            if (failClusterProbe) throw new SQLException("secret-in-driver", "42000", 1227);
                            return rows(new Object[][]{{sourceClusterId}}, Types.BIGINT, "BIGINT");
                        }
                        if (sql.startsWith("DESCRIBE ")) {
                            List<Object[]> fields = new ArrayList<>();
                            fields.add(new Object[]{"id", nativeIdType});
                            fields.add(new Object[]{"time_stamp", timestampTypeName});
                            if (jsonColumn) fields.add(new Object[]{"payload", nativeJsonType});
                            return rows(fields.toArray(new Object[0][]), Types.VARCHAR, "VARCHAR");
                        }
                        if (sql.contains("WHERE 1=0")) {
                            if (sql.startsWith("SELECT \"id\", ")) return rows(new Object[0][],
                                    new int[]{Types.BIGINT, timestampType, Types.VARCHAR},
                                    new String[]{nativeIdType, timestampTypeName, nativeJsonType});
                            return rows(new Object[0][], timestampType, timestampTypeName);
                        }
                        if (sql.startsWith("SELECT COUNT")) return rows(new Object[][]{jsonColumn ? new Object[]{count, jsonBytes} : new Object[]{count}}, Types.BIGINT, "BIGINT");
                        if (sql.startsWith("SELECT MIN")) return rows(new Object[][]{{min, max}}, Types.TIMESTAMP, "DATETIME");
                        if (sql.equals("SELECT @@session.time_zone, @@global.time_zone, @@system_time_zone")) {
                            return rows(new Object[][]{{"SYSTEM", "SYSTEM", "Asia/Shanghai"}}, Types.VARCHAR, "VARCHAR");
                        }
                        throw new AssertionError(sql);
                    }
                    return null;
                });
                return null;
            });
        }
        Connection target() {
            return proxy(Connection.class, (method, args) -> {
                if (method.equals("getMetaData")) return proxy(DatabaseMetaData.class, (name, values) -> {
                    if (name.equals("getURL")) return targetMetadataUrl;
                    return null;
                });
                if (method.equals("getAutoCommit")) return false;
                if (method.equals("commit")) { commits++; return null; }
                if (method.equals("createStatement")) return proxy(Statement.class, (name, values) -> {
                    if (name.equals("execute") || name.equals("executeQuery") || name.equals("executeLargeUpdate")) {
                        String sql = (String) values[0];
                        targetSql.add(sql);
                        if (sql.equals("SHOW FRONTENDS")) return rows(new Object[][]{{targetClusterId}}, Types.BIGINT, "BIGINT");
                        if (name.equals("execute")) return true;
                        if (name.equals("executeLargeUpdate")) { inserts.add(sql); return 17L; }
                        if (sql.startsWith("SHOW CREATE")) return rows(new Object[][]{{"src_mysql", "CREATE EXTERNAL CATALOG src_mysql PROPERTIES (\"type\" = \"" + catalogType + "\", \"jdbc_uri\" = \"" + catalogUrl + "\", \"user\" = \"" + catalogUser + "\", \"password\" = \"secret-in-driver\")"}}, Types.VARCHAR, "VARCHAR");
                        if (sql.startsWith("DESCRIBE")) {
                            List<Object[]> fields = new ArrayList<>();
                            fields.add(new Object[]{"id", sql.contains("src_mysql") ? catalogColumnType : "BIGINT"});
                            fields.add(new Object[]{"time_stamp", sql.contains("src_mysql") ? catalogTimestampType : targetTimestampType});
                            if (jsonColumn) fields.add(new Object[]{"payload", sql.contains("src_mysql") ? catalogJsonType : "JSON"});
                            return rows(fields.toArray(new Object[0][]), Types.VARCHAR, "VARCHAR");
                        }
                        if (sql.contains("enable_insert_strict")) return rows(new Object[][]{{"enable_insert_strict", "true"}}, Types.VARCHAR, "VARCHAR");
                        if (sql.contains("query_timeout")) return rows(new Object[][]{{"query_timeout", "300"}}, Types.BIGINT, "BIGINT");
                        if (sql.startsWith("EXPLAIN")) {
                            if (failExplain) throw new SQLException("secret-in-driver", "HY000", 1064);
                            return rows(new Object[][]{{"plan"}}, Types.VARCHAR, "VARCHAR");
                        }
                        if (sql.equals(StarRocksExternalFullImport.JSON_CONVERSION_PROBE_SQL)) return rows(new Object[][]{jsonProbe}, Types.VARCHAR, "VARCHAR");
                        if (failWindowProbe && sql.startsWith("SELECT ") && sql.contains(" >= ")) throw new SQLException("NLS_DATE_FORMAT secret-in-driver", "HY000", 1861);
                        if (sql.startsWith("SELECT")) return rows(count == 0 || !catalogSeesRows ? new Object[0][]
                                : new Object[][]{jsonColumn ? new Object[]{1L, max, "{}"} : new Object[]{1L, max}}, Types.BIGINT, "BIGINT");
                        throw new AssertionError(sql);
                    }
                    return null;
                });
                return null;
            });
        }
    }

    private static ResultSet rows(Object[][] data, int type, String typeName) {
        return rows(data, new int[]{type}, new String[]{typeName});
    }

    private static ResultSet rows(Object[][] data, int[] types, String[] typeNames) {
        int[] index = {-1};
        boolean[] wasNull = {false};
        return proxy(ResultSet.class, (method, args) -> {
            if (method.equals("next")) return ++index[0] < data.length;
            if (method.equals("wasNull")) return wasNull[0];
            if (method.equals("getMetaData")) return proxy(ResultSetMetaData.class, (name, values) -> {
                int column = Math.min(((Integer) values[0]) - 1, types.length - 1);
                if (name.equals("getColumnType")) return types[column];
                if (name.equals("getColumnTypeName")) return typeNames[column];
                return null;
            });
            if (method.startsWith("get")) {
                int column = args[0] instanceof Integer ? (Integer) args[0] - 1 : 0;
                Object value = data[index[0]][column];
                wasNull[0] = value == null;
                if (method.equals("getString")) return value == null ? null : String.valueOf(value);
                if (method.equals("getLong")) return value == null ? 0L : Long.parseLong(String.valueOf(value));
                return value;
            }
            return null;
        });
    }

    private interface Handler { Object invoke(String method, Object[] args) throws Throwable; }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (object, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if (method.getName().equals("toString")) return type.getSimpleName() + "Fixture";
                if (method.getName().equals("hashCode")) return System.identityHashCode(object);
                if (method.getName().equals("equals")) return object == args[0];
            }
            Object result = handler.invoke(method.getName(), args);
            if (result != null || !method.getReturnType().isPrimitive()) return result;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == long.class) return 0L;
            if (method.getReturnType() == int.class) return 0;
            return null;
        });
    }
}
