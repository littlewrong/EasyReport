package cn.easyreport.sync.service.impl;

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
import java.util.StringJoiner;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import com.alibaba.fastjson2.JSON;
import cn.easyreport.sync.datasync.DataSyncExecutor;
import cn.easyreport.sync.datasync.DataSyncExecutor.DataSyncResult;
import cn.easyreport.sync.datasync.DataSyncExtractor;
import cn.easyreport.sync.datasync.DataSyncFactory;
import cn.easyreport.sync.datasync.DataSyncExecutorFactory;
import cn.easyreport.sync.strategy.DatabaseStrategy;
import cn.easyreport.sync.strategy.DatabaseStrategyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.easyreport.common.utils.DateUtils;
import cn.easyreport.common.utils.StringUtils;
import cn.easyreport.sync.domain.ErDataTransfer;
import cn.easyreport.sync.domain.ErDataTransferLog;
import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.domain.ErDataTransferProgress;
import cn.easyreport.sync.mapper.ErDataTransferMapper;
import cn.easyreport.sync.mapper.ErDataTransferLogMapper;
import cn.easyreport.sync.mapper.ErDatasourceMapper;
import cn.easyreport.sync.service.IErDataTransferService;
import cn.easyreport.quartz.service.ISysJobService;
import cn.easyreport.quartz.domain.SysJob;

/**
 * 数据同步任务Service业务层处理
 *
 * @author easyreport
 * @date 2026-01-18
 */
@Service
public class ErDataTransferServiceImpl implements IErDataTransferService
{
    private static final Logger log = LoggerFactory.getLogger(ErDataTransferServiceImpl.class);

    // 存储正在执行的任务的停止标志：transferId -> stopFlag
    private static final ConcurrentHashMap<Long, Boolean> stopFlags = new ConcurrentHashMap<>();

    // 本实例内正在执行的数据传输任务，防止重复点击/定时任务并发启动同一个 transferId。
    private static final ConcurrentHashMap<Long, Boolean> runningTransfers = new ConcurrentHashMap<>();

    @Autowired
    private ErDataTransferMapper erDataTransferMapper;

    @Autowired
    private ErDataTransferLogMapper erDataTransferLogMapper;

    @Autowired
    private cn.easyreport.sync.mapper.ErDataTransferProgressMapper erDataTransferProgressMapper;

    @Autowired
    private ErDatasourceMapper erDatasourceMapper;

    @Autowired(required = false)
    private ISysJobService jobService;

    /**
     * 查询数据同步任务
     *
     * @param transferId 数据同步任务主键
     * @return 数据同步任务
     */
    @Override
    public ErDataTransfer selectErDataTransferByTransferId(Long transferId)
    {
        return erDataTransferMapper.selectErDataTransferByTransferId(transferId);
    }

    /**
     * 查询数据同步任务列表
     *
     * @param erDataTransfer 数据同步任务
     * @return 数据同步任务
     */
    @Override
    public List<ErDataTransfer> selectErDataTransferList(ErDataTransfer erDataTransfer)
    {
        return erDataTransferMapper.selectErDataTransferList(erDataTransfer);
    }

