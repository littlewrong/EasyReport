package cn.easyreport.combinesync.service.impl;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.easyreport.common.utils.DateUtils;
import cn.easyreport.common.utils.StringUtils;
import cn.easyreport.combinesync.domain.ErCombineSync;
import cn.easyreport.combinesync.domain.ErCombineSyncLog;
import cn.easyreport.combinesync.domain.ErCombineSyncProgress;
import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.combinesync.mapper.ErCombineSyncMapper;
import cn.easyreport.combinesync.mapper.ErCombineSyncLogMapper;
import cn.easyreport.combinesync.mapper.ErCombineSyncProgressMapper;
import cn.easyreport.sync.mapper.ErDatasourceMapper;
import cn.easyreport.combinesync.service.IErCombineSyncService;

/**
 * 合并同步任务Service业务层处理
 * 独立于现有sync同步，专门用于分库分表合并到一个库表
 *
 * @author easyreport
 * @date 2026-01-18
 */
@Service
public class ErCombineSyncServiceImpl implements IErCombineSyncService
{
    private static final Logger log = LoggerFactory.getLogger(ErCombineSyncServiceImpl.class);

    private static final ConcurrentHashMap<Long, Boolean> stopFlags = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Boolean> runningTasks = new ConcurrentHashMap<>();

    @Autowired
    private ErCombineSyncMapper erCombineSyncMapper;

    @Autowired
    private ErCombineSyncLogMapper erCombineSyncLogMapper;

    @Autowired
    private ErCombineSyncProgressMapper erCombineSyncProgressMapper;

    @Autowired
    private ErDatasourceMapper erDatasourceMapper;

    // ==================== CRUD方法 ====================

    @Override
    public ErCombineSync selectErCombineSyncByCombineId(Long combineId)
    {
        return erCombineSyncMapper.selectErCombineSyncByCombineId(combineId);
    }

    @Override
    public List<ErCombineSync> selectErCombineSyncList(ErCombineSync erCombineSync)
    {
        return erCombineSyncMapper.selectErCombineSyncList(erCombineSync);
    }

    @Override
    @Transactional
    public int insertErCombineSync(ErCombineSync erCombineSync)
    {
        if (erCombineSync.getSourceSchemaPattern() != null && erCombineSync.getSourceSchemaPattern().trim().isEmpty())
        {
            erCombineSync.setSourceSchemaPattern(null);
        }
        if (StringUtils.isEmpty(erCombineSync.getSourceColumn()))
        {
            erCombineSync.setSourceColumn("source_table");
        }
        erCombineSync.setCreateTime(DateUtils.getNowDate());
        erCombineSync.setSyncStatus("0");
        if (StringUtils.isEmpty(erCombineSync.getStatus()))
        {
            erCombineSync.setStatus("0");
        }
        return erCombineSyncMapper.insertErCombineSync(erCombineSync);
    }

    @Override
    @Transactional
    public int updateErCombineSync(ErCombineSync erCombineSync)
    {
        if (erCombineSync.getSourceSchemaPattern() != null && erCombineSync.getSourceSchemaPattern().trim().isEmpty())
        {
            erCombineSync.setSourceSchemaPattern(null);
        }
        erCombineSync.setUpdateTime(DateUtils.getNowDate());
        return erCombineSyncMapper.updateErCombineSync(erCombineSync);
    }

    @Override
    @Transactional
    public int deleteErCombineSyncByCombineIds(Long[] combineIds)
    {
        for (Long combineId : combineIds)
        {
            erCombineSyncLogMapper.deleteErCombineSyncLogByCombineId(combineId);
            erCombineSyncProgressMapper.deleteByCombineId(combineId);
        }
        return erCombineSyncMapper.deleteErCombineSyncByCombineIds(combineIds);
    }

    @Override
    @Transactional
    public int deleteErCombineSyncByCombineId(Long combineId)
    {
        erCombineSyncLogMapper.deleteErCombineSyncLogByCombineId(combineId);
        erCombineSyncProgressMapper.deleteByCombineId(combineId);
        return erCombineSyncMapper.deleteErCombineSyncByCombineId(combineId);
    }

    @Override
    public boolean checkCombineNameUnique(ErCombineSync erCombineSync)
    {
        Long combineId = StringUtils.isNull(erCombineSync.getCombineId()) ? -1L : erCombineSync.getCombineId();
        ErCombineSync info = erCombineSyncMapper.checkCombineNameUnique(erCombineSync.getCombineName());
        if (StringUtils.isNotNull(info) && info.getCombineId().longValue() != combineId.longValue())
        {
            return false;
        }
        return true;
    }

    // ==================== 执行同步 ====================

    @Override
    public Map<String, Object> executeCombineSync(Long combineId)
    {
        Map<String, Object> result = new HashMap<>();

        ErCombineSync task = erCombineSyncMapper.selectErCombineSyncByCombineId(combineId);
        if (task == null)
        {
            result.put("success", false);
            result.put("message", "合并任务不存在");
            return result;
        }

        if ("1".equals(task.getSyncStatus()))
        {
            result.put("success", false);
            result.put("message", "任务正在同步中，请勿重复执行");
            return result;
        }

        if (runningTasks.putIfAbsent(combineId, Boolean.TRUE) != null)
        {
            result.put("success", false);
            result.put("message", "任务正在同步中，请勿重复执行");
            return result;
        }

        try
        {
            ErDatasource source = erDatasourceMapper.selectErDatasourceByDatasourceId(task.getSourceDatasourceId());
            ErDatasource target = erDatasourceMapper.selectErDatasourceByDatasourceId(task.getTargetDatasourceId());
            if (source == null || target == null)
            {
                runningTasks.remove(combineId);
                result.put("success", false);
                result.put("message", "源或目标数据源未配置");
                return result;
            }

            stopFlags.remove(combineId);

            ErCombineSync updateTask = new ErCombineSync();
            updateTask.setCombineId(combineId);
            updateTask.setSyncStatus("1");
            updateTask.setSyncProgress("{\"current\":0,\"total\":0,\"percent\":0,\"message\":\"准备开始合并同步...\"}");
            erCombineSyncMapper.updateErCombineSync(updateTask);

            Thread asyncThread = new Thread(() -> {
                executeCombineSyncAsync(combineId, task, source, target);
            });
            asyncThread.setName("CombineSync-" + combineId);
            asyncThread.setUncaughtExceptionHandler((t, e) -> {
                log.error("合并同步任务 [{}] 线程异常退出", combineId, e);
                try {
                    ErCombineSync errorUpdate = new ErCombineSync();
                    errorUpdate.setCombineId(combineId);
                    errorUpdate.setSyncStatus("3");
                    errorUpdate.setLastSyncTime(DateUtils.getNowDate());
                    String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                    errorUpdate.setLastSyncResult("{\"error\":\"线程异常: " + errorMsg.replace("\"", "'") + "\"}");
                    erCombineSyncMapper.updateErCombineSync(errorUpdate);
                } catch (Exception ex) {
                    log.error("更新异常状态失败", ex);
                }
                stopFlags.remove(combineId);
                runningTasks.remove(combineId);
            });
            asyncThread.start();
            log.info("已启动异步合并同步线程: {}", asyncThread.getName());

            result.put("success", true);
            result.put("message", "合并同步任务已启动");
            return result;
        }
        catch (Exception e)
        {
            runningTasks.remove(combineId);
            stopFlags.remove(combineId);
            log.error("启动合并同步任务 [{}] 失败", combineId, e);
            result.put("success", false);
            result.put("message", "启动合并同步任务失败: " + (e.getMessage() == null ? e.getClass().getName() : e.getMessage()));
            return result;
        }
    }

