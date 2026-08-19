package cn.easyreport.sync.datasync;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.alibaba.fastjson2.JSON;
import cn.easyreport.common.utils.DateUtils;
import cn.easyreport.sync.domain.ErDataTransfer;
import cn.easyreport.sync.domain.ErDataTransferLog;
import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.mapper.ErDataTransferMapper;
import cn.easyreport.sync.mapper.ErDataTransferLogMapper;
import cn.easyreport.sync.mapper.ErDataTransferProgressMapper;
import cn.easyreport.sync.domain.ErDataTransferProgress;
import cn.easyreport.sync.core.SyncProgressCallback;

/**
 * Abstract base class for data sync executors.
 * Subclasses implement database-specific behavior.
 */
public abstract class DataSyncExecutor {

    /**
     * 停止检查器接口
     */
    public interface StopChecker {
        boolean shouldStop();
    }

    protected final ErDataTransferMapper transferMapper;
    protected final ErDataTransferLogMapper logMapper;
    protected final ErDataTransferProgressMapper progressMapper;
    private final DataSyncValueConverter valueConverter;

    public DataSyncExecutor(ErDataTransferMapper transferMapper, ErDataTransferLogMapper logMapper, ErDataTransferProgressMapper progressMapper) {
        this(transferMapper, logMapper, progressMapper, new DefaultDataSyncValueConverter());
    }

    protected DataSyncExecutor(ErDataTransferMapper transferMapper, ErDataTransferLogMapper logMapper,
                               ErDataTransferProgressMapper progressMapper,
                               DataSyncValueConverter valueConverter) {
        this.transferMapper = transferMapper;
        this.logMapper = logMapper;
        this.progressMapper = progressMapper;
        this.valueConverter = valueConverter == null ? new DefaultDataSyncValueConverter() : valueConverter;
    }

    /**
     * Clear all data from target table.
     * Subclasses implement database-specific logic (TRUNCATE, DELETE, etc.)
     */
    protected abstract void clearTable(Connection conn, String tableName) throws Exception;

    /**
     * Called in syncTable() before data transfer begins.
     * Subclasses can override to fix target schema incompatibilities (e.g., type widening for SQL Server).
     * Exceptions are caught by the caller and logged as warnings; data sync continues regardless.
     */
    protected void prepareTargetSchema(Connection sourceConn, Connection targetConn,
                                       String sourceSchema, String sourceTable,
                                       String targetTableName) throws Exception {
        // Default: do nothing
    }

    /**
     * Called before batch insert into target table.
     * Subclasses can override to perform pre-insert setup (e.g., SET IDENTITY_INSERT ON for SQL Server).
     */
    protected void beforeBatchInsert(Connection targetConn, String tableName) throws Exception {
        // Default: do nothing
    }

    /**
     * Called after batch insert into target table (always called, even on failure).
     * Subclasses can override to perform post-insert cleanup (e.g., SET IDENTITY_INSERT OFF for SQL Server).
     */
    protected void afterBatchInsert(Connection targetConn, String tableName) throws Exception {
        // Default: do nothing
    }

    /**
     * 是否对 upsert 语句逐行执行（而非 addBatch/executeBatch）。
     * Oracle JDBC 对 MERGE INTO 使用 batch 会静默失败，需逐行 executeUpdate。
     */
    protected boolean useRowByRowUpsert() {
        return false;
    }

    /**
     * 是否使用缓冲批量 upsert（子类收集整批数据后自行构造并执行 SQL）。
     * StarRocks 需要多行 VALUES 拼接减少版本数；Oracle 需要批量 DELETE+INSERT 绕过 MERGE batch 问题。
     */
    protected boolean useBufferedUpsert() {
        return false;
    }

    /**
     * 是否在全量同步时使用缓冲批量插入。
     * 全量同步会先清空目标表，因此默认使用真正的多行 INSERT，避免依赖 JDBC 驱动的 batch rewrite 配置。
     */
    protected boolean useBufferedFullInsert() {
        return true;
    }

    /**
     * 执行缓冲批量 upsert。仅当 useBufferedUpsert() == true 时被调用。
     * @param conn       目标库连接（commit 由调用方统一处理）
     * @param rows       本批数据，每个 Object[] 对应一行，顺序与 cols 一致
     * @param targetTable 目标表完全限定名
     * @param cols       列名列表
     * @param pkCols     主键列名列表
     */
    protected void executeBufferedUpsert(Connection conn, List<Object[]> rows,
                                         String targetTable, List<String> cols,
                                         List<String> pkCols) throws Exception {
        // 默认不实现，子类按需覆写
    }

    /**
     * 执行全量同步的缓冲批量插入。仅当 useBufferedFullInsert() == true 时被调用。
     */
    protected void executeBufferedFullInsert(Connection conn, List<Object[]> rows,
                                             String targetTable, List<String> cols,
                                             List<String> pkCols) throws Exception {
        if (rows == null || rows.isEmpty()) return;

        int columnCount = Math.max(cols.size(), 1);
        int maxRowsByParams = Math.max(1, getMaxFullInsertParams() / columnCount);
        int maxRowsPerStatement = Math.max(1, Math.min(maxRowsByParams, getMaxFullInsertRows()));
        for (int start = 0; start < rows.size(); start += maxRowsPerStatement) {
            int end = Math.min(start + maxRowsPerStatement, rows.size());
            executeFullInsertStatement(conn, rows, start, end, targetTable, cols);
        }
    }

    /**
     * 单条全量 INSERT 最大参数数量。子类可按目标库参数限制覆写。
     */
    protected int getMaxFullInsertParams() {
        return 60000;
    }

    /**
     * 单条全量 INSERT 最大行数。子类可按目标库 SQL 文本限制覆写。
     */
    protected int getMaxFullInsertRows() {
        return Integer.MAX_VALUE;
    }

    protected void executeFullInsertStatement(Connection conn, List<Object[]> rows, int start, int end,
                                              String targetTable, List<String> cols) throws Exception {
        executeMultiRowInsert(conn, rows, start, end, targetTable, cols);
    }

