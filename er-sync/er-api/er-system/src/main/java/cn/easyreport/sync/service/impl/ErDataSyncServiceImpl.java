package cn.easyreport.sync.service.impl;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.alibaba.fastjson2.JSON;
import cn.easyreport.common.utils.DateUtils;
import cn.easyreport.common.utils.StringUtils;
import cn.easyreport.sync.domain.ErDataSync;
import cn.easyreport.sync.domain.ErDataSyncLog;
import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.mapper.ErDataSyncMapper;
import cn.easyreport.sync.mapper.ErDataSyncLogMapper;
import cn.easyreport.sync.mapper.ErDatasourceMapper;
import cn.easyreport.sync.service.IErDataSyncService;
import cn.easyreport.sync.core.SchemaSyncExecutor;
import cn.easyreport.sync.model.SyncResult;
import cn.easyreport.quartz.service.ISysJobService;
import cn.easyreport.quartz.domain.SysJob;

/**
 * 数据同步任务Service业务层处理
 *
 * @author easyreport
 * @date 2026-01-18
 */
@Service
public class ErDataSyncServiceImpl implements IErDataSyncService
{
    private static final Logger log = LoggerFactory.getLogger(ErDataSyncServiceImpl.class);

    // 存储正在执行的任务的停止标志：syncId -> stopFlag
    private static final ConcurrentHashMap<Long, Boolean> stopFlags = new ConcurrentHashMap<>();

    @Autowired
    private ErDataSyncMapper erDataSyncMapper;

    @Autowired
    private ErDataSyncLogMapper erDataSyncLogMapper;

    @Autowired
    private ErDatasourceMapper erDatasourceMapper;

    @Autowired(required = false)
    private ISysJobService jobService;

    private final SchemaSyncExecutor schemaSyncExecutor = new SchemaSyncExecutor();

    private final ExecutorService executorService = Executors.newFixedThreadPool(5);

    /**
     * 查询数据同步任务
     *
     * @param syncId 数据同步任务主键
     * @return 数据同步任务
     */
    @Override
    public ErDataSync selectErDataSyncBySyncId(Long syncId)
    {
        return erDataSyncMapper.selectErDataSyncBySyncId(syncId);
    }

    /**
     * 查询数据同步任务列表
     *
     * @param erDataSync 数据同步任务
     * @return 数据同步任务
     */
    @Override
    public List<ErDataSync> selectErDataSyncList(ErDataSync erDataSync)
    {
        return erDataSyncMapper.selectErDataSyncList(erDataSync);
    }

    /**
     * 新增数据同步任务
     *
     * @param erDataSync 数据同步任务
     * @return 结果
     */
    @Override
    @Transactional
    public int insertErDataSync(ErDataSync erDataSync)
    {
        log.info("[Quartz同步] 新增架构同步任务，前端传来的status={}", erDataSync.getStatus());

        // 预处理：将空字符串转换为null，避免查询时出现问题
        if (erDataSync.getSourceSchemaPattern() != null && erDataSync.getSourceSchemaPattern().trim().isEmpty())
        {
            log.info("insertErDataSync: sourceSchemaPattern is empty string, converting to null");
            erDataSync.setSourceSchemaPattern(null);
        }

        erDataSync.setCreateTime(DateUtils.getNowDate());
        erDataSync.setSyncStatus("0"); // 待同步
        // 如果前端没有传status，默认设置为"0"（启用）
        if (StringUtils.isEmpty(erDataSync.getStatus()))
        {
            erDataSync.setStatus("0");
            log.info("[Quartz同步] status为空，设置默认值为0");
        }

        log.info("[Quartz同步] 准备插入数据库，最终status={}", erDataSync.getStatus());
        int rows = erDataSyncMapper.insertErDataSync(erDataSync);

        // 如果配置了cron表达式，创建Quartz定时任务
        if (rows > 0 && StringUtils.isNotEmpty(erDataSync.getCronExpression()) && jobService != null)
        {
            try
            {
                log.info("[Quartz同步] 准备创建Quartz任务，业务任务status={}", erDataSync.getStatus());
                SysJob job = createQuartzJob(erDataSync);
                log.info("[Quartz同步] 创建的Quartz任务对象，status={}", job.getStatus());

                jobService.insertJob(job);
                log.info("[Quartz同步] insertJob完成，jobId={}", job.getJobId());

                // 如果业务任务是启用状态，需要启动Quartz任务（insertJob默认为暂停状态）
                if ("0".equals(erDataSync.getStatus()))
                {
                    log.info("[Quartz同步] 业务任务是启用状态，调用changeStatus启动Quartz任务");
                    job.setStatus("0");  // 重新设置为启用状态，因为insertJob已将其改为"1"
                    jobService.changeStatus(job);
                }

                // 保存job_id到任务表
                ErDataSync updateSync = new ErDataSync();
                updateSync.setSyncId(erDataSync.getSyncId());
                updateSync.setJobId(job.getJobId());
                erDataSyncMapper.updateErDataSync(updateSync);

                // 查询并记录创建后的Quartz任务实际状态
                SysJob createdJob = jobService.selectJobById(job.getJobId());
                log.info("架构同步任务[{}]创建Quartz定时任务成功，jobId={}，Quartz任务实际status={}",
                    erDataSync.getSyncId(), job.getJobId(), createdJob != null ? createdJob.getStatus() : "null");
            }
            catch (Exception e)
            {
                log.error("创建Quartz定时任务失败: {}", e.getMessage(), e);
                // 不影响主任务创建，只记录错误
            }
        }

        return rows;
    }

