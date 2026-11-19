package cn.easyreport.sync.datasync;

import cn.easyreport.sync.domain.ErDataTransfer;
import cn.easyreport.sync.domain.ErDataTransferLog;
import cn.easyreport.sync.domain.ErDataTransferProgress;
import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.mapper.ErDataTransferProgressMapper;
import org.junit.Test;

import javax.sql.rowset.serial.SerialBlob;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.time.Duration;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
import java.util.TreeMap;

import static org.junit.Assert.*;

/** Exercises the real full/incremental lifecycle with JDBC boundaries replaced by strict proxies. */
public class StarRocksBinaryTextExecutorTest {
    private static final List<String> COLUMNS = Arrays.asList("id", "invoice_qr_code", "modified_at");
    private static final List<String> KEYS = Collections.singletonList("id");
    private static final String TARGET = "`warehouse`.`invoices`";
    private static final Timestamp MODIFIED = Timestamp.valueOf("2026-10-01 12:34:56");
    private static final String QR_TEXT = "发票二维码：https://example.test/qr?id=123&code=中文\n\\N\u0000";

    @Test
    public void mysqlAndTidbResourceImportsWriteOriginalUtf8WithoutReadingResourceOrCreatingExternalTables() throws Exception {
        for (String sourceType : Arrays.asList("MYSQL", "TIDB")) {
            for (int jdbcType : new int[]{Types.BLOB, Types.LONGVARBINARY}) {
                Fixture f = new Fixture(sourceType, jdbcType);
                try (TestExecutor executor = f.executor()) {
                    Object result = f.full(executor, ErDataTransfer.DIRECT_IMPORT_JDBC_RESOURCE);
                    f.assertOriginalTextWritten(result);
                    assertTrue(f.messages.toString(), f.messages.stream().anyMatch(message -> message.contains("无需额外兼容驱动")));
                    f.assertNoRemoteDriverImport();
                }
            }
        }
    }

    @Test
    public void mysqlAndTidbCatalogImportsUseTheSameValidatedJdbcFallback() throws Exception {
        for (String sourceType : Arrays.asList("MYSQL", "TIDB")) {
            Fixture f = new Fixture(sourceType, Types.BLOB);
            try (TestExecutor executor = f.executor()) {
                Object result = f.full(executor, ErDataTransfer.DIRECT_IMPORT_JDBC_CATALOG);
                f.assertOriginalTextWritten(result);
                f.assertNoRemoteDriverImport();
            }
        }
    }

    @Test
    public void regularFullImportsAlsoDecodeByteArraysAndJdbcBlobValues() throws Exception {
        for (boolean blobObject : new boolean[]{false, true}) {
            Fixture f = new Fixture("MYSQL", Types.BLOB);
            f.blobObjects = blobObject;
            try (TestExecutor executor = f.executor()) {
                Object result = f.full(executor, null);
                f.assertOriginalTextWritten(result);
                assertFalse(f.messages.toString(), f.messages.stream().anyMatch(message -> message.contains("无需额外兼容驱动")));
            }
        }
    }

    @Test
    public void incrementalLifecyclePreparesColumnConversionAndBindsOriginalStrings() throws Exception {
        for (String sourceType : Arrays.asList("MYSQL", "TIDB")) {
            Fixture f = new Fixture(sourceType, Types.LONGVARBINARY);
            ErDataTransfer task = f.task(null);
            task.setSyncMode("1");
            task.setTimestampField("modified_at");
            task.setSyncInsert("1");
            task.setSyncUpdate("1");
            task.setSyncDelete("0");
            task.setTargetTable("warehouse.invoices");
            Map<String, Timestamp> checkpoints = new HashMap<>();
            checkpoints.put("erp_base.tb_base_e_invoice", Timestamp.valueOf("2026-09-01 00:00:00"));
            try (TestExecutor executor = f.executor()) {
                Object result = invoke(executor, "syncTable", task, f.datasource,
                        "erp_base", "tb_base_e_invoice", f.extractor(), new StarRocksDataSyncLoader(),
                        f.source(), f.target(), 2, null, checkpoints);
                f.assertOriginalTextWritten(result);
                assertFalse(f.targetSql.toString(), f.targetSql.stream().anyMatch(sql -> sql.startsWith("TRUNCATE")));
                assertEquals(MODIFIED, checkpoints.get("erp_base.tb_base_e_invoice"));
                assertEquals(1, f.savedProgress.size());
            }
        }
    }

