package cn.easyreport.sync.datasync;

import cn.easyreport.sync.mapper.ErDataTransferLogMapper;
import cn.easyreport.sync.mapper.ErDataTransferMapper;
import cn.easyreport.sync.mapper.ErDataTransferProgressMapper;
import cn.easyreport.sync.core.SyncProgressCallback;
import cn.easyreport.sync.domain.ErDataTransfer;
import cn.easyreport.sync.domain.ErDatasource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * StarRocks-specific data sync executor.
 */
public class StarRocksDataSyncExecutor extends DataSyncExecutor {

    private StarRocksBinaryTextTransfer binaryTextTransfer;
    private boolean mysqlBinaryTextSource;

    public StarRocksDataSyncExecutor(ErDataTransferMapper transferMapper, ErDataTransferLogMapper logMapper, ErDataTransferProgressMapper progressMapper) {
        super(transferMapper, logMapper, progressMapper);
    }

    @Override
    protected void prepareColumnTransfer(ErDataTransfer task, ErDatasource sourceDs,
            Connection sourceConn, Connection targetConn, String schema, String table,
            String targetTable, List<String> sourceCols, List<String> targetCols) throws Exception {
        // Full imports prepare in prepareDirectFullImport, including callers that
        // invoke the full-import lifecycle directly. Reset between physical tables.
        binaryTextTransfer = null;
        mysqlBinaryTextSource = false;
        if (task != null && !"0".equals(task.getSyncMode())) {
            prepareBinaryTextTransfer(sourceDs, sourceConn, targetConn, schema, table,
                    targetTable, sourceCols, targetCols);
        }
    }

    private void prepareBinaryTextTransfer(ErDatasource sourceDs, Connection sourceConn,
            Connection targetConn, String schema, String table, String targetTable,
            List<String> sourceCols, List<String> targetCols) throws Exception {
        binaryTextTransfer = null;
        mysqlBinaryTextSource = false;
        if (sourceDs == null) return;
        String type = sourceDs.getDatasourceType();
        if (!("MYSQL".equalsIgnoreCase(type) || "TIDB".equalsIgnoreCase(type)
                || "ORACLE".equalsIgnoreCase(type))) return;
        binaryTextTransfer = StarRocksBinaryTextTransfer.prepare(type, sourceConn, targetConn,
                schema, table, targetTable, sourceCols, targetCols, cancellationContext());
        mysqlBinaryTextSource = "MYSQL".equalsIgnoreCase(type) || "TIDB".equalsIgnoreCase(type);
        if (binaryTextTransfer.hasTextColumns()) {
            checkStopped();
            try (Statement statement = targetConn.createStatement()) {
                statement.execute("SET enable_insert_strict = true");
            }
        }
    }

    @Override
    protected Object readColumnValue(ResultSet rows, String column, String sourceTypeName) throws Exception {
        if (binaryTextTransfer != null && binaryTextTransfer.contains(column)) {
            return binaryTextTransfer.read(rows, column);
        }
        if (mysqlBinaryTextSource && binaryTextTransfer != null && binaryTextTransfer.hasTextColumns()) {
            // A whole-table JDBC transfer must also preserve the other special
            // MySQL values that the Resource compatibility driver handled.
            if ("BIT".equalsIgnoreCase(sourceTypeName)) {
                Object value = rows.getObject(column);
                if (value instanceof byte[]) {
                    byte[] bytes = (byte[]) value;
                    return bytes.length == 0 ? BigInteger.ZERO : new BigInteger(1, bytes);
                }
                return convertColumnValue(value, sourceTypeName);
            }
            if ("TIME".equalsIgnoreCase(sourceTypeName)) {
                Duration value = rows.getObject(column, Duration.class);
                if (value == null) return null;
                int scale = rows.getMetaData().getScale(rows.findColumn(column));
                Duration absolute = value.abs();
                long seconds = absolute.getSeconds();
                String text = String.format(Locale.ROOT, "%s%02d:%02d:%02d", value.isNegative() ? "-" : "",
                        seconds / 3600, seconds / 60 % 60, seconds % 60);
                if (scale > 0 && scale <= 6) {
                    text += "." + String.format(Locale.ROOT, "%09d", absolute.getNano()).substring(0, scale);
                }
                return text;
            }
        }
        return super.readColumnValue(rows, column, sourceTypeName);
    }

