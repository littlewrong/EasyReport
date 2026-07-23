package cn.easyreport.web.controller.sync;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import cn.easyreport.common.core.controller.BaseController;
import cn.easyreport.common.core.domain.AjaxResult;
import cn.easyreport.sync.domain.ErDataSync;
import cn.easyreport.sync.domain.ErDataTransfer;
import cn.easyreport.sync.service.IErDataSyncService;
import cn.easyreport.sync.service.IErDataTransferService;

/**
 * 批量测试控制器
 * 用于批量执行所有架构同步和数据传输任务，并等待结果
 */
@RestController
@RequestMapping("/system/batchtest")
public class BatchTestController extends BaseController
{
    private static final Logger log = LoggerFactory.getLogger(BatchTestController.class);

    /** 默认轮询间隔（毫秒） */
    private static final long POLL_INTERVAL = 3000;

    /** 默认单任务超时（毫秒）：10分钟 */
    private static final long DEFAULT_TIMEOUT = 600000;

    @Autowired
    private IErDataSyncService dataSyncService;

    @Autowired
    private IErDataTransferService dataTransferService;

    /**
     * 批量执行所有架构同步任务
     * POST /system/batchtest/sync
     *
     * @param timeoutMs 单任务超时（毫秒），默认600000
     * @return 各任务执行结果汇总
     */
    @PostMapping("/sync")
    public AjaxResult batchSync(
            @RequestParam(value = "timeout", required = false, defaultValue = "600000") long timeoutMs)
    {
        log.info("========== 开始批量架构同步测试 ==========");

        List<ErDataSync> tasks = dataSyncService.selectErDataSyncList(new ErDataSync());
        if (tasks == null || tasks.isEmpty())
        {
            return success("没有架构同步任务");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;
        int skipCount = 0;

        for (ErDataSync task : tasks)
        {
            Map<String, Object> taskResult = executeSyncTask(task, timeoutMs);
            results.add(taskResult);

            String status = (String) taskResult.get("status");
            if ("SUCCESS".equals(status))
            {
                successCount++;
            }
            else if ("SKIPPED".equals(status))
            {
                skipCount++;
            }
            else
            {
                failCount++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("taskType", "架构同步");
        summary.put("total", tasks.size());
        summary.put("success", successCount);
        summary.put("failed", failCount);
        summary.put("skipped", skipCount);
        summary.put("details", results);

        log.info("========== 批量架构同步测试完成: 总计={}, 成功={}, 失败={}, 跳过={} ==========",
                tasks.size(), successCount, failCount, skipCount);

        return success(summary);
    }

    /**
     * 批量执行所有数据传输任务
     * POST /system/batchtest/transfer
     *
     * @param timeoutMs 单任务超时（毫秒），默认600000
     * @return 各任务执行结果汇总
     */
    @PostMapping("/transfer")
    public AjaxResult batchTransfer(
            @RequestParam(value = "timeout", required = false, defaultValue = "600000") long timeoutMs)
    {
        log.info("========== 开始批量数据传输测试 ==========");

        List<ErDataTransfer> tasks = dataTransferService.selectErDataTransferList(new ErDataTransfer());
        if (tasks == null || tasks.isEmpty())
        {
            return success("没有数据传输任务");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;
        int skipCount = 0;

        for (ErDataTransfer task : tasks)
        {
            Map<String, Object> taskResult = executeTransferTask(task, timeoutMs);
            results.add(taskResult);

            String status = (String) taskResult.get("status");
            if ("SUCCESS".equals(status))
            {
                successCount++;
            }
            else if ("SKIPPED".equals(status))
            {
                skipCount++;
            }
            else
            {
                failCount++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("taskType", "数据传输");
        summary.put("total", tasks.size());
        summary.put("success", successCount);
        summary.put("failed", failCount);
        summary.put("skipped", skipCount);
        summary.put("details", results);

        log.info("========== 批量数据传输测试完成: 总计={}, 成功={}, 失败={}, 跳过={} ==========",
                tasks.size(), successCount, failCount, skipCount);

        return success(summary);
    }

    /**
     * 批量执行全部任务（先架构同步，再数据传输）
     * POST /system/batchtest/all
     *
     * @param timeoutMs 单任务超时（毫秒），默认600000
     * @return 全部任务执行结果汇总
     */
    @PostMapping("/all")
    public AjaxResult batchAll(
            @RequestParam(value = "timeout", required = false, defaultValue = "600000") long timeoutMs)
    {
        log.info("========== 开始批量全部测试（架构同步 + 数据传输） ==========");

        // 1. 架构同步
        List<ErDataSync> syncTasks = dataSyncService.selectErDataSyncList(new ErDataSync());
        List<Map<String, Object>> syncResults = new ArrayList<>();
        int syncSuccess = 0, syncFail = 0, syncSkip = 0;

        if (syncTasks != null)
        {
            for (ErDataSync task : syncTasks)
            {
                Map<String, Object> taskResult = executeSyncTask(task, timeoutMs);
                syncResults.add(taskResult);

                String status = (String) taskResult.get("status");
                if ("SUCCESS".equals(status)) syncSuccess++;
                else if ("SKIPPED".equals(status)) syncSkip++;
                else syncFail++;
            }
        }

        // 2. 数据传输
        List<ErDataTransfer> transferTasks = dataTransferService.selectErDataTransferList(new ErDataTransfer());
        List<Map<String, Object>> transferResults = new ArrayList<>();
        int transferSuccess = 0, transferFail = 0, transferSkip = 0;

        if (transferTasks != null)
        {
            for (ErDataTransfer task : transferTasks)
            {
                Map<String, Object> taskResult = executeTransferTask(task, timeoutMs);
                transferResults.add(taskResult);

                String status = (String) taskResult.get("status");
                if ("SUCCESS".equals(status)) transferSuccess++;
                else if ("SKIPPED".equals(status)) transferSkip++;
                else transferFail++;
            }
        }

        // 汇总
        Map<String, Object> result = new LinkedHashMap<>();

        Map<String, Object> syncSummary = new LinkedHashMap<>();
        syncSummary.put("total", syncTasks != null ? syncTasks.size() : 0);
        syncSummary.put("success", syncSuccess);
        syncSummary.put("failed", syncFail);
        syncSummary.put("skipped", syncSkip);
        syncSummary.put("details", syncResults);
        result.put("schemaSync", syncSummary);

        Map<String, Object> transferSummary = new LinkedHashMap<>();
        transferSummary.put("total", transferTasks != null ? transferTasks.size() : 0);
        transferSummary.put("success", transferSuccess);
        transferSummary.put("failed", transferFail);
        transferSummary.put("skipped", transferSkip);
        transferSummary.put("details", transferResults);
        result.put("dataTransfer", transferSummary);

        int totalTasks = (syncTasks != null ? syncTasks.size() : 0) + (transferTasks != null ? transferTasks.size() : 0);
        int totalSuccess = syncSuccess + transferSuccess;
        int totalFail = syncFail + transferFail;
        result.put("totalTasks", totalTasks);
        result.put("totalSuccess", totalSuccess);
        result.put("totalFailed", totalFail);

        log.info("========== 批量全部测试完成: 架构同步({}/{})  数据传输({}/{}) ==========",
                syncSuccess, syncTasks != null ? syncTasks.size() : 0,
                transferSuccess, transferTasks != null ? transferTasks.size() : 0);

        return success(result);
    }

    /**
     * 查看上次执行结果（查询所有任务当前状态）
     * GET /system/batchtest/status
     */
    @GetMapping("/status")
    public AjaxResult status()
    {
        List<ErDataSync> syncTasks = dataSyncService.selectErDataSyncList(new ErDataSync());
        List<ErDataTransfer> transferTasks = dataTransferService.selectErDataTransferList(new ErDataTransfer());

        List<Map<String, Object>> syncStatus = new ArrayList<>();
        if (syncTasks != null)
        {
            for (ErDataSync t : syncTasks)
            {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("syncId", t.getSyncId());
                m.put("syncName", t.getSyncName());
                m.put("syncStatus", formatStatus(t.getSyncStatus()));
                m.put("lastSyncResult", t.getLastSyncResult());
                m.put("lastSyncTime", t.getLastSyncTime());
                syncStatus.add(m);
            }
        }

        List<Map<String, Object>> transferStatus = new ArrayList<>();
        if (transferTasks != null)
        {
            for (ErDataTransfer t : transferTasks)
            {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("transferId", t.getTransferId());
                m.put("transferName", t.getTransferName());
                m.put("syncStatus", formatStatus(t.getSyncStatus()));
                m.put("lastSyncResult", t.getLastSyncResult());
                m.put("lastSyncTime", t.getLastSyncTime());
                transferStatus.add(m);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaSync", syncStatus);
        result.put("dataTransfer", transferStatus);
        return success(result);
    }

    // ===================== 内部方法 =====================

    /**
     * 执行单个架构同步任务并等待完成
     */
    private Map<String, Object> executeSyncTask(ErDataSync task, long timeoutMs)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("syncId", task.getSyncId());
        result.put("syncName", task.getSyncName());
        result.put("source", task.getSourceDatasourceName());
        result.put("target", task.getTargetDatasourceName());

        long startTime = System.currentTimeMillis();

        // 如果任务正在运行中，跳过
        if ("1".equals(task.getSyncStatus()))
        {
            result.put("status", "SKIPPED");
            result.put("message", "任务正在运行中，跳过");
            result.put("duration", 0);
            log.info("[架构同步] syncId={} 「{}」 跳过（运行中）", task.getSyncId(), task.getSyncName());
            return result;
        }

        try
        {
            log.info("[架构同步] syncId={} 「{}」 开始执行 ({} → {})",
                    task.getSyncId(), task.getSyncName(),
                    task.getSourceDatasourceName(), task.getTargetDatasourceName());

            // 提交异步任务
            Map<String, Object> submitResult = dataSyncService.executeSyncAsync(task.getSyncId());
            if (!Boolean.TRUE.equals(submitResult.get("success")))
            {
                result.put("status", "FAILED");
                result.put("message", "提交失败: " + submitResult.get("message"));
                result.put("duration", System.currentTimeMillis() - startTime);
                log.warn("[架构同步] syncId={} 提交失败: {}", task.getSyncId(), submitResult.get("message"));
                return result;
            }

            // 轮询等待完成
            String finalStatus = pollSyncCompletion(task.getSyncId(), timeoutMs);
            long duration = System.currentTimeMillis() - startTime;

            // 获取最终结果
            ErDataSync finalTask = dataSyncService.getSyncProgress(task.getSyncId());

            if ("2".equals(finalStatus))
            {
                result.put("status", "SUCCESS");
                result.put("message", "同步成功");
                log.info("[架构同步] syncId={} 「{}」 成功 (耗时 {}ms)", task.getSyncId(), task.getSyncName(), duration);
            }
            else if ("TIMEOUT".equals(finalStatus))
            {
                result.put("status", "TIMEOUT");
                result.put("message", "超时未完成");
                log.warn("[架构同步] syncId={} 「{}」 超时 ({}ms)", task.getSyncId(), task.getSyncName(), duration);
            }
            else
            {
                result.put("status", "FAILED");
                result.put("message", finalTask != null ? finalTask.getLastSyncResult() : "状态: " + finalStatus);
                log.error("[架构同步] syncId={} 「{}」 失败 (耗时 {}ms), result={}",
                        task.getSyncId(), task.getSyncName(), duration,
                        finalTask != null ? finalTask.getLastSyncResult() : "N/A");
            }

            result.put("duration", duration);
            result.put("lastSyncResult", finalTask != null ? finalTask.getLastSyncResult() : null);
        }
        catch (Exception e)
        {
            result.put("status", "ERROR");
            result.put("message", "异常: " + e.getMessage());
            result.put("duration", System.currentTimeMillis() - startTime);
            log.error("[架构同步] syncId={} 「{}」 异常", task.getSyncId(), task.getSyncName(), e);
        }

        return result;
    }

    /**
     * 执行单个数据传输任务并等待完成
     */
    private Map<String, Object> executeTransferTask(ErDataTransfer task, long timeoutMs)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transferId", task.getTransferId());
        result.put("transferName", task.getTransferName());
        result.put("source", task.getSourceDatasourceName());
        result.put("target", task.getTargetDatasourceName());
        result.put("syncMode", "0".equals(task.getSyncMode()) ? "全量" : "增量");

        long startTime = System.currentTimeMillis();

        // 如果任务正在运行中，跳过
        if ("1".equals(task.getSyncStatus()))
        {
            result.put("status", "SKIPPED");
            result.put("message", "任务正在运行中，跳过");
            result.put("duration", 0);
            log.info("[数据传输] transferId={} 「{}」 跳过（运行中）", task.getTransferId(), task.getTransferName());
            return result;
        }

        try
        {
            log.info("[数据传输] transferId={} 「{}」 开始执行 ({} → {}, {})",
                    task.getTransferId(), task.getTransferName(),
                    task.getSourceDatasourceName(), task.getTargetDatasourceName(),
                    "0".equals(task.getSyncMode()) ? "全量" : "增量");

            // 提交任务
            Map<String, Object> submitResult = dataTransferService.executeTransfer(task.getTransferId());
            if (!Boolean.TRUE.equals(submitResult.get("success")))
            {
                result.put("status", "FAILED");
                result.put("message", "提交失败: " + submitResult.get("message"));
                result.put("duration", System.currentTimeMillis() - startTime);
                log.warn("[数据传输] transferId={} 提交失败: {}", task.getTransferId(), submitResult.get("message"));
                return result;
            }

            // 轮询等待完成
            String finalStatus = pollTransferCompletion(task.getTransferId(), timeoutMs);
            long duration = System.currentTimeMillis() - startTime;

            // 获取最终结果
            ErDataTransfer finalTask = dataTransferService.getSyncProgress(task.getTransferId());

            if ("2".equals(finalStatus))
            {
                result.put("status", "SUCCESS");
                result.put("message", "传输成功");
                log.info("[数据传输] transferId={} 「{}」 成功 (耗时 {}ms)", task.getTransferId(), task.getTransferName(), duration);
            }
            else if ("TIMEOUT".equals(finalStatus))
            {
                result.put("status", "TIMEOUT");
                result.put("message", "超时未完成");
                log.warn("[数据传输] transferId={} 「{}」 超时 ({}ms)", task.getTransferId(), task.getTransferName(), duration);
            }
            else
            {
                result.put("status", "FAILED");
                result.put("message", finalTask != null ? finalTask.getLastSyncResult() : "状态: " + finalStatus);
                log.error("[数据传输] transferId={} 「{}」 失败 (耗时 {}ms), result={}",
                        task.getTransferId(), task.getTransferName(), duration,
                        finalTask != null ? finalTask.getLastSyncResult() : "N/A");
            }

            result.put("duration", duration);
            result.put("lastSyncResult", finalTask != null ? finalTask.getLastSyncResult() : null);
        }
        catch (Exception e)
        {
            result.put("status", "ERROR");
            result.put("message", "异常: " + e.getMessage());
            result.put("duration", System.currentTimeMillis() - startTime);
            log.error("[数据传输] transferId={} 「{}」 异常", task.getTransferId(), task.getTransferName(), e);
        }

        return result;
    }

    /**
     * 轮询架构同步任务状态直到完成或超时
     *
     * @return 最终状态码（"2"=成功, "3"=失败, "4"=停止, "TIMEOUT"=超时）
     */
    private String pollSyncCompletion(Long syncId, long timeoutMs)
    {
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                Thread.sleep(POLL_INTERVAL);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return "INTERRUPTED";
            }

            ErDataSync task = dataSyncService.getSyncProgress(syncId);
            if (task == null)
            {
                return "NOT_FOUND";
            }

            String status = task.getSyncStatus();
            // "0"=待同步, "1"=同步中 → 继续等待
            // "2"=成功, "3"=失败, "4"=已停止 → 完成
            if (!"0".equals(status) && !"1".equals(status))
            {
                return status;
            }
        }

        return "TIMEOUT";
    }

    /**
     * 轮询数据传输任务状态直到完成或超时
     *
     * @return 最终状态码（"2"=成功, "3"=失败, "4"=停止, "TIMEOUT"=超时）
     */
    private String pollTransferCompletion(Long transferId, long timeoutMs)
    {
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                Thread.sleep(POLL_INTERVAL);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return "INTERRUPTED";
            }

            ErDataTransfer task = dataTransferService.getSyncProgress(transferId);
            if (task == null)
            {
                return "NOT_FOUND";
            }

            String status = task.getSyncStatus();
            if (!"0".equals(status) && !"1".equals(status))
            {
                return status;
            }
        }

        return "TIMEOUT";
    }

    /**
     * 格式化状态码为可读文本
     */
    private String formatStatus(String status)
    {
        if (status == null) return "未知";
        switch (status)
        {
            case "0": return "待执行";
            case "1": return "执行中";
            case "2": return "成功";
            case "3": return "失败";
            case "4": return "已停止";
            default: return "未知(" + status + ")";
        }
    }
}
