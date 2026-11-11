package cn.easyreport.sync.dm;

import cn.easyreport.sync.ConnectorFactory;
import cn.easyreport.sync.core.TargetConfig;
import cn.easyreport.sync.dialect.DialectFactory;
import cn.easyreport.sync.dialect.DmDialect;
import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.extractor.ExtractorFactory;
import cn.easyreport.sync.extractor.DmSchemaExtractor;
import cn.easyreport.sync.model.ColumnMeta;
import cn.easyreport.sync.model.TableMeta;
import cn.easyreport.sync.model.IndexMeta;
import cn.easyreport.sync.strategy.DatabaseStrategy;
import cn.easyreport.sync.strategy.DatabaseStrategyFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Logger;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class DmSchemaSupportTest {
    private FixtureDriver driver;

    @Before
    public void setup() throws SQLException {
        driver = new FixtureDriver();
        DriverManager.registerDriver(driver);
    }

    @After
    public void cleanup() throws SQLException {
        DriverManager.deregisterDriver(driver);
    }

    @Test
    public void readsJdbcMetadataPreservingSchemaCaseTypeDetailsAndKeyOrder() throws Exception {
        List<TableMeta> tables = new DmSchemaExtractor(driver.datasource()).listTables(null, "%");

        assertEquals(1, tables.size());
        TableMeta table = tables.get(0);
        assertEquals("Sales_East", table.getSchema());
        assertEquals("Order_Items", table.getName());
        assertEquals("Orders", table.getComment());
        assertEquals(Arrays.asList("tenant_id", "id"), table.getPrimaryKey());
        assertEquals(5, table.getColumns().size());
        ColumnMeta id = table.getColumns().get(0);
        assertEquals("BIGINT", id.getColumnType());
        assertTrue(id.isAutoIncrement());
        assertNull(id.getDefaultValue());
        assertEquals("VARCHAR(100)", table.getColumns().get(1).getColumnType());
        assertEquals("a::b", table.getColumns().get(1).getDefaultValue());
        assertEquals("NUMBER(18,2)", table.getColumns().get(2).getColumnType());
        assertEquals("TIMESTAMP(6)", table.getColumns().get(4).getColumnType());
        assertEquals(1, table.getIndexes().size());
        assertEquals(Arrays.asList("description", "tenant_id"), table.getIndexes().get(0).getColumns());
        assertTrue(table.getIndexes().get(0).isUnique());
        assertEquals(Arrays.asList("Sales\\_East", "Order\\_Items"), driver.columnLookups.get(0));
        assertEquals(1, driver.closedConnections);
    }

    @Test
    public void shardPreviewOrdersSchemasAndReadsNoColumnMetadata() throws Exception {
        List<TableMeta> tables = new DmSchemaExtractor(driver.datasource())
            .listTableNamesUsingFirstSchema("Sales\\_%", "%");

        assertEquals(2, tables.size());
        assertEquals("Sales_East", tables.get(0).getSchema());
        assertEquals("Sales_West", tables.get(1).getSchema());
        assertTrue(tables.get(0).getColumns().isEmpty());
        assertTrue(driver.columnLookups.isEmpty());
        assertEquals(Collections.singletonList("Sales\\_East"), driver.tableSchemas);
        assertEquals(1, driver.closedConnections);
    }

    @Test
    public void shardMetadataIsLoadedOnlyOnceAndExpandedAsIndependentCopies() throws Exception {
        List<TableMeta> tables = new DmSchemaExtractor(driver.datasource())
            .listTablesUsingFirstSchema("Sales\\_%", "%");

        assertEquals(2, tables.size());
        assertEquals(1, driver.columnLookups.size());
        tables.get(0).getColumns().get(0).setColumnType("modified");
        assertEquals("BIGINT", tables.get(1).getColumns().get(0).getColumnType());
        assertNotSame(tables.get(0).getIndexes().get(0), tables.get(1).getIndexes().get(0));
        assertNull(tables.get(1).getColumns().get(0).getDefaultValueLiteral());
        assertEquals(Boolean.TRUE, tables.get(1).getColumns().get(1).getDefaultValueLiteral());
        assertEquals(Boolean.FALSE, tables.get(1).getColumns().get(2).getDefaultValueLiteral());
        tables.get(0).getColumns().get(1).setDefaultValueLiteral(false);
        assertEquals(Boolean.TRUE, tables.get(1).getColumns().get(1).getDefaultValueLiteral());
    }

    @Test
    public void ordinaryPreviewAlsoAvoidsLoadingColumnMetadata() throws Exception {
        List<TableMeta> tables = new DmSchemaExtractor(driver.datasource()).listTableNames("Sales\\_%", "%");
        assertEquals(2, tables.size());
        assertTrue(driver.columnLookups.isEmpty());
    }

    @Test
    public void roundTripsLiteralDefaultsWithoutTreatingTheirContentsAsSql() throws Exception {
        String[][] cases = {
            {"'NULL'", "NULL", "'NULL'"},
            {"N'NULL'", "NULL", "'NULL'"},
            {"'CURRENT_TIMESTAMP'", "CURRENT_TIMESTAMP", "'CURRENT_TIMESTAMP'"},
            {"'''quoted'''", "'quoted'", "'''quoted'''"},
            {"'0012'", "0012", "'0012'"}
        };
        for (String[] testCase : cases) {
            driver.descriptionDefault = testCase[0];
            TableMeta table = new DmSchemaExtractor(driver.datasource()).listTables(null, "%").get(0);
            ColumnMeta description = table.getColumns().get(1);

            assertEquals(testCase[0], testCase[1], description.getDefaultValue());
            assertEquals(testCase[0], Boolean.TRUE, description.getDefaultValueLiteral());
            assertTrue(testCase[0], new DmDialect().createTable(table)
                .contains("\"description\" VARCHAR(100) DEFAULT " + testCase[2]));
        }
    }

    @Test
    public void retainsSqlDefaultExpressionsIncludingCastsAndArithmetic() throws Exception {
        for (String expression : Arrays.asList("(1 + 2)", "CAST('1' AS INT) + 2", "ABS(-1)")) {
            driver.amountDefault = expression;
            TableMeta table = new DmSchemaExtractor(driver.datasource()).listTables(null, "%").get(0);
            ColumnMeta amount = table.getColumns().get(2);

            assertEquals(expression, amount.getDefaultValue());
            assertEquals(Boolean.FALSE, amount.getDefaultValueLiteral());
            assertTrue(expression, new DmDialect().createTable(table)
                .contains("\"amount\" NUMBER(18,2) DEFAULT " + expression));
            assertEquals(Boolean.FALSE, table.getColumns().get(4).getDefaultValueLiteral());
            assertTrue(new DmDialect().createTable(table)
                .contains("\"occurred_at\" TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP"));
        }
    }

    @Test
    public void doesNotEmitIllegalNumericPrecisionAndPreservesExplicitTypeParameters() throws Exception {
        driver.numericPrecisionSentinel = true;

        TableMeta table = new DmSchemaExtractor(driver.datasource()).listTables(null, "%").get(0);

        assertEquals("NUMERIC", table.getColumns().get(2).getColumnType());
        assertEquals("NUMERIC(12,4)", table.getColumns().get(3).getColumnType());
    }

    @Test
    public void skipsEntireExpressionAndPartialIndexesIncludingEarlierAndLaterOrdinaryColumns() throws Exception {
        driver.complexIndexes = true;

        TableMeta table = new DmSchemaExtractor(driver.datasource()).listTables(null, "%").get(0);

        assertEquals(1, table.getIndexes().size());
        assertEquals("uk_desc_tenant", table.getIndexes().get(0).getName());
        assertEquals(Arrays.asList("description", "tenant_id"), table.getIndexes().get(0).getColumns());
    }

    @Test
    public void emptySchemaFallsBackToCurrentSchemaQueryForOlderDriver() throws Exception {
        driver.oldDriver = true;
        new DmSchemaExtractor(driver.datasource()).listTableNames(null, "%");
        assertEquals(Collections.singletonList("SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM DUAL"), driver.queries);
        assertEquals(Collections.singletonList("Sales\\_East"), driver.tableSchemas);
        assertEquals(1, driver.closedConnections);
    }

    @Test
    public void noCurrentSchemaFailsInsteadOfEnumeratingEverySchema() throws Exception {
        driver.currentSchema = null;
        try {
            new DmSchemaExtractor(driver.datasource()).listTables(null, "%");
            fail("No current schema should fail with actionable diagnostics");
        } catch (SQLException expected) {
            assertTrue(expected.getMessage().contains("Schema"));
        }
        assertTrue(driver.tableSchemas.isEmpty());
        assertEquals(1, driver.closedConnections);
    }

    @Test
    public void connectorAndFactoriesUseDmWithoutPublicOrDatabaseSchemaFallback() throws Exception {
        ErDatasource datasource = driver.datasource();
        assertTrue(ExtractorFactory.build(datasource) instanceof DmSchemaExtractor);
        assertTrue(DialectFactory.build(datasource) instanceof DmDialect);
        DatabaseStrategy strategy = DatabaseStrategyFactory.getStrategy("dm");
        assertEquals("CREATE SCHEMA \"Sales\"\"East\"", strategy.buildCreateDatabase("Sales\"East"));
        assertEquals("DROP TABLE IF EXISTS \"Sales\".\"Order\"\"Items\"", strategy.buildDropTable(null, "Sales", "Order\"Items"));
        assertNull(strategy.buildUseDatabase("other_db"));
        TargetConfig config = TargetConfig.create(null, "DM", "appdb");
        assertNull(config.getDatabase());
        assertNull(config.getSchema());
        try (DmConnector connector = (DmConnector) ConnectorFactory.build(datasource)) {
            assertTrue(connector.tableExists(null, "Order_Items"));
            assertEquals("Order\\_Items", driver.tablePatterns.get(0));
            connector.ensureSchemaExists("Sales_East");
            assertTrue(driver.executedSql.isEmpty());
            connector.ensureSchemaExists("New\"Schema");
            assertEquals(Collections.singletonList("CREATE SCHEMA \"New\"\"Schema\""), driver.executedSql);
        }
    }

    @Test
    public void dmDateRetainsDateSemanticsAndTimePrecisionIsSeparate() throws Exception {
        driver.temporalTypes = true;
        TableMeta table = new DmSchemaExtractor(driver.datasource()).listTables(null, "%").get(0);
        assertEquals("DATE", table.getColumns().get(2).getColumnType());
        assertEquals("TIME(3)", table.getColumns().get(3).getColumnType());
        assertEquals("TIMESTAMP(6) WITH LOCAL TIME ZONE", table.getColumns().get(4).getColumnType());
    }

    @Test
    public void dmIdentityPromotesSmallIntegersAndRejectsFractionalTypes() {
        TableMeta table = new TableMeta();
        table.setName("ids");
        ColumnMeta id = new ColumnMeta();
        id.setName("id");
        id.setColumnType("SMALLINT");
        id.setAutoIncrement(true);
        table.setColumns(Collections.singletonList(id));
        assertTrue(new DmDialect().createTable(table).contains("\"id\" INT IDENTITY(1,1)"));
        id.setColumnType("NUMERIC(10,2)");
        try {
            new DmDialect().createTable(table);
            fail("Fractional identity must not silently lose the source precision");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("IDENTITY"));
        }
    }

    @Test
    public void ordinaryDmTablesEnableLongRowsForMultipleVariableWidthColumns() {
        TableMeta table = new TableMeta();
        table.setName("wide_rows");
        ColumnMeta first = new ColumnMeta();
        first.setName("first");
        first.setColumnType("VARCHAR(400 CHAR)");
        ColumnMeta second = new ColumnMeta();
        second.setName("second");
        second.setColumnType("VARCHAR(400 CHAR)");
        table.setColumns(Arrays.asList(first, second));

        String ddl = new DmDialect().createTable(table);

        assertTrue(ddl, ddl.contains("\"first\" VARCHAR(400 CHAR)"));
        assertTrue(ddl, ddl.contains("\"second\" VARCHAR(400 CHAR)"));
        assertTrue(ddl, ddl.endsWith(") STORAGE (USING LONG ROW)"));
    }

    @Test
    public void lobPrimaryKeysFailClearlyWithoutNarrowingOrRemovingTheKey() {
        for (String type : Arrays.asList("CLOB", "NCLOB", "BLOB", "TEXT", "IMAGE")) {
            TableMeta table = new TableMeta();
            table.setName("wide_keys");
            table.setPrimaryKey(Collections.singletonList("id"));
            ColumnMeta id = new ColumnMeta();
            id.setName("id");
            id.setColumnType(type);
            table.setColumns(Collections.singletonList(id));
            try {
                new DmDialect().createTable(table);
                fail("DM must not silently truncate or discard a LOB primary key");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains(type));
                assertTrue(expected.getMessage().contains("id"));
            }
            assertEquals(type, id.getColumnType());
            assertEquals(Collections.singletonList("id"), table.getPrimaryKey());
        }
    }

    @Test
    public void dialectPreservesIdentityValuesEmptyDefaultsAndQuotedNames() {
        TableMeta table = new TableMeta();
        table.setName("Order\"Items");
        ColumnMeta id = new ColumnMeta();
        id.setName("id");
        id.setColumnType("BIGINT");
        id.setAutoIncrement(true);
        id.setDefaultValue("nextval('old_sequence')");
        ColumnMeta note = new ColumnMeta();
        note.setName("note");
        note.setColumnType("VARCHAR(20)");
        note.setNullable(true);
        note.setDefaultValue("");
        table.setColumns(Arrays.asList(id, note));

        String ddl = new DmDialect().createTable(table);

        assertTrue(ddl, ddl.startsWith("CREATE TABLE IF NOT EXISTS \"Order\"\"Items\""));
        assertTrue(ddl, ddl.contains("\"id\" BIGINT IDENTITY(1,1) NOT NULL"));
        assertTrue(ddl, ddl.contains("\"note\" VARCHAR(20) DEFAULT ''"));
        assertFalse(ddl, ddl.contains("old_sequence"));
        assertFalse(ddl, ddl.contains("public"));
    }

    @Test
    public void dialectConvertsBinaryAndNumericDefaultsAccordingToTargetColumnType() {
        TableMeta table = new TableMeta();
        table.setName("flags");
        ColumnMeta bits = new ColumnMeta();
        bits.setName("bits");
        bits.setColumnType("NUMERIC(20,0)");
        bits.setDefaultValue("b'101'");
        ColumnMeta enabled = new ColumnMeta();
        enabled.setName("enabled");
        enabled.setColumnType("BIT");
        enabled.setDefaultValue("((1))");
        ColumnMeta disabled = new ColumnMeta();
        disabled.setName("disabled");
        disabled.setColumnType("BIT");
        disabled.setDefaultValue("b'0'");
        table.setColumns(Arrays.asList(bits, enabled, disabled));

        String ddl = new DmDialect().createTable(table);

        assertTrue(ddl, ddl.contains("\"bits\" NUMERIC(20,0) DEFAULT 5"));
        assertTrue(ddl, ddl.contains("\"enabled\" BIT DEFAULT 1"));
        assertTrue(ddl, ddl.contains("\"disabled\" BIT DEFAULT 0"));
    }

    @Test
    public void legacyNumericDefaultsFromSqlServerDiscardRedundantParentheses() {
        TableMeta table = new TableMeta();
        table.setName("counts");
        ColumnMeta count = new ColumnMeta();
        count.setName("count");
        count.setColumnType("INTEGER");
        count.setDefaultValue("((0))");
        table.setColumns(Collections.singletonList(count));

        assertNull(count.getDefaultValueLiteral());
        assertTrue(new DmDialect().createTable(table).contains("\"count\" INTEGER DEFAULT 0 NOT NULL"));
    }

    @Test
    public void indexNamesAreStableAndDistinctAcrossTablesAndWithinIdentifierByteLimit() {
        TableMeta table = new TableMeta();
        table.setSchema("Sales");
        table.setName("Orders");
        IndexMeta index = new IndexMeta();
        index.setName("idx_status");
        index.setColumns(Collections.singletonList("status"));
        table.setIndexes(Collections.singletonList(index));
        DmDialect dialect = new DmDialect();
        String first = dialect.postCreateStatements(table).get(0);
        assertEquals(first, dialect.postCreateStatements(table).get(0));
        table.setName("Returns");
        String second = dialect.postCreateStatements(table).get(0);
        assertNotEquals(first.substring(0, first.indexOf(" ON ")), second.substring(0, second.indexOf(" ON ")));
        table.setName("订单订单订单订单订单订单订单订单订单订单订单订单订单订单订单订单订单订单订单");
        String longDdl = dialect.postCreateStatements(table).get(0);
        String generatedName = longDdl.substring(longDdl.indexOf('"') + 1, longDdl.indexOf('"', longDdl.indexOf('"') + 1));
        assertTrue(generatedName, generatedName.getBytes(StandardCharsets.UTF_8).length <= 128);
        assertFalse(generatedName, generatedName.contains("\ufffd"));
    }

    public static class FixtureDriver implements Driver {
        private final String url = "jdbc:dm-schema-fixture:" + UUID.randomUUID();
        private String currentSchema = "Sales_East";
        private boolean oldDriver;
        private boolean numericPrecisionSentinel;
        private boolean complexIndexes;
        private boolean temporalTypes;
        private String descriptionDefault = "'a::b'";
        private String amountDefault = "0";
        private int closedConnections;
        private final List<String> queries = new ArrayList<>();
        private final List<String> executedSql = new ArrayList<>();
        private final List<String> tableSchemas = new ArrayList<>();
        private final List<String> tablePatterns = new ArrayList<>();
        private final List<List<String>> columnLookups = new ArrayList<>();

        private ErDatasource datasource() {
            ErDatasource datasource = new ErDatasource();
            datasource.setDatasourceType("DM");
            datasource.setDriverClass(FixtureDriver.class.getName());
            datasource.setJdbcUrl(url);
            datasource.setDatabaseName("appdb");
            return datasource;
        }

        @Override
        public Connection connect(String url, Properties properties) {
            if (!acceptsURL(url)) return null;
            return proxy(Connection.class, (method, args) -> {
                if ("getSchema".equals(method)) {
                    if (oldDriver) throw new SQLFeatureNotSupportedException("old JDBC");
                    return currentSchema;
                }
                if ("getCatalog".equals(method)) return "appdb";
                if ("getMetaData".equals(method)) return metadata();
                if ("createStatement".equals(method)) {
                    return proxy(Statement.class, (name, parameters) -> {
                        if ("executeQuery".equals(name)) {
                            queries.add((String) parameters[0]);
                            return rows(Collections.singletonList(row("schema", currentSchema)));
                        }
                        if ("execute".equals(name)) executedSql.add((String) parameters[0]);
                        return null;
                    });
                }
                if ("close".equals(method)) closedConnections++;
                return null;
            });
        }

        private DatabaseMetaData metadata() {
            return proxy(DatabaseMetaData.class, (method, args) -> {
                if ("getSearchStringEscape".equals(method)) return "\\";
                if ("getSchemas".equals(method)) {
                    return rows(Arrays.asList(row("TABLE_SCHEM", "Sales_West"), row("TABLE_SCHEM", "SYS"),
                        row("TABLE_SCHEM", "Sales_East"), row("TABLE_SCHEM", "SalesXOther")));
                }
                if ("getTables".equals(method)) {
                    tableSchemas.add((String) args[1]);
                    tablePatterns.add((String) args[2]);
                    String schema = ((String) args[1]).replace("\\_", "_");
                    return rows(Collections.singletonList(row("TABLE_SCHEM", schema, "TABLE_NAME", "Order_Items", "REMARKS", "Orders")));
                }
                if ("getColumns".equals(method)) {
                    columnLookups.add(Arrays.asList((String) args[1], (String) args[2]));
                    return rows(Arrays.asList(column("description", "varchar", Types.VARCHAR, 100, 0, 2, descriptionDefault, "NO"),
                        column("id", "BIGINT", Types.BIGINT, 19, 0, 1, "IDENTITY", "YES"),
                        column("amount", temporalTypes ? "DATE" : numericPrecisionSentinel ? "NUMERIC" : "NUMBER", temporalTypes ? Types.DATE : Types.NUMERIC,
                            numericPrecisionSentinel ? 131089 : 18, 2, 3, amountDefault, "NO"),
                        column("tenant_id", temporalTypes ? "TIME" : numericPrecisionSentinel ? "NUMERIC(12,4)" : "INTEGER",
                            temporalTypes ? Types.TIME : numericPrecisionSentinel ? Types.NUMERIC : Types.INTEGER,
                            numericPrecisionSentinel ? 131089 : 10, temporalTypes ? 3 : 0, 4, null, "NO"),
                        column("occurred_at", temporalTypes ? "TIMESTAMP WITH LOCAL TIME ZONE" : "TIMESTAMP", Types.TIMESTAMP, 29, 6, 5, "CURRENT_TIMESTAMP", "NO")));
                }
                if ("getPrimaryKeys".equals(method)) {
                    return rows(Arrays.asList(row("KEY_SEQ", 2, "COLUMN_NAME", "id", "PK_NAME", "pk_orders"),
                        row("KEY_SEQ", 1, "COLUMN_NAME", "tenant_id", "PK_NAME", "pk_orders")));
                }
                if ("getIndexInfo".equals(method)) {
                    List<Map<String, Object>> indexes = new ArrayList<>(Arrays.asList(index("pk_orders", "id", 1),
                        index("uk_desc_tenant", "tenant_id", 2), index("uk_desc_tenant", "description", 1)));
                    if (complexIndexes) {
                        indexes.add(index("uk_expression", "id", 1));
                        indexes.add(index("uk_expression", null, 2));
                        indexes.add(index("uk_expression", "description", 3));
                        indexes.add(index("uk_named_expression", "UPPER(description)", 1));
                        indexes.add(index("uk_partial", "id", 1));
                        Map<String, Object> partialColumn = index("uk_partial", "description", 2);
                        partialColumn.put("FILTER_CONDITION", "description IS NOT NULL");
                        indexes.add(partialColumn);
                        indexes.add(index("uk_partial", "tenant_id", 3));
                    }
                    return rows(indexes);
                }
                return null;
            });
        }

        private Map<String, Object> column(String name, String type, int jdbcType, int size, int scale, int ordinal, String def, String identity) {
            return row("COLUMN_NAME", name, "TYPE_NAME", type, "DATA_TYPE", jdbcType, "COLUMN_SIZE", size,
                "DECIMAL_DIGITS", scale, "ORDINAL_POSITION", ordinal, "NULLABLE", DatabaseMetaData.columnNullable,
                "COLUMN_DEF", def, "IS_AUTOINCREMENT", identity, "REMARKS", "");
        }

        private Map<String, Object> index(String name, String column, int ordinal) {
            return row("TYPE", DatabaseMetaData.tableIndexOther, "INDEX_NAME", name, "COLUMN_NAME", column,
                "ORDINAL_POSITION", ordinal, "NON_UNIQUE", false);
        }

        @Override public boolean acceptsURL(String url) { return this.url.equals(url); }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
        @Override public int getMajorVersion() { return 1; }
        @Override public int getMinorVersion() { return 0; }
        @Override public boolean jdbcCompliant() { return false; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
    }

    private interface Call {
        Object invoke(String method, Object[] arguments) throws Throwable;
    }

    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (object, method, args) -> {
            Object value = call.invoke(method.getName(), args);
            if (value != null || !method.getReturnType().isPrimitive()) return value;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == short.class) return (short) 0;
            if (method.getReturnType() == long.class) return 0L;
            return 0;
        }));
    }

    private static ResultSet rows(List<Map<String, Object>> values) {
        int[] position = {-1};
        boolean[] wasNull = {false};
        return proxy(ResultSet.class, (method, args) -> {
            if ("next".equals(method)) return ++position[0] < values.size();
            if ("wasNull".equals(method)) return wasNull[0];
            if (method.startsWith("get")) {
                Map<String, Object> row = values.get(position[0]);
                Object value = args[0] instanceof Integer ? new ArrayList<>(row.values()).get((Integer) args[0] - 1) : row.get(args[0]);
                wasNull[0] = value == null;
                if ("getString".equals(method)) return value == null ? null : value.toString();
                if ("getInt".equals(method)) return value == null ? 0 : ((Number) value).intValue();
                if ("getShort".equals(method)) return value == null ? (short) 0 : ((Number) value).shortValue();
                return value;
            }
            return null;
        });
    }

    private static Map<String, Object> row(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
        return result;
    }
}
