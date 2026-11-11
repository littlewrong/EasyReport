package cn.easyreport.sync.datasync;

import cn.easyreport.sync.domain.ErDataTransfer;
import cn.easyreport.sync.domain.ErDataTransferLog;
import cn.easyreport.sync.domain.ErDataTransferProgress;
import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.mapper.ErDataTransferLogMapper;
import cn.easyreport.sync.mapper.ErDataTransferMapper;
import cn.easyreport.sync.mapper.ErDataTransferProgressMapper;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.Assert.*;

/** Exercises ordinary JDBC sync through the production factory, executor, extractors and loader. */
public class DataSyncTargetNamespaceTest {
    @Test
    public void schemaSourcesUseMatchingSourceSchemaForFullAndIncrementalWrites() throws Exception {
        for (String type : Arrays.asList("DM", "KINGBASE", "POSTGRESQL")) {
            for (String mode : Arrays.asList("0", "1")) {
                Fixture f = new Fixture(type, mode);
                DataSyncExecutor.DataSyncResult result = f.execute();
                assertSuccessfulReadWriteAndCheckpoint(f, result, "test", "Orders");
                assertEquals(type + ": full sync clears the resolved target only", "0".equals(mode) ? 1 : 0,
                        f.mutations.stream().filter(sql -> sql.startsWith("TRUNCATE TABLE `test`.`Orders`")).count());
                assertTrue("The target connection's default database cannot redirect a source schema",
                        f.mutations.stream().noneMatch(sql -> sql.contains("`report_db`")));
                if ("1".equals(mode)) {
                    assertEquals("Per-table source checkpoint must win over the task-wide timestamp",
                            f.checkpoint, f.dataQueries.get(0).parameters.get(0));
                }
            }
        }
    }

    @Test
    public void explicitQualifiedTargetTableWinsEvenWithoutAConnectionDefaultDatabase() throws Exception {
        for (String type : Arrays.asList("DM", "KINGBASE", "POSTGRESQL")) {
            for (String mode : Arrays.asList("0", "1")) {
                Fixture f = new Fixture(type, mode);
                f.targetCatalog = null;
                f.task.setTargetTable("warehouse.RenamedOrders");
                assertSuccessfulReadWriteAndCheckpoint(f, f.execute(), "warehouse", "RenamedOrders");
            }
        }
    }

    @Test
    public void absentTargetConnectionDefaultDatabaseDoesNotChangeSourceSchemaMapping() throws Exception {
        for (String type : Arrays.asList("DM", "KINGBASE", "POSTGRESQL")) {
            for (String mode : Arrays.asList("0", "1")) {
                for (String catalog : Arrays.asList(null, "")) {
                    Fixture f = new Fixture(type, mode);
                    f.targetCatalog = catalog;
                    assertSuccessfulReadWriteAndCheckpoint(f, f.execute(), "test", "Orders");
                }
            }
        }
    }

