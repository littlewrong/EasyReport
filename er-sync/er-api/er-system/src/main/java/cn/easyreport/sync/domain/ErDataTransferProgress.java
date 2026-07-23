package cn.easyreport.sync.domain;

import java.util.Date;

/**
 * 每表增量进度表（记录最后同步时间戳）。
 */
public class ErDataTransferProgress {
    private Long id;
    private Long transferId;
    private String sourceTable;      // schema.table
    private String lastSyncValue;    // 毫秒时间戳字符串
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTransferId() { return transferId; }
    public void setTransferId(Long transferId) { this.transferId = transferId; }

    public String getSourceTable() { return sourceTable; }
    public void setSourceTable(String sourceTable) { this.sourceTable = sourceTable; }

    public String getLastSyncValue() { return lastSyncValue; }
    public void setLastSyncValue(String lastSyncValue) { this.lastSyncValue = lastSyncValue; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