    /**
     * 异步执行合并同步的核心逻辑
     */
    private void executeCombineSyncAsync(Long combineId, ErCombineSync task, ErDatasource source, ErDatasource target)
    {
        log.info("开始异步执行合并同步任务 [{}]", combineId);
        ErCombineSync updateTask = new ErCombineSync();
        updateTask.setCombineId(combineId);

        try
        {
            Class.forName(source.getDriverClass());
            Class.forName(target.getDriverClass());

            try (Connection sourceConn = DriverManager.getConnection(source.getJdbcUrl(), source.getUsername(), source.getPassword());
                 Connection targetConn = DriverManager.getConnection(target.getJdbcUrl(), target.getUsername(), target.getPassword()))
            {
                // 1. 发现所有匹配的分库分表
                updateProgress(combineId, 0, 0, "正在发现匹配的分库分表...");
                List<String[]> sourceTables = discoverSourceTables(sourceConn, task);
                if (sourceTables.isEmpty())
                {
                    throw new IllegalStateException("未匹配到任何源表，请检查Schema模式和源表名配置");
                }

                log.info("合并任务 [{}] 发现 {} 个源表", combineId, sourceTables.size());

                // 2. 解析目标库名
                String targetDbName = task.getTargetTable();
                if (targetDbName == null || targetDbName.trim().isEmpty())
                {
                    throw new IllegalArgumentException("目标库名不能为空");
                }
                targetDbName = targetDbName.replace("`", "").trim();

                // 3. 获取来源列名
                String sourceColumn = StringUtils.isNotEmpty(task.getSourceColumn()) ? task.getSourceColumn() : "source_table";

                // 4. 根据同步模式执行
                boolean isInit = "0".equals(task.getSyncMode());
                if (isInit)
                {
                    // 初始化：先创建/检查目标表
                    updateProgress(combineId, 0, sourceTables.size(), "正在创建/检查目标表...");
                    ensureTargetTableExists(sourceConn, targetConn, sourceTables, targetDbName, sourceColumn, task);

                    // 如果需要清空
                    if ("1".equals(task.getIsClear()))
                    {
                        updateProgress(combineId, 0, sourceTables.size(), "正在清空目标表...");
                        // 使用第一个源表名构建完整的目标表路径用于清空
                        String[] firstSource = sourceTables.get(0);
                    String targetDbTable = resolveTargetTable(targetDbName, firstSource[1]);
                        try (Statement stmt = targetConn.createStatement())
                        {
                            stmt.execute("DELETE FROM " + targetDbTable + " WHERE 1=1");
                            log.info("已清空目标表 {}", targetDbTable);
                        }
                    }

                    doInitSync(combineId, task, sourceConn, targetConn, sourceTables, targetDbName, sourceColumn);
                }
                else
                {
                    doIncrementalSync(combineId, task, sourceConn, targetConn, sourceTables, targetDbName, sourceColumn);
                }
            }

            // 更新状态为成功
            ErCombineSync currentTask = erCombineSyncMapper.selectErCombineSyncByCombineId(combineId);
            if (currentTask != null && "4".equals(currentTask.getSyncStatus()))
            {
                log.info("合并任务 [{}] 已被手动停止，跳过状态更新", combineId);
                stopFlags.remove(combineId);
                return;
            }

            updateTask.setSyncStatus("2");
            updateTask.setLastSyncTime(DateUtils.getNowDate());
            updateTask.setLastSyncResult("{\"message\":\"合并同步成功完成\"}");
            erCombineSyncMapper.updateErCombineSync(updateTask);
            log.info("合并任务 [{}] 执行完成，状态: 成功", combineId);

            stopFlags.remove(combineId);
        }
        catch (Exception e)
        {
            log.error("执行合并同步任务 [" + combineId + "] 失败", e);
            try {
                updateTask.setSyncStatus("3");
                updateTask.setLastSyncTime(DateUtils.getNowDate());
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                updateTask.setLastSyncResult("{\"error\":\"" + errorMsg.replace("\"", "'") + "\"}");
                erCombineSyncMapper.updateErCombineSync(updateTask);
            } catch (Exception updateEx) {
                log.error("更新任务 [" + combineId + "] 失败状态时出错", updateEx);
            }
            stopFlags.remove(combineId);
        }
        finally
        {
            runningTasks.remove(combineId);
        }
    }

    // ==================== 初始化同步 ====================

