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
 * StarRocks-specific data sync executor.
 */
public class StarRocksDataSyncExecutor extends DataSyncExecutor {

    public StarRocksDataSyncExecutor(ErDataTransferMapper transferMapper, ErDataTransferLogMapper logMapper, ErDataTransferProgressMapper progressMapper) {
        super(transferMapper, logMapper, progressMapper);
    }

    /**
     * 数据同步前自动将目标 StarRocks 表的 CHAR(n)/VARCHAR(n) 列升级为 STRING，
     * 避免多字节 Unicode 字符（如中文，UTF-8 3字节）超出字节限制触发 "String too long" 错误。
     */
    @Override
    protected void prepareTargetSchema(Connection sourceConn, Connection targetConn,
                                       String sourceSchema, String sourceTable,
                                       String targetTableName) throws Exception {
        String[] parts = parseStarRocksTableName(targetTableName);
        if (parts == null) return;
        String dbName   = parts[0];
        String tblName  = parts[1];

        // StarRocks information_schema.columns 兼容 MySQL 协议
        String querySql = "SELECT COLUMN_NAME, DATA_TYPE" +
                          " FROM information_schema.COLUMNS" +
                          " WHERE TABLE_SCHEMA = '" + dbName.replace("'", "''") + "'" +
                          " AND TABLE_NAME = '" + tblName.replace("'", "''") + "'" +
                          " AND DATA_TYPE IN ('char', 'varchar')";

        List<String> modifyClauses = new ArrayList<>();
        try (Statement st = targetConn.createStatement();
             ResultSet rs = st.executeQuery(querySql)) {
            while (rs.next()) {
                String colName = rs.getString("COLUMN_NAME");
                modifyClauses.add("MODIFY COLUMN `" + colName + "` STRING");
            }
        }

        if (!modifyClauses.isEmpty()) {
            // 合并成一条 ALTER TABLE，避免多个 schema change 并行冲突
            String alterSql = "ALTER TABLE " + targetTableName + " " + String.join(", ", modifyClauses);
            System.out.println("[StarRocksDataSyncExecutor] CHAR/VARCHAR->STRING: " + alterSql);
            try (Statement st = targetConn.createStatement()) {
                st.execute(alterSql);
            }
            // StarRocks ALTER TABLE 是异步的，需要等待 schema change 完成
            waitForSchemaChange(targetConn, dbName, tblName);
        }
    }

    /**
     * 等待 StarRocks 异步 schema change 完成。
     * 使用 SHOW ALTER TABLE COLUMN 轮询，直到所有任务状态为 FINISHED 或 CANCELLED。
     * 超时后打印警告但继续执行（不抛异常）。
     */
    private void waitForSchemaChange(Connection conn, String dbName, String tblName) {
        // 最多等待 5 分钟，每 3 秒轮询一次
        int maxWaitMs = 5 * 60 * 1000;
        int pollIntervalMs = 3000;
        int waited = 0;

        String showSql = "SHOW ALTER TABLE COLUMN FROM `" + dbName + "` WHERE TableName = '" + tblName + "'";

        while (waited < maxWaitMs) {
            try {
                Thread.sleep(pollIntervalMs);
                waited += pollIntervalMs;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }

            boolean allDone = true;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(showSql)) {
                while (rs.next()) {
                    String state = rs.getString("State");
                    if (state != null && !state.equalsIgnoreCase("FINISHED") && !state.equalsIgnoreCase("CANCELLED")) {
                        allDone = false;
                        System.out.println("[StarRocksDataSyncExecutor] schema change 进行中，状态: " + state + "，已等待 " + waited / 1000 + "s");
                        break;
                    }
                }
            } catch (Exception e) {
                System.err.println("[StarRocksDataSyncExecutor] 查询 schema change 状态失败: " + e.getMessage());
                // 查询失败时保守等待，继续轮询
                allDone = false;
            }

            if (allDone) {
                System.out.println("[StarRocksDataSyncExecutor] schema change 已完成，共等待 " + waited / 1000 + "s");
                return;
            }
        }

        System.err.println("[StarRocksDataSyncExecutor] 警告：等待 schema change 超时（" + maxWaitMs / 1000 + "s），继续执行");
    }

    private String[] parseStarRocksTableName(String tableName) {
        if (tableName == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("^`([^`]+)`\\.`([^`]+)`$").matcher(tableName.trim());
        if (m.matches()) return new String[]{m.group(1), m.group(2)};
        return null;
    }

    @Override
    protected void clearTable(Connection conn, String tableName) throws Exception {
        // StarRocks 使用 TRUNCATE TABLE 清空表
        // 注意：StarRocks 不允许不带 WHERE 的 DELETE
        // tableName 可能已经是完全限定名格式：`schema`.`table`
        try (Statement st = conn.createStatement()) {
            // 如果已经包含反引号，直接使用；否则添加反引号
            String sql = tableName.contains("`")
                ? "TRUNCATE TABLE " + tableName
                : "TRUNCATE TABLE `" + tableName + "`";
            st.execute(sql);
        }
    }

    /**
     * StarRocks 每次 executeBatch+commit 产生一个版本，版本数限制默认 1000。
     * 改用多行 VALUES 拼接，一批数据只产生 1 个版本。
     */
    @Override
    protected boolean useBufferedUpsert() {
        return true;
    }

    /**
     * 将整批数据拼接成一条 INSERT INTO t (cols) VALUES (row1),(row2),... 执行。
     * StarRocks PRIMARY KEY 表天然支持 upsert（主键冲突时替换）。
     */
    @Override
    protected void executeBufferedUpsert(Connection conn, List<Object[]> rows,
                                         String targetTable, List<String> cols,
                                         List<String> pkCols) throws Exception {
        if (rows.isEmpty()) return;

        // 列名
        StringBuilder colPart = new StringBuilder("(");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) colPart.append(",");
            colPart.append("`").append(cols.get(i)).append("`");
        }
        colPart.append(")");

        // 单行占位符模板 (?,?,...)
        StringBuilder rowTemplate = new StringBuilder("(");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) rowTemplate.append(",");
            rowTemplate.append("?");
        }
        rowTemplate.append(")");

        // 拼接: INSERT INTO t (cols) VALUES (?,?,...),(?,?,...),...
        String tableName = targetTable.contains("`") ? targetTable : "`" + targetTable + "`";
        StringBuilder sql = new StringBuilder("INSERT INTO ");
        sql.append(tableName).append(" ").append(colPart).append(" VALUES ");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sql.append(",");
            sql.append(rowTemplate);
        }

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (Object[] row : rows) {
                for (Object val : row) {
                    ps.setObject(idx++, val);
                }
            }
            ps.executeUpdate();
        }
    }

    @Override
    protected String qualify(String schema, String table) {
        if (schema == null || schema.isEmpty()) {
            return table;
        }
        // StarRocks: `schema`.`table` (与 MySQL 兼容)
        return "`" + schema + "`.`" + table + "`";
    }
}
