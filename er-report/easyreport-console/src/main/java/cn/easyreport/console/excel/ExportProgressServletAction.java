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
package cn.easyreport.console.excel;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import cn.easyreport.console.BaseServletAction;
import cn.easyreport.export.ExportTaskManager;
import cn.easyreport.export.ExportTaskManager.ExportTaskProgress;

/**
 * 导出进度SSE推送服务
 * 使用Server-Sent Events向前端推送导出进度
 *
 * @since 2026年1月15日
 */
public class ExportProgressServletAction extends BaseServletAction {

	@Override
	public void execute(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String taskId = req.getParameter("taskId");
		if (taskId == null || taskId.trim().isEmpty()) {
			resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			resp.getWriter().write("Missing taskId parameter");
			return;
		}

		// 设置SSE响应头
		resp.setContentType("text/event-stream");
		resp.setCharacterEncoding("UTF-8");
		resp.setHeader("Cache-Control", "no-cache");
		resp.setHeader("Connection", "keep-alive");
		resp.setHeader("Access-Control-Allow-Origin", "*");

		PrintWriter writer = resp.getWriter();

		try {
			// 持续推送进度，直到任务完成或失败
			while (true) {
				ExportTaskProgress progress = ExportTaskManager.getProgress(taskId);

				if (progress == null) {
					// 任务不存在
					sendEvent(writer, "error", "{\"message\":\"Task not found\"}");
					break;
				}

				// 构建进度JSON
				String progressJson = buildProgressJson(progress);
				sendEvent(writer, "progress", progressJson);

				// 如果任务已完成或失败，结束推送
				if ("completed".equals(progress.getStatus()) || "failed".equals(progress.getStatus())) {
					break;
				}

				// 每隔500毫秒推送一次
				Thread.sleep(500);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			sendEvent(writer, "error", "{\"message\":\"" + escapeJson(e.getMessage()) + "\"}");
		} finally {
			writer.close();
		}
	}

	/**
	 * 发送SSE事件
	 */
	private void sendEvent(PrintWriter writer, String event, String data) {
		writer.write("event: " + event + "\n");
		writer.write("data: " + data + "\n\n");
		writer.flush();
	}

	/**
	 * 构建进度JSON
	 */
	private String buildProgressJson(ExportTaskProgress progress) {
		StringBuilder json = new StringBuilder();
		json.append("{");
		json.append("\"taskId\":\"").append(escapeJson(progress.getTaskId())).append("\",");
		json.append("\"status\":\"").append(escapeJson(progress.getStatus())).append("\",");
		json.append("\"current\":").append(progress.getCurrent()).append(",");
		json.append("\"total\":").append(progress.getTotal()).append(",");
		json.append("\"percent\":").append(progress.getPercent()).append(",");
		json.append("\"message\":\"").append(escapeJson(progress.getMessage())).append("\"");

		if (progress.getDownloadUrl() != null) {
			json.append(",\"downloadUrl\":\"").append(escapeJson(progress.getDownloadUrl())).append("\"");
		}
		if (progress.getError() != null) {
			json.append(",\"error\":\"").append(escapeJson(progress.getError())).append("\"");
		}

		json.append("}");
		return json.toString();
	}

	/**
	 * 转义JSON字符串
	 */
	private String escapeJson(String str) {
		if (str == null) return "";
		return str.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t");
	}

	@Override
	public String url() {
		return "/exportProgress";
	}
}
