package cn.easyreport.sync.extractor;

import cn.easyreport.sync.model.ColumnMeta;
import cn.easyreport.sync.model.IndexMeta;
import cn.easyreport.sync.model.TableMeta;

import java.util.ArrayList;
import java.util.List;

/** Shared expansion for homogeneous database/schema shards. */
public final class FirstSchemaSupport {

    private FirstSchemaSupport() {
    }

    /** SQL Server uses a database/catalog scope while retaining its table schema. */
    public static String scopeOf(TableMeta table, String sourceType) {
        if ("SQLSERVER".equalsIgnoreCase(sourceType) && table.getDatabase() != null) {
            return table.getDatabase();
        }
        return table.getSchema();
    }

    /**
     * Readers provide scopes and template tables in deterministic order. Each
     * expanded table owns its metadata because target mapping mutates it.
     * SQL Server scopes are catalogs: preserve dbo and replace the database.
     */
    public static List<TableMeta> expand(List<String> scopes, List<TableMeta> templates,
                                         boolean includeMetadata, boolean catalogScopes) {
        List<TableMeta> result = new ArrayList<>();
        if (scopes.isEmpty()) {
            return result;
        }
        if (templates.isEmpty()) {
            throw new IllegalStateException("首个匹配库/Schema " + scopes.get(0)
                + " 未匹配到任何表，请检查源表模式、权限或调整源 Schema 范围");
        }
        if (includeMetadata) {
            for (TableMeta template : templates) {
                if (template.getColumns().isEmpty()) {
                    throw new IllegalStateException("无法读取表 " + scopes.get(0) + "." + template.getName()
                        + " 的列元数据，表可能已被删除或发生权限变化，请检查后重试");
                }
            }
        }
        for (String scope : scopes) {
            for (TableMeta template : templates) {
                TableMeta copy = includeMetadata ? copyTable(template) : new TableMeta();
                copy.setName(template.getName());
                if (catalogScopes) {
                    copy.setDatabase(scope);
                    copy.setSchema(template.getSchema());
                } else {
                    copy.setSchema(scope);
                }
                result.add(copy);
            }
        }
        return result;
    }

    private static TableMeta copyTable(TableMeta template) {
        TableMeta copy = new TableMeta();
        copy.setDatabase(template.getDatabase());
        copy.setSchema(template.getSchema());
        copy.setName(template.getName());
        copy.setEngine(template.getEngine());
        copy.setCharset(template.getCharset());
        copy.setComment(template.getComment());
        copy.setPrimaryKey(new ArrayList<>(template.getPrimaryKey()));
        for (ColumnMeta column : template.getColumns()) {
            ColumnMeta columnCopy = new ColumnMeta();
            columnCopy.setName(column.getName());
            columnCopy.setColumnType(column.getColumnType());
            columnCopy.setNullable(column.isNullable());
            columnCopy.setDefaultValue(column.getDefaultValue());
            columnCopy.setAutoIncrement(column.isAutoIncrement());
            columnCopy.setComment(column.getComment());
            columnCopy.setOrdinalPosition(column.getOrdinalPosition());
            columnCopy.setCharacterSet(column.getCharacterSet());
            columnCopy.setCollation(column.getCollation());
            copy.getColumns().add(columnCopy);
        }
        for (IndexMeta index : template.getIndexes()) {
            IndexMeta indexCopy = new IndexMeta();
            indexCopy.setName(index.getName());
            indexCopy.setUnique(index.isUnique());
            indexCopy.setColumns(new ArrayList<>(index.getColumns()));
            copy.getIndexes().add(indexCopy);
        }
        return copy;
    }
}
