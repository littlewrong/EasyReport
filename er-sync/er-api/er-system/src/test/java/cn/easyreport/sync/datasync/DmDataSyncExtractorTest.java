package cn.easyreport.sync.datasync;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.*;

public class DmDataSyncExtractorTest {
    private final DmDataSyncExtractor extractor = new DmDataSyncExtractor();

    @Test
    public void resolvesWildcardsToActualSchemasAndDeduplicatesOverlappingPatterns() throws Exception {
        Fixture jdbc = new Fixture();
        jdbc.tableRows = Arrays.asList(table("App_1", "Orders"), table("App_2", "Orders"),
            table("SYS", "SYSOBJECTS"), table("INFORMATION_SCHEMA", "scratch"));

        List<String[]> result = extractor.listTableSpecs(jdbc.connection,
            Arrays.asList("App_%", "App_1"), Collections.singletonList("Order%"));

        assertEquals(2, result.size());
        assertArrayEquals(new String[]{"App_1", "Orders"}, result.get(0));
        assertArrayEquals(new String[]{"App_2", "Orders"}, result.get(1));
        assertArrayEquals(new Object[]{null, "App_%", "Order%"}, jdbc.tableCalls.get(0));
        assertArrayEquals(new Object[]{null, "App_1", "Order%"}, jdbc.tableCalls.get(1));
        assertEquals(2, jdbc.closedResultSets);
        assertFalse(jdbc.connectionClosed);
    }

    @Test
    public void emptySchemaUsesLiteralCurrentSchemaAndPreservesCase() throws Exception {
        Fixture jdbc = new Fixture();
        jdbc.currentSchema = "Team_2026";
        jdbc.tableRows = Collections.singletonList(table("Team_2026", "Order_Items"));

        List<String[]> result = extractor.listTableSpecs(jdbc.connection,
            Collections.emptyList(), Collections.emptyList());

        assertArrayEquals(new Object[]{null, "Team\\_2026", "%"}, jdbc.tableCalls.get(0));
        assertArrayEquals(new String[]{"Team_2026", "Order_Items"}, result.get(0));
        assertEquals(Collections.singletonList("Order_Items"),
            extractor.listTables(jdbc.metadata, "DM", null, null));
        assertArrayEquals(new Object[]{null, "Team\\_2026", "%"}, jdbc.tableCalls.get(1));
    }

    @Test
    public void columnsUseExactMetadataPatternsAndBothColumnsAndCompositeKeysKeepTheirOrder() throws Exception {
        Fixture jdbc = new Fixture();
        jdbc.columnRows = Arrays.asList(row("ORDINAL_POSITION", 3, "COLUMN_NAME", "Payload"),
            row("ORDINAL_POSITION", 1, "COLUMN_NAME", "Tenant_Id"),
            row("ORDINAL_POSITION", 2, "COLUMN_NAME", "Order_Id"));
        jdbc.keyRows = Arrays.asList(row("KEY_SEQ", 2, "COLUMN_NAME", "Order_Id"),
            row("KEY_SEQ", 1, "COLUMN_NAME", "Tenant_Id"));

        assertEquals(Arrays.asList("Tenant_Id", "Order_Id", "Payload"),
            extractor.listColumns(jdbc.connection, "App_1", "Order_%"));
        assertArrayEquals(new Object[]{null, "App\\_1", "Order\\_\\%", "%"}, jdbc.columnCall);
        assertEquals(Arrays.asList("Tenant_Id", "Order_Id"),
            extractor.listPrimaryKeys(jdbc.connection, "App_1", "Order_%"));
        assertArrayEquals(new Object[]{null, "App_1", "Order_%"}, jdbc.keyCall);
        assertEquals(2, jdbc.closedResultSets);
    }

    @Test
    public void queryBuildersPreserveQuotedNamesAndUseAllCompositeKeys() {
        List<String> keys = Arrays.asList("Tenant_Id", "Order\"Id");
        assertEquals("SELECT * FROM \"App\".\"Order\"\"Items\" ORDER BY \"Changed\", \"Tenant_Id\", \"Order\"\"Id\"",
            extractor.buildSelectFull("App", "Order\"Items", "Changed", keys));
        assertEquals("SELECT * FROM \"Orders\"", extractor.buildSelectFull(null, "Orders", null, Collections.emptyList()));
        assertEquals("SELECT * FROM \"App\".\"Orders\" WHERE \"Changed\" > ? ORDER BY \"Changed\", \"Tenant_Id\", \"Order\"\"Id\"",
            extractor.buildSelectIncremental("App", "Orders", "Changed", keys));
        assertEquals("SELECT MIN(\"Changed\"), MAX(\"Changed\") FROM \"App\".\"Orders\" WHERE \"Changed\" IS NOT NULL",
            extractor.buildSelectTimestampBounds("App", "Orders", "Changed"));
        assertEquals("SELECT * FROM \"App\".\"Orders\" WHERE \"Changed\" >= ? AND \"Changed\" < ?",
            extractor.buildSelectFullWindow("App", "Orders", "Changed", keys));
        assertTrue(extractor.supportsTimestampWindowFullSync());
    }

    @Test(expected = IllegalArgumentException.class)
    public void incrementalQueryRequiresTimestampField() {
        extractor.buildSelectIncremental("App", "Orders", null, Collections.singletonList("Id"));
    }

    private static Map<String, Object> table(String schema, String table) {
        return row("TABLE_SCHEM", schema, "TABLE_NAME", table);
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }

    private static final class Fixture {
        final Connection connection;
        final DatabaseMetaData metadata;
        List<Map<String, Object>> tableRows = Collections.emptyList();
        List<Map<String, Object>> columnRows = Collections.emptyList();
        List<Map<String, Object>> keyRows = Collections.emptyList();
        final List<Object[]> tableCalls = new ArrayList<>();
        Object[] columnCall;
        Object[] keyCall;
        String currentSchema = "CurrentApp";
        int closedResultSets;
        boolean connectionClosed;

        Fixture() {
            metadata = (DatabaseMetaData) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getConnection": return connection();
                        case "getSearchStringEscape": return "\\";
                        case "getTables":
                            assertArrayEquals(new String[]{"TABLE"}, (String[]) args[3]);
                            tableCalls.add(Arrays.copyOf(args, 3));
                            return resultSet(tableRows);
                        case "getColumns": columnCall = args.clone(); return resultSet(columnRows);
                        case "getPrimaryKeys": keyCall = args.clone(); return resultSet(keyRows);
                        default: throw new AssertionError("Unexpected metadata method: " + method.getName());
                    }
                });
            connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getSchema": return currentSchema;
                        case "getMetaData": return metadata;
                        case "close": connectionClosed = true; return null;
                        default: throw new AssertionError("Unexpected connection method: " + method.getName());
                    }
                });
        }

        private Connection connection() { return connection; }

        private ResultSet resultSet(List<Map<String, Object>> rows) {
            int[] index = {-1};
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "next": return ++index[0] < rows.size();
                        case "getString": return (String) rows.get(index[0]).get(args[0]);
                        case "getInt": return ((Number) rows.get(index[0]).get(args[0])).intValue();
                        case "close": closedResultSets++; return null;
                        default: throw new AssertionError("Unexpected result method: " + method.getName());
                    }
                });
        }
    }
}
