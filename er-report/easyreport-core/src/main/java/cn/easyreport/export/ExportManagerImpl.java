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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.easyreport.build.paging.Page;
import cn.easyreport.cache.CacheUtils;
import cn.easyreport.chart.ChartData;
import cn.easyreport.definition.ReportDefinition;
import cn.easyreport.definition.Paper;
import cn.easyreport.Utils;
import cn.easyreport.export.excel.high.ExcelProducer;
import cn.easyreport.export.excel.low.Excel97Producer;
import cn.easyreport.export.html.HtmlProducer;
import cn.easyreport.export.html.HtmlReport;
import cn.easyreport.export.pdf.PdfProducer;
import cn.easyreport.export.word.high.WordProducer;
import cn.easyreport.model.Report;

/**
 * @author Jacky.gao
 * @since 2016年12月4日
 */
public class ExportManagerImpl implements ExportManager {
	private ReportRender reportRender;
	private HtmlProducer htmlProducer=new HtmlProducer();
	private WordProducer wordProducer=new WordProducer();
	private ExcelProducer excelProducer=new ExcelProducer();
	private Excel97Producer excel97Producer=new Excel97Producer();
	private PdfProducer pdfProducer=new PdfProducer();
	@Override
	public HtmlReport exportHtml(String file,String contextPath,Map<String, Object> parameters) {
		ReportDefinition reportDefinition=reportRender.getReportDefinition(file);
		Report report=reportRender.render(reportDefinition, parameters);
		Map<String, ChartData> chartMap=report.getContext().getChartDataMap();
		if(chartMap.size()>0){
			CacheUtils.storeChartDataMap(chartMap);				
		}
		HtmlReport htmlReport=new HtmlReport();
		String content=htmlProducer.produce(report);
		htmlReport.setContent(content);
		if(reportDefinition.getPaper().isColumnEnabled()){
			htmlReport.setColumn(reportDefinition.getPaper().getColumnCount());
		}
		htmlReport.setStyle(reportDefinition.getStyle());
		htmlReport.setSearchFormData(reportDefinition.buildSearchFormData(report.getContext().getDatasetMap(),parameters));
		htmlReport.setReportAlign(report.getPaper().getHtmlReportAlign().name());
		htmlReport.setChartDatas(report.getContext().getChartDataMap().values());
		htmlReport.setHtmlIntervalRefreshValue(report.getPaper().getHtmlIntervalRefreshValue());
		htmlReport.setHttpDatasetConfigs(buildHttpDatasetConfigs(reportDefinition));
		htmlReport.setApiPagingConfig(buildApiPagingConfigWithTotalCount(reportDefinition.getPaper(), report.getContext()));
		return htmlReport;
	}
	
	@Override
	public HtmlReport exportHtml(String file,String contextPath,Map<String, Object> parameters, int pageIndex) {
		ReportDefinition reportDefinition=reportRender.getReportDefinition(file);
		Report report=reportRender.render(reportDefinition, parameters);
		Map<String, ChartData> chartMap=report.getContext().getChartDataMap();
		if(chartMap.size()>0){
			CacheUtils.storeChartDataMap(chartMap);				
		}
		SinglePageData pageData=PageBuilder.buildSinglePageData(pageIndex, report);
		List<Page> pages=pageData.getPages();
		String content=null;
		if(pages.size()==1){
			content=htmlProducer.produce(report.getContext(),pages.get(0),false);
		}else{
			content=htmlProducer.produce(report.getContext(),pages,pageData.getColumnMargin(),false);			
		}
		HtmlReport htmlReport=new HtmlReport();
		htmlReport.setContent(content);
		if(reportDefinition.getPaper().isColumnEnabled()){
			htmlReport.setColumn(reportDefinition.getPaper().getColumnCount());
		}
		htmlReport.setStyle(reportDefinition.getStyle());
		htmlReport.setSearchFormData(reportDefinition.buildSearchFormData(report.getContext().getDatasetMap(),parameters));
		htmlReport.setPageIndex(pageIndex);
		htmlReport.setTotalPage(pageData.getTotalPages());
		htmlReport.setReportAlign(report.getPaper().getHtmlReportAlign().name());
		htmlReport.setChartDatas(report.getContext().getChartDataMap().values());
		htmlReport.setHtmlIntervalRefreshValue(report.getPaper().getHtmlIntervalRefreshValue());
		htmlReport.setHttpDatasetConfigs(buildHttpDatasetConfigs(reportDefinition));
		htmlReport.setApiPagingConfig(buildApiPagingConfigWithTotalCount(reportDefinition.getPaper(), report.getContext()));
		return htmlReport;
	}

