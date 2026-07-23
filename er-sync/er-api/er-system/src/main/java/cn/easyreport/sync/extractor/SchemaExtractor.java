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
     * Get one table meta by schema & name.
     */
    TableMeta getTable(String schema, String table) throws Exception;
}
