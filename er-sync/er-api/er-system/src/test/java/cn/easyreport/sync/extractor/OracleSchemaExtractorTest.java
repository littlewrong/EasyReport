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
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class OracleSchemaExtractorTest {
    private FirstScopeJdbc jdbc;
    private OracleSchemaExtractor extractor;

    @Before
    public void setup() throws SQLException {
        jdbc = new FirstScopeJdbc();
        DriverManager.registerDriver(jdbc);
        extractor = new OracleSchemaExtractor(jdbc.datasource("app_owner"));
    }

    @After
    public void cleanup() throws SQLException {
        DriverManager.deregisterDriver(jdbc);
    }

    @Test
    public void discoversEveryOwnerButReadsOnlyFirstOwnersMetadataAndMakesIndependentCopies() throws Exception {
        expectOwners(Arrays.asList("ERP_QUALITY\\_%", "ARCHIVE\\_%"),
            "ARCHIVE_A", "ERP_QUALITY_A", "ERP_QUALITY_B");
        expectTables("ARCHIVE_A", Arrays.asList("T_ID\\_%", "LOG\\_%"), "T_ID_ORDERS");
        expectMetadata("ARCHIVE_A", "T_ID_ORDERS");

        Locale previousLocale = Locale.getDefault();
        List<TableMeta> tables;
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            tables = extractor.listTablesUsingFirstSchema(
                " erp_quality\\_%, archive\\_% , , ", " t_id\\_%, log\\_% , ");
        } finally {
            Locale.setDefault(previousLocale);
        }

        assertEquals(3, tables.size());
        assertEquals("ARCHIVE_A", tables.get(0).getSchema());
        assertEquals("ERP_QUALITY_A", tables.get(1).getSchema());
        assertEquals("ERP_QUALITY_B", tables.get(2).getSchema());
        for (TableMeta table : tables) {
            assertNull(table.getDatabase());
            assertEquals("T_ID_ORDERS", table.getName());
            assertEquals("orders comment", table.getComment());
            assertEquals(Arrays.asList("ID", "CODE"), table.getPrimaryKey());
            assertEquals(2, table.getColumns().size());
            ColumnMeta id = table.getColumns().get(0);
            assertEquals("ID", id.getName());
            assertEquals("NUMBER(10)", id.getColumnType());
            assertFalse(id.isNullable());
            assertNull(id.getDefaultValue());
            assertEquals(Integer.valueOf(1), id.getOrdinalPosition());
            assertEquals("identifier", id.getComment());
            ColumnMeta code = table.getColumns().get(1);
            assertEquals("VARCHAR2(64)", code.getColumnType());
            assertTrue(code.isNullable());
            assertEquals("'draft'", code.getDefaultValue());
            assertEquals("status code", code.getComment());
            assertEquals(1, table.getIndexes().size());
            IndexMeta index = table.getIndexes().get(0);
            assertEquals("UK_CODE_ID", index.getName());
            assertTrue(index.isUnique());
            assertEquals(Arrays.asList("CODE", "ID"), index.getColumns());
        }
        FirstScopeJdbc.assertIndependentCopies(tables.get(0), tables.get(1));
        assertFalse(FirstScopeJdbc.normalized(jdbc.calls.get(0).sql).contains("all_tables"));
        assertEquals(7, jdbc.calls.size());
        jdbc.assertCompleteAndClosed();
    }

    @Test
    public void previewExpandsFirstOwnerTableNamesWithoutReadingColumnOrIndexMetadata() throws Exception {
        expectOwners(Collections.singletonList("ERP\\_%"), "ERP_A", "ERP_B", "ERP_C");
        expectTables("ERP_A", Collections.singletonList("%"), "ORDERS", "PAYMENTS");

        List<TableMeta> tables = extractor.listTableNamesUsingFirstSchema("erp\\_%", "%");

        assertEquals(6, tables.size());
        assertEquals(Arrays.asList("ERP_A.ORDERS", "ERP_A.PAYMENTS", "ERP_B.ORDERS",
            "ERP_B.PAYMENTS", "ERP_C.ORDERS", "ERP_C.PAYMENTS"), FirstScopeJdbc.names(tables, false));
        for (TableMeta table : tables) {
            assertTrue(table.getColumns().isEmpty());
            assertTrue(table.getPrimaryKey().isEmpty());
            assertTrue(table.getIndexes().isEmpty());
        }
        assertEquals(2, jdbc.calls.size());
        jdbc.assertCompleteAndClosed();
    }

    @Test
    public void firstOwnerWithoutMatchingTablesFailsClearlyWithoutTryingAnotherOwner() throws Exception {
        expectOwners(Collections.singletonList("ERP\\_%"), "ERP_A", "ERP_B");
        expectTables("ERP_A", Collections.singletonList("MISSING%"));

        try {
            extractor.listTablesUsingFirstSchema("erp\\_%", "missing%");
            fail("The first matching owner must be the template even when it has no matching tables");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("ERP_A"));
            assertTrue(expected.getMessage().contains("表"));
        }
        assertEquals(2, jdbc.calls.size());
        jdbc.assertCompleteAndClosed();
    }

    @Test
    public void noMatchingOwnersReturnsEmptyWithoutQueryingTables() throws Exception {
        expectOwners(Collections.singletonList("MISSING\\_%"));

        assertTrue(extractor.listTablesUsingFirstSchema("missing\\_%", "%").isEmpty());

        assertEquals(1, jdbc.calls.size());
        jdbc.assertCompleteAndClosed();
    }

    @Test
    public void emptySchemaUsesExactUppercaseUsernameWithLiteralUnderscore() throws Exception {
        jdbc.expectQuery(Collections.singletonList("APP_OWNER"), FirstScopeJdbc.rows(
            FirstScopeJdbc.row("USERNAME", "APP_OWNER")), "from all_users", "username = ?", "order by username");
        expectTables("APP_OWNER", Collections.<String>emptyList(), "ORDERS");

        List<TableMeta> tables = extractor.listTableNamesUsingFirstSchema(" , ", null);

        assertEquals(Collections.singletonList("APP_OWNER.ORDERS"), FirstScopeJdbc.names(tables, false));
        assertFalse(FirstScopeJdbc.normalized(jdbc.calls.get(0).sql).contains("usernamelike"));
        jdbc.assertCompleteAndClosed();
    }

    @Test
    public void metadataFailureClosesConnectionStatementsAndEarlierResultSets() throws Exception {
        expectOwners(Collections.singletonList("ERP\\_%"), "ERP_A", "ERP_B");
        expectTables("ERP_A", Collections.singletonList("%"), "ORDERS");
        jdbc.expectFailure(Arrays.asList("ERP_A", "ORDERS"), new SQLException("metadata unavailable"),
            "from all_tab_comments", "owner = ?", "table_name = ?");

        try {
            extractor.listTablesUsingFirstSchema("erp\\_%", "%");
            fail("Metadata errors must remain visible");
        } catch (SQLException expected) {
            assertEquals("metadata unavailable", expected.getMessage());
        }
        jdbc.assertCompleteAndClosed();
    }

    private void expectOwners(List<String> patterns, String... owners) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String owner : owners) rows.add(FirstScopeJdbc.row("USERNAME", owner));
        jdbc.expectQuery(patterns, rows, "select username from all_users", "where 1=1",
            patterns.size() > 1 ? "username like ? escape '\\' OR username like ? escape '\\'"
                : "username like ? escape '\\'", "order by username");
    }

    private void expectTables(String owner, List<String> patterns, String... names) {
        List<Object> params = new ArrayList<>();
        params.add(owner);
        params.addAll(patterns);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String name : names) rows.add(FirstScopeJdbc.row("OWNER", owner, "TABLE_NAME", name));
        List<String> fragments = new ArrayList<>(Arrays.asList("from all_tables", "owner = ?", "order by table_name"));
        if (!patterns.isEmpty()) fragments.add("table_name like ? escape '\\'");
        if (patterns.size() > 1) fragments.add("table_name like ? escape '\\' OR table_name like ? escape '\\'");
        jdbc.expectQuery(params, rows, fragments.toArray(new String[0]));
    }

    private void expectMetadata(String owner, String table) {
        List<String> params = Arrays.asList(owner, table);
        jdbc.expectQuery(params, FirstScopeJdbc.rows(FirstScopeJdbc.row("COMMENTS", "orders comment")),
            "from all_tab_comments", "owner = ?", "table_name = ?");
        jdbc.expectQuery(params, FirstScopeJdbc.rows(
            FirstScopeJdbc.row("COLUMN_NAME", "ID", "DATA_TYPE", "NUMBER", "DATA_PRECISION", 10,
                "DATA_SCALE", 0, "DATA_LENGTH", 22, "NULLABLE", "N", "DATA_DEFAULT", " NULL ", "COLUMN_ID", 1),
            FirstScopeJdbc.row("COLUMN_NAME", "CODE", "DATA_TYPE", "VARCHAR2", "DATA_LENGTH", 64,
                "NULLABLE", "Y", "DATA_DEFAULT", " 'draft' ", "COLUMN_ID", 2)),
            "from all_tab_columns", "owner = ?", "table_name = ?", "order by column_id");
        jdbc.expectQuery(params, FirstScopeJdbc.rows(
            FirstScopeJdbc.row("COLUMN_NAME", "ID", "COMMENTS", "identifier"),
            FirstScopeJdbc.row("COLUMN_NAME", "CODE", "COMMENTS", "status code")),
            "from all_col_comments", "owner = ?", "table_name = ?");
        jdbc.expectQuery(params, FirstScopeJdbc.rows(
            FirstScopeJdbc.row("COLUMN_NAME", "ID"), FirstScopeJdbc.row("COLUMN_NAME", "CODE")),
            "from all_constraints", "join all_cons_columns", "order by cc.position");
        jdbc.expectQuery(params, FirstScopeJdbc.rows(
            FirstScopeJdbc.row("INDEX_NAME", "UK_CODE_ID", "UNIQUENESS", "UNIQUE", "COLUMN_NAME", "CODE"),
            FirstScopeJdbc.row("INDEX_NAME", "UK_CODE_ID", "UNIQUENESS", "UNIQUE", "COLUMN_NAME", "ID")),
            "from all_indexes", "join all_ind_columns", "order by i.index_name, ic.column_position");
    }
}

