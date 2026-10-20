package cn.easyreport.sync.core;

import cn.easyreport.sync.domain.ErDataSync;
import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.model.SyncResult;
import org.junit.After;
import org.junit.Before;
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
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

import static org.junit.Assert.*;

public class SchemaSyncExecutorTest {

    private final RecordingDriver driver = new RecordingDriver();

    @Before
    public void registerDriver() throws SQLException {
        DriverManager.registerDriver(driver);
    }

    @After
    public void deregisterDriver() throws SQLException {
        DriverManager.deregisterDriver(driver);
    }

    @Test
    public void opensTargetAfterSourceMetadataIsCollectedAndClosed() {
        SyncResult result = execute();

        assertBefore("source.query", "target.open");
        assertBefore("source.close", "target.open");
        assertBefore("target.open", "target.close");
        assertTrue(driver.events.contains("source.statement.close"));
        assertTrue(driver.events.contains("source.rows.close"));
        assertTrue(driver.sourceSql.contains("table_schema LIKE ?"));
        assertEquals("erp_quality\\_%", driver.parameters.get(1));
        assertEquals("%", driver.parameters.get(2));
        // An empty result exercises connection lifecycle without issuing target DDL.
        assertFalse(result.isSuccess());
        assertEquals(0, result.getTotalCount());
        assertTrue(result.getLogs().isEmpty());
    }

