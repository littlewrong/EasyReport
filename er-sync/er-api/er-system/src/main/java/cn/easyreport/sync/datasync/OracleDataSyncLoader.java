package cn.easyreport.sync.datasync;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Oracle数据同步加载器
 *
 * Oracle 使用 MERGE INTO 语法实现 upsert
 */
public class OracleDataSyncLoader implements DataSyncLoader {

    @Override
    public String buildUpsertSql(String table, List<String> columns, boolean upsert) {
        return buildUpsertSql(table, columns, upsert, columns.subList(0, 1));
    }

    @Override
    public String buildUpsertSql(String table, List<String> columns, boolean upsert, List<String> pkColumns) {
        // 如果表名已经包含双引号（完全限定名），直接使用；否则添加双引号
        String tableName = table.contains("\"") ? table : "\"" + table + "\"";

        if (!upsert) {
            // 不需要 upsert，直接 INSERT
            StringJoiner col = new StringJoiner(",", "(", ")");
            StringJoiner val = new StringJoiner(",", "(", ")");
            for (String c : columns) {
                col.add("\"" + c + "\"");
                val.add("?");
            }
            return "INSERT INTO " + tableName + col + " VALUES " + val;
        }

        // 使用 MERGE INTO 实现 upsert
        // MERGE INTO "TARGET" t
        // USING (SELECT ? AS "col1", ? AS "col2", ... FROM DUAL) s
        // ON (t."pk1" = s."pk1" AND t."pk2" = s."pk2")
        // WHEN MATCHED THEN UPDATE SET t."col" = s."col", ...
        // WHEN NOT MATCHED THEN INSERT ("col1","col2",...) VALUES (s."col1",s."col2",...)
        StringBuilder sb = new StringBuilder();
        sb.append("MERGE INTO ").append(tableName).append(" t");

        // USING (SELECT ? AS "col1", ... FROM DUAL) s
        StringJoiner selectCols = new StringJoiner(", ");
        for (String c : columns) {
            selectCols.add("? AS \"" + c + "\"");
        }
        sb.append(" USING (SELECT ").append(selectCols).append(" FROM DUAL) s");

        // ON 条件：主键匹配
        StringJoiner onClause = new StringJoiner(" AND ");
        Set<String> pkSet = new HashSet<>(pkColumns);
        for (String pk : pkColumns) {
            onClause.add("t.\"" + pk + "\" = s.\"" + pk + "\"");
        }
        sb.append(" ON (").append(onClause).append(")");

        // WHEN MATCHED THEN UPDATE SET（排除主键列）
        StringJoiner updateSet = new StringJoiner(",");
        for (String c : columns) {
            if (!pkSet.contains(c)) {
                updateSet.add("t.\"" + c + "\" = s.\"" + c + "\"");
            }
        }
        if (updateSet.toString().isEmpty()) {
            // 所有列都是主键，无需 UPDATE；仅做 INSERT
            sb.append(" WHEN NOT MATCHED THEN INSERT (");
            StringJoiner insertCols = new StringJoiner(",");
            StringJoiner insertVals = new StringJoiner(",");
            for (String c : columns) {
                insertCols.add("\"" + c + "\"");
                insertVals.add("s.\"" + c + "\"");
            }
            sb.append(insertCols).append(") VALUES (").append(insertVals).append(")");
        } else {
            sb.append(" WHEN MATCHED THEN UPDATE SET ").append(updateSet);
            sb.append(" WHEN NOT MATCHED THEN INSERT (");
            StringJoiner insertCols = new StringJoiner(",");
            StringJoiner insertVals = new StringJoiner(",");
            for (String c : columns) {
                insertCols.add("\"" + c + "\"");
                insertVals.add("s.\"" + c + "\"");
            }
            sb.append(insertCols).append(") VALUES (").append(insertVals).append(")");
        }

        return sb.toString();
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
