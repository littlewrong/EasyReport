package cn.easyreport.sync.datasync;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public interface DataSyncExtractor {
    List<String> listTables(DatabaseMetaData meta, String dbType, String schemaPattern, String tablePattern) throws SQLException;

    /**
     * 解析多 schema/table 模式，返回实际存在的 (actualSchema, tableName) 对。
     * 默认实现使用 JDBC getTables（不支持 % 通配符），MySQL/TiDB 等子类应覆盖此方法使用 information_schema。
     */
    default List<String[]> listTableSpecs(Connection conn, List<String> schemaPatterns, List<String> tablePatterns) throws Exception {
        List<String[]> result = new ArrayList<>();
        DatabaseMetaData meta = conn.getMetaData();
        List<String> effectiveSchemas = schemaPatterns.isEmpty() ? java.util.Collections.singletonList(null) : schemaPatterns;
        List<String> effectiveTables = tablePatterns.isEmpty() ? java.util.Collections.singletonList("%") : tablePatterns;
        for (String schema : effectiveSchemas) {
            for (String tp : effectiveTables) {
                List<String> tables = listTables(meta, null, schema, tp);
                for (String t : tables) {
                    result.add(new String[]{schema, t});
                }
            }
        }
        return result;
    }
    List<String> listColumns(Connection conn, String schemaPattern, String table) throws SQLException;
    List<String> listPrimaryKeys(Connection conn, String schemaPattern, String table) throws SQLException;
    String buildSelectFull(String schema, String table, String tsField, List<String> pkCols);

    default boolean supportsTimestampWindowFullSync() {
        return false;
    }

    default String buildSelectTimestampBounds(String schema, String table, String tsField) {
        throw new UnsupportedOperationException("Timestamp window full sync is not supported");
    }

    default String buildSelectFullWindow(String schema, String table, String tsField, List<String> pkCols) {
        throw new UnsupportedOperationException("Timestamp window full sync is not supported");
    }

    String buildSelectIncremental(String schema, String table, String tsField, List<String> pkCols);
}
