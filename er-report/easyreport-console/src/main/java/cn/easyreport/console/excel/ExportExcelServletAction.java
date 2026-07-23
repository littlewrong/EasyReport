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
import java.io.OutputStream;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.StringUtils;

import cn.easyreport.build.ReportBuilder;
import cn.easyreport.console.BaseServletAction;
import cn.easyreport.console.cache.TempObjectCache;
import cn.easyreport.console.exception.ReportDesignException;
import cn.easyreport.definition.ReportDefinition;
import cn.easyreport.exception.ReportComputeException;
import cn.easyreport.exception.ReportException;
import cn.easyreport.export.ExportConfigure;
import cn.easyreport.export.ExportConfigureImpl;
import cn.easyreport.export.ExportManager;
import cn.easyreport.export.excel.high.ExcelProducer;
import cn.easyreport.model.Report;

/**
 * @author Jacky.gao
 * @since 2017年4月17日
 */
public class ExportExcelServletAction extends BaseServletAction {
	private ReportBuilder reportBuilder;
	private ExportManager exportManager;
	private cn.easyreport.export.ReportRender reportRender;
	private ExcelProducer excelProducer=new ExcelProducer();
	
	@Override
	public void execute(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String method=retriveMethod(req);
		if(method!=null){
			invokeMethod(method, req, resp);
		}else{			
			buildExcel(req, resp,false,false);
		}
	}
	public void paging(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		buildExcel(req, resp, true, false);
	}
	
