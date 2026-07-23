/*******************************************************************************
 * Copyright 2017 Bstek
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
package cn.easyreport.export;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 导出任务管理器
 * 用于管理流式导出任务的进度状态
 *
 * @since 2026年1月15日
 */
public class ExportTaskManager {

	private static final Map<String, ExportTaskProgress> taskMap = new ConcurrentHashMap<>();

	/**
	 * 创建新的导出任务
	 */
	public static String createTask() {
		String taskId = "export_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 10000);
		ExportTaskProgress progress = new ExportTaskProgress();
		progress.setTaskId(taskId);
		progress.setStatus("pending");
		progress.setPercent(0);
		progress.setMessage("准备导出...");
		progress.setStartTime(System.currentTimeMillis());
		taskMap.put(taskId, progress);
		return taskId;
	}

	/**
	 * 更新任务进度
	 */
	public static void updateProgress(String taskId, int current, int total, int percent, String message) {
		ExportTaskProgress progress = taskMap.get(taskId);
		if (progress != null) {
			progress.setCurrent(current);
			progress.setTotal(total);
			progress.setPercent(percent);
			progress.setMessage(message);
			progress.setStatus("running");
		}
	}

	/**
	 * 标记任务完成
	 */
	public static void completeTask(String taskId, int totalRows, String downloadUrl) {
		ExportTaskProgress progress = taskMap.get(taskId);
		if (progress != null) {
			progress.setStatus("completed");
			progress.setPercent(100);
			progress.setMessage("导出完成");
			progress.setTotalRows(totalRows);
			progress.setDownloadUrl(downloadUrl);
			progress.setEndTime(System.currentTimeMillis());
		}
	}

	/**
	 * 标记任务失败
	 */
	public static void failTask(String taskId, String error) {
		ExportTaskProgress progress = taskMap.get(taskId);
		if (progress != null) {
			progress.setStatus("failed");
			progress.setMessage("导出失败: " + error);
			progress.setError(error);
			progress.setEndTime(System.currentTimeMillis());
		}
	}

	/**
	 * 获取任务进度
	 */
	public static ExportTaskProgress getProgress(String taskId) {
		return taskMap.get(taskId);
	}

	/**
	 * 删除任务（导出完成后清理）
	 */
	public static void removeTask(String taskId) {
		taskMap.remove(taskId);
	}

	/**
	 * 清理超过1小时的旧任务
	 */
	public static void cleanupOldTasks() {
		long now = System.currentTimeMillis();
		long oneHour = 60 * 60 * 1000;
		taskMap.entrySet().removeIf(entry -> {
			ExportTaskProgress progress = entry.getValue();
			return progress.getEndTime() > 0 && (now - progress.getEndTime() > oneHour);
		});
	}

	/**
	 * 导出任务进度信息
	 */
	public static class ExportTaskProgress {
		private String taskId;
		private String status; // pending, running, completed, failed
		private int current;
		private int total;
		private int percent;
		private String message;
		private int totalRows;
		private String downloadUrl;
		private String error;
		private long startTime;
		private long endTime;

		public String getTaskId() {
			return taskId;
		}

		public void setTaskId(String taskId) {
			this.taskId = taskId;
		}

		public String getStatus() {
			return status;
		}

		public void setStatus(String status) {
			this.status = status;
		}

		public int getCurrent() {
			return current;
		}

		public void setCurrent(int current) {
			this.current = current;
		}

		public int getTotal() {
			return total;
		}

		public void setTotal(int total) {
			this.total = total;
		}

		public int getPercent() {
			return percent;
		}

		public void setPercent(int percent) {
			this.percent = percent;
		}

		public String getMessage() {
			return message;
		}

		public void setMessage(String message) {
			this.message = message;
		}

		public int getTotalRows() {
			return totalRows;
		}

		public void setTotalRows(int totalRows) {
			this.totalRows = totalRows;
		}

		public String getDownloadUrl() {
			return downloadUrl;
		}

		public void setDownloadUrl(String downloadUrl) {
			this.downloadUrl = downloadUrl;
		}

		public String getError() {
			return error;
		}

		public void setError(String error) {
			this.error = error;
		}

		public long getStartTime() {
			return startTime;
		}

		public void setStartTime(long startTime) {
			this.startTime = startTime;
		}

		public long getEndTime() {
			return endTime;
		}

		public void setEndTime(long endTime) {
			this.endTime = endTime;
		}
	}
}
