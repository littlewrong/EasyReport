package cn.easyreport.sync.extractor;

import cn.easyreport.sync.model.TableMeta;

import java.util.List;

/**
 * Extract structured metadata from a datasource.
 */
public interface SchemaExtractor {

    /**
     * List tables by schema/table pattern.
     */
    List<TableMeta> listTables(String schemaPattern, String tablePattern) throws Exception;

    /**
     * Reuse the first matching schema's structure for all selected shard schemas.
     * Database/schema scopes are ordered by name, independently of input pattern order.
     * Callers must explicitly opt in and ensure the schemas have identical structures.
     */
    default List<TableMeta> listTablesUsingFirstSchema(String schemaPattern, String tablePattern) throws Exception {
        throw new UnsupportedOperationException("当前源数据库不支持分片库架构创建");
    }

    /** Preview the same shard/table combinations without loading their structure. */
    default List<TableMeta> listTableNamesUsingFirstSchema(String schemaPattern, String tablePattern) throws Exception {
        throw new UnsupportedOperationException("当前源数据库不支持分片库架构预览");
    }

    /**
     * Get one table meta by schema & name.
     */
    TableMeta getTable(String schema, String table) throws Exception;
}
