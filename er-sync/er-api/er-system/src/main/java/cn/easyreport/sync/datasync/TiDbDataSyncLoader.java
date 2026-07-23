package cn.easyreport.sync.datasync;

import java.util.List;
import java.util.StringJoiner;

/**
 * TiDB loader实现：与 MySQL 语法基本一致，单独实现便于未来差异化。
 */
public class TiDbDataSyncLoader implements DataSyncLoader {
    @Override
    public String buildUpsertSql(String table, List<String> columns, boolean upsert) {
        StringJoiner col = new StringJoiner(",", "(", ")");
        StringJoiner val = new StringJoiner(",", "(", ")");
        for (String c : columns) {
            col.add("`" + c + "`");
            val.add("?");
        }
        // 如果表名已经包含反引号（完全限定名），直接使用；否则添加反引号
        String tableName = table.contains("`") ? table : "`" + table + "`";
        String sql = "INSERT INTO " + tableName + col + " VALUES " + val;
        if (upsert) {
            StringJoiner upd = new StringJoiner(",");
            for (String c : columns) {
                upd.add("`" + c + "`=VALUES(`" + c + "`)");
            }
            sql += " ON DUPLICATE KEY UPDATE " + upd;
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
        // 如果表名已经包含反引号（完全限定名），直接使用；否则添加反引号
        String tableName = table.contains("`") ? table : "`" + table + "`";
        return "DELETE FROM " + tableName + " WHERE `" + pkColumn + "` IN (" + in + ")";
    }
}
