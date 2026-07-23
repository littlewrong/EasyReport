package cn.easyreport.combinesync.domain;

import java.util.Date;

/**
 * 合并同步每表进度（记录最后同步时间戳）。
 */
public class ErCombineSyncProgress {
    private Long id;
    private Long combineId;
    private String sourceTable;      // 库名.表名
    private String lastSyncValue;    // 毫秒时间戳字符串
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCombineId() { return combineId; }
    public void setCombineId(Long combineId) { this.combineId = combineId; }

    public String getSourceTable() { return sourceTable; }
    public void setSourceTable(String sourceTable) { this.sourceTable = sourceTable; }

    public String getLastSyncValue() { return lastSyncValue; }
    public void setLastSyncValue(String lastSyncValue) { this.lastSyncValue = lastSyncValue; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