    @Test
    public void matchingTableNamesInDifferentSourceSchemasWriteSeparateDatabasesAndCheckpoints() throws Exception {
        for (String type : Arrays.asList("DM", "KINGBASE", "POSTGRESQL")) {
            for (String mode : Arrays.asList("0", "1")) {
                Fixture f = new Fixture(type, mode);
                f.task.setSourceSchemaPattern("test,archive");
                f.tables.add(new String[]{"archive", "Orders"});
                DataSyncExecutor.DataSyncResult result = f.execute();
                assertTrue(type + " " + mode + ": " + result.getMessage(), result.isSuccess());
                assertEquals(2, result.getCompletedTables().size());
                assertTrue(result.getCompletedTables().containsAll(Arrays.asList("test.Orders", "archive.Orders")));
                assertEquals(2, f.dataQueries.size());
                assertEquals(2, f.inserts.size());
                assertEquals(2, f.savedProgress.size());
                assertEquals(2, result.getLogs().size());
                for (String schema : Arrays.asList("test", "archive")) {
                    Query source = f.dataQueries.stream().filter(query -> query.sql.contains("FROM \"" + schema + "\".\"Orders\""))
                            .findFirst().orElseThrow(() -> new AssertionError("Missing source scan for " + schema));
                    Query insert = f.inserts.stream().filter(query -> query.sql.startsWith("INSERT INTO `" + schema + "`.`Orders`"))
                            .findFirst().orElseThrow(() -> new AssertionError("Missing target write for " + schema));
                    assertEquals("Each schema's actual rows must reach its own target database", f.rowParameters(schema), insert.parameters);
                    if ("1".equals(mode)) assertEquals("Each source table uses its own checkpoint", f.checkpoint(schema), source.parameters.get(0));
                    assertEquals("Each full-sync target is cleared separately", "0".equals(mode) ? 1 : 0,
                            f.mutations.stream().filter(sql -> sql.startsWith("TRUNCATE TABLE `" + schema + "`.`Orders`")).count());
                    ErDataTransferProgress saved = f.savedProgress.stream().filter(progress -> (schema + ".Orders").equals(progress.getSourceTable()))
                            .findFirst().orElseThrow(() -> new AssertionError("Missing checkpoint for " + schema));
                    assertEquals(String.valueOf(f.lastRow(schema).getTime()), saved.getLastSyncValue());
                    ErDataTransferLog log = result.getLogs().stream()
                            .filter(entry -> (schema + ".Orders").equals(entry.getSourceTable())).findFirst()
                            .orElseThrow(() -> new AssertionError("Missing successful source log for " + schema));
                    assertEquals("0", log.getSyncResult());
                    assertEquals(schema + ".Orders", log.getTargetTable().replace("`", ""));
                    assertEquals(Long.valueOf(2L), log.getSuccessCount());
                }
                assertTrue(f.mutations.stream().noneMatch(sql -> sql.contains("`report_db`")));
            }
        }
    }

    @Test
    public void mysqlAndTidbKeepTheirExistingSourceDatabaseMapping() throws Exception {
        for (String type : Arrays.asList("MYSQL", "TIDB")) {
            for (String mode : Arrays.asList("0", "1")) {
                Fixture f = new Fixture(type, mode);
                assertSuccessfulReadWriteAndCheckpoint(f, f.execute(), "test", "Orders");
            }
        }
    }

    private static void assertSuccessfulReadWriteAndCheckpoint(Fixture f, DataSyncExecutor.DataSyncResult result,
            String database, String table) {
        assertTrue(f.sourceType + " " + f.task.getSyncMode() + ": " + result.getMessage(), result.isSuccess());
        assertFalse(result.isStopped());
        assertEquals(Collections.singletonList("test.Orders"), result.getCompletedTables());
        assertEquals(1, f.dataQueries.size());
        String sourceTable = Arrays.asList("MYSQL", "TIDB").contains(f.sourceType) ? "`test`.`Orders`" : "\"test\".\"Orders\"";
        assertTrue(f.dataQueries.get(0).sql, f.dataQueries.get(0).sql.contains("FROM " + sourceTable));
        assertEquals(1, f.inserts.size());
        Query insert = f.inserts.get(0);
        assertTrue(insert.sql, insert.sql.startsWith("INSERT INTO `" + database + "`.`" + table + "`"));
        assertEquals("A real two-row batch should bind every source value",
                Arrays.asList(1L, f.firstRow, 2L, f.lastRow), insert.parameters);
        assertTrue("Schema inspection uses the resolved target database", f.targetQueries.stream()
                .anyMatch(sql -> sql.contains("TABLE_SCHEMA = '" + database + "'") && sql.contains("TABLE_NAME = '" + table + "'")));
        assertEquals(1, f.savedProgress.size());
        assertEquals("The checkpoint key remains in the source namespace", "test.Orders", f.savedProgress.get(0).getSourceTable());
        assertEquals(String.valueOf(f.lastRow.getTime()), f.savedProgress.get(0).getLastSyncValue());
        assertEquals("test.Orders", result.getLogs().get(0).getSourceTable());
        assertEquals("0", result.getLogs().get(0).getSyncResult());
        assertEquals(database + "." + table, result.getLogs().get(0).getTargetTable().replace("`", ""));
        assertEquals(1, f.sourceCloses);
        assertEquals(1, f.targetCloses);
    }

