package cn.easyreport.sync.core;

import cn.easyreport.sync.DataSourceConnector;
import cn.easyreport.sync.dialect.ExtendedDialectFactory;
import cn.easyreport.sync.dialect.SchemaDialect;
import cn.easyreport.sync.dialect.StarRocksDialect;
import cn.easyreport.sync.domain.ErDataSync;
import cn.easyreport.sync.domain.ErDataSyncLog;
import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.model.ColumnMeta;
import cn.easyreport.sync.model.TableMeta;
import cn.easyreport.sync.registry.DatabaseComponentRegistry;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class)
public class StarRocksSourceRoutesTest {

    @Parameterized.Parameters(name = "{0} -> STARROCKS")
    public static Collection<Object[]> sources() {
        return Arrays.asList(new Object[][] {
            {"MYSQL"}, {"TIDB"}, {"STARROCKS"}, {"SQLSERVER"},
            {"POSTGRESQL"}, {"ORACLE"}, {"KINGBASE"}, {"DM"}
        });
    }

    private final String sourceType;

    public StarRocksSourceRoutesTest(String sourceType) {
        this.sourceType = sourceType;
    }

    @Before
    public void clearRegistry() {
        DatabaseComponentRegistry.clearCache();
    }

    @Test
    public void placesCompositeKeyFirstThroughRegisteredTargetDialectAndSourceMapper() {
        ErDatasource source = datasource(sourceType);
        ErDatasource target = datasource("STARROCKS");
        assertNotNull(DatabaseComponentRegistry.getTypeMapper(sourceType));
        assertTrue(DatabaseComponentRegistry.getTypeMapper(sourceType).supportsTarget("STARROCKS"));
        SchemaDialect dialect = ExtendedDialectFactory.build(target);
        assertTrue(dialect instanceof StarRocksDialect);

        TableMeta table = new TableMeta();
        table.setName("resource");
        table.setPrimaryKey(Arrays.asList("id", "is_split_merge"));
        List<ColumnMeta> sourceColumns = Collections.unmodifiableList(Arrays.asList(
            column("note", "STARROCKS".equals(sourceType) ? "STRING" : "VARCHAR(50)"),
            column("id", "BIGINT"),
            column("sort", "INT"),
            column("is_split_merge", "INT")));
        table.setColumns(sourceColumns);
        ErDataSync task = new ErDataSync();
        task.setSyncId(1L);
        task.setIfExistsAction("0");
        RecordingConnector connector = new RecordingConnector();
        TableSyncContext context = new TableSyncContext(task, source, target, connector, dialect,
            null, new TargetConfig(null, "target_db", "STARROCKS"), table, "source_db");

        ErDataSyncLog result = new TableSyncService().syncTable(context);

        assertEquals(result.getErrorMessage(), "0", result.getSyncResult());
        assertEquals("CREATE", result.getSyncAction());
        assertEquals(1, connector.executionCount);
        assertEquals(connector.sql, result.getExecuteSql());
        assertTrue(connector.sql, connector.sql.startsWith(
            "CREATE TABLE IF NOT EXISTS `target_db`.`resource` (\n"));
        List<String> ddlColumns = Arrays.stream(connector.sql.split("\n"))
            .filter(line -> line.startsWith("  `"))
            .map(line -> line.substring(3, line.indexOf('`', 3)))
            .collect(Collectors.toList());
        assertEquals(Arrays.asList("id", "is_split_merge", "note", "sort"), ddlColumns);
        assertTrue(connector.sql, connector.sql.contains("PRIMARY KEY (`id`, `is_split_merge`)"));
        assertTrue(connector.sql, connector.sql.contains("DISTRIBUTED BY HASH(`id`) BUCKETS 10"));
        assertSame(sourceColumns, table.getColumns());
        assertEquals(Arrays.asList("note", "id", "sort", "is_split_merge"),
            table.getColumns().stream().map(ColumnMeta::getName).collect(Collectors.toList()));
        assertEquals(Arrays.asList("id", "is_split_merge"), table.getPrimaryKey());
    }

    private static ErDatasource datasource(String type) {
        ErDatasource datasource = new ErDatasource();
        datasource.setDatasourceType(type);
        return datasource;
    }

    private static ColumnMeta column(String name, String type) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setColumnType(type);
        return column;
    }

    private static class RecordingConnector implements DataSourceConnector {
        private String sql;
        private int executionCount;

        @Override
        public boolean tableExists(String schemaPattern, String tableName) {
            return false;
        }

        @Override
        public void execute(String sql) {
            this.sql = sql;
            executionCount++;
        }

        @Override
        public List<String> listTables(String schemaPattern, String tablePattern) {
            throw new UnsupportedOperationException("No database access in this regression test");
        }

        @Override
        public String getCreateTableSql(String schemaPattern, String tableName) {
            throw new UnsupportedOperationException("No database access in this regression test");
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
