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
package cn.easyreport.console.html;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.StringUtils;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.codehaus.jackson.map.ObjectMapper;

import cn.easyreport.build.Context;
import cn.easyreport.build.ReportBuilder;
import cn.easyreport.build.paging.Page;
import cn.easyreport.cache.CacheUtils;
import cn.easyreport.chart.ChartData;
import cn.easyreport.console.MobileUtils;
import cn.easyreport.console.RenderPageServletAction;
import cn.easyreport.console.cache.TempObjectCache;
import cn.easyreport.console.exception.ReportDesignException;
import cn.easyreport.definition.Paper;
import cn.easyreport.definition.ReportDefinition;
import cn.easyreport.definition.searchform.FormPosition;
import cn.easyreport.exception.ReportComputeException;
import cn.easyreport.export.ExportManager;
import cn.easyreport.export.FullPageData;
import cn.easyreport.export.PageBuilder;
import cn.easyreport.export.ReportRender;
import cn.easyreport.export.SinglePageData;
import cn.easyreport.export.html.HtmlProducer;
import cn.easyreport.export.html.HtmlReport;
import cn.easyreport.export.html.SearchFormData;
import cn.easyreport.model.Report;

/**
 * @author Jacky.gao
 * @since 2017年2月15日
 */
public class HtmlPreviewServletAction extends RenderPageServletAction {
	private ExportManager exportManager;
	private ReportBuilder reportBuilder;
	private ReportRender reportRender;
	private HtmlProducer htmlProducer=new HtmlProducer();
	@Override
	public void execute(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String method=retriveMethod(req);
		if(method!=null){
			invokeMethod(method, req, resp);
		}else{
			VelocityContext context = new VelocityContext();
			HtmlReport htmlReport=null;
			String errorMsg=null;
			try{
				htmlReport=loadReport(req);
			}catch(Exception ex){
				if(!(ex instanceof ReportDesignException)){
					ex.printStackTrace();					
				}
				errorMsg=buildExceptionMessage(ex);
			}
			String title=buildTitle(req);
			context.put("title", title);
			if(htmlReport==null){
				context.put("content", "<div style='color:red'><strong>报表计算出错，错误信息如下：</strong><br><div style=\"margin:10px\">"+errorMsg+"</div></div>");
				context.put("error", true);
				context.put("searchFormJs", "");
				context.put("downSearchFormHtml", "");
				context.put("upSearchFormHtml", "");
			}else{
				SearchFormData formData=htmlReport.getSearchFormData();
				if(formData!=null){
					context.put("searchFormJs", formData.getJs());
					if(formData.getFormPosition().equals(FormPosition.up)){
						context.put("upSearchFormHtml", formData.getHtml());						
						context.put("downSearchFormHtml", "");						
					}else{
						context.put("downSearchFormHtml", formData.getHtml());						
						context.put("upSearchFormHtml", "");						
					}
				}else{
					context.put("searchFormJs", "");
					context.put("downSearchFormHtml", "");
					context.put("upSearchFormHtml", "");	
				}
				context.put("content", htmlReport.getContent());
				context.put("style", htmlReport.getStyle());
				context.put("reportAlign", htmlReport.getReportAlign());				
				context.put("totalPage", htmlReport.getTotalPage()); 
				context.put("totalPageWithCol", htmlReport.getTotalPageWithCol()); 
				context.put("pageIndex", htmlReport.getPageIndex());
				context.put("chartDatas", convertJson(htmlReport.getChartDatas()));
				context.put("httpDatasets", convertObjectToJson(htmlReport.getHttpDatasetConfigs()));
				context.put("apiPagingConfig", convertObjectToJson(htmlReport.getApiPagingConfig()));
				context.put("error", false);
				context.put("file", req.getParameter("_u"));
				context.put("intervalRefreshValue",htmlReport.getHtmlIntervalRefreshValue());
				String customParameters=buildCustomParameters(req);
				context.put("customParameters", customParameters);
				context.put("_t", "");
				Tools tools=null;
				if(MobileUtils.isMobile(req)){
					tools=new Tools(false);
					tools.setShow(false);
				}else{
					String toolsInfo=req.getParameter("_t");
					if(StringUtils.isNotBlank(toolsInfo)){
						tools=new Tools(false);
						if(toolsInfo.equals("0")){
							tools.setShow(false);
						}else{
							String[] infos=toolsInfo.split(",");
							for(String name:infos){
								tools.doInit(name);
							}						
						}
						context.put("_t", toolsInfo);
						context.put("hasTools", true);
					}else{
						tools=new Tools(true);
					}
				}
				context.put("tools", tools);
			}
			context.put("contextPath", req.getContextPath());
			resp.setContentType("text/html");
			resp.setCharacterEncoding("utf-8");
			Template template=ve.getTemplate("easyreport-html/html-preview.html","utf-8");
			PrintWriter writer=resp.getWriter();
			template.merge(context, writer);
			writer.close();
		}
	}
	