    public static final class NamespaceDriver implements Driver {
        private final Fixture fixture;
        private final String prefix = "jdbc:easyreport-namespace-test:" + UUID.randomUUID() + ":";
        NamespaceDriver(Fixture fixture) { this.fixture = fixture; }
        @Override public Connection connect(String url, Properties info) { return acceptsURL(url) ? fixture.connection(url.endsWith("source")) : null; }
        @Override public boolean acceptsURL(String url) { return url != null && url.startsWith(prefix); }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
        @Override public int getMajorVersion() { return 1; }
        @Override public int getMinorVersion() { return 0; }
        @Override public boolean jdbcCompliant() { return false; }
        @Override public Logger getParentLogger() { return Logger.getLogger("namespace-test"); }
    }

    private static final class Query {
        final String sql;
        final List<Object> parameters;
        Query(String sql, List<Object> parameters) { this.sql = sql; this.parameters = new ArrayList<>(parameters); }
    }

    private static final class Fixture {
        final String sourceType;
        final ErDataTransfer task = new ErDataTransfer();
        final List<String[]> tables = new ArrayList<>();
        final List<Query> dataQueries = new ArrayList<>();
        final List<Query> inserts = new ArrayList<>();
        final List<String> mutations = new ArrayList<>();
        final List<String> targetQueries = new ArrayList<>();
        final List<ErDataTransferProgress> savedProgress = new ArrayList<>();
        final Timestamp checkpoint = Timestamp.valueOf("2026-11-11 09:00:00");
        final Timestamp firstRow = Timestamp.valueOf("2026-11-11 10:00:00");
        final Timestamp lastRow = Timestamp.valueOf("2026-11-11 11:00:00");
        final Timestamp archiveCheckpoint = Timestamp.valueOf("2026-11-11 09:30:00");
        final Timestamp archiveFirstRow = Timestamp.valueOf("2026-11-11 12:00:00");
        final Timestamp archiveLastRow = Timestamp.valueOf("2026-11-11 13:00:00");
        String targetCatalog = "report_db";
        int sourceCloses, targetCloses;

        Fixture(String sourceType, String mode) {
            this.sourceType = sourceType;
            tables.add(new String[]{"test", "Orders"});
            task.setTransferId(617L);
            task.setSourceSchemaPattern("test");
            task.setSourceTable("Orders");
            task.setSyncMode(mode);
            task.setExternalTableEnabled("0");
            task.setSyncInsert("1"); task.setSyncUpdate("1"); task.setSyncDelete("0");
            task.setBatchSize(2);
            task.setTimestampField("modified_at");
            task.setExternalTableWindowUnit("DAY");
            task.setLastSyncValue(String.valueOf(Timestamp.valueOf("2026-11-11 08:00:00").getTime()));
        }

        DataSyncExecutor.DataSyncResult execute() throws Exception {
            NamespaceDriver driver = new NamespaceDriver(this);
            DriverManager.registerDriver(driver);
            try {
                ErDatasource source = datasource(sourceType, driver.prefix + "source");
                ErDatasource target = datasource("STARROCKS", driver.prefix + "target");
                ErDataTransferMapper transfer = proxy(ErDataTransferMapper.class, (p, m, a) -> defaultValue(m.getReturnType()));
                ErDataTransferLogMapper logs = proxy(ErDataTransferLogMapper.class, (p, m, a) -> {
                    if ("selectErDataTransferLogByTransferId".equals(m.getName())) return Collections.emptyList();
                    return defaultValue(m.getReturnType());
                });
                ErDataTransferProgressMapper progress = proxy(ErDataTransferProgressMapper.class, (p, m, a) -> {
                    if ("selectByTransferId".equals(m.getName())) {
                        List<ErDataTransferProgress> previous = new ArrayList<>();
                        for (String[] table : tables) {
                            ErDataTransferProgress saved = new ErDataTransferProgress();
                            saved.setSourceTable(table[0] + "." + table[1]);
                            saved.setLastSyncValue(String.valueOf(checkpoint(table[0]).getTime()));
                            previous.add(saved);
                        }
                        return previous;
                    }
                    if ("upsert".equals(m.getName())) savedProgress.add((ErDataTransferProgress) a[0]);
                    return defaultValue(m.getReturnType());
                });
                DataSyncExecutor executor = DataSyncExecutorFactory.create(target, transfer, logs, progress);
                assertTrue("Use the production StarRocks executor", executor instanceof StarRocksDataSyncExecutor);
                return executor.execute(task, source, target, null, () -> false);
            } finally { DriverManager.deregisterDriver(driver); }
        }

