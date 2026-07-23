package cn.easyreport.sync;

import java.util.List;

/**
 * Minimal data-source connector abstraction used by schema sync.
 */
public interface DataSourceConnector extends AutoCloseable {

    /**
     * List table names by schema/table pattern.
     *
     * @param schemaPattern schema pattern (may be null)
     * @param tablePattern  table pattern or exact name(s)
     */
    List<String> listTables(String schemaPattern, String tablePattern) throws Exception;

    /**
     * Whether a table already exists.
     */
    boolean tableExists(String schemaPattern, String tableName) throws Exception;

    /**
     * Return DDL of source table.
     */
    String getCreateTableSql(String schemaPattern, String tableName) throws Exception;

    /**
     * Execute single SQL (DDL).
     */
    void execute(String sql) throws Exception;

    /**
     * Database name (for connectors that distinguish db/schema).
     */
    String getDatabaseName();

    @Override
    void close();
}
