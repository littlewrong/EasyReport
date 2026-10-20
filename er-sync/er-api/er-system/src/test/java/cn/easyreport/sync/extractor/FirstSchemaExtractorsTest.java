package cn.easyreport.sync.extractor;

import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.model.TableMeta;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(Parameterized.class)
public class FirstSchemaExtractorsTest {

    private static final String FIRST_SCHEMA = "Shard_A_01";
    private static final String SECOND_SCHEMA = "Shard_B_02";
    private static final String TABLE = "Orders_2026";
    private final String databaseType;
    private FixtureDriver driver;
    private SchemaExtractor extractor;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> databases() {
        return Arrays.asList(new Object[][]{{"MYSQL"}, {"STARROCKS"}, {"POSTGRESQL"}});
    }

    public FirstSchemaExtractorsTest(String databaseType) {
        this.databaseType = databaseType;
    }

    @Before
    public void registerFixture() throws SQLException {
        driver = new FixtureDriver(databaseType);
        DriverManager.registerDriver(driver);
        ErDatasource datasource = new ErDatasource();
        datasource.setDriverClass(FixtureDriver.class.getName());
        datasource.setJdbcUrl(driver.url);
        datasource.setUsername("test");
        datasource.setPassword("test");
        switch (databaseType) {
            case "MYSQL": extractor = new MySqlSchemaExtractor(datasource); break;
            case "STARROCKS": extractor = new StarRocksSchemaExtractor(datasource); break;
            default: extractor = new PostgreSqlSchemaExtractor(datasource);
        }
    }

    @After
    public void deregisterFixture() throws SQLException {
        DriverManager.deregisterDriver(driver);
    }

    @Test
    public void previewUsesTwoQueriesAndOneGlobalFirstSchemaWithoutReadingDetails() throws Exception {
        List<TableMeta> tables = extractor.listTableNamesUsingFirstSchema(
            " Shard_B%, Shard_A\\_% , ", " Orders\\_%, Audit% ");

        assertEquals(2, tables.size());
        assertEquals(FIRST_SCHEMA, tables.get(0).getSchema());
        assertEquals(SECOND_SCHEMA, tables.get(1).getSchema());
        assertEquals(TABLE, tables.get(0).getName());
        assertEquals(TABLE, tables.get(1).getName());
        assertTrue(tables.get(0).getColumns().isEmpty());
        assertEquals(2, driver.queries.size());
        assertEquals(Arrays.asList("Shard_B%", "Shard_A\\_%"), driver.queries.get(0).parameters);
        assertTrue(driver.queries.get(0).sql.contains("schema_name LIKE ? OR schema_name LIKE ?"));
        assertTrue(driver.queries.get(0).sql.endsWith("ORDER BY schema_name"));
        Query names = driver.queries.get(1);
        assertEquals(Arrays.asList(FIRST_SCHEMA, "Orders\\_%", "Audit%"), names.parameters);
        assertTrue(names.sql.contains("table_schema=?"));
        assertFalse(names.sql.contains("table_schema LIKE"));
        assertTrue(names.sql.contains("table_name LIKE ? OR table_name LIKE ?"));
        assertTrue(names.sql.endsWith("ORDER BY table_name"));
        assertTrue(driver.metadataScopes.isEmpty());
        assertResourcesClosed();
    }

    @Test
    public void readsOnlyFirstSchemaDetailsAndExpandsIndependentMetadataCopies() throws Exception {
        List<TableMeta> tables = extractor.listTablesUsingFirstSchema("Shard_B%,Shard_A\\_%", "Orders\\_%");

        assertEquals(2, tables.size());
        TableMeta first = tables.get(0);
        TableMeta second = tables.get(1);
        assertEquals(FIRST_SCHEMA, first.getSchema());
        assertEquals(SECOND_SCHEMA, second.getSchema());
        assertEquals(TABLE, second.getName());
        assertEquals(Arrays.asList("Id"), first.getPrimaryKey());
        assertEquals(2, first.getColumns().size());
        assertEquals("Id", first.getColumns().get(0).getName());
        assertFalse(first.getColumns().get(0).isNullable());
        assertEquals("seed", first.getColumns().get(1).getDefaultValue());
        String expectedType = "POSTGRESQL".equals(databaseType) ? "VARCHAR(24)"
            : "STARROCKS".equals(databaseType) ? "DECIMAL(18,2)" : "varchar(24)";
        assertEquals(expectedType, first.getColumns().get(1).getColumnType());
        for (Query details : driver.queries.subList(2, driver.queries.size())) {
            assertEquals(Arrays.asList(FIRST_SCHEMA, TABLE), details.parameters);
        }
        for (String scope : driver.metadataScopes) assertEquals(FIRST_SCHEMA, scope);
        if ("POSTGRESQL".equals(databaseType)) {
            assertTrue(driver.queries.stream().anyMatch(query -> query.sql.contains("quote_ident(?)")));
        } else {
            assertTrue(first.getColumns().get(0).isAutoIncrement());
            assertEquals("label comment", first.getColumns().get(1).getComment());
            assertEquals(1, first.getIndexes().size());
            assertEquals(Arrays.asList("Label"), second.getIndexes().get(0).getColumns());
            first.getIndexes().get(0).getColumns().clear();
            assertEquals(Arrays.asList("Label"), second.getIndexes().get(0).getColumns());
        }
        first.getColumns().get(0).setColumnType("CHANGED");
        first.getColumns().remove(1);
        first.getPrimaryKey().clear();
        assertEquals(2, second.getColumns().size());
        assertNotEquals("CHANGED", second.getColumns().get(0).getColumnType());
        assertEquals(Arrays.asList("Id"), second.getPrimaryKey());
        assertResourcesClosed();
    }

