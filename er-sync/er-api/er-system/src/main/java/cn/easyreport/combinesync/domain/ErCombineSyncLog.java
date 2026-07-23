package cn.easyreport.combinesync.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import cn.easyreport.common.annotation.Excel;
import cn.easyreport.common.core.domain.BaseEntity;

/**
 * 合并同步日志对象 er_combine_sync_log
 *
 * @author easyreport
 * @date 2026-01-18
 */
public class ErCombineSyncLog extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 日志ID */
    private Long logId;

    /** 合并任务ID */
    @Excel(name = "合并任务ID")
    private Long combineId;

    /** 源库名 */
    @Excel(name = "源库名")
    private String sourceDatabase;

    /** 源表名（库名.表名） */
    @Excel(name = "源表名")
    private String sourceTable;

    /** 目标表名 */
    @Excel(name = "目标表名")
    private String targetTable;

    /** 同步模式（0初始化 1增量） */
    @Excel(name = "同步模式", readConverterExp = "0=初始化,1=增量")
    private String syncMode;

    /** 同步动作 */
    @Excel(name = "同步动作")
    private String syncAction;

    /** 总记录数 */
    @Excel(name = "总记录数")
    private Long totalCount;

    /** 成功记录数 */
    @Excel(name = "成功记录数")
    private Long successCount;

    /** 失败记录数 */
    @Excel(name = "失败记录数")
    private Long failCount;

    /** 开始时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "开始时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    private Date startTime;

    /** 结束时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "结束时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    private Date endTime;

    /** 执行耗时（毫秒） */
    @Excel(name = "执行耗时(ms)")
    private Long executeTime;

    /** 同步结果（0成功 1失败） */
    @Excel(name = "同步结果", readConverterExp = "0=成功,1=失败")
    private String syncResult;

    /** 错误信息 */
    @Excel(name = "错误信息")
    private String errorMessage;

    /** 本次同步的最后时间戳值 */
    private String lastSyncValue;

    public void setLogId(Long logId)
    {
        this.logId = logId;
    }

    public Long getLogId()
    {
        return logId;
    }

    public void setCombineId(Long combineId)
    {
        this.combineId = combineId;
    }

    public Long getCombineId()
    {
        return combineId;
    }

    public void setSourceDatabase(String sourceDatabase)
    {
        this.sourceDatabase = sourceDatabase;
    }

    public String getSourceDatabase()
    {
        return sourceDatabase;
    }

    public void setSourceTable(String sourceTable)
    {
        this.sourceTable = sourceTable;
    }

    public String getSourceTable()
    {
        return sourceTable;
    }

    public void setTargetTable(String targetTable)
    {
        this.targetTable = targetTable;
    }

    public String getTargetTable()
    {
        return targetTable;
    }

    public void setSyncMode(String syncMode)
    {
        this.syncMode = syncMode;
    }

    public String getSyncMode()
    {
        return syncMode;
    }

    public void setSyncAction(String syncAction)
    {
        this.syncAction = syncAction;
    }

    public String getSyncAction()
    {
        return syncAction;
    }

    public void setTotalCount(Long totalCount)
    {
        this.totalCount = totalCount;
    }

    public Long getTotalCount()
    {
        return totalCount;
    }

    public void setSuccessCount(Long successCount)
    {
        this.successCount = successCount;
    }

    public Long getSuccessCount()
    {
        return successCount;
    }

    public void setFailCount(Long failCount)
    {
        this.failCount = failCount;
    }

    public Long getFailCount()
    {
        return failCount;
    }

    public void setStartTime(Date startTime)
    {
        this.startTime = startTime;
    }

    public Date getStartTime()
    {
        return startTime;
    }

    public void setEndTime(Date endTime)
    {
        this.endTime = endTime;
    }

    public Date getEndTime()
    {
        return endTime;
    }

    public void setExecuteTime(Long executeTime)
    {
        this.executeTime = executeTime;
    }

    public Long getExecuteTime()
    {
        return executeTime;
    }

    public void setSyncResult(String syncResult)
    {
        this.syncResult = syncResult;
    }

    public String getSyncResult()
    {
        return syncResult;
    }

    public void setErrorMessage(String errorMessage)
    {
        this.errorMessage = errorMessage;
    }

    public String getErrorMessage()
    {
        return errorMessage;
    }

    public void setLastSyncValue(String lastSyncValue)
    {
        this.lastSyncValue = lastSyncValue;
    }

    public String getLastSyncValue()
    {
        return lastSyncValue;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("logId", getLogId())
            .append("combineId", getCombineId())
            .append("sourceDatabase", getSourceDatabase())
            .append("sourceTable", getSourceTable())
            .append("targetTable", getTargetTable())
            .append("syncMode", getSyncMode())
            .append("syncAction", getSyncAction())
            .append("totalCount", getTotalCount())
            .append("successCount", getSuccessCount())
            .append("failCount", getFailCount())
            .append("startTime", getStartTime())
            .append("endTime", getEndTime())
            .append("executeTime", getExecuteTime())
            .append("syncResult", getSyncResult())
            .append("errorMessage", getErrorMessage())
            .append("lastSyncValue", getLastSyncValue())
            .append("createTime", getCreateTime())
            .toString();
    }
}