    /**
     * 初始化合并同步：遍历每个源表，分页INSERT到目标表，带上来源列
     */
    private void doInitSync(Long combineId, ErCombineSync task, Connection sourceConn, Connection targetConn,
                            List<String[]> sourceTables, String targetDbName, String sourceColumn) throws Exception
    {
        int batchSize = (task.getBatchSize() != null && task.getBatchSize() > 0) ? task.getBatchSize() : 500000;
        int total = sourceTables.size();
        int current = 0;
        boolean overallSuccess = true;

        for (String[] tableSpec : sourceTables)
        {
            if (stopFlags.getOrDefault(combineId, false))
            {
                log.info("合并任务 [{}] 收到停止信号，退出初始化同步", combineId);
                break;
            }

            current++;
            String sourceDb = tableSpec[0];
            String sourceTbl = tableSpec[1];
            String sourceFullTable = "`" + sourceDb + "`.`" + sourceTbl + "`";
            String sourceTag = sourceDb + "." + sourceTbl;  // 来源标识：库名.表名

            // 构建完整的目标表路径（使用目标库名 + 源表名）
            String targetDbTable = resolveTargetTable(targetDbName, sourceTbl);

            updateProgress(combineId, current, total, "初始化同步: " + sourceTag);

            ErCombineSyncLog logEntry = new ErCombineSyncLog();
            logEntry.setCombineId(combineId);
            logEntry.setSourceDatabase(sourceDb);
            logEntry.setSourceTable(sourceTag);
            logEntry.setTargetTable(task.getTargetTable());
            logEntry.setSyncMode("0");
            logEntry.setSyncAction("FULL");
            logEntry.setStartTime(DateUtils.getNowDate());
            logEntry.setSyncResult("0");

            try
            {
                // 获取列名（排除来源列，来源列在INSERT时用固定值）
                List<String> columns = getTableColumns(sourceConn, sourceDb, sourceTbl);
                if (columns.isEmpty())
                {
                    throw new IllegalStateException("无法获取源表列信息: " + sourceTag);
                }

                // 构建INSERT SQL: INSERT INTO target (col1,col2,...,source_column) SELECT col1,col2,...,'db.table' FROM source
                String columnList = String.join(",", columns);
                String columnListQuoted = quoteColumns(columns);
                String selectColumns = quoteSelectColumns(columns);

                String insertSql = "INSERT INTO " + targetDbTable +
                        " (" + columnListQuoted + ",`" + sourceColumn + "`) " +
                        "SELECT " + selectColumns + ", '" + escapeSql(sourceTag) + "' " +
                        "FROM " + sourceFullTable + " AS a";

                log.info("合并任务 [{}] {} INSERT SQL: {}", combineId, sourceTag, insertSql);

                // 获取总行数用于日志记录
                long totalRows = getTableRowCount(sourceConn, sourceFullTable);
                log.info("合并任务 [{}] {} 总行数: {}", combineId, sourceTag, totalRows);

                // 获取主键列用于排序（使用第一列作为排序依据）
                String firstColumn = columns.get(0);

                // 分页执行INSERT（参考 InitCombineData.copyByOffset）
                long inserted = 0;
                long offset = 0;

                while (offset < totalRows)
                {
                    if (stopFlags.getOrDefault(combineId, false))
                    {
                        log.info("合并任务 [{}] 在分页INSERT时收到停止信号", combineId);
                        break;
                    }

                    // 构建带分页的SQL：INSERT INTO target SELECT ... FROM source ORDER BY pk LIMIT X OFFSET Y
                    String pageInsertSql = insertSql + " ORDER BY `" + firstColumn + "` ASC LIMIT " + batchSize + " OFFSET " + offset;
                    log.info("合并任务 [{}] {} 第{}批 (offset={})", combineId, sourceTag, (offset / batchSize + 1), offset);

                    try (Statement stmt = targetConn.createStatement())
                    {
                        int rows = stmt.executeUpdate(pageInsertSql);
                        inserted += rows;
                        offset += batchSize;
                        log.info("合并任务 [{}] {} 第{}批插入 {} 行，累计 {} 行", combineId, sourceTag, (offset / batchSize), rows, inserted);
                    }
                    catch (Exception e)
                    {
                        log.error("合并任务 [{}] {} 第{}批执行失败", combineId, sourceTag, (offset / batchSize + 1), e);
                        throw e;
                    }
                }

                logEntry.setTotalCount(totalRows);
                logEntry.setSuccessCount(inserted);
                logEntry.setFailCount(0L);
                logEntry.setEndTime(DateUtils.getNowDate());
                logEntry.setExecuteTime(logEntry.getEndTime().getTime() - logEntry.getStartTime().getTime());

                log.info("合并任务 [{}] 初始化 {} : 总计{}行, 插入{}行", combineId, sourceTag, totalRows, inserted);
            }
            catch (Exception e)
            {
                log.error("合并任务 [{}] 初始化 {} 失败", combineId, sourceTag, e);
                logEntry.setSyncResult("1");
                logEntry.setErrorMessage(e.getMessage() != null ? e.getMessage() : e.getClass().getName());
                logEntry.setEndTime(DateUtils.getNowDate());
                logEntry.setExecuteTime(logEntry.getEndTime().getTime() - logEntry.getStartTime().getTime());
                overallSuccess = false;
            }

            erCombineSyncLogMapper.insertErCombineSyncLog(logEntry);
        }

        if (!overallSuccess)
        {
            throw new IllegalStateException("部分源表初始化同步失败，请查看日志");
        }
    }

    // ==================== 增量同步 ====================

