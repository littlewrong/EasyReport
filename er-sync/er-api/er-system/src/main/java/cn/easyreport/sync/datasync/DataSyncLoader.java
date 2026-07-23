package cn.easyreport.sync.datasync;

import java.util.List;

public interface DataSyncLoader {
    String buildUpsertSql(String table, List<String> columns, boolean upsert);

    /**
     * 构建 upsert SQL，支持传入主键列以便生成正确的 MERGE / ON CONFLICT 语句。
     * 默认实现回退到不带主键的版本（MySQL / TiDB / StarRocks 不需要主键信息）。
     */
    default String buildUpsertSql(String table, List<String> columns, boolean upsert, List<String> pkColumns) {
        return buildUpsertSql(table, columns, upsert);
    }

    String buildDeleteSql(String table, String pkColumn, List<String> ids);
}