        Connection connection(boolean source) {
            return proxy(Connection.class, (p, m, a) -> {
                switch (m.getName()) {
                    case "getCatalog": return source ? "source_business" : targetCatalog;
                    case "getSchema": return source ? "test" : targetCatalog;
                    case "getMetaData": return metadata((Connection) p);
                    case "isValid": return true;
                    case "isClosed": return false;
                    case "createStatement": return statement(source, "");
                    case "prepareStatement": return statement(source, (String) a[0]);
                    case "close": if (source) sourceCloses++; else targetCloses++; return null;
                    case "setAutoCommit": case "commit": case "rollback": return null;
                    default: return defaultValue(m.getReturnType());
                }
            });
        }

        DatabaseMetaData metadata(Connection source) {
            return proxy(DatabaseMetaData.class, (p, m, a) -> {
                switch (m.getName()) {
                    case "getConnection": return source;
                    case "getSearchStringEscape": return "\\";
                    case "getTables":
                        List<Map<String, Object>> discovered = new ArrayList<>();
                        for (String[] table : tables) if (a[1] == null || "%".equals(a[1]) || table[0].equals(a[1])) {
                            discovered.add(row("TABLE_SCHEM", table[0], "TABLE_NAME", table[1]));
                        }
                        return rows(discovered);
                    case "getColumns": return rows(Arrays.asList(
                            row("COLUMN_NAME", "id", "ORDINAL_POSITION", 1),
                            row("COLUMN_NAME", "modified_at", "ORDINAL_POSITION", 2)));
                    case "getPrimaryKeys": return rows(Collections.singletonList(row("COLUMN_NAME", "id", "KEY_SEQ", 1)));
                    default: return defaultValue(m.getReturnType());
                }
            });
        }

        PreparedStatement statement(boolean source, String preparedSql) {
            List<Object> parameters = new ArrayList<>();
            return proxy(PreparedStatement.class, (p, m, a) -> {
                String name = m.getName();
                if (name.startsWith("set") && a != null && a.length > 1 && a[0] instanceof Integer) {
                    int index = (Integer) a[0];
                    while (parameters.size() < index) parameters.add(null);
                    parameters.set(index - 1, a[1]);
                }
                if (name.startsWith("execute")) {
                    String sql = preparedSql.isEmpty() && a != null && a.length > 0 ? String.valueOf(a[0]) : preparedSql;
                    if ("executeQuery".equals(name)) return query(source, sql, parameters);
                    if (!source && sql.matches("(?is)^(?:ALTER|TRUNCATE|INSERT|UPDATE|DELETE|CREATE|DROP)\\b.*")) mutations.add(sql);
                    if (!source && sql.startsWith("INSERT")) inserts.add(new Query(sql, parameters));
                    if (m.getReturnType() == Boolean.TYPE) return true;
                    if (m.getReturnType() == Integer.TYPE) return 2;
                    if (m.getReturnType() == int[].class) return new int[]{1, 1};
                }
                return defaultValue(m.getReturnType());
            });
        }

        ResultSet query(boolean source, String sql, List<Object> parameters) {
            if (!source) {
                targetQueries.add(sql);
                if (sql.contains("information_schema.COLUMNS")) return rows(Collections.emptyList());
                throw new AssertionError("Unexpected target query: " + sql);
            }
            if (sql.startsWith("SELECT *")) {
                dataQueries.add(new Query(sql, parameters));
                return dataRows(sourceSchema(sql));
            }
            if (sql.startsWith("SELECT MIN(")) {
                String schema = sourceSchema(sql);
                return rows(Collections.singletonList(row("minimum", firstRow(schema), "maximum", lastRow(schema))));
            }
            if (sql.contains("information_schema.tables")) {
                List<Map<String, Object>> discovered = new ArrayList<>();
                for (String[] table : tables) discovered.add(row("table_schema", table[0], "table_name", table[1]));
                return rows(discovered);
            }
            if (sql.contains("information_schema.columns")) return rows(Arrays.asList(row("column_name", "id"), row("column_name", "modified_at")));
            if (sql.contains("information_schema.key_column_usage")) return rows(Collections.singletonList(row("column_name", "id")));
            throw new AssertionError("Unexpected source query: " + sql);
        }