    /**
     * 增量合并同步：对每个源表执行删除对齐→插入→更新（INSERT覆盖）
     * 所有操作均用source_column限定来源范围，避免跨来源误删或漏插
     */
    private void doIncrementalSync(Long combineId, ErCombineSync task, Connection sourceConn, Connection targetConn,
                                   List<String[]> sourceTables, String targetDbName, String sourceColumn) throws Exception
    {
        int batchSize = (task.getBatchSize() != null && task.getBatchSize() > 0) ? task.getBatchSize() : 500000;
        int total = sourceTables.size();
        int current = 0;
        boolean overallSuccess = true;
        String timestampField = task.getTimestampField();
        boolean hasTimestamp = StringUtils.isNotEmpty(timestampField);

        boolean doInsert = "1".equals(task.getSyncInsert());
        boolean doUpdate = "1".equals(task.getSyncUpdate());
        boolean doDelete = "1".equals(task.getSyncDelete());

        for (String[] tableSpec : sourceTables)
        {
            if (stopFlags.getOrDefault(combineId, false))
            {
                log.info("合并任务 [{}] 收到停止信号，退出增量同步", combineId);
                break;
            }

            current++;
            String sourceDb = tableSpec[0];
            String sourceTbl = tableSpec[1];
            String sourceFullTable = "`" + sourceDb + "`.`" + sourceTbl + "`";
            String sourceTag = sourceDb + "." + sourceTbl;

            // 构建完整的目标表路径（使用目标库名 + 源表名）
            String targetDbTable = resolveTargetTable(targetDbName, sourceTbl);

            updateProgress(combineId, current, total, "增量同步: " + sourceTag);

            ErCombineSyncLog logEntry = new ErCombineSyncLog();
            logEntry.setCombineId(combineId);
            logEntry.setSourceDatabase(sourceDb);
            logEntry.setSourceTable(sourceTag);
            logEntry.setTargetTable(task.getTargetTable());
            logEntry.setSyncMode("1");
            logEntry.setStartTime(DateUtils.getNowDate());
            logEntry.setSyncResult("0");

            try
            {
                List<String> columns = getTableColumns(sourceConn, sourceDb, sourceTbl);
                if (columns.isEmpty())
                {
                    throw new IllegalStateException("无法获取源表列信息: " + sourceTag);
                }

                List<String> pkCols = getPrimaryKeys(sourceConn, sourceDb, sourceTbl);
                if (pkCols.isEmpty())
                {
                    pkCols = new ArrayList<>();
                    pkCols.add(columns.get(0));
                }
                String pkCol = pkCols.get(0);  // 使用第一个主键列

                String columnListQuoted = quoteColumns(columns);
                String selectColumns = quoteSelectColumns(columns);

                // 获取该表的上次水位
                String lastSyncValue = getProgress(combineId, sourceTag);
                log.info("合并任务 [{}] {} 水位: {}", combineId, sourceTag, lastSyncValue);

                long deleteCount = 0, insertCount = 0, updateCount = 0;

                // Step 1: 删除对齐（目标表中该来源有但源表中不存在的记录）
                if (doDelete)
                {
                    updateProgress(combineId, current, total, "增量同步-删除对齐: " + sourceTag);
                    String deleteSql = "DELETE FROM " + targetDbTable +
                            " WHERE `" + sourceColumn + "` = '" + escapeSql(sourceTag) + "'" +
                            " AND `" + pkCol + "` NOT IN (SELECT `" + pkCol + "` FROM " + sourceFullTable + ")";

                    try (Statement stmt = targetConn.createStatement())
                    {
                        deleteCount = stmt.executeUpdate(deleteSql);
                        log.info("合并任务 [{}] {} 删除{}行", combineId, sourceTag, deleteCount);
                    }
                }

                // Step 2: 插入（源表有但目标表中该来源不存在的记录）
                // 参考 DiffData - 使用 LIMIT 分批，每批插入后那些记录已存在于目标表，b.pk IS NULL 不再匹配
                if (doInsert)
                {
                    updateProgress(combineId, current, total, "增量同步-插入: " + sourceTag);
                    int insertBatchNum = 0;
                    
                    while (true)
                    {
                        if (stopFlags.getOrDefault(combineId, false))
                        {
                            log.info("合并任务 [{}] 在插入时收到停止信号", combineId);
                            break;
                        }
                        
                        insertBatchNum++;
                        String insertSql = "INSERT INTO " + targetDbTable +
                                " (" + columnListQuoted + ",`" + sourceColumn + "`) " +
                                "SELECT " + selectColumns + ", '" + escapeSql(sourceTag) + "' " +
                                "FROM " + sourceFullTable + " AS a " +
                                "LEFT JOIN " + targetDbTable + " AS b " +
                                "ON a.`" + pkCol + "` = b.`" + pkCol + "` " +
                                "AND b.`" + sourceColumn + "` = '" + escapeSql(sourceTag) + "' " +
                                "WHERE b.`" + pkCol + "` IS NULL " +
                                "LIMIT " + batchSize;
                        
                        long rows = executeInsert(targetConn, insertSql);
                        if (rows == 0)
                        {
                            break;
                        }
                        insertCount += rows;
                        log.info("合并任务 [{}] {} 第{}批插入 {}行", combineId, sourceTag, insertBatchNum, rows);
                    }
                    
                    log.info("合并任务 [{}] {} 插入完成，共{}行", combineId, sourceTag, insertCount);
                }

                // Step 3: 更新（统一用INSERT覆盖，StarRocks主键表自动覆盖相同主键的旧数据）
                if (doUpdate && hasTimestamp)
                {
                    updateProgress(combineId, current, total, "增量同步-更新: " + sourceTag);
                    
                    // 参考 DiffData - 使用 LIMIT 分批处理，只更新 timestamp 不同的记录
                    // 每批 INSERT 后目标表记录的 timestamp 已与源表一致，下次不会再被选中
                    int batchNum = 0;
                    
                    while (true)
                    {
                        if (stopFlags.getOrDefault(combineId, false))
                        {
                            log.info("合并任务 [{}] 在更新时收到停止信号", combineId);
                            break;
                        }
                        
                        batchNum++;
                        String updateSql = "INSERT INTO " + targetDbTable +
                                " (" + columnListQuoted + ",`" + sourceColumn + "`) " +
                                "SELECT " + selectColumns + ", '" + escapeSql(sourceTag) + "' " +
                                "FROM " + sourceFullTable + " AS a " +
                                "LEFT JOIN " + targetDbTable + " AS b " +
                                "ON a.`" + pkCol + "` = b.`" + pkCol + "` " +
                                "AND b.`" + sourceColumn + "` = '" + escapeSql(sourceTag) + "' " +
                                "WHERE b.`" + pkCol + "` IS NOT NULL " +
                                "AND a.`" + timestampField + "` <> IFNULL(b.`" + timestampField + "`, NOW()) " +
                                "LIMIT " + batchSize;
                        
                        try (Statement stmt = targetConn.createStatement())
                        {
                            long rows = stmt.executeUpdate(updateSql);
                            if (rows == 0)
                            {
                                break;
                            }
                            updateCount += rows;
                            log.info("合并任务 [{}] {} 第{}批更新 {}行", combineId, sourceTag, batchNum, rows);
                        }
                    }
                    
                    log.info("合并任务 [{}] {} 更新完成，共{}行", combineId, sourceTag, updateCount);
                }

                // 更新水位
                String newSyncValue = null;
                if (hasTimestamp)
                {
                    newSyncValue = getMaxTimestamp(sourceConn, sourceFullTable, timestampField);
                }
                if (newSyncValue != null)
                {
                    ErCombineSyncProgress progress = new ErCombineSyncProgress();
                    progress.setCombineId(combineId);
                    progress.setSourceTable(sourceTag);
                    progress.setLastSyncValue(newSyncValue);
                    erCombineSyncProgressMapper.upsert(progress);
                }

                logEntry.setSyncAction("INCREMENTAL");
                logEntry.setTotalCount(deleteCount + insertCount + (doUpdate && hasTimestamp ? updateCount : 0));
                logEntry.setSuccessCount(deleteCount + insertCount + (doUpdate && hasTimestamp ? updateCount : 0));
                logEntry.setFailCount(0L);
                logEntry.setLastSyncValue(newSyncValue);
                logEntry.setEndTime(DateUtils.getNowDate());
                logEntry.setExecuteTime(logEntry.getEndTime().getTime() - logEntry.getStartTime().getTime());
            }
            catch (Exception e)
            {
                log.error("合并任务 [{}] 增量 {} 失败", combineId, sourceTag, e);
                logEntry.setSyncResult("1");
                logEntry.setErrorMessage(e.getMessage() != null ? e.getMessage() : e.getClass().getName());
                logEntry.setEndTime(DateUtils.getNowDate());
                logEntry.setExecuteTime(logEntry.getEndTime().getTime() - logEntry.getStartTime().getTime());
                overallSuccess = false;
            }

            erCombineSyncLogMapper.insertErCombineSyncLog(logEntry);
        }

        if (!overallSuccess)
        {
            throw new IllegalStateException("部分源表增量同步失败，请查看日志");
        }
    }

