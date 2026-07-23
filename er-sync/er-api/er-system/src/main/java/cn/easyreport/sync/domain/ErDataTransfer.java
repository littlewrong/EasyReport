package cn.easyreport.sync.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import cn.easyreport.common.annotation.Excel;
import cn.easyreport.common.core.domain.BaseEntity;

/**
 * 数据同步任务对象 er_data_transfer
 *
 * @author easyreport
 * @date 2026-01-18
 */
public class ErDataTransfer extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 同步任务ID */
    private Long transferId;

    /** 任务名称 */
    @Excel(name = "任务名称")
    private String transferName;

    /** 任务编码 */
    @Excel(name = "任务编码")
    private String transferCode;

    /** 源数据源ID */
    @Excel(name = "源数据源ID")
    private Long sourceDatasourceId;

    /** 源数据源名称（关联查询） */
    private String sourceDatasourceName;

    /** 源Schema匹配模式 */
    @Excel(name = "源Schema匹配模式")
    private String sourceSchemaPattern;

    /** 源表名 */
    @Excel(name = "源表名")
    private String sourceTable;

    /** 目标数据源ID */
    @Excel(name = "目标数据源ID")
    private Long targetDatasourceId;

    /** 目标数据源名称（关联查询） */
    private String targetDatasourceName;

    /** 目标表名 */
    private String targetTable;

    /** 同步模式（0初始化同步 1增量同步） */
    @Excel(name = "同步模式", readConverterExp = "0=初始化同步,1=增量同步")
    private String syncMode;

    /** 时间戳字段 */
    private String timestampField;

    /** 同步INSERT（0否 1是） */
    private String syncInsert;

    /** 同步UPDATE（0否 1是） */
    private String syncUpdate;

    /** 同步DELETE（0否 1是） */
    private String syncDelete;

    /** 删除保护窗口（最近N年，增量同步删除用） */
    private Integer deleteWindowYears;

    /** 批次大小 */
    private Integer batchSize;

    /** 同步状态（0待同步 1同步中 2同步成功 3同步失败） */
    @Excel(name = "同步状态", readConverterExp = "0=待同步,1=同步中,2=同步成功,3=同步失败")
    private String syncStatus;

    /** 同步进度（JSON格式） */
    private String syncProgress;

    /** 最后同步时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "最后同步时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    private Date lastSyncTime;

    /** 最后同步值 */
    private String lastSyncValue;

    /** 最后同步结果 */
    private String lastSyncResult;

    /** Cron表达式 */
    private String cronExpression;

    /** 关联的定时任务ID */
    private Long jobId;

    /** 状态（0正常 1停用） */
    @Excel(name = "状态", readConverterExp = "0=正常,1=停用")
    private String status;

    public void setTransferId(Long transferId)
    {
        this.transferId = transferId;
    }

    public Long getTransferId()
    {
        return transferId;
    }

    public void setTransferName(String transferName)
    {
        this.transferName = transferName;
    }

    public String getTransferName()
    {
        return transferName;
    }

    public void setTransferCode(String transferCode)
    {
        this.transferCode = transferCode;
    }

    public String getTransferCode()
    {
        return transferCode;
    }

    public void setSourceDatasourceId(Long sourceDatasourceId)
    {
        this.sourceDatasourceId = sourceDatasourceId;
    }

    public Long getSourceDatasourceId()
    {
        return sourceDatasourceId;
    }

    public void setSourceDatasourceName(String sourceDatasourceName)
    {
        this.sourceDatasourceName = sourceDatasourceName;
    }

    public String getSourceDatasourceName()
    {
        return sourceDatasourceName;
    }

    public void setSourceTable(String sourceTable)
    {
        this.sourceTable = sourceTable;
    }

    public String getSourceTable()
    {
        return sourceTable;
    }

    public void setSourceSchemaPattern(String sourceSchemaPattern)
    {
        this.sourceSchemaPattern = sourceSchemaPattern;
    }

    public String getSourceSchemaPattern()
    {
        return sourceSchemaPattern;
    }

    public void setTargetDatasourceId(Long targetDatasourceId)
    {
        this.targetDatasourceId = targetDatasourceId;
    }

    public Long getTargetDatasourceId()
    {
        return targetDatasourceId;
    }

    public void setTargetDatasourceName(String targetDatasourceName)
    {
        this.targetDatasourceName = targetDatasourceName;
    }

    public String getTargetDatasourceName()
    {
        return targetDatasourceName;
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

    public void setTimestampField(String timestampField)
    {
        this.timestampField = timestampField;
    }

    public String getTimestampField()
    {
        return timestampField;
    }

    public void setSyncInsert(String syncInsert)
    {
        this.syncInsert = syncInsert;
    }

    public String getSyncInsert()
    {
        return syncInsert;
    }

    public void setSyncUpdate(String syncUpdate)
    {
        this.syncUpdate = syncUpdate;
    }

    public String getSyncUpdate()
    {
        return syncUpdate;
    }

    public void setSyncDelete(String syncDelete)
    {
        this.syncDelete = syncDelete;
    }

    public String getSyncDelete()
    {
        return syncDelete;
    }

    public Integer getDeleteWindowYears()
    {
        return deleteWindowYears;
    }

    public void setDeleteWindowYears(Integer deleteWindowYears)
    {
        this.deleteWindowYears = deleteWindowYears;
    }

    public void setBatchSize(Integer batchSize)
    {
        this.batchSize = batchSize;
    }

    public Integer getBatchSize()
    {
        return batchSize;
    }

    public void setSyncStatus(String syncStatus)
    {
        this.syncStatus = syncStatus;
    }

    public String getSyncStatus()
    {
        return syncStatus;
    }

    public void setSyncProgress(String syncProgress)
    {
        this.syncProgress = syncProgress;
    }

    public String getSyncProgress()
    {
        return syncProgress;
    }

    public void setLastSyncTime(Date lastSyncTime)
    {
        this.lastSyncTime = lastSyncTime;
    }

    public Date getLastSyncTime()
    {
        return lastSyncTime;
    }

    public void setLastSyncValue(String lastSyncValue)
    {
        this.lastSyncValue = lastSyncValue;
    }

    public String getLastSyncValue()
    {
        return lastSyncValue;
    }

    public void setLastSyncResult(String lastSyncResult)
    {
        this.lastSyncResult = lastSyncResult;
    }

    public String getLastSyncResult()
    {
        return lastSyncResult;
    }

    public void setCronExpression(String cronExpression)
    {
        this.cronExpression = cronExpression;
    }

    public String getCronExpression()
    {
        return cronExpression;
    }

    public void setJobId(Long jobId)
    {
        this.jobId = jobId;
    }

    public Long getJobId()
    {
        return jobId;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public String getStatus()
    {
        return status;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("transferId", getTransferId())
            .append("transferName", getTransferName())
            .append("transferCode", getTransferCode())
            .append("sourceDatasourceId", getSourceDatasourceId())
            .append("sourceSchemaPattern", getSourceSchemaPattern())
            .append("sourceTable", getSourceTable())
            .append("targetDatasourceId", getTargetDatasourceId())
            .append("syncMode", getSyncMode())
            .append("timestampField", getTimestampField())
            .append("syncInsert", getSyncInsert())
            .append("syncUpdate", getSyncUpdate())
            .append("syncDelete", getSyncDelete())
            .append("deleteWindowYears", getDeleteWindowYears())
            .append("batchSize", getBatchSize())
            .append("syncStatus", getSyncStatus())
            .append("syncProgress", getSyncProgress())
            .append("lastSyncTime", getLastSyncTime())
            .append("lastSyncValue", getLastSyncValue())
            .append("lastSyncResult", getLastSyncResult())
            .append("cronExpression", getCronExpression())
            .append("jobId", getJobId())
            .append("status", getStatus())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .toString();
    }
}