    @Test
    public void malformedUtf8InALaterRowFailsEveryFullRouteBeforeTruncate() throws Exception {
        for (String method : Arrays.asList(null, ErDataTransfer.DIRECT_IMPORT_JDBC_RESOURCE,
                ErDataTransfer.DIRECT_IMPORT_JDBC_CATALOG)) {
            Fixture f = new Fixture("MYSQL", Types.BLOB);
            f.payloads.set(2, new byte[]{(byte) 0xC3, 0x28});
            f.assertPreclearFailure(method, "UTF-8");
        }
    }

    @Test
    public void utf8ByteCapacityIsCheckedBeforeTruncateOnResourceAndCatalogRoutes() throws Exception {
        for (String method : Arrays.asList(ErDataTransfer.DIRECT_IMPORT_JDBC_RESOURCE,
                ErDataTransfer.DIRECT_IMPORT_JDBC_CATALOG)) {
            Fixture f = new Fixture("TIDB", Types.LONGVARBINARY);
            f.targetTextType = "VARCHAR(3)";
            f.payloads.set(0, "ok".getBytes(StandardCharsets.UTF_8));
            f.payloads.set(1, "中文".getBytes(StandardCharsets.UTF_8));
            f.assertPreclearFailure(method, "容量");
        }
    }

    @Test
    public void mixedBlobTablesKeepUnsignedBit64AndNegativeLongTimeValues() throws Exception {
        for (String sourceType : Arrays.asList("MYSQL", "TIDB")) {
            Fixture f = new Fixture(sourceType, Types.BLOB);
            try (TestExecutor executor = f.executor()) {
                f.full(executor, ErDataTransfer.DIRECT_IMPORT_JDBC_RESOURCE);
                byte[] bits = new byte[8];
                Arrays.fill(bits, (byte) 0xff);
                ResultSet bitRows = proxy(ResultSet.class, (object, method, args) ->
                        "getObject".equals(method.getName()) ? bits : defaultValue(method.getReturnType()));
                assertEquals(new BigInteger("18446744073709551615"), executor.readColumnValue(bitRows, "flags", "BIT"));
                for (Duration time : Arrays.asList(Duration.ofHours(838).plusMinutes(59).plusSeconds(59).plusNanos(123456000),
                        Duration.ofHours(25).plusNanos(123456000).negated(), Duration.ZERO)) {
                    ResultSet timeRows = proxy(ResultSet.class, (object, method, args) -> {
                        if ("getObject".equals(method.getName())) {
                            assertEquals(Duration.class, args[1]);
                            return time;
                        }
                        if ("findColumn".equals(method.getName())) return 1;
                        if ("getMetaData".equals(method.getName())) return proxy(ResultSetMetaData.class,
                                (metadata, query, values) -> "getScale".equals(query.getName()) ? 6 : defaultValue(query.getReturnType()));
                        return defaultValue(method.getReturnType());
                    });
                    String expected = time.isNegative() ? "-25:00:00.123456" : time.isZero() ? "00:00:00.000000" : "838:59:59.123456";
                    assertEquals(expected, executor.readColumnValue(timeRows, "duration", "TIME"));
                }
            }
        }
    }

    private static final class TestExecutor extends StarRocksDataSyncExecutor implements AutoCloseable {
        TestExecutor(ErDataTransferProgressMapper progressMapper) { super(null, null, progressMapper); }
        @Override protected String quoteTargetIdentifier(String identifier) { return "`" + identifier + "`"; }
        @Override public void close() { cancellationContext().close(); }
    }

    private static final class Fixture {
        final ErDatasource datasource = new ErDatasource();
        final int binaryJdbcType;
        final List<byte[]> payloads = new ArrayList<>(Arrays.asList(QR_TEXT.getBytes(StandardCharsets.UTF_8), new byte[0], null));
        final List<String> targetSql = new ArrayList<>();
        final List<String> sourceSql = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        final List<Object[]> writtenRows = new ArrayList<>();
        final List<ErDataTransferProgress> savedProgress = new ArrayList<>();
        String targetTextType = "STRING";
        boolean blobObjects;

        Fixture(String sourceType, int jdbcType) {
            binaryJdbcType = jdbcType;
            datasource.setDatasourceType(sourceType);
            // The normal MySQL driver is sufficient: FE/BE Resource properties are never consulted.
            datasource.setDriverClass("com.mysql.cj.jdbc.Driver");
        }

        TestExecutor executor() {
            ErDataTransferProgressMapper mapper = proxy(ErDataTransferProgressMapper.class, (object, method, args) -> {
                if ("upsert".equals(method.getName())) savedProgress.add((ErDataTransferProgress) args[0]);
                return defaultValue(method.getReturnType());
            });
            return new TestExecutor(mapper);
        }