    /**
     * 修改数据同步任务
     *
     * @param erDataSync 数据同步任务
     * @return 结果
     */
    @Override
    @Transactional
    public int updateErDataSync(ErDataSync erDataSync)
    {
        // 预处理：将空字符串转换为null，避免查询时出现问题
        if (erDataSync.getSourceSchemaPattern() != null && erDataSync.getSourceSchemaPattern().trim().isEmpty())
        {
            log.info("updateErDataSync: sourceSchemaPattern is empty string, converting to null");
            erDataSync.setSourceSchemaPattern(null);
        }

        erDataSync.setUpdateTime(DateUtils.getNowDate());
        ErDataSync oldTask = erDataSyncMapper.selectErDataSyncBySyncId(erDataSync.getSyncId());

        int rows = erDataSyncMapper.updateErDataSync(erDataSync);

        // 处理Quartz任务变更
        if (rows > 0 && jobService != null)
        {
            try
            {
                if (StringUtils.isNotEmpty(erDataSync.getCronExpression()))
                {
                    if (oldTask.getJobId() != null)
                    {
                        // 已有定时任务，更新
                        SysJob job = jobService.selectJobById(oldTask.getJobId());
                        if (job != null)
                        {
                            updateQuartzJob(job, erDataSync);
                            jobService.updateJob(job);
                            log.info("架构同步任务[{}]更新Quartz定时任务成功，jobId={}", erDataSync.getSyncId(), job.getJobId());
                        }
                        else
                        {
                            // job_id存在但Quartz中找不到，重新创建
                            SysJob newJob = createQuartzJob(erDataSync);
                            jobService.insertJob(newJob);

                            // 如果业务任务是启用状态，需要启动Quartz任务
                            if ("0".equals(erDataSync.getStatus()))
                            {
                                newJob.setStatus("0");  // 重新设置为启用状态
                                jobService.changeStatus(newJob);
                            }

                            ErDataSync updateSync = new ErDataSync();
                            updateSync.setSyncId(erDataSync.getSyncId());
                            updateSync.setJobId(newJob.getJobId());
                            erDataSyncMapper.updateErDataSync(updateSync);
                            log.info("架构同步任务[{}]重新创建Quartz定时任务成功，jobId={}", erDataSync.getSyncId(), newJob.getJobId());
                        }
                    }
                    else
                    {
                        // 没有定时任务，新建
                        SysJob job = createQuartzJob(erDataSync);
                        jobService.insertJob(job);

                        // 如果业务任务是启用状态，需要启动Quartz任务
                        if ("0".equals(erDataSync.getStatus()))
                        {
                            job.setStatus("0");  // 重新设置为启用状态
                            jobService.changeStatus(job);
                        }

                        ErDataSync updateSync = new ErDataSync();
                        updateSync.setSyncId(erDataSync.getSyncId());
                        updateSync.setJobId(job.getJobId());
                        erDataSyncMapper.updateErDataSync(updateSync);
                        log.info("架构同步任务[{}]创建Quartz定时任务成功，jobId={}", erDataSync.getSyncId(), job.getJobId());
                    }
                }
                else
                {
                    // cron表达式为空，删除定时任务
                    if (oldTask.getJobId() != null)
                    {
                        SysJob job = new SysJob();
                        job.setJobId(oldTask.getJobId());
                        jobService.deleteJob(job);
                        ErDataSync updateSync = new ErDataSync();
                        updateSync.setSyncId(erDataSync.getSyncId());
                        updateSync.setJobId(null);
                        erDataSyncMapper.updateErDataSync(updateSync);
                        log.info("架构同步任务[{}]删除Quartz定时任务成功，jobId={}", erDataSync.getSyncId(), oldTask.getJobId());
                    }
                }
            }
            catch (Exception e)
            {
                log.error("同步Quartz定时任务失败: {}", e.getMessage(), e);
                // 不影响主任务更新，只记录错误
            }
        }

        return rows;
    }

