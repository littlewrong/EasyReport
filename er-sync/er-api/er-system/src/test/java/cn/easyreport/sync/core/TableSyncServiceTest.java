package cn.easyreport.sync.core;

import cn.easyreport.sync.DataSourceConnector;
import cn.easyreport.sync.domain.ErDataSync;
import cn.easyreport.sync.domain.ErDataSyncLog;
import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.model.ColumnMeta;
import cn.easyreport.sync.model.TableMeta;
import cn.easyreport.sync.model.IndexMeta;
import cn.easyreport.sync.dialect.KingbaseDialect;
import cn.easyreport.sync.dialect.DmDialect;
import cn.easyreport.sync.registry.DatabaseComponentRegistry;
import org.junit.Before;
import org.junit.Test;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;

import static org.junit.Assert.*;

public class TableSyncServiceTest {

    @Before
    public void clearRegistry() {
        DatabaseComponentRegistry.clearCache();
    }

    @Test
    public void loadsAllComponentsFromRegistryResource() {
        List<String> databaseTypes = Arrays.asList("MYSQL", "TIDB", "STARROCKS", "SQLSERVER", "POSTGRESQL", "ORACLE", "KINGBASE", "DM");
        assertEquals(new HashSet<>(databaseTypes), new HashSet<>(DatabaseComponentRegistry.getRegisteredDatabaseTypes()));
        for (String databaseType : databaseTypes) {
            ErDatasource datasource = datasource(databaseType);
            assertNotNull(databaseType + " mapper", DatabaseComponentRegistry.getTypeMapper(databaseType));
            assertNotNull(databaseType + " extractor", DatabaseComponentRegistry.getExtractor(datasource));
            assertNotNull(databaseType + " dialect", DatabaseComponentRegistry.getDialect(datasource));
        }
    }

    @Test
    public void mapsTiDbTypesBeforeExecutingStarRocksDdl() {
        RecordingConnector connector = new RecordingConnector(false);
        TableSyncContext context = context(connector);

        ErDataSyncLog result = new TableSyncService().syncTable(context);

        assertEquals("0", result.getSyncResult());
        assertEquals("CREATE", result.getSyncAction());
        assertEquals(connector.sql, result.getExecuteSql());
        assertTrue(connector.sql.startsWith("CREATE TABLE IF NOT EXISTS `target_db`.`orders`"));
        String[] expectedTypes = {"INT", "DATETIME", "DATETIME", "STRING", "DECIMAL(18,2)"};
        for (int i = 0; i < expectedTypes.length; i++) {
            ColumnMeta column = context.getTableMeta().getColumns().get(i);
            assertEquals(expectedTypes[i], column.getColumnType());
            assertTrue(connector.sql.contains("`" + column.getName() + "` " + expectedTypes[i] + " NOT NULL"));
        }
        assertFalse(connector.sql.toLowerCase().contains("datetime(3)"));
        assertFalse(connector.sql.toLowerCase().contains("timestamp(6)"));
        assertFalse(connector.sql.toLowerCase().contains("int(11)"));
    }

    @Test
    public void mapsMySqlZerofillColumnsBeforeExecutingStarRocksDdl() {
        RecordingConnector connector = new RecordingConnector(false);
        TableSyncContext original = context(connector);
        ColumnMeta restore = column("is_restore", "int(1) unsigned zerofill");
        restore.setNullable(true);
        restore.setDefaultValue("0");
        restore.setComment("是否还原票号");
        ColumnMeta description = column("description", "varchar(100)");
        description.setNullable(true);
        description.setDefaultValue("unsigned zerofill");
        original.getTableMeta().setColumns(Arrays.asList(
                column("id", "bigint(20) unsigned zerofill"), restore, description));
        TableSyncContext context = new TableSyncContext(original.getTask(), datasource("MYSQL"),
                original.getTargetDs(), connector, original.getDialect(), original.getStrategy(),
                original.getTargetConfig(), original.getTableMeta(), original.getSourceSchema());

        ErDataSyncLog result = new TableSyncService().syncTable(context);

        assertEquals("0", result.getSyncResult());
        assertEquals("CREATE", result.getSyncAction());
        assertEquals(connector.sql, result.getExecuteSql());
        assertTrue(connector.sql, connector.sql.contains("`id` LARGEINT NOT NULL"));
        assertTrue(connector.sql, connector.sql.contains(
                "`is_restore` BIGINT NULL DEFAULT \"0\" COMMENT '是否还原票号'"));
        assertTrue(connector.sql, connector.sql.contains(
                "`description` STRING NULL DEFAULT \"unsigned zerofill\""));
        assertTrue(connector.sql, connector.sql.contains("PRIMARY KEY (`id`)"));
        assertEquals("BIGINT", restore.getColumnType());
        assertEquals("0", restore.getDefaultValue());
        assertEquals("是否还原票号", restore.getComment());
    }