        ErDataTransfer task(String method) {
            ErDataTransfer task = new ErDataTransfer();
            task.setTransferId(42L);
            task.setSyncMode("0");
            task.setExternalTableEnabled(method == null ? "0" : "1");
            task.setDirectImportMethod(method);
            task.setResourceName("jdbc_normal_mysql");
            task.setCatalogName("normal_mysql_catalog");
            return task;
        }

        DataSyncExtractor extractor() {
            if ("TIDB".equals(datasource.getDatasourceType())) {
                return new TiDbDataSyncExtractor() {
                    @Override public List<String> listColumns(Connection conn, String schema, String table) { return COLUMNS; }
                    @Override public List<String> listPrimaryKeys(Connection conn, String schema, String table) { return KEYS; }
                };
            }
            return new MySqlDataSyncExtractor() {
                @Override public List<String> listColumns(Connection conn, String schema, String table) { return COLUMNS; }
                @Override public List<String> listPrimaryKeys(Connection conn, String schema, String table) { return KEYS; }
            };
        }

        Object full(TestExecutor executor, String method) throws Exception {
            return invoke(executor, "syncFull", task(method), datasource, "erp_base", "tb_base_e_invoice",
                    TARGET, COLUMNS, COLUMNS, KEYS, extractor(), new StarRocksDataSyncLoader(), source(), target(),
                    2, null, true, true, (cn.easyreport.sync.core.SyncProgressCallback)
                            (current, total, message) -> messages.add(message));
        }

        void assertOriginalTextWritten(Object result) throws Exception {
            ErDataTransferLog log = (ErDataTransferLog) field(result, "log");
            assertEquals("0", log.getSyncResult());
            assertEquals(Long.valueOf(3), log.getSuccessCount());
            assertEquals(3, writtenRows.size());
            assertEquals(QR_TEXT, writtenRows.get(0)[1]);
            assertEquals("", writtenRows.get(1)[1]);
            assertNull(writtenRows.get(2)[1]);
            for (Object[] row : writtenRows) assertTrue(row[1] == null || row[1] instanceof String);
            assertEquals("com.mysql.cj.jdbc.Driver", datasource.getDriverClass());
            int clear = events.indexOf("truncate");
            if (clear >= 0) {
                assertTrue(events.toString(), events.indexOf("preflight") >= 0 && events.indexOf("preflight") < clear);
                assertTrue(events.toString(), events.indexOf("insert") > clear);
            }
        }

        void assertNoRemoteDriverImport() {
            assertFalse(targetSql.toString(), targetSql.stream().anyMatch(sql -> sql.startsWith("SHOW RESOURCES")
                    || sql.startsWith("SHOW CREATE CATALOG") || sql.startsWith("CREATE EXTERNAL") || sql.startsWith("DROP TABLE")));
            assertTrue(targetSql.toString(), targetSql.stream().filter(sql -> sql.startsWith("INSERT"))
                    .allMatch(sql -> sql.contains(" VALUES ") && !sql.contains(" SELECT ")));
        }

        void assertPreclearFailure(String method, String messagePart) throws Exception {
            try (TestExecutor executor = executor()) {
                try {
                    full(executor, method);
                    fail("Invalid text must be rejected before clearing the existing target");
                } catch (SQLException expected) {
                    assertTrue(expected.getMessage(), expected.getMessage().contains(messagePart));
                    assertTrue(expected.getMessage(), expected.getMessage().contains("尚未清空目标表"));
                }
                assertFalse(events.toString(), events.contains("truncate"));
                assertTrue(writtenRows.isEmpty());
                assertTrue(savedProgress.isEmpty());
                assertNoRemoteDriverImport();
            }
        }

        Connection source() {
            return proxy(Connection.class, (object, method, args) -> {
                if ("createStatement".equals(method.getName())) {
                    return proxy(Statement.class, (statement, query, values) -> {
                        if ("executeQuery".equals(query.getName())) {
                            String sql = (String) values[0];
                            sourceSql.add(sql);
                            assertTrue("Only metadata belongs on this statement: " + sql, sql.endsWith("WHERE 1=0"));
                            return sourceRows(true);
                        }
                        return defaultValue(query.getReturnType());
                    });
                }
                if ("prepareStatement".equals(method.getName())) {
                    String sql = (String) args[0];
                    sourceSql.add(sql);
                    return proxy(PreparedStatement.class, (statement, query, values) -> {
                        if ("executeQuery".equals(query.getName())) {
                            events.add(sql.startsWith("SELECT *") ? "select" : "preflight");
                            return sourceRows(false);
                        }
                        return defaultValue(query.getReturnType());
                    });
                }
                return defaultValue(method.getReturnType());
            });
        }