/** Scripted JDBC checks the actual boundary: bound filters, source scope and closed resources. */
class FirstScopeJdbc implements Driver {
    final String url = "jdbc:first-scope-test:" + UUID.randomUUID();
    final List<Call> calls = new ArrayList<>();
    final List<String> catalogChanges = new ArrayList<>();
    String catalog = "default_catalog";
    private final Deque<Expectation> expectations = new ArrayDeque<>();
    private int openedConnections;
    private int closedConnections;
    private int openedStatements;
    private int closedStatements;
    private int openedResults;
    private int closedResults;

    ErDatasource datasource(String username) {
        ErDatasource ds = new ErDatasource();
        ds.setDriverClass(FirstScopeJdbc.class.getName());
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword("test");
        return ds;
    }

    void expectQuery(List<?> params, List<Map<String, Object>> rows, String... fragments) {
        expectations.add(new Expectation(null, params, rows, null, fragments));
    }

    void expectFailure(List<?> params, SQLException failure, String... fragments) {
        expectations.add(new Expectation(null, params, Collections.<Map<String, Object>>emptyList(), failure, fragments));
    }

    void expectMetadata(String method, List<?> params, List<Map<String, Object>> rows) {
        expectations.add(new Expectation(method, params, rows, null));
    }

    void expectMetadataFailure(String method, List<?> params, SQLException failure) {
        expectations.add(new Expectation(method, params, Collections.<Map<String, Object>>emptyList(), failure));
    }