    @Override
    protected void validateSourceTargetIdentity(ErDataTransfer task, ErDatasource sourceDs,
            Connection sourceConn, Connection targetConn, String sourceSchema,
            String sourceTable, String targetTable) throws Exception {
        if (task != null && "0".equals(task.getSyncMode()) && sourceDs != null
                && "STARROCKS".equalsIgnoreCase(sourceDs.getDatasourceType())) {
            StarRocksTableIdentity.verifyDistinct(sourceDs, sourceConn, targetConn,
                    sourceSchema, sourceTable, targetTable, null, cancellationContext());
        }
    }

    @Override
    protected DirectFullImport prepareDirectFullImport(ErDataTransfer task, ErDatasource sourceDs,
            String schema, String table, String targetTable, List<String> sourceCols,
            List<String> targetCols, DataSyncExtractor extractor, Connection sourceConn,
            Connection targetConn, String tsField, SyncProgressCallback callback) throws Exception {
        if (task != null && "0".equals(task.getSyncMode()) && "1".equals(task.getExternalTableEnabled())) {
            String method = task.resolveDirectImportMethod();
            if (ErDataTransfer.DIRECT_IMPORT_JDBC_RESOURCE.equals(method)) {
                if (task.getResourceName() == null || task.getResourceName().trim().isEmpty()) {
                    throw new IllegalArgumentException("JDBC Resource 导入必须填写已有的 Resource 名称；旧外部表任务请补填后重试，尚未清空目标表");
                }
                if (!supportsResourceSource(sourceDs, extractor)) {
                    throw new IllegalArgumentException("JDBC Resource 导入的源数据库类型或提取器不匹配，尚未清空目标表");
                }
            } else if (ErDataTransfer.DIRECT_IMPORT_JDBC_CATALOG.equals(method)) {
                if (task.getCatalogName() == null || task.getCatalogName().trim().isEmpty()) {
                    throw new IllegalArgumentException("Catalog 导入必须填写已有的 JDBC Catalog 名称，尚未清空目标表");
                }
            } else {
                throw new IllegalArgumentException("未知的数据湖导入方式，尚未清空目标表");
            }
        }
        if (task != null && "0".equals(task.getSyncMode())) {
            prepareBinaryTextTransfer(sourceDs, sourceConn, targetConn, schema, table,
                    targetTable, sourceCols, targetCols);
            if (binaryTextTransfer != null && binaryTextTransfer.hasTextColumns()) {
                // Scanner-side byte[] handling cannot be fixed by changing the target
                // type alone. Validate every effective value before syncFull truncates.
                binaryTextTransfer.preflight(sourceConn, schema, table, tsField, cancellationContext());
            }
        }
        if (task == null || !"0".equals(task.getSyncMode()) || !"1".equals(task.getExternalTableEnabled())) {
            return null;
        }
        String method = task.resolveDirectImportMethod();
        if (ErDataTransfer.DIRECT_IMPORT_JDBC_RESOURCE.equals(method)) {
            if (useBinaryTextJdbcTransfer(callback, schema, table)) return null;
            checkStopped();
            final StarRocksJdbcResourceFullImport resourceImport = StarRocksJdbcResourceFullImport.prepare(sourceDs,
                    sourceConn, targetConn, task.getResourceName(), schema, table, targetTable, sourceCols,
                    targetCols, tsField, task.getTransferId(), task.getExternalTableThresholdWan(),
                    task.getExternalTableWindowUnit(), callback, cancellationContext());
            return new DirectFullImport() {
                @Override public FullSyncStats execute() throws Exception {
                    StarRocksJdbcResourceFullImport.Result result = resourceImport.execute();
                    FullSyncStats stats = new FullSyncStats();
                    stats.processed = result.processed;
                    stats.maxTs = result.maxTs;
                    return stats;
                }
                @Override public void close() throws Exception { resourceImport.close(); }
            };
        }
        if (ErDataTransfer.DIRECT_IMPORT_JDBC_CATALOG.equals(method)) {
            if (useBinaryTextJdbcTransfer(callback, schema, table)) return null;
            checkStopped();
            final StarRocksCatalogFullImport catalogImport = StarRocksCatalogFullImport.prepare(
                    sourceDs, sourceConn, targetConn, task.getCatalogName().trim(), schema, table,
                    targetTable, sourceCols, targetCols, tsField, task.getExternalTableThresholdWan(),
                    task.getExternalTableWindowUnit(), callback, cancellationContext());
            return new DirectFullImport() {
                @Override public FullSyncStats execute() throws Exception {
                    StarRocksCatalogFullImport.Result result = catalogImport.execute();
                    FullSyncStats stats = new FullSyncStats();
                    stats.processed = result.processed;
                    stats.maxTs = result.maxTs;
                    return stats;
                }
                @Override public void close() { catalogImport.close(); }
            };
        }
        throw new IllegalArgumentException("未知的数据湖导入方式，尚未清空目标表");
    }

