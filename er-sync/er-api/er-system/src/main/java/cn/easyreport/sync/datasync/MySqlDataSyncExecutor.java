package cn.easyreport.sync.datasync;

import cn.easyreport.sync.mapper.ErDataTransferLogMapper;
import cn.easyreport.sync.mapper.ErDataTransferMapper;
import cn.easyreport.sync.mapper.ErDataTransferProgressMapper;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL-specific data sync executor.
 */
public class MySqlDataSyncExecutor extends DataSyncExecutor {

    public MySqlDataSyncExecutor(ErDataTransferMapper transferMapper, ErDataTransferLogMapper logMapper, ErDataTransferProgressMapper progressMapper) {
        super(transferMapper, logMapper, progressMapper);
    }

    /**
     * 数据同步前自动将目标 MySQL 表的 TIMESTAMP 列升级为 DATETIME，
     * 避免来自 Oracle DATE 等可能包含 >2038 年份的数据触发 "Incorrect timestamp value" 错误。
     */
    @Override
    protected void prepareTargetSchema(Connection sourceConn, Connection targetConn,
                                       String sourceSchema, String sourceTable,
                                       String targetTableName) throws Exception {
        String[] parts = parseMySqlTableName(targetTableName);
        if (parts == null) return;
        String dbName = parts[0];
        String tblName = parts[1];

        String querySql = "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA, COLUMN_COMMENT" +
                          " FROM information_schema.COLUMNS" +
                          " WHERE TABLE_SCHEMA = '" + dbName.replace("'", "''") + "'" +
                          " AND TABLE_NAME = '" + tblName.replace("'", "''") + "'" +
                          " AND DATA_TYPE = 'timestamp'";

        List<String> alterSqls = new ArrayList<>();
        try (Statement st = targetConn.createStatement();
             ResultSet rs = st.executeQuery(querySql)) {
            while (rs.next()) {
                String colName  = rs.getString("COLUMN_NAME");
                String colType  = rs.getString("COLUMN_TYPE");
                String nullable = rs.getString("IS_NULLABLE");
                String defVal   = rs.getString("COLUMN_DEFAULT");
                String extra    = rs.getString("EXTRA");
                String comment  = rs.getString("COLUMN_COMMENT");

                String newType = colType.toUpperCase().replace("TIMESTAMP", "DATETIME");
                StringBuilder alter = new StringBuilder("ALTER TABLE ")
                    .append(targetTableName)
                    .append(" MODIFY COLUMN `").append(colName).append("` ").append(newType);
                if ("NO".equalsIgnoreCase(nullable)) alter.append(" NOT NULL");
                else alter.append(" NULL");
                if (defVal != null) alter.append(" DEFAULT '").append(defVal.replace("'", "''")).append("'");
                if (extra != null && !extra.isEmpty()) alter.append(" ").append(extra);
                if (comment != null && !comment.isEmpty())
                    alter.append(" COMMENT '").append(comment.replace("'", "''")).append("'");
                alterSqls.add(alter.toString());
            }
        }

        if (!alterSqls.isEmpty()) {
            try (Statement st = targetConn.createStatement()) {
                for (String sql : alterSqls) {
                    System.out.println("[MySqlDataSyncExecutor] TIMESTAMP->DATETIME: " + sql);
                    st.execute(sql);
                }
            }
            targetConn.commit();
        }
    }

    private String[] parseMySqlTableName(String tableName) {
        if (tableName == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("^`([^`]+)`\\.`([^`]+)`$").matcher(tableName.trim());
        if (m.matches()) return new String[]{m.group(1), m.group(2)};
        return null;
    }

    @Override
    protected void clearTable(Connection conn, String tableName) throws Exception {
        // MySQL 使用 TRUNCATE TABLE 清空表
        // tableName 可能已经是完全限定名格式：`schema`.`table`
        try (Statement st = conn.createStatement()) {
            // 如果已经包含反引号，直接使用；否则添加反引号
            String sql = tableName.contains("`")
                ? "TRUNCATE TABLE " + tableName
                : "TRUNCATE TABLE `" + tableName + "`";
            st.execute(sql);
        }
    }

    @Override
    protected String qualify(String schema, String table) {
        if (schema == null || schema.isEmpty()) {
            return table;
        }
        // MySQL: `schema`.`table`
        return "`" + schema + "`.`" + table + "`";
    }
}
