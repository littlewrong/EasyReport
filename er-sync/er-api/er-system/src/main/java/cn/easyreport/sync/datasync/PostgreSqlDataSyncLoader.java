package cn.easyreport.sync.datasync;

import java.util.List;
import java.util.StringJoiner;

/**
 * PostgreSQL数据同步加载器
 *
 * PostgreSQL 支持 INSERT ... ON CONFLICT ... DO UPDATE SET 语法实现 upsert
 */
public class PostgreSqlDataSyncLoader implements DataSyncLoader {

    @Override
    public String buildUpsertSql(String table, List<String> columns, boolean upsert) {
        return buildUpsertSql(table, columns, upsert, columns.subList(0, 1));
    }

    @Override
    public String buildUpsertSql(String table, List<String> columns, boolean upsert, List<String> pkColumns) {
        StringJoiner col = new StringJoiner(",", "(", ")");
        StringJoiner val = new StringJoiner(",", "(", ")");
        for (String c : columns) {
            col.add("\"" + c + "\"");
            val.add("?");
        }

        // 如果表名已经包含双引号（完全限定名），直接使用；否则添加双引号
        String tableName = table.contains("\"") ? table : "\"" + table + "\"";
        String sql = "INSERT INTO " + tableName + col + " VALUES " + val;

        // PostgreSQL 支持 ON CONFLICT ... DO UPDATE SET 语法
        if (upsert) {
            StringJoiner pkJoiner = new StringJoiner(",");
            for (String pk : pkColumns) {
                pkJoiner.add("\"" + pk + "\"");
            }
            StringJoiner updateSet = new StringJoiner(",");
            for (String c : columns) {
                updateSet.add("\"" + c + "\" = EXCLUDED.\"" + c + "\"");
            }
            sql += " ON CONFLICT (" + pkJoiner + ") DO UPDATE SET " + updateSet;
        }

        return sql;
    }

    @Override
    public String buildDeleteSql(String table, String pkColumn, List<String> ids) {
        StringJoiner in = new StringJoiner(",");
        for (String id : ids) {
            // 对ID进行转义，防止SQL注入
            in.add("'" + id.replace("'", "''") + "'");
        }
        // 如果表名已经包含双引号（完全限定名），直接使用；否则添加双引号
        String tableName = table.contains("\"") ? table : "\"" + table + "\"";
        return "DELETE FROM " + tableName + " WHERE \"" + pkColumn + "\" IN (" + in + ")";
    }
}