	private String buildTitle(HttpServletRequest req){
		String title=req.getParameter("_title");
		if(StringUtils.isBlank(title)){
			title=req.getParameter("_u");
			title=decode(title);
			int point=title.lastIndexOf(".easyreport.xml");
			if(point>-1){
				title=title.substring(0,point);
			}
			if(title.equals("p")){
				title="设计中报表";
			}
		}else{
			title=decode(title);
		}
		return title+"-easyreport";
	}
	
	private String convertJson(Collection<ChartData> data){
		if(data==null || data.size()==0){
			return "";
		}
		ObjectMapper mapper=new ObjectMapper();
		try {
			String json = mapper.writeValueAsString(data);
			return json;
		} catch (Exception e) {
			throw new ReportComputeException(e);
		}
	}

	private String convertObjectToJson(Object data){
		if(data==null){
			return "[]";
		}
		ObjectMapper mapper=new ObjectMapper();
		try {
			return mapper.writeValueAsString(data);
		} catch (Exception e) {
			throw new ReportComputeException(e);
		}
	}

	private List<Map<String,Object>> buildHttpDatasetConfigs(ReportDefinition reportDefinition){
		return new ArrayList<Map<String,Object>>();
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
		map.put("totalCountPath", paper.getApiTotalCountPath());
		map.put("pageSize", paper.getApiPageSize() != null ? paper.getApiPageSize() : 100);

		// 日期循环导出参数
		String startFieldName = paper.getApiStartFieldName();
		String endFieldName = paper.getApiEndFieldName();
		cn.easyreport.Utils.logToConsole("========== HtmlPreviewServletAction.buildApiPagingConfig ==========");
		cn.easyreport.Utils.logToConsole("paper.getApiStartFieldName() = " + startFieldName);
		cn.easyreport.Utils.logToConsole("paper.getApiEndFieldName() = " + endFieldName);
		map.put("apiStartFieldName", startFieldName);
		map.put("apiEndFieldName", endFieldName);

		// 从HttpDatasetDefinition的ThreadLocal中获取总行数
		// 注意：此方法在报表构建完成后调用，应该能够获取到总行数
		map.put("totalCount", 0); // 默认值，将在前端从数据集中获取

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
	
	public void loadData(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		HtmlReport htmlReport=loadReport(req);
		writeObjectToJson(resp, htmlReport);
	}

	public void loadPrintPages(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String file=req.getParameter("_u");
		file=decode(file);
		if(StringUtils.isBlank(file)){
			throw new ReportComputeException("Report file can not be null.");
		}
		Map<String, Object> parameters = buildParameters(req);
		// 获取所有原始URL参数（包括以_开头的系统参数）
		Map<String, Object> originalUrlParameters = buildAllParameters(req);
		ReportDefinition reportDefinition=null;
		if(file.equals(PREVIEW_KEY)){
			reportDefinition=(ReportDefinition)TempObjectCache.getObject(PREVIEW_KEY);
			if(reportDefinition==null){
				throw new ReportDesignException("Report data has expired,can not do export excel.");
			}
		}else{
			reportDefinition=reportRender.getReportDefinition(file);
		}
		Report report=reportBuilder.buildReport(reportDefinition, parameters);	
		// 设置原始URL参数到Context中
		report.getContext().setOriginalUrlParameters(originalUrlParameters);
		Map<String, ChartData> chartMap=report.getContext().getChartDataMap();
		if(chartMap.size()>0){
			CacheUtils.storeChartDataMap(chartMap);				
		}
		FullPageData pageData=PageBuilder.buildFullPageData(report);
		StringBuilder sb=new StringBuilder();
		List<List<Page>> list=pageData.getPageList();
		Context context=report.getContext();
		if(list.size()>0){
			for(int i=0;i<list.size();i++){
				List<Page> columnPages=list.get(i);
				if(i==0){
					String html=htmlProducer.produce(context,columnPages,pageData.getColumnMargin(),false);
					sb.append(html);											
				}else{
					String html=htmlProducer.produce(context,columnPages,pageData.getColumnMargin(),false);
					sb.append(html);											
				}
			}
		}else{
			List<Page> pages=report.getPages();
			for(int i=0;i<pages.size();i++){
				Page page=pages.get(i);
				if(i==0){
					String html=htmlProducer.produce(context,page, false);
					sb.append(html);
				}else{
					String html=htmlProducer.produce(context,page, true);
					sb.append(html);
				}
			}
		}
		Map<String,String> map=new HashMap<String,String>();
		map.put("html", sb.toString());
		writeObjectToJson(resp, map);
	}
	
	public void loadPagePaper(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String file=req.getParameter("_u");
		file=decode(file);
		if(StringUtils.isBlank(file)){
			throw new ReportComputeException("Report file can not be null.");
		}
		ReportDefinition report=null;
		if(file.equals(PREVIEW_KEY)){
			report=(ReportDefinition)TempObjectCache.getObject(PREVIEW_KEY);	
			if(report==null){
				throw new ReportDesignException("Report data has expired.");
			}
		}else{
			report=reportRender.getReportDefinition(file);
		}
		Paper paper=report.getPaper();
		writeObjectToJson(resp, paper);
	}
	
	private HtmlReport loadReport(HttpServletRequest req) {
		Map<String, Object> parameters = buildParameters(req);
		// 获取所有原始URL参数（包括以_开头的系统参数）
		Map<String, Object> originalUrlParameters = buildAllParameters(req);
		HtmlReport htmlReport=null;
		String file=req.getParameter("_u");
		file=decode(file);
		String pageIndex=req.getParameter("_i");
		if(StringUtils.isBlank(file)){
			throw new ReportComputeException("Report file can not be null.");
		}
		if(file.equals(PREVIEW_KEY)){
			ReportDefinition reportDefinition=(ReportDefinition)TempObjectCache.getObject(PREVIEW_KEY);
			if(reportDefinition==null){
				throw new ReportDesignException("Report data has expired,can not do preview.");
			}
			Report report=reportBuilder.buildReport(reportDefinition, parameters);
			// 设置原始URL参数到Context中
			report.getContext().setOriginalUrlParameters(originalUrlParameters);
			Map<String, ChartData> chartMap=report.getContext().getChartDataMap();
			if(chartMap.size()>0){
				CacheUtils.storeChartDataMap(chartMap);				
			}
			htmlReport=new HtmlReport();
			String html=null;
			if(StringUtils.isNotBlank(pageIndex) && !pageIndex.equals("0")){
				Context context=report.getContext();
				int index=Integer.valueOf(pageIndex);
				SinglePageData pageData=PageBuilder.buildSinglePageData(index, report);
				List<Page> pages=pageData.getPages();
				if(pages.size()==1){
					Page page=pages.get(0);
					html=htmlProducer.produce(context,page,false);					
				}else{
					html=htmlProducer.produce(context,pages,pageData.getColumnMargin(),false);					
				}
				htmlReport.setTotalPage(pageData.getTotalPages());
				htmlReport.setPageIndex(index);
			}else{
				html=htmlProducer.produce(report);				
			}
			if(report.getPaper().isColumnEnabled()){
				htmlReport.setColumn(report.getPaper().getColumnCount());				
			}
			htmlReport.setChartDatas(report.getContext().getChartDataMap().values());
			htmlReport.setContent(html);
			htmlReport.setTotalPage(report.getPages().size());
			htmlReport.setStyle(reportDefinition.getStyle());
			htmlReport.setSearchFormData(reportDefinition.buildSearchFormData(report.getContext().getDatasetMap(),parameters));
			htmlReport.setReportAlign(report.getPaper().getHtmlReportAlign().name());
			htmlReport.setHtmlIntervalRefreshValue(report.getPaper().getHtmlIntervalRefreshValue());
			htmlReport.setHttpDatasetConfigs(buildHttpDatasetConfigs(reportDefinition));
			htmlReport.setApiPagingConfig(buildApiPagingConfigWithTotalCount(reportDefinition.getPaper(), report.getContext()));
		}else{
			if(StringUtils.isNotBlank(pageIndex) && !pageIndex.equals("0")){
				int index=Integer.valueOf(pageIndex);
				htmlReport=exportManager.exportHtml(file,req.getContextPath(),parameters,index,originalUrlParameters);								
			}else{
				htmlReport=exportManager.exportHtml(file,req.getContextPath(),parameters,originalUrlParameters);				
			}
		}
		return htmlReport;
	}
	
	
	private String buildCustomParameters(HttpServletRequest req){
		StringBuilder sb=new StringBuilder();
		Enumeration<?> enumeration=req.getParameterNames();
		while(enumeration.hasMoreElements()){
			Object obj=enumeration.nextElement();
			if(obj==null){
				continue;
			}
			String name=obj.toString();
			String value=req.getParameter(name);
			if(name==null || value==null || (name.startsWith("_") && !name.equals("_n"))){
				continue;
			}
			if(sb.length()>0){
				sb.append("&");
			}
			sb.append(name);
			sb.append("=");
			sb.append(value);
		}
		return sb.toString();
	}
	
	private Map<String, Object> buildAllParameters(HttpServletRequest req) {
		Map<String,Object> parameters=new HashMap<String,Object>();
		Enumeration<?> enumeration=req.getParameterNames();
		while(enumeration.hasMoreElements()){
			Object obj=enumeration.nextElement();
			if(obj==null){
				continue;
			}
			String name=obj.toString();
			String value=req.getParameter(name);
			if(name==null || value==null){
				continue;
			}
			// 排除系统内部参数，但保留业务参数
			if(name.startsWith("_")){
				continue;
			}
			parameters.put(name, decode(value));
		}
		return parameters;
	}
	
	private String buildExceptionMessage(Throwable throwable){
		Throwable root=buildRootException(throwable);
		StringWriter sw=new StringWriter();
		PrintWriter pw=new PrintWriter(sw);
		root.printStackTrace(pw);
		String trace=sw.getBuffer().toString();
		trace=trace.replaceAll("\n", "<br>");
		pw.close();
		return trace;
	}
	
	public void setExportManager(ExportManager exportManager) {
		this.exportManager = exportManager;
	}
	
	public void setReportBuilder(ReportBuilder reportBuilder) {
		this.reportBuilder = reportBuilder;
	}
	public void setReportRender(ReportRender reportRender) {
		this.reportRender = reportRender;
	}

	@Override
	public String url() {
		return "/preview";
	}
}