    // ==================== 预览 ====================

    @Override
    public Map<String, Object> previewCombineSync(Long combineId)
    {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, String>> tables = new ArrayList<>();
        Set<String> schemas = new HashSet<>();

        ErCombineSync task = erCombineSyncMapper.selectErCombineSyncByCombineId(combineId);
        if (task == null)
        {
            result.put("tables", tables);
            result.put("schemaCount", 0);
            result.put("tableCount", 0);
            return result;
        }

        ErDatasource sourceDatasource = erDatasourceMapper.selectErDatasourceByDatasourceId(task.getSourceDatasourceId());
        if (sourceDatasource == null)
        {
            result.put("tables", tables);
            result.put("schemaCount", 0);
            result.put("tableCount", 0);
            return result;
        }

        Connection conn = null;
        try
        {
            Class.forName(sourceDatasource.getDriverClass());
            conn = DriverManager.getConnection(
                sourceDatasource.getJdbcUrl(),
                sourceDatasource.getUsername(),
                sourceDatasource.getPassword()
            );

            List<String[]> sourceTables = discoverSourceTables(conn, task);
            for (String[] spec : sourceTables)
            {
                String schemaName = spec[0];
                String tableName = spec[1];
                schemas.add(schemaName);
                Map<String, String> tableInfo = new HashMap<>();
                tableInfo.put("schemaName", schemaName);
                tableInfo.put("tableName", tableName);
                tableInfo.put("sourceTag", schemaName + "." + tableName);
                tables.add(tableInfo);
            }
        }
        catch (Exception e)
        {
            log.error("预览合并同步失败", e);
        }
        finally
        {
            try { if (conn != null) conn.close(); } catch (Exception e) { log.error("关闭连接失败", e); }
        }

        result.put("tables", tables);
        result.put("schemaCount", schemas.size());
        result.put("tableCount", tables.size());
        return result;
    }

    // ==================== 日志和进度 ====================

    @Override
    public List<ErCombineSyncLog> selectErCombineSyncLogList(ErCombineSyncLog erCombineSyncLog)
    {
        return erCombineSyncLogMapper.selectErCombineSyncLogList(erCombineSyncLog);
    }

    @Override
    public List<ErCombineSyncLog> selectErCombineSyncLogByCombineId(Long combineId)
    {
        return erCombineSyncLogMapper.selectErCombineSyncLogByCombineId(combineId);
    }

    @Override
    public List<ErCombineSyncProgress> selectProgressByCombineId(Long combineId)
    {
        if (combineId == null)
        {
            return new ArrayList<>();
        }
        return erCombineSyncProgressMapper.selectByCombineId(combineId);
    }

