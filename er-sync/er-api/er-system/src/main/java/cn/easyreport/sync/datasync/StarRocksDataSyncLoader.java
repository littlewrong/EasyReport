package cn.easyreport.sync.datasync;

import java.util.List;
import java.util.StringJoiner;

/**
 * StarRocks数据同步加载器
 *
 * 注意事项：
 * 1. StarRocks 不支持 MySQL 的 ON DUPLICATE KEY UPDATE 语法
 * 2. 对于 DUPLICATE KEY 模型的表，INSERT 会追加数据
 * 3. 对于 PRIMARY KEY 模型的表，INSERT 会自动实现 upsert 语义（主键冲突时替换）
 * 4. 建议使用 PRIMARY KEY 模型以获得更好的数据同步效果
 */
public class StarRocksDataSyncLoader implements DataSyncLoader {

    @Override
    public String buildUpsertSql(String table, List<String> columns, boolean upsert) {
        StringJoiner col = new StringJoiner(",", "(", ")");
        StringJoiner val = new StringJoiner(",", "(", ")");
        for (String c : columns) {
            col.add("`" + c + "`");
            val.add("?");
        }

        // StarRocks 不支持 MySQL 的 ON DUPLICATE KEY UPDATE 语法
        // 对于 PRIMARY KEY 表，简单的 INSERT 会自动处理 upsert（主键冲突时替换）
        // 对于 DUPLICATE KEY 表，INSERT 只会追加数据

        // 如果表名已经包含反引号（完全限定名），直接使用；否则添加反引号
        String tableName = table.contains("`") ? table : "`" + table + "`";
        String sql = "INSERT INTO " + tableName + col + " VALUES " + val;

        // 注意：upsert 参数在这里被忽略
        // PRIMARY KEY 表会自动处理主键冲突（新数据替换旧数据）
        // 不需要额外的 ON DUPLICATE KEY UPDATE 子句

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
