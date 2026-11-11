package cn.easyreport.sync.datasync;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.*;

/** JDBC metadata fixtures only; no PostgreSQL server is contacted. */
public class PostgreSqlDataSyncExtractorTest {
    @Test
    public void wildcardAndMultiplePatternsKeepActualSchemasInsteadOfInputPatterns() throws Exception {
        Fixture f = new Fixture();
        f.respond("tenant_%", "order%", row("tenant_a", "orders"));
        f.respond("tenant_%", "events", row("tenant_b", "events"));
        f.respond("reports", "order%", row("reports", "orders"));
        f.respond("reports", "events", row("reports", "events"));
        List<String[]> tables = f.list(Arrays.asList("tenant_%", "reports"), Arrays.asList("order%", "events"));
        assertEquals(Arrays.asList("tenant_a.orders", "tenant_b.events", "reports.orders", "reports.events"), names(tables));
        assertEquals(Arrays.asList("tenant_%|order%", "tenant_%|events", "reports|order%", "reports|events"), f.calls);
    }

    @Test
    public void sameTableNamesInDifferentSchemasRemainDistinct() throws Exception {
        Fixture f = new Fixture();
        f.respond("%", "orders", row("first", "orders"), row("second", "orders"));
        assertEquals(Arrays.asList("first.orders", "second.orders"),
                names(f.list(Collections.singletonList("%"), Collections.singletonList("orders"))));
    }

    @Test
    public void emptyPatternsUsePublicAndAllTables() throws Exception {
        Fixture f = new Fixture();
        f.respond("public", "%", row("public", "orders"));
        assertEquals(Collections.singletonList("public.orders"), names(f.list(Collections.emptyList(), Collections.emptyList())));
        assertEquals(Collections.singletonList("public|%"), f.calls);
        for (String emptySchema : new String[]{null, ""}) {
            Fixture explicitEmpty = new Fixture();
            explicitEmpty.respond("public", "%", row("public", "orders"));
            assertEquals(Collections.singletonList("public.orders"), names(explicitEmpty.list(
                    Collections.singletonList(emptySchema), Collections.singletonList(""))));
        }
    }

    @Test
    public void overlappingPatternsDeduplicateActualSchemaAndTablePairs() throws Exception {
        Fixture f = new Fixture();
        f.respond("public", "orders", row("public", "orders"));
        f.respond("public", "%", row("public", "orders"), row("public", "customers"));
        f.respond("%", "orders", row("public", "orders"), row("archive", "orders"));
        f.respond("%", "%", row("public", "orders"), row("public", "customers"), row("archive", "orders"));
        assertEquals(Arrays.asList("public.orders", "public.customers", "archive.orders"),
                names(f.list(Arrays.asList("public", "%"), Arrays.asList("orders", "%"))));
    }

    @Test
    public void missingActualSchemaFailsInsteadOfGuessingTheInputPattern() throws Exception {
        for (String missing : new String[]{null, "", "   "}) {
            Fixture f = new Fixture();
            f.respond("%", "%", row(missing, "orders"));
            try { f.list(Collections.singletonList("%"), Collections.emptyList()); fail("Expected missing TABLE_SCHEM rejection"); }
            catch (SQLException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("TABLE_SCHEM")); }
        }
    }

    @Test
    public void systemSchemasAreExcludedAndUserIdentifierCaseIsRetained() throws Exception {
        Fixture f = new Fixture();
        f.respond("%", "%", row("pg_catalog", "pg_class"), row("information_schema", "tables"), row("Tenant", "OrderItems"));
        assertEquals(Collections.singletonList("Tenant.OrderItems"),
                names(f.list(Collections.singletonList("%"), Collections.emptyList())));
    }

    private static String[] row(String schema, String table) { return new String[]{schema, table}; }

    private static List<String> names(List<String[]> tables) {
        List<String> names = new ArrayList<>();
        for (String[] table : tables) names.add(table[0] + "." + table[1]);
        return names;
    }

    private static final class Fixture {
        final Map<String, List<String[]>> responses = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();

        void respond(String schema, String table, String[]... rows) {
            responses.put(schema + "|" + table, Arrays.asList(rows));
        }

        List<String[]> list(List<String> schemas, List<String> tables) throws SQLException {
            DatabaseMetaData metadata = (DatabaseMetaData) Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(),
                    new Class<?>[]{DatabaseMetaData.class}, (proxy, method, arguments) -> {
                        if (!"getTables".equals(method.getName())) throw new AssertionError(method.getName());
                        assertNull("Catalog stays bound to the connection", arguments[0]);
                        assertArrayEquals(new String[]{"TABLE"}, (String[]) arguments[3]);
                        String key = arguments[1] + "|" + arguments[2];
                        calls.add(key);
                        List<String[]> values = responses.get(key);
                        if (values == null) throw new AssertionError("Unexpected metadata request: " + key);
                        return resultSet(values);
                    });
            Connection connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                        if ("getMetaData".equals(method.getName())) return metadata;
                        throw new AssertionError(method.getName());
                    });
            return new PostgreSqlDataSyncExtractor().listTableSpecs(connection, schemas, tables);
        }
    }

    private static ResultSet resultSet(List<String[]> rows) {
        int[] cursor = {-1};
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, arguments) -> {
                    if ("next".equals(method.getName())) return ++cursor[0] < rows.size();
                    if ("close".equals(method.getName())) return null;
                    if ("getString".equals(method.getName())) {
                        if ("TABLE_SCHEM".equals(arguments[0])) return rows.get(cursor[0])[0];
                        if ("TABLE_NAME".equals(arguments[0])) return rows.get(cursor[0])[1];
                    }
                    throw new AssertionError(method.getName());
                });
    }
}
