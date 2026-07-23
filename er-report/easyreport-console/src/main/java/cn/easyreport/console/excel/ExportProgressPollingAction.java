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

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import cn.easyreport.console.BaseServletAction;
import cn.easyreport.export.ExportTaskManager;
import cn.easyreport.export.ExportTaskManager.ExportTaskProgress;

/**
 * 导出进度轮询接口（替代SSE，更稳定）
 * 客户端定时轮询此接口获取导出进度
 *
 * @since 2026年2月4日
 */
public class ExportProgressPollingAction extends BaseServletAction {

	@Override
	public void execute(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String taskId = req.getParameter("taskId");

		System.out.println("[ExportProgressPollingAction] 接收到轮询请求");
		System.out.println("[ExportProgressPollingAction] taskId参数: " + taskId);

		// 设置响应头
		resp.setContentType("application/json;charset=UTF-8");
		resp.setCharacterEncoding("UTF-8");
		resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
		resp.setHeader("Pragma", "no-cache");
		resp.setHeader("Expires", "0");

		if (taskId == null || taskId.trim().isEmpty()) {
			System.out.println("[ExportProgressPollingAction] taskId为空，返回400错误");
			resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			resp.getWriter().write("{\"error\":\"Missing taskId parameter\"}");
			return;
		}

		// 获取任务进度
		ExportTaskProgress progress = ExportTaskManager.getProgress(taskId);

		if (progress == null) {
			System.out.println("[ExportProgressPollingAction] 任务未找到");
			resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
			resp.getWriter().write("{\"error\":\"Task not found\"}");
			return;
		}

		System.out.println("[ExportProgressPollingAction] 任务状态: " + progress.getStatus() + ", 进度: " + progress.getPercent() + "%");

		// 构建JSON响应
		String json = buildProgressJson(progress);
		resp.getWriter().write(json);
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
		return "/exportProgressPoll";
	}
}