    @Test
    public void retainsGeneratedDdlWhenExecutionFails() {
        RecordingConnector connector = new RecordingConnector(true);

        ErDataSyncLog result = new TableSyncService().syncTable(context(connector));

        assertEquals("1", result.getSyncResult());
        assertEquals("DDL rejected", result.getErrorMessage());
        assertNotNull(connector.sql);
        assertEquals(connector.sql, result.getExecuteSql());
    }

    @Test
    public void executesKingbaseTableBeforeIndexesAndComments() {
        RecordingConnector connector = new RecordingConnector(false);
        TableSyncContext context = kingbaseContext(connector);

        ErDataSyncLog result = new TableSyncService().syncTable(context);

        assertEquals("0", result.getSyncResult());
        assertEquals(4, connector.statements.size());
        assertTrue(connector.statements.get(0).startsWith("CREATE TABLE IF NOT EXISTS \"Sales\".\"Orders\""));
        assertTrue(connector.statements.get(1), connector.statements.get(1).startsWith("CREATE INDEX IF NOT EXISTS \"Orders_ix_note_"));
        assertTrue(connector.statements.get(1), connector.statements.get(1).endsWith(" ON \"Sales\".\"Orders\" (\"note\")"));
        assertEquals("COMMENT ON TABLE \"Sales\".\"Orders\" IS 'Order table'", connector.statements.get(2));
        assertEquals("COMMENT ON COLUMN \"Sales\".\"Orders\".\"note\" IS 'Customer''s note'", connector.statements.get(3));
        assertEquals(String.join(";\n", connector.statements), result.getExecuteSql());
    }

    @Test
    public void reportsKingbasePostCreateFailureAndRetainsPlannedDdl() {
        RecordingConnector connector = new RecordingConnector(false);
        connector.failAtStatement = 2;

        ErDataSyncLog result = new TableSyncService().syncTable(kingbaseContext(connector));

        assertEquals("1", result.getSyncResult());
        assertEquals("DDL rejected", result.getErrorMessage());
        assertEquals(2, connector.statements.size());
        assertTrue(result.getExecuteSql().contains("CREATE TABLE"));
        assertTrue(result.getExecuteSql().contains("CREATE INDEX"));
        assertTrue(result.getExecuteSql().contains("COMMENT ON COLUMN"));
    }

    @Test
    public void executesDmNativeTableBeforeIndexesAndComments() {
        RecordingConnector connector = new RecordingConnector(false);
        TableSyncContext original = kingbaseContext(connector);
        ErDatasource ds = datasource("DM");
        ColumnMeta id = column("id", "BIGINT");
        id.setAutoIncrement(true);
        List<ColumnMeta> columns = new ArrayList<>(original.getTableMeta().getColumns());
        columns.add(id);
        original.getTableMeta().setColumns(columns);
        TableSyncContext context = new TableSyncContext(original.getTask(), ds, ds, connector,
            new DmDialect(), null, new TargetConfig(null, "Sales", "DM"), original.getTableMeta(), "Sales");

        ErDataSyncLog result = new TableSyncService().syncTable(context);

        assertEquals("0", result.getSyncResult());
        assertEquals(4, connector.statements.size());
        assertTrue(connector.statements.get(0).contains("\"id\" BIGINT IDENTITY(1,1) NOT NULL"));
        assertTrue(connector.statements.get(1).startsWith("CREATE INDEX IF NOT EXISTS"));
        assertTrue(connector.statements.get(2).startsWith("COMMENT ON TABLE"));
        assertEquals(String.join(";\n", connector.statements), result.getExecuteSql());
    }

