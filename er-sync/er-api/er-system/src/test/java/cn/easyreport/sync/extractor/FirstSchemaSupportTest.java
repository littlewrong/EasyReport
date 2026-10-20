package cn.easyreport.sync.extractor;

import cn.easyreport.sync.model.ColumnMeta;
import cn.easyreport.sync.model.IndexMeta;
import cn.easyreport.sync.model.TableMeta;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class FirstSchemaSupportTest {
    @Test
    public void expandsCompleteMetadataIntoIndependentTablesAndLists() {
        TableMeta template = template();

        List<TableMeta> expanded = FirstSchemaSupport.expand(Arrays.asList("erp_a", "erp_b"),
                Collections.singletonList(template), true, false);

        assertEquals(2, expanded.size());
        for (int i = 0; i < expanded.size(); i++) {
            TableMeta copy = expanded.get(i);
            assertEquals(i == 0 ? "erp_a" : "erp_b", copy.getSchema());
            assertEquals(template.getDatabase(), copy.getDatabase());
            assertEquals(template.getName(), copy.getName());
            assertEquals(template.getEngine(), copy.getEngine());
            assertEquals(template.getCharset(), copy.getCharset());
            assertEquals(template.getComment(), copy.getComment());
            assertEquals(template.getPrimaryKey(), copy.getPrimaryKey());
            assertColumnEquals(template.getColumns().get(0), copy.getColumns().get(0));
            assertEquals(template.getIndexes().get(0).getName(), copy.getIndexes().get(0).getName());
            assertEquals(template.getIndexes().get(0).isUnique(), copy.getIndexes().get(0).isUnique());
            assertEquals(template.getIndexes().get(0).getColumns(), copy.getIndexes().get(0).getColumns());
        }
        List<TableMeta> all = Arrays.asList(template, expanded.get(0), expanded.get(1));
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                TableMeta left = all.get(i);
                TableMeta right = all.get(j);
                assertNotSame(left, right);
                assertNotSame(left.getColumns(), right.getColumns());
                assertNotSame(left.getColumns().get(0), right.getColumns().get(0));
                assertNotSame(left.getPrimaryKey(), right.getPrimaryKey());
                assertNotSame(left.getIndexes(), right.getIndexes());
                assertNotSame(left.getIndexes().get(0), right.getIndexes().get(0));
                assertNotSame(left.getIndexes().get(0).getColumns(), right.getIndexes().get(0).getColumns());
            }
        }
        TableMeta first = expanded.get(0);
        first.getColumns().get(0).setColumnType("STRING");
        first.getColumns().clear();
        first.getPrimaryKey().clear();
        first.getIndexes().get(0).setName("changed");
        first.getIndexes().get(0).setUnique(false);
        first.getIndexes().get(0).getColumns().clear();
        first.getIndexes().clear();
        for (TableMeta unchanged : Arrays.asList(template, expanded.get(1))) {
            assertEquals("bigint", unchanged.getColumns().get(0).getColumnType());
            assertEquals(Collections.singletonList("id"), unchanged.getPrimaryKey());
            assertEquals("uk_id", unchanged.getIndexes().get(0).getName());
            assertTrue(unchanged.getIndexes().get(0).isUnique());
            assertEquals(Collections.singletonList("id"), unchanged.getIndexes().get(0).getColumns());
        }
    }

    @Test
    public void catalogScopesReplaceDatabaseAndKeepDboSchema() {
        TableMeta template = template();

        List<TableMeta> expanded = FirstSchemaSupport.expand(Arrays.asList("erp_a", "erp_b"),
                Collections.singletonList(template), true, true);

        assertEquals(2, expanded.size());
        assertEquals("erp_a", expanded.get(0).getDatabase());
        assertEquals("erp_b", expanded.get(1).getDatabase());
        assertEquals("erp_a", FirstSchemaSupport.scopeOf(expanded.get(0), "SQLSERVER"));
        assertEquals("erp_b", FirstSchemaSupport.scopeOf(expanded.get(1), "sqlserver"));
        assertEquals("dbo", FirstSchemaSupport.scopeOf(expanded.get(0), "MYSQL"));
        for (TableMeta table : expanded) {
            assertEquals("dbo", table.getSchema());
            assertEquals("orders", table.getName());
            assertColumnEquals(template.getColumns().get(0), table.getColumns().get(0));
        }
        assertEquals("original_catalog", template.getDatabase());
        assertEquals("dbo", template.getSchema());
    }

    @Test
    public void previewReturnsOnlyNamesAndScopeIdentity() {
        for (boolean catalogScopes : new boolean[]{false, true}) {
            List<TableMeta> preview = FirstSchemaSupport.expand(Arrays.asList("erp_a", "erp_b"),
                    Collections.singletonList(template()), false, catalogScopes);

            assertEquals(2, preview.size());
            for (int i = 0; i < preview.size(); i++) {
                TableMeta table = preview.get(i);
                String scope = i == 0 ? "erp_a" : "erp_b";
                assertEquals("orders", table.getName());
                assertEquals(catalogScopes ? "dbo" : scope, table.getSchema());
                assertEquals(catalogScopes ? scope : null, table.getDatabase());
                assertNull(table.getEngine());
                assertNull(table.getCharset());
                assertNull(table.getComment());
                assertTrue(table.getColumns().isEmpty());
                assertTrue(table.getPrimaryKey().isEmpty());
                assertTrue(table.getIndexes().isEmpty());
            }
            assertNotSame(preview.get(0), preview.get(1));
            assertNotSame(preview.get(0).getColumns(), preview.get(1).getColumns());
        }
    }

    @Test
    public void emptyScopesReturnEmptyButEmptyFirstScopeMetadataFailsClearly() {
        assertTrue(FirstSchemaSupport.expand(Collections.emptyList(), Collections.emptyList(), true, false).isEmpty());
        assertFailureContains("erp_a", () -> FirstSchemaSupport.expand(Arrays.asList("erp_a", "erp_b"),
                Collections.emptyList(), true, false));
        TableMeta emptyTable = new TableMeta();
        emptyTable.setName("orders");
        assertFailureContains("erp_a.orders", () -> FirstSchemaSupport.expand(Arrays.asList("erp_a", "erp_b"),
                Collections.singletonList(emptyTable), true, false));
        assertEquals(2, FirstSchemaSupport.expand(Arrays.asList("erp_a", "erp_b"),
                Collections.singletonList(emptyTable), false, false).size());
    }

    private void assertFailureContains(String text, Runnable action) {
        try {
            action.run();
            fail("Expected empty first scope failure");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(text));
        }
    }

    private TableMeta template() {
        TableMeta table = new TableMeta();
        table.setDatabase("original_catalog");
        table.setSchema("dbo");
        table.setName("orders");
        table.setEngine("InnoDB");
        table.setCharset("utf8mb4");
        table.setComment("Order records");
        ColumnMeta id = new ColumnMeta();
        id.setName("id");
        id.setColumnType("bigint");
        id.setNullable(false);
        id.setDefaultValue("7");
        id.setAutoIncrement(true);
        id.setComment("Identifier");
        id.setOrdinalPosition(1);
        id.setCharacterSet("utf8mb4");
        id.setCollation("utf8mb4_bin");
        table.getColumns().add(id);
        table.getPrimaryKey().add("id");
        IndexMeta index = new IndexMeta();
        index.setName("uk_id");
        index.setUnique(true);
        index.getColumns().add("id");
        table.getIndexes().add(index);
        return table;
    }

    private void assertColumnEquals(ColumnMeta expected, ColumnMeta actual) {
        assertEquals(expected.getName(), actual.getName());
        assertEquals(expected.getColumnType(), actual.getColumnType());
        assertEquals(expected.isNullable(), actual.isNullable());
        assertEquals(expected.getDefaultValue(), actual.getDefaultValue());
        assertEquals(expected.isAutoIncrement(), actual.isAutoIncrement());
        assertEquals(expected.getComment(), actual.getComment());
        assertEquals(expected.getOrdinalPosition(), actual.getOrdinalPosition());
        assertEquals(expected.getCharacterSet(), actual.getCharacterSet());
        assertEquals(expected.getCollation(), actual.getCollation());
    }
}