	public void sheet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		buildExcel(req, resp, false, true);
	}
	
	public void buildExcel(HttpServletRequest req, HttpServletResponse resp,boolean withPage,boolean withSheet) throws IOException {
		String file=req.getParameter("_u");
		file=decode(file);
		if(StringUtils.isBlank(file)){
			throw new ReportComputeException("Report file can not be null.");
		}

		// 注意：流式导出已作为可选功能提供
		// 当前所有标准导出都使用普通方式，但已优化内存窗口（SXSSFWorkbook(100)）
		// 流式导出（仅原始数据，无样式）通过前端UI选择或手动调用：
		// /easyreport/excel/streamingExport?_u=p&page=1&page_size=100000

		OutputStream outputStream=resp.getOutputStream();
		try {
			String fileName=req.getParameter("_n");
			fileName=buildDownloadFileName(file, fileName, ".xlsx");
			resp.setContentType("application/octet-stream;charset=ISO8859-1");
			fileName=new String(fileName.getBytes("UTF-8"),"ISO8859-1");
			resp.setHeader("Content-Disposition","attachment;filename=\"" + fileName + "\"");
			Map<String, Object> parameters = buildParameters(req);
			if(file.equals(PREVIEW_KEY)){
				ReportDefinition reportDefinition=(ReportDefinition)TempObjectCache.getObject(PREVIEW_KEY);
				if(reportDefinition==null){
					throw new ReportDesignException("Report data has expired,can not do export excel.");
				}
				Report report=reportBuilder.buildReport(reportDefinition, parameters);	
				if(withPage){
					excelProducer.produceWithPaging(report, outputStream);
				}else if(withSheet){
					excelProducer.produceWithSheet(report, outputStream);
				}else{
					excelProducer.produce(report, outputStream);				
				}
			}else{
				ExportConfigure configure=new ExportConfigureImpl(file,parameters,outputStream);
				if(withPage){
					exportManager.exportExcelWithPaging(configure);
				}else if(withSheet){
					exportManager.exportExcelWithPagingSheet(configure);
				}else{
					exportManager.exportExcel(configure);
				}
			}
		}catch(Exception ex) {
			throw new ReportException(ex);
		}finally {
			outputStream.flush();
			outputStream.close();			
		}
	}
	
	/**
	 * 流式导出Excel（用于大数据量，10万+）
	 * 通过分批查询和边查边写，避免内存溢出
	 */
	public void streamingExport(HttpServletRequest req, HttpServletResponse resp)
		throws ServletException, IOException {

		String file = req.getParameter("_u");
		file = decode(file);

		if (StringUtils.isBlank(file)) {
			throw new ReportComputeException("Report file can not be null.");
		}

		OutputStream outputStream = resp.getOutputStream();

		try {
			// 获取用户指定的分页参数
			String pageStr = req.getParameter("page");
			String pageSizeStr = req.getParameter("page_size");

			int userPage = 1;
			int userPageSize = 100000;

			if (pageStr != null && !pageStr.isEmpty()) {
				userPage = Integer.parseInt(pageStr);
			}
			if (pageSizeStr != null && !pageSizeStr.isEmpty()) {
				userPageSize = Integer.parseInt(pageSizeStr);
			}

			// 获取日期参数（用于按日期循环导出）
			String startDate = req.getParameter("_startDate");
			String endDate = req.getParameter("_endDate");
			String startFieldName = req.getParameter("_startFieldName");
			String endFieldName = req.getParameter("_endFieldName");

			// 详细日志：输出所有接收到的参数
			cn.easyreport.Utils.logToConsole("========== 接收到的日期参数 ==========");
			cn.easyreport.Utils.logToConsole("_startDate = " + startDate);
			cn.easyreport.Utils.logToConsole("_endDate = " + endDate);
			cn.easyreport.Utils.logToConsole("_startFieldName = " + startFieldName);
			cn.easyreport.Utils.logToConsole("_endFieldName = " + endFieldName);

			// 输出所有请求参数以便调试
			java.util.Enumeration<String> paramNames = req.getParameterNames();
			cn.easyreport.Utils.logToConsole("========== 所有请求参数 ==========");
			while (paramNames.hasMoreElements()) {
				String paramName = paramNames.nextElement();
				String paramValue = req.getParameter(paramName);
				cn.easyreport.Utils.logToConsole(paramName + " = " + paramValue);
			}
			cn.easyreport.Utils.logToConsole("=====================================");

			String fileName = req.getParameter("_n");
			fileName = buildDownloadFileName(file, fileName, ".xlsx");
			resp.setContentType("application/octet-stream;charset=ISO8859-1");
			fileName = new String(fileName.getBytes("UTF-8"), "ISO8859-1");
			resp.setHeader("Content-Disposition", "attachment;filename=\"" + fileName + "\"");

			Map<String, Object> parameters = buildParameters(req);

			// 加载报表定义
			ReportDefinition reportDefinition = null;
			if (file.equals(PREVIEW_KEY)) {
				// 从缓存加载（设计器预览模式）
				reportDefinition = (ReportDefinition) TempObjectCache.getObject(PREVIEW_KEY);
				if (reportDefinition == null) {
					throw new ReportDesignException("Report data has expired, can not do streaming export.");
				}
			} else {
				// 从文件加载（直接打开预览页面）
				reportDefinition = reportRender.getReportDefinition(file);
				if (reportDefinition == null) {
					throw new ReportComputeException("Report file not found: " + file);
				}
			}

			// 查找HTTP数据源
			cn.easyreport.definition.datasource.HttpDatasourceDefinition httpDs = findHttpDatasource(reportDefinition);
			if (httpDs == null) {
				throw new ReportComputeException("未找到HTTP数据源，流式导出仅支持HTTP数据源");
			}

			java.util.List<cn.easyreport.definition.dataset.DatasetDefinition> datasets = httpDs.getDatasets();
			if (datasets == null || datasets.isEmpty()) {
				throw new ReportComputeException("HTTP数据源中没有数据集");
			}

			// 按名称查找主数据集（为空则取第一个）
			cn.easyreport.definition.Paper paper = reportDefinition.getPaper();
			String mainDatasetName = paper != null ? paper.getApiDatasetName() : null;
			cn.easyreport.definition.dataset.HttpDatasetDefinition httpDataset =
				findHttpDataset(datasets, mainDatasetName);
			if (httpDataset == null) {
				throw new ReportComputeException("未找到主数据集" + (mainDatasetName != null ? ": " + mainDatasetName : ""));
			}

			// 查找汇总数据集（如果启用）
			cn.easyreport.definition.dataset.HttpDatasetDefinition summaryDataset = null;
			cn.easyreport.Utils.logToConsole("========== 汇总配置检查 ==========");
			cn.easyreport.Utils.logToConsole("paper=" + (paper != null));
			cn.easyreport.Utils.logToConsole("apiSummaryEnabled=" + (paper != null ? paper.isApiSummaryEnabled() : "N/A"));
			cn.easyreport.Utils.logToConsole("apiSummaryDatasetName=" + (paper != null ? paper.getApiSummaryDatasetName() : "N/A"));
			cn.easyreport.Utils.logToConsole("apiSummaryFieldMapping=" + (paper != null ? paper.getApiSummaryFieldMapping() : "N/A"));
			cn.easyreport.Utils.logToConsole("数据集总数=" + datasets.size());
			for (int i = 0; i < datasets.size(); i++) {
				cn.easyreport.definition.dataset.DatasetDefinition ds = datasets.get(i);
				cn.easyreport.Utils.logToConsole("数据集[" + i + "]: " + (ds instanceof cn.easyreport.definition.dataset.HttpDatasetDefinition ? ((cn.easyreport.definition.dataset.HttpDatasetDefinition)ds).getName() : ds.getClass().getSimpleName()));
			}
			if (paper != null && paper.isApiSummaryEnabled()) {
				String summaryName = paper.getApiSummaryDatasetName();
				if (summaryName != null && !summaryName.isEmpty()) {
					summaryDataset = findHttpDataset(datasets, summaryName);
					if (summaryDataset == null) {
						summaryDataset = findHttpDatasetFromAllSources(reportDefinition, summaryName);
					}
					cn.easyreport.Utils.logToConsole(summaryDataset != null ? "找到汇总数据集: " + summaryName : "未找到汇总数据集: " + summaryName);
				}
			}
			cn.easyreport.Utils.logToConsole("最终summaryDataset=" + (summaryDataset != null ? "有" : "null"));

			// 创建进度回调（记录日志）
			cn.easyreport.export.excel.high.ExportProgressCallback progressCallback =
				new cn.easyreport.export.excel.high.ExportProgressCallback() {
					@Override
					public void onProgress(int current, int total, int percent, String message) {
						cn.easyreport.Utils.logToConsole(String.format("[进度] %d%% - %s", percent, message));
					}

					@Override
					public void onComplete(int totalRows) {
						cn.easyreport.Utils.logToConsole(String.format("[完成] 共导出 %d 行数据", totalRows));
					}

					@Override
					public void onError(String error) {
						cn.easyreport.Utils.logToConsole(String.format("[错误] %s", error));
					}
				};

			// 使用流式导出器
			cn.easyreport.export.excel.high.builder.ExcelBuilderStreaming streamingBuilder =
				new cn.easyreport.export.excel.high.builder.ExcelBuilderStreaming();

			streamingBuilder.buildStreaming(
				httpDataset,
				parameters,
				userPage,
				userPageSize,
				outputStream,
				progressCallback,
				startDate,
				endDate,
				startFieldName,
				endFieldName,
				paper,
				summaryDataset
			);

		} catch (Exception ex) {
			throw new ReportException(ex);
		} finally {
			outputStream.flush();
			outputStream.close();
		}
	}

	/**
	 * 流式导出（异步，支持SSE进度推送）
	 * 立即返回taskId，后台异步执行导出
	 */
	public void streamingExportAsync(HttpServletRequest req, HttpServletResponse resp)
		throws ServletException, IOException {

		String file = req.getParameter("_u");
		file = decode(file);

		if (StringUtils.isBlank(file)) {
			throw new ReportComputeException("Report file can not be null.");
		}

		try {
			// 创建导出任务
			final String taskId = cn.easyreport.export.ExportTaskManager.createTask();

			// 获取用户指定的分页参数
			String pageStr = req.getParameter("page");
			String pageSizeStr = req.getParameter("page_size");

			final int userPage = (pageStr != null && !pageStr.isEmpty()) ? Integer.parseInt(pageStr) : 1;
			final int userPageSize = (pageSizeStr != null && !pageSizeStr.isEmpty()) ? Integer.parseInt(pageSizeStr) : 100000;

			// ========== 直接从报表定义和URL参数中提取日期信息 ==========
			String startDate = null;
			String endDate = null;
			String startFieldName = null;
			String endFieldName = null;

			cn.easyreport.Utils.logToConsole("========== [Async] 开始从报表定义提取日期配置 ==========");

			String fileName = req.getParameter("_n");
			final String finalFileName = buildDownloadFileName(file, fileName, ".xlsx");

			final Map<String, Object> parameters = buildParameters(req);

			// *** 关键修复：在主线程中立即加载报表定义，避免后台线程无法访问Session ***
			ReportDefinition reportDefinition = null;
			if (file.equals(PREVIEW_KEY)) {
				reportDefinition = (ReportDefinition) TempObjectCache.getObject(PREVIEW_KEY);
				if (reportDefinition == null) {
					throw new ReportDesignException("Report data has expired, can not do streaming export.");
				}
			} else {
				reportDefinition = reportRender.getReportDefinition(file);
				if (reportDefinition == null) {
					throw new ReportComputeException("Report file not found: " + file);
				}
			}

			// ========== 从报表定义中读取日期字段配置 ==========
			if (reportDefinition.getPaper() != null) {
				cn.easyreport.definition.Paper paper = reportDefinition.getPaper();
				startFieldName = paper.getApiStartFieldName();
				endFieldName = paper.getApiEndFieldName();

				cn.easyreport.Utils.logToConsole("从Paper读取配置: startFieldName = " + startFieldName);
				cn.easyreport.Utils.logToConsole("从Paper读取配置: endFieldName = " + endFieldName);

				// 如果配置了日期字段名，从URL参数中提取对应的日期值
				if (startFieldName != null && !startFieldName.isEmpty()) {
					startDate = req.getParameter(startFieldName);
					cn.easyreport.Utils.logToConsole("从URL参数提取: " + startFieldName + " = " + startDate);
				}

				if (endFieldName != null && !endFieldName.isEmpty()) {
					endDate = req.getParameter(endFieldName);
					cn.easyreport.Utils.logToConsole("从URL参数提取: " + endFieldName + " = " + endDate);
				}

				cn.easyreport.Utils.logToConsole("最终日期参数: startDate = " + startDate + ", endDate = " + endDate);
			} else {
				cn.easyreport.Utils.logToConsole("报表定义中没有Paper对象");
			}

			// 转换为final变量供lambda使用
			final String finalStartDate = startDate;
			final String finalEndDate = endDate;
			final String finalStartFieldName = startFieldName;
			final String finalEndFieldName = endFieldName;
		final cn.easyreport.definition.Paper finalPaper = reportDefinition.getPaper();

			// 在主线程中查找HTTP数据源
			cn.easyreport.definition.datasource.HttpDatasourceDefinition httpDs = findHttpDatasource(reportDefinition);
			if (httpDs == null) {
				throw new ReportComputeException("未找到HTTP数据源，流式导出仅支持HTTP数据源");
			}

			java.util.List<cn.easyreport.definition.dataset.DatasetDefinition> datasets = httpDs.getDatasets();
			if (datasets == null || datasets.isEmpty()) {
				throw new ReportComputeException("HTTP数据源中没有数据集");
			}

			// 按名称查找主数据集（为空则取第一个）
			String mainDatasetName = finalPaper != null ? finalPaper.getApiDatasetName() : null;
			final cn.easyreport.definition.dataset.HttpDatasetDefinition httpDataset =
				findHttpDataset(datasets, mainDatasetName);
			if (httpDataset == null) {
				throw new ReportComputeException("未找到主数据集" + (mainDatasetName != null ? ": " + mainDatasetName : ""));
			}

			// 查找汇总数据集（如果启用）
			cn.easyreport.definition.dataset.HttpDatasetDefinition summaryDs = null;
			cn.easyreport.Utils.logToConsole("========== [异步] 汇总配置检查 ==========");
			cn.easyreport.Utils.logToConsole("apiSummaryEnabled=" + (finalPaper != null ? finalPaper.isApiSummaryEnabled() : "N/A"));
			cn.easyreport.Utils.logToConsole("apiSummaryDatasetName=" + (finalPaper != null ? finalPaper.getApiSummaryDatasetName() : "N/A"));
			cn.easyreport.Utils.logToConsole("apiSummaryFieldMapping=" + (finalPaper != null ? finalPaper.getApiSummaryFieldMapping() : "N/A"));
			for (int i = 0; i < datasets.size(); i++) {
				cn.easyreport.definition.dataset.DatasetDefinition ds = datasets.get(i);
				if (ds instanceof cn.easyreport.definition.dataset.HttpDatasetDefinition) {
					cn.easyreport.Utils.logToConsole("数据集[" + i + "].name=" + ((cn.easyreport.definition.dataset.HttpDatasetDefinition)ds).getName());
				}
			}
			if (finalPaper != null && finalPaper.isApiSummaryEnabled()) {
				String summaryName = finalPaper.getApiSummaryDatasetName();
				if (summaryName != null && !summaryName.isEmpty()) {
					// 先在当前数据源找，找不到则遍历所有HTTP数据源
					summaryDs = findHttpDataset(datasets, summaryName);
					if (summaryDs == null) {
						summaryDs = findHttpDatasetFromAllSources(reportDefinition, summaryName);
					}
					cn.easyreport.Utils.logToConsole("查找汇总数据集[" + summaryName + "]结果: " + (summaryDs != null ? "找到" : "未找到"));
				}
			}
			final cn.easyreport.definition.dataset.HttpDatasetDefinition finalSummaryDataset = summaryDs;
			cn.easyreport.Utils.logToConsole("最终finalSummaryDataset=" + (finalSummaryDataset != null ? "有" : "null"));

			// 异步执行导出
			new Thread(new Runnable() {
				@Override
				public void run() {
					java.io.FileOutputStream fos = null;
					try {
						// 创建临时文件
						java.io.File tempDir = new java.io.File(System.getProperty("java.io.tmpdir"), "easyreport_export");
						if (!tempDir.exists()) {
							tempDir.mkdirs();
						}
						java.io.File tempFile = new java.io.File(tempDir, taskId + ".xlsx");
						fos = new java.io.FileOutputStream(tempFile);

						// 创建进度回调（更新到TaskManager）
						cn.easyreport.export.excel.high.ExportProgressCallback progressCallback =
							new cn.easyreport.export.excel.high.ExportProgressCallback() {
								@Override
								public void onProgress(int current, int total, int percent, String message) {
									cn.easyreport.export.ExportTaskManager.updateProgress(taskId, current, total, percent, message);
									cn.easyreport.Utils.logToConsole(String.format("[进度] %d%% - %s", percent, message));
								}

								@Override
								public void onComplete(int totalRows) {
									cn.easyreport.Utils.logToConsole(String.format("[完成] 共导出 %d 行数据", totalRows));
								}

								@Override
								public void onError(String error) {
									cn.easyreport.Utils.logToConsole(String.format("[错误] %s", error));
								}
							};

						// 使用流式导出器
						cn.easyreport.export.excel.high.builder.ExcelBuilderStreaming streamingBuilder =
							new cn.easyreport.export.excel.high.builder.ExcelBuilderStreaming();

						streamingBuilder.buildStreaming(
							httpDataset,
							parameters,
							userPage,
							userPageSize,
							fos,
							progressCallback,
							finalStartDate,
							finalEndDate,
							finalStartFieldName,
							finalEndFieldName,
							finalPaper,
							finalSummaryDataset
						);

						// 标记任务完成
						// 使用路径格式而非查询参数: /excel/download?taskId=xxx&fileName=xxx
						String downloadUrl = "/excel/download?taskId=" + taskId + "&fileName=" + java.net.URLEncoder.encode(finalFileName, "UTF-8");
						cn.easyreport.export.ExportTaskManager.completeTask(taskId, userPageSize, downloadUrl);

					} catch (Exception ex) {
						cn.easyreport.export.ExportTaskManager.failTask(taskId, ex.getMessage());
						ex.printStackTrace();
					} finally {
						if (fos != null) {
							try {
								fos.close();
							} catch (Exception e) {
								e.printStackTrace();
							}
						}
					}
				}
			}).start();

			// 立即返回任务ID
			resp.setContentType("application/json;charset=UTF-8");
			resp.getWriter().write("{\"taskId\":\"" + taskId + "\"}");

		} catch (Exception ex) {
			throw new ReportException(ex);
		}
	}

	/**
	 * 下载已完成的导出文件
	 * URL路由: /excel/download?taskId=xxx&fileName=xxx
	 */
	public void download(HttpServletRequest req, HttpServletResponse resp)
		throws ServletException, IOException {

		String taskId = req.getParameter("taskId");
		String fileName = req.getParameter("fileName");

		if (StringUtils.isBlank(taskId)) {
			throw new ReportComputeException("Task ID can not be null.");
		}

		try {
			java.io.File tempDir = new java.io.File(System.getProperty("java.io.tmpdir"), "easyreport_export");
			java.io.File tempFile = new java.io.File(tempDir, taskId + ".xlsx");

			if (!tempFile.exists()) {
				throw new ReportComputeException("Export file not found or has been deleted.");
			}

			resp.setContentType("application/octet-stream;charset=ISO8859-1");
			if (fileName != null && !fileName.isEmpty()) {
				fileName = new String(fileName.getBytes("UTF-8"), "ISO8859-1");
			} else {
				fileName = "export.xlsx";
			}
			resp.setHeader("Content-Disposition", "attachment;filename=\"" + fileName + "\"");

			java.io.FileInputStream fis = new java.io.FileInputStream(tempFile);
			java.io.OutputStream outputStream = resp.getOutputStream();

			byte[] buffer = new byte[8192];
			int bytesRead;
			while ((bytesRead = fis.read(buffer)) != -1) {
				outputStream.write(buffer, 0, bytesRead);
			}

			fis.close();
			outputStream.flush();
			outputStream.close();

			// 删除临时文件
			tempFile.delete();

			// 清理任务
			cn.easyreport.export.ExportTaskManager.removeTask(taskId);

		} catch (Exception ex) {
			throw new ReportException(ex);
		}
	}

	/**
	 * 从报表定义中查找HTTP数据源
	 */
	private cn.easyreport.definition.datasource.HttpDatasourceDefinition findHttpDatasource(ReportDefinition reportDef) {
		java.util.List<cn.easyreport.definition.datasource.DatasourceDefinition> datasources = reportDef.getDatasources();
		if (datasources != null) {
			for (cn.easyreport.definition.datasource.DatasourceDefinition ds : datasources) {
				if (ds instanceof cn.easyreport.definition.datasource.HttpDatasourceDefinition) {
					return (cn.easyreport.definition.datasource.HttpDatasourceDefinition) ds;
				}
			}
		}
		return null;
	}

	/**
	 * 从数据集列表中按名称查找HttpDatasetDefinition
	 * @param datasets 数据集列表
	 * @param name 数据集名称，为空则返回第一个HttpDatasetDefinition
	 */
	private cn.easyreport.definition.dataset.HttpDatasetDefinition findHttpDataset(
		java.util.List<cn.easyreport.definition.dataset.DatasetDefinition> datasets, String name) {
		if (datasets == null || datasets.isEmpty()) return null;
		if (name != null && !name.isEmpty()) {
			for (cn.easyreport.definition.dataset.DatasetDefinition ds : datasets) {
				if (ds instanceof cn.easyreport.definition.dataset.HttpDatasetDefinition) {
					cn.easyreport.definition.dataset.HttpDatasetDefinition httpDs =
						(cn.easyreport.definition.dataset.HttpDatasetDefinition) ds;
					if (name.equals(httpDs.getName())) {
						return httpDs;
					}
				}
			}
			return null;
		}
		// 名称为空，返回第一个HttpDatasetDefinition
		for (cn.easyreport.definition.dataset.DatasetDefinition ds : datasets) {
			if (ds instanceof cn.easyreport.definition.dataset.HttpDatasetDefinition) {
				return (cn.easyreport.definition.dataset.HttpDatasetDefinition) ds;
			}
		}
		return null;
	}

	/**
	 * 从报表所有HTTP数据源中按名称查找数据集
	 */
	private cn.easyreport.definition.dataset.HttpDatasetDefinition findHttpDatasetFromAllSources(
		ReportDefinition reportDef, String name) {
		if (name == null || name.isEmpty() || reportDef.getDatasources() == null) return null;
		for (cn.easyreport.definition.datasource.DatasourceDefinition dsDef : reportDef.getDatasources()) {
			if (dsDef instanceof cn.easyreport.definition.datasource.HttpDatasourceDefinition) {
				cn.easyreport.definition.datasource.HttpDatasourceDefinition httpDsDef =
					(cn.easyreport.definition.datasource.HttpDatasourceDefinition) dsDef;
				java.util.List<cn.easyreport.definition.dataset.DatasetDefinition> datasets = httpDsDef.getDatasets();
				if (datasets != null) {
					for (cn.easyreport.definition.dataset.DatasetDefinition ds : datasets) {
						if (ds instanceof cn.easyreport.definition.dataset.HttpDatasetDefinition) {
							cn.easyreport.definition.dataset.HttpDatasetDefinition httpDs =
								(cn.easyreport.definition.dataset.HttpDatasetDefinition) ds;
							if (name.equals(httpDs.getName())) {
								return httpDs;
							}
						}
					}
				}
			}
		}
		return null;
	}

	public void setReportBuilder(ReportBuilder reportBuilder) {
		this.reportBuilder = reportBuilder;
	}
	public void setExportManager(ExportManager exportManager) {
		this.exportManager = exportManager;
	}
	public void setReportRender(cn.easyreport.export.ReportRender reportRender) {
		this.reportRender = reportRender;
	}

	@Override
	public String url() {
		return "/excel";
	}
}