    void assertCompleteAndClosed() {
        assertTrue("Unexecuted JDBC expectations: " + expectations.size(), expectations.isEmpty());
        assertEquals(1, openedConnections);
        assertEquals("Connection leak", openedConnections, closedConnections);
        assertEquals("Statement leak", openedStatements, closedStatements);
        assertEquals("ResultSet leak", openedResults, closedResults);
    }

    @Override
    public Connection connect(String candidate, Properties info) {
        if (!acceptsURL(candidate)) return null;
        openedConnections++;
        boolean[] closed = {false};
        return proxy(Connection.class, (object, method, args) -> {
            switch (method.getName()) {
                case "prepareStatement": return statement((String) args[0]);
                case "getMetaData": return metadata();
                case "getCatalog": return catalog;
                case "setCatalog":
                    catalog = (String) args[0];
                    catalogChanges.add(catalog);
                    return null;
                case "close":
                    if (!closed[0]) closedConnections++;
                    closed[0] = true;
                    return null;
                case "isClosed": return closed[0];
                default: return objectMethod(object, method.getName(), args);
            }
        });
    }

    private PreparedStatement statement(String sql) {
        openedStatements++;
        Map<Integer, Object> bound = new LinkedHashMap<>();
        boolean[] closed = {false};
        return proxy(PreparedStatement.class, (object, method, args) -> {
            switch (method.getName()) {
                case "setString":
                    bound.put((Integer) args[0], args[1]);
                    return null;
                case "executeQuery":
                    List<Object> params = new ArrayList<>();
                    for (int i = 1; i <= bound.size(); i++) params.add(bound.get(i));
                    return execute(new Call(sql, null, params));
                case "close":
                    if (!closed[0]) closedStatements++;
                    closed[0] = true;
                    return null;
                case "isClosed": return closed[0];
                default: return objectMethod(object, method.getName(), args);
            }
        });
    }

