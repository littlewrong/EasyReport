package cn.easyreport.sync.extractor;

import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.model.ColumnMeta;
import cn.easyreport.sync.model.IndexMeta;
import cn.easyreport.sync.model.TableMeta;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class TiDbSchemaExtractorTest {
    private TestDriver driver;
    private TiDbSchemaExtractor extractor;

    @Before
    public void setup() throws SQLException {
        driver = new TestDriver();
        DriverManager.registerDriver(driver);
        ErDatasource ds = new ErDatasource();
        ds.setDriverClass(TestDriver.class.getName());
        ds.setJdbcUrl(driver.url);
        ds.setUsername("test");
        ds.setPassword("test");
        extractor = new TiDbSchemaExtractor(ds);
    }

    @After
    public void cleanup() throws SQLException {
        DriverManager.deregisterDriver(driver);
    }

    @Test
    public void loads501TablesInThreeQueriesAndClosesEveryResource() throws Exception {
        Set<String> expectedNames = new HashSet<>();
        for (int i = 0; i < 501; i++) {
            String name = String.format(Locale.ROOT, "table_%03d", i);
            expectedNames.add(name);
            addTable("erp_quality_a", name, "table " + i);
            driver.columns.add(column("erp_quality_a", name, "id", 1, "bigint", "NO", null, "auto_increment"));
            driver.statistics.add(index("erp_quality_a", name, "PRIMARY", "id", 1, false));
        }

        List<TableMeta> tables = extractor.listTables("erp_quality\\_%", "%");

        assertEquals(501, tables.size());
        Set<String> actualNames = new HashSet<>();
        for (TableMeta table : tables) {
            actualNames.add(table.getName());
            assertEquals("erp_quality_a", table.getSchema());
            assertEquals(Collections.singletonList("id"), table.getPrimaryKey());
            assertEquals(1, table.getColumns().size());
            assertEquals("id", table.getColumns().get(0).getName());
        }
        assertEquals(expectedNames, actualNames);
        assertEquals(3, driver.queries.size());
        assertPatternQueries(Arrays.asList("erp_quality\\_%", "%"));
        assertResourcesClosed();
    }

    @Test
    public void keepsSameNamedTablesSeparateAndPreservesMetadataAndPatternParameters() throws Exception {
        addTable("erp_quality_a", "orders", "first table");
        addTable("erp_quality_b", "orders", "second table");
        driver.columns.add(column("erp_quality_a", "orders", "id", 1, "bigint unsigned", "NO", null, "auto_increment"));
        driver.columns.add(column("erp_quality_a", "orders", "code", 2, "varchar(64)", "YES", "draft", ""));
        driver.columns.add(column("erp_quality_b", "orders", "other_id", 1, "int(11)", "NO", "0", ""));
        driver.statistics.add(index("erp_quality_a", "orders", "PRIMARY", "code", 2, false));
        driver.statistics.add(index("erp_quality_a", "orders", "PRIMARY", "id", 1, false));
        driver.statistics.add(index("erp_quality_a", "orders", "uk_code_id", "id", 2, false));
        driver.statistics.add(index("erp_quality_a", "orders", "uk_code_id", "code", 1, false));
        driver.statistics.add(index("erp_quality_a", "orders", "idx_code", "code", 1, true));
        driver.statistics.add(index("erp_quality_b", "orders", "PRIMARY", "other_id", 1, false));

        List<TableMeta> tables = extractor.listTables(" erp_quality\\_%, erp_quality_b , , ", " orders, log\\_% , ");

        assertEquals(2, tables.size());
        TableMeta first = find(tables, "erp_quality_a", "orders");
        TableMeta second = find(tables, "erp_quality_b", "orders");
        assertEquals("first table", first.getComment());
        assertEquals("second table", second.getComment());
        assertEquals("InnoDB", first.getEngine());
        assertEquals("utf8mb4", first.getCharset());
        assertEquals(Arrays.asList("id", "code"), first.getPrimaryKey());
        assertEquals(Collections.singletonList("other_id"), second.getPrimaryKey());
        assertEquals(2, first.getColumns().size());
        assertEquals(1, second.getColumns().size());
        ColumnMeta id = first.getColumns().get(0);
        assertEquals("id", id.getName());
        assertEquals("bigint unsigned", id.getColumnType());
        assertFalse(id.isNullable());
        assertNull(id.getDefaultValue());
        assertTrue(id.isAutoIncrement());
        assertEquals(Integer.valueOf(1), id.getOrdinalPosition());
        ColumnMeta code = first.getColumns().get(1);
        assertEquals("varchar(64)", code.getColumnType());
        assertTrue(code.isNullable());
        assertFalse(code.isAutoIncrement());
        assertEquals("draft", code.getDefaultValue());
        assertEquals("comment code", code.getComment());
        assertEquals("utf8mb4", code.getCharacterSet());
        assertEquals("utf8mb4_bin", code.getCollation());
        assertEquals(2, first.getIndexes().size());
        IndexMeta unique = findIndex(first, "uk_code_id");
        assertTrue(unique.isUnique());
        assertEquals(Arrays.asList("code", "id"), unique.getColumns());
        assertFalse(findIndex(first, "idx_code").isUnique());
        assertEquals(Collections.singletonList("code"), findIndex(first, "idx_code").getColumns());
        assertTrue(second.getIndexes().isEmpty());
        assertEquals("other_id", second.getColumns().get(0).getName());
        assertEquals(3, driver.queries.size());
        assertPatternQueries(Arrays.asList("erp_quality\\_%", "erp_quality_b", "orders", "log\\_%"));
        for (Query query : driver.queries) {
            assertTrue(query.sql.contains("table_schema like ? or table_schema like ?"));
            assertTrue(query.sql.contains("table_name like ? or table_name like ?"));
        }
        assertResourcesClosed();
    }

    @Test
    public void singleTableUsesSameMetadataReader() throws Exception {
        addTable("erp_quality_a", "orders", "single");
        driver.columns.add(column("erp_quality_a", "orders", "id", 1, "bigint", "NO", null, "auto_increment"));
        driver.statistics.add(index("erp_quality_a", "orders", "PRIMARY", "id", 1, false));

        TableMeta table = extractor.getTable("erp_quality_a", "orders");

        assertEquals("orders", table.getName());
        assertEquals("erp_quality_a", table.getSchema());
        assertEquals("single", table.getComment());
        assertEquals(Collections.singletonList("id"), table.getPrimaryKey());
        assertTrue(table.getColumns().get(0).isAutoIncrement());
        assertEquals(3, driver.queries.size());
        for (Query query : driver.queries) {
            assertEquals(Arrays.asList("erp_quality_a", "orders"), query.parameters);
            assertTrue(query.sql.matches(".*table_schema\\s*=\\s*\\?.*"));
            assertTrue(query.sql.matches(".*table_name\\s*=\\s*\\?.*"));
            assertFalse(query.sql.contains(" like ?"));
        }
        assertResourcesClosed();
    }

    @Test
    public void emptyMatchSkipsMetadataQueries() throws Exception {
        assertTrue(extractor.listTables("erp_quality\\_%", "%").isEmpty());
        assertEquals(1, driver.queries.size());
        assertResourcesClosed();
    }

    @Test
    public void closesConnectionStatementAndResultSetWhenMetadataReadFails() throws Exception {
        addTable("erp_quality_a", "orders", "failure");
        driver.failColumns = new SQLException("Columns connection lost", "08006");

        try {
            extractor.listTables("erp_quality\\_%", "%");
            fail("Expected SQLException");
        } catch (SQLException e) {
            assertSame(driver.failColumns, e);
        }

        assertEquals(2, driver.queries.size());
        assertResourcesClosed();
    }

    @Test
    public void firstSchemaModeUsesOnlySortedFirstSchemaAndDeepCopiesAllMutableMetadata() throws Exception {
        addTemplateFixture();
        addSchema("actual_c");

        List<TableMeta> tables = extractor.listTablesUsingFirstSchema("actual\\_b, actual\\_a, actual\\_c", "orders, logs\\_%");

        assertEquals(3, tables.size());
        assertEquals(Arrays.asList("actual_a.orders", "actual_b.orders", "actual_c.orders"), tableKeys(tables));
        assertEquals(4, driver.queries.size());
        assertEquals(Arrays.asList("actual\\_b", "actual\\_a", "actual\\_c"), driver.queries.get(0).parameters);
        assertFirstSchemaQueries("actual_a", "orders", "logs\\_%");
        TableMeta first = find(tables, "actual_a", "orders");
        TableMeta second = find(tables, "actual_b", "orders");
        assertEquals("id_a", first.getColumns().get(0).getName());
        assertEquals("id_a", second.getColumns().get(0).getName());
        assertEquals(Collections.singletonList("id_a"), second.getPrimaryKey());
        assertEquals("InnoDB", second.getEngine());
        assertEquals("utf8mb4", second.getCharset());
        assertEquals("first", second.getComment());
        ColumnMeta copiedId = second.getColumns().get(0);
        assertEquals("bigint", copiedId.getColumnType());
        assertFalse(copiedId.isNullable());
        assertTrue(copiedId.isAutoIncrement());
        assertNull(copiedId.getDefaultValue());
        assertEquals("comment id_a", copiedId.getComment());
        assertEquals(Integer.valueOf(1), copiedId.getOrdinalPosition());
        ColumnMeta copiedCode = second.getColumns().get(1);
        assertEquals("varchar(32)", copiedCode.getColumnType());
        assertTrue(copiedCode.isNullable());
        assertFalse(copiedCode.isAutoIncrement());
        assertEquals("draft", copiedCode.getDefaultValue());
        assertEquals("comment code", copiedCode.getComment());
        assertEquals(Integer.valueOf(2), copiedCode.getOrdinalPosition());
        assertEquals("utf8mb4", copiedCode.getCharacterSet());
        assertEquals("utf8mb4_bin", copiedCode.getCollation());
        assertFalse(second.getIndexes().get(0).isUnique());
        for (int i = 0; i < tables.size(); i++) {
            for (int j = i + 1; j < tables.size(); j++) {
                TableMeta left = tables.get(i);
                TableMeta right = tables.get(j);
                assertNotSame(left, right);
                assertNotSame(left.getColumns(), right.getColumns());
                for (int c = 0; c < left.getColumns().size(); c++) assertNotSame(left.getColumns().get(c), right.getColumns().get(c));
                assertNotSame(left.getPrimaryKey(), right.getPrimaryKey());
                assertNotSame(left.getIndexes(), right.getIndexes());
                assertNotSame(left.getIndexes().get(0), right.getIndexes().get(0));
                assertNotSame(left.getIndexes().get(0).getColumns(), right.getIndexes().get(0).getColumns());
            }
        }
        first.getColumns().get(0).setName("changed");
        first.getColumns().clear();
        first.getPrimaryKey().add("changed");
        first.getIndexes().get(0).setName("changed");
        first.getIndexes().get(0).setUnique(true);
        first.getIndexes().get(0).getColumns().clear();
        first.getIndexes().clear();
        assertEquals("id_a", second.getColumns().get(0).getName());
        assertEquals(Collections.singletonList("id_a"), second.getPrimaryKey());
        assertEquals("idx_code", second.getIndexes().get(0).getName());
        assertFalse(second.getIndexes().get(0).isUnique());
        assertEquals(Collections.singletonList("code"), second.getIndexes().get(0).getColumns());
        assertResourcesClosed();
    }

    @Test
    public void previewUsesTwoQueriesAndExpandsFirstSchemasNamesForEveryMatchingSchema() throws Exception {
        addTemplateFixture();
        addSchema("actual_c");
        addTable("actual_a", "logs_a", "second template table");
        driver.failColumns = new SQLException("Preview must not load columns");

        List<TableMeta> tables = extractor.listTableNamesUsingFirstSchema("actual\\_%, unused\\_%", "orders, logs\\_%");

        assertEquals(Arrays.asList("actual_a.logs_a", "actual_a.orders", "actual_b.logs_a", "actual_b.orders",
                "actual_c.logs_a", "actual_c.orders"), tableKeys(tables));
        assertEquals(2, driver.queries.size());
        assertEquals(Arrays.asList("actual\\_%", "unused\\_%"), driver.queries.get(0).parameters);
        assertFirstSchemaQueries("actual_a", "orders", "logs\\_%");
        for (TableMeta table : tables) {
            assertTrue(table.getColumns().isEmpty());
            assertTrue(table.getPrimaryKey().isEmpty());
            assertTrue(table.getIndexes().isEmpty());
            assertNull(table.getEngine());
            assertNull(table.getCharset());
            assertNull(table.getComment());
        }
        assertResourcesClosed();
    }

    @Test
    public void firstSchemaModeWithNoMatchingSchemasReturnsEmptyAfterOneQuery() throws Exception {
        addTemplateFixture();

        assertTrue(extractor.listTablesUsingFirstSchema("missing\\_%", "%").isEmpty());

        assertEquals(1, driver.queries.size());
        assertEquals("schemata", driver.queries.get(0).kind);
        assertEquals(Collections.singletonList("missing\\_%"), driver.queries.get(0).parameters);
        assertResourcesClosed();
    }

    @Test
    public void previewWithNoMatchingSchemasReturnsEmptyAfterOneQuery() throws Exception {
        addTemplateFixture();

        assertTrue(extractor.listTableNamesUsingFirstSchema("missing\\_%", "%").isEmpty());

        assertEquals(1, driver.queries.size());
        assertEquals("schemata", driver.queries.get(0).kind);
        assertResourcesClosed();
    }

    @Test
    public void emptyFirstSchemaFailsWithoutTryingAnotherSchema() throws Exception {
        addSchema("actual_a");
        addTable("actual_b", "orders", "later schema has tables");

        assertFirstSchemaFailure("%", "actual_a");

        assertEquals(2, driver.queries.size());
        assertFirstSchemaQueries("actual_a", "%");
        assertResourcesClosed();
    }

    @Test
    public void noMatchingTablesInFirstSchemaFailsWithoutSwitchingSchemas() throws Exception {
        addTable("actual_a", "other", "first schema has no matching table");
        addTable("actual_b", "orders", "later schema has a matching table");

        assertFirstSchemaFailure("orders", "actual_a");

        assertEquals(2, driver.queries.size());
        assertFirstSchemaQueries("actual_a", "orders");
        assertResourcesClosed();
    }

    @Test
    public void firstSchemaTableWithNoColumnsFailsClearly() throws Exception {
        addTable("actual_a", "orders", "no column metadata");
        addSchema("actual_b");

        assertFirstSchemaFailure("orders", "orders");

        assertFirstSchemaQueries("actual_a", "orders");
        assertResourcesClosed();
    }

    @Test
    public void normalDiscoveryRejectsEmptyColumnsWhileSingleMissingTableKeepsExistingBehavior() throws Exception {
        addTable("actual_a", "orders", "missing columns");
        try {
            extractor.listTables("actual\\_%", "%");
            fail("Expected missing columns failure");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("actual_a.orders"));
        }

        TableMeta absent = extractor.getTable("actual_a", "absent");

        assertEquals("actual_a", absent.getSchema());
        assertEquals("absent", absent.getName());
        assertTrue(absent.getColumns().isEmpty());
        assertTrue(absent.getPrimaryKey().isEmpty());
        assertTrue(absent.getIndexes().isEmpty());
        assertEquals(6, driver.queries.size());
        assertResourcesClosed(2);
    }

    private void addTemplateFixture() {
        // Discovery order must come from ORDER BY rather than insertion order.
        addTable("actual_b", "different_table", "other schemas need not have template tables");
        addTable("actual_a", "orders", "first");
        driver.columns.add(column("actual_a", "orders", "id_a", 1, "bigint", "NO", null, "auto_increment"));
        driver.columns.add(column("actual_a", "orders", "code", 2, "varchar(32)", "YES", "draft", ""));
        driver.columns.add(column("actual_b", "different_table", "id_b", 1, "int", "NO", "0", ""));
        driver.statistics.add(index("actual_a", "orders", "PRIMARY", "id_a", 1, false));
        driver.statistics.add(index("actual_a", "orders", "idx_code", "code", 1, true));
        driver.statistics.add(index("actual_b", "different_table", "PRIMARY", "id_b", 1, false));
    }

    private void assertFirstSchemaFailure(String tablePattern, String messagePart) throws Exception {
        try {
            extractor.listTablesUsingFirstSchema("actual\\_%", tablePattern);
            fail("Expected template metadata failure");
        } catch (IllegalStateException e) {
            assertTrue("Failure should identify missing template metadata: " + e.getMessage(), e.getMessage().contains(messagePart));
        }
    }

    private void assertFirstSchemaQueries(String templateSchema, String... tablePatterns) {
        List<String> expected = new ArrayList<>();
        expected.add(templateSchema);
        expected.addAll(Arrays.asList(tablePatterns));
        assertEquals("schemata", driver.queries.get(0).kind);
        assertTrue(driver.queries.get(0).sql.contains("schema_name like ?"));
        for (int i = 1; i < driver.queries.size(); i++) {
            Query query = driver.queries.get(i);
            assertEquals(expected, query.parameters);
            assertTrue(query.sql.matches(".*table_schema\\s*=\\s*\\?.*"));
            assertFalse(query.sql.contains("table_schema like"));
        }
    }

    private List<String> tableKeys(List<TableMeta> tables) {
        List<String> keys = new ArrayList<>();
        for (TableMeta table : tables) keys.add(table.getSchema() + "." + table.getName());
        return keys;
    }

    private void addSchema(String schema) {
        for (Map<String, Object> row : driver.schemata) if (schema.equals(row.get("SCHEMA_NAME"))) return;
        driver.schemata.add(row("SCHEMA_NAME", schema));
    }

    private void addTable(String schema, String name, String comment) {
        addSchema(schema);
        driver.tables.add(row("TABLE_SCHEMA", schema, "TABLE_NAME", name, "ENGINE", "InnoDB",
                "TABLE_COLLATION", "utf8mb4_bin", "TABLE_COMMENT", comment));
    }

    private Map<String, Object> column(String schema, String table, String name, int ordinal,
                                      String type, String nullable, String defaultValue, String extra) {
        return row("TABLE_SCHEMA", schema, "TABLE_NAME", table, "COLUMN_NAME", name,
                "COLUMN_TYPE", type, "IS_NULLABLE", nullable, "COLUMN_DEFAULT", defaultValue,
                "EXTRA", extra, "COLUMN_COMMENT", "comment " + name, "ORDINAL_POSITION", ordinal,
                "CHARACTER_SET_NAME", "utf8mb4", "COLLATION_NAME", "utf8mb4_bin");
    }

    private Map<String, Object> index(String schema, String table, String name, String column, int sequence, boolean nonUnique) {
        return row("TABLE_SCHEMA", schema, "TABLE_NAME", table, "INDEX_NAME", name,
                "COLUMN_NAME", column, "SEQ_IN_INDEX", sequence, "NON_UNIQUE", nonUnique ? 1 : 0);
    }

    private void assertPatternQueries(List<String> parameters) {
        Set<String> kinds = new HashSet<>();
        for (Query query : driver.queries) {
            assertEquals(parameters, query.parameters);
            assertEquals(query.parameters.size(), query.sql.chars().filter(c -> c == '?').count());
            assertTrue(query.sql.contains("table_schema like ?"));
            assertTrue(query.sql.contains("table_name like ?"));
            assertTrue(kinds.add(query.kind));
        }
        assertEquals(new HashSet<>(Arrays.asList("tables", "columns", "statistics")), kinds);
    }

    private void assertResourcesClosed() {
        assertResourcesClosed(1);
    }

    private void assertResourcesClosed(int expectedConnections) {
        assertEquals(expectedConnections, driver.connectionCount);
        assertEquals(driver.connectionCount, driver.closedConnections);
        assertEquals(driver.statementCount, driver.closedStatements);
        assertEquals(driver.resultSetCount, driver.closedResultSets);
    }

    private TableMeta find(List<TableMeta> tables, String schema, String name) {
        for (TableMeta table : tables) {
            if (schema.equals(table.getSchema()) && name.equals(table.getName())) return table;
        }
        throw new AssertionError("Missing " + schema + "." + name);
    }

    private IndexMeta findIndex(TableMeta table, String name) {
        for (IndexMeta index : table.getIndexes()) if (name.equals(index.getName())) return index;
        throw new AssertionError("Missing index " + name);
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) row.put(((String) values[i]).toUpperCase(Locale.ROOT), values[i + 1]);
        return row;
    }

    private static class Query {
        private static final Pattern PARAMETER_PREDICATE = Pattern.compile("(schema_name|table_schema|table_name)\\s*(=|like)\\s*\\?");
        final String sql;
        final String kind;
        final List<String> parameters;

        Query(String sql, Map<Integer, String> parameters) {
            this.sql = sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            this.kind = this.sql.contains("information_schema.columns") ? "columns"
                    : this.sql.contains("information_schema.statistics") ? "statistics"
                    : this.sql.contains("information_schema.schemata") ? "schemata" : "tables";
            this.parameters = new ArrayList<>();
            for (int i = 1; i <= parameters.size(); i++) this.parameters.add(parameters.get(i));
        }

        boolean matches(Map<String, Object> row) {
            Matcher predicates = PARAMETER_PREDICATE.matcher(sql);
            Boolean matchesSchema = null;
            Boolean matchesTable = null;
            int parameter = 0;
            while (predicates.find()) {
                String column = predicates.group(1);
                String expected = parameters.get(parameter++);
                String actual = (String) row.get(column.toUpperCase(Locale.ROOT));
                boolean matches = "=".equals(predicates.group(2)) ? expected.equals(actual) : like(actual, expected);
                if (!"table_name".equals(column)) matchesSchema = matchesSchema == null ? matches : matchesSchema || matches;
                else matchesTable = matchesTable == null ? matches : matchesTable || matches;
            }
            assertEquals("Every bound parameter must belong to a supported predicate", parameters.size(), parameter);
            return (matchesSchema == null || matchesSchema) && (matchesTable == null || matchesTable);
        }

        private boolean like(String value, String pattern) {
            StringBuilder regex = new StringBuilder();
            for (int i = 0; i < pattern.length(); i++) {
                char next = pattern.charAt(i);
                if (next == '\\' && i + 1 < pattern.length()) regex.append(Pattern.quote(String.valueOf(pattern.charAt(++i))));
                else if (next == '%') regex.append(".*");
                else if (next == '_') regex.append('.');
                else regex.append(Pattern.quote(String.valueOf(next)));
            }
            return value != null && value.matches(regex.toString());
        }
    }

    public static class TestDriver implements Driver {
        final String url = "jdbc:tidb-extractor-test:" + UUID.randomUUID();
        final List<Map<String, Object>> schemata = new ArrayList<>();
        final List<Map<String, Object>> tables = new ArrayList<>();
        final List<Map<String, Object>> columns = new ArrayList<>();
        final List<Map<String, Object>> statistics = new ArrayList<>();
        final List<Query> queries = new ArrayList<>();
        int connectionCount;
        int closedConnections;
        int statementCount;
        int closedStatements;
        int resultSetCount;
        int closedResultSets;
        SQLException failColumns;

        @Override
        public Connection connect(String url, Properties info) {
            if (!acceptsURL(url)) return null;
            connectionCount++;
            boolean[] closed = {false};
            return proxy(Connection.class, (object, method, args) -> {
                switch (method.getName()) {
                    case "prepareStatement": return statement((String) args[0]);
                    case "close": if (!closed[0]) { closed[0] = true; closedConnections++; } return null;
                    case "isClosed": return closed[0];
                    case "getMetaData": throw new AssertionError("Per-table JDBC metadata must not be used");
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        }

        PreparedStatement statement(String sql) {
            statementCount++;
            Map<Integer, String> parameters = new LinkedHashMap<>();
            boolean[] closed = {false};
            return proxy(PreparedStatement.class, (object, method, args) -> {
                switch (method.getName()) {
                    case "setString": parameters.put((Integer) args[0], (String) args[1]); return null;
                    case "executeQuery":
                        Query query = new Query(sql, parameters);
                        queries.add(query);
                        return resultSet(query);
                    case "close": if (!closed[0]) { closed[0] = true; closedStatements++; } return null;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        }

        ResultSet resultSet(Query query) {
            resultSetCount++;
            List<Map<String, Object>> source = "columns".equals(query.kind) ? columns
                    : "statistics".equals(query.kind) ? statistics
                    : "schemata".equals(query.kind) ? schemata : tables;
            List<Map<String, Object>> selected = new ArrayList<>();
            for (Map<String, Object> row : source) {
                if (query.matches(row)) selected.add(row);
            }
            // Honor SQL ordering so deliberately shuffled index rows exercise SEQ_IN_INDEX.
            int orderBy = query.sql.indexOf(" order by ");
            if (orderBy >= 0) {
                String[] orderColumns = query.sql.substring(orderBy + " order by ".length()).split(",");
                selected.sort((left, right) -> {
                    for (String orderColumn : orderColumns) {
                        String name = orderColumn.trim().toUpperCase(Locale.ROOT);
                        Object a = left.get(name);
                        Object b = right.get(name);
                        int compared;
                        if (a == b) compared = 0;
                        else if (a == null) compared = -1;
                        else if (b == null) compared = 1;
                        else if (a instanceof Number && b instanceof Number) {
                            compared = Integer.compare(((Number) a).intValue(), ((Number) b).intValue());
                        } else compared = a.toString().compareTo(b.toString());
                        if (compared != 0) return compared;
                    }
                    return 0;
                });
            }
            int[] cursor = {-1};
            boolean[] closed = {false};
            return proxy(ResultSet.class, (object, method, args) -> {
                switch (method.getName()) {
                    case "next":
                        if ("columns".equals(query.kind) && failColumns != null) throw failColumns;
                        return ++cursor[0] < selected.size();
                    case "getString":
                    case "getInt":
                    case "getBoolean":
                        Object value = selected.get(cursor[0]).get(((String) args[0]).toUpperCase(Locale.ROOT));
                        if ("getString".equals(method.getName())) return value == null ? null : value.toString();
                        if ("getBoolean".equals(method.getName())) return value instanceof Boolean ? value : value != null && Integer.parseInt(value.toString()) != 0;
                        return value == null ? 0 : Integer.parseInt(value.toString());
                    case "close": if (!closed[0]) { closed[0] = true; closedResultSets++; } return null;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        }

        @Override public boolean acceptsURL(String url) { return this.url.equals(url); }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
        @Override public int getMajorVersion() { return 1; }
        @Override public int getMinorVersion() { return 0; }
        @Override public boolean jdbcCompliant() { return false; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(TiDbSchemaExtractorTest.class.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
