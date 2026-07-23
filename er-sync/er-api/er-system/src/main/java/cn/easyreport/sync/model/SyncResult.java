package cn.easyreport.sync.model;

import cn.easyreport.sync.domain.ErDataSyncLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregated sync result used by service layer.
 */
public class SyncResult {
    private boolean success;
    private boolean stopped; // 是否被停止
    private String message;
    private int totalCount;
    private int successCount;
    private final List<ErDataSyncLog> logs = new ArrayList<>();
    private final List<String> pendingTables = new ArrayList<>(); // 待同步的表
    private final List<String> completedTables = new ArrayList<>(); // 已同步的表

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public boolean isStopped() {
        return stopped;
    }

    public void setStopped(boolean stopped) {
        this.stopped = stopped;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(int totalCount) {
        this.totalCount = totalCount;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(int successCount) {
        this.successCount = successCount;
    }

    public List<ErDataSyncLog> getLogs() {
        return logs;
    }

    public List<String> getPendingTables() {
        return pendingTables;
    }

    public List<String> getCompletedTables() {
        return completedTables;
    }
}
