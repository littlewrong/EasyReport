package cn.easyreport.sync.datasync;

import cn.easyreport.sync.domain.ErDatasource;
import org.junit.Test;

import java.lang.reflect.Proxy;
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
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class StarRocksJdbcResourceSourceTest {
    @Test
    public void suppliedKingbaseResourceShapeUsesQuotedPhysicalColumnsAndStableScanAliases() throws Exception {
        Fixture f = new Fixture("KINGBASE");
        f.datasource.setJdbcUrl("jdbc:kingbase8://source.test:54321/business?stringtype=unspecified&useSSL=false");
        f.resource.put("jdbc_uri", "jdbc:kingbase8://source.test:54321/business?useSSL=false");
        f.failRawProbe = true; // The old bare-original-name query must never be used for Kingbase.
        StarRocksJdbcResourceSource.Plan plan = f.prepare("time_stamp");
        assertTrue(plan.sourceTableProperty, plan.sourceTableProperty.startsWith("(SELECT "));
        assertTrue(plan.sourceTableProperty, plan.sourceTableProperty.contains("\"Id\" AS er_c0"));
        assertTrue(plan.sourceTableProperty, plan.sourceTableProperty.contains("FROM \"erp_base\".\"rf_user\""));
        assertTrue(plan.externalColumnsSql, plan.externalColumnsSql.contains("`er_c0` VARCHAR(1048576) NULL"));
        assertTrue(plan.externalColumnsSql.contains("`er_c2` DATETIME NULL"));
        assertEquals("`er_c0`, `er_c1`, `er_c2`", plan.selectColumnsSql);
        assertEquals("Id", plan.columns.get(0).name);
        assertEquals("er_c0", plan.columns.get(0).scanName);
        assertEquals("er_c2", plan.timestampColumn);
        assertFalse(f.queries.contains("SELECT Id, Name, time_stamp FROM erp_base.rf_user WHERE 1=0"));
        assertTrue(f.queries.contains("SELECT \"Id\", \"Name\", \"time_stamp\" FROM \"erp_base\".\"rf_user\" WHERE 1=0"));
        assertEquals(1L, f.queries.stream().filter(sql -> sql.endsWith("WHERE 1=0") && sql.contains(" AS er_c")).count());
        assertTrue(f.queries.toString(), f.queries.stream().anyMatch(sql -> sql.startsWith("SELECT COUNT(*)")
                && sql.endsWith("FROM \"erp_base\".\"rf_user\" WHERE \"time_stamp\" IS NOT NULL")));
        assertEquals(10L, plan.sourceRowCount);
        assertEquals(40L, plan.columns.get(0).maxTextBytes);
        assertEquals(f.maximum, plan.maxTs);
        assertEquals("2024-01-01", plan.minDay.toString());
        assertFalse(f.queries.toString(), f.queries.stream().anyMatch(sql -> sql.contains("`")));
    }

    @Test
    public void suppliedDmResourceNeedsNoForcedMysqlOrTimestampUrlFlagsForOrdinaryTimestamp() throws Exception {
        Fixture f = new Fixture("DM");
        f.datasource.setJdbcUrl("jdbc:dm://source.test:5236?convertToTimestamp=true");
        f.resource.put("jdbc_uri", "jdbc:dm://source.test:5236");
        f.columns.get(0).nullable = ResultSetMetaData.columnNoNulls;
        f.columns.get(1).nullable = ResultSetMetaData.columnNullableUnknown;
        StarRocksJdbcResourceSource.Plan plan = f.prepare("TIME_STAMP");
        assertEquals("er_c2", plan.timestampColumn);
        assertTrue(plan.externalColumnsSql.contains("`er_c0` VARCHAR(1048576) NULL"));
        assertTrue(plan.externalColumnsSql.contains("`er_c1` VARCHAR(1048576) NULL"));
        assertFalse(plan.externalColumnsSql.contains("NOT NULL"));
        assertTrue(plan.columns.get(2).timestampText);
        assertEquals("VARCHAR(26)", plan.columns.get(2).externalType);
        assertEquals(3, plan.columns.size());
    }

    @Test
    public void nativeDateNameUsesActualJdbcTypeAndJavaClassRatherThanOracleAlias() throws Exception {
        Fixture f = new Fixture("KINGBASE");
        f.columns.get(2).nativeType = "DATE";
        StarRocksJdbcResourceSource.Plan plan = f.prepare("time_stamp");
        assertEquals(Types.TIMESTAMP, plan.columns.get(2).jdbcType);
        assertEquals("DATETIME", plan.columns.get(2).externalType);
        f.columns.get(2).jdbcType = Types.DATE;
        f.columns.get(2).javaClass = "java.sql.Date";
        f.columns.get(2).scale = 0;
        assertEquals("DATE", f.prepare("time_stamp").columns.get(2).externalType);
    }

    @Test
    public void dmDatePreservesClockThroughServerTextWithoutChangingDriverUrlFlags() throws Exception {
        for (String javaClass : Arrays.asList("java.sql.Date", "java.sql.Timestamp", "dm.jdbc.driver.DmdbTimestamp")) {
            Fixture f = new Fixture("DM");
            Field date = f.columns.get(2);
            date.nativeType = "DATE"; date.jdbcType = Types.DATE; date.javaClass = javaClass; date.scale = 0;
            f.datasource.setJdbcUrl("jdbc:dm://source.test:5236?dateToTimestamp=false");
            f.resource.put("jdbc_uri", "jdbc:dm://source.test:5236?dateToTimestamp=true");
            f.minimum = Timestamp.valueOf("2024-01-01 13:14:15");
            f.maximum = Timestamp.valueOf("2026-11-11 23:59:59");
            StarRocksJdbcResourceSource.Plan plan = f.prepare("time_stamp");
            assertEquals("er_c2", plan.timestampColumn);
            assertTrue(plan.columns.get(2).timestampText);
            assertEquals("VARCHAR(26)", plan.columns.get(2).externalType);
            assertTrue(plan.sourceTableProperty, plan.sourceTableProperty.contains(
                    "TO_CHAR(CAST(\"time_stamp\" AS TIMESTAMP), 'YYYY-MM-DD HH24:MI:SS.FF6') END AS er_c2"));
            assertTrue(plan.sourceTableProperty, plan.sourceTableProperty.contains(
                    "CAST(\"time_stamp\" AS TIMESTAMP) < TIMESTAMP '0001-01-01 00:00:00'"));
            assertTrue(plan.sourceTableProperty, plan.sourceTableProperty.contains(
                    "CAST(\"time_stamp\" AS TIMESTAMP) > TIMESTAMP '9999-12-31 23:59:59.999999'"));
            assertTrue(plan.sourceTableProperty, plan.sourceTableProperty.contains("THEN 'invalid datetime' ELSE TO_CHAR("));
            assertEquals(f.minimum, plan.minTs);
            assertEquals(f.maximum, plan.maxTs);
            assertEquals("2026-11-11", plan.maxDay.toString());
            assertTrue(f.queries.toString(), f.queries.stream().anyMatch(sql -> sql.startsWith(
                    "SELECT MIN(CAST(\"time_stamp\" AS TIMESTAMP))")));
            assertEquals("dm.jdbc.driver.DmDriver", f.resource.get("driver_class"));
            assertTrue(f.datasource.getJdbcUrl().endsWith("dateToTimestamp=false"));
            assertTrue(f.resource.get("jdbc_uri").endsWith("dateToTimestamp=true"));
        }
    }

    @Test
    public void dmBinaryUsesExistingDriverAndVerifiesHighBytesEmptyAndSqlNullAsHex() throws Exception {
        for (String nativeType : Arrays.asList("BINARY", "BINARY(16)", "VARBINARY", "RAW")) {
            Fixture f = dmBinaryFixture(nativeType);
            Field binary = f.columns.get(0);
            binary.binaryValues = Arrays.asList(new byte[]{0, (byte) 0x80, (byte) 0xFF}, new byte[0], null);
            binary.binaryBytes = 3L;
            StarRocksJdbcResourceSource.Plan plan = f.prepare("time_stamp");
            assertTrue(plan.columns.get(0).binaryHex);
            assertEquals("VARCHAR(1048576)", plan.columns.get(0).externalType);
            assertEquals(6L, plan.columns.get(0).maxTextBytes);
            assertEquals("dm.jdbc.driver.DmDriver", f.resource.get("driver_class"));
            assertTrue(plan.sourceTableProperty, plan.sourceTableProperty.contains("UPPER(RAWTOHEX(\"Id\")) AS er_c0"));
            assertTrue(f.queries.toString(), f.queries.contains("SELECT \"Id\", UPPER(RAWTOHEX(\"Id\")) FROM \"erp_base\".\"rf_user\" WHERE \"time_stamp\" IS NOT NULL"));
        }
    }

    @Test
    public void dmBinaryRejectsHexTruncationAndChangedNullOrEmptySemantics() throws Exception {
        Object[][] invalid = {
                {new byte[]{0, (byte) 0xFF}, "00"}, {new byte[]{0, (byte) 0xFF}, "00FE"},
                {new byte[0], null}, {null, ""}
        };
        for (Object[] values : invalid) {
            Fixture f = dmBinaryFixture("VARBINARY");
            f.columns.get(0).binaryValues = Collections.singletonList((byte[]) values[0]);
            f.columns.get(0).binaryProjectionValues = Collections.singletonList((String) values[1]);
            f.reject("未完整保留原始字节");
        }
        Fixture excessive = dmBinaryFixture("BINARY");
        excessive.columns.get(0).binaryBytes = StarRocksJdbcResourceSource.MAX_TEXT_BYTES / 2 + 1;
        excessive.reject("1 MiB");
    }

    @Test
    public void dmClobReadsFullDriverStringWithoutBoundedCastAndAcceptsNullAndEmpty() throws Exception {
        String large = repeat("达梦🙂A", 20_000);
        for (String nativeType : Arrays.asList("CLOB", "NCLOB", "TEXT")) {
            Fixture f = dmClobFixture(nativeType);
            Field clob = f.columns.get(0);
            clob.clobValues = Arrays.asList(large, "", null);
            clob.textBytes = (long) large.length() * 4;
            StarRocksJdbcResourceSource.Plan plan = f.prepare(null);
            assertEquals(large.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, plan.columns.get(0).maxTextBytes);
            assertTrue(plan.sourceTableProperty, plan.sourceTableProperty.contains("\"Id\" AS er_c0"));
            assertFalse(plan.sourceTableProperty.contains("CAST(\"Id\" AS VARCHAR"));
            assertTrue(f.queries.toString(), f.queries.contains("SELECT \"Id\" FROM \"erp_base\".\"rf_user\""));
            assertEquals("VARCHAR(1048576)", plan.columns.get(0).externalType);
        }
    }

    @Test
    public void dmClobRejectsTruncatedStringsChangedNullSemanticsAndActualUtf8Overflow() throws Exception {
        Object[][] invalid = {
                {repeat("A", 40_000), repeat("A", 32_767), "截断"},
                {"完整字符流", "不一致字符流", "不一致"},
                {null, "", "未保留 NULL"}, {"", null, "未保留 NULL"}
        };
        for (Object[] values : invalid) {
            Fixture f = dmClobFixture("CLOB");
            f.columns.get(0).clobValues = Collections.singletonList((String) values[0]);
            f.columns.get(0).clobStringValues = Collections.singletonList((String) values[1]);
            f.reject((String) values[2]);
        }
        Fixture excessive = dmClobFixture("CLOB");
        excessive.columns.get(0).clobValues = Collections.singletonList(repeat("🙂", 262_145));
        excessive.reject("UTF-8 字节数");
    }

    @Test
    public void dmConvertedDateAndBinaryMustActuallyReturnScannerReadableStringMetadata() throws Exception {
        for (boolean timestamp : new boolean[]{false, true}) {
            for (boolean badClass : new boolean[]{false, true}) {
                Fixture f = timestamp ? new Fixture("DM") : dmBinaryFixture("BINARY");
                Field field = f.columns.get(timestamp ? 2 : 0);
                if (timestamp) { field.nativeType = "DATE"; field.jdbcType = Types.DATE; field.javaClass = "java.sql.Date"; field.scale = 0; }
                if (badClass) field.projectedJavaClass = "vendor.Unreadable"; else field.projectedJdbcType = Types.OTHER;
                f.reject("转为文本后未以 String 返回");
                assertFalse(f.queries.stream().anyMatch(sql -> sql.startsWith("SELECT COUNT(*)")));
            }
        }
    }

    private static Fixture dmBinaryFixture(String nativeType) {
        Fixture f = new Fixture("DM");
        Field binary = f.columns.get(0);
        binary.nativeType = nativeType; binary.jdbcType = Types.VARBINARY; binary.javaClass = "[B";
        return f;
    }

    private static Fixture dmClobFixture(String nativeType) {
        Fixture f = new Fixture("DM");
        Field clob = f.columns.get(0);
        clob.nativeType = nativeType; clob.jdbcType = "NCLOB".equals(nativeType) ? Types.NCLOB : Types.CLOB;
        clob.javaClass = "NCLOB".equals(nativeType) ? "dm.jdbc.driver.DmdbNClob" : "dm.jdbc.driver.DmdbClob";
        return f;
    }

    @Test
    public void unknownLobJsonComplexTimezoneAndUnboundedNumericCannotBecomeUnverifiedText() throws Exception {
        Object[][] unsupported = {
                {Types.CLOB, "CLOB", "dm.jdbc.driver.DmdbClob", 100, 0},
                {Types.SQLXML, "XML", "java.sql.SQLXML", 100, 0},
                {Types.ARRAY, "INTEGER[]", "java.sql.Array", 100, 0},
                {Types.TIMESTAMP_WITH_TIMEZONE, "TIMESTAMP WITH TIME ZONE", "java.sql.Timestamp", 29, 6},
                {Types.NUMERIC, "NUMERIC", "java.math.BigDecimal", 0, 0},
                {Types.NUMERIC, "NUMBER", "java.math.BigDecimal", 40, 0},
                {Types.TIMESTAMP, "TIMESTAMP", "java.time.LocalDateTime", 29, 6},
                {Types.TIMESTAMP, "TIMESTAMP", "dm.jdbc.driver.DmdbTimestamp", 29, 6}
        };
        for (Object[] item : unsupported) {
            Fixture f = new Fixture("KINGBASE");
            Field field = f.columns.get(0);
            field.jdbcType = (Integer) item[0]; field.nativeType = (String) item[1];
            field.javaClass = (String) item[2]; field.precision = (Integer) item[3]; field.scale = (Integer) item[4];
            f.reject("尚未清空目标表");
            assertFalse(f.queries.stream().anyMatch(sql -> sql.startsWith("SELECT COUNT(*)")));
        }
    }

    @Test
    public void ordinaryScalarExternalTypesFollowScannerJavaClassContract() throws Exception {
        Object[][] supported = {
                {Types.TINYINT, "TINYINT", "java.lang.Byte", 3, 0, "VARCHAR(4)"},
                {Types.BOOLEAN, "BOOLEAN", "java.lang.Boolean", 1, 0, "BOOLEAN"},
                {Types.SMALLINT, "SMALLINT", "java.lang.Short", 5, 0, "SMALLINT"},
                {Types.INTEGER, "INTEGER", "java.lang.Integer", 10, 0, "INT"},
                {Types.BIGINT, "BIGINT", "java.lang.Long", 19, 0, "BIGINT"},
                {Types.NUMERIC, "NUMERIC", "java.math.BigDecimal", 38, 9, "DECIMAL(38,9)"},
                {Types.REAL, "REAL", "java.lang.Float", 8, 0, "FLOAT"},
                {Types.DOUBLE, "DOUBLE", "java.lang.Double", 16, 0, "DOUBLE"}
        };
        for (Object[] item : supported) {
            Fixture f = new Fixture("KINGBASE");
            Field field = f.columns.get(0);
            field.jdbcType = (Integer) item[0]; field.nativeType = (String) item[1];
            field.javaClass = (String) item[2]; field.precision = (Integer) item[3]; field.scale = (Integer) item[4];
            assertEquals(item[5], f.prepare(null).columns.get(0).externalType);
        }
    }

    @Test
    public void dmQuotedProjectionAvoidsReservedWordsAndRejectsPhysicalAndAliasSqlFailures() throws Exception {
        Fixture reserved = new Fixture("DM");
        reserved.columns.get(0).name = "level";
        reserved.columns.get(1).name = "order";
        reserved.table = "order";
        reserved.failRawProbe = true;
        StarRocksJdbcResourceSource.Plan plan = reserved.prepare("time_stamp");
        assertTrue(plan.sourceTableProperty, plan.sourceTableProperty.contains("\"level\" AS er_c0"));
        assertTrue(plan.sourceTableProperty, plan.sourceTableProperty.contains("FROM \"erp_base\".\"order\""));
        assertEquals("er_c2", plan.timestampColumn);
        for (boolean alias : new boolean[]{false, true}) {
            Fixture failure = new Fixture("DM");
            SQLException syntax = new SQLException("private-driver-secret", "42000", 919);
            if (alias) failure.aliasProbeFailure = syntax; else failure.physicalProbeFailure = syntax;
            try { failure.prepare(null); fail("Expected a physical/projection SQL error"); }
            catch (SQLException expected) {
                assertFalse(expected.getMessage().contains("private-driver-secret"));
                assertNull(expected.getCause());
                assertEquals("42000", expected.getSQLState());
            }
            assertFalse(failure.queries.stream().anyMatch(sql -> sql.startsWith("SELECT COUNT(*)")));
        }
        Fixture different = new Fixture("DM");
        different.wrongPhysicalName = true;
        different.reject("物理列名与配置不一致");
        assertFalse(different.queries.stream().anyMatch(sql -> sql.startsWith("SELECT COUNT(*)")));
    }

    @Test
    public void kingbaseReservedWordsAndMixedCaseKeepLogicalNamesButScanSafeAliases() throws Exception {
        Fixture f = new Fixture("KINGBASE");
        f.columns.get(0).name = "level";
        f.columns.get(1).name = "order";
        f.table = "order";
        f.failRawProbe = true;
        StarRocksJdbcResourceSource.Plan plan = f.prepare("TIME_STAMP");
        assertEquals("level", plan.columns.get(0).name);
        assertEquals("order", plan.columns.get(1).name);
        assertEquals("er_c0", plan.columns.get(0).scanName);
        assertEquals("er_c1", plan.columns.get(1).scanName);
        assertEquals("er_c2", plan.timestampColumn);
        assertTrue(plan.sourceTableProperty, plan.sourceTableProperty.contains("\"level\" AS er_c0"));
        assertTrue(plan.sourceTableProperty, plan.sourceTableProperty.contains("\"order\" AS er_c1"));
        assertTrue(plan.sourceTableProperty, plan.sourceTableProperty.contains("FROM \"erp_base\".\"order\""));
        assertTrue(f.queries.toString(), f.queries.stream().anyMatch(sql -> sql.startsWith("SELECT MIN(\"time_stamp\")")
                && sql.endsWith("FROM \"erp_base\".\"order\" WHERE \"time_stamp\" IS NOT NULL")));
    }

    @Test
    public void kingbaseUnquotedAliasLabelsMayFoldToUppercaseWithoutChangingScanNames() throws Exception {
        Fixture f = new Fixture("KINGBASE");
        f.uppercaseAliasLabels = true;
        StarRocksJdbcResourceSource.Plan plan = f.prepare("time_stamp");
        assertEquals("er_c0", plan.columns.get(0).scanName);
        assertEquals("er_c2", plan.timestampColumn);
        assertEquals("`er_c0`, `er_c1`, `er_c2`", plan.selectColumnsSql);
    }

    @Test
    public void kingbaseJsonAndJsonbUseExistingDriverThroughAStringProjection() throws Exception {
        for (String nativeType : new String[]{"JSON", "JSONB"}) {
            for (String javaClass : new String[]{"com.kingbase8.util.KBobject", "java.lang.String", "java.lang.Object"}) {
                Fixture f = new Fixture("KINGBASE");
                Field json = f.columns.get(0);
                json.nativeType = nativeType; json.jdbcType = Types.OTHER; json.javaClass = javaClass;
                StarRocksJdbcResourceSource.Plan plan = f.prepare("time_stamp");
                StarRocksJdbcResourceSource.Column column = plan.columns.get(0);
                assertTrue(nativeType + "/" + javaClass, column.json);
                assertEquals("Id", column.name);
                assertEquals("er_c0", column.scanName);
                assertEquals("VARCHAR(1048576)", column.externalType);
                assertEquals(40L, column.maxTextBytes);
                assertTrue(plan.sourceTableProperty, plan.sourceTableProperty.contains("CAST(\"Id\" AS TEXT) AS er_c0"));
                assertEquals("com.kingbase8.Driver", f.resource.get("driver_class"));
                assertTrue(f.queries.toString(), f.queries.stream().anyMatch(sql -> sql.startsWith("SELECT COUNT(*)")
                        && sql.contains("CAST(\"Id\" AS TEXT)") && sql.endsWith("WHERE \"time_stamp\" IS NOT NULL")));
            }
        }
    }

    @Test
    public void kingbaseTimeUsesTheExistingDriverAndCastsAllSupportedPrecisionsToTextAliases() throws Exception {
        Object[][] types = {{"TIME", 6}, {"TIME(0)", 0}, {"TIME(3)", 3}, {"TIME(6)", 6},
                {"TIME WITHOUT TIME ZONE", 6}, {"TIME(6) WITHOUT TIME ZONE", 6}};
        for (Object[] type : types) {
            for (String javaClass : Arrays.asList("java.sql.Time", "java.lang.String", "java.time.LocalTime")) {
                Fixture f = kingbaseTime((String) type[0], javaClass, (Integer) type[1]);
                StarRocksJdbcResourceSource.Plan plan = f.prepare("time_stamp");
                StarRocksJdbcResourceSource.Column column = plan.columns.get(0);
                assertTrue(column.timeText);
                assertFalse(column.json);
                assertFalse(column.binaryHex);
                assertFalse(column.mysqlTimestamp);
                assertEquals("col_time", column.name);
                assertEquals("er_c0", column.scanName);
                assertEquals("er_c2", plan.timestampColumn);
                assertEquals("VARCHAR(1048576)", column.externalType);
                assertEquals("com.kingbase8.Driver", f.resource.get("driver_class"));
                assertTrue(plan.sourceTableProperty, plan.sourceTableProperty.contains("CAST(\"col_time\" AS TEXT) AS er_c0"));
                assertTrue(plan.externalColumnsSql, plan.externalColumnsSql.contains("`er_c0` VARCHAR(1048576) NULL"));
                assertTrue(f.queries.toString(), f.queries.stream().anyMatch(sql -> sql.startsWith("SELECT COUNT(*)")
                        && sql.contains("MAX(LENGTH(CAST(\"col_time\" AS TEXT)) * 4)")
                        && sql.endsWith("WHERE \"time_stamp\" IS NOT NULL")));
            }
        }
    }

    @Test
    public void kingbaseTimeStatisticsMeasureServerCastTextWithoutJavaDateOrTimeNormalization() throws Exception {
        // The source adapter reads SQL aggregates, not native TIME objects. These samples model
        // server CAST output, including PostgreSQL/Oracle-style 24:00 and MySQL-mode durations.
        for (String text : Arrays.asList("00:00:00", "24:00:00", "23:59:59.123456", "-00:30:00.000001",
                "100:01:02.123456", "-838:59:59.000000", "838:59:59.000000", null)) {
            Fixture f = kingbaseTime("TIME(6)", "java.sql.Time", 6);
            f.columns.get(0).timeTextValues = Collections.singletonList(text);
            f.sourceCount = 1;
            f.uppercaseAliasLabels = true;
            StarRocksJdbcResourceSource.Plan plan = f.prepare(null);
            assertEquals(text == null ? 0L : text.length() * 4L, plan.columns.get(0).maxTextBytes);
            assertEquals("er_c0", plan.columns.get(0).scanName);
            assertTrue(plan.sourceTableProperty.contains("CAST(\"col_time\" AS TEXT) AS er_c0"));
            assertFalse("TIME must not be used to derive timestamp bounds", f.queries.stream().anyMatch(sql -> sql.startsWith("SELECT MIN(")));
            assertEquals("Small TIME strings need no source value fetch or Java temporal conversion", 0, f.candidateReads);
        }
    }

    @Test
    public void kingbaseTimeRejectsTimezoneUnknownJdbcClassesAndUnsupportedPrecisionBeforeStatistics() throws Exception {
        Object[][] unsupported = {{"TIME(7)", Types.TIME, "java.sql.Time", 7},
                {"TIME", Types.TIME, "java.sql.Time", -1}, {"TIME", Types.TIME, "java.sql.Time", 7},
                {"TIMETZ", Types.TIME, "java.sql.Time", 6}, {"TIME WITH TIME ZONE", Types.TIME, "java.sql.Time", 6},
                {"TIME(6) WITH TIME ZONE", Types.TIME, "java.sql.Time", 6},
                {"TIME WITHOUT TIME ZONE", Types.TIME_WITH_TIMEZONE, "java.sql.Time", 6},
                {"TIME", Types.TIMESTAMP, "java.sql.Time", 6},
                {"TIME", Types.TIME, "java.sql.Timestamp", 6}, {"TIME", Types.TIME, "vendor.UnknownTime", 6}};
        for (Object[] type : unsupported) {
            Fixture f = kingbaseTime((String) type[0], (String) type[2], (Integer) type[3]);
            f.columns.get(0).jdbcType = (Integer) type[1];
            f.reject("尚未清空目标表");
            assertFalse(f.queries.stream().anyMatch(sql -> sql.startsWith("SELECT COUNT(*)")));
        }
    }

    @Test
    public void kingbaseTimeProjectionMustActuallyReturnStringCharacterMetadata() throws Exception {
        Fixture wrongClass = kingbaseTime("TIME(6)", "java.sql.Time", 6);
        wrongClass.columns.get(0).projectedJavaClass = "java.sql.Time";
        wrongClass.reject("转为文本后未以 String 返回");
        Fixture wrongJdbc = kingbaseTime("TIME(6)", "java.sql.Time", 6);
        wrongJdbc.columns.get(0).projectedJdbcType = Types.TIME;
        wrongJdbc.reject("转为文本后未以 String 返回");
        assertFalse(wrongClass.queries.stream().anyMatch(sql -> sql.startsWith("SELECT COUNT(*)")));
        assertFalse(wrongJdbc.queries.stream().anyMatch(sql -> sql.startsWith("SELECT COUNT(*)")));
    }

    @Test
    public void kingbaseTimeCannotBeConfiguredAsTheInitializationTimestampField() throws Exception {
        Fixture f = kingbaseTime("TIME(6)", "java.sql.Time", 6);
        try { f.prepare("col_time"); fail("A TIME value has no calendar date for initialization windows"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("时间戳字段")); }
        assertFalse(f.queries.stream().anyMatch(sql -> sql.startsWith("SELECT COUNT(*)")));
        assertFalse(f.queries.stream().anyMatch(sql -> sql.startsWith("SELECT MIN(")));
    }

    private static Fixture kingbaseTime(String nativeType, String javaClass, int scale) {
        Fixture f = new Fixture("KINGBASE");
        Field time = f.columns.get(0);
        time.name = "col_time"; time.nativeType = nativeType; time.jdbcType = Types.TIME;
        time.javaClass = javaClass; time.precision = 17; time.scale = scale;
        time.projectedTimeText = true;
        return f;
    }

    @Test
    public void kingbaseByteaHexCapacityPreservesHighBytesEmptyAndSqlNullWithoutANewDriver() throws Exception {
        byte[] highBytes = new byte[]{0, (byte) 0x80, (byte) 0xFF};
        for (Long rawBytes : Arrays.asList((long) highBytes.length, 0L, null)) {
            Fixture f = kingbaseBytea();
            f.columns.get(0).binaryBytes = rawBytes;
            StarRocksJdbcResourceSource.Plan plan = f.prepare(null);
            assertTrue(plan.columns.get(0).binaryHex);
            assertFalse(plan.columns.get(0).json);
            assertEquals(rawBytes == null ? 0L : rawBytes * 2 * 4, plan.columns.get(0).maxTextBytes);
            assertEquals("VARCHAR(1048576)", plan.columns.get(0).externalType);
            assertTrue(plan.sourceTableProperty, plan.sourceTableProperty.contains("UPPER(ENCODE(\"Id\", 'hex')) AS er_c0"));
            assertEquals("com.kingbase8.Driver", f.resource.get("driver_class"));
            assertTrue(f.queries.toString(), f.queries.stream().anyMatch(sql -> sql.startsWith("SELECT COUNT(*)")
                    && sql.contains("UPPER(ENCODE(\"Id\", 'hex'))")));
        }
        Fixture exact = kingbaseBytea();
        exact.columns.get(0).binaryBytes = StarRocksJdbcResourceSource.MAX_TEXT_BYTES / 2;
        exact.candidates = Collections.singletonList(repeat("FF", (int) StarRocksJdbcResourceSource.MAX_TEXT_BYTES / 2));
        assertEquals(StarRocksJdbcResourceSource.MAX_TEXT_BYTES, exact.prepare(null).columns.get(0).maxTextBytes);
        assertTrue(exact.queries.toString(), exact.queries.stream().anyMatch(sql -> sql.startsWith("SELECT UPPER(ENCODE(")
                && sql.contains("LENGTH(UPPER(ENCODE(")));
        Fixture excessive = kingbaseBytea();
        excessive.columns.get(0).binaryBytes = StarRocksJdbcResourceSource.MAX_TEXT_BYTES / 2 + 1;
        excessive.reject("1 MiB");
    }

    @Test
    public void kingbaseConvertedTextStreamsTheConvertedExpressionAndRejectsUtf8Overflow() throws Exception {
        Fixture f = new Fixture("KINGBASE");
        Field json = f.columns.get(0);
        json.nativeType = "JSONB"; json.jdbcType = Types.OTHER; json.javaClass = "com.kingbase8.util.KBobject";
        json.textBytes = 1_200_000L;
        f.candidates = Collections.singletonList(repeat("🙂", 300_000));
        f.reject("实际 UTF-8 字节数");
        assertTrue(f.queries.toString(), f.queries.stream().anyMatch(sql -> sql.startsWith("SELECT CAST(\"Id\" AS TEXT)")
                && sql.contains("LENGTH(CAST(\"Id\" AS TEXT))")));
    }

    @Test
    public void kingbaseByteaProjectionCannotDropNonNullEmptyBytesOrChangeEncodedLength() throws Exception {
        Fixture invalid = kingbaseBytea();
        invalid.columns.get(0).binaryBytes = 0L;
        invalid.binaryInvalidProjectionCount = 1L;
        invalid.reject("尚未清空目标表");
        assertTrue(invalid.queries.toString(), invalid.queries.stream().anyMatch(sql -> sql.startsWith("SELECT COUNT(*)")
                && sql.contains("SUM(CASE WHEN") && sql.contains("OCTET_LENGTH(\"Id\")")));
        Fixture nullAggregate = kingbaseBytea();
        nullAggregate.columns.get(0).binaryBytes = null;
        nullAggregate.binaryInvalidProjectionCount = null;
        assertEquals(0L, nullAggregate.prepare(null).columns.get(0).maxTextBytes);
    }

    @Test
    public void kingbaseProjectionMustReturnStringCharacterMetadataAndPreserveScalarPrecision() throws Exception {
        Fixture wrongClass = kingbaseBytea();
        wrongClass.columns.get(0).projectedJavaClass = "[B";
        wrongClass.reject("转为文本后未以 String 返回");
        Fixture wrongJdbc = kingbaseBytea();
        wrongJdbc.columns.get(0).projectedJdbcType = Types.OTHER;
        wrongJdbc.reject("转为文本后未以 String 返回");
        Fixture jsonObject = new Fixture("KINGBASE");
        jsonObject.columns.get(0).nativeType = "JSONB";
        jsonObject.columns.get(0).jdbcType = Types.OTHER;
        jsonObject.columns.get(0).javaClass = "com.kingbase8.util.KBobject";
        jsonObject.columns.get(0).projectedJavaClass = "com.kingbase8.util.KBobject";
        jsonObject.reject("转为文本后未以 String 返回");
        for (String changed : new String[]{"jdbc", "class", "precision", "scale"}) {
            Fixture scalar = new Fixture("KINGBASE");
            if ("jdbc".equals(changed)) scalar.columns.get(0).projectedJdbcType = Types.INTEGER;
            if ("class".equals(changed)) scalar.columns.get(0).projectedJavaClass = "java.lang.Integer";
            if ("precision".equals(changed)) scalar.columns.get(0).projectedPrecision = 35;
            if ("scale".equals(changed)) scalar.columns.get(0).projectedScale = 1;
            scalar.reject("投影改变了源标量类型或精度");
            assertFalse(scalar.queries.stream().anyMatch(sql -> sql.startsWith("SELECT COUNT(*)")));
        }
        Fixture wrongCount = new Fixture("KINGBASE");
        wrongCount.aliasColumnCountDelta = 1;
        wrongCount.reject("投影字段数量");
        Fixture wrongAlias = new Fixture("KINGBASE");
        wrongAlias.columns.get(0).projectedName = "Id";
        wrongAlias.reject("安全别名");
        Fixture wrongPhysical = new Fixture("KINGBASE");
        wrongPhysical.wrongPhysicalName = true;
        wrongPhysical.reject("物理列名与配置不一致");
    }

    @Test
    public void kingbasePhysicalAndAliasSqlState42601ErrorsStaySanitizedBeforeStatistics() throws Exception {
        for (boolean alias : new boolean[]{false, true}) {
            Fixture f = new Fixture("KINGBASE");
            SQLException syntax = new SQLException("private-driver-secret syntax near user-value", "42601", 919);
            if (alias) f.aliasProbeFailure = syntax; else f.physicalProbeFailure = syntax;
            try { f.prepare(null); fail("Expected a source/projection syntax failure"); }
            catch (SQLException expected) {
                assertEquals("42601", expected.getSQLState());
                assertFalse(expected.getMessage().contains("private-driver-secret"));
                assertFalse(expected.getMessage().contains("user-value"));
                assertNull(expected.getCause());
            }
            assertFalse(f.queries.stream().anyMatch(sql -> sql.startsWith("SELECT COUNT(*)")));
        }
    }

    private static Fixture kingbaseBytea() {
        Fixture f = new Fixture("KINGBASE");
        Field binary = f.columns.get(0);
        binary.nativeType = "BYTEA"; binary.jdbcType = Types.VARBINARY; binary.javaClass = "[B";
        return f;
    }

    @Test
    public void resourceBindingStillRejectsWrongUserDriverSemanticSettingsAndFakeProtocol() throws Exception {
        for (String mismatch : Arrays.asList("user", "driver", "session", "fakeMysql")) {
            Fixture f = new Fixture("KINGBASE");
            if ("user".equals(mismatch)) f.resource.put("user", "other_user");
            if ("driver".equals(mismatch)) f.resource.put("driver_class", "org.postgresql.Driver");
            if ("session".equals(mismatch)) f.resource.put("jdbc_uri", f.datasource.getJdbcUrl() + "?options=-c%20TimeZone%3DUTC");
            if ("fakeMysql".equals(mismatch)) f.resource.put("jdbc_uri", "jdbc:mysql://source.test:54321/business");
            f.reject("尚未清空目标表");
            assertTrue(f.queries.isEmpty());
        }
    }

    @Test
    public void allEightSourcesAllowResourceAndLiveConnectionAddressAliasesAndResourceDatabaseDifferences() throws Exception {
        for (String type : Arrays.asList("MYSQL", "TIDB", "STARROCKS", "KINGBASE", "DM", "POSTGRESQL", "ORACLE", "SQLSERVER")) {
            Fixture f = new Fixture(type);
            String resourceUrl;
            String actualUrl;
            if ("ORACLE".equals(type)) {
                resourceUrl = "jdbc:oracle:thin:@//resource-gateway.test:31521/resource_service";
                actualUrl = "jdbc:oracle:thin:@source-internal.test:21521:business";
            } else if ("SQLSERVER".equals(type)) {
                resourceUrl = "jdbc:sqlserver://resource-gateway.test:31433;databaseName=resource_business";
                actualUrl = "jdbc:sqlserver://source-internal.test:21433;databaseName=business";
            } else {
                String protocol = "KINGBASE".equals(type) ? "kingbase8" : "DM".equals(type) ? "dm"
                        : "POSTGRESQL".equals(type) ? "postgresql" : "mysql";
                resourceUrl = "jdbc:" + protocol + "://resource-gateway.test:30001/resource_business";
                actualUrl = "jdbc:" + protocol + "://source-internal.test:20001"
                        + ("DM".equals(type) ? "" : "/business");
            }
            f.resource.put("jdbc_uri", resourceUrl);
            f.actualUrl = actualUrl;
            StarRocksJdbcResourceSource.Plan plan = f.prepare(null);
            assertEquals(type, 10L, plan.sourceRowCount);
            if ("KINGBASE".equals(type) || "DM".equals(type)) {
                assertTrue(plan.sourceTableProperty, plan.sourceTableProperty.contains("FROM \"erp_base\".\"rf_user\""));
                assertTrue(plan.sourceTableProperty, plan.sourceTableProperty.contains("\"Id\" AS er_c0"));
                continue;
            }
            assertEquals(type, "SQLSERVER".equals(type) ? "[business].[dbo].[rf_user]"
                    : "ORACLE".equals(type) ? "\"ERP_BASE\".\"rf_user\""
                    : "POSTGRESQL".equals(type) ? "\"erp_base\".\"rf_user\""
                    : Arrays.asList("MYSQL", "TIDB", "STARROCKS").contains(type) ? "erp_base`.`rf_user" : "erp_base.rf_user",
                    plan.sourceTableProperty);
        }
    }

    @Test
    public void resourceTimestampChecksIgnoreRoutingParametersButKeepTimeSettings() throws Exception {
        Fixture f = new Fixture("MYSQL");
        f.columns.get(2).nativeType = "TIMESTAMP";
        f.datasource.setJdbcUrl("jdbc:mysql://source.test:3306/business?host=source.test&port=3306"
                + "&database=business&socksProxyHost=source-proxy.test&serverTimezone=GMT%2B8");
        f.actualUrl = "jdbc:mysql://internal.test:13306/business?host=internal.test&port=13306"
                + "&database=actual_business&socksProxyHost=internal-proxy.test&serverTimezone=GMT%2B8";
        f.resource.put("jdbc_uri", "jdbc:mysql://external.test:23306/resource_business?host=external.test&port=23306"
                + "&database=resource_business&socksProxyHost=resource-proxy.test&serverTimezone=GMT%2B8");
        assertEquals(10L, f.prepare("time_stamp").sourceRowCount);
    }

    @Test
    public void resourceAddressSyntaxIsLeftToTheDriverAndActualScan() throws Exception {
        String[][] resources = {
                {"ORACLE", "jdbc:oracle:thin:@RESOURCE_ALIAS"},
                {"SQLSERVER", "jdbc:sqlserver://gateway.test\\ERP;databaseName=resource_business"},
                {"POSTGRESQL", "jdbc:postgresql://gateway-a.test:15432,gateway-b.test:25432/resource_business"}
        };
        for (String[] resource : resources) {
            Fixture f = new Fixture(resource[0]);
            f.resource.put("jdbc_uri", resource[1]);
            assertEquals(resource[0], 10L, f.prepare(null).sourceRowCount);
        }
    }

    @Test
    public void unsafeIdentifierFragmentsAreNotEmbeddedInRawSourceOrRemoteSql() throws Exception {
        Fixture f = new Fixture("KINGBASE");
        f.columns.get(0).name = "Id); DROP TABLE sensitive --";
        f.reject("普通列名");
        assertTrue(f.queries.isEmpty());
    }

    @Test
    public void textCapacityAndNullAggregatesUseExplicitUtf8ConservativeBound() throws Exception {
        Fixture f = new Fixture("DM");
        f.columns.get(0).textBytes = StarRocksJdbcResourceSource.MAX_TEXT_BYTES;
        assertEquals(StarRocksJdbcResourceSource.MAX_TEXT_BYTES, f.prepare(null).columns.get(0).maxTextBytes);
        f.columns.get(0).textBytes = StarRocksJdbcResourceSource.MAX_TEXT_BYTES * 4 + 4;
        f.reject("最大字符数");
        f.columns.get(0).textBytes = null;
        assertEquals(0L, f.prepare(null).columns.get(0).maxTextBytes);
    }

    @Test
    public void ambiguousTextUpperBoundStreamsOnlyCandidatesAndAcceptsLargeAscii() throws Exception {
        Fixture f = new Fixture("DM");
        f.columns.get(0).textBytes = 1_200_000L;
        f.candidates = Collections.singletonList(repeat("A", 300_000));
        assertEquals(StarRocksJdbcResourceSource.MAX_TEXT_BYTES, f.prepare("time_stamp").columns.get(0).maxTextBytes);
        assertTrue(f.queries.toString(), f.queries.contains("SELECT \"Id\" FROM \"erp_base\".\"rf_user\" WHERE \"time_stamp\" IS NOT NULL AND LENGTH(\"Id\") > 262144"));
        assertEquals(128, f.candidateFetchSize);
        Fixture boundary = new Fixture("KINGBASE");
        boundary.columns.get(0).textBytes = StarRocksJdbcResourceSource.MAX_TEXT_BYTES * 4;
        boundary.candidates = Collections.singletonList(repeat("A", (int) StarRocksJdbcResourceSource.MAX_TEXT_BYTES));
        assertEquals(StarRocksJdbcResourceSource.MAX_TEXT_BYTES, boundary.prepare(null).columns.get(0).maxTextBytes);
    }

    @Test
    public void ambiguousFourByteTextIsRejectedAndStreamingCanBeCancelled() throws Exception {
        Fixture excessive = new Fixture("KINGBASE");
        excessive.columns.get(0).textBytes = 1_200_000L;
        excessive.candidates = Collections.singletonList(repeat("🙂", 300_000));
        excessive.reject("实际 UTF-8 字节数");
        Fixture cancelled = new Fixture("DM");
        cancelled.columns.get(0).textBytes = 1_200_000L;
        cancelled.candidates = Arrays.asList(repeat("A", 300_000), repeat("B", 300_000));
        cancelled.cancelCandidate = true;
        try {
            cancelled.prepare(null);
            fail("Source text validation must observe cancellation before import preparation succeeds");
        } catch (DataSyncCancellation.SyncStoppedException expected) {
            assertEquals(1, cancelled.candidateReads);
        }
    }

    @Test
    public void mysqlFamilyUsesActualResourceProtocolAndEscapedDatabaseTableParts() throws Exception {
        for (String type : Arrays.asList("MYSQL", "TIDB", "STARROCKS")) {
            Fixture f = new Fixture(type);
            f.resource.put("driver_class", "com.mysql.jdbc.Driver");
            StarRocksJdbcResourceSource.Plan plan = f.prepare("time_stamp");
            assertEquals("erp_base`.`rf_user", plan.sourceTableProperty);
            assertTrue(f.queries.contains("SELECT `Id`, `Name`, `time_stamp` FROM `erp_base`.`rf_user` WHERE 1=0"));
            assertFalse("Ordinary DATETIME must not trigger timestamp timezone queries", f.queries.toString().contains("@@session.time_zone"));
        }
        Fixture defaultPort = new Fixture("MYSQL");
        defaultPort.datasource.setJdbcUrl("jdbc:mysql://source.test/business");
        defaultPort.resource.put("jdbc_uri", "jdbc:mysql://source.test:3306/business");
        assertEquals(10L, defaultPort.prepare(null).sourceRowCount);
    }

    @Test
    public void mysqlProtocolKeepsQuotedUnicodeSpaceAndReservedColumnNamesButRejectsEmbeddedBackticks() throws Exception {
        Fixture f = new Fixture("MYSQL");
        f.columns.get(0).name = "客户 名称";
        f.columns.get(1).name = "order";
        assertTrue(f.prepare(null).externalColumnsSql.contains("`客户 名称` VARCHAR"));
        assertTrue(f.queries.contains("SELECT `客户 名称`, `order`, `time_stamp` FROM `erp_base`.`rf_user` WHERE 1=0"));
        Fixture invalid = new Fixture("TIDB");
        invalid.columns.get(0).name = "Id` FROM sensitive --";
        invalid.reject("裸列名无法安全读取");
        assertTrue(invalid.queries.isEmpty());
    }

    @Test
    public void unsignedMySqlValuesKeepTheirDriverNumericRange() throws Exception {
        Object[][] cases = {
                {Types.TINYINT, "TINYINT UNSIGNED", "java.lang.Short", 3, 0, "SMALLINT"},
                {Types.SMALLINT, "SMALLINT UNSIGNED ZEROFILL", "java.lang.Integer", 5, 0, "INT"},
                {Types.INTEGER, "INT(10) UNSIGNED", "java.lang.Long", 10, 0, "BIGINT"},
                {Types.BIGINT, "BIGINT UNSIGNED", "java.math.BigInteger", 20, 0, "LARGEINT"},
                {Types.DECIMAL, "DECIMAL(30,9) UNSIGNED", "java.math.BigDecimal", 30, 9, "DECIMAL(30,9)"}
        };
        for (String type : Arrays.asList("MYSQL", "TIDB")) for (Object[] item : cases) {
            Fixture f = new Fixture(type);
            Field field = f.columns.get(0);
            field.jdbcType = (Integer) item[0]; field.nativeType = (String) item[1];
            field.javaClass = (String) item[2]; field.precision = (Integer) item[3]; field.scale = (Integer) item[4];
            assertEquals(item[5], f.prepare(null).columns.get(0).externalType);
        }
    }

    @Test
    public void jsonSourceKindsUseNativeUtf8SerializationAndExposeHelperProjectionMarker() throws Exception {
        for (String type : Arrays.asList("MYSQL", "TIDB", "STARROCKS")) {
            Fixture f = new Fixture(type);
            Field field = f.columns.get(0);
            field.jdbcType = Types.LONGVARCHAR;
            field.nativeType = "STARROCKS".equals(type) ? "VARCHAR" : "JSON";
            field.describedType = "JSON";
            StarRocksJdbcResourceSource.Column column = f.prepare(null).columns.get(0);
            assertTrue(column.json);
            assertEquals("JSON", column.nativeType);
            assertEquals("VARCHAR(1048576)", column.externalType);
            assertTrue(f.queries.toString(), f.queries.stream().anyMatch(sql -> "STARROCKS".equals(type)
                    ? sql.contains("MAX(LENGTH(JSON_STRING(`Id`)))")
                    : sql.contains("MAX(OCTET_LENGTH(CAST(`Id` AS CHAR CHARACTER SET utf8mb4)))")));
        }
    }

    @Test
    public void starRocksDescribeDistinguishesNativeLargeIntegerAndComplexValuesDisguisedAsString() throws Exception {
        Fixture f = new Fixture("STARROCKS");
        f.columns.get(0).nativeType = "VARCHAR";
        f.columns.get(0).describedType = "LARGEINT";
        StarRocksJdbcResourceSource.Plan plan = f.prepare(null);
        assertEquals("VARCHAR(40)", plan.columns.get(0).externalType);
        assertTrue(plan.columns.get(0).largeIntegerText);
        assertEquals(-1, plan.columns.get(0).externalTextCapacity);
        assertTrue(plan.selectColumnsSql.contains("CAST(`Id` AS LARGEINT)"));
        f.columns.get(0).jdbcType = Types.BIGINT;
        f.columns.get(0).javaClass = "java.math.BigInteger";
        f.columns.get(0).precision = 39;
        assertEquals("LARGEINT", f.prepare(null).columns.get(0).externalType);
        for (String unsupported : Arrays.asList("ARRAY<INT>", "MAP<VARCHAR,INT>", "STRUCT<a INT>", "HLL", "BITMAP", "VARBINARY")) {
            Fixture invalid = new Fixture("STARROCKS");
            invalid.columns.get(0).describedType = unsupported;
            invalid.reject("尚未验证");
            assertFalse(invalid.queries.stream().anyMatch(sql -> sql.startsWith("SELECT COUNT(*)")));
        }
    }

    @Test
    public void mysqlTimestampValidatesRealResourceSessionWhileDatetimeIgnoresUnneededZoneLabels() throws Exception {
        Fixture f = new Fixture("MYSQL");
        f.columns.get(2).nativeType = "TIMESTAMP";
        f.globalZone = "+00:00";
        try { f.prepare("time_stamp"); fail("Different sessions cannot safely import TIMESTAMP"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("会话时区")); }
        f.globalZone = f.sessionZone;
        assertEquals(10L, f.prepare("time_stamp").sourceRowCount);
        Fixture datetime = new Fixture("TIDB");
        datetime.datasource.setJdbcUrl(datetime.datasource.getJdbcUrl() + "?serverTimezone=Asia%2FShanghai");
        datetime.resource.put("jdbc_uri", datetime.resource.get("jdbc_uri") + "?serverTimezone=UTC");
        assertEquals(10L, datetime.prepare("time_stamp").sourceRowCount);
        assertFalse(datetime.queries.toString().contains("@@session.time_zone"));
    }

    @Test
    public void knownDmTimestampVendorClassCanBeValidatedByTheDifferentResourceDriver() throws Exception {
        Fixture f = new Fixture("DM");
        f.columns.get(2).javaClass = "dm.jdbc.driver.DmdbTimestamp";
        StarRocksJdbcResourceSource.Column column = f.prepare("time_stamp").columns.get(2);
        assertTrue(column.timestampText);
        assertEquals("VARCHAR(26)", column.externalType);
    }

    @Test
    public void timestampWindowDaysAndWallClockReferenceUseRawTextRatherThanJvmInstantDate() throws Exception {
        Fixture f = new Fixture("MYSQL");
        f.columns.get(2).nativeType = "TIMESTAMP";
        f.minimum = Timestamp.valueOf("2025-01-01 07:00:00");
        f.maximum = Timestamp.valueOf("2025-01-01 07:30:00");
        f.rawMinimum = "2024-12-31 23:00:00.000000";
        f.rawMaximum = "2024-12-31 23:30:00";
        StarRocksJdbcResourceSource.Plan plan = f.prepare("time_stamp");
        assertEquals("2024-12-31", plan.minDay.toString());
        assertEquals("2024-12-31", plan.maxDay.toString());
        assertEquals(f.rawMinimum, plan.columns.get(2).mysqlTimestampMinText);
        assertEquals(f.rawMaximum, plan.columns.get(2).mysqlTimestampMaxText);
        assertEquals(f.maximum, plan.maxTs);
    }

    @Test
    public void tinyintBooleanDriverConversionRequiresOnlyZeroAndOneBeforeImport() throws Exception {
        Fixture f = new Fixture("MYSQL");
        f.columns.get(0).nativeType = "TINYINT(1)";
        f.columns.get(0).jdbcType = Types.BIT;
        f.columns.get(0).javaClass = "java.lang.Boolean";
        f.columns.get(0).precision = 1;
        assertTrue(f.prepare(null).columns.get(0).tinyIntBoolean);
        assertTrue(f.queries.stream().anyMatch(sql -> sql.contains("`Id` NOT IN (0, 1)")));
        f.invalidTinyIntValues = 1L;
        f.reject("tinyInt1isBit");
    }

    @Test
    public void compatibleResourceMapsNativeMultiBitToUnsignedLargeintDespiteDriverPrecision() throws Exception {
        for (String type : Arrays.asList("MYSQL", "TIDB")) {
            for (int width : new int[] {8, 64}) {
                for (String javaClass : Arrays.asList("java.lang.Boolean", "[B", "java.math.BigInteger")) {
                    Fixture f = new Fixture(type);
                    f.resource.put("driver_class", StarRocksJdbcResourceSource.MYSQL_BIT_COMPATIBLE_DRIVER);
                    Field bit = f.columns.get(0);
                    bit.name = "col_bit" + width;
                    bit.nativeType = "BIT";
                    bit.describedType = "bit(" + width + ")";
                    bit.jdbcType = Types.BIT;
                    bit.javaClass = javaClass;
                    bit.precision = 1; // The live standard driver may advertise Boolean precision.
                    f.columns.get(1).textBytes = null;

                    StarRocksJdbcResourceSource.Plan plan = f.prepare(null);

                    StarRocksJdbcResourceSource.Column mapped = plan.columns.get(0);
                    assertTrue(plan.bitNumeric);
                    assertEquals(width, mapped.unsignedBitWidth);
                    assertEquals(width, mapped.precision);
                    assertEquals(javaClass, mapped.javaClassName);
                    assertEquals("LARGEINT", mapped.externalType);
                    assertEquals(-1, mapped.externalTextCapacity);
                    assertEquals(-1, mapped.maxTextBytes);
                    assertTrue(mapped.nullable);
                    assertEquals(0, plan.columns.get(1).maxTextBytes);
                    assertTrue(plan.externalColumnsSql, plan.externalColumnsSql.contains("`col_bit" + width + "` LARGEINT NULL"));
                    assertTrue(f.queries.toString(), f.queries.contains("SELECT COUNT(*), MAX(OCTET_LENGTH(CAST(`Name` AS CHAR CHARACTER SET utf8mb4))) FROM `erp_base`.`rf_user`"));
                    assertEquals("com.mysql.cj.jdbc.Driver", f.datasource.getDriverClass());
                }
            }
        }
    }

    @Test
    public void compatibleBitSupportsEmptyEligibleSourceWithoutBitValueOrTextStatistics() throws Exception {
        Fixture f = new Fixture("MYSQL");
        f.resource.put("driver_class", StarRocksJdbcResourceSource.MYSQL_BIT_COMPATIBLE_DRIVER);
        Field bit = f.columns.get(0);
        bit.nativeType = "BIT(64)"; bit.jdbcType = Types.BIT; bit.javaClass = "java.lang.Boolean"; bit.precision = 1;
        f.sourceCount = 0;
        f.columns.get(1).textBytes = null;
        StarRocksJdbcResourceSource.Plan plan = f.prepare("time_stamp");
        assertEquals(0, plan.sourceRowCount);
        assertTrue(plan.bitNumeric);
        assertNull(plan.minTs);
        assertNull(plan.maxTs);
        assertFalse(f.queries.stream().anyMatch(sql -> sql.startsWith("SELECT MIN(")));
        assertFalse(f.queries.stream().anyMatch(sql -> sql.startsWith("SELECT COUNT(*)") && sql.contains("`Id`")));
    }

    @Test
    public void nativeBitOneKeepsBooleanWithStandardOrCompatibleResourceDriver() throws Exception {
        for (String driver : Arrays.asList("com.mysql.cj.jdbc.Driver", StarRocksJdbcResourceSource.MYSQL_BIT_COMPATIBLE_DRIVER,
                StarRocksJdbcResourceSource.MYSQL_RESOURCE_COMPATIBLE_DRIVER)) {
            Fixture f = new Fixture("MYSQL");
            f.resource.put("driver_class", driver);
            Field bit = f.columns.get(0);
            bit.nativeType = "BIT(1)"; bit.jdbcType = Types.BIT; bit.javaClass = "java.lang.Boolean"; bit.precision = 64;
            StarRocksJdbcResourceSource.Plan plan = f.prepare(null);
            assertEquals("BOOLEAN", plan.columns.get(0).externalType);
            assertEquals(1, plan.columns.get(0).precision);
            assertEquals(0, plan.columns.get(0).unsignedBitWidth);
            assertFalse(plan.bitNumeric);
            assertFalse(plan.columns.get(0).tinyIntBoolean);
        }
    }

    @Test
    public void newResourceCompatibleV2DriverAlsoKeepsMultiBitNumericSemantics() throws Exception {
        for (String type : Arrays.asList("MYSQL", "TIDB")) {
            for (int width : new int[] {8, 64}) {
                Fixture f = new Fixture(type);
                f.resource.put("driver_class", StarRocksJdbcResourceSource.MYSQL_RESOURCE_COMPATIBLE_DRIVER);
                Field bit = f.columns.get(0);
                bit.nativeType = "BIT(" + width + ")"; bit.jdbcType = Types.BIT;
                bit.javaClass = "java.lang.Boolean"; bit.precision = 1;
                StarRocksJdbcResourceSource.Plan plan = f.prepare(null);
                assertTrue(plan.bitNumeric);
                assertEquals(width, plan.columns.get(0).unsignedBitWidth);
                assertEquals("LARGEINT", plan.columns.get(0).externalType);
            }
        }
    }

    @Test
    public void standardResourceDriverRejectsMultiBitBeforeStatisticsWithActionableDriverName() throws Exception {
        for (String type : Arrays.asList("MYSQL", "TIDB")) {
            for (String javaClass : Arrays.asList("java.lang.Boolean", "[B")) {
                Fixture f = new Fixture(type);
                Field bit = f.columns.get(0);
                bit.nativeType = "BIT(8)"; bit.jdbcType = Types.BIT; bit.javaClass = javaClass; bit.precision = 1;
                f.reject(StarRocksJdbcResourceSource.MYSQL_RESOURCE_COMPATIBLE_DRIVER);
                assertFalse(f.queries.stream().anyMatch(sql -> sql.startsWith("SELECT COUNT(")));
            }
        }
    }

    @Test
    public void compatibleDriverIsOnlyAllowedForMysqlAndTidbResourceNotApplicationDatasource() throws Exception {
        for (String driver : Arrays.asList(StarRocksJdbcResourceSource.MYSQL_BIT_COMPATIBLE_DRIVER,
                StarRocksJdbcResourceSource.MYSQL_RESOURCE_COMPATIBLE_DRIVER)) {
            for (String type : Arrays.asList("MYSQL", "TIDB", "STARROCKS", "KINGBASE", "DM", "POSTGRESQL", "ORACLE", "SQLSERVER")) {
                Fixture f = new Fixture(type);
                f.resource.put("driver_class", driver);
                if ("MYSQL".equals(type) || "TIDB".equals(type)) {
                    assertFalse(f.prepare(null).bitNumeric);
                } else {
                    f.reject("驱动类");
                    assertTrue(f.queries.isEmpty());
                }
            }
            Fixture applicationDriver = new Fixture("MYSQL");
            applicationDriver.resource.put("driver_class", driver);
            applicationDriver.datasource.setDriverClass(driver);
            applicationDriver.reject("驱动类");
            assertTrue(applicationDriver.queries.isEmpty());
        }
    }

    @Test
    public void malformedNativeBitWidthsAndInconsistentJdbcMetadataDoNotBecomeBooleans() throws Exception {
        for (String nativeType : Arrays.asList("BIT(0)", "BIT(65)", "BIT(2147483648)", "BIT(-1)", "BIT(1,2)")) {
            Fixture f = new Fixture("MYSQL");
            f.resource.put("driver_class", StarRocksJdbcResourceSource.MYSQL_BIT_COMPATIBLE_DRIVER);
            Field bit = f.columns.get(0);
            bit.nativeType = nativeType; bit.jdbcType = Types.BIT; bit.javaClass = "java.lang.Boolean"; bit.precision = 1;
            f.reject("原生 BIT 位宽无效");
            assertFalse(f.queries.stream().anyMatch(sql -> sql.startsWith("SELECT COUNT(")));
        }
        for (String mismatch : Arrays.asList("jdbcType", "scale", "javaClass")) {
            Fixture f = new Fixture("MYSQL");
            f.resource.put("driver_class", StarRocksJdbcResourceSource.MYSQL_BIT_COMPATIBLE_DRIVER);
            Field bit = f.columns.get(0);
            bit.nativeType = "BIT(8)"; bit.jdbcType = Types.BIT; bit.javaClass = "java.lang.Boolean"; bit.precision = 1;
            if ("jdbcType".equals(mismatch)) bit.jdbcType = Types.VARBINARY;
            if ("scale".equals(mismatch)) bit.scale = 1;
            if ("javaClass".equals(mismatch)) bit.javaClass = "java.lang.String";
            f.reject("尚未清空目标表");
        }
    }

    @Test
    public void compatibleDriverDoesNotBypassTinyintBooleanValueChecksOrSessionSettings() throws Exception {
        Fixture f = new Fixture("TIDB");
        f.resource.put("driver_class", StarRocksJdbcResourceSource.MYSQL_BIT_COMPATIBLE_DRIVER);
        Field tinyint = f.columns.get(0);
        tinyint.nativeType = "TINYINT(1)"; tinyint.jdbcType = Types.BIT; tinyint.javaClass = "java.lang.Boolean"; tinyint.precision = 1;
        f.invalidTinyIntValues = 2;
        f.reject("tinyInt1isBit");
        Fixture session = new Fixture("MYSQL");
        session.resource.put("driver_class", StarRocksJdbcResourceSource.MYSQL_BIT_COMPATIBLE_DRIVER);
        session.resource.put("jdbc_uri", session.datasource.getJdbcUrl() + "?sessionVariables=sql_mode%3DANSI_QUOTES");
        session.reject("会话");
        assertTrue(session.queries.isEmpty());
    }

    @Test
    public void compatibleV2BinaryFamiliesUseHexTextAndExactDoubledByteStatistics() throws Exception {
        Object[][] binaryTypes = {
                {"BINARY(8)", Types.BINARY, "[B"},
                {"VARBINARY(64)", Types.VARBINARY, "[B"},
                {"TINYBLOB", Types.LONGVARBINARY, "[B"},
                {"BLOB", Types.BLOB, "java.sql.Blob"},
                {"MEDIUMBLOB", Types.LONGVARBINARY, "com.mysql.cj.jdbc.Blob"},
                {"LONGBLOB", Types.BLOB, "com.mysql.jdbc.Blob"}
        };
        for (String type : Arrays.asList("MYSQL", "TIDB")) {
            for (Object[] item : binaryTypes) {
                Fixture f = new Fixture(type);
                f.resource.put("driver_class", StarRocksJdbcResourceSource.MYSQL_RESOURCE_COMPATIBLE_DRIVER);
                Field binary = f.columns.get(0);
                binary.nativeType = (String) item[0]; binary.jdbcType = (Integer) item[1]; binary.javaClass = (String) item[2];
                binary.binaryBytes = 3L;

                StarRocksJdbcResourceSource.Plan plan = f.prepare("time_stamp");

                StarRocksJdbcResourceSource.Column mapped = plan.columns.get(0);
                assertTrue(mapped.binaryHex);
                assertFalse(mapped.timeText);
                assertEquals("VARCHAR(1048576)", mapped.externalType);
                assertEquals(StarRocksJdbcResourceSource.MAX_TEXT_BYTES, mapped.externalTextCapacity);
                assertEquals(6L, mapped.maxTextBytes);
                assertEquals(item[2], mapped.javaClassName);
                assertTrue(plan.externalColumnsSql, plan.externalColumnsSql.contains("`Id` VARCHAR(1048576) NULL"));
                String statistics = f.queries.stream().filter(sql -> sql.startsWith("SELECT COUNT(*)")).findFirst().get();
                assertTrue(statistics, statistics.contains("MAX(OCTET_LENGTH(`Id`)) * 2"));
                assertFalse(statistics, statistics.contains("CAST(`Id` AS CHAR"));
                assertTrue(statistics, statistics.endsWith("WHERE `time_stamp` IS NOT NULL"));
            }
        }
    }

    @Test
    public void hexCapacityChecksExpandedBytesAndAllowsNullOrEmptyValues() throws Exception {
        for (Long rawBytes : Arrays.asList(524_288L, 0L, null)) {
            Fixture f = new Fixture("MYSQL");
            f.resource.put("driver_class", StarRocksJdbcResourceSource.MYSQL_RESOURCE_COMPATIBLE_DRIVER);
            Field binary = f.columns.get(0);
            binary.nativeType = "LONGBLOB"; binary.jdbcType = Types.LONGVARBINARY; binary.javaClass = "[B";
            binary.binaryBytes = rawBytes;
            assertEquals(rawBytes == null ? 0L : rawBytes * 2, f.prepare(null).columns.get(0).maxTextBytes);
        }
        Fixture tooLarge = new Fixture("TIDB");
        tooLarge.resource.put("driver_class", StarRocksJdbcResourceSource.MYSQL_RESOURCE_COMPATIBLE_DRIVER);
        Field binary = tooLarge.columns.get(0);
        binary.nativeType = "MEDIUMBLOB"; binary.jdbcType = Types.BLOB; binary.javaClass = "[B";
        binary.binaryBytes = 524_289L;
        tooLarge.reject("超过当前导入通道容量");
        assertEquals(0, tooLarge.candidateReads);
    }

    @Test
    public void compatibleV2TimeUsesSourceTextStatisticsWithoutJavaTimeConversion() throws Exception {
        for (String type : Arrays.asList("MYSQL", "TIDB")) {
            for (String javaClass : Arrays.asList("java.sql.Time", "java.lang.String", "java.time.LocalTime")) {
                Fixture f = new Fixture(type);
                f.resource.put("driver_class", StarRocksJdbcResourceSource.MYSQL_RESOURCE_COMPATIBLE_DRIVER);
                Field time = f.columns.get(0);
                time.nativeType = "TIME(6)"; time.jdbcType = Types.TIME; time.javaClass = javaClass;
                time.precision = 17; time.scale = 6;
                time.textBytes = (long) "-838:59:59.123456".length();

                StarRocksJdbcResourceSource.Plan plan = f.prepare(null);

                StarRocksJdbcResourceSource.Column mapped = plan.columns.get(0);
                assertTrue(mapped.timeText);
                assertFalse(mapped.binaryHex);
                assertFalse(mapped.mysqlTimestamp);
                assertEquals("VARCHAR(1048576)", mapped.externalType);
                assertEquals(17L, mapped.maxTextBytes);
                assertTrue(f.queries.toString(), f.queries.stream().anyMatch(sql -> sql.startsWith("SELECT COUNT(*)")
                        && sql.contains("MAX(OCTET_LENGTH(CAST(`Id` AS CHAR CHARACTER SET utf8mb4)))")));
                assertFalse(f.queries.stream().anyMatch(sql -> sql.startsWith("SELECT MIN(")));
                int before = f.queries.size();
                try { f.prepare("Id"); fail("A TIME duration is not an initialization watermark"); }
                catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("时间戳字段")); }
                assertFalse(f.queries.subList(before, f.queries.size()).stream().anyMatch(sql -> sql.startsWith("SELECT COUNT(*)")));
            }
        }
    }

    @Test
    public void allFourCompatibilityFamiliesRequireDeployingV2JarWithStandardResourceDriver() throws Exception {
        Object[][] needsV2 = {
                {"BIT(8)", Types.BIT, "java.lang.Boolean"},
                {"BINARY(8)", Types.BINARY, "[B"},
                {"BLOB", Types.BLOB, "java.sql.Blob"},
                {"TIME(6)", Types.TIME, "java.sql.Time"}
        };
        for (Object[] item : needsV2) {
            Fixture f = new Fixture("MYSQL");
            Field field = f.columns.get(0);
            field.nativeType = (String) item[0]; field.jdbcType = (Integer) item[1]; field.javaClass = (String) item[2];
            field.precision = 1;
            try { f.prepare(null); fail("An application-only update cannot install a BE driver"); }
            catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("v2 JAR"));
                assertTrue(expected.getMessage(), expected.getMessage().contains("StarRocks FE/BE"));
                assertTrue(expected.getMessage(), expected.getMessage().contains(StarRocksJdbcResourceSource.MYSQL_RESOURCE_COMPATIBLE_DRIVER));
                assertTrue(expected.getMessage(), expected.getMessage().contains("仅更新应用后端不能修复"));
            }
            assertFalse(f.queries.stream().anyMatch(sql -> sql.startsWith("SELECT COUNT(*)")));
        }
    }

    @Test
    public void originalBitOnlyV1DriverCannotEnableBinaryBlobOrTimeTextMappings() throws Exception {
        Object[][] requiresNewV2Class = {
                {"BINARY(8)", Types.BINARY, "[B"},
                {"VARBINARY(64)", Types.VARBINARY, "[B"},
                {"BLOB", Types.BLOB, "java.sql.Blob"},
                {"TIME(6)", Types.TIME, "java.sql.Time"}
        };
        for (String type : Arrays.asList("MYSQL", "TIDB")) {
            for (Object[] item : requiresNewV2Class) {
                Fixture f = new Fixture(type);
                f.resource.put("driver_class", StarRocksJdbcResourceSource.MYSQL_BIT_COMPATIBLE_DRIVER);
                Field field = f.columns.get(0);
                field.nativeType = (String) item[0]; field.jdbcType = (Integer) item[1]; field.javaClass = (String) item[2];
                try { f.prepare(null); fail("The existing v1 BIT jar has no binary/TIME conversion capability"); }
                catch (IllegalArgumentException expected) {
                    assertTrue(expected.getMessage(), expected.getMessage().contains("v2 JAR"));
                    assertTrue(expected.getMessage(), expected.getMessage().contains(StarRocksJdbcResourceSource.MYSQL_RESOURCE_COMPATIBLE_DRIVER));
                }
                assertFalse(f.queries.stream().anyMatch(sql -> sql.startsWith("SELECT COUNT(*)")));
            }
        }
    }

    @Test
    public void v2DoesNotRelaxUnknownBinaryTimeMetadataOrOtherDatabaseLobRestrictions() throws Exception {
        Object[][] unsupported = {
                {"VARBINARY(64)", Types.VARBINARY, "java.lang.String", 0},
                {"BLOB", Types.VARCHAR, "[B", 0},
                {"BLOB", Types.BLOB, "vendor.UnverifiedBlob", 0},
                {"TIME(6)", Types.TIME, "java.sql.Timestamp", 6},
                {"TIME(6)", Types.TIME_WITH_TIMEZONE, "java.sql.Time", 6},
                {"TIME(6)", Types.TIME, "java.sql.Time", 7},
                {"TIME(7)", Types.TIME, "java.sql.Time", 7},
                {"GEOMETRY", Types.BINARY, "[B", 0}
        };
        for (Object[] item : unsupported) {
            Fixture f = new Fixture("MYSQL");
            f.resource.put("driver_class", StarRocksJdbcResourceSource.MYSQL_RESOURCE_COMPATIBLE_DRIVER);
            Field field = f.columns.get(0);
            field.nativeType = (String) item[0]; field.jdbcType = (Integer) item[1]; field.javaClass = (String) item[2];
            field.scale = (Integer) item[3];
            f.reject("尚未清空目标表");
            assertFalse(f.queries.stream().anyMatch(sql -> sql.startsWith("SELECT COUNT(*)")));
        }
        for (String type : Arrays.asList("STARROCKS", "KINGBASE", "DM", "POSTGRESQL", "ORACLE", "SQLSERVER")) {
            Fixture f = new Fixture(type);
            Field blob = f.columns.get(0);
            blob.nativeType = "BLOB"; blob.jdbcType = Types.BLOB; blob.javaClass = "[B";
            f.reject("尚未清空目标表");
            assertFalse(f.queries.stream().anyMatch(sql -> sql.startsWith("SELECT COUNT(")));
        }
    }

    @Test
    public void additionalThreeSourcesUseRealDriverAndCorrectRemoteNamespace() throws Exception {
        for (String type : Arrays.asList("POSTGRESQL", "ORACLE", "SQLSERVER")) {
            Fixture f = new Fixture(type);
            StarRocksJdbcResourceSource.Plan plan = f.prepare("time_stamp");
            assertEquals(type, plan.sourceType);
            assertEquals("SQLSERVER".equals(type) ? "[business].[dbo].[rf_user]"
                    : "ORACLE".equals(type) ? "\"ERP_BASE\".\"rf_user\"" : "\"erp_base\".\"rf_user\"", plan.sourceTableProperty);
            assertEquals("DATETIME", plan.columns.get(2).externalType);
            assertEquals(10L, plan.sourceRowCount);
            if ("SQLSERVER".equals(type)) {
                assertTrue(f.queries.toString(), f.queries.stream().anyMatch(sql -> sql.startsWith("SELECT COUNT_BIG(*)")
                        && sql.contains("DATALENGTH(CONVERT(NVARCHAR(MAX), [Id]))")));
                assertTrue(f.queries.contains("SELECT [Id], [Name], [time_stamp] FROM [business].[dbo].[rf_user] WHERE 1=0"));
                assertTrue(f.queries.contains("SELECT Id, Name, time_stamp FROM [business].[dbo].[rf_user] WHERE 1=0"));
            }
        }
    }

    @Test
    public void postgresFollowsExtractorPublicDefaultSeparatelyFromUrlDatabaseAndCountsCharPadding() throws Exception {
        Fixture f = new Fixture("POSTGRESQL");
        f.schema = null;
        f.currentSchema = "inventory";
        f.columns.get(0).jdbcType = Types.CHAR;
        f.columns.get(0).nativeType = "bpchar";
        StarRocksJdbcResourceSource.Plan plan = f.prepare(null);
        assertEquals("public", plan.schema);
        assertEquals("\"public\".\"rf_user\"", plan.sourceTableProperty);
        assertTrue(f.queries.toString(), f.queries.stream().anyMatch(sql -> sql.contains(
                "OCTET_LENGTH(CONVERT_TO(CAST(\"Id\" AS TEXT), 'UTF8')) + OCTET_LENGTH(\"Id\") - OCTET_LENGTH(CAST(\"Id\" AS TEXT))")));
        f.schema = "%";
        f.reject("普通模式名");
        Fixture session = new Fixture("POSTGRESQL");
        session.resource.put("jdbc_uri", session.datasource.getJdbcUrl() + "?currentSchema=another");
        session.reject("currentschema");
        Fixture shiftedDatabase = new Fixture("POSTGRESQL");
        shiftedDatabase.resource.put("jdbc_uri", "jdbc:postgresql://source.test:5432/another");
        assertEquals("\"erp_base\".\"rf_user\"", shiftedDatabase.prepare(null).sourceTableProperty);
    }

    @Test
    public void oraclePreservesQuotedSchemaAndPhysicalTableAndUsesNlsIndependentWallDates() throws Exception {
        Fixture f = new Fixture("ORACLE");
        f.schema = "\"MixedOwner\"";
        f.table = "MixedTable";
        f.resource.put("driver_class", "oracle.jdbc.driver.OracleDriver");
        f.rawMinimum = "2024-12-31 23:00:00.000000";
        f.rawMaximum = "2025-01-01 00:30:00.000000";
        StarRocksJdbcResourceSource.Plan plan = f.prepare("time_stamp");
        assertEquals("\"MixedOwner\".\"MixedTable\"", plan.sourceTableProperty);
        assertEquals("2024-12-31", plan.minDay.toString());
        assertEquals("2025-01-01", plan.maxDay.toString());
        assertTrue(f.queries.toString(), f.queries.stream().anyMatch(sql -> sql.contains(
                "TO_CHAR(CAST(MIN(\"time_stamp\") AS TIMESTAMP), 'YYYY-MM-DD HH24:MI:SS.FF6')")));
        Fixture actualOwner = new Fixture("ORACLE");
        actualOwner.schema = null;
        actualOwner.currentSchema = "OTHER_OWNER";
        assertEquals("SYNC_TEST", actualOwner.prepare(null).schema);
    }

    @Test
    public void oracleDateRequiresTimestampJavaClassAndMatchingConversionButRejectsVendorAndTimezoneTypes() throws Exception {
        Fixture f = new Fixture("ORACLE");
        Field date = f.columns.get(2);
        date.nativeType = "DATE";
        date.jdbcType = Types.DATE;
        date.scale = 0;
        assertEquals("DATETIME", f.prepare("time_stamp").columns.get(2).externalType);
        date.javaClass = "java.sql.Date";
        f.reject("并确认驱动返回类型");
        date.javaClass = "java.sql.Timestamp";
        f.resource.put("jdbc_uri", f.datasource.getJdbcUrl() + "?oracle.jdbc.mapDateToTimestamp=false");
        f.reject("DATE 转换设置不一致");
        for (String vendor : Arrays.asList("oracle.sql.DATE", "oracle.sql.TIMESTAMP")) {
            Fixture unknown = new Fixture("ORACLE");
            unknown.columns.get(2).javaClass = vendor;
            unknown.reject("尚未验证");
        }
        Fixture timezone = new Fixture("ORACLE");
        timezone.columns.get(2).nativeType = "TIMESTAMP WITH LOCAL TIME ZONE";
        timezone.reject("尚未验证");
    }

    @Test
    public void oracleResourceSidAndServiceMayDifferFromApplicationConnectionIdentity() throws Exception {
        Fixture f = new Fixture("ORACLE");
        f.datasource.setJdbcUrl("jdbc:oracle:thin:@source.test:1521:business");
        f.resource.put("jdbc_uri", "jdbc:oracle:thin:@//source.test:1521/business");
        assertEquals(10L, f.prepare(null).sourceRowCount);
        f.datasource.setJdbcUrl("jdbc:oracle:thin:@//source.test:1521/business");
        assertEquals(10L, f.prepare(null).sourceRowCount);
        f.resource.put("jdbc_uri", "jdbc:oracle:thin:@//source.test/business");
        assertEquals(10L, f.prepare(null).sourceRowCount);
    }

    @Test
    public void sqlServerDatabaseScopeUnsignedTinyintAndKnownScalarsArePreserved() throws Exception {
        Fixture f = new Fixture("SQLSERVER");
        Field tiny = f.columns.get(0);
        tiny.nativeType = "TINYINT"; tiny.jdbcType = Types.TINYINT; tiny.javaClass = "java.lang.Short"; tiny.precision = 3;
        StarRocksJdbcResourceSource.Column mapped = f.prepare(null).columns.get(0);
        assertEquals("TINYINT UNSIGNED", mapped.nativeType);
        assertEquals("SMALLINT", mapped.externalType);
        f.schema = "other";
        f.reject("应为源连接的数据库名");
        Object[][] scalar = {{Types.CHAR, "uniqueidentifier", "java.lang.String", 36, 0, "VARCHAR(1048576)"},
                {Types.DECIMAL, "money", "java.math.BigDecimal", 19, 4, "DECIMAL(19,4)"},
                {Types.DECIMAL, "smallmoney", "java.math.BigDecimal", 10, 4, "DECIMAL(10,4)"},
                {Types.TIMESTAMP, "datetime2", "java.sql.Timestamp", 27, 6, "DATETIME"}};
        for (Object[] item : scalar) {
            Fixture itemFixture = new Fixture("SQLSERVER");
            Field field = itemFixture.columns.get(0);
            field.jdbcType = (Integer) item[0]; field.nativeType = (String) item[1]; field.javaClass = (String) item[2];
            field.precision = (Integer) item[3]; field.scale = (Integer) item[4];
            assertEquals(item[5], itemFixture.prepare(null).columns.get(0).externalType);
        }
        for (String unsupported : Arrays.asList("timestamp", "rowversion", "datetimeoffset")) {
            Fixture rejected = new Fixture("SQLSERVER");
            rejected.columns.get(0).nativeType = unsupported;
            rejected.columns.get(0).jdbcType = Types.BINARY;
            rejected.columns.get(0).javaClass = "[B";
            rejected.reject("尚未验证");
        }
    }

    @Test
    public void sqlServerBracedDatabaseAndSessionPropertiesAreParsedWithoutFakeUris() throws Exception {
        Fixture f = new Fixture("SQLSERVER");
        f.datasource.setJdbcUrl("jdbc:sqlserver://source.test;databaseName={business};encrypt=false");
        f.resource.put("jdbc_uri", "jdbc:sqlserver://source.test:1433;databaseName=business;encrypt=false");
        assertEquals(10L, f.prepare(null).sourceRowCount);
        f.resource.put("jdbc_uri", f.resource.get("jdbc_uri") + ";integratedSecurity=true");
        f.reject("integratedsecurity");
    }

    @Test
    public void sqlServerDriverExpandedMetadataDefaultsRemainEquivalentToOrdinaryResourceUrl() throws Exception {
        Fixture f = new Fixture("SQLSERVER");
        // SQLServerDatabaseMetaData.getURL() materializes driver defaults and omits
        // integratedSecurity and credentials rather than returning the original URL.
        f.actualUrl = f.datasource.getJdbcUrl() + ";authenticationScheme=NativeAuthentication"
                + ";jaasConfigurationName=SQLJDBCDriver;authentication=NotSpecified"
                + ";applicationIntent=readwrite;responseBuffering=adaptive;selectMethod=direct"
                + ";sendTimeAsDatetime=true;socketTimeout=0;encrypt=false";
        assertEquals(10L, f.prepare(null).sourceRowCount);
        f.resource.put("jdbc_uri", f.datasource.getJdbcUrl() + ";integratedSecurity=false");
        assertEquals(10L, f.prepare(null).sourceRowCount);
        f.resource.put("jdbc_uri", f.datasource.getJdbcUrl() + ";authenticationScheme=JavaKerberos");
        f.reject("authenticationscheme");
    }

    private static String repeat(String text, int count) {
        StringBuilder result = new StringBuilder(text.length() * count);
        for (int i = 0; i < count; i++) result.append(text);
        return result.toString();
    }

    private static final class Field {
        String name;
        int jdbcType;
        String nativeType;
        String javaClass;
        int precision;
        int scale;
        int nullable = ResultSetMetaData.columnNullable;
        Long textBytes = 40L;
        Long binaryBytes = 20L;
        String describedType;
        String projectedJavaClass;
        Integer projectedJdbcType;
        Integer projectedPrecision;
        Integer projectedScale;
        String projectedName;
        boolean projectedTimeText;
        List<String> timeTextValues;
        List<byte[]> binaryValues = Collections.singletonList(new byte[]{0, (byte) 0x80, (byte) 0xFF});
        List<String> binaryProjectionValues;
        List<String> clobValues = Collections.singletonList("达梦 CLOB");
        List<String> clobStringValues;

        Field(String name, int jdbcType, String nativeType, String javaClass, int precision, int scale) {
            this.name = name; this.jdbcType = jdbcType; this.nativeType = nativeType;
            this.javaClass = javaClass; this.precision = precision; this.scale = scale;
        }
    }

    private static final class Fixture {
        final ErDatasource datasource = new ErDatasource();
        final Map<String, String> resource = new HashMap<>();
        final List<String> queries = new ArrayList<>();
        final List<Field> columns = Arrays.asList(
                new Field("Id", Types.VARCHAR, "VARCHAR", "java.lang.String", 36, 0),
                new Field("Name", Types.VARCHAR, "VARCHAR", "java.lang.String", 50, 0),
                new Field("time_stamp", Types.TIMESTAMP, "TIMESTAMP", "java.sql.Timestamp", 29, 6));
        Timestamp minimum = Timestamp.valueOf("2024-01-01 01:02:03");
        Timestamp maximum = Timestamp.valueOf("2026-11-11 12:00:00");
        String rawMinimum;
        String rawMaximum;
        String schema = "erp_base";
        String table = "rf_user";
        String currentSchema = "erp_base";
        String actualCatalog = "business";
        long invalidTinyIntValues;
        long sourceCount = 10;
        String actualUrl;
        boolean failRawProbe;
        boolean wrongRawPhysicalName;
        boolean wrongPhysicalName;
        SQLException physicalProbeFailure;
        SQLException aliasProbeFailure;
        int aliasColumnCountDelta;
        boolean uppercaseAliasLabels;
        Long binaryInvalidProjectionCount = 0L;
        final DataSyncCancellation cancellation = new DataSyncCancellation();
        List<String> candidates = Collections.emptyList();
        boolean cancelCandidate;
        int candidateReads;
        int candidateFetchSize;
        String sessionZone = "+08:00";
        String globalZone = "+08:00";
        String systemZone = "CST";

        Fixture(String type) {
            datasource.setDatasourceType(type);
            boolean mysql = Arrays.asList("MYSQL", "TIDB", "STARROCKS").contains(type);
            datasource.setDriverClass(mysql ? "com.mysql.cj.jdbc.Driver" : "DM".equals(type) ? "dm.jdbc.driver.DmDriver"
                    : "POSTGRESQL".equals(type) ? "org.postgresql.Driver" : "ORACLE".equals(type) ? "oracle.jdbc.OracleDriver"
                    : "SQLSERVER".equals(type) ? "com.microsoft.sqlserver.jdbc.SQLServerDriver" : "com.kingbase8.Driver");
            datasource.setUsername("sync_test");
            datasource.setJdbcUrl(mysql ? "jdbc:mysql://source.test:" + ("TIDB".equals(type) ? 4000 : "STARROCKS".equals(type) ? 9030 : 3306) + "/business"
                    : "DM".equals(type) ? "jdbc:dm://source.test:5236"
                    : "POSTGRESQL".equals(type) ? "jdbc:postgresql://source.test:5432/business"
                    : "ORACLE".equals(type) ? "jdbc:oracle:thin:@source.test:1521:business"
                    : "SQLSERVER".equals(type) ? "jdbc:sqlserver://source.test:1433;databaseName=business"
                    : "jdbc:kingbase8://source.test:54321/business");
            datasource.setDatabaseName("business");
            if ("SQLSERVER".equals(type)) schema = "business";
            if (mysql) columns.get(2).nativeType = "DATETIME";
            resource.put("driver_class", datasource.getDriverClass());
            resource.put("user", datasource.getUsername());
            resource.put("jdbc_uri", datasource.getJdbcUrl());
        }

        StarRocksJdbcResourceSource.Plan prepare(String timestamp) throws Exception {
            List<String> names = new ArrayList<>();
            for (Field column : columns) names.add(column.name);
            return StarRocksJdbcResourceSource.prepare(datasource, connection(), schema, table, names,
                    timestamp, resource, cancellation);
        }

        void reject(String message) throws Exception {
            try { prepare(null); fail("Expected validation error containing " + message); }
            catch (IllegalArgumentException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains(message)); }
        }

        Connection connection() {
            return proxy(Connection.class, (method, args) -> {
                if ("getCatalog".equals(method)) return actualCatalog;
                if ("getSchema".equals(method)) return currentSchema;
                if ("getMetaData".equals(method)) return proxy(DatabaseMetaData.class, (call, values) -> {
                    if ("getURL".equals(call)) return actualUrl == null ? datasource.getJdbcUrl() : actualUrl;
                    if ("getUserName".equals(call)) return datasource.getUsername();
                    if ("storesUpperCaseIdentifiers".equals(call)) return true; // Must not reject proven MixedCase SQL.
                    if ("storesLowerCaseIdentifiers".equals(call)) return false;
                    throw new AssertionError(call);
                });
                if ("createStatement".equals(method)) return proxy(Statement.class, (call, values) -> {
                    if ("close".equals(call)) return null;
                    if ("setFetchSize".equals(call)) { candidateFetchSize = (Integer) values[0]; return null; }
                    assertEquals("executeQuery", call);
                    String sql = (String) values[0]; queries.add(sql);
                    if (sql.startsWith("DESCRIBE ")) {
                        int[] cursor = {-1};
                        return proxy(ResultSet.class, (get, arguments) -> {
                            if ("close".equals(get)) return null;
                            if ("next".equals(get)) return ++cursor[0] < columns.size();
                            if ("getString".equals(get)) {
                                Field field = columns.get(cursor[0]);
                                return "Field".equals(arguments[0]) ? field.name : field.describedType == null ? field.nativeType : field.describedType;
                            }
                            throw new AssertionError(get);
                        });
                    }
                    boolean alias = sql.contains(" AS er_c") && sql.contains("er_source");
                    boolean physical = !alias && (sql.startsWith("SELECT \"") || sql.startsWith("SELECT ["));
                    if (sql.endsWith("WHERE 1=0")) {
                        if (alias && aliasProbeFailure != null) throw aliasProbeFailure;
                        if (physical && physicalProbeFailure != null) throw physicalProbeFailure;
                        if (!physical && !alias && failRawProbe) throw new SQLException("private-driver-secret", "42000", 919);
                        return result(metadata(!physical, alias), new ArrayList<>());
                    }
                    if (sql.startsWith("SELECT COUNT(*)") || sql.startsWith("SELECT COUNT_BIG(*)")) {
                        if (sql.contains("NOT IN (0, 1)")) return result(null, Collections.singletonList(new Object[]{invalidTinyIntValues}));
                        List<Object> aggregates = new ArrayList<>(); aggregates.add(sourceCount);
                        for (Field field : columns) {
                            String sourceField = "`" + field.name + "`";
                            if ("DM".equals(datasource.getDatasourceType()) && dmBinary(field)) {
                                aggregates.add(field.binaryBytes == null ? null : field.binaryBytes * 2);
                            } else if ("DM".equals(datasource.getDatasourceType()) && dmClob(field)) {
                                aggregates.add(field.textBytes);
                            } else if ("KINGBASE".equals(datasource.getDatasourceType()) && "BYTEA".equalsIgnoreCase(field.nativeType)) {
                                aggregates.add(field.binaryBytes == null ? null : field.binaryBytes * 2 * 4);
                            } else if ("KINGBASE".equals(datasource.getDatasourceType()) && kingbaseJson(field)) {
                                aggregates.add(field.textBytes);
                            } else if ("KINGBASE".equals(datasource.getDatasourceType()) && field.projectedTimeText) {
                                Long bound = field.textBytes;
                                if (field.timeTextValues != null) {
                                    bound = null;
                                    for (String text : field.timeTextValues) if (text != null) {
                                        long length = text.length() * 4L;
                                        bound = bound == null ? length : Math.max(bound, length);
                                    }
                                }
                                aggregates.add(bound);
                            } else if (sql.contains("MAX(OCTET_LENGTH(" + sourceField + ")) * 2")) {
                                aggregates.add(field.binaryBytes == null ? null : field.binaryBytes * 2);
                            } else if (sql.contains("MAX(OCTET_LENGTH(CAST(" + sourceField + " AS CHAR CHARACTER SET utf8mb4)))")
                                    || ("java.lang.String".equals(field.javaClass) && !"LARGEINT".equals(field.describedType))) {
                                aggregates.add(field.textBytes);
                            }
                        }
                        if ("KINGBASE".equals(datasource.getDatasourceType())) {
                            for (Field field : columns) {
                                if ("BYTEA".equalsIgnoreCase(field.nativeType)) aggregates.add(binaryInvalidProjectionCount);
                            }
                        }
                        return result(null, Collections.singletonList(aggregates.toArray()));
                    }
                    if (sql.startsWith("SELECT MIN(")) {
                        if (sql.contains("TO_CHAR(")) return result(null, Collections.singletonList(new Object[]{minimum, maximum,
                                rawMinimum == null ? minimum.toString() : rawMinimum, rawMaximum == null ? maximum.toString() : rawMaximum}));
                        return result(null, Collections.singletonList(new Object[]{minimum, maximum}), new String[]{rawMinimum, rawMaximum});
                    }
                    if (sql.startsWith("SELECT @@session.time_zone")) return result(null,
                            Collections.singletonList(new Object[]{sessionZone, globalZone, systemZone}));
                    if ("DM".equals(datasource.getDatasourceType())) {
                        for (Field field : columns) {
                            String raw = "\"" + field.name + "\"";
                            if (dmBinary(field) && sql.startsWith("SELECT " + raw + ", UPPER(RAWTOHEX(")) {
                                List<Object[]> valuesToRead = new ArrayList<>();
                                for (int index = 0; index < field.binaryValues.size(); index++) {
                                    byte[] bytes = field.binaryValues.get(index);
                                    String encoded = field.binaryProjectionValues == null ? hex(bytes) : field.binaryProjectionValues.get(index);
                                    valuesToRead.add(new Object[]{bytes, encoded});
                                }
                                return result(null, valuesToRead);
                            }
                            if (dmClob(field) && sql.startsWith("SELECT " + raw + " FROM ") && !sql.contains("LENGTH(")) {
                                int[] cursor = {-1};
                                return proxy(ResultSet.class, (get, arguments) -> {
                                    if ("close".equals(get)) return null;
                                    if ("next".equals(get)) return ++cursor[0] < field.clobValues.size();
                                    String clob = field.clobValues.get(cursor[0]);
                                    if ("getString".equals(get)) return field.clobStringValues == null ? clob : field.clobStringValues.get(cursor[0]);
                                    if ("getCharacterStream".equals(get)) return clob == null ? null : new java.io.StringReader(clob);
                                    throw new AssertionError(get);
                                });
                            }
                        }
                    }
                    if (sql.startsWith("SELECT Id FROM ") || (("KINGBASE".equals(datasource.getDatasourceType()) || "DM".equals(datasource.getDatasourceType()))
                            && sql.startsWith("SELECT ") && sql.contains("LENGTH(") && sql.contains(" > "))) {
                        int[] cursor = {-1};
                        return proxy(ResultSet.class, (get, arguments) -> {
                            if ("close".equals(get)) return null;
                            if ("next".equals(get)) return ++cursor[0] < candidates.size();
                            if ("getString".equals(get)) {
                                candidateReads++;
                                if (cancelCandidate) cancellation.requestStop();
                                return candidates.get(cursor[0]);
                            }
                            throw new AssertionError(get);
                        });
                    }
                    throw new AssertionError(sql);
                });
                throw new AssertionError(method);
            });
        }

        ResultSetMetaData metadata(boolean raw, boolean alias) {
            return proxy(ResultSetMetaData.class, (method, args) -> {
                if ("getColumnCount".equals(method)) return columns.size() + (alias ? aliasColumnCountDelta : 0);
                int index = (Integer) args[0] - 1;
                Field field = columns.get(index);
                boolean timestampText = alias && "DM".equals(datasource.getDatasourceType()) && dmTimestampText(field);
                boolean converted = alias && (kingbaseJson(field) || "BYTEA".equalsIgnoreCase(field.nativeType)
                        || field.projectedTimeText || timestampText || "DM".equals(datasource.getDatasourceType()) && dmBinary(field));
                switch (method) {
                    case "getColumnName": case "getColumnLabel":
                        if (alias) {
                            String aliasName = field.projectedName == null ? "er_c" + index : field.projectedName;
                            return uppercaseAliasLabels && "getColumnLabel".equals(method)
                                    ? aliasName.toUpperCase(java.util.Locale.ROOT) : aliasName;
                        }
                        if (wrongPhysicalName && index == 0) return "different_physical_name";
                        return raw && wrongRawPhysicalName && index == 0 ? "different_field" : field.name;
                    case "getTableName": return "rf_user";
                    case "getSchemaName": return "erp_base";
                    case "getColumnType": return alias && field.projectedJdbcType != null ? field.projectedJdbcType
                            : converted ? Types.VARCHAR : field.jdbcType;
                    case "getColumnTypeName": return converted ? "TEXT" : field.nativeType;
                    case "getColumnClassName": return alias && field.projectedJavaClass != null ? field.projectedJavaClass
                            : converted ? "java.lang.String" : field.javaClass;
                    case "getPrecision": return alias && field.projectedPrecision != null ? field.projectedPrecision
                            : timestampText ? 26 : converted ? (int) StarRocksJdbcResourceSource.MAX_TEXT_BYTES : field.precision;
                    case "getScale": return alias && field.projectedScale != null ? field.projectedScale : converted ? 0 : field.scale;
                    case "isNullable": return field.nullable;
                    default: throw new AssertionError(method);
                }
            });
        }
    }

    private static boolean kingbaseJson(Field field) {
        return "JSON".equalsIgnoreCase(field.nativeType) || "JSONB".equalsIgnoreCase(field.nativeType);
    }

    private static boolean dmTimestampText(Field field) {
        return "DATE".equalsIgnoreCase(field.nativeType) || field.jdbcType == Types.TIMESTAMP;
    }

    private static boolean dmBinary(Field field) {
        return field.nativeType.toUpperCase(java.util.Locale.ROOT).matches("(?:BINARY|VARBINARY|RAW)(?:\\s*\\(\\s*\\d+\\s*\\))?");
    }

    private static boolean dmClob(Field field) {
        return Arrays.asList("CLOB", "NCLOB", "TEXT").contains(field.nativeType.toUpperCase(java.util.Locale.ROOT));
    }

    private static String hex(byte[] bytes) {
        if (bytes == null) return null;
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format(java.util.Locale.ROOT, "%02X", value & 0xff));
        return text.toString();
    }

    private static ResultSet result(ResultSetMetaData metadata, List<Object[]> rows) {
        return result(metadata, rows, null);
    }

    private static ResultSet result(ResultSetMetaData metadata, List<Object[]> rows, String[] rawStrings) {
        int[] cursor = {-1};
        boolean[] wasNull = {false};
        return proxy(ResultSet.class, (method, args) -> {
            if ("close".equals(method)) return null;
            if ("getMetaData".equals(method)) return metadata;
            if ("next".equals(method)) return ++cursor[0] < rows.size();
            if ("wasNull".equals(method)) return wasNull[0];
            Object value = rows.get(cursor[0])[(Integer) args[0] - 1];
            wasNull[0] = value == null;
            if ("getLong".equals(method)) return value == null ? 0L : ((Number) value).longValue();
            if ("getTimestamp".equals(method)) return value;
            if ("getBytes".equals(method)) return value;
            if ("getString".equals(method)) {
                String raw = rawStrings == null ? null : rawStrings[(Integer) args[0] - 1];
                return raw != null ? raw : value == null ? null : value.toString();
            }
            throw new AssertionError(method);
        });
    }

    private interface Invocation { Object invoke(String method, Object[] args) throws Throwable; }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (object, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if ("toString".equals(method.getName())) return "resource-source fixture";
                if ("hashCode".equals(method.getName())) return System.identityHashCode(object);
                if ("equals".equals(method.getName())) return object == args[0];
            }
            return invocation.invoke(method.getName(), args);
        }));
    }
}
