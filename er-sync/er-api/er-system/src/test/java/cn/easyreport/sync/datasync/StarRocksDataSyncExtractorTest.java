package cn.easyreport.sync.datasync;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

public class StarRocksDataSyncExtractorTest
{
    private final StarRocksDataSyncExtractor extractor = new StarRocksDataSyncExtractor();

    @Test
    public void resolvesWildcardAndMultiplePatternsToActualDatabaseTablePairs() throws Exception
    {
        JdbcFixture jdbc = new JdbcFixture(new String[][] {
            {"erp_quality_100001", "tb_quality_task"},
            {"erp_quality_100002", "tb_quality_task"},
            {"erp_archive", "rf_user"}
        });

        List<String[]> tables = extractor.listTableSpecs(jdbc.connection,
            Arrays.asList("erp_quality_%", "erp_archive"), Arrays.asList("tb_quality_%", "rf_user"));

        assertEquals(Arrays.asList("erp_quality_%", "erp_archive", "tb_quality_%", "rf_user"), jdbc.params);
        assertTrue(jdbc.sql.contains("table_type='BASE TABLE'"));
        assertTrue(jdbc.sql.contains("AND (table_schema LIKE ? OR table_schema LIKE ?)"));
        assertTrue(jdbc.sql.contains("AND (table_name LIKE ? OR table_name LIKE ?)"));
        assertTrue(jdbc.sql.endsWith("ORDER BY table_schema, table_name"));
        assertEquals(3, tables.size());
        assertArrayEquals(new String[] {"erp_quality_100001", "tb_quality_task"}, tables.get(0));
        assertArrayEquals(new String[] {"erp_quality_100002", "tb_quality_task"}, tables.get(1));
        assertArrayEquals(new String[] {"erp_archive", "rf_user"}, tables.get(2));
        jdbc.assertResourcesClosed();
    }

    @Test
    public void emptyPatternListsEnumerateInternalBaseTablesWithoutInventedDatabaseNames() throws Exception
    {
        JdbcFixture jdbc = new JdbcFixture(new String[][] {{"erp_a", "orders"}, {"erp_b", "orders"}});

        List<String[]> tables = extractor.listTableSpecs(jdbc.connection,
            Collections.emptyList(), Collections.emptyList());

        assertEquals("SELECT table_schema, table_name FROM information_schema.tables "
            + "WHERE table_type='BASE TABLE' ORDER BY table_schema, table_name", jdbc.sql);
        assertTrue(jdbc.params.isEmpty());
        assertEquals(2, tables.size());
        assertArrayEquals(new String[] {"erp_a", "orders"}, tables.get(0));
        assertArrayEquals(new String[] {"erp_b", "orders"}, tables.get(1));
        jdbc.assertResourcesClosed();
    }

    @Test
    public void schemaOnlyAndTableOnlyFiltersKeepWildcardsAsBoundParameters() throws Exception
    {
        JdbcFixture schemaOnly = new JdbcFixture(new String[0][]);
        extractor.listTableSpecs(schemaOnly.connection,
            Collections.singletonList("erp\\_%'"), Collections.emptyList());
        assertEquals(Collections.singletonList("erp\\_%'"), schemaOnly.params);
        assertTrue(schemaOnly.sql.contains("AND (table_schema LIKE ?)"));
        assertFalse(schemaOnly.sql.contains("table_name LIKE"));
        assertFalse(schemaOnly.sql.contains("erp\\_%'"));
        schemaOnly.assertResourcesClosed();

        JdbcFixture tableOnly = new JdbcFixture(new String[0][]);
        extractor.listTableSpecs(tableOnly.connection,
            Collections.emptyList(), Collections.singletonList("tb_%"));
        assertEquals(Collections.singletonList("tb_%"), tableOnly.params);
        assertFalse(tableOnly.sql.contains("table_schema LIKE"));
        assertTrue(tableOnly.sql.contains("AND (table_name LIKE ?)"));
        tableOnly.assertResourcesClosed();
    }

    @Test
    public void noMatchingTablesReturnsEmptyAndClosesMetadataResources() throws Exception
    {
        JdbcFixture jdbc = new JdbcFixture(new String[0][]);

        List<String[]> tables = extractor.listTableSpecs(jdbc.connection,
            Collections.singletonList("missing%"), Collections.singletonList("%"));

        assertTrue(tables.isEmpty());
        jdbc.assertResourcesClosed();
    }

    @Test
    public void metadataQueryFailurePropagatesAndClosesPreparedStatement() throws Exception
    {
        JdbcFixture jdbc = new JdbcFixture(new String[0][]);
        jdbc.queryFailure = new SQLException("source metadata unavailable");

        try
        {
            extractor.listTableSpecs(jdbc.connection, Collections.singletonList("erp%"), Collections.emptyList());
            fail("Expected source metadata failure");
        }
        catch (SQLException expected)
        {
            assertSame(jdbc.queryFailure, expected);
        }

        assertTrue(jdbc.statementClosed);
        assertFalse(jdbc.connectionClosed);
    }

    private static class JdbcFixture
    {
        private final Connection connection;
        private final List<String> params = new ArrayList<>();
        private String sql;
        private boolean statementClosed;
        private boolean resultSetClosed;
        private boolean connectionClosed;
        private SQLException queryFailure;
        private int row = -1;

        JdbcFixture(String[][] rows)
        {
            ResultSet resultSet = (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                new Class<?>[] {ResultSet.class}, (proxy, method, args) -> {
                    if ("next".equals(method.getName())) return ++row < rows.length;
                    if ("getString".equals(method.getName()))
                    {
                        if ("table_schema".equals(args[0])) return rows[row][0];
                        if ("table_name".equals(args[0])) return rows[row][1];
                    }
                    if ("close".equals(method.getName())) { resultSetClosed = true; return null; }
                    throw new AssertionError("Unexpected ResultSet call: " + method.getName());
                });
            PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
                    if ("setString".equals(method.getName()))
                    {
                        assertEquals(params.size() + 1, args[0]);
                        params.add((String) args[1]);
                        return null;
                    }
                    if ("executeQuery".equals(method.getName()))
                    {
                        if (queryFailure != null) throw queryFailure;
                        return resultSet;
                    }
                    if ("close".equals(method.getName())) { statementClosed = true; return null; }
                    throw new AssertionError("Unexpected PreparedStatement call: " + method.getName());
                });
            connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName())) { sql = (String) args[0]; return statement; }
                    if ("close".equals(method.getName())) { connectionClosed = true; return null; }
                    throw new AssertionError("Unexpected Connection call: " + method.getName());
                });
        }

        private void assertResourcesClosed()
        {
            assertTrue(statementClosed);
            assertTrue(resultSetClosed);
            assertFalse(connectionClosed);
        }
    }
}