    private DatabaseMetaData metadata() {
        return proxy(DatabaseMetaData.class, (object, method, args) -> {
            switch (method.getName()) {
                case "getSearchStringEscape": return "\\";
                case "getColumns":
                case "getPrimaryKeys":
                case "getIndexInfo":
                case "getTables":
                    return execute(new Call(null, method.getName(), Arrays.asList(args)));
                default: return objectMethod(object, method.getName(), args);
            }
        });
    }

    private ResultSet execute(Call call) throws SQLException {
        call.catalog = catalog;
        calls.add(call);
        assertFalse("Unexpected JDBC call: " + call, expectations.isEmpty());
        Expectation expected = expectations.removeFirst();
        assertEquals("JDBC operation for " + call, expected.method, call.method);
        assertEquals("Parameters for " + call, expected.params, call.params);
        if (call.sql != null) {
            for (String fragment : expected.fragments) {
                assertTrue("Expected SQL fragment " + fragment + " in " + call.sql,
                    normalized(call.sql).contains(normalized(fragment)));
            }
        }
        if (expected.failure != null) throw expected.failure;
        if ("getColumns".equals(call.method)) {
            List<Map<String, Object>> matched = new ArrayList<>();
            String tablePattern = (String) call.params.get(2);
            for (Map<String, Object> row : expected.rows) {
                Object tableName = row.get("TABLE_NAME");
                if (tableName == null || matchesLike(tableName.toString(), tablePattern)) matched.add(row);
            }
            return results(matched);
        }
        return results(expected.rows);
    }