        Connection target() {
            return proxy(Connection.class, (object, method, args) -> {
                if ("createStatement".equals(method.getName())) {
                    return proxy(Statement.class, (statement, query, values) -> {
                        if ("executeQuery".equals(query.getName())) {
                            String sql = (String) values[0];
                            targetSql.add(sql);
                            if (sql.equals("DESC " + TARGET)) return description();
                            if (sql.contains("information_schema.COLUMNS")) return emptyRows();
                            throw new AssertionError("Unexpected remote import or metadata query: " + sql);
                        }
                        if ("execute".equals(query.getName())) {
                            String sql = (String) values[0];
                            targetSql.add(sql);
                            if (sql.equals("SET enable_insert_strict = true")) return false;
                            assertEquals("TRUNCATE TABLE " + TARGET, sql);
                            events.add("truncate");
                            return false;
                        }
                        return defaultValue(query.getReturnType());
                    });
                }
                if ("prepareStatement".equals(method.getName())) {
                    String sql = (String) args[0];
                    assertTrue(sql, sql.startsWith("INSERT INTO " + TARGET));
                    Map<Integer, Object> bindings = new TreeMap<>();
                    return proxy(PreparedStatement.class, (statement, query, values) -> {
                        if ("setObject".equals(query.getName())) bindings.put((Integer) values[0], values[1]);
                        if ("executeUpdate".equals(query.getName())) {
                            targetSql.add(sql);
                            events.add("insert");
                            for (int offset = 0; offset < bindings.size(); offset += COLUMNS.size()) {
                                Object[] row = new Object[COLUMNS.size()];
                                for (int index = 0; index < row.length; index++) row[index] = bindings.get(offset + index + 1);
                                writtenRows.add(row);
                            }
                            return bindings.size() / COLUMNS.size();
                        }
                        return defaultValue(query.getReturnType());
                    });
                }
                return defaultValue(method.getReturnType());
            });
        }

        ResultSet sourceRows(boolean metadataOnly) {
            int[] row = {-1};
            return proxy(ResultSet.class, (object, method, args) -> {
                String name = method.getName();
                if ("next".equals(name)) return !metadataOnly && ++row[0] < payloads.size();
                if ("findColumn".equals(name)) return COLUMNS.indexOf(args[0]) + 1;
                if ("getObject".equals(name)) {
                    int column = args[0] instanceof Integer ? (Integer) args[0] - 1 : COLUMNS.indexOf(args[0]);
                    if (column == 0) return row[0] + 1L;
                    if (column == 2) return MODIFIED;
                    byte[] bytes = payloads.get(row[0]);
                    return blobObjects && bytes != null ? new SerialBlob(bytes) : bytes;
                }
                if ("getMetaData".equals(name)) {
                    return proxy(ResultSetMetaData.class, (metadata, query, values) -> {
                        if ("getColumnCount".equals(query.getName())) return COLUMNS.size();
                        if ("getColumnType".equals(query.getName())) {
                            return (Integer) values[0] == 1 ? Types.BIGINT : (Integer) values[0] == 2 ? binaryJdbcType : Types.TIMESTAMP;
                        }
                        if ("getColumnTypeName".equals(query.getName())) {
                            return (Integer) values[0] == 1 ? "BIGINT" : (Integer) values[0] == 2 ? "BLOB" : "DATETIME";
                        }
                        return defaultValue(query.getReturnType());
                    });
                }
                return defaultValue(method.getReturnType());
            });
        }

        ResultSet description() {
            int[] row = {-1};
            return proxy(ResultSet.class, (object, method, args) -> {
                if ("next".equals(method.getName())) return ++row[0] < COLUMNS.size();
                if ("getString".equals(method.getName())) {
                    if ("Field".equals(args[0])) return COLUMNS.get(row[0]);
                    if ("Type".equals(args[0])) return row[0] == 0 ? "BIGINT" : row[0] == 1 ? targetTextType : "DATETIME";
                }
                return defaultValue(method.getReturnType());
            });
        }
    }

    private static ResultSet emptyRows() {
        return proxy(ResultSet.class, (object, method, args) -> defaultValue(method.getReturnType()));
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static Object invoke(DataSyncExecutor executor, String name, Object... arguments) throws Exception {
        for (Method method : DataSyncExecutor.class.getDeclaredMethods()) {
            if (!name.equals(method.getName())) continue;
            method.setAccessible(true);
            try {
                return method.invoke(executor, arguments);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
                throw (Error) failure.getCause();
            }
        }
        throw new AssertionError("Missing executor method: " + name);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(StarRocksBinaryTextExecutorTest.class.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
}
