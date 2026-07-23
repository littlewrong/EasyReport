package cn.easyreport.combinesync.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import cn.easyreport.common.annotation.Excel;
import cn.easyreport.common.core.domain.BaseEntity;

/**
 * 合并同步任务对象 er_combine_sync
 *
 * @author easyreport
 * @date 2026-01-18
 */
public class ErCombineSync extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 合并任务ID */
    private Long combineId;

    /** 任务名称 */
    @Excel(name = "任务名称")
    private String combineName;

    /** 任务编码 */
    @Excel(name = "任务编码")
    private String combineCode;

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

    /** 目标表名（合并后所有数据写入此表） */
    @Excel(name = "目标表名")
    private String targetTable;

    /** 来源列名（标识数据来源，值为库名.表名） */
    @Excel(name = "来源列名")
    private String sourceColumn;

    /** 同步模式（0初始化同步 1增量同步） */
    @Excel(name = "同步模式", readConverterExp = "0=初始化同步,1=增量同步")
    private String syncMode;

    /** 时间戳字段 */
    private String timestampField;

    /** 同步INSERT（0否 1是） */
    private String syncInsert;

    /** 同步UPDATE（0否 1是，StarRocks用INSERT覆盖） */
    private String syncUpdate;

    /** 同步DELETE（0否 1是） */
    private String syncDelete;

    /** 批次大小 */
    private Integer batchSize;

    /** 初始化是否清空目标表（0否 1是） */
    @Excel(name = "初始化清空", readConverterExp = "0=否,1=是")
    private String isClear;

    /** 同步状态（0待同步 1同步中 2同步成功 3同步失败 4已停止） */
    @Excel(name = "同步状态", readConverterExp = "0=待同步,1=同步中,2=同步成功,3=同步失败,4=已停止")
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

    public void setCombineId(Long combineId)
    {
        this.combineId = combineId;
    }

    public Long getCombineId()
    {
        return combineId;
    }

    public void setCombineName(String combineName)
    {
        this.combineName = combineName;
    }

    public String getCombineName()
    {
        return combineName;
    }

    public void setCombineCode(String combineCode)
    {
        this.combineCode = combineCode;
    }

    public String getCombineCode()
    {
        return combineCode;
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

    public void setSourceSchemaPattern(String sourceSchemaPattern)
    {
        this.sourceSchemaPattern = sourceSchemaPattern;
    }

    public String getSourceSchemaPattern()
    {
        return sourceSchemaPattern;
    }

    public void setSourceTable(String sourceTable)
    {
        this.sourceTable = sourceTable;
    }

    public String getSourceTable()
    {
        return sourceTable;
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

    public void setSourceColumn(String sourceColumn)
    {
        this.sourceColumn = sourceColumn;
    }

    public String getSourceColumn()
    {
        return sourceColumn;
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

    public void setBatchSize(Integer batchSize)
    {
        this.batchSize = batchSize;
    }

    public Integer getBatchSize()
    {
        return batchSize;
    }

    public void setIsClear(String isClear)
    {
        this.isClear = isClear;
    }

    public String getIsClear()
    {
        return isClear;
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
            .append("combineId", getCombineId())
            .append("combineName", getCombineName())
            .append("combineCode", getCombineCode())
            .append("sourceDatasourceId", getSourceDatasourceId())
            .append("sourceSchemaPattern", getSourceSchemaPattern())
            .append("sourceTable", getSourceTable())
            .append("targetDatasourceId", getTargetDatasourceId())
            .append("targetTable", getTargetTable())
            .append("sourceColumn", getSourceColumn())
            .append("syncMode", getSyncMode())
            .append("timestampField", getTimestampField())
            .append("syncInsert", getSyncInsert())
            .append("syncUpdate", getSyncUpdate())
            .append("syncDelete", getSyncDelete())
            .append("batchSize", getBatchSize())
            .append("isClear", getIsClear())
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