	@Override
	public HtmlReport exportHtml(String file,String contextPath,Map<String, Object> parameters,Map<String, Object> originalUrlParameters) {
		ReportDefinition reportDefinition=reportRender.getReportDefinition(file);
		Report report=reportRender.render(reportDefinition, parameters);
		// 设置原始URL参数到Context中
		report.getContext().setOriginalUrlParameters(originalUrlParameters);
		Map<String, ChartData> chartMap=report.getContext().getChartDataMap();
		if(chartMap.size()>0){
			CacheUtils.storeChartDataMap(chartMap);				
		}
		HtmlReport htmlReport=new HtmlReport();
		String content=htmlProducer.produce(report);
		htmlReport.setContent(content);
		if(reportDefinition.getPaper().isColumnEnabled()){
			htmlReport.setColumn(reportDefinition.getPaper().getColumnCount());
		}
		htmlReport.setStyle(reportDefinition.getStyle());
		htmlReport.setSearchFormData(reportDefinition.buildSearchFormData(report.getContext().getDatasetMap(),parameters));
		htmlReport.setReportAlign(report.getPaper().getHtmlReportAlign().name());
		htmlReport.setChartDatas(report.getContext().getChartDataMap().values());
		htmlReport.setHtmlIntervalRefreshValue(report.getPaper().getHtmlIntervalRefreshValue());
		htmlReport.setHttpDatasetConfigs(buildHttpDatasetConfigs(reportDefinition));
		htmlReport.setApiPagingConfig(buildApiPagingConfigWithTotalCount(reportDefinition.getPaper(), report.getContext()));
		return htmlReport;
	}
	
	@Override
	public HtmlReport exportHtml(String file,String contextPath,Map<String, Object> parameters, int pageIndex,Map<String, Object> originalUrlParameters) {
		ReportDefinition reportDefinition=reportRender.getReportDefinition(file);
		Report report=reportRender.render(reportDefinition, parameters);
		// 设置原始URL参数到Context中
		report.getContext().setOriginalUrlParameters(originalUrlParameters);
		Map<String, ChartData> chartMap=report.getContext().getChartDataMap();
		if(chartMap.size()>0){
			CacheUtils.storeChartDataMap(chartMap);				
		}
		SinglePageData pageData=PageBuilder.buildSinglePageData(pageIndex, report);
		List<Page> pages=pageData.getPages();
		String content=null;
		if(pages.size()==1){
			content=htmlProducer.produce(report.getContext(),pages.get(0),false);
		}else{
			content=htmlProducer.produce(report.getContext(),pages,pageData.getColumnMargin(),false);			
		}
		HtmlReport htmlReport=new HtmlReport();
		htmlReport.setContent(content);
		if(reportDefinition.getPaper().isColumnEnabled()){
			htmlReport.setColumn(reportDefinition.getPaper().getColumnCount());
		}
		htmlReport.setStyle(reportDefinition.getStyle());
		htmlReport.setSearchFormData(reportDefinition.buildSearchFormData(report.getContext().getDatasetMap(),parameters));
		htmlReport.setPageIndex(pageIndex);
		htmlReport.setTotalPage(pageData.getTotalPages());
		htmlReport.setReportAlign(report.getPaper().getHtmlReportAlign().name());
		htmlReport.setChartDatas(report.getContext().getChartDataMap().values());
		htmlReport.setHtmlIntervalRefreshValue(report.getPaper().getHtmlIntervalRefreshValue());
		htmlReport.setHttpDatasetConfigs(buildHttpDatasetConfigs(reportDefinition));
		htmlReport.setApiPagingConfig(buildApiPagingConfigWithTotalCount(reportDefinition.getPaper(), report.getContext()));
		return htmlReport;
	}

	private List<Map<String,Object>> buildHttpDatasetConfigs(ReportDefinition reportDefinition){
		return new ArrayList<Map<String,Object>>();
	}

	private String resolveName(String explicit,String fallback){
		if(explicit!=null && !explicit.trim().isEmpty()){
			return explicit.trim();
		}
		return fallback;
	}

	private Map<String,Object> buildApiPagingConfig(Paper paper){
		Map<String,Object> map=new HashMap<String,Object>();
		if(paper==null){
			return map;
		}
		map.put("apiPagingEnabled", paper.isApiPagingEnabled());
		map.put("pageParamName", paper.getApiPageParamName());
		map.put("pageSizeParamName", paper.getApiPageSizeParamName());
		map.put("defaultPageSize", paper.getApiDefaultPageSize());
		map.put("maxPageSize", paper.getApiMaxPageSize());
		map.put("pageSize", paper.getApiPageSize() != null ? paper.getApiPageSize() : 100);
		map.put("totalCountPath", paper.getApiTotalCountPath());

		// 调试日志：检查日期字段配置
		String startFieldName = paper.getApiStartFieldName();
		String endFieldName = paper.getApiEndFieldName();
		Utils.logToConsole("========== buildApiPagingConfig 日志 ==========");
		Utils.logToConsole("paper.getApiStartFieldName() = " + startFieldName);
		Utils.logToConsole("paper.getApiEndFieldName() = " + endFieldName);

		map.put("apiStartFieldName", startFieldName);
		map.put("apiEndFieldName", endFieldName);
		return map;
	}