    /**
     * 批量删除数据同步任务
     *
     * @param syncIds 需要删除的数据同步任务主键
     * @return 结果
     */
    @Override
    @Transactional
    public int deleteErDataSyncBySyncIds(Long[] syncIds)
    {
        // 删除关联的日志和Quartz任务
        for (Long syncId : syncIds)
        {
            ErDataSync task = erDataSyncMapper.selectErDataSyncBySyncId(syncId);

            // 删除关联的Quartz任务
            if (task != null && task.getJobId() != null && jobService != null)
            {
                try
                {
                    SysJob job = new SysJob();
                    job.setJobId(task.getJobId());
                    jobService.deleteJob(job);
                    log.info("架构同步任务[{}]删除Quartz定时任务成功，jobId={}", syncId, task.getJobId());
                }
                catch (Exception e)
                {
                    log.error("删除Quartz定时任务失败: {}", e.getMessage(), e);
                    // 不影响主任务删除，只记录错误
                }
            }

            erDataSyncLogMapper.deleteErDataSyncLogBySyncId(syncId);
        }
        return erDataSyncMapper.deleteErDataSyncBySyncIds(syncIds);
    }

    /**
     * 删除数据同步任务信息
     *
     * @param syncId 数据同步任务主键
     * @return 结果
     */
    @Override
    @Transactional
    public int deleteErDataSyncBySyncId(Long syncId)
    {
        ErDataSync task = erDataSyncMapper.selectErDataSyncBySyncId(syncId);

        // 删除关联的Quartz任务
        if (task != null && task.getJobId() != null && jobService != null)
        {
            try
            {
                SysJob job = new SysJob();
                job.setJobId(task.getJobId());
                jobService.deleteJob(job);
                log.info("架构同步任务[{}]删除Quartz定时任务成功，jobId={}", syncId, task.getJobId());
            }
            catch (Exception e)
            {
                log.error("删除Quartz定时任务失败: {}", e.getMessage(), e);
                // 不影响主任务删除，只记录错误
            }
        }

        // 删除关联的日志
        erDataSyncLogMapper.deleteErDataSyncLogBySyncId(syncId);
        return erDataSyncMapper.deleteErDataSyncBySyncId(syncId);
    }

    /**
     * 校验任务名称是否唯一
     *
     * @param erDataSync 数据同步任务信息
     * @return 结果
     */
    @Override
    public boolean checkSyncNameUnique(ErDataSync erDataSync)
    {
        Long syncId = StringUtils.isNull(erDataSync.getSyncId()) ? -1L : erDataSync.getSyncId();
        ErDataSync info = erDataSyncMapper.checkSyncNameUnique(erDataSync.getSyncName());
        if (StringUtils.isNotNull(info) && info.getSyncId().longValue() != syncId.longValue())
        {
            return false;
        }
        return true;
    }

