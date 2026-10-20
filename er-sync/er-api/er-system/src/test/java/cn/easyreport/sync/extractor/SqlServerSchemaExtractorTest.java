package cn.easyreport.sync.extractor;

import cn.easyreport.sync.model.ColumnMeta;
import cn.easyreport.sync.model.IndexMeta;
import cn.easyreport.sync.model.TableMeta;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class SqlServerSchemaExtractorTest {
    private FirstScopeJdbc jdbc;
    private SqlServerSchemaExtractor extractor;

    @Before
    public void setup() throws SQLException {
        jdbc = new FirstScopeJdbc();
        DriverManager.registerDriver(jdbc);
        extractor = new SqlServerSchemaExtractor(jdbc.datasource("test"));
    }

    @After
    public void cleanup() throws SQLException {
        DriverManager.deregisterDriver(jdbc);
    }

    @Test
    public void discoversAllCatalogsAndReadsSortedMetadataOnlyFromFirstCatalogAndDbo() throws Exception {
        expectCatalogs(Arrays.asList("erp_quality\\_%", "archive\\_%"),
            "archive_a", "erp_quality_a", "erp_quality_b");
        expectTables("archive_a", Arrays.asList("orders", "log\\_%"), "orders");
        expectMetadata("archive_a", "orders");

        List<TableMeta> tables = extractor.listTablesUsingFirstSchema(
            " erp_quality\\_%, archive\\_% , , ", " orders, log\\_% , ");

        assertEquals(Arrays.asList("archive_a.orders", "erp_quality_a.orders", "erp_quality_b.orders"),
            FirstScopeJdbc.names(tables, true));
        assertEquals(Collections.singletonList("archive_a"), jdbc.catalogChanges);
        for (TableMeta table : tables) {
            assertEquals("dbo", table.getSchema());
            assertEquals("orders comment", table.getComment());
            assertEquals(Arrays.asList("id", "code"), table.getPrimaryKey());
            assertEquals(2, table.getColumns().size());
            ColumnMeta id = table.getColumns().get(0);
            assertEquals("id", id.getName());
            assertEquals("BIGINT", id.getColumnType());
            assertFalse(id.isNullable());
            assertTrue(id.isAutoIncrement());
            assertEquals(Integer.valueOf(1), id.getOrdinalPosition());
            assertEquals("1", id.getDefaultValue());
            assertEquals("identifier", id.getComment());
            ColumnMeta code = table.getColumns().get(1);
            assertEquals("code", code.getName());
            assertEquals("NVARCHAR(64)", code.getColumnType());
            assertTrue(code.isNullable());
            assertFalse(code.isAutoIncrement());
            assertEquals("'draft'", code.getDefaultValue());
            assertEquals(Integer.valueOf(2), code.getOrdinalPosition());
            assertEquals("status code", code.getComment());
            assertEquals(1, table.getIndexes().size());
            IndexMeta index = table.getIndexes().get(0);
            assertEquals("uk_code_id", index.getName());
            assertTrue(index.isUnique());
            assertEquals(Arrays.asList("code", "id"), index.getColumns());
        }
        FirstScopeJdbc.assertIndependentCopies(tables.get(0), tables.get(1));
        assertFalse(FirstScopeJdbc.normalized(jdbc.calls.get(0).sql).contains("information_schema.tables"));
        assertEquals("archive_a", jdbc.calls.get(1).catalog);
        assertEquals("archive_a", jdbc.calls.get(2).catalog);
        assertEquals(6, jdbc.calls.size());
        jdbc.assertCompleteAndClosed();
    }

    @Test
    public void previewUsesFirstCatalogTableNamesAndDoesNotReadCommentsOrJdbcMetadata() throws Exception {
        expectCatalogs(Collections.singletonList("erp\\_%"), "erp_a", "erp_b", "erp_c");
        expectTables("erp_a", Collections.singletonList("%"), "orders", "payments");

        List<TableMeta> tables = extractor.listTableNamesUsingFirstSchema("erp\\_%", "%");

        assertEquals(Arrays.asList("erp_a.orders", "erp_a.payments", "erp_b.orders", "erp_b.payments",
            "erp_c.orders", "erp_c.payments"), FirstScopeJdbc.names(tables, true));
        assertEquals(Collections.singletonList("erp_a"), jdbc.catalogChanges);
        for (TableMeta table : tables) {
            assertEquals("dbo", table.getSchema());
            assertTrue(table.getColumns().isEmpty());
            assertTrue(table.getPrimaryKey().isEmpty());
            assertTrue(table.getIndexes().isEmpty());
        }
        assertEquals(2, jdbc.calls.size());
        jdbc.assertCompleteAndClosed();
    }

    @Test
    public void firstCatalogWithoutMatchingTablesFailsWithoutTryingNextCatalog() throws Exception {
        expectCatalogs(Collections.singletonList("erp\\_%"), "erp_a", "erp_b");
        expectTables("erp_a", Collections.singletonList("missing%"));

        try {
            extractor.listTablesUsingFirstSchema("erp\\_%", "missing%");
            fail("The first matched catalog must remain the template when it has no matching tables");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("erp_a"));
            assertTrue(expected.getMessage().contains("表"));
        }
        assertEquals(Collections.singletonList("erp_a"), jdbc.catalogChanges);
        assertEquals(2, jdbc.calls.size());
        jdbc.assertCompleteAndClosed();
    }

    @Test
    public void noAccessibleMatchingCatalogsReturnsEmptyWithoutChangingCatalog() throws Exception {
        expectCatalogs(Collections.singletonList("missing\\_%"));

        assertTrue(extractor.listTablesUsingFirstSchema("missing\\_%", "%").isEmpty());

        assertTrue(jdbc.catalogChanges.isEmpty());
        assertEquals(1, jdbc.calls.size());
        jdbc.assertCompleteAndClosed();
    }

    @Test
    public void emptySchemaUsesExactConnectionDefaultCatalogIncludingUnderscores() throws Exception {
        jdbc.catalog = "app_default_catalog";
        jdbc.expectQuery(Collections.singletonList("app_default_catalog"), FirstScopeJdbc.rows(
            FirstScopeJdbc.row("name", "app_default_catalog")), "from sys.databases", "state=0",
            "has_dbaccess(name)=1", "name = ?", "order by name");
        expectTables("app_default_catalog", Collections.<String>emptyList(), "orders");

        List<TableMeta> tables = extractor.listTableNamesUsingFirstSchema(" , ", null);

        assertEquals(Collections.singletonList("app_default_catalog.orders"), FirstScopeJdbc.names(tables, true));
        assertFalse(FirstScopeJdbc.normalized(jdbc.calls.get(0).sql).contains("namelike"));
        assertEquals(Collections.singletonList("app_default_catalog"), jdbc.catalogChanges);
        jdbc.assertCompleteAndClosed();
    }

    @Test
    public void metadataFailureClosesEarlierResultsAndConnection() throws Exception {
        expectCatalogs(Collections.singletonList("erp\\_%"), "erp_a", "erp_b");
        expectTables("erp_a", Collections.singletonList("%"), "orders");
        expectComment("orders");
        jdbc.expectMetadata("getColumns", Arrays.asList("erp_a", "dbo", "orders", "%"),
            FirstScopeJdbc.rows(column("id", 1, "bigint identity", 19, "NO", "((1))", "identifier", "YES")));
        jdbc.expectMetadata("getPrimaryKeys", Arrays.asList("erp_a", "dbo", "orders"),
            FirstScopeJdbc.rows(FirstScopeJdbc.row("COLUMN_NAME", "id", "KEY_SEQ", 1, "PK_NAME", "orders_primary")));
        jdbc.expectMetadataFailure("getIndexInfo", Arrays.asList("erp_a", "dbo", "orders", false, false),
            new SQLException("index metadata unavailable"));

        try {
            extractor.listTablesUsingFirstSchema("erp\\_%", "%");
            fail("Metadata errors must remain visible");
        } catch (SQLException expected) {
            assertEquals("index metadata unavailable", expected.getMessage());
        }
        assertEquals(Collections.singletonList("erp_a"), jdbc.catalogChanges);
        jdbc.assertCompleteAndClosed();
    }

    @Test
    public void columnLookupEscapesLiteralUnderscoreButPrimaryKeysAndIndexesUseActualTableName() throws Exception {
        expectCatalogs(Collections.singletonList("erp\\_%"), "erp_a", "erp_b");
        expectTables("erp_a", Collections.singletonList("orders\\_log"), "orders_log");
        expectMetadata("erp_a", "orders_log");

        List<TableMeta> tables = extractor.listTablesUsingFirstSchema("erp\\_%", "orders\\_log");

        assertEquals(Arrays.asList("erp_a.orders_log", "erp_b.orders_log"), FirstScopeJdbc.names(tables, true));
        assertEquals(Arrays.asList("erp_a", "dbo", "orders\\_log", "%"), jdbc.calls.get(3).params);
        assertEquals(Arrays.asList("erp_a", "dbo", "orders_log"), jdbc.calls.get(4).params);
        assertEquals(Arrays.asList("erp_a", "dbo", "orders_log", false, false), jdbc.calls.get(5).params);
        for (TableMeta table : tables) {
            assertEquals(2, table.getColumns().size());
            assertEquals("id", table.getColumns().get(0).getName());
            assertEquals("code", table.getColumns().get(1).getName());
        }
        jdbc.assertCompleteAndClosed();
    }

    private void expectCatalogs(List<String> patterns, String... catalogs) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String catalog : catalogs) rows.add(FirstScopeJdbc.row("name", catalog));
        jdbc.expectQuery(patterns, rows, "select name from sys.databases", "state=0", "has_dbaccess(name)=1",
            patterns.size() > 1 ? "name like ? escape '\\' OR name like ? escape '\\'"
                : "name like ? escape '\\'", "order by name");
    }

    private void expectTables(String catalog, List<String> patterns, String... names) {
        List<Object> params = new ArrayList<>();
        params.add(catalog);
        params.addAll(patterns);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String name : names) rows.add(FirstScopeJdbc.row("TABLE_NAME", name));
        List<String> fragments = new ArrayList<>(Arrays.asList("from information_schema.tables",
            "table_type='BASE TABLE'", "table_catalog=?", "table_schema='dbo'", "order by table_name"));
        if (!patterns.isEmpty()) fragments.add("table_name like ? escape '\\'");
        if (patterns.size() > 1) fragments.add("table_name like ? escape '\\' OR table_name like ? escape '\\'");
        jdbc.expectQuery(params, rows, fragments.toArray(new String[0]));
    }

    private void expectComment(String table) {
        jdbc.expectQuery(Arrays.asList("dbo", table), FirstScopeJdbc.rows(
            FirstScopeJdbc.row("REMARKS", "orders comment")), "from sys.tables", "join sys.schemas",
            "sys.extended_properties", "s.name=?", "t.name=?");
    }

    private void expectMetadata(String catalog, String table) {
        expectComment(table);
        List<Map<String, Object>> columns = new ArrayList<>();
        columns.add(qualifiedColumn(catalog, table, column("code", 2, "nvarchar", 64,
            "YES", "(N'draft')", "status code", "NO")));
        columns.add(qualifiedColumn(catalog, table, column("id", 1, "bigint identity", 19,
            "NO", "((1))", "identifier", "YES")));
        if (table.contains("_")) {
            columns.add(qualifiedColumn(catalog, table.replace('_', 'X'), column("other_table_column", 3,
                "int", 10, "YES", null, null, "NO")));
        }
        String tablePattern = table.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        jdbc.expectMetadata("getColumns", Arrays.asList(catalog, "dbo", tablePattern, "%"), columns);
        // JDBC drivers need not return primary-key or index rows in key-column order.
        jdbc.expectMetadata("getPrimaryKeys", Arrays.asList(catalog, "dbo", table), FirstScopeJdbc.rows(
            FirstScopeJdbc.row("COLUMN_NAME", "code", "KEY_SEQ", 2, "PK_NAME", "orders_primary"),
            FirstScopeJdbc.row("COLUMN_NAME", "id", "KEY_SEQ", 1, "PK_NAME", "orders_primary")));
        jdbc.expectMetadata("getIndexInfo", Arrays.asList(catalog, "dbo", table, false, false), FirstScopeJdbc.rows(
            FirstScopeJdbc.row("INDEX_NAME", "orders_primary", "NON_UNIQUE", false, "COLUMN_NAME", "code", "ORDINAL_POSITION", 2),
            FirstScopeJdbc.row("INDEX_NAME", "orders_primary", "NON_UNIQUE", false, "COLUMN_NAME", "id", "ORDINAL_POSITION", 1),
            FirstScopeJdbc.row("INDEX_NAME", "uk_code_id", "NON_UNIQUE", false, "COLUMN_NAME", "id", "ORDINAL_POSITION", 2),
            FirstScopeJdbc.row("INDEX_NAME", "uk_code_id", "NON_UNIQUE", false, "COLUMN_NAME", "code", "ORDINAL_POSITION", 1)));
    }

    private Map<String, Object> qualifiedColumn(String catalog, String table, Map<String, Object> column) {
        column.put("TABLE_CAT", catalog);
        column.put("TABLE_SCHEM", "dbo");
        column.put("TABLE_NAME", table);
        return column;
    }

    private Map<String, Object> column(String name, int ordinal, String type, int size,
                                       String nullable, String defaultValue, String comment, String identity) {
        return FirstScopeJdbc.row("COLUMN_NAME", name, "ORDINAL_POSITION", ordinal, "TYPE_NAME", type,
            "COLUMN_SIZE", size, "DECIMAL_DIGITS", 0, "IS_NULLABLE", nullable, "COLUMN_DEF", defaultValue,
            "REMARKS", comment, "IS_AUTOINCREMENT", identity);
    }
}
