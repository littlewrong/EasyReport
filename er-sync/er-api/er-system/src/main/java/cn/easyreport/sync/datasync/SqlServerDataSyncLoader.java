package cn.easyreport.sync.datasync;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * SQL Server数据同步加载器
 */
public class SqlServerDataSyncLoader implements DataSyncLoader {

    @Override
    public String buildUpsertSql(String table, List<String> columns, boolean upsert) {
        return buildUpsertSql(table, columns, upsert, columns.subList(0, 1));
    }

    @Override
    public String buildUpsertSql(String table, List<String> columns, boolean upsert, List<String> pkColumns) {
        // 如果表名已经包含方括号（完全限定名），直接使用；否则添加方括号
        String tableName = table.contains("[") ? table : "[" + table + "]";

        if (!upsert) {
            // 不需要 upsert，直接 INSERT
            StringJoiner col = new StringJoiner(",", "(", ")");
            StringJoiner val = new StringJoiner(",", "(", ")");
            for (String c : columns) {
                col.add("[" + c + "]");
                val.add("?");
            }
            return "INSERT INTO " + tableName + col + " VALUES " + val;
        }

        // 使用 MERGE 实现 upsert
        // MERGE INTO [target] AS t
        // USING (VALUES (?,?,...)) AS s([col1],[col2],...)
        // ON t.[pk1] = s.[pk1] AND t.[pk2] = s.[pk2]
        // WHEN MATCHED THEN UPDATE SET t.[col] = s.[col], ...
        // WHEN NOT MATCHED THEN INSERT ([col1],[col2],...) VALUES (s.[col1],s.[col2],...);
        StringBuilder sb = new StringBuilder();
        sb.append("MERGE INTO ").append(tableName).append(" WITH (HOLDLOCK) AS t");

        // USING (VALUES (?,?,...)) AS s([col1],[col2],...)
        StringJoiner val = new StringJoiner(",", "(", ")");
        StringJoiner srcCols = new StringJoiner(",", "(", ")");
        for (String c : columns) {
            val.add("?");
            srcCols.add("[" + c + "]");
        }
        sb.append(" USING (VALUES ").append(val).append(") AS s").append(srcCols);

        // ON 条件：主键匹配
        StringJoiner onClause = new StringJoiner(" AND ");
        Set<String> pkSet = new HashSet<>(pkColumns);
        for (String pk : pkColumns) {
            onClause.add("t.[" + pk + "] = s.[" + pk + "]");
        }
        sb.append(" ON ").append(onClause);

        // WHEN MATCHED THEN UPDATE SET（排除主键列）
        StringJoiner updateSet = new StringJoiner(",");
        for (String c : columns) {
            if (!pkSet.contains(c)) {
                updateSet.add("t.[" + c + "] = s.[" + c + "]");
            }
        }
        if (updateSet.toString().isEmpty()) {
            // 所有列都是主键，无需 UPDATE；仅做 INSERT
            sb.append(" WHEN NOT MATCHED THEN INSERT ").append(srcCols).append(" VALUES ");
            StringJoiner insertVals = new StringJoiner(",", "(", ")");
            for (String c : columns) {
                insertVals.add("s.[" + c + "]");
            }
            sb.append(insertVals);
        } else {
            sb.append(" WHEN MATCHED THEN UPDATE SET ").append(updateSet);
            sb.append(" WHEN NOT MATCHED THEN INSERT ").append(srcCols).append(" VALUES ");
            StringJoiner insertVals = new StringJoiner(",", "(", ")");
            for (String c : columns) {
                insertVals.add("s.[" + c + "]");
            }
            sb.append(insertVals);
        }
        sb.append(";"); // SQL Server MERGE 必须以分号结尾
        return sb.toString();
    }

    @Override
    public String buildDeleteSql(String table, String pkColumn, List<String> ids) {
        StringJoiner in = new StringJoiner(",");
        for (String id : ids) {
            // 对ID进行转义，防止SQL注入
            in.add("'" + id.replace("'", "''") + "'");
        }
        // 如果表名已经包含方括号（完全限定名），直接使用；否则添加方括号
        String tableName = table.contains("[") ? table : "[" + table + "]";
        return "DELETE FROM " + tableName + " WHERE [" + pkColumn + "] IN (" + in + ")";
    }
}
