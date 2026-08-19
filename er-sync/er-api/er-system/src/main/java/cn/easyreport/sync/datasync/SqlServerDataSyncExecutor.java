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
 * SQL Server-specific data sync executor.
 */
public class SqlServerDataSyncExecutor extends DataSyncExecutor {

    /** 记录当前是否已开启 IDENTITY_INSERT，用于 afterBatchInsert 清理 */
    private boolean identityInsertEnabled = false;

    /**
     * SQL Server 单条语句参数硬限制为 2100，保守使用 2000。
     */
    @Override
    protected int getMaxFullInsertParams() {
        return 2000;
    }

    /**
     * 数据同步前将目标 SQL Server 表的 TINYINT 列升级为 SMALLINT。
     * SQL Server TINYINT 是无符号 0-255，而 StarRocks/MySQL TINYINT 是有符号 -128-127，
     * 负值（如 -128）写入会触发"算术溢出"错误。
     */
    @Override
    protected void prepareTargetSchema(Connection sourceConn, Connection targetConn,
                                       String sourceSchema, String sourceTable,
                                       String targetTableName) throws Exception {
        String[] parts = parseSqlServerTableName(targetTableName);
        if (parts == null) return;
        String dbName     = parts[0]; // database (may be null for two-part names)
        String schemaName = parts[1]; // e.g. dbo
        String tblName    = parts[2]; // table name

        // 使用 INFORMATION_SCHEMA.COLUMNS 查询 TINYINT 列，比 sys.columns 跨库访问更稳定
        String catalog = dbName != null ? dbName : targetConn.getCatalog();
        String querySql = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS" +
                          " WHERE TABLE_CATALOG = '" + catalog.replace("'", "''") + "'" +
                          " AND TABLE_SCHEMA = '" + schemaName.replace("'", "''") + "'" +
                          " AND TABLE_NAME = '" + tblName.replace("'", "''") + "'" +
                          " AND DATA_TYPE = 'tinyint'";

        List<String> alterSqls = new ArrayList<>();
        try (Statement st = targetConn.createStatement();
             ResultSet rs = st.executeQuery(querySql)) {
            while (rs.next()) {
                String colName = rs.getString("COLUMN_NAME");
                alterSqls.add("ALTER TABLE " + targetTableName +
                               " ALTER COLUMN [" + colName + "] SMALLINT");
            }
        }

        if (!alterSqls.isEmpty()) {
            try (Statement st = targetConn.createStatement()) {
                for (String sql : alterSqls) {
                    System.out.println("[SqlServerDataSyncExecutor] TINYINT->SMALLINT: " + sql);
                    st.execute(sql);
                }
            }
            targetConn.commit();
        }
    }

    /**
     * 解析 SQL Server 完全限定表名 [db].[schema].[table]，返回 [db, schema, table]（不含方括号）。
     * 若格式不匹配（如仅 [schema].[table]），db 返回 null。
     */
    private String[] parseSqlServerTableName(String tableName) {
        if (tableName == null) return null;
        // 三段式：[db].[schema].[table]
        java.util.regex.Matcher m3 = java.util.regex.Pattern
            .compile("^\\[([^\\]]+)\\]\\.\\[([^\\]]+)\\]\\.\\[([^\\]]+)\\]$").matcher(tableName.trim());
        if (m3.matches()) return new String[]{m3.group(1), m3.group(2), m3.group(3)};
        // 两段式：[schema].[table]
        java.util.regex.Matcher m2 = java.util.regex.Pattern
            .compile("^\\[([^\\]]+)\\]\\.\\[([^\\]]+)\\]$").matcher(tableName.trim());
        if (m2.matches()) return new String[]{null, m2.group(1), m2.group(2)};
        return null;
    }

    public SqlServerDataSyncExecutor(ErDataTransferMapper transferMapper, ErDataTransferLogMapper logMapper, ErDataTransferProgressMapper progressMapper) {
        super(transferMapper, logMapper, progressMapper);
    }

    /**
     * 在批量插入前检查目标表是否有 IDENTITY 列，若有则开启 SET IDENTITY_INSERT ON。
     * tableName 格式：[database].[dbo].[table] 或 [dbo].[table]
     */
    @Override
    protected void beforeBatchInsert(Connection targetConn, String tableName) throws Exception {
        identityInsertEnabled = false;
        // 从 [db].[schema].[table] 中提取数据库名，构造跨库的 sys.identity_columns 查询
        String dbRef = extractDatabasePart(tableName);
        String sysTable = (dbRef != null) ? dbRef + ".sys.identity_columns" : "sys.identity_columns";
        String checkSql = "SELECT COUNT(*) FROM " + sysTable +
                          " WHERE object_id = OBJECT_ID(N'" + tableName.replace("'", "''") + "')";
        try (Statement st = targetConn.createStatement();
             ResultSet rs = st.executeQuery(checkSql)) {
            if (rs.next() && rs.getInt(1) > 0) {
                st.execute("SET IDENTITY_INSERT " + tableName + " ON");
                identityInsertEnabled = true;
                System.out.println("[SqlServerDataSyncExecutor] SET IDENTITY_INSERT ON: " + tableName);
            }
        }
    }