    @Test
    public void blankSchemaPreservesDatabaseSpecificScopeDefaults() throws Exception {
        if ("POSTGRESQL".equals(databaseType)) driver.schemas = Collections.singletonList("public");

        extractor.listTableNamesUsingFirstSchema(" , ", "%");

        assertEquals("POSTGRESQL".equals(databaseType) ? Collections.singletonList("public") : Collections.emptyList(),
            driver.queries.get(0).parameters);
        assertResourcesClosed();
    }

    @Test
    public void emptyScopeReturnsEmptyWithoutLookingForTables() throws Exception {
        driver.schemas = Collections.emptyList();

        assertTrue(extractor.listTablesUsingFirstSchema("missing%", "%").isEmpty());

        assertEquals(1, driver.queries.size());
        assertResourcesClosed();
    }

    @Test
    public void emptyFirstSchemaFailsWithoutTryingTheNextSchema() throws Exception {
        driver.emptyFirstSchema = true;

        try {
            extractor.listTableNamesUsingFirstSchema("Shard%", "%");
            fail("An empty first schema must not silently select a later schema");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(FIRST_SCHEMA));
        }

        assertEquals(2, driver.queries.size());
        assertTrue(driver.metadataScopes.isEmpty());
        assertResourcesClosed();
    }

    @Test
    public void starRocksRetainsPrimaryKeyFallbackWhenBothKeySourcesAreEmpty() throws Exception {
        assumeTrue("STARROCKS".equals(databaseType));
        driver.emptyPrimaryKeys = true;

        List<TableMeta> tables = extractor.listTablesUsingFirstSchema("Shard%", "%");

        assertEquals(Arrays.asList("Id"), tables.get(0).getPrimaryKey());
        assertEquals(Arrays.asList("Id"), tables.get(1).getPrimaryKey());
        assertEquals("DECIMAL(18,2)", tables.get(0).getColumns().get(1).getColumnType());
        assertTrue(driver.queries.stream().anyMatch(query -> query.sql.contains("key_column_usage")));
        assertResourcesClosed();
    }

    private void assertResourcesClosed() {
        assertEquals(1, driver.connectionsClosed);
        assertEquals(driver.statementsOpened, driver.statementsClosed);
        assertEquals(driver.resultSetsOpened, driver.resultSetsClosed);
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            result.put(values[i].toString().toLowerCase(Locale.ROOT), values[i + 1]);
        }
        return result;
    }

    private static class Query {
        private final String sql;
        private final List<String> parameters;

        private Query(String sql, Map<Integer, String> parameters) {
            this.sql = sql;
            this.parameters = new ArrayList<>(parameters.values());
        }
    }

    public static class FixtureDriver implements Driver {
        private final String databaseType;
        private final String url = "jdbc:first-schema-test:" + UUID.randomUUID();
        private final List<Query> queries = new ArrayList<>();
        private final List<String> metadataScopes = new ArrayList<>();
        private List<String> schemas = Arrays.asList(FIRST_SCHEMA, SECOND_SCHEMA);
        private boolean emptyFirstSchema;
        private boolean emptyPrimaryKeys;
        private int connectionsClosed;
        private int statementsOpened;
        private int statementsClosed;
        private int resultSetsOpened;
        private int resultSetsClosed;

        private FixtureDriver(String databaseType) {
            this.databaseType = databaseType;
        }

        @Override
        public Connection connect(String candidate, Properties info) {
            if (!acceptsURL(candidate)) return null;
            return proxy(Connection.class, (connection, method, args) -> {
                switch (method.getName()) {
                    case "prepareStatement": return statement((String) args[0]);
                    case "getMetaData": return metadata();
                    case "close": connectionsClosed++; return null;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        }

        private PreparedStatement statement(String sql) {
            statementsOpened++;
            Map<Integer, String> params = new TreeMap<>();
            return proxy(PreparedStatement.class, (statement, method, args) -> {
                switch (method.getName()) {
                    case "setString": params.put((Integer) args[0], (String) args[1]); return null;
                    case "executeQuery":
                        Query query = new Query(sql, params);
                        queries.add(query);
                        return queryRows(query);
                    case "close": statementsClosed++; return null;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        }

        private ResultSet queryRows(Query query) {
            String sql = query.sql.toLowerCase(Locale.ROOT);
            if (sql.contains("from information_schema.schemata")) {
                List<Map<String, Object>> records = new ArrayList<>();
                for (String schema : schemas) records.add(row("schema_name", schema));
                return rows(records);
            }
            if (sql.startsWith("select table_name from information_schema.tables")) {
                return rows(emptyFirstSchema ? Collections.emptyList() : Collections.singletonList(row("table_name", TABLE)));
            }
            if (sql.contains("from information_schema.tables")) {
                return rows(Collections.singletonList(row("ENGINE", "InnoDB", "TABLE_COLLATION", "utf8mb4_bin", "TABLE_COMMENT", "orders")));
            }
            if (sql.contains("from information_schema.columns")) {
                if ("POSTGRESQL".equals(databaseType)) {
                    return rows(Arrays.asList(
                        row("column_name", "Id", "data_type", "integer", "is_nullable", "NO"),
                        row("column_name", "Label", "data_type", "character varying", "character_maximum_length", 24,
                            "is_nullable", "YES", "column_default", "'seed'::character varying")));
                }
                return rows(Arrays.asList(
                    row("COLUMN_NAME", "Id", "COLUMN_TYPE", "int(11)", "IS_NULLABLE", "NO", "EXTRA", "auto_increment", "ORDINAL_POSITION", 1),
                    row("COLUMN_NAME", "Label", "COLUMN_TYPE", "STARROCKS".equals(databaseType) ? "decimal128(18, 2)" : "varchar(24)",
                        "IS_NULLABLE", "YES", "COLUMN_DEFAULT", "seed", "COLUMN_COMMENT", "label comment", "ORDINAL_POSITION", 2,
                        "CHARACTER_SET_NAME", "utf8mb4", "COLLATION_NAME", "utf8mb4_bin")));
            }
            if (sql.contains("from pg_index")) return rows(Collections.singletonList(row("attname", "Id")));
            if (sql.contains("from information_schema.key_column_usage")) return rows(Collections.emptyList());
            throw new UnsupportedOperationException(query.sql);
        }

        private DatabaseMetaData metadata() {
            return proxy(DatabaseMetaData.class, (metadata, method, args) -> {
                metadataScopes.add((String) args[0]);
                assertEquals(TABLE, args[2]);
                if ("getPrimaryKeys".equals(method.getName())) {
                    return rows(emptyPrimaryKeys ? Collections.emptyList()
                        : Collections.singletonList(row("KEY_SEQ", 1, "COLUMN_NAME", "Id")));
                }
                if ("getIndexInfo".equals(method.getName())) {
                    return rows(Collections.singletonList(row("INDEX_NAME", "idx_label", "NON_UNIQUE", true, "COLUMN_NAME", "Label")));
                }
                throw new UnsupportedOperationException(method.getName());
            });
        }

        private ResultSet rows(List<Map<String, Object>> records) {
            resultSetsOpened++;
            int[] index = {-1};
            return proxy(ResultSet.class, (resultSet, method, args) -> {
                if ("next".equals(method.getName())) return ++index[0] < records.size();
                if ("close".equals(method.getName())) { resultSetsClosed++; return null; }
                Map<String, Object> record = records.get(index[0]);
                Object value = args[0] instanceof Integer ? new ArrayList<>(record.values()).get((Integer) args[0] - 1)
                    : record.get(args[0].toString().toLowerCase(Locale.ROOT));
                switch (method.getName()) {
                    case "getString": return value == null ? null : value.toString();
                    case "getInt": return value == null ? 0 : ((Number) value).intValue();
                    case "getShort": return value == null ? (short) 0 : ((Number) value).shortValue();
                    case "getBoolean": return value != null && (Boolean) value;
                    case "getObject": return value;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        }

        @Override
        public boolean acceptsURL(String candidate) { return url.equals(candidate); }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String candidate, Properties info) { return new DriverPropertyInfo[0]; }

        @Override
        public int getMajorVersion() { return 1; }

        @Override
        public int getMinorVersion() { return 0; }

        @Override
        public boolean jdbcCompliant() { return false; }

        @Override
        public Logger getParentLogger() { return Logger.getGlobal(); }
    }
}