    /**
     * 新增数据同步任务
     *
     * @param erDataTransfer 数据同步任务
     * @return 结果
     */
    @Override
    @Transactional
    public int insertErDataTransfer(ErDataTransfer erDataTransfer)
    {
        log.info("[Quartz同步] 新增数据传输任务，前端传来的status={}", erDataTransfer.getStatus());

        // 预处理：将空字符串转换为null，避免查询时出现问题
        if (erDataTransfer.getSourceSchemaPattern() != null && erDataTransfer.getSourceSchemaPattern().trim().isEmpty())
        {
            log.info("insertErDataTransfer: sourceSchemaPattern is empty string, converting to null");
            erDataTransfer.setSourceSchemaPattern(null);
        }

        erDataTransfer.setCreateTime(DateUtils.getNowDate());
        erDataTransfer.setSyncStatus("0"); // 待同步
        // 如果前端没有传status，默认设置为"0"（启用）
        if (StringUtils.isEmpty(erDataTransfer.getStatus()))
        {
            erDataTransfer.setStatus("0");
            log.info("[Quartz同步] status为空，设置默认值为0");
        }

        log.info("[Quartz同步] 准备插入数据库，最终status={}", erDataTransfer.getStatus());
        int rows = erDataTransferMapper.insertErDataTransfer(erDataTransfer);

        // 如果配置了cron表达式，创建Quartz定时任务
        if (rows > 0 && StringUtils.isNotEmpty(erDataTransfer.getCronExpression()) && jobService != null)
        {
            try
            {
                log.info("[Quartz同步] 准备创建Quartz任务，业务任务status={}", erDataTransfer.getStatus());
                SysJob job = createQuartzJob(erDataTransfer);
                log.info("[Quartz同步] 创建的Quartz任务对象，status={}", job.getStatus());

                jobService.insertJob(job);
                log.info("[Quartz同步] insertJob完成，jobId={}，job对象当前status={}", job.getJobId(), job.getStatus());

                // 如果业务任务是启用状态，需要启动Quartz任务（insertJob默认为暂停状态）
                if ("0".equals(erDataTransfer.getStatus()))
                {
                    log.info("[Quartz同步] 业务任务是启用状态，调用changeStatus启动Quartz任务");
                    job.setStatus("0");  // 重新设置为启用状态，因为insertJob已将其改为"1"
                    log.info("[Quartz同步] 重新设置job.status=0，调用changeStatus");
                    jobService.changeStatus(job);
                }

                // 保存job_id到任务表
                ErDataTransfer updateTransfer = new ErDataTransfer();
                updateTransfer.setTransferId(erDataTransfer.getTransferId());
                updateTransfer.setJobId(job.getJobId());
                erDataTransferMapper.updateErDataTransfer(updateTransfer);

                // 查询并记录创建后的Quartz任务实际状态
                SysJob createdJob = jobService.selectJobById(job.getJobId());
                log.info("数据传输任务[{}]创建Quartz定时任务成功，jobId={}，Quartz任务实际status={}",
                    erDataTransfer.getTransferId(), job.getJobId(), createdJob != null ? createdJob.getStatus() : "null");
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
     * @param erDataTransfer 数据同步任务
     * @return 结果
     */
    @Override
    @Transactional
    public int updateErDataTransfer(ErDataTransfer erDataTransfer)
    {
        // 预处理：将空字符串转换为null，避免查询时出现问题
        if (erDataTransfer.getSourceSchemaPattern() != null && erDataTransfer.getSourceSchemaPattern().trim().isEmpty())
        {
            log.info("updateErDataTransfer: sourceSchemaPattern is empty string, converting to null");
            erDataTransfer.setSourceSchemaPattern(null);
        }

        erDataTransfer.setUpdateTime(DateUtils.getNowDate());
        ErDataTransfer oldTask = erDataTransferMapper.selectErDataTransferByTransferId(erDataTransfer.getTransferId());

        int rows = erDataTransferMapper.updateErDataTransfer(erDataTransfer);

        // 处理Quartz任务变更
        if (rows > 0 && jobService != null)
        {
            try
            {
                if (StringUtils.isNotEmpty(erDataTransfer.getCronExpression()))
                {
                    if (oldTask.getJobId() != null)
                    {
                        // 已有定时任务，更新
                        SysJob job = jobService.selectJobById(oldTask.getJobId());
                        if (job != null)
                        {
                            updateQuartzJob(job, erDataTransfer);
                            jobService.updateJob(job);
                            log.info("数据传输任务[{}]更新Quartz定时任务成功，jobId={}", erDataTransfer.getTransferId(), job.getJobId());
                        }
                        else
                        {
                            // job_id存在但Quartz中找不到，重新创建
                            SysJob newJob = createQuartzJob(erDataTransfer);
                            jobService.insertJob(newJob);

                            // 如果业务任务是启用状态，需要启动Quartz任务
                            if ("0".equals(erDataTransfer.getStatus()))
                            {
                                newJob.setStatus("0");  // 重新设置为启用状态，因为insertJob已将其改为"1"
                                jobService.changeStatus(newJob);
                            }

                            ErDataTransfer updateTransfer = new ErDataTransfer();
                            updateTransfer.setTransferId(erDataTransfer.getTransferId());
                            updateTransfer.setJobId(newJob.getJobId());
                            erDataTransferMapper.updateErDataTransfer(updateTransfer);
                            log.info("数据传输任务[{}]重新创建Quartz定时任务成功，jobId={}", erDataTransfer.getTransferId(), newJob.getJobId());
                        }
                    }
                    else
                    {
                        // 没有定时任务，新建
                        SysJob job = createQuartzJob(erDataTransfer);
                        jobService.insertJob(job);

                        // 如果业务任务是启用状态，需要启动Quartz任务
                        if ("0".equals(erDataTransfer.getStatus()))
                        {
                            job.setStatus("0");  // 重新设置为启用状态，因为insertJob已将其改为"1"
                            jobService.changeStatus(job);
                        }

                        ErDataTransfer updateTransfer = new ErDataTransfer();
                        updateTransfer.setTransferId(erDataTransfer.getTransferId());
                        updateTransfer.setJobId(job.getJobId());
                        erDataTransferMapper.updateErDataTransfer(updateTransfer);
                        log.info("数据传输任务[{}]创建Quartz定时任务成功，jobId={}", erDataTransfer.getTransferId(), job.getJobId());
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
                        ErDataTransfer updateTransfer = new ErDataTransfer();
                        updateTransfer.setTransferId(erDataTransfer.getTransferId());
                        updateTransfer.setJobId(null);
                        erDataTransferMapper.updateErDataTransfer(updateTransfer);
                        log.info("数据传输任务[{}]删除Quartz定时任务成功，jobId={}", erDataTransfer.getTransferId(), oldTask.getJobId());
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
     * @param transferIds 需要删除的数据同步任务主键
     * @return 结果
     */
    @Override
    @Transactional
    public int deleteErDataTransferByTransferIds(Long[] transferIds)
    {
        // 删除关联的日志和Quartz任务
        for (Long transferId : transferIds)
        {
            ErDataTransfer task = erDataTransferMapper.selectErDataTransferByTransferId(transferId);

            // 删除关联的Quartz任务
            if (task != null && task.getJobId() != null && jobService != null)
            {
                try
                {
                    SysJob job = new SysJob();
                    job.setJobId(task.getJobId());
                    jobService.deleteJob(job);
                    log.info("数据传输任务[{}]删除Quartz定时任务成功，jobId={}", transferId, task.getJobId());
                }
                catch (Exception e)
                {
                    log.error("删除Quartz定时任务失败: {}", e.getMessage(), e);
                    // 不影响主任务删除，只记录错误
                }
            }

            erDataTransferLogMapper.deleteErDataTransferLogByTransferId(transferId);
        }
        return erDataTransferMapper.deleteErDataTransferByTransferIds(transferIds);
    }

    /**
     * 删除数据同步任务信息
     *
     * @param transferId 数据同步任务主键
     * @return 结果
     */
    @Override
    @Transactional
    public int deleteErDataTransferByTransferId(Long transferId)
    {
        ErDataTransfer task = erDataTransferMapper.selectErDataTransferByTransferId(transferId);

        // 删除关联的Quartz任务
        if (task != null && task.getJobId() != null && jobService != null)
        {
            try
            {
                SysJob job = new SysJob();
                job.setJobId(task.getJobId());
                jobService.deleteJob(job);
                log.info("数据传输任务[{}]删除Quartz定时任务成功，jobId={}", transferId, task.getJobId());
            }
            catch (Exception e)
            {
                log.error("删除Quartz定时任务失败: {}", e.getMessage(), e);
                // 不影响主任务删除，只记录错误
            }
        }

        // 删除关联的日志
        erDataTransferLogMapper.deleteErDataTransferLogByTransferId(transferId);
        return erDataTransferMapper.deleteErDataTransferByTransferId(transferId);
    }

    /**
     * 校验任务名称是否唯一
     *
     * @param erDataTransfer 数据同步任务信息
     * @return 结果
     */
    @Override
    public boolean checkTransferNameUnique(ErDataTransfer erDataTransfer)
    {
        Long transferId = StringUtils.isNull(erDataTransfer.getTransferId()) ? -1L : erDataTransfer.getTransferId();
        ErDataTransfer info = erDataTransferMapper.checkTransferNameUnique(erDataTransfer.getTransferName());
        if (StringUtils.isNotNull(info) && info.getTransferId().longValue() != transferId.longValue())
        {
            return false;
        }
        return true;
    }

    /**
     * 执行同步任务（异步执行）
     *
     * @param transferId 同步任务ID
     * @return 同步结果
     */
    @Override
    public Map<String, Object> executeTransfer(Long transferId)
    {
        Map<String, Object> result = new HashMap<>();

        ErDataTransfer transferTask = erDataTransferMapper.selectErDataTransferByTransferId(transferId);
        if (transferTask == null)
        {
            result.put("success", false);
            result.put("message", "同步任务不存在");
            return result;
        }

        // 检查是否已经在同步中
        if ("1".equals(transferTask.getSyncStatus()))
        {
            result.put("success", false);
            result.put("message", "任务正在同步中，请勿重复执行");
            return result;
        }

        if (runningTransfers.putIfAbsent(transferId, Boolean.TRUE) != null)
        {
            result.put("success", false);
            result.put("message", "任务正在同步中，请勿重复执行");
            return result;
        }

        try {
            ErDatasource source = erDatasourceMapper.selectErDatasourceByDatasourceId(transferTask.getSourceDatasourceId());
            ErDatasource target = erDatasourceMapper.selectErDatasourceByDatasourceId(transferTask.getTargetDatasourceId());
            if (source == null || target == null) {
                runningTransfers.remove(transferId);
                result.put("success", false);
                result.put("message", "源或目标数据源未配置");
                return result;
            }

            // 清除停止标志（如果之前有）
            stopFlags.remove(transferId);

            // 更新状态为同步中
            ErDataTransfer updateTransfer = new ErDataTransfer();
            updateTransfer.setTransferId(transferId);
            updateTransfer.setSyncStatus("1"); // 同步中
            updateTransfer.setSyncProgress("{\"current\":0,\"total\":0,\"percent\":0,\"message\":\"准备开始同步...\"}");
            erDataTransferMapper.updateErDataTransfer(updateTransfer);

            // 异步执行同步任务
            Thread asyncThread = new Thread(() -> {
                executeTransferAsync(transferId, transferTask, source, target);
            });
            asyncThread.setName("DataSync-" + transferId);
            asyncThread.setUncaughtExceptionHandler((t, e) -> {
                log.error("同步任务 [" + transferId + "] 线程异常退出", e);
                // 确保异常时也更新状态
                try {
                    ErDataTransfer errorUpdate = new ErDataTransfer();
                    errorUpdate.setTransferId(transferId);
                    errorUpdate.setSyncStatus("3"); // 同步失败
                    errorUpdate.setLastSyncTime(DateUtils.getNowDate());
                    String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                    errorUpdate.setLastSyncResult("{\"error\":\"线程异常: " + errorMsg.replace("\"", "'") + "\"}");
                    erDataTransferMapper.updateErDataTransfer(errorUpdate);
                } catch (Exception ex) {
                    log.error("更新异常状态失败", ex);
                }
                stopFlags.remove(transferId);
                runningTransfers.remove(transferId);
            });
            asyncThread.start();
            log.info("已启动异步同步线程: {}", asyncThread.getName());

            result.put("success", true);
            result.put("message", "同步任务已启动");
            return result;
        } catch (Exception e) {
            runningTransfers.remove(transferId);
            stopFlags.remove(transferId);
            log.error("启动同步任务 [" + transferId + "] 失败", e);
            result.put("success", false);
            result.put("message", "启动同步任务失败: " + (e.getMessage() == null ? e.getClass().getName() : e.getMessage()));
            return result;
        }
    }

    /**
     * 异步执行同步任务的核心逻辑
     * 注意：此方法在异步线程中执行，不能使用@Transactional注解
     */
    private void executeTransferAsync(Long transferId, ErDataTransfer transferTask, ErDatasource source, ErDatasource target)
    {
        log.info("开始异步执行同步任务 [{}]", transferId);
        ErDataTransfer updateTransfer = new ErDataTransfer();
        updateTransfer.setTransferId(transferId);

        try
        {
            log.info("创建 DataSyncExecutor 执行器（根据目标数据库类型: {}），任务 [{}]", target.getDatasourceType(), transferId);
            DataSyncExecutor executor = DataSyncExecutorFactory.create(target, erDataTransferMapper, erDataTransferLogMapper, erDataTransferProgressMapper);

            log.info("调用 executor.execute() 开始同步，任务 [{}]", transferId);
            DataSyncResult syncResult = executor.execute(transferTask, source, target,
                (current, total, message) -> {
                    updateProgress(transferId, current, total, message);
                },
                () -> stopFlags.getOrDefault(transferId, false)); // 停止检查器

            log.info("executor.execute() 执行完成，任务 [{}]，结果: success={}, stopped={}, message={}",
                transferId, syncResult.isSuccess(), syncResult.isStopped(), syncResult.getMessage());

            // 记录日志
            log.info("保存同步日志，任务 [{}]，日志数量: {}", transferId, syncResult.getLogs().size());
            try {
                for (ErDataTransferLog logItem : syncResult.getLogs()) {
                    log.info("保存日志记录: 源表={}, 目标表={}, 结果={}",
                        logItem.getSourceTable(), logItem.getTargetTable(), logItem.getSyncResult());
                    erDataTransferLogMapper.insertErDataTransferLog(logItem);
                }
                log.info("所有同步日志已保存，任务 [{}]", transferId);
            } catch (Exception e) {
                log.error("保存同步日志失败，任务 [{}]", transferId, e);
            }

            // 检查当前状态，如果已经被手动停止（状态4），则不再更新状态
            ErDataTransfer currentTask = erDataTransferMapper.selectErDataTransferByTransferId(transferId);
            if (currentTask != null && "4".equals(currentTask.getSyncStatus())) {
                log.info("同步任务 [{}] 已被手动停止，跳过状态更新", transferId);
                stopFlags.remove(transferId);
                return;
            }

            // 检查是否是停止导致的
            String syncStatus;
            if (syncResult.isStopped()) {
                syncStatus = "4"; // 已停止
            } else {
                syncStatus = syncResult.isSuccess() ? "2" : "3";
            }

            // 更新状态
            log.info("更新任务状态，任务 [{}]，新状态: {}", transferId, syncStatus);
            updateTransfer.setSyncStatus(syncStatus);
            updateTransfer.setLastSyncTime(DateUtils.getNowDate());
            updateTransfer.setLastSyncResult("{\"message\":\"" + syncResult.getMessage() + "\"}");
            erDataTransferMapper.updateErDataTransfer(updateTransfer);

            log.info("同步任务 [{}] 执行完成，最终状态: {}, 结果: {}", transferId, syncStatus, syncResult.getMessage());

            // 清除停止标志
            stopFlags.remove(transferId);
        }
        catch (Exception e)
        {
            log.error("执行同步任务 [" + transferId + "] 失败", e);

            try {
                // 更新状态为同步失败
                updateTransfer.setSyncStatus("3"); // 同步失败
                updateTransfer.setLastSyncTime(DateUtils.getNowDate());
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                updateTransfer.setLastSyncResult("{\"error\":\"" + errorMsg.replace("\"", "'") + "\"}");
                erDataTransferMapper.updateErDataTransfer(updateTransfer);
                log.info("已更新任务 [{}] 状态为失败", transferId);
            } catch (Exception updateEx) {
                log.error("更新任务 [" + transferId + "] 失败状态时出错", updateEx);
            }

            // 清除停止标志
            stopFlags.remove(transferId);
        }
        finally
        {
            runningTransfers.remove(transferId);
        }
    }

    /**
     * 预览同步（查询匹配的表列表）
     *
     * @param transferId 同步任务ID
     * @return 包含表列表和统计信息的Map
     */
    @Override
    public Map<String, Object> previewTransfer(Long transferId)
    {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, String>> tables = new ArrayList<>();
        Set<String> schemas = new HashSet<>();

        ErDataTransfer transferTask = erDataTransferMapper.selectErDataTransferByTransferId(transferId);
        if (transferTask == null)
        {
            result.put("tables", tables);
            result.put("schemaCount", 0);
            result.put("tableCount", 0);
            return result;
        }

        ErDatasource sourceDatasource = erDatasourceMapper.selectErDatasourceByDatasourceId(transferTask.getSourceDatasourceId());
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
        String tablePattern = transferTask.getSourceTable();
        String schemaPattern = transferTask.getSourceSchemaPattern();
        List<String> schemaPatterns = new ArrayList<>();
        List<String> tablePatterns = new ArrayList<>();

        // 调试日志：记录从数据库读取的原始值
        log.warn("=== previewTransfer DEBUG ===");
        log.warn("transferId={}", transferId);
        log.warn("sourceSchemaPattern原始值=[{}]", transferTask.getSourceSchemaPattern());
        log.warn("sourceSchemaPattern长度={}", transferTask.getSourceSchemaPattern() == null ? "null" : transferTask.getSourceSchemaPattern().length());
        log.warn("sourceSchemaPattern是否为空字符串={}", transferTask.getSourceSchemaPattern() != null && transferTask.getSourceSchemaPattern().isEmpty());
        log.warn("sourceTable=[{}]", transferTask.getSourceTable());
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
                || dbType.toUpperCase().contains("STARROCKS"));

            if (isCatalogDb)
            {
                // MySQL/TiDB/StarRocks: catalog 参数不支持 % 通配符，直接用 information_schema
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
                log.info("previewTransfer information_schema SQL: {}", sql);
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
            else
            {
                // 其他数据库（PostgreSQL/Oracle/SQL Server）：使用 JDBC getTables
                List<String> schemaList = schemaPatterns.isEmpty() ? java.util.Collections.singletonList(null) : schemaPatterns;
                List<String> tableList = tablePatterns.isEmpty() ? java.util.Collections.singletonList(null) : tablePatterns;
                for (String sp : schemaList)
                {
                    for (String tp : tableList)
                    {
                        try (ResultSet rsTmp = metaData.getTables(null, sp, tp, new String[]{"TABLE"}))
                        {
                            while (rsTmp.next())
                            {
                                String schemaName = rsTmp.getString("TABLE_SCHEM");
                                String tableName = rsTmp.getString("TABLE_NAME");
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
            }

        }
        catch (Exception e)
        {
            log.error("预览同步失败", e);
        }
        finally
        {
            // 关闭连接
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
     * @param erDataTransferLog 同步日志
     * @return 同步日志集合
     */
    @Override
    public List<ErDataTransferLog> selectErDataTransferLogList(ErDataTransferLog erDataTransferLog)
    {
        return erDataTransferLogMapper.selectErDataTransferLogList(erDataTransferLog);
    }

    /**
     * 根据同步任务ID查询日志
     *
     * @param transferId 同步任务ID
     * @return 同步日志集合
     */
    @Override
    public List<ErDataTransferLog> selectErDataTransferLogByTransferId(Long transferId)
    {
        return erDataTransferLogMapper.selectErDataTransferLogByTransferId(transferId);
    }

    /**
     * 查询表级最后同步时间戳
     */
    @Override
    public List<ErDataTransferProgress> selectProgressByTransferId(Long transferId)
    {
        if (transferId == null)
        {
            return Collections.emptyList();
        }

        // 查询所有进度记录
        List<ErDataTransferProgress> allProgress = erDataTransferProgressMapper.selectByTransferId(transferId);

        // 获取任务配置，用于过滤
        ErDataTransfer transfer = erDataTransferMapper.selectErDataTransferByTransferId(transferId);
        if (transfer == null || StringUtils.isEmpty(transfer.getSourceSchemaPattern()))
        {
            // 如果任务不存在或没有配置schema模式，返回所有记录
            return allProgress;
        }

        // 将 sourceSchemaPattern 按逗号拆分为多个模式
        String schemaPatternRaw = transfer.getSourceSchemaPattern();
        List<String> schemaPatterns = new ArrayList<>();
        for (String p : schemaPatternRaw.split(","))
        {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) schemaPatterns.add(trimmed);
        }

        List<ErDataTransferProgress> filteredProgress = new ArrayList<>();

        for (ErDataTransferProgress progress : allProgress)
        {
            String sourceTable = progress.getSourceTable();
            if (StringUtils.isEmpty(sourceTable))
            {
                continue;
            }

            // 提取 schema 部分（格式：schema.table）
            String schema;
            int dotIndex = sourceTable.indexOf('.');
            if (dotIndex > 0)
            {
                schema = sourceTable.substring(0, dotIndex);
            }
            else
            {
                schema = sourceTable; // 如果没有点号，整个字符串就是schema
            }

            // 检查 schema 是否匹配任意一个模式
            boolean matched = false;
            for (String pat : schemaPatterns)
            {
                if (matchesPattern(schema, pat))
                {
                    matched = true;
                    break;
                }
            }
            if (matched)
            {
                filteredProgress.add(progress);
            }
        }

        return filteredProgress;
    }

    /**
     * 从目标表 MAX(timestampField) 刷新当前任务自己的表级增量进度。
     */
    @Override
    public Map<String, Object> refreshProgressFromTarget(Long transferId)
    {
        Map<String, Object> result = new HashMap<>();
        if (transferId == null)
        {
            result.put("success", false);
            result.put("message", "同步任务ID不能为空");
            return result;
        }

        ErDataTransfer transferTask = erDataTransferMapper.selectErDataTransferByTransferId(transferId);
        if (transferTask == null)
        {
            result.put("success", false);
            result.put("message", "同步任务不存在");
            return result;
        }
        if ("1".equals(transferTask.getSyncStatus()))
        {
            result.put("success", false);
            result.put("message", "任务正在同步中，不能刷新时间戳");
            return result;
        }
        if (StringUtils.isEmpty(transferTask.getTimestampField()))
        {
            result.put("success", false);
            result.put("message", "未配置时间戳字段，无法刷新增量起点");
            return result;
        }

        ErDatasource sourceDatasource = erDatasourceMapper.selectErDatasourceByDatasourceId(transferTask.getSourceDatasourceId());
        ErDatasource targetDatasource = erDatasourceMapper.selectErDatasourceByDatasourceId(transferTask.getTargetDatasourceId());
        if (sourceDatasource == null || targetDatasource == null)
        {
            result.put("success", false);
            result.put("message", "源或目标数据源未配置");
            return result;
        }

        int updated = 0;
        int skipped = 0;
        int failed = 0;
        Timestamp globalMaxTs = null;
        List<String> errors = new ArrayList<>();

        try
        {
            Class.forName(sourceDatasource.getDriverClass());
            Class.forName(targetDatasource.getDriverClass());
            DataSyncExtractor extractor = DataSyncFactory.buildExtractor(sourceDatasource);

            try (Connection sourceConn = DriverManager.getConnection(sourceDatasource.getJdbcUrl(), sourceDatasource.getUsername(), sourceDatasource.getPassword());
                 Connection targetConn = DriverManager.getConnection(targetDatasource.getJdbcUrl(), targetDatasource.getUsername(), targetDatasource.getPassword()))
            {
                List<String[]> tables = resolveTransferTableSpecs(extractor, sourceConn,
                        transferTask.getSourceSchemaPattern(), transferTask.getSourceTable());
                if (tables.isEmpty())
                {
                    result.put("success", false);
                    result.put("message", "未匹配到需要刷新的源表");
                    return result;
                }

                for (String[] spec : tables)
                {
                    String schema = spec[0];
                    String table = spec[1];
                    String sourceTable = (schema != null && !schema.isEmpty()) ? schema + "." + table : table;
                    String targetTable = resolveTargetTableForProgressRefresh(transferTask, targetDatasource.getDatasourceType(), schema, table);
                    String tsColumn = resolveTimestampColumnForProgressRefresh(extractor, sourceConn, schema, table, transferTask.getTimestampField());

                    try
                    {
                        Timestamp maxTs = queryTargetMaxTimestamp(targetConn, targetDatasource.getDatasourceType(), targetTable, tsColumn);
                        if (maxTs == null)
                        {
                            skipped++;
                            if (errors.size() < 10)
                            {
                                errors.add(sourceTable + ": 目标表最大时间戳为空");
                            }
                            continue;
                        }

                        ErDataTransferProgress progress = new ErDataTransferProgress();
                        progress.setTransferId(transferId);
                        progress.setSourceTable(sourceTable);
                        progress.setLastSyncValue(String.valueOf(maxTs.getTime()));
                        erDataTransferProgressMapper.upsert(progress);
                        updated++;
                        if (globalMaxTs == null || maxTs.after(globalMaxTs))
                        {
                            globalMaxTs = maxTs;
                        }
                    }
                    catch (Exception e)
                    {
                        failed++;
                        if (errors.size() < 10)
                        {
                            errors.add(sourceTable + ": " + e.getMessage());
                        }
                        log.warn("刷新表级时间戳失败，transferId={}, sourceTable={}", transferId, sourceTable, e);
                    }
                }
            }

            if (globalMaxTs != null)
            {
                ErDataTransfer updateTransfer = new ErDataTransfer();
                updateTransfer.setTransferId(transferId);
                updateTransfer.setLastSyncValue(String.valueOf(globalMaxTs.getTime()));
                erDataTransferMapper.updateErDataTransfer(updateTransfer);
            }
        }
        catch (Exception e)
        {
            log.error("刷新表级时间戳失败，transferId={}", transferId, e);
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

    private List<String[]> resolveTransferTableSpecs(DataSyncExtractor extractor, Connection conn,
                                                     String schemaPatternCsv, String tablePatternCsv) throws Exception
    {
        List<String> schemaPatterns = new ArrayList<>();
        if (schemaPatternCsv != null && !schemaPatternCsv.trim().isEmpty())
        {
            for (String s : schemaPatternCsv.split("[,;]"))
            {
                if (!s.trim().isEmpty()) schemaPatterns.add(s.trim());
            }
        }

        List<String> tablePatterns = new ArrayList<>();
        if (tablePatternCsv != null && !tablePatternCsv.trim().isEmpty())
        {
            for (String t : tablePatternCsv.split("[,;]"))
            {
                if (!t.trim().isEmpty()) tablePatterns.add(t.trim());
            }
        }
        return extractor.listTableSpecs(conn, schemaPatterns, tablePatterns);
    }

    private String resolveTargetTableForProgressRefresh(ErDataTransfer transferTask, String targetDbType, String schema, String table)
    {
        String targetSchema = null;
        String targetTableName;
        if (StringUtils.isNotEmpty(transferTask.getTargetTable()))
        {
            String targetTable = transferTask.getTargetTable();
            if (targetTable.contains("."))
            {
                int dot = targetTable.indexOf('.');
                targetSchema = targetTable.substring(0, dot);
                targetTableName = targetTable.substring(dot + 1);
            }
            else
            {
                targetTableName = targetTable;
            }
        }
        else
        {
            targetSchema = schema;
            targetTableName = table;
        }
        return qualifyTargetTableForProgressRefresh(targetDbType, targetSchema, targetTableName);
    }

    private String qualifyTargetTableForProgressRefresh(String dbType, String schema, String table)
    {
        if (StringUtils.isEmpty(schema))
        {
            return quoteTargetIdentifierForProgressRefresh(dbType, table);
        }

        String type = dbType == null ? "" : dbType.toUpperCase();
        if ("SQLSERVER".equals(type))
        {
            return "[" + schema + "].[dbo].[" + table + "]";
        }
        if ("ORACLE".equals(type))
        {
            return "\"" + schema.toUpperCase() + "\".\"" + table.toUpperCase() + "\"";
        }
        if ("POSTGRESQL".equals(type))
        {
            return "\"" + schema + "\".\"" + table + "\"";
        }
        return "`" + schema + "`.`" + table + "`";
    }

    private String resolveTimestampColumnForProgressRefresh(DataSyncExtractor extractor, Connection sourceConn,
                                                            String schema, String table, String timestampField)
    {
        try
        {
            List<String> columns = extractor.listColumns(sourceConn, schema, table);
            for (String column : columns)
            {
                if (column != null && column.equals(timestampField))
                {
                    return column;
                }
            }
            for (String column : columns)
            {
                if (column != null && column.equalsIgnoreCase(timestampField))
                {
                    return column;
                }
            }
        }
        catch (Exception e)
        {
            log.warn("读取源表字段失败，使用任务配置的时间戳字段继续，schema={}, table={}, timestampField={}",
                    schema, table, timestampField, e);
        }
        return timestampField;
    }

    private Timestamp queryTargetMaxTimestamp(Connection targetConn, String targetDbType, String targetTable,
                                              String timestampField) throws Exception
    {
        String sql = "SELECT MAX(" + quoteTargetIdentifierForProgressRefresh(targetDbType, timestampField) + ") FROM " + targetTable;
        try (PreparedStatement ps = targetConn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery())
        {
            if (!rs.next())
            {
                return null;
            }
            return toTimestamp(rs.getObject(1));
        }
    }

    private String quoteTargetIdentifierForProgressRefresh(String dbType, String identifier)
    {
        DatabaseStrategy strategy = DatabaseStrategyFactory.getStrategy(dbType);
        if (strategy == null)
        {
            return identifier;
        }
        return strategy.quoteIdentifier(identifier);
    }

    private Timestamp toTimestamp(Object value)
    {
        if (value == null)
        {
            return null;
        }
        if (value instanceof Timestamp)
        {
            return (Timestamp) value;
        }
        if (value instanceof java.sql.Date)
        {
            return new Timestamp(((java.sql.Date) value).getTime());
        }
        if (value instanceof java.util.Date)
        {
            return new Timestamp(((java.util.Date) value).getTime());
        }
        if (value instanceof java.time.OffsetDateTime)
        {
            return Timestamp.from(((java.time.OffsetDateTime) value).toInstant());
        }
        if (value instanceof java.time.LocalDateTime)
        {
            return Timestamp.valueOf((java.time.LocalDateTime) value);
        }
        return null;
    }

    /**
     * 检查字符串是否匹配模式（支持%通配符）
     * @param value 要检查的值
     * @param pattern 模式（支持%作为通配符）
     * @return 是否匹配
     */
    private boolean matchesPattern(String value, String pattern)
    {
        if (StringUtils.isEmpty(value) || StringUtils.isEmpty(pattern))
        {
            return false;
        }

        // 如果模式不包含通配符，直接比较
        if (!pattern.contains("%"))
        {
            return value.equals(pattern);
        }

        // 将 SQL 通配符 % 转换为正则表达式
        String regex = pattern.replace("%", ".*");
        return value.matches(regex);
    }

    /**
     * 获取同步任务的实时进度信息
     *
     * @param transferId 同步任务ID
     * @return 包含同步进度、状态和结果的任务信息
     */
    @Override
    public ErDataTransfer getSyncProgress(Long transferId)
    {
        return erDataTransferMapper.selectErDataTransferByTransferId(transferId);
    }

    /**
     * 停止同步任务（优雅停止，当前表处理完成后停止）
     *
     * @param transferId 同步任务ID
     * @return 停止结果
     */
    @Override
    public Map<String, Object> stopTransfer(Long transferId)
    {
        Map<String, Object> result = new HashMap<>();

        ErDataTransfer transferTask = erDataTransferMapper.selectErDataTransferByTransferId(transferId);
        if (transferTask == null)
        {
            result.put("success", false);
            result.put("message", "同步任务不存在");
            return result;
        }

        // 只有正在同步中的任务才能停止
        if (!"1".equals(transferTask.getSyncStatus()))
        {
            result.put("success", false);
            result.put("message", "任务未在同步中");
            return result;
        }

        // 设置停止标志（用于正在运行的异步任务）
        stopFlags.put(transferId, true);

        // 直接更新数据库状态为已停止
        // 如果异步任务还在运行，它会检测到停止标志并优雅退出
        // 如果异步任务已经不存在（如程序重启），直接更新状态可以解除锁定
        ErDataTransfer updateTransfer = new ErDataTransfer();
        updateTransfer.setTransferId(transferId);
        updateTransfer.setSyncStatus("4"); // 已停止
        updateTransfer.setLastSyncTime(DateUtils.getNowDate());
        updateTransfer.setLastSyncResult("{\"message\":\"任务已被手动停止\"}");
        erDataTransferMapper.updateErDataTransfer(updateTransfer);

        log.info("已停止同步任务 [{}]", transferId);

        result.put("success", true);
        result.put("message", "任务已停止");
        return result;
    }

    // ================= 数据同步核心实现 =================

    private LegacyDataSyncResult doExecuteTransfer(ErDataTransfer task) throws Exception
    {
        LegacyDataSyncResult result = new LegacyDataSyncResult();
        result.setSuccess(true);
        result.setMessage("OK");

        ErDatasource sourceDs = erDatasourceMapper.selectErDatasourceByDatasourceId(task.getSourceDatasourceId());
        ErDatasource targetDs = erDatasourceMapper.selectErDatasourceByDatasourceId(task.getTargetDatasourceId());
        if (sourceDs == null || targetDs == null) {
            throw new IllegalStateException("数据源不存在");
        }

        Class.forName(sourceDs.getDriverClass());
        Class.forName(targetDs.getDriverClass());

        try (Connection sourceConn = DriverManager.getConnection(sourceDs.getJdbcUrl(), sourceDs.getUsername(), sourceDs.getPassword());
             Connection targetConn = DriverManager.getConnection(targetDs.getJdbcUrl(), targetDs.getUsername(), targetDs.getPassword())) {

            sourceConn.setAutoCommit(false);
            targetConn.setAutoCommit(false);

            List<String> tables = listTables(sourceConn.getMetaData(), sourceDs.getDatasourceType(),
                task.getSourceSchemaPattern(), task.getSourceTable());

            int batchSize = (task.getBatchSize() != null && task.getBatchSize() > 0) ? task.getBatchSize() : 500;
            long totalTables = tables.size();
            long currentTable = 0;

            Timestamp maxSyncTs = null;

            for (String table : tables) {
                currentTable++;
                SyncTableResult tr = syncSingleTable(task, table, sourceConn, targetConn, batchSize, totalTables, currentTable);
                result.addLog(tr.log);
                if (!tr.success) {
                    result.setSuccess(false);
                    result.setMessage("部分表同步失败");
                }
                if (tr.maxTs != null && (maxSyncTs == null || tr.maxTs.after(maxSyncTs))) {
                    maxSyncTs = tr.maxTs;
                }
            }

            if (maxSyncTs != null) {
                ErDataTransfer upd = new ErDataTransfer();
                upd.setTransferId(task.getTransferId());
                upd.setLastSyncValue(String.valueOf(maxSyncTs.getTime()));
                erDataTransferMapper.updateErDataTransfer(upd);
            }
        }
        return result;
    }

    private SyncTableResult syncSingleTable(ErDataTransfer task, String table, Connection sourceConn, Connection targetConn,
                                            int batchSize, long totalTables, long currentTable) throws Exception {
        SyncTableResult tr = new SyncTableResult();
        tr.success = true;
        tr.log = new ErDataTransferLog();
        tr.log.setTransferId(task.getTransferId());
        tr.log.setSourceTable(table);
        tr.log.setTargetTable(task.getTargetTable() != null && !task.getTargetTable().isEmpty() ? task.getTargetTable() : table);
        tr.log.setSyncMode(task.getSyncMode());
        tr.log.setSyncAction("FULL");
        tr.log.setStartTime(DateUtils.getNowDate());

        List<String> columns = listColumns(sourceConn, task.getSourceSchemaPattern(), table);
        if (columns.isEmpty()) {
            tr.log.setSyncResult("1");
            tr.log.setErrorMessage("无法获取列信息");
            return tr;
        }
        List<String> pkCols = listPrimaryKeys(sourceConn, task.getSourceSchemaPattern(), table);
        if (pkCols.isEmpty()) {
            pkCols = Collections.singletonList(columns.get(0)); // 兜底使用第一列
        }

        boolean doInsert = "1".equals(task.getSyncInsert());
        boolean doUpdate = "1".equals(task.getSyncUpdate());
        boolean doDelete = "1".equals(task.getSyncDelete());
        String timestampField = task.getTimestampField();

        if ("0".equals(task.getSyncMode())) {
            // 初始化：全量分批导入
            tr = syncFull(task, table, columns, pkCols, sourceConn, targetConn, batchSize, timestampField, doInsert, doUpdate, totalTables, currentTable);
        } else {
            // 增量：基于时间戳 + UPSERT + 删除对齐
            tr = syncIncremental(task, table, columns, pkCols, sourceConn, targetConn, batchSize, timestampField, doInsert, doUpdate, doDelete, totalTables, currentTable);
        }

        tr.log.setEndTime(DateUtils.getNowDate());
        if (tr.log.getStartTime() != null && tr.log.getEndTime() != null) {
            tr.log.setExecuteTime((tr.log.getEndTime().getTime() - tr.log.getStartTime().getTime()));
        }
        return tr;
    }

    private SyncTableResult syncFull(ErDataTransfer task, String table, List<String> columns, List<String> pkCols,
                                     Connection sourceConn, Connection targetConn, int batchSize, String tsField,
                                     boolean doInsert, boolean doUpdate,
                                     long totalTables, long currentTable) throws Exception {
        SyncTableResult tr = new SyncTableResult();
        tr.success = true;
        tr.log = new ErDataTransferLog();
        tr.log.setTransferId(task.getTransferId());
        tr.log.setSourceTable(table);
        tr.log.setTargetTable(task.getTargetTable() != null && !task.getTargetTable().isEmpty() ? task.getTargetTable() : table);
        tr.log.setSyncMode("0");
        tr.log.setSyncAction("FULL");
        tr.log.setStartTime(DateUtils.getNowDate());

        String targetTable = tr.log.getTargetTable();
        // 清空目标表
        try (Statement st = targetConn.createStatement()) {
            st.execute("DELETE FROM `" + targetTable + "`");
            targetConn.commit();
        }

        String selectSql = buildSelectAll(task.getSourceSchemaPattern(), table, tsField, pkCols);
        String insertSql = buildInsertSql(targetTable, columns, doUpdate);

        try (PreparedStatement psSelect = sourceConn.prepareStatement(selectSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             PreparedStatement psInsert = targetConn.prepareStatement(insertSql)) {
            psSelect.setFetchSize(batchSize);
            ResultSet rs = psSelect.executeQuery();
            int batch = 0;
            long processed = 0;
            Timestamp maxTs = null;
            while (rs.next()) {
                for (int i = 0; i < columns.size(); i++) {
                    psInsert.setObject(i + 1, rs.getObject(columns.get(i)));
                }
                Timestamp ts = rs.getTimestamp(tsField);
                if (ts != null && (maxTs == null || ts.after(maxTs))) {
                    maxTs = ts;
                }
                psInsert.addBatch();
                batch++;
                processed++;
                if (batch >= batchSize) {
                    psInsert.executeBatch();
                    targetConn.commit();
                    batch = 0;
                    updateProgress(task.getTransferId(), processed, -1, "初始化表: " + table + " (" + processed + ")");
                }
            }
            if (batch > 0) {
                psInsert.executeBatch();
                targetConn.commit();
            }
            tr.maxTs = maxTs;
            tr.log.setTotalCount(processed);
            tr.log.setSuccessCount(processed);
            tr.log.setSyncResult("0");
        } catch (Exception e) {
            targetConn.rollback();
            tr.success = false;
            tr.log.setSyncResult("1");
            tr.log.setErrorMessage(e.getMessage());
            throw e;
        }
        return tr;
    }

    private SyncTableResult syncIncremental(ErDataTransfer task, String table, List<String> columns, List<String> pkCols,
                                           Connection sourceConn, Connection targetConn, int batchSize, String tsField,
                                           boolean doInsert, boolean doUpdate, boolean doDelete,
                                           long totalTables, long currentTable) throws Exception {
        SyncTableResult tr = new SyncTableResult();
        tr.success = true;
        tr.log = new ErDataTransferLog();
        tr.log.setTransferId(task.getTransferId());
        tr.log.setSourceTable(table);
        tr.log.setTargetTable(task.getTargetTable() != null && !task.getTargetTable().isEmpty() ? task.getTargetTable() : table);
        tr.log.setSyncMode("1");
        tr.log.setSyncAction("INCR");
        tr.log.setStartTime(DateUtils.getNowDate());

        Timestamp lastSyncTs = parseTimestamp(task.getLastSyncValue());
        Timestamp maxTs = lastSyncTs;

        String targetTable = tr.log.getTargetTable();
        String incrSql = buildIncrementalSelect(task.getSourceSchemaPattern(), table, tsField, pkCols);
        String insertSql = buildInsertSql(targetTable, columns, doUpdate);

        try (PreparedStatement psSelect = sourceConn.prepareStatement(incrSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             PreparedStatement psUpsert = targetConn.prepareStatement(insertSql)) {
            psSelect.setFetchSize(batchSize);
            psSelect.setTimestamp(1, lastSyncTs == null ? new Timestamp(0) : lastSyncTs);

            ResultSet rs = psSelect.executeQuery();
            int batch = 0;
            long total = 0;
            while (rs.next()) {
                for (int i = 0; i < columns.size(); i++) {
                    psUpsert.setObject(i + 1, rs.getObject(columns.get(i)));
                }
                Timestamp ts = rs.getTimestamp(tsField);
                if (ts != null && (maxTs == null || ts.after(maxTs))) {
                    maxTs = ts;
                }
        psUpsert.addBatch();
                batch++;
                total++;
                if (batch >= batchSize) {
                    psUpsert.executeBatch();
                    targetConn.commit();
                    batch = 0;
                    updateProgress(task.getTransferId(), total, -1, "增量表: " + table + " (" + total + ")");
                }
            }
            if (batch > 0) {
                psUpsert.executeBatch();
                targetConn.commit();
            }
            tr.log.setTotalCount(total);
            tr.log.setSuccessCount(total);
            tr.log.setSyncResult("0");
        } catch (Exception e) {
            targetConn.rollback();
            tr.success = false;
            tr.log.setSyncResult("1");
            tr.log.setErrorMessage(e.getMessage());
            throw e;
        }

        // 删除对齐
        if (doDelete) {
            try {
                int windowYears = task.getDeleteWindowYears() != null ? task.getDeleteWindowYears() : 2;
                Timestamp windowStart = new Timestamp(System.currentTimeMillis() - (long) windowYears * 365 * 24 * 3600 * 1000);
                alignDelete(targetConn, sourceConn, task.getSourceSchemaPattern(), table, targetTable, tsField, pkCols, windowStart, batchSize);
            } catch (Exception e) {
                tr.success = false;
                tr.log.setSyncResult("1");
                tr.log.setErrorMessage("删除对齐失败: " + e.getMessage());
            }
        }

        tr.maxTs = maxTs;
        return tr;
    }

    private void alignDelete(Connection targetConn, Connection sourceConn, String schema, String sourceTable, String targetTable,
                             String tsField, List<String> pkCols, Timestamp windowStart, int batchSize) throws Exception {
        String pk = pkCols.get(0);
        String selectTarget = "SELECT `" + pk + "`, `" + tsField + "` FROM `" + targetTable + "` WHERE `" + tsField + "` >= ? ORDER BY `" + tsField + "`, `" + pk + "` LIMIT ?";
        boolean hasMore = true;
        Timestamp cursorTs = windowStart;
        String lastPk = null;
        while (hasMore) {
            try (PreparedStatement ps = targetConn.prepareStatement(selectTarget)) {
                ps.setTimestamp(1, cursorTs);
                ps.setInt(2, batchSize);
                ResultSet rs = ps.executeQuery();
                List<Object> ids = new ArrayList<>();
                Timestamp maxTs = cursorTs;
                while (rs.next()) {
                    ids.add(rs.getObject(pk));
                    Timestamp ts = rs.getTimestamp(tsField);
                    if (ts != null && (maxTs == null || ts.after(maxTs))) {
                        maxTs = ts;
                        lastPk = rs.getString(pk);
                    }
                }
                hasMore = ids.size() == batchSize;
                if (ids.isEmpty()) break;

                // 反查源库
                String sourceTableName = quoteMySqlTableName(schema, sourceTable);
                Set<String> exist = new HashSet<>();
                int deleteChunkSize = 1000;
                for (int start = 0; start < ids.size(); start += deleteChunkSize) {
                    int end = Math.min(start + deleteChunkSize, ids.size());
                    List<Object> chunk = ids.subList(start, end);
                    String checkSql = "SELECT `" + pk + "` FROM " + sourceTableName +
                            " WHERE `" + pk + "` IN (" + buildPlaceholders(chunk.size()) + ")";
                    try (PreparedStatement psCheck = sourceConn.prepareStatement(checkSql)) {
                        bindObjects(psCheck, chunk);
                        try (ResultSet rsSrc = psCheck.executeQuery()) {
                            while (rsSrc.next()) exist.add(String.valueOf(rsSrc.getObject(pk)));
                        }
                    }
                }

                List<Object> deleteIds = new ArrayList<>();
                for (Object id : ids) {
                    if (!exist.contains(String.valueOf(id))) {
                        deleteIds.add(id);
                    }
                }

                String targetTableName = quoteMySqlTableName(null, targetTable);
                for (int start = 0; start < deleteIds.size(); start += deleteChunkSize) {
                    int end = Math.min(start + deleteChunkSize, deleteIds.size());
                    List<Object> chunk = deleteIds.subList(start, end);
                    String deleteSql = "DELETE FROM " + targetTableName +
                            " WHERE `" + pk + "` IN (" + buildPlaceholders(chunk.size()) + ")";
                    try (PreparedStatement psDelete = targetConn.prepareStatement(deleteSql)) {
                        bindObjects(psDelete, chunk);
                        psDelete.executeUpdate();
                    }
                }
                if (!deleteIds.isEmpty()) {
                    targetConn.commit();
                }
                cursorTs = maxTs;
            }
        }
    }

    private String quoteMySqlTableName(String schema, String table) {
        if (table == null) return "``";
        if (table.contains("`")) return table;
        if (schema != null && !schema.isEmpty()) {
            return "`" + schema + "`.`" + table + "`";
        }
        if (table.contains(".")) {
            String[] parts = table.split("\\.", 2);
            return "`" + parts[0] + "`.`" + parts[1] + "`";
        }
        return "`" + table + "`";
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

    private List<String> listTables(DatabaseMetaData meta, String dbType, String schemaPattern, String tablePattern) throws Exception {
        List<String> list = new ArrayList<>();
        String catalog = null;
        String schemaParam = null;
        // MySQL/TiDB/StarRocks/SQL Server: schemaPattern 是数据库名(catalog)
        // PostgreSQL/Oracle: schemaPattern 是 schema
        if (dbType != null && (dbType.toUpperCase().contains("MYSQL")
            || dbType.toUpperCase().contains("TIDB")
            || dbType.toUpperCase().contains("STARROCKS")
            || dbType.toUpperCase().contains("SQLSERVER"))) {
            catalog = schemaPattern;
        } else {
            schemaParam = schemaPattern;
        }
        try (ResultSet rs = meta.getTables(catalog, schemaParam, tablePattern, new String[]{"TABLE"})) {
            while (rs.next()) {
                list.add(rs.getString("TABLE_NAME"));
            }
        }
        return list;
    }

    private List<String> listColumns(Connection conn, String schemaPattern, String table) throws Exception {
        List<String> cols = new ArrayList<>();
        String schema = (schemaPattern == null || schemaPattern.isEmpty()) ? conn.getCatalog() : schemaPattern;
        String sql = "SELECT COLUMN_NAME FROM information_schema.columns WHERE table_schema=? AND table_name=? ORDER BY ORDINAL_POSITION";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) cols.add(rs.getString(1));
            }
        }
        return cols;
    }

    private List<String> listPrimaryKeys(Connection conn, String schemaPattern, String table) throws Exception {
        List<String> pk = new ArrayList<>();
        String schema = (schemaPattern == null || schemaPattern.isEmpty()) ? conn.getCatalog() : schemaPattern;
        String sql = "SELECT COLUMN_NAME FROM information_schema.key_column_usage WHERE table_schema=? AND table_name=? AND constraint_name='PRIMARY' ORDER BY ordinal_position";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) pk.add(rs.getString(1));
            }
        }
        return pk;
    }

    private String buildInsertSql(String table, List<String> cols, boolean upsert) {
        StringJoiner colJoin = new StringJoiner(",", "(", ")");
        StringJoiner valJoin = new StringJoiner(",", "(", ")");
        for (String c : cols) {
            colJoin.add("`" + c + "`");
            valJoin.add("?");
        }
        String sql = "INSERT INTO `" + table + "`" + colJoin.toString() + " VALUES " + valJoin.toString();
        if (upsert) {
            StringJoiner upd = new StringJoiner(",");
            for (String c : cols) {
                upd.add("`" + c + "`=VALUES(`" + c + "`)");
            }
            sql += " ON DUPLICATE KEY UPDATE " + upd;
        }
        return sql;
    }

    private String buildSelectAll(String schema, String table, String tsField, List<String> pk) {
        String prefix = (schema != null && !schema.isEmpty()) ? ("`" + schema + "`.") : "";
        String order = "`" + tsField + "`";
        if (!pk.isEmpty()) {
            order += ", `" + pk.get(0) + "`";
        }
        return "SELECT * FROM " + prefix + "`" + table + "` ORDER BY " + order;
    }

    private String buildIncrementalSelect(String schema, String table, String tsField, List<String> pk) {
        String prefix = (schema != null && !schema.isEmpty()) ? ("`" + schema + "`.") : "";
        String order = "`" + tsField + "`";
        if (!pk.isEmpty()) {
            order += ", `" + pk.get(0) + "`";
        }
        return "SELECT * FROM " + prefix + "`" + table + "` WHERE `" + tsField + "` > ? ORDER BY " + order;
    }

    private Timestamp parseTimestamp(String v) {
        if (v == null || v.isEmpty()) return null;
        try {
            long ms = Long.parseLong(v);
            return new Timestamp(ms);
        } catch (Exception e) {
            return null;
        }
    }

    private void updateProgress(Long transferId, long current, long total, String message) {
        Map<String, Object> progress = new HashMap<>();
        progress.put("current", current);
        progress.put("total", total);
        progress.put("percent", (total > 0 && current >= 0) ? (current * 100 / total) : 0);
        progress.put("message", message);
        ErDataTransfer upd = new ErDataTransfer();
        upd.setTransferId(transferId);
        upd.setSyncProgress(JSON.toJSONString(progress));
        erDataTransferMapper.updateErDataTransfer(upd);
    }

    private static class SyncTableResult {
        boolean success;
        ErDataTransferLog log;
        Timestamp maxTs;
    }

    /**
     * 创建Quartz任务对象
     *
     * @param erDataTransfer 数据传输任务
     * @return Quartz任务对象
     */
    private SysJob createQuartzJob(ErDataTransfer erDataTransfer)
    {
        SysJob job = new SysJob();
        job.setJobName("数据传输-" + erDataTransfer.getTransferName());
        job.setJobGroup("DATA_SYNC");
        // 使用专门的Job类来执行数据传输（参数加L后缀表示Long类型）
        job.setInvokeTarget("dataSyncTask.executeDataTransferById(" + erDataTransfer.getTransferId() + "L)");
        job.setCronExpression(erDataTransfer.getCronExpression());
        job.setMisfirePolicy("2"); // 立即执行
        job.setConcurrent("0"); // 不允许并发

        // 使用业务任务的状态（0正常 1停用）
        String businessStatus = erDataTransfer.getStatus();
        String finalStatus = businessStatus != null ? businessStatus : "0";
        log.info("[Quartz同步] createQuartzJob - 业务任务status={}, 最终设置的status={}", businessStatus, finalStatus);
        job.setStatus(finalStatus);

        job.setCreateBy(erDataTransfer.getCreateBy());
        return job;
    }

    /**
     * 更新Quartz任务对象
     *
     * @param job Quartz任务对象
     * @param erDataTransfer 数据传输任务
     */
    private void updateQuartzJob(SysJob job, ErDataTransfer erDataTransfer)
    {
        job.setJobName("数据传输-" + erDataTransfer.getTransferName());
        job.setCronExpression(erDataTransfer.getCronExpression());
        job.setInvokeTarget("dataSyncTask.executeDataTransferById(" + erDataTransfer.getTransferId() + "L)");
        // 同步业务任务的状态
        if (erDataTransfer.getStatus() != null) {
            job.setStatus(erDataTransfer.getStatus());
        }
        job.setUpdateBy(erDataTransfer.getUpdateBy());
    }

    private static class LegacyDataSyncResult {
        private boolean success;
        private String message;
        private long totalCount;
        private long successCount;
        private long failCount;
        private final List<ErDataTransferLog> logs = new ArrayList<>();

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
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
    }
}