	private Map<String,Object> buildApiPagingConfigWithTotalCount(Paper paper, cn.easyreport.build.Context context){
		Map<String,Object> map = buildApiPagingConfig(paper);
		if(context != null && context.getDatasetMap() != null){
			// 1. 优先按 Paper 配置的主数据集名取总行数
			String configuredMain = paper != null ? paper.getApiDatasetName() : null;
			if(configuredMain != null && !configuredMain.trim().isEmpty()
					&& context.getDatasetMap().containsKey(configuredMain.trim())){
				try {
					Integer totalCount = cn.easyreport.definition.dataset.HttpDatasetDefinition.getTotalCount(configuredMain.trim());
					if(totalCount != null && totalCount >= 0){
						map.put("totalCount", totalCount);
						map.put("datasetName", configuredMain.trim());
						return map;
					}
				} catch (Exception e) {
					// 忽略异常，继续走兜底逻辑
				}
			}
			// 2. 兜底：未配置主数据集名 或 配置的取不到，则遍历找第一个有总行数的
			for(String datasetName : context.getDatasetMap().keySet()){
				try {
					Integer totalCount = cn.easyreport.definition.dataset.HttpDatasetDefinition.getTotalCount(datasetName);
					if(totalCount != null && totalCount > 0){
						map.put("totalCount", totalCount);
						map.put("datasetName", datasetName);
						break;
					}
				} catch (Exception e) {
					// 忽略异常
				}
			}
		}
		return map;
	}
	@Override
	public void exportPdf(ExportConfigure config) {
		String file=config.getFile();
		Map<String, Object> parameters=config.getParameters();
		ReportDefinition reportDefinition=reportRender.getReportDefinition(file);
		Report report=reportRender.render(reportDefinition, parameters);
		pdfProducer.produce(report, config.getOutputStream());
	}
	@Override
	public void exportWord(ExportConfigure config) {
		String file=config.getFile();
		Map<String, Object> parameters=config.getParameters();
		ReportDefinition reportDefinition=reportRender.getReportDefinition(file);
		Report report=reportRender.render(reportDefinition, parameters);
		wordProducer.produce(report, config.getOutputStream());
	}
	@Override
	public void exportExcel(ExportConfigure config) {
		String file=config.getFile();
		Map<String, Object> parameters=config.getParameters();
		ReportDefinition reportDefinition=reportRender.getReportDefinition(file);
		Report report=reportRender.render(reportDefinition, parameters);
		excelProducer.produce(report, config.getOutputStream());
	}
	
	@Override
	public void exportExcel97(ExportConfigure config) {
		String file=config.getFile();
		Map<String, Object> parameters=config.getParameters();
		ReportDefinition reportDefinition=reportRender.getReportDefinition(file);
		Report report=reportRender.render(reportDefinition, parameters);
		excel97Producer.produce(report, config.getOutputStream());
	}
	
	@Override
	public void exportExcelWithPaging(ExportConfigure config) {
		String file=config.getFile();
		Map<String, Object> parameters=config.getParameters();
		ReportDefinition reportDefinition=reportRender.getReportDefinition(file);
		Report report=reportRender.render(reportDefinition, parameters);
		excelProducer.produceWithPaging(report, config.getOutputStream());
	}
	@Override
	public void exportExcel97WithPaging(ExportConfigure config) {
		String file=config.getFile();
		Map<String, Object> parameters=config.getParameters();
		ReportDefinition reportDefinition=reportRender.getReportDefinition(file);
		Report report=reportRender.render(reportDefinition, parameters);
		excel97Producer.produceWithPaging(report, config.getOutputStream());
	}
	
	@Override
	public void exportExcelWithPagingSheet(ExportConfigure config) {
		String file=config.getFile();
		Map<String, Object> parameters=config.getParameters();
		ReportDefinition reportDefinition=reportRender.getReportDefinition(file);
		Report report=reportRender.render(reportDefinition, parameters);
		excelProducer.produceWithSheet(report, config.getOutputStream());
	}
	
	@Override
	public void exportExcel97WithPagingSheet(ExportConfigure config) {
		String file=config.getFile();
		Map<String, Object> parameters=config.getParameters();
		ReportDefinition reportDefinition=reportRender.getReportDefinition(file);
		Report report=reportRender.render(reportDefinition, parameters);
		excel97Producer.produceWithSheet(report, config.getOutputStream());
	}
	
	public void setReportRender(ReportRender reportRender) {
		this.reportRender = reportRender;
	}
}
