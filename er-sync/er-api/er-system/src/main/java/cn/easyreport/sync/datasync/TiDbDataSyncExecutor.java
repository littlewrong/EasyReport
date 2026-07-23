package cn.easyreport.sync.datasync;

import cn.easyreport.sync.mapper.ErDataTransferLogMapper;
import cn.easyreport.sync.mapper.ErDataTransferMapper;
import cn.easyreport.sync.mapper.ErDataTransferProgressMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * TiDB-specific data sync executor.
 */
public class TiDbDataSyncExecutor extends DataSyncExecutor {

    private static final int MAX_FULL_INSERT_PARAMS = 60000;

    public TiDbDataSyncExecutor(ErDataTransferMapper transferMapper, ErDataTransferLogMapper logMapper, ErDataTransferProgressMapper progressMapper) {
        super(transferMapper, logMapper, progressMapper);
    }

    /**
     * TiDB 全量同步目标表已先 TRUNCATE，直接使用多行 INSERT，减少网络往返和单行写入开销。
     * 增量同步暂不启用该路径，仍保持原有 ON DUPLICATE KEY UPDATE batch 逻辑。
     */
    @Override
    protected boolean useBufferedFullInsert() {
        return true;
    }

    @Override
    protected void executeBufferedFullInsert(Connection conn, List<Object[]> rows,
                                             String targetTable, List<String> cols,
                                             List<String> pkCols) throws Exception {
        if (rows == null || rows.isEmpty()) return;

        int columnCount = Math.max(cols.size(), 1);
        int maxRowsPerStatement = Math.max(1, MAX_FULL_INSERT_PARAMS / columnCount);
        for (int start = 0; start < rows.size(); start += maxRowsPerStatement) {
            int end = Math.min(start + maxRowsPerStatement, rows.size());
            executeMultiRowInsert(conn, rows, start, end, targetTable, cols);
        }
    }

    protected void executeMultiRowInsert(Connection conn, List<Object[]> rows, int start, int end,
                                         String targetTable, List<String> cols) throws Exception {
        StringBuilder colPart = new StringBuilder("(");
        StringBuilder rowTemplate = new StringBuilder("(");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                colPart.append(",");
                rowTemplate.append(",");
            }
            colPart.append("`").append(cols.get(i)).append("`");
            rowTemplate.append("?");
        }
        colPart.append(")");
        rowTemplate.append(")");

        String tableName = targetTable.contains("`") ? targetTable : "`" + targetTable + "`";
        StringBuilder sql = new StringBuilder("INSERT INTO ");
        sql.append(tableName).append(" ").append(colPart).append(" VALUES ");
        for (int i = start; i < end; i++) {
            if (i > start) sql.append(",");
            sql.append(rowTemplate);
        }

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int paramIndex = 1;
            for (int i = start; i < end; i++) {
                Object[] row = rows.get(i);
                for (Object value : row) {
                    ps.setObject(paramIndex++, value);
                }
            }
            ps.executeUpdate();
        }
    }

    /**
     * 数据同步前自动将目标 TiDB 表的 TIMESTAMP 列升级为 DATETIME，
     * 避免来自 Oracle DATE 等可能包含 >2038 年份的数据触发 "Incorrect timestamp value" 错误。
     */
    @Override
    protected void prepareTargetSchema(Connection sourceConn, Connection targetConn,
                                       String sourceSchema, String sourceTable,
                                       String targetTableName) throws Exception {
        // 从 targetTableName（格式：`schema`.`table`）解析 schema 和 table
        String[] parts = parseMySqlTableName(targetTableName);
        if (parts == null) return;
        String dbName = parts[0];
        String tableName = parts[1];

        String querySql = "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA, COLUMN_COMMENT" +
                          " FROM information_schema.COLUMNS" +
                          " WHERE TABLE_SCHEMA = '" + dbName.replace("'", "''") + "'" +
                          " AND TABLE_NAME = '" + tableName.replace("'", "''") + "'" +
                          " AND DATA_TYPE = 'timestamp'";

        List<String> alterSqls = new ArrayList<>();
        try (Statement st = targetConn.createStatement();
             ResultSet rs = st.executeQuery(querySql)) {
            while (rs.next()) {
                String colName    = rs.getString("COLUMN_NAME");
                String colType    = rs.getString("COLUMN_TYPE"); // e.g. timestamp, timestamp(3)
                String nullable   = rs.getString("IS_NULLABLE");
                String defVal     = rs.getString("COLUMN_DEFAULT");
                String extra      = rs.getString("EXTRA");
                String comment    = rs.getString("COLUMN_COMMENT");

                // 将 TIMESTAMP 替换为 DATETIME（保留精度）
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
                    System.out.println("[TiDbDataSyncExecutor] TIMESTAMP->DATETIME: " + sql);
                    st.execute(sql);
                }
            }
            targetConn.commit();
        }
    }

    /**
     * 解析 MySQL/TiDB 表名 `schema`.`table`，返回 [schema, table]（不含反引号）。
     */
    private String[] parseMySqlTableName(String tableName) {
        if (tableName == null) return null;
        // 格式：`schema`.`table`
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("^`([^`]+)`\\.`([^`]+)`$").matcher(tableName.trim());
        if (m.matches()) return new String[]{m.group(1), m.group(2)};
        // 无 schema 格式：`table`
        m = java.util.regex.Pattern.compile("^`([^`]+)`$").matcher(tableName.trim());
        if (m.matches()) return null; // 无 schema 时无法查 information_schema
        return null;
    }

    @Override
    protected void clearTable(Connection conn, String tableName) throws Exception {
        // TiDB 使用 TRUNCATE TABLE 清空表（与 MySQL 兼容）
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
        // TiDB: `schema`.`table` (与 MySQL 兼容)
        return "`" + schema + "`.`" + table + "`";
    }
}