    @Test
    public void dmPostCreateFailureKeepsCompletePlannedSqlForDiagnostics() {
        RecordingConnector connector = new RecordingConnector(false);
        connector.failAtStatement = 2;
        TableSyncContext original = kingbaseContext(connector);
        ErDatasource ds = datasource("DM");
        TableSyncContext context = new TableSyncContext(original.getTask(), ds, ds, connector,
            new DmDialect(), null, new TargetConfig(null, "Sales", "DM"), original.getTableMeta(), "Sales");

        ErDataSyncLog result = new TableSyncService().syncTable(context);

        assertEquals("1", result.getSyncResult());
        assertEquals("DDL rejected", result.getErrorMessage());
        assertTrue(result.getExecuteSql().contains("COMMENT ON COLUMN"));
    }

    @Test
    public void invalidDmLobKeyDoesNotDropAnExistingTargetTable() {
        RecordingConnector connector = new RecordingConnector(false);
        TableSyncContext original = kingbaseContext(connector);
        original.getTask().setIfExistsAction("1");
        original.getTableMeta().setColumns(Collections.singletonList(column("id", "CLOB")));
        original.getTableMeta().setPrimaryKey(Collections.singletonList("id"));
        ErDatasource ds = datasource("DM");
        TableSyncContext context = new TableSyncContext(original.getTask(), ds, ds, connector,
            new DmDialect(), cn.easyreport.sync.strategy.DatabaseStrategyFactory.getStrategy("DM"),
            new TargetConfig(null, "Sales", "DM"), original.getTableMeta(), "Sales");

        ErDataSyncLog result = new TableSyncService().syncTable(context);

        assertEquals("1", result.getSyncResult());
        assertTrue(result.getErrorMessage().contains("主键"));
        assertTrue(connector.statements.isEmpty());
    }

    private TableSyncContext kingbaseContext(RecordingConnector connector) {
        ErDataSync task = new ErDataSync();
        task.setSyncId(1L);
        task.setIfExistsAction("0");
        TableMeta table = new TableMeta();
        table.setName("Orders");
        table.setComment("Order table");
        ColumnMeta note = column("note", "VARCHAR(100)");
        note.setComment("Customer's note");
        table.setColumns(Collections.singletonList(note));
        IndexMeta index = new IndexMeta();
        index.setName("ix_note");
        index.setColumns(Collections.singletonList("note"));
        table.setIndexes(Collections.singletonList(index));
        ErDatasource ds = datasource("KINGBASE");
        return new TableSyncContext(task, ds, ds, connector, new KingbaseDialect(), null,
            new TargetConfig(null, "Sales", "KINGBASE"), table, "Sales");
    }

    private TableSyncContext context(RecordingConnector connector) {
        ErDataSync task = new ErDataSync();
        task.setSyncId(1L);
        task.setIfExistsAction("0");
        ErDatasource target = datasource("STARROCKS");
        TableMeta table = new TableMeta();
        table.setName("orders");
        table.setPrimaryKey(Collections.singletonList("id"));
        table.setColumns(Arrays.asList(
                column("id", "int(11)"),
                column("created_at", "datetime(3)"),
                column("updated_at", "timestamp(6)"),
                column("description", "varchar(100)"),
                column("amount", "decimal(18,2)")));
        return new TableSyncContext(task, datasource("TIDB"), target, connector,
                DatabaseComponentRegistry.getDialect(target), null,
                new TargetConfig(null, "target_db", "STARROCKS"), table, "source_db");
    }

    private ErDatasource datasource(String databaseType) {
        ErDatasource datasource = new ErDatasource();
        datasource.setDatasourceType(databaseType);
        return datasource;
    }

    private ColumnMeta column(String name, String type) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setColumnType(type);
        return column;
    }

    private static class RecordingConnector implements DataSourceConnector {
        private final boolean failExecution;
        private String sql;
        private final List<String> statements = new ArrayList<>();
        private int failAtStatement;

        private RecordingConnector(boolean failExecution) {
            this.failExecution = failExecution;
        }

        @Override
        public boolean tableExists(String schemaPattern, String tableName) {
            return false;
        }

        @Override
        public void execute(String sql) throws SQLException {
            this.sql = sql;
            statements.add(sql);
            if (failExecution || statements.size() == failAtStatement) {
                throw new SQLException("DDL rejected");
            }
        }

        @Override
        public List<String> listTables(String schemaPattern, String tablePattern) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getCreateTableSql(String schemaPattern, String tableName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getDatabaseName() {
            return "target_db";
        }

        @Override
        public void close() {
        }
    }
}
