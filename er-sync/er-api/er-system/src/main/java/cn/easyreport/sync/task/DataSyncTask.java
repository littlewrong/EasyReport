package cn.easyreport.sync.task;

import cn.easyreport.sync.service.IErDataSyncService;
import cn.easyreport.sync.service.IErDataTransferService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 数据同步定时任务
 *
 * @author easyreport
 * @date 2026-01-29
 */
@Component("dataSyncTask")
public class DataSyncTask
{
    private static final Logger log = LoggerFactory.getLogger(DataSyncTask.class);

    @Autowired
    private IErDataSyncService dataSyncService;

    @Autowired
    private IErDataTransferService dataTransferService;

    /**
     * 执行架构同步（通过任务ID）
     *
     * @param syncId 架构同步任务ID
     */
    public void executeSchemaSyncById(Long syncId)
    {
        log.info("[定时任务] 开始执行架构同步任务，syncId={}", syncId);
        try
        {
            Map<String, Object> result = dataSyncService.executeSyncAsync(syncId);
            if (result != null && (Boolean) result.get("success"))
            {
                log.info("[定时任务] 架构同步任务提交成功，syncId={}", syncId);
            }
            else
            {
                String message = result != null ? (String) result.get("message") : "未知错误";
                log.error("[定时任务] 架构同步任务提交失败，syncId={}, message={}", syncId, message);
            }
        }
        catch (Exception e)
        {
            log.error("[定时任务] 执行架构同步任务异常，syncId={}", syncId, e);
        }
    }

    /**
     * 执行数据传输（通过任务ID）
     *
     * @param transferId 数据传输任务ID
     */
    public void executeDataTransferById(Long transferId)
    {
        log.info("[定时任务] 开始执行数据传输任务，transferId={}", transferId);
        try
        {
            Map<String, Object> result = dataTransferService.executeTransfer(transferId);
            if (result != null && (Boolean) result.get("success"))
            {
                log.info("[定时任务] 数据传输任务提交成功，transferId={}", transferId);
            }
            else
            {
                String message = result != null ? (String) result.get("message") : "未知错误";
                log.error("[定时任务] 数据传输任务提交失败，transferId={}, message={}", transferId, message);
            }
        }
        catch (Exception e)
        {
            log.error("[定时任务] 执行数据传输任务异常，transferId={}", transferId, e);
        }
    }
}