    /**
     * 批量插入结束后（无论成功与否）关闭 IDENTITY_INSERT。
     */
    @Override
    protected void afterBatchInsert(Connection targetConn, String tableName) throws Exception {
        if (identityInsertEnabled) {
            try (Statement st = targetConn.createStatement()) {
                st.execute("SET IDENTITY_INSERT " + tableName + " OFF");
                System.out.println("[SqlServerDataSyncExecutor] SET IDENTITY_INSERT OFF: " + tableName);
            } catch (Exception e) {
                System.err.println("[SqlServerDataSyncExecutor] 关闭 IDENTITY_INSERT 失败（忽略）: " + e.getMessage());
            } finally {
                identityInsertEnabled = false;
            }
        }
    }

    /**
     * 从完全限定表名（如 [synctest].[dbo].[table]）中提取数据库部分（如 [synctest]）。
     * 若不是三段式则返回 null。
     */
    private String extractDatabasePart(String tableName) {
        if (tableName == null || !tableName.startsWith("[")) return null;
        int firstClose = tableName.indexOf(']');
        if (firstClose < 1) return null;
        String rest = tableName.substring(firstClose + 1);
        if (!rest.startsWith(".[")) return null;
        int secondClose = rest.indexOf(']', 2);
        if (secondClose < 2) return null;
        String afterSecond = rest.substring(secondClose + 1);
        if (!afterSecond.startsWith(".[")) return null;
        // 确认是三段式，返回第一段（含方括号）
        return tableName.substring(0, firstClose + 1);
    }

    @Override
    protected void clearTable(Connection conn, String tableName) throws Exception {
        // SQL Server supports TRUNCATE TABLE
        // tableName 可能已经是完全限定名格式：[database].[schema].[table]
        try (Statement st = conn.createStatement()) {
            // 如果已经包含方括号，直接使用；否则添加方括号
            String sql = tableName.contains("[")
                ? "TRUNCATE TABLE " + tableName
                : "TRUNCATE TABLE [" + tableName + "]";
            st.execute(sql);
        }
    }

    @Override
    protected String qualify(String schema, String table) {
        if (schema == null || schema.isEmpty()) {
            return table;
        }
        // SQL Server: [database].[dbo].[table]
        // schema 参数在 SQL Server 中实际代表数据库名
        return "[" + schema + "].[dbo].[" + table + "]";
    }

    @Override
    protected String buildAlignDeleteSelectSql(String targetTable, String pk, String tsField) {
        // SQL Server: 使用 TOP 语法进行分页
        // 注意：targetTable 已经是完全限定名（如 [db].[schema].[table]），不需要再引用
        return "SELECT TOP (?) " + quoteTargetIdentifier(pk) + ", " + quoteTargetIdentifier(tsField) +
               " FROM " + targetTable +
               " WHERE " + quoteTargetIdentifier(tsField) + " >= ? ORDER BY " + quoteTargetIdentifier(tsField) +
               ", " + quoteTargetIdentifier(pk);
    }

    @Override
    protected String buildAlignDeleteSelectSqlAfter(String targetTable, String pk, String tsField) {
        // SQL Server: keyset 分页，TOP 语法参数在最前
        return "SELECT TOP (?) " + quoteTargetIdentifier(pk) + ", " + quoteTargetIdentifier(tsField) +
               " FROM " + targetTable +
               " WHERE (" + quoteTargetIdentifier(tsField) + " > ?" +
               " OR (" + quoteTargetIdentifier(tsField) + " = ? AND " + quoteTargetIdentifier(pk) + " > ?))" +
               " ORDER BY " + quoteTargetIdentifier(tsField) + ", " + quoteTargetIdentifier(pk);
    }

    @Override
    protected void setAlignDeleteQueryParams(java.sql.PreparedStatement ps, java.sql.Timestamp cursor, int batchSize) throws Exception {
        // SQL Server TOP 语法：参数顺序是 batchSize, timestamp（与基类相反）
        ps.setInt(1, batchSize);
        ps.setTimestamp(2, cursor);
    }

    @Override
    protected void setAlignDeleteQueryParamsAfter(java.sql.PreparedStatement ps, java.sql.Timestamp lastTs, Object lastPk, int batchSize) throws Exception {
        // SQL Server TOP 语法：参数顺序是 batchSize, ts, ts, pk（与基类相反）
        ps.setInt(1, batchSize);
        ps.setTimestamp(2, lastTs);
        ps.setTimestamp(3, lastTs);
        ps.setObject(4, lastPk);
    }
}