        ResultSet dataRows(String schema) {
            List<Object> parameters = rowParameters(schema);
            ResultSet data = rows(Arrays.asList(row("id", parameters.get(0), "modified_at", parameters.get(1)),
                    row("id", parameters.get(2), "modified_at", parameters.get(3))));
            return proxy(ResultSet.class, (p, m, a) -> {
                if ("getMetaData".equals(m.getName())) return proxy(ResultSetMetaData.class, (mp, mm, ma) -> {
                    if ("getColumnTypeName".equals(mm.getName())) return (Integer) ma[0] == 1 ? "BIGINT" : "TIMESTAMP";
                    return defaultValue(mm.getReturnType());
                });
                try { return m.invoke(data, a); }
                catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
            });
        }

        String sourceSchema(String sql) { return sql.contains("\"archive\".") || sql.contains("`archive`.") ? "archive" : "test"; }
        Timestamp checkpoint(String schema) { return "archive".equals(schema) ? archiveCheckpoint : checkpoint; }
        Timestamp firstRow(String schema) { return "archive".equals(schema) ? archiveFirstRow : firstRow; }
        Timestamp lastRow(String schema) { return "archive".equals(schema) ? archiveLastRow : lastRow; }
        List<Object> rowParameters(String schema) {
            long firstId = "archive".equals(schema) ? 101L : 1L;
            return Arrays.asList(firstId, firstRow(schema), firstId + 1, lastRow(schema));
        }
    }

    private static ErDatasource datasource(String type, String url) {
        ErDatasource ds = new ErDatasource(); ds.setDatasourceType(type); ds.setDriverClass(NamespaceDriver.class.getName());
        ds.setJdbcUrl(url); ds.setUsername("namespace_test"); ds.setPassword("fixture"); return ds;
    }

    private static Map<String, Object> row(Object... fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < fields.length; index += 2) result.put(String.valueOf(fields[index]).toLowerCase(Locale.ROOT), fields[index + 1]);
        return result;
    }

    private static ResultSet rows(List<Map<String, Object>> values) {
        int[] cursor = {-1}; boolean[] wasNull = {false};
        return proxy(ResultSet.class, (p, m, a) -> {
            String name = m.getName();
            if ("next".equals(name)) return ++cursor[0] < values.size();
            if ("close".equals(name)) return null;
            if ("wasNull".equals(name)) return wasNull[0];
            if ("findColumn".equals(name)) {
                int index = 1;
                for (String key : values.get(0).keySet()) { if (key.equalsIgnoreCase(String.valueOf(a[0]))) return index; index++; }
                throw new SQLException("Unknown fixture column", "S0022");
            }
            if (name.startsWith("get") && a != null && a.length > 0) {
                Map<String, Object> row = values.get(cursor[0]);
                Object value = a[0] instanceof Integer ? new ArrayList<>(row.values()).get((Integer) a[0] - 1)
                        : row.get(String.valueOf(a[0]).toLowerCase(Locale.ROOT));
                wasNull[0] = value == null;
                if ("getString".equals(name)) return value == null ? null : value.toString();
                if ("getInt".equals(name)) return value == null ? 0 : ((Number) value).intValue();
                if ("getLong".equals(name)) return value == null ? 0L : ((Number) value).longValue();
                return value;
            }
            return defaultValue(m.getReturnType());
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(DataSyncTargetNamespaceTest.class.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object defaultValue(Class<?> type) {
        if (type == Boolean.TYPE) return false;
        if (type == Integer.TYPE) return 0;
        if (type == Long.TYPE) return 0L;
        if (type == Short.TYPE) return (short) 0;
        if (type == Byte.TYPE) return (byte) 0;
        if (type == Float.TYPE) return 0F;
        if (type == Double.TYPE) return 0D;
        if (type == Character.TYPE) return (char) 0;
        return null;
    }
}