    @Test
    public void neverOpensTargetWhenSourceMetadataQueryFails() {
        driver.failMetadataQuery = true;

        SyncResult result = execute();

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().endsWith("metadata unavailable"));
        assertTrue(driver.events.contains("source.query"));
        assertTrue(driver.events.contains("source.statement.close"));
        assertTrue(driver.events.contains("source.close"));
        assertFalse(driver.events.contains("target.open"));
    }

    @Test
    public void createsBothShardSchemasFromFirstSchemaAndReportsTemplateProgress() {
        driver.shardFixture = true;
        ErDataSync task = new ErDataSync();
        task.setSyncId(1L);
        task.setSourceSchemaPattern("erp_quality\\_%");
        task.setSourceTablePattern("%");
        task.setStructureReuseMode("1");
        task.setIfExistsAction("0");
        List<Integer> currentProgress = new ArrayList<>();
        List<String> progressMessages = new ArrayList<>();

        SyncResult result = new SchemaSyncExecutor().execute(task,
            datasource("TIDB", "source"), datasource("STARROCKS", "target"),
            (current, total, message) -> {
                assertEquals(2, total);
                currentProgress.add(current);
                progressMessages.add(message);
            });

        assertTrue(result.getMessage(), result.isSuccess());
        assertEquals(2, result.getTotalCount());
        assertEquals(2, result.getSuccessCount());
        assertEquals("erp_quality_001", result.getTemplateSchema());
        assertEquals(2, result.getLogs().size());
        for (int index = 0; index < 2; index++) {
            String schema = "erp_quality_00" + (index + 1);
            String ddl = result.getLogs().get(index).getExecuteSql();
            assertTrue(ddl, ddl.startsWith("CREATE TABLE IF NOT EXISTS `" + schema + "`.`orders`"));
            assertTrue(ddl, ddl.contains("`id` INT NOT NULL"));
            assertTrue(ddl, ddl.contains("PRIMARY KEY (`id`)"));
            assertTrue(driver.targetSql.contains(ddl));
            assertEquals(schema + ".orders", result.getLogs().get(index).getSourceTable());
            assertEquals(schema + ".orders", result.getLogs().get(index).getTargetTable());
        }
        assertEquals(2, driver.targetSql.stream().filter(sql -> sql.startsWith("CREATE TABLE")).count());
        assertEquals(Arrays.asList(0, 1, 2), currentProgress);
        for (String message : progressMessages) {
            assertTrue(message, message.contains("erp_quality_001"));
        }
        assertBefore("source.close", "target.open");
        assertEquals(4, Collections.frequency(driver.events, "source.query"));
    }

    @Test
    public void createsSeparateStarRocksDatabasesForMySqlShards() {
        driver.shardFixture = true;
        driver.sourceType = "MYSQL";

        SyncResult result = executeShards("MYSQL", "erp_quality\\_%");

        assertShardResult(result, Arrays.asList("erp_quality_001", "erp_quality_002"));
        assertBefore("source.close", "target.open");
        assertTrue(driver.events.contains("source.metadata.getPrimaryKeys"));
    }

    @Test
    public void groupsSqlServerShardsByCatalogInsteadOfDboSchema() {
        driver.shardFixture = true;
        driver.sourceType = "SQLSERVER";

        SyncResult result = executeShards("SQLSERVER", "erp\\_%");

        assertShardResult(result, Arrays.asList("erp_a", "erp_b"));
        assertBefore("source.close", "target.open");
        assertTrue(driver.events.contains("source.catalog.erp_a"));
        assertFalse(driver.events.contains("source.catalog.erp_b"));
        assertTrue(result.getPendingTables().isEmpty());
        assertEquals(Arrays.asList("erp_a.orders", "erp_b.orders"), result.getCompletedTables());
        for (String sql : driver.targetSql) assertFalse(sql, sql.contains("`dbo`"));
    }

    private SyncResult executeShards(String sourceType, String schemaPattern) {
        ErDataSync task = new ErDataSync();
        task.setSyncId(1L);
        task.setSourceSchemaPattern(schemaPattern);
        task.setSourceTablePattern("%");
        task.setStructureReuseMode("1");
        task.setIfExistsAction("0");
        return new SchemaSyncExecutor().execute(task,
                datasource(sourceType, "source"), datasource("STARROCKS", "target"));
    }

    private void assertShardResult(SyncResult result, List<String> scopes) {
        assertTrue(result.getMessage(), result.isSuccess());
        assertEquals(2, result.getTotalCount());
        assertEquals(2, result.getSuccessCount());
        assertEquals(scopes.get(0), result.getTemplateSchema());
        assertEquals(2, result.getLogs().size());
        for (int i = 0; i < scopes.size(); i++) {
            String scope = scopes.get(i);
            String ddl = result.getLogs().get(i).getExecuteSql();
            assertTrue(ddl, ddl.startsWith("CREATE TABLE IF NOT EXISTS `" + scope + "`.`orders`"));
            assertTrue(ddl, ddl.contains("`id` INT NOT NULL"));
            assertTrue(ddl, ddl.contains("PRIMARY KEY (`id`)"));
            assertTrue(driver.targetSql.contains(ddl));
            assertEquals(scope + ".orders", result.getLogs().get(i).getSourceTable());
            assertEquals(scope + ".orders", result.getLogs().get(i).getTargetTable());
            assertTrue(driver.targetSql.toString(), driver.targetSql.stream().anyMatch(sql ->
                    sql.startsWith("CREATE DATABASE") && sql.contains("`" + scope + "`")));
        }
        assertEquals(2, driver.targetSql.stream().filter(sql -> sql.startsWith("CREATE TABLE")).count());
    }

    private SyncResult execute() {
        ErDataSync task = new ErDataSync();
        task.setSyncId(1L);
        task.setSourceSchemaPattern("erp_quality\\_%");
        task.setSourceTablePattern("%");
        return new SchemaSyncExecutor().execute(task,
                datasource("TIDB", "source"), datasource("STARROCKS", "target"));
    }

    private ErDatasource datasource(String databaseType, String role) {
        ErDatasource datasource = new ErDatasource();
        datasource.setDatasourceType(databaseType);
        datasource.setDriverClass(RecordingDriver.class.getName());
        datasource.setJdbcUrl(RecordingDriver.URL_PREFIX + role);
        datasource.setUsername("test");
        datasource.setPassword("test");
        return datasource;
    }

    private void assertBefore(String earlier, String later) {
        assertTrue(earlier + " missing: " + driver.events, driver.events.contains(earlier));
        assertTrue(later + " missing: " + driver.events, driver.events.contains(later));
        assertTrue(driver.events.toString(), driver.events.indexOf(earlier) < driver.events.indexOf(later));
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    public static class RecordingDriver implements Driver {
        private static final String URL_PREFIX = "jdbc:schema-sync-test:";
        private final List<String> events = new ArrayList<>();
        private final Map<Integer, String> parameters = new HashMap<>();
        private final List<String> targetSql = new ArrayList<>();
        private String sourceSql;
        private boolean failMetadataQuery;
        private boolean shardFixture;
        private String sourceType = "TIDB";

        @Override
        public Connection connect(String url, Properties info) {
            if (!acceptsURL(url)) return null;
            String role = url.substring(URL_PREFIX.length());
            events.add(role + ".open");
            boolean[] closed = {false};
            return proxy(Connection.class, (connection, method, args) -> {
                if ("close".equals(method.getName())) {
                    closed[0] = true;
                    events.add(role + ".close");
                    return null;
                }
                if ("prepareStatement".equals(method.getName()) && "source".equals(role)) {
                    sourceSql = (String) args[0];
                    return sourceStatement();
                }
                if (shardFixture && "source".equals(role)) {
                    if ("getMetaData".equals(method.getName())) return sourceMetadata();
                    if ("setCatalog".equals(method.getName())) {
                        assertEquals("erp_a", args[0]);
                        events.add("source.catalog." + args[0]);
                        return null;
                    }
                }
                if (shardFixture && "target".equals(role)) {
                    switch (method.getName()) {
                        case "isClosed": return closed[0];
                        case "isValid": return !closed[0];
                        case "getMetaData":
                            return proxy(DatabaseMetaData.class, (metadata, metadataMethod, metadataArgs) -> {
                                if ("getTables".equals(metadataMethod.getName())) {
                                    return rows(Collections.emptyList(), "target");
                                }
                                throw new UnsupportedOperationException(metadataMethod.getName());
                            });
                        case "createStatement":
                            return proxy(Statement.class, (statement, statementMethod, statementArgs) -> {
                                if ("execute".equals(statementMethod.getName())) {
                                    targetSql.add((String) statementArgs[0]);
                                    return false;
                                }
                                if ("close".equals(statementMethod.getName())) return null;
                                throw new UnsupportedOperationException(statementMethod.getName());
                            });
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                }
                throw new UnsupportedOperationException(role + ": " + method.getName());
            });
        }

        private PreparedStatement sourceStatement() {
            String sql = sourceSql;
            Map<Integer, String> statementParameters = new HashMap<>();
            return proxy(PreparedStatement.class, (statement, method, args) -> {
                switch (method.getName()) {
                    case "setString":
                        parameters.put((Integer) args[0], (String) args[1]);
                        statementParameters.put((Integer) args[0], (String) args[1]);
                        return null;
                    case "executeQuery":
                        events.add("source.query");
                        if (failMetadataQuery) throw new SQLException("metadata unavailable");
                        if (shardFixture) return shardRows(sql, statementParameters);
                        return proxy(ResultSet.class, (rows, resultMethod, resultArgs) -> {
                            if ("next".equals(resultMethod.getName())) return false;
                            if ("close".equals(resultMethod.getName())) {
                                events.add("source.rows.close");
                                return null;
                            }
                            throw new UnsupportedOperationException(resultMethod.getName());
                        });
                    case "close":
                        events.add("source.statement.close");
                        return null;
                    default:
                        throw new UnsupportedOperationException(method.getName());
                }
            });
        }

        private ResultSet shardRows(String sql, Map<Integer, String> statementParameters) {
            String query = sql.toLowerCase(Locale.ROOT);
            if ("SQLSERVER".equals(sourceType)) {
                if (query.contains("from sys.databases")) {
                    assertEquals("erp\\_%", statementParameters.get(1));
                    return rows(Arrays.asList(row("name", "erp_a"), row("name", "erp_b")), "source");
                }
                if (query.contains("from information_schema.tables")) {
                    assertEquals("erp_a", statementParameters.get(1));
                    assertEquals("%", statementParameters.get(2));
                    return rows(Collections.singletonList(row("TABLE_NAME", "orders")), "source");
                }
                if (query.contains("from sys.tables")) {
                    assertEquals("dbo", statementParameters.get(1));
                    assertEquals("orders", statementParameters.get(2));
                    return rows(Collections.singletonList(row("REMARKS", "Orders")), "source");
                }
                throw new UnsupportedOperationException(sql);
            }
            if (query.contains("from information_schema.schemata")) {
                assertEquals("erp_quality\\_%", statementParameters.get(1));
                return rows(Arrays.asList(row("schema_name", "erp_quality_001"),
                    row("schema_name", "erp_quality_002")), "source");
            }
            // Only the first matching schema supplies table, column and key metadata.
            assertEquals("erp_quality_001", statementParameters.get(1));
            assertEquals("MYSQL".equals(sourceType) && !query.contains("table_name like")
                    ? "orders" : "%", statementParameters.get(2));
            Map<String, Object> record = row("table_schema", "erp_quality_001", "table_name", "orders");
            if (query.contains("from information_schema.tables")) {
                record.putAll(row("ENGINE", "InnoDB", "TABLE_COLLATION", "utf8mb4_bin", "TABLE_COMMENT", "Orders"));
            } else if (query.contains("from information_schema.columns")) {
                record.putAll(row("COLUMN_NAME", "id", "COLUMN_TYPE", "int(11)", "IS_NULLABLE", "NO",
                    "EXTRA", "", "ORDINAL_POSITION", 1));
            } else if (query.contains("from information_schema.statistics")) {
                record.putAll(row("INDEX_NAME", "PRIMARY", "NON_UNIQUE", false, "COLUMN_NAME", "id", "SEQ_IN_INDEX", 1));
            } else {
                throw new UnsupportedOperationException(sql);
            }
            return rows(Collections.singletonList(record), "source");
        }

        private DatabaseMetaData sourceMetadata() {
            return proxy(DatabaseMetaData.class, (metadata, method, args) -> {
                String name = method.getName();
                if ("getSearchStringEscape".equals(name)) return "\\";
                events.add("source.metadata." + name);
                assertEquals("SQLSERVER".equals(sourceType) ? "erp_a" : "erp_quality_001", args[0]);
                assertEquals("SQLSERVER".equals(sourceType) ? "dbo" : null, args[1]);
                assertEquals("orders", args[2]);
                switch (name) {
                    case "getColumns":
                        return rows(Collections.singletonList(row("COLUMN_NAME", "id", "TYPE_NAME", "int",
                                "COLUMN_SIZE", 10, "DECIMAL_DIGITS", 0, "IS_NULLABLE", "NO",
                                "IS_AUTOINCREMENT", "NO", "ORDINAL_POSITION", 1)), "source");
                    case "getPrimaryKeys":
                        return rows(Collections.singletonList(row("COLUMN_NAME", "id", "KEY_SEQ", 1,
                                "PK_NAME", "PK_orders")), "source");
                    case "getIndexInfo": return rows(Collections.emptyList(), "source");
                    default: throw new UnsupportedOperationException(name);
                }
            });
        }

        private Map<String, Object> row(Object... fields) {
            Map<String, Object> record = new HashMap<>();
            for (int index = 0; index < fields.length; index += 2) {
                record.put(fields[index].toString().toLowerCase(Locale.ROOT), fields[index + 1]);
            }
            return record;
        }

        private ResultSet rows(List<Map<String, Object>> records, String role) {
            int[] index = {-1};
            return proxy(ResultSet.class, (resultSet, method, args) -> {
                if ("next".equals(method.getName())) return ++index[0] < records.size();
                if ("close".equals(method.getName())) {
                    events.add(role + ".rows.close");
                    return null;
                }
                Object value = records.get(index[0]).get(args[0].toString().toLowerCase(Locale.ROOT));
                switch (method.getName()) {
                    case "getString": return value == null ? null : value.toString();
                    case "getInt": return value == null ? 0 : ((Number) value).intValue();
                    case "getShort": return value == null ? (short) 0 : ((Number) value).shortValue();
                    case "getBoolean": return value != null && (Boolean) value;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        }

        @Override
        public boolean acceptsURL(String url) {
            return url != null && url.startsWith(URL_PREFIX);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() { return 1; }

        @Override
        public int getMinorVersion() { return 0; }

        @Override
        public boolean jdbcCompliant() { return false; }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
    }
}