    @Override
    public Map<String, Object> refreshProgressFromTarget(Long combineId)
    {
        Map<String, Object> result = new HashMap<>();
        if (combineId == null)
        {
            result.put("success", false);
            result.put("message", "任务ID不能为空");
            return result;
        }

        ErCombineSync task = erCombineSyncMapper.selectErCombineSyncByCombineId(combineId);
        if (task == null)
        {
            result.put("success", false);
            result.put("message", "合并任务不存在");
            return result;
        }
        if ("1".equals(task.getSyncStatus()))
        {
            result.put("success", false);
            result.put("message", "任务正在同步中，不能刷新时间戳");
            return result;
        }
        if (StringUtils.isEmpty(task.getTimestampField()))
        {
            result.put("success", false);
            result.put("message", "未配置时间戳字段，无法刷新增量起点");
            return result;
        }

        ErDatasource sourceDs = erDatasourceMapper.selectErDatasourceByDatasourceId(task.getSourceDatasourceId());
        ErDatasource targetDs = erDatasourceMapper.selectErDatasourceByDatasourceId(task.getTargetDatasourceId());
        if (sourceDs == null || targetDs == null)
        {
            result.put("success", false);
            result.put("message", "源或目标数据源未配置");
            return result;
        }

        int updated = 0, skipped = 0, failed = 0;
        String globalMaxTs = null;
        List<String> errors = new ArrayList<>();

        try
        {
            Class.forName(sourceDs.getDriverClass());
            Class.forName(targetDs.getDriverClass());

            try (Connection sourceConn = DriverManager.getConnection(sourceDs.getJdbcUrl(), sourceDs.getUsername(), sourceDs.getPassword());
                 Connection targetConn = DriverManager.getConnection(targetDs.getJdbcUrl(), targetDs.getUsername(), targetDs.getPassword()))
            {
                List<String[]> sourceTables = discoverSourceTables(sourceConn, task);
                String targetDbName = task.getTargetTable();
                if (targetDbName == null || targetDbName.trim().isEmpty())
                {
                    throw new IllegalArgumentException("目标库名不能为空");
                }
                targetDbName = targetDbName.replace("`", "").trim();
                
                String sourceColumn = StringUtils.isNotEmpty(task.getSourceColumn()) ? task.getSourceColumn() : "source_table";
                String tsField = task.getTimestampField();

                for (String[] spec : sourceTables)
                {
                    String sourceTag = spec[0] + "." + spec[1];
                    // 构建完整的目标表路径（使用目标库名 + 源表名）
                    String targetDbTable = resolveTargetTable(targetDbName, spec[1]);
                    try
                    {
                        // 从目标表查询该来源的最大时间戳
                        String sql = "SELECT MAX(`" + tsField + "`) FROM " + targetDbTable +
                                " WHERE `" + sourceColumn + "` = '" + escapeSql(sourceTag) + "'";
                        try (Statement stmt = targetConn.createStatement();
                             ResultSet rs = stmt.executeQuery(sql))
                        {
                            if (rs.next())
                            {
                                Object maxVal = rs.getObject(1);
                                String maxTsStr = toTimestampString(maxVal);
                                if (maxTsStr != null)
                                {
                                    ErCombineSyncProgress progress = new ErCombineSyncProgress();
                                    progress.setCombineId(combineId);
                                    progress.setSourceTable(sourceTag);
                                    progress.setLastSyncValue(maxTsStr);
                                    erCombineSyncProgressMapper.upsert(progress);
                                    updated++;
                                    if (globalMaxTs == null || maxTsStr.compareTo(globalMaxTs) > 0)
                                    {
                                        globalMaxTs = maxTsStr;
                                    }
                                }
                                else
                                {
                                    skipped++;
                                }
                            }
                            else
                            {
                                skipped++;
                            }
                        }
                    }
                    catch (Exception e)
                    {
                        failed++;
                        if (errors.size() < 10)
                        {
                            errors.add(sourceTag + ": " + e.getMessage());
                        }
                    }
                }
            }

            if (globalMaxTs != null)
            {
                ErCombineSync updateTask = new ErCombineSync();
                updateTask.setCombineId(combineId);
                updateTask.setLastSyncValue(globalMaxTs);
                erCombineSyncMapper.updateErCombineSync(updateTask);
            }
        }
        catch (Exception e)
        {
            log.error("刷新表级时间戳失败, combineId={}", combineId, e);
            result.put("success", false);
            result.put("message", "刷新时间戳失败: " + (e.getMessage() == null ? e.getClass().getName() : e.getMessage()));
            return result;
        }

        result.put("updated", updated);
        result.put("skipped", skipped);
        result.put("failed", failed);
        result.put("errors", errors);
        if (updated <= 0)
        {
            result.put("success", false);
            result.put("message", "未从目标表获取到任何最大时间戳");
            return result;
        }

        result.put("success", true);
        result.put("message", "已刷新 " + updated + " 张表最大时间戳，跳过 " + skipped + " 张，失败 " + failed + " 张");
        return result;
    }

    @Override
    public ErCombineSync getSyncProgress(Long combineId)
    {
        return erCombineSyncMapper.selectErCombineSyncByCombineId(combineId);
    }

    @Override
    public Map<String, Object> stopCombineSync(Long combineId)
    {
        Map<String, Object> result = new HashMap<>();

        ErCombineSync task = erCombineSyncMapper.selectErCombineSyncByCombineId(combineId);
        if (task == null)
        {
            result.put("success", false);
            result.put("message", "合并任务不存在");
            return result;
        }

        if (!"1".equals(task.getSyncStatus()))
        {
            result.put("success", false);
            result.put("message", "任务未在同步中");
            return result;
        }

        stopFlags.put(combineId, true);

        ErCombineSync updateTask = new ErCombineSync();
        updateTask.setCombineId(combineId);
        updateTask.setSyncStatus("4");
        updateTask.setLastSyncTime(DateUtils.getNowDate());
        updateTask.setLastSyncResult("{\"message\":\"任务已被手动停止\"}");
        erCombineSyncMapper.updateErCombineSync(updateTask);

        log.info("已停止合并同步任务 [{}]", combineId);

        result.put("success", true);
        result.put("message", "任务已停止");
        return result;
    }

    // ==================== 辅助方法 ====================