    private static boolean supportsResourceSource(ErDatasource datasource, DataSyncExtractor extractor) {
        if (datasource == null) return false;
        String type = datasource.getDatasourceType();
        return ("KINGBASE".equalsIgnoreCase(type) && extractor instanceof KingbaseDataSyncExtractor)
                || ("DM".equalsIgnoreCase(type) && extractor instanceof DmDataSyncExtractor)
                || ("MYSQL".equalsIgnoreCase(type) && extractor instanceof MySqlDataSyncExtractor)
                || ("TIDB".equalsIgnoreCase(type) && extractor instanceof TiDbDataSyncExtractor)
                || ("STARROCKS".equalsIgnoreCase(type) && extractor instanceof StarRocksDataSyncExtractor)
                || ("SQLSERVER".equalsIgnoreCase(type) && extractor instanceof SqlServerDataSyncExtractor)
                || ("POSTGRESQL".equalsIgnoreCase(type) && extractor instanceof PostgreSqlDataSyncExtractor)
                || ("ORACLE".equalsIgnoreCase(type) && extractor instanceof OracleDataSyncExtractor);
    }

    private boolean useBinaryTextJdbcTransfer(SyncProgressCallback callback, String schema, String table) throws Exception {
        if (binaryTextTransfer == null || !binaryTextTransfer.hasTextColumns()) return false;
        checkStopped();
        String message = "表 " + (schema == null || schema.isEmpty() ? table : schema + "." + table)
                + " 包含二进制/BLOB→文本映射，已通过 UTF-8 和容量预检，使用应用 JDBC 批量同步，无需额外兼容驱动";
        System.out.println("[StarRocksDataSyncExecutor] " + message);
        if (callback != null) callback.onProgress(0, -1, message);
        return true;
    }

    @Override
    protected void prepareTargetSchema(ErDataTransfer task, ErDatasource sourceDs, Connection sourceConn, Connection targetConn,
                                       String sourceSchema, String sourceTable,
                                       String targetTableName) throws Exception {
        // Preserve native byte capacities on the Resource path. Other import
        // methods and incremental tasks retain their existing schema preparation behavior.
        if (task != null && "0".equals(task.getSyncMode()) && "1".equals(task.getExternalTableEnabled())) {
            String method = task.resolveDirectImportMethod();
            if (ErDataTransfer.DIRECT_IMPORT_JDBC_RESOURCE.equals(method)) return;
        }
        super.prepareTargetSchema(task, sourceDs, sourceConn, targetConn, sourceSchema, sourceTable, targetTableName);
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
    private void waitForSchemaChange(Connection conn, String dbName, String tblName) throws Exception {
        // 最多等待 5 分钟，每 3 秒轮询一次
        int maxWaitMs = 5 * 60 * 1000;
        int pollIntervalMs = 3000;
        int waited = 0;

        String showSql = "SHOW ALTER TABLE COLUMN FROM `" + dbName + "` WHERE TableName = '" + tblName + "'";

        while (waited < maxWaitMs) {
            checkStopped();
            try {
                Thread.sleep(pollIntervalMs);
                waited += pollIntervalMs;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new DataSyncCancellation.SyncStoppedException();
            }
            checkStopped();

            boolean allDone = true;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(showSql)) {
                while (rs.next()) {
                    checkStopped();
                    String state = rs.getString("State");
                    if (state != null && !state.equalsIgnoreCase("FINISHED") && !state.equalsIgnoreCase("CANCELLED")) {
                        allDone = false;
                        System.out.println("[StarRocksDataSyncExecutor] schema change 进行中，状态: " + state + "，已等待 " + waited / 1000 + "s");
                        break;
                    }
                }
            } catch (DataSyncCancellation.SyncStoppedException stopped) {
                throw stopped;
            } catch (Exception e) {
                checkStopped();
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