    /**
     * 执行同步任务（预留接口，待后续实现）
     *
     * @param syncId 同步任务ID
     * @return 同步结果
     */
    @Override
    @Transactional
    public Map<String, Object> executeSync(Long syncId)
    {
        Map<String, Object> result = new HashMap<>();

        ErDataSync syncTask = erDataSyncMapper.selectErDataSyncBySyncId(syncId);
        if (syncTask == null)
        {
            result.put("success", false);
            result.put("message", "同步任务不存在");
            return result;
        }

        // 更新状态为同步中
        ErDataSync updateSync = new ErDataSync();
        updateSync.setSyncId(syncId);
        updateSync.setSyncStatus("1"); // 同步中
        erDataSyncMapper.updateErDataSync(updateSync);

        try
        {
            ErDatasource source = erDatasourceMapper.selectErDatasourceByDatasourceId(syncTask.getSourceDatasourceId());
            ErDatasource target = erDatasourceMapper.selectErDatasourceByDatasourceId(syncTask.getTargetDatasourceId());

            SyncResult syncResult = schemaSyncExecutor.execute(syncTask, source, target);

            if (!syncResult.getLogs().isEmpty()) {
                erDataSyncLogMapper.batchInsertErDataSyncLog(syncResult.getLogs());
            }

            updateSync.setSyncStatus(syncResult.isSuccess() ? "2" : "3");
            updateSync.setLastSyncTime(DateUtils.getNowDate());
            updateSync.setLastSyncResult("{\"message\":\"" + syncResult.getMessage() + "\"}");
            erDataSyncMapper.updateErDataSync(updateSync);

            result.put("success", syncResult.isSuccess());
            result.put("message", syncResult.getMessage());
        }
        catch (Exception e)
        {
            log.error("执行同步任务失败", e);

            // 更新状态为同步失败
            updateSync.setSyncStatus("3"); // 同步失败
            updateSync.setLastSyncTime(DateUtils.getNowDate());
            updateSync.setLastSyncResult("{\"error\":\"" + e.getMessage() + "\"}");
            erDataSyncMapper.updateErDataSync(updateSync);

            result.put("success", false);
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * 预览同步（查询匹配的表列表）
     *
     * @param syncId 同步任务ID
     * @return 包含表列表和统计信息的Map
     */
    @Override
    public Map<String, Object> previewSync(Long syncId)
    {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, String>> tables = new ArrayList<>();
        Set<String> schemas = new HashSet<>();

        ErDataSync syncTask = erDataSyncMapper.selectErDataSyncBySyncId(syncId);
        if (syncTask == null)
        {
            result.put("tables", tables);
            result.put("schemaCount", 0);
            result.put("tableCount", 0);
            return result;
        }

        ErDatasource sourceDatasource = erDatasourceMapper.selectErDatasourceByDatasourceId(syncTask.getSourceDatasourceId());
        if (sourceDatasource == null)
        {
            result.put("tables", tables);
            result.put("schemaCount", 0);
            result.put("tableCount", 0);
            return result;
        }

        Connection conn = null;
        ResultSet rs = null;
        // 预先定义，便于 finally 中兜底查询复用
        String tablePattern = syncTask.getSourceTablePattern();
        String schemaPattern = syncTask.getSourceSchemaPattern();
        List<String> schemaPatterns = new ArrayList<>();
        List<String> tablePatterns = new ArrayList<>();

        // 调试日志：记录从数据库读取的原始值
        log.warn("=== previewSync DEBUG ===");
        log.warn("syncId={}", syncId);
        log.warn("sourceSchemaPattern原始值=[{}]", syncTask.getSourceSchemaPattern());
        log.warn("sourceSchemaPattern长度={}", syncTask.getSourceSchemaPattern() == null ? "null" : syncTask.getSourceSchemaPattern().length());
        log.warn("sourceSchemaPattern是否为空字符串={}", syncTask.getSourceSchemaPattern() != null && syncTask.getSourceSchemaPattern().isEmpty());
        log.warn("sourceTablePattern=[{}]", syncTask.getSourceTablePattern());
        log.warn("========================");

        try
        {
            // 连接源数据库
            Class.forName(sourceDatasource.getDriverClass());
            conn = DriverManager.getConnection(
                sourceDatasource.getJdbcUrl(),
                sourceDatasource.getUsername(),
                sourceDatasource.getPassword()
            );

            DatabaseMetaData metaData = conn.getMetaData();

            // 处理空字符串：将空字符串转为null，表示查询所有库
            if (schemaPattern != null && schemaPattern.trim().isEmpty())
            {
                schemaPattern = null;
            }

            // 将逗号分隔的多个模式拆分为列表
            if (schemaPattern != null)
            {
                for (String p : schemaPattern.split(","))
                {
                    String trimmed = p.trim();
                    if (!trimmed.isEmpty()) schemaPatterns.add(trimmed);
                }
            }
            if (tablePattern != null)
            {
                for (String p : tablePattern.split(","))
                {
                    String trimmed = p.trim();
                    if (!trimmed.isEmpty()) tablePatterns.add(trimmed);
                }
            }

            String dbType = sourceDatasource.getDatasourceType();
            boolean isCatalogDb = dbType != null && (dbType.toUpperCase().contains("MYSQL")
                || dbType.toUpperCase().contains("TIDB")
                || dbType.toUpperCase().contains("STARROCKS")
                || dbType.toUpperCase().contains("SQLSERVER"));

            // 对每个 schema+table 组合调用 getTables
            List<String> schemaList = schemaPatterns.isEmpty() ? java.util.Collections.singletonList(null) : schemaPatterns;
            List<String> tableList = tablePatterns.isEmpty() ? java.util.Collections.singletonList(null) : tablePatterns;
            for (String sp : schemaList)
            {
                String cat = (sp != null && isCatalogDb) ? sp : (isCatalogDb ? null : null);
                String sch = (sp != null && !isCatalogDb) ? sp : null;
                if (isCatalogDb) { cat = sp; sch = null; } else { cat = null; sch = sp; }
                for (String tp : tableList)
                {
                    try (ResultSet rsTmp = metaData.getTables(cat, sch, tp, new String[]{"TABLE"}))
                    {
                        while (rsTmp.next())
                        {
                            String schemaName = rsTmp.getString("TABLE_SCHEM");
                            String tableName = rsTmp.getString("TABLE_NAME");
                            if (schemaName == null || schemaName.isEmpty()) schemaName = rsTmp.getString("TABLE_CAT");
                            if (schemaName == null || schemaName.isEmpty()) schemaName = sp != null ? sp : "默认库";
                            schemas.add(schemaName);
                            Map<String, String> tableInfo = new HashMap<>();
                            tableInfo.put("schemaName", schemaName);
                            tableInfo.put("tableName", tableName);
                            tables.add(tableInfo);
                        }
                    }
                }
            }

            // 如果 JDBC getTables 未取到数据，使用 information_schema 兜底
            if (tables.isEmpty())
            {
                StringBuilder sql = new StringBuilder("select table_schema, table_name from information_schema.tables where table_type='BASE TABLE'");
                List<String> params = new ArrayList<>();
                if (!schemaPatterns.isEmpty())
                {
                    sql.append(" and (");
                    for (int i = 0; i < schemaPatterns.size(); i++)
                    {
                        if (i > 0) sql.append(" or ");
                        sql.append("table_schema like ?");
                        params.add(schemaPatterns.get(i));
                    }
                    sql.append(")");
                }
                if (!tablePatterns.isEmpty())
                {
                    sql.append(" and (");
                    for (int i = 0; i < tablePatterns.size(); i++)
                    {
                        if (i > 0) sql.append(" or ");
                        sql.append("table_name like ?");
                        params.add(tablePatterns.get(i));
                    }
                    sql.append(")");
                }
                sql.append(" order by table_schema, table_name");
                log.info("previewSync fallback SQL: {}", sql);

                try (PreparedStatement ps = conn.prepareStatement(sql.toString()))
                {
                    for (int i = 0; i < params.size(); i++) ps.setString(i + 1, params.get(i));
                    try (ResultSet rs2 = ps.executeQuery())
                    {
                        while (rs2.next())
                        {
                            String schemaName = rs2.getString("table_schema");
                            String tableName = rs2.getString("table_name");
                            schemas.add(schemaName);
                            Map<String, String> tableInfo = new HashMap<>();
                            tableInfo.put("schemaName", schemaName);
                            tableInfo.put("tableName", tableName);
                            tables.add(tableInfo);
                        }
                    }
                }
            }

        }
        catch (Exception e)
        {
            log.error("预览同步失败", e);
        }
        finally
        {
            // 如果前面的 getTables 没取到结果（可能因为 catalog/schema 参数不被驱动支持或抛异常），用 information_schema 再查一遍，并将 SQL 打到日志
            if (tables.isEmpty() && conn != null)
            {
                try
                {
                    StringBuilder sql = new StringBuilder("select table_schema, table_name from information_schema.tables where table_type='BASE TABLE'");
                    List<String> params = new ArrayList<>();
                    if (!schemaPatterns.isEmpty())
                    {
                        sql.append(" and (");
                        for (int i = 0; i < schemaPatterns.size(); i++)
                        {
                            if (i > 0) sql.append(" or ");
                            sql.append("table_schema like ?");
                            params.add(schemaPatterns.get(i));
                        }
                        sql.append(")");
                    }
                    if (!tablePatterns.isEmpty())
                    {
                        sql.append(" and (");
                        for (int i = 0; i < tablePatterns.size(); i++)
                        {
                            if (i > 0) sql.append(" or ");
                            sql.append("table_name like ?");
                            params.add(tablePatterns.get(i));
                        }
                        sql.append(")");
                    }
                    sql.append(" order by table_schema, table_name");

                    log.info("previewSync fallback SQL: {}", sql);

                    try (PreparedStatement ps = conn.prepareStatement(sql.toString()))
                    {
                        for (int i = 0; i < params.size(); i++)
                        {
                            ps.setString(i + 1, params.get(i));
                        }

                        try (ResultSet rs2 = ps.executeQuery())
                        {
                            while (rs2.next())
                            {
                                String schemaName = rs2.getString("table_schema");
                                String tableName = rs2.getString("table_name");

                                schemas.add(schemaName);

                                Map<String, String> tableInfo = new HashMap<>();
                                tableInfo.put("schemaName", schemaName);
                                tableInfo.put("tableName", tableName);
                                tables.add(tableInfo);
                            }
                        }
                    }
                }
                catch (Exception ex)
                {
                    log.error("previewSync fallback query failed", ex);
                }
            }

            try
            {
                if (rs != null) rs.close();
                if (conn != null) conn.close();
            }
            catch (Exception e)
            {
                log.error("关闭数据库连接失败", e);
            }
        }

        result.put("tables", tables);
        result.put("schemaCount", schemas.size());
        result.put("tableCount", tables.size());
        return result;
    }

    /**
     * 查询同步日志列表
     *
     * @param erDataSyncLog 同步日志
     * @return 同步日志集合
     */
    @Override
    public List<ErDataSyncLog> selectErDataSyncLogList(ErDataSyncLog erDataSyncLog)
    {
        return erDataSyncLogMapper.selectErDataSyncLogList(erDataSyncLog);
    }

    /**
     * 根据同步任务ID查询日志
     *
     * @param syncId 同步任步ID
     * @return 同步日志集合
     */
    @Override
    public List<ErDataSyncLog> selectErDataSyncLogBySyncId(Long syncId)
    {
        return erDataSyncLogMapper.selectErDataSyncLogBySyncId(syncId);
    }

    /**
     * 异步执行同步任务
     *
     * @param syncId 同步任务ID
     * @return 提交结果
     */
    @Override
    public Map<String, Object> executeSyncAsync(Long syncId)
    {
        Map<String, Object> result = new HashMap<>();

        ErDataSync syncTask = erDataSyncMapper.selectErDataSyncBySyncId(syncId);
        if (syncTask == null)
        {
            result.put("success", false);
            result.put("message", "同步任务不存在");
            return result;
        }

        // 检查任务状态，如果正在同步中则不允许再次提交
        if ("1".equals(syncTask.getSyncStatus()))
        {
            result.put("success", false);
            result.put("message", "任务正在同步中，请勿重复提交");
            return result;
        }

        // 清除停止标志（如果之前有）
        stopFlags.remove(syncId);

        // 更新状态为同步中
        ErDataSync updateSync = new ErDataSync();
        updateSync.setSyncId(syncId);
        updateSync.setSyncStatus("1"); // 同步中
        updateSync.setSyncProgress("{\"current\":0,\"total\":0,\"percent\":0,\"message\":\"准备开始同步...\",\"pendingTables\":[],\"completedTables\":[]}");
        erDataSyncMapper.updateErDataSync(updateSync);

        // 提交异步任务
        executorService.submit(() -> {
            executeAsyncTask(syncId);
        });

        log.info("架构同步任务已提交，syncId={}", syncId);

        result.put("success", true);
        result.put("message", "同步任务已提交，正在后台执行");
        return result;
    }

    /**
     * 异步任务执行逻辑
     *
     * @param syncId 同步任务ID
     */
    private void executeAsyncTask(Long syncId)
    {
        ErDataSync syncTask = erDataSyncMapper.selectErDataSyncBySyncId(syncId);
        if (syncTask == null)
        {
            log.error("同步任务不存在: {}", syncId);
            return;
        }

        ErDataSync updateSync = new ErDataSync();
        updateSync.setSyncId(syncId);

        try
        {
            // 获取数据源信息
            ErDatasource source = erDatasourceMapper.selectErDatasourceByDatasourceId(syncTask.getSourceDatasourceId());
            ErDatasource target = erDatasourceMapper.selectErDatasourceByDatasourceId(syncTask.getTargetDatasourceId());

            if (source == null || target == null)
            {
                updateSync.setSyncStatus("3");
                updateSync.setLastSyncTime(DateUtils.getNowDate());
                updateSync.setLastSyncResult("{\"error\":\"数据源不存在\"}");
                updateSync.setSyncProgress("{\"current\":0,\"total\":0,\"percent\":0,\"message\":\"数据源不存在\"}");
                erDataSyncMapper.updateErDataSync(updateSync);
                return;
            }

            log.info("开始执行架构同步，syncId={}", syncId);

            // 执行同步，并在回调中更新进度，同时传入停止检查器
            SyncResult syncResult = schemaSyncExecutor.execute(syncTask, source, target,
                (current, total, message) -> {
                    // 更新进度（暂不包含表列表，因为回调参数限制）
                    Map<String, Object> progress = new HashMap<>();
                    progress.put("current", current);
                    progress.put("total", total);
                    progress.put("percent", total > 0 ? (current * 100 / total) : 0);
                    progress.put("message", message);

                    ErDataSync progressUpdate = new ErDataSync();
                    progressUpdate.setSyncId(syncId);
                    progressUpdate.setSyncProgress(JSON.toJSONString(progress));
                    erDataSyncMapper.updateErDataSync(progressUpdate);
                },
                () -> stopFlags.getOrDefault(syncId, false) // 停止检查器
            );

            log.info("架构同步执行完成，syncId={}, success={}, stopped={}", syncId, syncResult.isSuccess(), syncResult.isStopped());

            // 保存日志
            log.info("保存同步日志，syncId={}, 日志数量: {}", syncId, syncResult.getLogs().size());
            if (!syncResult.getLogs().isEmpty())
            {
                try {
                    erDataSyncLogMapper.batchInsertErDataSyncLog(syncResult.getLogs());
                    log.info("所有同步日志已保存，syncId={}", syncId);
                } catch (Exception e) {
                    log.error("保存同步日志失败，syncId={}", syncId, e);
                }
            }

            // 检查当前状态，如果已经被手动停止（状态4），则不再更新状态
            ErDataSync currentTask = erDataSyncMapper.selectErDataSyncBySyncId(syncId);
            if (currentTask != null && "4".equals(currentTask.getSyncStatus())) {
                log.info("同步任务 [{}] 已被手动停止，跳过状态更新", syncId);
                stopFlags.remove(syncId);
                return;
            }

            // 检查是否是停止导致的
            String syncStatus;
            if (syncResult.isStopped()) {
                syncStatus = "4"; // 已停止
            } else {
                syncStatus = syncResult.isSuccess() ? "2" : "3";
            }

            // 更新最终状态
            log.info("更新任务状态，syncId={}, 新状态: {}", syncId, syncStatus);
            updateSync.setSyncStatus(syncStatus);
            updateSync.setLastSyncTime(DateUtils.getNowDate());

            // 构建最终结果消息
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("message", syncResult.getMessage());
            resultMap.put("totalCount", syncResult.getTotalCount());
            resultMap.put("successCount", syncResult.getSuccessCount());
            updateSync.setLastSyncResult(JSON.toJSONString(resultMap));

            // 更新最终进度（同步完成后不再需要存储表列表，避免超出字段长度）
            Map<String, Object> finalProgress = new HashMap<>();
            finalProgress.put("current", syncResult.getSuccessCount());
            finalProgress.put("total", syncResult.getTotalCount());
            finalProgress.put("percent", syncResult.getTotalCount() > 0 ? 100 : 0);
            finalProgress.put("message", syncResult.getMessage());
            updateSync.setSyncProgress(JSON.toJSONString(finalProgress));

            erDataSyncMapper.updateErDataSync(updateSync);

            log.info("同步任务执行完成: syncId={}, success={}, status={}, message={}",
                syncId, syncResult.isSuccess(), syncStatus, syncResult.getMessage());

            // 清除停止标志
            stopFlags.remove(syncId);
        }
        catch (Exception e)
        {
            log.error("执行同步任务失败: syncId={}", syncId, e);

            try {
                // 更新状态为同步失败
                updateSync.setSyncStatus("3");
                updateSync.setLastSyncTime(DateUtils.getNowDate());
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                updateSync.setLastSyncResult("{\"error\":\"" + errorMsg.replace("\"", "'") + "\"}");
                updateSync.setSyncProgress("{\"current\":0,\"total\":0,\"percent\":0,\"message\":\"同步失败: " + errorMsg.replace("\"", "'") + "\"}");
                erDataSyncMapper.updateErDataSync(updateSync);
                log.info("已更新任务 [{}] 状态为失败", syncId);
            } catch (Exception updateEx) {
                log.error("更新任务 [{}] 失败状态时出错", syncId, updateEx);
            }

            // 清除停止标志
            stopFlags.remove(syncId);
        }
    }

    /**
     * 停止架构同步任务
     *
     * @param syncId 同步任务ID
     * @return 停止结果
     */
    @Override
    public Map<String, Object> stopSync(Long syncId)
    {
        Map<String, Object> result = new HashMap<>();

        ErDataSync syncTask = erDataSyncMapper.selectErDataSyncBySyncId(syncId);
        if (syncTask == null)
        {
            result.put("success", false);
            result.put("message", "同步任务不存在");
            return result;
        }

        // 只有正在同步中的任务才能停止
        if (!"1".equals(syncTask.getSyncStatus()))
        {
            result.put("success", false);
            result.put("message", "任务未在同步中");
            return result;
        }

        // 设置停止标志（用于正在运行的异步任务）
        stopFlags.put(syncId, true);

        // 直接更新数据库状态为已停止
        ErDataSync updateSync = new ErDataSync();
        updateSync.setSyncId(syncId);
        updateSync.setSyncStatus("4"); // 已停止
        updateSync.setLastSyncTime(DateUtils.getNowDate());
        updateSync.setLastSyncResult("{\"message\":\"任务已被手动停止\"}");
        erDataSyncMapper.updateErDataSync(updateSync);

        log.info("已停止架构同步任务 [{}]", syncId);

        result.put("success", true);
        result.put("message", "任务已停止");
        return result;
    }

    /**
     * 获取同步任务的实时进度信息
     *
     * @param syncId 同步任务ID
     * @return 包含同步进度、状态和结果的任务信息
     */
    @Override
    public ErDataSync getSyncProgress(Long syncId)
    {
        return erDataSyncMapper.selectErDataSyncBySyncId(syncId);
    }

    /**
     * 创建Quartz任务对象
     *
     * @param erDataSync 架构同步任务
     * @return Quartz任务对象
     */
    private SysJob createQuartzJob(ErDataSync erDataSync)
    {
        SysJob job = new SysJob();
        job.setJobName("架构同步-" + erDataSync.getSyncName());
        job.setJobGroup("DATA_SYNC");
        // 使用专门的Job类来执行架构同步（参数加L后缀表示Long类型）
        job.setInvokeTarget("dataSyncTask.executeSchemaSyncById(" + erDataSync.getSyncId() + "L)");
        job.setCronExpression(erDataSync.getCronExpression());
        job.setMisfirePolicy("2"); // 立即执行
        job.setConcurrent("0"); // 不允许并发

        // 使用业务任务的状态（0正常 1停用）
        String businessStatus = erDataSync.getStatus();
        String finalStatus = businessStatus != null ? businessStatus : "0";
        log.info("[Quartz同步] createQuartzJob - 业务任务status={}, 最终设置的status={}", businessStatus, finalStatus);
        job.setStatus(finalStatus);

        job.setCreateBy(erDataSync.getCreateBy());
        return job;
    }

    /**
     * 更新Quartz任务对象
     *
     * @param job Quartz任务对象
     * @param erDataSync 架构同步任务
     */
    private void updateQuartzJob(SysJob job, ErDataSync erDataSync)
    {
        job.setJobName("架构同步-" + erDataSync.getSyncName());
        job.setCronExpression(erDataSync.getCronExpression());
        job.setInvokeTarget("dataSyncTask.executeSchemaSyncById(" + erDataSync.getSyncId() + "L)");
        // 同步业务任务的状态
        if (erDataSync.getStatus() != null) {
            job.setStatus(erDataSync.getStatus());
        }
        job.setUpdateBy(erDataSync.getUpdateBy());
    }
}
