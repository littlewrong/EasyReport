package cn.easyreport.sync.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import cn.easyreport.common.annotation.Excel;
import cn.easyreport.common.core.domain.BaseEntity;

/**
 * 架构同步任务对象 er_data_sync
 *
 * @author easyreport
 * @date 2026-01-18
 */
public class ErDataSync extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 同步任务ID */
    private Long syncId;

    /** 任务名称 */
    @Excel(name = "任务名称")
    private String syncName;

    /** 任务编码 */
    @Excel(name = "任务编码")
    private String syncCode;

    /** 源数据源ID */
    @Excel(name = "源数据源ID")
    private Long sourceDatasourceId;

    /** 源数据源名称（关联查询） */
    private String sourceDatasourceName;

    /** 源Schema匹配模式 */
    @Excel(name = "源Schema匹配模式")
    private String sourceSchemaPattern;

    /** 源表匹配模式 */
    @Excel(name = "源表匹配模式")
    private String sourceTablePattern;

    /** 匹配方式：0 模糊 1 精确 */
    @Excel(name = "匹配方式", readConverterExp = "0=模糊,1=精确")
    private String matchType;

    /** 目标数据源ID */
    @Excel(name = "目标数据源ID")
    private Long targetDatasourceId;

    /** 目标数据源名称（关联查询） */
    private String targetDatasourceName;

    /** 目标表已存在时（0跳过 1删除重建） */
    @Excel(name = "已存在处理", readConverterExp = "0=跳过,1=删除重建")
    private String ifExistsAction;

    /** 同步状态（0待同步 1同步中 2同步成功 3同步失败） */
    @Excel(name = "同步状态", readConverterExp = "0=待同步,1=同步中,2=同步成功,3=同步失败")
    private String syncStatus;

    /** 同步进度（JSON格式） */
    private String syncProgress;

    /** 最后同步时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "最后同步时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    private Date lastSyncTime;

    /** 最后同步结果 */
    private String lastSyncResult;

    /** Cron表达式 */
    @Excel(name = "Cron表达式")
    private String cronExpression;

    /** Quartz任务ID */
    private Long jobId;

    /** 状态（0正常 1停用） */
    @Excel(name = "状态", readConverterExp = "0=正常,1=停用")
    private String status;

    public void setSyncId(Long syncId)
    {
        this.syncId = syncId;
    }

    public Long getSyncId()
    {
        return syncId;
    }

    public void setSyncName(String syncName)
    {
        this.syncName = syncName;
    }

    public String getSyncName()
    {
        return syncName;
    }

    public void setSyncCode(String syncCode)
    {
        this.syncCode = syncCode;
    }

    public String getSyncCode()
    {
        return syncCode;
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

    public void setSourceTablePattern(String sourceTablePattern)
    {
        this.sourceTablePattern = sourceTablePattern;
    }

    public String getSourceTablePattern()
    {
        return sourceTablePattern;
    }

    public void setMatchType(String matchType)
    {
        this.matchType = matchType;
    }

    public String getMatchType()
    {
        return matchType;
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

    public void setIfExistsAction(String ifExistsAction)
    {
        this.ifExistsAction = ifExistsAction;
    }

    public String getIfExistsAction()
    {
        return ifExistsAction;
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

    public void setLastSyncResult(String lastSyncResult)
    {
        this.lastSyncResult = lastSyncResult;
    }

    public String getLastSyncResult()
    {
        return lastSyncResult;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public String getStatus()
    {
        return status;
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

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("syncId", getSyncId())
            .append("syncName", getSyncName())
            .append("syncCode", getSyncCode())
            .append("sourceDatasourceId", getSourceDatasourceId())
            .append("sourceSchemaPattern", getSourceSchemaPattern())
            .append("sourceTablePattern", getSourceTablePattern())
            .append("matchType", getMatchType())
            .append("targetDatasourceId", getTargetDatasourceId())
            .append("ifExistsAction", getIfExistsAction())
            .append("syncStatus", getSyncStatus())
            .append("syncProgress", getSyncProgress())
            .append("lastSyncTime", getLastSyncTime())
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
