package cn.easyreport.sync.core;

/**
 * Progress callback interface for sync operations.
 */
@FunctionalInterface
public interface SyncProgressCallback {
    /**
     * Called when progress is updated.
     *
     * @param current Current progress value
     * @param total Total expected value
     * @param message Progress message
     */
    void onProgress(int current, int total, String message);
}
