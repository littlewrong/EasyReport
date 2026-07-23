package cn.easyreport.sync.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import cn.easyreport.common.annotation.Excel;
import cn.easyreport.common.core.domain.BaseEntity;

/**
 * 数据同步日志对象 er_data_sync_log
 *
 * @author easyreport
 * @date 2026-01-18
 */
public class ErDataSyncLog extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 日志ID */
    private Long logId;

    /** 同步任务ID */
    @Excel(name = "同步任务ID")
    private Long syncId;

    /** 源表名 */
    @Excel(name = "源表名")
    private String sourceTable;

    /** 目标表名 */
    @Excel(name = "目标表名")
    private String targetTable;

    /** 同步操作 */
    @Excel(name = "同步操作")
    private String syncAction;

    /** 执行的SQL语句 */
    private String executeSql;

    /** 同步结果（0成功 1失败） */
    @Excel(name = "同步结果", readConverterExp = "0=成功,1=失败")
    private String syncResult;

    /** 错误信息 */
    private String errorMessage;

    /** 影响行数 */
    @Excel(name = "影响行数")
    private Integer rowsAffected;

    /** 执行耗时（毫秒） */
    @Excel(name = "执行耗时(ms)")
    private Integer executeTime;

    /** 开始时间 */
    private String beginTime;

    /** 结束时间 */
    private String endTime;

    public void setLogId(Long logId)
    {
        this.logId = logId;
    }

    public Long getLogId()
    {
        return logId;
    }

    public void setSyncId(Long syncId)
    {
        this.syncId = syncId;
    }

    public Long getSyncId()
    {
        return syncId;
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

    public void setSyncAction(String syncAction)
    {
        this.syncAction = syncAction;
    }

    public String getSyncAction()
    {
        return syncAction;
    }

    public void setExecuteSql(String executeSql)
    {
        this.executeSql = executeSql;
    }

    public String getExecuteSql()
    {
        return executeSql;
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

    public void setRowsAffected(Integer rowsAffected)
    {
        this.rowsAffected = rowsAffected;
    }

    public Integer getRowsAffected()
    {
        return rowsAffected;
    }

    public void setExecuteTime(Integer executeTime)
    {
        this.executeTime = executeTime;
    }

    public Integer getExecuteTime()
    {
        return executeTime;
    }

    public void setBeginTime(String beginTime)
    {
        this.beginTime = beginTime;
    }

    public String getBeginTime()
    {
        return beginTime;
    }

    public void setEndTime(String endTime)
    {
        this.endTime = endTime;
    }

    public String getEndTime()
    {
        return endTime;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("logId", getLogId())
            .append("syncId", getSyncId())
            .append("sourceTable", getSourceTable())
            .append("targetTable", getTargetTable())
            .append("syncAction", getSyncAction())
            .append("executeSql", getExecuteSql())
            .append("syncResult", getSyncResult())
            .append("errorMessage", getErrorMessage())
            .append("rowsAffected", getRowsAffected())
            .append("executeTime", getExecuteTime())
            .append("createTime", getCreateTime())
            .toString();
    }
}