    protected void executeMultiRowInsert(Connection conn, List<Object[]> rows, int start, int end,
                                         String targetTable, List<String> cols) throws Exception {
        String colPart = buildTargetColumnList(cols);
        String rowTemplate = buildPlaceholderRow(cols.size());
        String tableName = normalizeTargetTableSqlName(targetTable);

        StringBuilder sql = new StringBuilder("INSERT INTO ");
        sql.append(tableName).append(" ").append(colPart).append(" VALUES ");
        for (int i = start; i < end; i++) {
            if (i > start) sql.append(",");
            sql.append(rowTemplate);
        }

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bindRows(ps, rows, start, end);
            ps.executeUpdate();
        }
    }

    protected String buildTargetColumnList(List<String> cols) {
        StringBuilder colPart = new StringBuilder("(");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) colPart.append(",");
            colPart.append(quoteTargetIdentifier(cols.get(i)));
        }
        colPart.append(")");
        return colPart.toString();
    }

    protected String buildPlaceholderRow(int columnCount) {
        StringBuilder rowTemplate = new StringBuilder("(");
        for (int i = 0; i < columnCount; i++) {
            if (i > 0) rowTemplate.append(",");
            rowTemplate.append("?");
        }
        rowTemplate.append(")");
        return rowTemplate.toString();
    }

    protected void bindRows(PreparedStatement ps, List<Object[]> rows, int start, int end) throws Exception {
        int paramIndex = 1;
        for (int i = start; i < end; i++) {
            Object[] row = rows.get(i);
            for (Object value : row) {
                ps.setObject(paramIndex++, value);
            }
        }
    }

    protected String normalizeTargetTableSqlName(String targetTable) {
        if (targetTable == null || targetTable.isEmpty()) {
            return targetTable;
        }
        if (targetTable.contains("`") || targetTable.contains("\"") || targetTable.contains("[")) {
            return targetTable;
        }
        return quoteTargetIdentifier(targetTable);
    }

    // 源数据库和目标数据库策略（用于生成正确的表名引用）
    private cn.easyreport.sync.strategy.DatabaseStrategy sourceStrategy;
    private cn.easyreport.sync.strategy.DatabaseStrategy targetStrategy;

    public DataSyncResult execute(ErDataTransfer task, ErDatasource sourceDs, ErDatasource targetDs, SyncProgressCallback callback, StopChecker stopChecker) throws Exception {
        System.out.println("[DataSyncExecutor] 开始execute方法，taskId=" + task.getTransferId());
        // 保存源和目标数据库策略
        this.sourceStrategy = cn.easyreport.sync.strategy.DatabaseStrategyFactory.getStrategy(sourceDs.getDatasourceType());
        this.targetStrategy = cn.easyreport.sync.strategy.DatabaseStrategyFactory.getStrategy(targetDs.getDatasourceType());

        DataSyncResult result = new DataSyncResult();
        result.setSuccess(true);
        result.setStopped(false);
        result.setMessage("OK");
        result.setTotalCount(0);
        result.setSuccessCount(0);
        result.setFailCount(0);

        System.out.println("[DataSyncExecutor] 创建 extractor 和 loader");
        DataSyncExtractor extractor = DataSyncFactory.buildExtractor(sourceDs);
        DataSyncLoader loader = DataSyncFactory.buildLoader(targetDs);

        System.out.println("[DataSyncExecutor] 加载驱动类");
        Class.forName(sourceDs.getDriverClass());
        Class.forName(targetDs.getDriverClass());

        System.out.println("[DataSyncExecutor] 建立数据库连接");
        Connection sourceConn = null;
        Connection targetConn = null;
        try {
            sourceConn = openManagedConnection(sourceDs);
            targetConn = openManagedConnection(targetDs);

            System.out.println("[DataSyncExecutor] 调用 resolveTables");
            List<TableSpec> tables = resolveTables(extractor, sourceConn, sourceDs.getDatasourceType(),
                    task.getSourceSchemaPattern(), task.getSourceTable());
            System.out.println("[DataSyncExecutor] resolveTables 返回，表数量=" + tables.size());

            int batchSize = (task.getBatchSize() != null && task.getBatchSize() > 0) ? task.getBatchSize() : 500;
            Timestamp maxSyncTs = null;

            System.out.println("[DataSyncExecutor] 调用 loadLastSyncMap");
            Map<String, Timestamp> lastSyncMap = loadLastSyncMap(task.getTransferId());
            System.out.println("[DataSyncExecutor] loadLastSyncMap 返回，记录数=" + lastSyncMap.size());

            // 初始化待同步表列表
            System.out.println("[DataSyncExecutor] 初始化待同步表列表");
            for (TableSpec spec : tables) {
                String fullTableName = (spec.schema != null && !spec.schema.isEmpty())
                        ? spec.schema + "." + spec.table
                        : spec.table;
                result.getPendingTables().add(fullTableName);
            }

            System.out.println("[DataSyncExecutor] 开始同步表，总数: " + tables.size());
            for (int idx = 0; idx < tables.size(); idx++) {
                // 检查是否需要停止
                if (stopChecker != null && stopChecker.shouldStop()) {
                    System.out.println("[DataSyncExecutor] 检测到停止标志，停止同步");
                    result.setStopped(true);
                    result.setSuccess(true); // 优雅停止视为成功
                    result.setMessage("同步已停止（已完成 " + idx + " 张表，剩余 " + (tables.size() - idx) + " 张表）");
                    // 更新进度显示停止信息
                    updateProgressWithTables(task.getTransferId(), idx, tables.size(),
                        "同步已停止", result.getPendingTables(), result.getCompletedTables());
                    break;
                }

                TableSpec spec = tables.get(idx);
                String fullTableName = (spec.schema != null && !spec.schema.isEmpty())
                        ? spec.schema + "." + spec.table
                        : spec.table;

                System.out.println("[DataSyncExecutor] 开始同步表 [" + (idx + 1) + "/" + tables.size() + "]: " + fullTableName);

                // 从待同步列表移到已完成列表
                result.getPendingTables().remove(fullTableName);
                final int currentTableNo = idx + 1;
                updateProgressWithTables(task.getTransferId(), currentTableNo, tables.size(),
                    "正在同步: " + fullTableName, result.getPendingTables(), result.getCompletedTables());

                try {
                    // 上一张表耗时较长（>wait_timeout）时，源/目标连接可能已被服务端关闭。
                    // 在每张表同步前校验连接活性，失效时重建，避免后续表全部抛出
                    // "The last packet successfully received ... is longer than 'wait_timeout'"。
                    if (!isConnectionAlive(sourceConn)) {
                        System.out.println("[DataSyncExecutor] 源连接失效，重建连接");
                        closeQuietly(sourceConn);
                        sourceConn = openManagedConnection(sourceDs);
                    }
                    if (!isConnectionAlive(targetConn)) {
                        System.out.println("[DataSyncExecutor] 目标连接失效，重建连接");
                        closeQuietly(targetConn);
                        targetConn = openManagedConnection(targetDs);
                    }

                    SyncProgressCallback tableProgressCallback = (current, total, message) ->
                        updateProgressWithTables(task.getTransferId(), currentTableNo, tables.size(),
                            message, result.getPendingTables(), result.getCompletedTables());
                    SyncTableResult tr = syncTable(task, spec.schema, spec.table, extractor, loader, sourceConn, targetConn, batchSize, tableProgressCallback, lastSyncMap);
                    result.addLog(tr.log);
                    result.getCompletedTables().add(fullTableName);

                    System.out.println("[DataSyncExecutor] 完成同步表 [" + (idx + 1) + "/" + tables.size() + "]: " + fullTableName + ", 结果: " + (tr.success ? "成功" : "失败"));

                    // 更新进度（包含表列表）
                    updateProgressWithTables(task.getTransferId(), idx + 1, tables.size(),
                        "已完成: " + fullTableName, result.getPendingTables(), result.getCompletedTables());

                    if (!tr.success) {
                        result.setSuccess(false);
                        result.setMessage("Some tables failed");
                    }
                    if (tr.maxTs != null && (maxSyncTs == null || tr.maxTs.after(maxSyncTs))) {
                        maxSyncTs = tr.maxTs;
                    }
                } catch (Exception e) {
                    System.err.println("[DataSyncExecutor] 同步表失败 [" + fullTableName + "]: " + e.getMessage());
                    e.printStackTrace();

                    // 创建失败日志
                    ErDataTransferLog errorLog = new ErDataTransferLog();
                    errorLog.setTransferId(task.getTransferId());
                    errorLog.setSourceTable(fullTableName);
                    errorLog.setTargetTable(fullTableName);
                    errorLog.setSyncMode(task.getSyncMode());
                    errorLog.setSyncAction("0".equals(task.getSyncMode()) ? "FULL" : "INCR");
                    errorLog.setSyncResult("1"); // 失败
                    errorLog.setErrorMessage(e.getMessage());
                    errorLog.setTotalCount(0L);
                    errorLog.setSuccessCount(0L);
                    errorLog.setFailCount(0L);
                    java.util.Date errStart = DateUtils.getNowDate();
                    errorLog.setStartTime(errStart);
                    errorLog.setEndTime(errStart);
                    errorLog.setExecuteTime(0L);
                    result.addLog(errorLog);
                    result.getCompletedTables().add(fullTableName);

                    result.setSuccess(false);
                    result.setMessage("表同步失败: " + fullTableName + " - " + e.getMessage());

                    // 继续同步下一张表，而不是直接抛出异常
                }
            }
            System.out.println("[DataSyncExecutor] 所有表同步完成");

            System.out.println("[DataSyncExecutor] 更新最后同步时间戳");
            if (maxSyncTs != null) {
                ErDataTransfer upd = new ErDataTransfer();
                upd.setTransferId(task.getTransferId());
                upd.setLastSyncValue(String.valueOf(maxSyncTs.getTime()));
                transferMapper.updateErDataTransfer(upd);
                System.out.println("[DataSyncExecutor] 最后同步时间戳已更新: " + maxSyncTs.getTime());
            }

            System.out.println("[DataSyncExecutor] 统计同步结果");
            long totalCount = 0;
            long successCount = 0;
            long failCount = 0;
            for (ErDataTransferLog log : result.getLogs()) {
                if (log.getTotalCount() != null) totalCount += log.getTotalCount();
                if ("0".equals(log.getSyncResult())) {
                    successCount += log.getSuccessCount() == null ? 0 : log.getSuccessCount();
                } else {
                    failCount += log.getTotalCount() == null ? 0 : log.getTotalCount();
                }
            }
            result.setTotalCount(totalCount);
            result.setSuccessCount(successCount);
            result.setFailCount(failCount);
            System.out.println("[DataSyncExecutor] 统计结果 - 总数: " + totalCount + ", 成功: " + successCount + ", 失败: " + failCount);
        } finally {
            closeQuietly(sourceConn);
            closeQuietly(targetConn);
        }
        System.out.println("[DataSyncExecutor] execute方法执行完成，返回结果");
        return result;
    }

    /**
     * 打开数据源连接并完成通用会话初始化；数据库特定初始化由子类 hook 处理。
     */
    private Connection openManagedConnection(ErDatasource ds) throws Exception {
        Connection conn = DriverManager.getConnection(ds.getJdbcUrl(), ds.getUsername(), ds.getPassword());
        conn.setAutoCommit(false);
        initializeConnection(ds, conn);
        return conn;
    }

    /**
     * 数据库特定连接初始化入口。子类可在这里设置会话参数。
     */
    protected void initializeConnection(ErDatasource ds, Connection conn) throws Exception {
        DataSyncConnectionInitializer.initialize(ds, conn);
    }

    private static boolean isConnectionAlive(Connection conn) {
        if (conn == null) return false;
        try {
            return !conn.isClosed() && conn.isValid(5);
        } catch (Exception e) {
            return false;
        }
    }

    private static void closeQuietly(Connection conn) {
        if (conn == null) return;
        try {
            conn.close();
        } catch (Exception ignored) {
            // 忽略关闭异常
        }
    }

    private SyncTableResult syncTable(ErDataTransfer task, String schema, String table, DataSyncExtractor extractor, DataSyncLoader loader,
                                      Connection sourceConn, Connection targetConn, int batchSize, SyncProgressCallback callback,
                                      Map<String, Timestamp> lastSyncMap) throws Exception {
        SyncTableResult tr = new SyncTableResult();
        tr.success = true;
        tr.log = new ErDataTransferLog();
        tr.log.setTransferId(task.getTransferId());
        tr.log.setSourceTable(schema != null && !schema.isEmpty() ? (schema + "." + table) : table);
        // 目标表：若显式指定 schema.table 则使用；否则沿用源 schema（规范化大小写）
        String targetSchema = null;
        String targetTableName;
        if (task.getTargetTable() != null && !task.getTargetTable().isEmpty()) {
            String tt = task.getTargetTable();
            if (tt.contains(".")) {
                int dot = tt.indexOf('.');
                targetSchema = tt.substring(0, dot);
                targetTableName = tt.substring(dot + 1);
            } else {
                targetTableName = tt;
            }
        } else {
            // 没有显式指定目标表，使用源表名并规范化大小写（Oracle大写 -> 其他库小写）
            targetTableName = normalizeForTarget(table);
            if (schema != null && !schema.isEmpty()) {
                targetSchema = normalizeForTarget(schema);
            }
        }
        String targetTableSqlName = qualify(targetSchema, targetTableName);
        tr.log.setTargetTable(targetSchema != null ? targetSchema + "." + targetTableName : targetTableName);
        tr.log.setSyncMode(task.getSyncMode());
        tr.log.setStartTime(DateUtils.getNowDate());

        // 源列名（用于从源数据库读取数据）
        List<String> sourceCols = extractor.listColumns(sourceConn, schema, table);
        if (sourceCols.isEmpty()) {
            tr.success = false;
            tr.log.setSyncResult("1");
            tr.log.setErrorMessage("No columns");
            return tr;
        }
        List<String> sourcePk = extractor.listPrimaryKeys(sourceConn, schema, table);
        if (sourcePk.isEmpty()) sourcePk = sourceCols.subList(0, 1); // fallback

        // 目标列名（规范化大小写，用于目标数据库写入）
        List<String> targetCols = new ArrayList<>();
        for (String c : sourceCols) targetCols.add(normalizeForTarget(c));
        List<String> targetPk = new ArrayList<>();
        for (String p : sourcePk) targetPk.add(normalizeForTarget(p));

        boolean doInsert = "1".equals(task.getSyncInsert());
        boolean doUpdate = "1".equals(task.getSyncUpdate());
        boolean doDelete = "1".equals(task.getSyncDelete());
        String tsField = task.getTimestampField();

        // 同步前修复目标表 schema（各子类可重写）
        try {
            prepareTargetSchema(sourceConn, targetConn, schema, table, targetTableSqlName);
        } catch (Exception e) {
            System.out.println("[DataSyncExecutor] prepareTargetSchema 警告（忽略，继续同步）: " + e.getMessage());
        }

        if ("0".equals(task.getSyncMode())) {
            tr = syncFull(task, schema, table, targetTableSqlName, sourceCols, targetCols, targetPk, extractor, loader, sourceConn, targetConn, batchSize, tsField, doInsert, doUpdate, callback);
        } else {
            tr = syncIncremental(task, schema, table, targetTableSqlName, sourceCols, targetCols, targetPk, extractor, loader, sourceConn, targetConn, batchSize, tsField, doInsert, doUpdate, doDelete, callback, lastSyncMap);
        }

        tr.log.setEndTime(DateUtils.getNowDate());
        if (tr.log.getStartTime() != null && tr.log.getEndTime() != null) {
            tr.log.setExecuteTime(tr.log.getEndTime().getTime() - tr.log.getStartTime().getTime());
        }
        return tr;
    }

    private SyncTableResult syncFull(ErDataTransfer task, String schema, String table, String targetTable,
                                     List<String> sourceCols, List<String> targetCols, List<String> targetPk,
                                     DataSyncExtractor extractor, DataSyncLoader loader,
                                     Connection sourceConn, Connection targetConn, int batchSize, String tsField,
                                     boolean doInsert, boolean doUpdate, SyncProgressCallback callback) throws Exception {
        SyncTableResult tr = new SyncTableResult();
        tr.success = true;
        tr.log = new ErDataTransferLog();
        tr.log.setTransferId(task.getTransferId());
        tr.log.setSourceTable(schema != null && !schema.isEmpty() ? (schema + "." + table) : table);
        tr.log.setTargetTable(targetTable);
        tr.log.setSyncMode("0");
        tr.log.setSyncAction("FULL");
        tr.log.setStartTime(DateUtils.getNowDate());

        // 源端 SELECT 用源列名构建排序（pk 也需要源端原始大小写）
        List<String> sourcePk = new ArrayList<>();
        for (String p : targetPk) {
            // 在源列名中找到对应列（忽略大小写匹配）
            for (String sc : sourceCols) {
                if (sc.equalsIgnoreCase(p)) { sourcePk.add(sc); break; }
            }
        }
        if (sourcePk.isEmpty()) sourcePk = sourceCols.subList(0, 1);

        // Clear target table using database-specific method
        clearTable(targetConn, targetTable);
        targetConn.commit();

        // Full sync always TRUNCATEs first, so target is always empty; always use INSERT, never MERGE
        // Oracle JDBC addBatch()/executeBatch() silently fails for MERGE INTO statements
        String upsertSql = loader.buildUpsertSql(targetTable, targetCols, false, targetPk);

        System.out.println("[DataSyncExecutor.syncFull] Generated upsert SQL: " + upsertSql);

        beforeBatchInsert(targetConn, targetTable);
        try {
            try (PreparedStatement psIns = targetConn.prepareStatement(upsertSql)) {
                FullSyncStats stats;
                if (shouldUseTimestampWindowFullSync(extractor, tsField)) {
                    stats = syncFullByTimestampWindow(schema, table, targetTable, sourceCols, targetCols, targetPk,
                            sourcePk, extractor, sourceConn, targetConn, psIns, batchSize, tsField,
                            callback, tr.log.getSourceTable());
                } else {
                    String selectSql = extractor.buildSelectFull(schema, table, tsField, sourcePk);
                    stats = syncFullQuery(selectSql, null, targetTable, sourceCols, targetCols, targetPk,
                            sourceConn, targetConn, psIns, batchSize, tsField, callback,
                            tr.log.getSourceTable(), "Full sync", new FullSyncStats());
                }
                tr.log.setTotalCount(stats.processed);
                tr.log.setSuccessCount(stats.processed);
                tr.log.setFailCount(0L);
                tr.log.setSyncResult("0");
                tr.maxTs = stats.maxTs;
                if (stats.maxTs != null) {
                    tr.log.setLastSyncValue(String.valueOf(stats.maxTs.getTime()));
                    saveProgress(task.getTransferId(), tr.log.getSourceTable(), stats.maxTs);
                }
            } catch (Exception e) {
                targetConn.rollback();
                tr.success = false;
                tr.log.setSyncResult("1");
                tr.log.setErrorMessage(e.getMessage());
                throw e;
            }
        } finally {
            afterBatchInsert(targetConn, targetTable);
        }
        return tr;
    }

    private boolean shouldUseTimestampWindowFullSync(DataSyncExtractor extractor, String tsField) {
        return extractor.supportsTimestampWindowFullSync() && tsField != null && !tsField.trim().isEmpty();
    }

    private FullSyncStats syncFullByTimestampWindow(String schema, String table, String targetTable,
                                                    List<String> sourceCols, List<String> targetCols, List<String> targetPk,
                                                    List<String> sourcePk, DataSyncExtractor extractor,
                                                    Connection sourceConn, Connection targetConn, PreparedStatement psIns,
                                                    int batchSize, String tsField, SyncProgressCallback callback,
                                                    String sourceTableName) throws Exception {
        TimestampBounds bounds = loadTimestampBounds(extractor, sourceConn, schema, table, tsField);
        if (bounds == null || bounds.minTs == null || bounds.maxTs == null) {
            System.out.println("[DataSyncExecutor.syncFull] 时间戳范围为空，回退为普通全表查询: " + sourceTableName);
            return syncFullQuery(extractor.buildSelectFull(schema, table, tsField, sourcePk), null, targetTable,
                    sourceCols, targetCols, targetPk, sourceConn, targetConn, psIns, batchSize, tsField,
                    callback, sourceTableName, "Full sync", new FullSyncStats());
        }

        System.out.println("[DataSyncExecutor.syncFull] 启用按天窗口全量同步: " + sourceTableName
                + ", minTs=" + bounds.minTs + ", maxTs=" + bounds.maxTs);

        FullSyncStats stats = new FullSyncStats();
        String windowSql = extractor.buildSelectFullWindow(schema, table, tsField, sourcePk);
        Timestamp windowStart = truncateToDay(bounds.minTs);
        int windowDays = Math.max(1, getFullSyncWindowDays());
        while (!windowStart.after(bounds.maxTs)) {
            Timestamp currentStart = windowStart;
            Timestamp currentEnd = addDays(currentStart, windowDays);
            String messagePrefix = "Full sync " + currentStart.toLocalDateTime().toLocalDate();
            syncFullQuery(windowSql, ps -> {
                ps.setTimestamp(1, currentStart);
                ps.setTimestamp(2, currentEnd);
            }, targetTable, sourceCols, targetCols, targetPk, sourceConn, targetConn, psIns, batchSize, tsField,
                    callback, sourceTableName, messagePrefix, stats);
            windowStart = currentEnd;
        }
        return stats;
    }

    private TimestampBounds loadTimestampBounds(DataSyncExtractor extractor, Connection sourceConn,
                                                String schema, String table, String tsField) throws Exception {
        String boundsSql = extractor.buildSelectTimestampBounds(schema, table, tsField);
        try (PreparedStatement ps = sourceConn.prepareStatement(boundsSql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            TimestampBounds bounds = new TimestampBounds();
            bounds.minTs = rs.getTimestamp(1);
            bounds.maxTs = rs.getTimestamp(2);
            return bounds;
        }
    }

    private FullSyncStats syncFullQuery(String selectSql, PreparedStatementBinder binder, String targetTable,
                                        List<String> sourceCols, List<String> targetCols, List<String> targetPk,
                                        Connection sourceConn, Connection targetConn, PreparedStatement psIns,
                                        int batchSize, String tsField, SyncProgressCallback callback,
                                        String sourceTableName, String messagePrefix, FullSyncStats stats) throws Exception {
        try (PreparedStatement psSel = sourceConn.prepareStatement(selectSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            psSel.setFetchSize(batchSize);
            if (binder != null) {
                binder.bind(psSel);
            }
            try (ResultSet rs = psSel.executeQuery()) {
                ResultSetMetaData rsmd = rs.getMetaData();
                List<String> sourceTypeNames = new ArrayList<>();
                for (int i = 0; i < sourceCols.size(); i++) {
                    int colIdx = rs.findColumn(sourceCols.get(i));
                    sourceTypeNames.add(rsmd.getColumnTypeName(colIdx));
                }

                List<Object[]> rowBuffer = new ArrayList<>();
                int batch = 0;
                while (rs.next()) {
                    Object[] rowData = new Object[sourceCols.size()];
                    for (int i = 0; i < sourceCols.size(); i++) {
                        Object val = rs.getObject(sourceCols.get(i));
                        val = convertColumnValue(val, sourceTypeNames.get(i));
                        rowData[i] = val;
                        if (!useBufferedFullInsert()) psIns.setObject(i + 1, val);
                    }
                    Timestamp ts = readTimestampColumn(rs, tsField);
                    if (ts != null && (stats.maxTs == null || ts.after(stats.maxTs))) stats.maxTs = ts;
                    if (useBufferedFullInsert()) {
                        rowBuffer.add(rowData);
                    } else {
                        psIns.addBatch();
                    }
                    batch++;
                    stats.processed++;
                    if (batch >= batchSize) {
                        if (useBufferedFullInsert()) {
                            executeBufferedFullInsert(targetConn, rowBuffer, targetTable, targetCols, targetPk);
                            rowBuffer.clear();
                        } else {
                            psIns.executeBatch();
                        }
                        targetConn.commit();
                        batch = 0;
                        if (callback != null) {
                            callback.onProgress((int)Math.min(stats.processed, Integer.MAX_VALUE), -1,
                                    messagePrefix + ": " + sourceTableName + "(" + stats.processed + ")");
                        }
                    }
                }
                if (batch > 0) {
                    if (useBufferedFullInsert()) {
                        executeBufferedFullInsert(targetConn, rowBuffer, targetTable, targetCols, targetPk);
                        rowBuffer.clear();
                    } else {
                        psIns.executeBatch();
                    }
                    targetConn.commit();
                }
            }
        }
        return stats;
    }

    protected int getFullSyncWindowDays() {
        return 1;
    }

    private static Timestamp truncateToDay(Timestamp ts) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(ts.getTime());
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return new Timestamp(cal.getTimeInMillis());
    }

    private static Timestamp addDays(Timestamp ts, int days) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(ts.getTime());
        cal.add(Calendar.DAY_OF_MONTH, days);
        return new Timestamp(cal.getTimeInMillis());
    }

    private interface PreparedStatementBinder {
        void bind(PreparedStatement ps) throws Exception;
    }

    private SyncTableResult syncIncremental(ErDataTransfer task, String schema, String table, String targetTable,
                                            List<String> sourceCols, List<String> targetCols, List<String> targetPk,
                                            DataSyncExtractor extractor, DataSyncLoader loader,
                                            Connection sourceConn, Connection targetConn, int batchSize, String tsField,
                                            boolean doInsert, boolean doUpdate, boolean doDelete, SyncProgressCallback callback,
                                            Map<String, Timestamp> lastSyncMap) throws Exception {
        SyncTableResult tr = new SyncTableResult();
        tr.success = true;
        tr.log = new ErDataTransferLog();
        tr.log.setTransferId(task.getTransferId());
        // 源表包含完整的 schema.table 格式
        String key = schema != null && !schema.isEmpty() ? schema + "." + table : table;
        tr.log.setSourceTable(key);
        tr.log.setTargetTable(targetTable);
        tr.log.setSyncMode("1");
        tr.log.setSyncAction("INCR");
        tr.log.setStartTime(DateUtils.getNowDate());

        // 源端 PK（用于 buildSelectIncremental 的 ORDER BY）
        List<String> sourcePk = new ArrayList<>();
        for (String p : targetPk) {
            for (String sc : sourceCols) {
                if (sc.equalsIgnoreCase(p)) { sourcePk.add(sc); break; }
            }
        }
        if (sourcePk.isEmpty()) sourcePk = sourceCols.subList(0, 1);

        Timestamp lastTs = resolveIncrementalStartTimestamp(task, key, targetTable, targetCols, tsField, targetConn, lastSyncMap);
        if (lastTs == null) {
            throw new IllegalStateException("增量同步未找到起始时间戳，已停止同步该表: " + key
                    + "。请先执行初始化同步，或确保目标表存在可读取的最大时间戳字段 " + tsField);
        }
        String selectSql = extractor.buildSelectIncremental(schema, table, tsField, sourcePk);
        String upsertSql = loader.buildUpsertSql(targetTable, targetCols, doUpdate, targetPk);
        Timestamp maxTs = lastTs;

        System.out.println("[DataSyncExecutor.syncIncremental] 开始增量同步: " + key);
        beforeBatchInsert(targetConn, targetTable);
        try {
            try (PreparedStatement psSel = sourceConn.prepareStatement(selectSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                 PreparedStatement psIns = targetConn.prepareStatement(upsertSql)) {
                psSel.setFetchSize(batchSize);
                psSel.setTimestamp(1, lastTs);
                System.out.println("[DataSyncExecutor.syncIncremental] 执行查询，lastTs=" + lastTs);
                ResultSet rs = psSel.executeQuery();

                // 预先识别需要特殊处理的列类型
                ResultSetMetaData rsmd = rs.getMetaData();
                List<String> sourceTypeNames = new ArrayList<>();
                for (int i = 0; i < sourceCols.size(); i++) {
                    int colIdx = rs.findColumn(sourceCols.get(i));
                    sourceTypeNames.add(rsmd.getColumnTypeName(colIdx));
                }

                List<Object[]> rowBuffer = new ArrayList<>();
                int batch = 0;
                long processed = 0;
                while (rs.next()) {
                    Object[] rowData = new Object[sourceCols.size()];
                    for (int i = 0; i < sourceCols.size(); i++) {
                        Object val = rs.getObject(sourceCols.get(i));
                        val = convertColumnValue(val, sourceTypeNames.get(i));
                        rowData[i] = val;
                        if (!useBufferedUpsert()) psIns.setObject(i + 1, val);
                    }
                    Timestamp ts = readTimestampColumn(rs, tsField);
                    if (ts != null && (maxTs == null || ts.after(maxTs))) maxTs = ts;
                    if (useBufferedUpsert()) {
                        rowBuffer.add(rowData);
                    } else if (useRowByRowUpsert()) {
                        psIns.executeUpdate();
                    } else {
                        psIns.addBatch();
                    }
                    batch++;
                    processed++;
                    if (batch >= batchSize) {
                        if (useBufferedUpsert()) {
                            executeBufferedUpsert(targetConn, rowBuffer, targetTable, targetCols, targetPk);
                            rowBuffer.clear();
                        } else if (!useRowByRowUpsert()) {
                            psIns.executeBatch();
                        }
                        targetConn.commit();
                        batch = 0;
                        if (callback != null) callback.onProgress((int)Math.min(processed, Integer.MAX_VALUE), -1, "Incremental: " + tr.log.getSourceTable() + "(" + processed + ")");
                    }
                }
                System.out.println("[DataSyncExecutor.syncIncremental] 数据读取完成，总记录数: " + processed + "，剩余batch: " + batch);
                if (batch > 0) {
                    System.out.println("[DataSyncExecutor.syncIncremental] 提交最后一批数据: " + batch + " 条");
                    if (useBufferedUpsert()) {
                        executeBufferedUpsert(targetConn, rowBuffer, targetTable, targetCols, targetPk);
                        rowBuffer.clear();
                    } else if (!useRowByRowUpsert()) {
                        psIns.executeBatch();
                    }
                    targetConn.commit();
                    System.out.println("[DataSyncExecutor.syncIncremental] 最后一批数据已提交");
                }
                tr.log.setTotalCount(processed);
                tr.log.setSuccessCount(processed);
                tr.log.setFailCount(0L);
                tr.log.setSyncResult("0");
                System.out.println("[DataSyncExecutor.syncIncremental] 增量同步完成，总数: " + processed);
            } catch (Exception e) {
                System.err.println("[DataSyncExecutor.syncIncremental] 增量同步异常: " + e.getMessage());
                e.printStackTrace();
                targetConn.rollback();
                tr.success = false;
                tr.log.setSyncResult("1");
                tr.log.setErrorMessage(e.getMessage());
                throw e;
            }
        } finally {
            afterBatchInsert(targetConn, targetTable);
        }

        // delete alignment
        if (doDelete) {
            System.out.println("[DataSyncExecutor.syncIncremental] 开始删除对齐");
            try {
                int windowYears = task.getDeleteWindowYears() != null ? task.getDeleteWindowYears() : 2;
                Timestamp windowStart = new Timestamp(System.currentTimeMillis() - (long) windowYears * 365 * 24 * 3600 * 1000);
                int deletedCount = alignDelete(task, schema, table, targetTable, targetPk.get(0), tsField, extractor, loader, sourceConn, targetConn, batchSize, windowStart);
                System.out.println("[DataSyncExecutor.syncIncremental] 删除对齐完成，删除 " + deletedCount + " 条记录");
                // 将删除的记录数累加到总数中
                if (deletedCount > 0) {
                    long currentTotal = tr.log.getTotalCount() != null ? tr.log.getTotalCount() : 0;
                    long currentSuccess = tr.log.getSuccessCount() != null ? tr.log.getSuccessCount() : 0;
                    tr.log.setTotalCount(currentTotal + deletedCount);
                    tr.log.setSuccessCount(currentSuccess + deletedCount);
                    System.out.println("[DataSyncExecutor.syncIncremental] 更新统计信息，总数: " + tr.log.getTotalCount() + ", 成功: " + tr.log.getSuccessCount());
                }
            } catch (Exception e) {
                System.err.println("[DataSyncExecutor.syncIncremental] 删除对齐失败: " + e.getMessage());
                e.printStackTrace();
                // 删除对齐失败不影响整体同步结果，只记录错误
            }
        }

        System.out.println("[DataSyncExecutor.syncIncremental] 保存进度");
        if (maxTs != null) {
            tr.log.setLastSyncValue(String.valueOf(maxTs.getTime()));
            lastSyncMap.put(key, maxTs);
            saveProgress(task.getTransferId(), tr.log.getSourceTable(), maxTs);
            System.out.println("[DataSyncExecutor.syncIncremental] 进度已保存，maxTs=" + maxTs.getTime());
        }
        tr.maxTs = maxTs;
        System.out.println("[DataSyncExecutor.syncIncremental] 返回同步结果");
        return tr;
    }

    /**
     * 构建删除对齐的分页查询 SQL
     * 子类可以重写此方法以支持数据库特定的分页语法
     *
     * @param targetTable 目标表名（已经是完全限定名，如 `schema`.`table` 或 [db].[schema].[table]）
     * @param pk 主键列名
     * @param tsField 时间戳列名
     * @return 分页查询 SQL（使用两个参数：时间戳和批量大小）
     */
    protected String buildAlignDeleteSelectSql(String targetTable, String pk, String tsField) {
        // MySQL/TiDB/StarRocks/PostgreSQL: LIMIT 语法
        // 注意：targetTable 已经是完全限定名，不需要再引用
        return "SELECT " + quoteTargetIdentifier(pk) + ", " + quoteTargetIdentifier(tsField) +
               " FROM " + targetTable +
               " WHERE " + quoteTargetIdentifier(tsField) + " >= ? ORDER BY " + quoteTargetIdentifier(tsField) +
               ", " + quoteTargetIdentifier(pk) + " LIMIT ?";
    }

    /**
     * 设置删除对齐查询的参数
     * 子类可以重写此方法以适应不同的 SQL 语法（如 SQL Server 的 TOP）
     *
     * @param ps PreparedStatement
     * @param cursor 当前时间戳游标
     * @param batchSize 批量大小
     */
    protected void setAlignDeleteQueryParams(PreparedStatement ps, Timestamp cursor, int batchSize) throws Exception {
        // MySQL/TiDB/StarRocks/PostgreSQL: LIMIT 语法，参数顺序：timestamp, batchSize
        ps.setTimestamp(1, cursor);
        ps.setInt(2, batchSize);
    }

    private int alignDelete(ErDataTransfer task, String schema, String sourceTable, String targetTable, String pk, String tsField, DataSyncExtractor extractor,
                             DataSyncLoader loader, Connection sourceConn, Connection targetConn, int batchSize, Timestamp windowStart) throws Exception {
        System.out.println("[DataSyncExecutor.alignDelete] 开始删除对齐，窗口起始: " + windowStart);
        // 查询目标表：使用目标数据库的标识符引用
        String selectTarget = buildAlignDeleteSelectSql(targetTable, pk, tsField);
        boolean hasMore = true;
        Timestamp cursor = windowStart;
        int batchCount = 0;
        int totalDeleted = 0;

        while (hasMore) {
            batchCount++;
            List<Object> ids = new ArrayList<>();
            Timestamp maxTs = cursor;
            try (PreparedStatement ps = targetConn.prepareStatement(selectTarget)) {
                setAlignDeleteQueryParams(ps, cursor, batchSize);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    ids.add(rs.getObject(pk));
                    Timestamp ts = rs.getTimestamp(tsField);
                    if (ts != null && (maxTs == null || ts.after(maxTs))) {
                        maxTs = ts;
                    }
                }
            }
            hasMore = ids.size() == batchSize;
            System.out.println("[DataSyncExecutor.alignDelete] 批次 " + batchCount + ": 读取 " + ids.size() + " 条记录, hasMore=" + hasMore);
            if (ids.isEmpty()) break;

            // check source side - 查询源表：使用源数据库的标识符引用
            Set<String> exist = new HashSet<>();
            String sourceTableName = (schema != null && !schema.isEmpty())
                    ? (quoteSourceIdentifier(schema) + "." + quoteSourceIdentifier(sourceTable))
                    : quoteSourceIdentifier(sourceTable);
            int deleteChunkSize = getDeleteBatchSize();
            for (int start = 0; start < ids.size(); start += deleteChunkSize) {
                int end = Math.min(start + deleteChunkSize, ids.size());
                List<Object> chunk = ids.subList(start, end);
                String checkSql = "SELECT " + quoteSourceIdentifier(pk) + " FROM " + sourceTableName +
                        " WHERE " + quoteSourceIdentifier(pk) + " IN (" + buildPlaceholders(chunk.size()) + ")";
                try (PreparedStatement ps = sourceConn.prepareStatement(checkSql)) {
                    bindObjects(ps, chunk);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) exist.add(String.valueOf(rs.getObject(pk)));
                    }
                }
            }

            // delete rows missing in source - 合并为 DELETE ... IN (?,...)，减少单行 DELETE 往返开销
            List<Object> deleteIds = new ArrayList<>();
            for (Object id : ids) {
                if (!exist.contains(String.valueOf(id))) {
                    deleteIds.add(id);
                }
            }

            int deleted = executeDeleteByIds(targetConn, targetTable, pk, deleteIds);
            totalDeleted += deleted;
            System.out.println("[DataSyncExecutor.alignDelete] 批次 " + batchCount + ": 删除 " + deleted + " 条记录");

            // 防止无限循环：如果 maxTs 没有变化，强制退出
            if (maxTs != null && maxTs.equals(cursor)) {
                System.out.println("[DataSyncExecutor.alignDelete] 警告: 时间戳未前进，退出循环避免死循环");
                break;
            }
            cursor = maxTs;
        }
        System.out.println("[DataSyncExecutor.alignDelete] 删除对齐完成，共处理 " + batchCount + " 批次，删除 " + totalDeleted + " 条记录");
        return totalDeleted;
    }

    private int executeDeleteByIds(Connection targetConn, String targetTable, String pk, List<Object> deleteIds) throws Exception {
        if (deleteIds == null || deleteIds.isEmpty()) return 0;
        int total = 0;
        int deleteChunkSize = getDeleteBatchSize();
        for (int start = 0; start < deleteIds.size(); start += deleteChunkSize) {
            int end = Math.min(start + deleteChunkSize, deleteIds.size());
            List<Object> chunk = deleteIds.subList(start, end);
            String deleteSql = "DELETE FROM " + targetTable + " WHERE " + quoteTargetIdentifier(pk) +
                    " IN (" + buildPlaceholders(chunk.size()) + ")";
            try (PreparedStatement ps = targetConn.prepareStatement(deleteSql)) {
                bindObjects(ps, chunk);
                total += ps.executeUpdate();
            }
        }
        targetConn.commit();
        return total;
    }

    private String buildPlaceholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(",");
            sb.append("?");
        }
        return sb.toString();
    }

    private void bindObjects(PreparedStatement ps, List<Object> values) throws Exception {
        for (int i = 0; i < values.size(); i++) {
            ps.setObject(i + 1, values.get(i));
        }
    }

    /**
     * DELETE ... IN (...) 的单条 SQL 最大主键数量。
     * 保持 1000 可兼容 Oracle IN 列表限制，同时显著少于逐行 DELETE。
     */
    protected int getDeleteBatchSize() {
        return 1000;
    }

    private Timestamp resolveIncrementalStartTimestamp(ErDataTransfer task, String sourceTable, String targetTable,
                                                       List<String> targetCols, String tsField, Connection targetConn,
                                                       Map<String, Timestamp> lastSyncMap) {
        Timestamp lastTs = lastSyncMap.get(sourceTable);
        if (lastTs != null) {
            return lastTs;
        }

        lastTs = parseTimestamp(task.getLastSyncValue());
        if (lastTs != null) {
            return lastTs;
        }

        Timestamp targetMaxTs = loadTargetMaxTimestamp(targetConn, targetTable, targetCols, tsField);
        if (targetMaxTs != null) {
            System.out.println("[DataSyncExecutor.syncIncremental] 未找到任务进度，使用目标表最大时间戳作为增量起点: "
                    + sourceTable + ", maxTs=" + targetMaxTs);
            lastSyncMap.put(sourceTable, targetMaxTs);
        }
        return targetMaxTs;
    }

    private Timestamp loadTargetMaxTimestamp(Connection targetConn, String targetTable, List<String> targetCols, String tsField) {
        if (targetConn == null || targetTable == null || tsField == null || tsField.trim().isEmpty()) {
            return null;
        }

        String targetTsField = resolveTargetTimestampColumn(targetCols, tsField);
        if (targetTsField == null) {
            System.out.println("[DataSyncExecutor.syncIncremental] 目标表未找到时间戳字段，无法从目标表推断增量起点: " + tsField);
            return null;
        }

        String sql = "SELECT MAX(" + quoteTargetIdentifier(targetTsField) + ") FROM " + targetTable;
        try (PreparedStatement ps = targetConn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return null;
            }
            return asTimestamp(convertValue(rs.getObject(1)));
        } catch (Exception e) {
            System.out.println("[DataSyncExecutor.syncIncremental] 查询目标表最大时间戳失败，增量同步不会回退到 1970: "
                    + e.getMessage());
            return null;
        }
    }

    private String resolveTargetTimestampColumn(List<String> targetCols, String tsField) {
        if (targetCols == null || tsField == null) {
            return null;
        }
        String normalized = normalizeForTarget(tsField);
        for (String c : targetCols) {
            if (c != null && c.equals(normalized)) {
                return c;
            }
        }
        for (String c : targetCols) {
            if (c != null && c.equalsIgnoreCase(normalized)) {
                return c;
            }
        }
        return null;
    }

    private Timestamp asTimestamp(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp) {
            return (Timestamp) value;
        }
        if (value instanceof java.sql.Date) {
            return new Timestamp(((java.sql.Date) value).getTime());
        }
        if (value instanceof java.util.Date) {
            return new Timestamp(((java.util.Date) value).getTime());
        }
        if (value instanceof java.time.OffsetDateTime) {
            return Timestamp.from(((java.time.OffsetDateTime) value).toInstant());
        }
        if (value instanceof java.time.LocalDateTime) {
            return Timestamp.valueOf((java.time.LocalDateTime) value);
        }
        return null;
    }

    private Timestamp parseTimestamp(String v) {
        if (v == null || v.isEmpty()) return null;
        try { return new Timestamp(Long.parseLong(v)); } catch (Exception e) { return null; }
    }

    public static class DataSyncResult {
        private boolean success;
        private boolean stopped; // 是否被停止
        private String message;
        private long totalCount;
        private long successCount;
        private long failCount;
        private final List<ErDataTransferLog> logs = new ArrayList<>();
        private final List<String> pendingTables = new ArrayList<>(); // 待同步的表
        private final List<String> completedTables = new ArrayList<>(); // 已同步的表

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public boolean isStopped() { return stopped; }
        public void setStopped(boolean stopped) { this.stopped = stopped; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public long getTotalCount() { return totalCount; }
        public void setTotalCount(long totalCount) { this.totalCount = totalCount; }
        public long getSuccessCount() { return successCount; }
        public void setSuccessCount(long successCount) { this.successCount = successCount; }
        public long getFailCount() { return failCount; }
        public void setFailCount(long failCount) { this.failCount = failCount; }
        public List<ErDataTransferLog> getLogs() { return logs; }
        public void addLog(ErDataTransferLog log) { if (log != null) logs.add(log); }
        public List<String> getPendingTables() { return pendingTables; }
        public List<String> getCompletedTables() { return completedTables; }
    }

    private static class SyncTableResult {
        boolean success;
        Timestamp maxTs;
        ErDataTransferLog log;
    }

    private static class FullSyncStats {
        long processed;
        Timestamp maxTs;
    }

    private static class TimestampBounds {
        Timestamp minTs;
        Timestamp maxTs;
    }

    /**
     * 解析多 schema / 多表模式，返回携带实际 schema 的表清单。
     * 使用 extractor.listTableSpecs() 以支持 % 通配符和逗号分隔，返回的是实际存在的 (schema, table) 对。
     */
    private List<TableSpec> resolveTables(DataSyncExtractor extractor, Connection conn, String dbType,
                                          String schemaPatternCsv, String tablePatternCsv) throws Exception {
        List<String> schemaPatterns = new ArrayList<>();
        if (schemaPatternCsv != null && !schemaPatternCsv.trim().isEmpty()) {
            for (String s : schemaPatternCsv.split("[,;]")) {
                if (!s.trim().isEmpty()) schemaPatterns.add(s.trim());
            }
        }

        List<String> tablePatterns = new ArrayList<>();
        if (tablePatternCsv != null && !tablePatternCsv.trim().isEmpty()) {
            for (String t : tablePatternCsv.split("[,;]")) {
                if (!t.trim().isEmpty()) tablePatterns.add(t.trim());
            }
        }

        List<String[]> specs = extractor.listTableSpecs(conn, schemaPatterns, tablePatterns);
        List<TableSpec> result = new ArrayList<>();
        for (String[] spec : specs) {
            result.add(new TableSpec(spec[0], spec[1]));
        }
        return result;
    }

    private static class TableSpec {
        String schema;
        String table;
        TableSpec(String schema, String table) {
            this.schema = schema;
            this.table = table;
        }
    }

    /**
     * 为源数据库引用标识符
     */
    protected String quoteSourceIdentifier(String identifier) {
        return sourceStrategy.quoteIdentifier(identifier);
    }

    /**
     * 为目标数据库引用标识符
     */
    protected String quoteTargetIdentifier(String identifier) {
        return targetStrategy.quoteIdentifier(identifier);
    }

    /**
     * 规范化标识符大小写，用于目标数据库。
     * 保持源端原始大小写，不做强制转换（源是什么就是什么）。
     */
    protected String normalizeForTarget(String identifier) {
        if (identifier == null) return null;
        return identifier;
    }

    /**
     * 根据数据库类型生成正确的完全限定表名
     * 子类应该重写此方法以提供数据库特定的实现
     *
     * @param schema 数据库或模式名称（可能为 null）
     * @param table 表名
     * @return 完全限定的表名
     */
    protected String qualify(String schema, String table) {
        if (schema == null || schema.isEmpty()) {
            return table;
        }
        // 默认实现：使用目标数据库策略的标识符引用
        return targetStrategy.quoteIdentifier(schema) + "." + targetStrategy.quoteIdentifier(table);
    }

    private void saveProgress(Long transferId, String sourceTable, Timestamp ts) {
        if (transferId == null || sourceTable == null || ts == null) return;
        ErDataTransferProgress p = new ErDataTransferProgress();
        p.setTransferId(transferId);
        p.setSourceTable(sourceTable);
        p.setLastSyncValue(String.valueOf(ts.getTime()));
        progressMapper.upsert(p);
    }

    private Map<String, Timestamp> loadLastSyncMap(Long transferId) {
        Map<String, Timestamp> map = new HashMap<>();
        if (transferId == null) return map;
        // 1) 先查进度表（每表唯一）
        List<ErDataTransferProgress> progresses = progressMapper.selectByTransferId(transferId);
        if (progresses != null) {
            for (ErDataTransferProgress p : progresses) {
                Timestamp ts = parseTimestamp(p.getLastSyncValue());
                if (p.getSourceTable() != null && ts != null) {
                    map.put(p.getSourceTable(), ts);
                }
            }
        }
        // 2) 兼容：如没有进度表记录，则从历史日志兜底
        if (map.isEmpty()) {
            List<ErDataTransferLog> logs = logMapper.selectErDataTransferLogByTransferId(transferId);
            if (logs != null) {
                for (ErDataTransferLog log : logs) {
                    String tbl = log.getSourceTable();
                    Timestamp ts = parseTimestamp(log.getLastSyncValue());
                    if (tbl == null || ts == null) continue;
                    Timestamp old = map.get(tbl);
                    if (old == null || ts.after(old)) {
                        map.put(tbl, ts);
                    }
                }
            }
        }
        return map;
    }

    /**
     * 更新进度（包含表列表信息）
     */
    private void updateProgressWithTables(Long transferId, int current, int total, String message,
                                          List<String> pendingTables, List<String> completedTables) {
        if (transferId == null) return;
        Map<String, Object> progress = new HashMap<>();
        progress.put("current", current);
        progress.put("total", total);
        progress.put("percent", (total > 0 && current >= 0) ? (current * 100 / total) : 0);
        progress.put("message", message);
        progress.put("pendingTables", pendingTables != null ? pendingTables : new ArrayList<>());
        progress.put("completedTables", completedTables != null ? completedTables : new ArrayList<>());

        ErDataTransfer upd = new ErDataTransfer();
        upd.setTransferId(transferId);
        upd.setSyncProgress(JSON.toJSONString(progress));
        transferMapper.updateErDataTransfer(upd);
    }

    protected Object convertColumnValue(Object value, String sourceTypeName) {
        return valueConverter.convertColumnValue(value, sourceTypeName);
    }

    private Timestamp readTimestampColumn(ResultSet rs, String tsField) throws Exception {
        if (tsField == null || tsField.trim().isEmpty()) {
            return null;
        }
        Object tsObj = convertValue(rs.getObject(tsField));
        return (tsObj instanceof Timestamp) ? (Timestamp) tsObj : null;
    }

    /**
     * 将数据库特有类型转换为标准 JDBC 类型，确保跨数据库传递时兼容。
     */
    protected Object convertValue(Object value) {
        return valueConverter.convertValue(value);
    }
}