    private static boolean matchesLike(String value, String pattern) {
        if (pattern == null) return true;
        StringBuilder regex = new StringBuilder("^");
        boolean escaped = false;
        for (char character : pattern.toCharArray()) {
            if (escaped) {
                regex.append(Pattern.quote(String.valueOf(character)));
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '%') {
                regex.append(".*");
            } else if (character == '_') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(character)));
            }
        }
        if (escaped) regex.append(Pattern.quote("\\"));
        regex.append('$');
        return Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(value).matches();
    }

    private ResultSet results(List<Map<String, Object>> rows) {
        openedResults++;
        int[] position = {-1};
        boolean[] closed = {false};
        boolean[] wasNull = {false};
        return proxy(ResultSet.class, (object, method, args) -> {
            String name = method.getName();
            if ("next".equals(name)) return ++position[0] < rows.size();
            if ("close".equals(name)) {
                if (!closed[0]) closedResults++;
                closed[0] = true;
                return null;
            }
            if ("isClosed".equals(name)) return closed[0];
            if ("wasNull".equals(name)) return wasNull[0];
            if ("getString".equals(name) || "getInt".equals(name) || "getShort".equals(name)
                || "getBoolean".equals(name) || "getObject".equals(name)) {
                Map<String, Object> row = rows.get(position[0]);
                Object value = args[0] instanceof Integer
                    ? new ArrayList<>(row.values()).get((Integer) args[0] - 1)
                    : row.get(args[0].toString().toUpperCase(Locale.ROOT));
                wasNull[0] = value == null;
                if ("getString".equals(name)) return value == null ? null : value.toString();
                if ("getInt".equals(name)) return value == null ? 0 : ((Number) value).intValue();
                if ("getShort".equals(name)) return value == null ? (short) 0 : ((Number) value).shortValue();
                if ("getBoolean".equals(name)) return value != null && (Boolean.TRUE.equals(value)
                    || "1".equals(value.toString()) || "true".equalsIgnoreCase(value.toString()));
                return value;
            }
            return objectMethod(object, name, args);
        });
    }

    static String normalized(String sql) {
        return sql.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put(pairs[i].toString().toUpperCase(Locale.ROOT), pairs[i + 1]);
        }
        return row;
    }

    @SafeVarargs
    static List<Map<String, Object>> rows(Map<String, Object>... rows) {
        return Arrays.asList(rows);
    }

    static List<String> names(List<TableMeta> tables, boolean catalogScopes) {
        List<String> names = new ArrayList<>();
        for (TableMeta table : tables) {
            names.add((catalogScopes ? table.getDatabase() : table.getSchema()) + "." + table.getName());
        }
        return names;
    }

    static void assertIndependentCopies(TableMeta first, TableMeta second) {
        assertNotSame(first, second);
        assertNotSame(first.getColumns(), second.getColumns());
        assertNotSame(first.getColumns().get(0), second.getColumns().get(0));
        assertNotSame(first.getPrimaryKey(), second.getPrimaryKey());
        assertNotSame(first.getIndexes(), second.getIndexes());
        assertNotSame(first.getIndexes().get(0), second.getIndexes().get(0));
        assertNotSame(first.getIndexes().get(0).getColumns(), second.getIndexes().get(0).getColumns());
        first.getColumns().get(0).setName("changed");
        first.getIndexes().get(0).getColumns().clear();
        first.getPrimaryKey().clear();
        first.getColumns().clear();
        first.getIndexes().clear();
        assertEquals(2, second.getColumns().size());
        assertFalse("changed".equals(second.getColumns().get(0).getName()));
        assertEquals(2, second.getPrimaryKey().size());
        assertEquals(1, second.getIndexes().size());
        assertEquals(2, second.getIndexes().get(0).getColumns().size());
    }

    private static Object objectMethod(Object object, String name, Object[] args) {
        if ("toString".equals(name)) return "FirstScopeJdbcProxy";
        if ("hashCode".equals(name)) return System.identityHashCode(object);
        if ("equals".equals(name)) return object == args[0];
        throw new AssertionError("Unexpected JDBC method: " + name);
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(FirstScopeJdbc.class.getClassLoader(), new Class<?>[]{type}, handler));
    }

    @Override public boolean acceptsURL(String candidate) { return url.equals(candidate); }
    @Override public DriverPropertyInfo[] getPropertyInfo(String candidate, Properties properties) { return new DriverPropertyInfo[0]; }
    @Override public int getMajorVersion() { return 1; }
    @Override public int getMinorVersion() { return 0; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public Logger getParentLogger() { return Logger.getGlobal(); }

    static class Call {
        final String sql;
        final String method;
        final List<?> params;
        String catalog;

        Call(String sql, String method, List<?> params) {
            this.sql = sql;
            this.method = method;
            this.params = params;
        }

        @Override public String toString() { return (sql == null ? method : sql) + " " + params; }
    }

    private static class Expectation {
        final String method;
        final List<?> params;
        final List<Map<String, Object>> rows;
        final SQLException failure;
        final String[] fragments;

        Expectation(String method, List<?> params, List<Map<String, Object>> rows,
                    SQLException failure, String... fragments) {
            this.method = method;
            this.params = params;
            this.rows = rows;
            this.failure = failure;
            this.fragments = fragments;
        }
    }
}
