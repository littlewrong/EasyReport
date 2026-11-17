package cn.easyreport.sync.dialect;

import cn.easyreport.sync.model.ColumnMeta;
import cn.easyreport.sync.model.TableMeta;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class StarRocksDialectTest {

    @Test
    public void placesNonconsecutivePrimaryKeyColumnsFirstInDeclaredKeyOrder() {
        TableMeta table = table();
        table.setPrimaryKey(Arrays.asList("tenant_id", "id", "is_split_merge"));

        String ddl = new StarRocksDialect().createTable(table);

        assertEquals(Arrays.asList("tenant_id", "id", "is_split_merge", "name", "sort", "created_at"),
            columnNames(ddl));
        assertTrue(ddl.contains("\nPRIMARY KEY (`tenant_id`, `id`, `is_split_merge`)"));
        assertTrue(ddl.contains("\nDISTRIBUTED BY HASH(`tenant_id`)"));
    }

    @Test
    public void preservesSourceColumnOrderWhenGeneratingDdlRepeatedly() {
        TableMeta table = table();
        table.setPrimaryKey(Arrays.asList("id", "is_split_merge"));
        List<ColumnMeta> originalColumns = table.getColumns();
        ColumnMeta originalSecondColumn = originalColumns.get(1);
        StarRocksDialect dialect = new StarRocksDialect();

        String firstDdl = dialect.createTable(table);
        String secondDdl = dialect.createTable(table);

        assertEquals(firstDdl, secondDdl);
        assertEquals(Arrays.asList("id", "is_split_merge", "name", "sort", "tenant_id", "created_at"),
            columnNames(firstDdl));
        assertSame(originalColumns, table.getColumns());
        assertSame(originalSecondColumn, table.getColumns().get(1));
        assertEquals(Arrays.asList("name", "id", "sort", "is_split_merge", "tenant_id", "created_at"),
            table.getColumns().stream().map(ColumnMeta::getName).collect(Collectors.toList()));
        assertEquals(Arrays.asList("id", "is_split_merge"), table.getPrimaryKey());
    }

    @Test
    public void preservesColumnOrderAndFirstColumnDuplicateKeyWithoutPrimaryKey() {
        TableMeta table = table();

        String ddl = new StarRocksDialect().createTable(table);

        assertEquals(Arrays.asList("name", "id", "sort", "is_split_merge", "tenant_id", "created_at"),
            columnNames(ddl));
        assertTrue(ddl.contains("\nDUPLICATE KEY (`name`)"));
        assertTrue(ddl.contains("\nDISTRIBUTED BY HASH(`name`)"));
    }

    private TableMeta table() {
        TableMeta table = new TableMeta();
        table.setSchema("erp_base");
        table.setName("resource");
        table.setColumns(Collections.unmodifiableList(Arrays.asList(
            column("name", "STRING"),
            column("id", "STRING"),
            column("sort", "INT"),
            column("is_split_merge", "INT"),
            column("tenant_id", "STRING"),
            column("created_at", "DATETIME"))));
        return table;
    }

    private ColumnMeta column(String name, String type) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setColumnType(type);
        return column;
    }

    private List<String> columnNames(String ddl) {
        return Arrays.stream(ddl.split("\n"))
            .filter(line -> line.startsWith("  `"))
            .map(line -> line.substring(3, line.indexOf('`', 3)))
            .collect(Collectors.toList());
    }
}