    /**
     * 发现匹配的分库分表
     * @return List of [schema, table]
     */
    private List<String[]> discoverSourceTables(Connection conn, ErCombineSync task) throws Exception
    {
        List<String[]> result = new ArrayList<>();
        String schemaPattern = task.getSourceSchemaPattern();
        String tablePattern = task.getSourceTable();

        List<String> schemaPatterns = new ArrayList<>();
        if (schemaPattern != null && !schemaPattern.trim().isEmpty())
        {
            for (String p : schemaPattern.split("[,;]"))
            {
                String trimmed = p.trim();
                if (!trimmed.isEmpty()) schemaPatterns.add(trimmed);
            }
        }

        List<String> tablePatterns = new ArrayList<>();
        if (tablePattern != null && !tablePattern.trim().isEmpty())
        {
            for (String p : tablePattern.split("[,;]"))
            {
                String trimmed = p.trim();
                if (!trimmed.isEmpty()) tablePatterns.add(trimmed);
            }
        }

        // 使用information_schema发现表
        StringBuilder sql = new StringBuilder("SELECT table_schema, table_name FROM information_schema.tables WHERE table_type='BASE TABLE'");
        List<String> params = new ArrayList<>();

        if (!schemaPatterns.isEmpty())
        {
            sql.append(" AND (");
            for (int i = 0; i < schemaPatterns.size(); i++)
            {
                if (i > 0) sql.append(" OR ");
                sql.append("table_schema LIKE ?");
                params.add(schemaPatterns.get(i));
            }
            sql.append(")");
        }
        if (!tablePatterns.isEmpty())
        {
            sql.append(" AND (");
            for (int i = 0; i < tablePatterns.size(); i++)
            {
                if (i > 0) sql.append(" OR ");
                sql.append("table_name LIKE ?");
                params.add(tablePatterns.get(i));
            }
            sql.append(")");
        }
        sql.append(" ORDER BY table_schema, table_name");

        log.info("discoverSourceTables SQL: {}", sql);
        try (PreparedStatement ps = conn.prepareStatement(sql.toString()))
        {
            for (int i = 0; i < params.size(); i++)
            {
                ps.setString(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery())
            {
                while (rs.next())
                {
                    String schemaName = rs.getString("table_schema");
                    String tableName = rs.getString("table_name");
                    // 过滤系统库
                    if (schemaName.equalsIgnoreCase("information_schema")
                        || schemaName.equalsIgnoreCase("mysql")
                        || schemaName.equalsIgnoreCase("performance_schema")
                        || schemaName.equalsIgnoreCase("sys"))
                    {
                        continue;
                    }
                    result.add(new String[]{schemaName, tableName});
                    log.info("发现源表: {}.{}", schemaName, tableName);
                }
            }
        }

        log.info("总共发现 {} 个源表", result.size());
        return result;
    }

    /**
     * 确保目标表存在，不存在则从源表DDL创建（追加来源列）
     * 注意：会根据 sourceTables 中所有不同的表名分别创建对应的目标表
     */
    private void ensureTargetTableExists(Connection sourceConn, Connection targetConn,
                                         List<String[]> sourceTables, String targetDbName,
                                         String sourceColumn, ErCombineSync task) throws Exception
    {
        if (sourceTables.isEmpty())
        {
            throw new IllegalArgumentException("源表列表不能为空");
        }
        
        // 自动创建目标库（如果不存在）
        try (Statement stmt = targetConn.createStatement())
        {
            String createDbSql = "CREATE DATABASE IF NOT EXISTS `" + targetDbName.replace("`", "").trim() + "`";
            log.info("确保目标库存在: {}", createDbSql);
            stmt.execute(createDbSql);
        }
        catch (Exception e)
        {
            log.warn("创建目标库时出现异常（可能已存在）: {}", e.getMessage());
        }
        
        // 收集所有唯一的表名（去重）
        Set<String> uniqueTableNames = new HashSet<>();
        for (String[] tableSpec : sourceTables)
        {
            uniqueTableNames.add(tableSpec[1]);
        }
        
        log.info("需要为目标表名创建/检查: {}", uniqueTableNames);
        
        // 为每个唯一的表名创建对应的目标表
        for (String tableName : uniqueTableNames)
        {
            // 构建完整的目标表路径（使用目标库名 + 表名）
            String targetDbTable = resolveTargetTable(targetDbName, tableName);
            
            // 检查目标表是否已存在
            String checkSql = "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?";
            String targetSchema = null;
            String targetTbl = null;
            if (targetDbTable.contains("."))
            {
                int dot = targetDbTable.indexOf('.');
                targetSchema = targetDbTable.substring(0, dot).replace("`", "");
                targetTbl = targetDbTable.substring(dot + 1).replace("`", "");
            }
            else
            {
                targetTbl = targetDbTable.replace("`", "");
            }

            boolean tableExists = false;
            try (PreparedStatement ps = targetConn.prepareStatement(checkSql))
            {
                // 如果targetSchema为null，尝试使用目标数据库默认库
                if (targetSchema == null)
                {
                    // 通过连接元数据获取当前数据库
                    targetSchema = targetConn.getCatalog();
                }
                ps.setString(1, targetSchema);
                ps.setString(2, targetTbl);
                try (ResultSet rs = ps.executeQuery())
                {
                    if (rs.next() && rs.getInt(1) > 0)
                    {
                        tableExists = true;
                    }
                }
            }

            if (!tableExists)
            {
                log.info("目标表 {} 不存在，从源表DDL创建", targetDbTable);
                // 从第一个匹配该表名的源表获取DDL
                String[] matchingSource = findMatchingSource(sourceTables, tableName);
                if (matchingSource == null)
                {
                    log.warn("找不到源表 {} 的DDL，跳过创建目标表 {}", tableName, targetDbTable);
                    continue;
                }
                
                String sourceFullTable = "`" + matchingSource[0] + "`.`" + matchingSource[1] + "`";

                String createDdl = null;
                try (Statement stmt = sourceConn.createStatement())
                {
                    try (ResultSet rs = stmt.executeQuery("SHOW CREATE TABLE " + sourceFullTable))
                    {
                        if (rs.next())
                        {
                            createDdl = rs.getString(2);
                        }
                    }
                }

                if (createDdl == null)
                {
                    log.warn("无法获取源表DDL: {}, 跳过创建目标表 {}", sourceFullTable, targetDbTable);
                    continue;
                }

                // 修改DDL中的表名为目标表名，并追加来源列
                // 在列定义块的结束括号前添加来源列（用括号深度匹配，避免误匹配到PROPERTIES等块）
                int firstParen = createDdl.indexOf('(');
                int closeParen = -1;
                if (firstParen >= 0)
                {
                    int depth = 0;
                    boolean inString = false;
                    char stringQuote = 0;
                    for (int i = firstParen; i < createDdl.length(); i++)
                    {
                        char c = createDdl.charAt(i);
                        if (inString)
                        {
                            if (c == stringQuote) inString = false;
                            continue;
                        }
                        if (c == '\'' || c == '"' || c == '`')
                        {
                            inString = true;
                            stringQuote = c;
                            continue;
                        }
                        if (c == '(') depth++;
                        else if (c == ')')
                        {
                            depth--;
                            if (depth == 0)
                            {
                                closeParen = i;
                                break;
                            }
                        }
                    }
                }
                if (closeParen > 0)
                {
                    String beforeParen = createDdl.substring(0, closeParen);
                    String afterParen = createDdl.substring(closeParen);
                    // 在列定义块内追加来源列
                    createDdl = beforeParen + ",\n  `" + sourceColumn + "` varchar(256) DEFAULT '' COMMENT '来源标识(库名.表名)'" + afterParen;
                }

                // 替换表名为目标表名
                createDdl = createDdl.replaceAll("CREATE TABLE.*?`" + matchingSource[1] + "`", "CREATE TABLE " + targetDbTable);
                // 如果DDL中没有反引号包裹表名，直接替换
                if (!createDdl.contains("CREATE TABLE " + targetDbTable))
                {
                    createDdl = createDdl.replaceFirst("CREATE TABLE `?\\w+`?", "CREATE TABLE " + targetDbTable);
                }

                log.info("创建目标表DDL: {}", createDdl);
                try (Statement stmt = targetConn.createStatement())
                {
                    stmt.execute(createDdl);
                }
                log.info("目标表 {} 创建成功", targetDbTable);
            }
            else
            {
                log.info("目标表 {} 已存在", targetDbTable);
            }
        }
    }
    
    /**
     * 查找第一个匹配指定表名的源表
     */
    private String[] findMatchingSource(List<String[]> sourceTables, String tableName)
    {
        for (String[] tableSpec : sourceTables)
        {
            if (tableName.equals(tableSpec[1]))
            {
                return tableSpec;
            }
        }
        return null;
    }

    /**
     * 获取表的列名列表
     */
    private List<String> getTableColumns(Connection conn, String schema, String table) throws Exception
    {
        List<String> columns = new ArrayList<>();
        String sql = "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
        try (PreparedStatement ps = conn.prepareStatement(sql))
        {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery())
            {
                while (rs.next())
                {
                    columns.add(rs.getString(1));
                }
            }
        }
        return columns;
    }

