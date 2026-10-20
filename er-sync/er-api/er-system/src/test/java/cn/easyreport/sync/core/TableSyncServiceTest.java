package cn.easyreport.sync.core;

import cn.easyreport.sync.DataSourceConnector;
import cn.easyreport.sync.domain.ErDataSync;
import cn.easyreport.sync.domain.ErDataSyncLog;
import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.model.ColumnMeta;
import cn.easyreport.sync.model.TableMeta;
import cn.easyreport.sync.registry.DatabaseComponentRegistry;
import org.junit.Before;
import org.junit.Test;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.*;

public class TableSyncServiceTest {

    @Before
    public void clearRegistry() {
        DatabaseComponentRegistry.clearCache();
    }

    @Test
    public void loadsAllComponentsFromRegistryResource() {
        List<String> databaseTypes = Arrays.asList("MYSQL", "TIDB", "STARROCKS", "SQLSERVER", "POSTGRESQL", "ORACLE");
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
    public void retainsGeneratedDdlWhenExecutionFails() {
        RecordingConnector connector = new RecordingConnector(true);

        ErDataSyncLog result = new TableSyncService().syncTable(context(connector));

        assertEquals("1", result.getSyncResult());
        assertEquals("DDL rejected", result.getErrorMessage());
        assertNotNull(connector.sql);
        assertEquals(connector.sql, result.getExecuteSql());
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
            if (failExecution) {
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