    /**
     * 获取表的主键列
     */
    private List<String> getPrimaryKeys(Connection conn, String schema, String table) throws Exception
    {
        List<String> pks = new ArrayList<>();
        DatabaseMetaData metaData = conn.getMetaData();
        try (ResultSet rs = metaData.getPrimaryKeys(schema, null, table))
        {
            while (rs.next())
            {
                pks.add(rs.getString("COLUMN_NAME"));
            }
        }
        return pks;
    }

    /**
     * 获取表行数
     */
    private long getTableRowCount(Connection conn, String fullTableName) throws Exception
    {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + fullTableName))
        {
            if (rs.next())
            {
                return rs.getLong(1);
            }
        }
        return 0;
    }

    /**
     * 获取表的最大时间戳
     */
    private String getMaxTimestamp(Connection conn, String fullTableName, String tsField) throws Exception
    {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT MAX(`" + tsField + "`) FROM " + fullTableName))
        {
            if (rs.next())
            {
                return toTimestampString(rs.getObject(1));
            }
        }
        return null;
    }

    /**
     * 获取某表的增量进度水位
     */
    private String getProgress(Long combineId, String sourceTag)
    {
        List<ErCombineSyncProgress> list = erCombineSyncProgressMapper.selectByCombineId(combineId);
        for (ErCombineSyncProgress p : list)
        {
            if (sourceTag.equals(p.getSourceTable()))
            {
                return p.getLastSyncValue();
            }
        }
        return null;
    }

    /**
     * 解析目标表全名（带反引号）
     * @param targetDbName 目标库名（如 tidb_combine）
     * @param sourceTableName 源表名（如 tb_charge_fee），用于拼接完整的目标表路径
     */
    private String resolveTargetTable(String targetDbName, String sourceTableName)
    {
        if (targetDbName == null || targetDbName.trim().isEmpty())
        {
            throw new IllegalArgumentException("目标库名不能为空");
        }
        if (sourceTableName == null || sourceTableName.trim().isEmpty())
        {
            throw new IllegalArgumentException("源表名不能为空");
        }
        
        // 提取纯库名和表名（去除反引号）
        String db = targetDbName.replace("`", "").trim();
        String tbl = sourceTableName.replace("`", "").trim();
        
        // 如果用户输入了库名.表名格式，只取表名部分
        if (tbl.contains("."))
        {
            int dot = tbl.lastIndexOf('.');
            tbl = tbl.substring(dot + 1);
        }
        
        return "`" + db + "`.`" + tbl + "`";
    }

    /**
     * 执行批量插入（StarRocks 支持大事务，直接执行不分页）
     * 参考 DiffData.copyByOffset - StarRocks INSERT INTO SELECT 本身支持大数据量
     */
    private long executeInsert(Connection conn, String baseSql) throws Exception
    {
        log.info("执行批量插入 SQL: {}", baseSql);
        try (Statement stmt = conn.createStatement())
        {
            long rows = stmt.executeUpdate(baseSql);
            log.info("批量插入完成，共 {} 行", rows);
            return rows;
        }
    }

    /**
     * 更新同步进度
     */
    private void updateProgress(Long combineId, int current, int total, String message)
    {
        try
        {
            int percent = total > 0 ? (int) ((current * 100.0) / total) : 0;
            String progressJson = "{\"current\":" + current + ",\"total\":" + total + ",\"percent\":" + percent + ",\"message\":\"" + message.replace("\"", "'") + "\"}";
            ErCombineSync updateTask = new ErCombineSync();
            updateTask.setCombineId(combineId);
            updateTask.setSyncProgress(progressJson);
            erCombineSyncMapper.updateErCombineSync(updateTask);
        }
        catch (Exception e)
        {
            log.warn("更新进度失败", e);
        }
    }

    /**
     * 将时间戳对象转换为字符串
     */
    private String toTimestampString(Object value)
    {
        if (value == null) return null;
        if (value instanceof Timestamp)
        {
            return String.valueOf(((Timestamp) value).getTime());
        }
        if (value instanceof java.sql.Date)
        {
            return String.valueOf(((java.sql.Date) value).getTime());
        }
        if (value instanceof java.util.Date)
        {
            return String.valueOf(((java.util.Date) value).getTime());
        }
        if (value instanceof java.time.LocalDateTime)
        {
            return String.valueOf(Timestamp.valueOf((java.time.LocalDateTime) value).getTime());
        }
        if (value instanceof java.time.OffsetDateTime)
        {
            return String.valueOf(Timestamp.from(((java.time.OffsetDateTime) value).toInstant()).getTime());
        }
        return value.toString();
    }

    /**
     * 给列名加反引号
     */
    private String quoteColumns(List<String> columns)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++)
        {
            if (i > 0) sb.append(",");
            sb.append("`").append(columns.get(i)).append("`");
        }
        return sb.toString();
    }

    /**
     * 给SELECT的列名加a.前缀和反引号
     */
    private String quoteSelectColumns(List<String> columns)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++)
        {
            if (i > 0) sb.append(",");
            sb.append("a.`").append(columns.get(i)).append("`");
        }
        return sb.toString();
    }

    /**
     * 简单SQL转义（防止单引号注入）
     */
    private String escapeSql(String value)
    {
        if (value == null) return "";
        return value.replace("'", "''");
    }
}
