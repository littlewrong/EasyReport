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
package cn.easyreport.console.designer;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.apache.commons.io.IOUtils;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;

import cn.easyreport.cache.CacheUtils;
import cn.easyreport.console.RenderPageServletAction;
import cn.easyreport.console.cache.TempObjectCache;
import cn.easyreport.console.exception.ReportDesignException;
import cn.easyreport.definition.ReportDefinition;
import cn.easyreport.dsl.ReportParserLexer;
import cn.easyreport.dsl.ReportParserParser;
import cn.easyreport.dsl.ReportParserParser.DatasetContext;
import cn.easyreport.export.ReportRender;
import cn.easyreport.expression.ErrorInfo;
import cn.easyreport.expression.ScriptErrorListener;
import cn.easyreport.parser.ReportParser;
import cn.easyreport.provider.report.ReportProvider;
import cn.easyreport.console.designer.ReportUtils;

/**
 * @author Jacky.gao
 * @since 2017年1月25日
 */
public class DesignerServletAction extends RenderPageServletAction {
	private ReportRender reportRender;
	private ReportParser reportParser;
	private List<ReportProvider> reportProviders=new ArrayList<ReportProvider>();
	@Override
	public void execute(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String method=retriveMethod(req);
		if(method!=null){
			invokeMethod(method, req, resp);
		}else{
			VelocityContext context = new VelocityContext();
			context.put("contextPath", req.getContextPath());
			resp.setContentType("text/html");
			resp.setCharacterEncoding("utf-8");
			Template template=ve.getTemplate("easyreport-html/designer.html","utf-8");
			PrintWriter writer=resp.getWriter();
			template.merge(context, writer);
			writer.close();
		}
	}
	public void scriptValidation(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String content=req.getParameter("content");
		ANTLRInputStream antlrInputStream=new ANTLRInputStream(content);
		ReportParserLexer lexer=new ReportParserLexer(antlrInputStream);
		CommonTokenStream tokenStream=new CommonTokenStream(lexer);
		ReportParserParser parser=new ReportParserParser(tokenStream);
		ScriptErrorListener errorListener=new ScriptErrorListener();
		parser.removeErrorListeners();
		parser.addErrorListener(errorListener);
		parser.expression();
		List<ErrorInfo> infos=errorListener.getInfos();
		writeObjectToJson(resp, infos);
	}
	
	public void conditionScriptValidation(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String content=req.getParameter("content");
		ANTLRInputStream antlrInputStream=new ANTLRInputStream(content);
		ReportParserLexer lexer=new ReportParserLexer(antlrInputStream);
		CommonTokenStream tokenStream=new CommonTokenStream(lexer);
		ReportParserParser parser=new ReportParserParser(tokenStream);
		ScriptErrorListener errorListener=new ScriptErrorListener();
		parser.removeErrorListeners();
		parser.addErrorListener(errorListener);
		parser.expr();
		List<ErrorInfo> infos=errorListener.getInfos();
		writeObjectToJson(resp, infos);
	}
	
	
	public void parseDatasetName(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String expr=req.getParameter("expr");
		ANTLRInputStream antlrInputStream=new ANTLRInputStream(expr);
		ReportParserLexer lexer=new ReportParserLexer(antlrInputStream);
		CommonTokenStream tokenStream=new CommonTokenStream(lexer);
		ReportParserParser parser=new ReportParserParser(tokenStream);
		parser.removeErrorListeners();
		DatasetContext ctx=parser.dataset();
		String datasetName=ctx.Identifier().getText();
		Map<String,String> result=new HashMap<String,String>();
		result.put("datasetName", datasetName);
		writeObjectToJson(resp, result);
	}
	public void savePreviewData(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String content=req.getParameter("content");
		content=decodeContent(content);
		InputStream inputStream=IOUtils.toInputStream(content,"utf-8");
		ReportDefinition reportDef=reportParser.parse(inputStream,"p");
		reportRender.rebuildReportDefinition(reportDef);
		IOUtils.closeQuietly(inputStream);
		TempObjectCache.putObject(PREVIEW_KEY, reportDef);
	}
	
	public void loadReport(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String file=req.getParameter("file");
		if(file==null){
			throw new ReportDesignException("Report file can not be null.");
		}
		file=ReportUtils.decodeFileName(file);
		Object obj=TempObjectCache.getObject(file);
		if(obj!=null && obj instanceof ReportDefinition){
			ReportDefinition reportDef=(ReportDefinition)obj;
			TempObjectCache.removeObject(file);
			writeObjectToJson(resp, new ReportDefinitionWrapper(reportDef));
		}else{
			ReportDefinition reportDef=reportRender.parseReport(file);
			writeObjectToJson(resp, new ReportDefinitionWrapper(reportDef));			
		}
	}
	
	public void deleteReportFile(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String file=req.getParameter("file");
		if(file==null){
			throw new ReportDesignException("Report file can not be null.");
		}
		ReportProvider targetReportProvider=null;
		for(ReportProvider provider:reportProviders){
			if(file.startsWith(provider.getPrefix())){
				targetReportProvider=provider;
				break;
			}
		}
		if(targetReportProvider==null){
			throw new ReportDesignException("File ["+file+"] not found available report provider.");
		}
		targetReportProvider.deleteReport(file);
	}
	
	public void loadReportFile(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String file=req.getParameter("file");
		if(file==null){
			throw new ReportDesignException("Report file can not be null.");
		}
		file=ReportUtils.decodeFileName(file);
		ReportProvider targetReportProvider=null;
		for(ReportProvider provider:reportProviders){
			if(file.startsWith(provider.getPrefix())){
				targetReportProvider=provider;
				break;
			}
		}
		if(targetReportProvider==null){
			throw new ReportDesignException("File ["+file+"] not found available report provider.");
		}
		InputStream inputStream=targetReportProvider.loadReport(file);
		if(inputStream==null){
			throw new ReportDesignException("File ["+file+"] not found.");
		}
		try{
			String content=IOUtils.toString(inputStream, "utf-8");
			resp.setContentType("text/plain");
			resp.setCharacterEncoding("utf-8");
			PrintWriter writer=resp.getWriter();
			writer.write(content);
			writer.flush();
		}finally{
			IOUtils.closeQuietly(inputStream);
		}
	}

	public void downloadReportFile(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String file=req.getParameter("file");
		if(file==null){
			throw new ReportDesignException("Report file can not be null.");
		}
		file=ReportUtils.decodeFileName(file);
		ReportProvider targetReportProvider=null;
		for(ReportProvider provider:reportProviders){
			if(file.startsWith(provider.getPrefix())){
				targetReportProvider=provider;
				break;
			}
		}
		if(targetReportProvider==null){
			throw new ReportDesignException("File ["+file+"] not found available report provider.");
		}
		InputStream inputStream=targetReportProvider.loadReport(file);
		if(inputStream==null){
			throw new ReportDesignException("File ["+file+"] not found.");
		}
		String fileName=file;
		if(fileName.startsWith(targetReportProvider.getPrefix())){
			fileName=fileName.substring(targetReportProvider.getPrefix().length());
		}
		int slashIndex=fileName.lastIndexOf("/");
		if(slashIndex>-1 && slashIndex<fileName.length()-1){
			fileName=fileName.substring(slashIndex+1);
		}
		fileName=fileName.replace("\\", "/");
		slashIndex=fileName.lastIndexOf("/");
		if(slashIndex>-1 && slashIndex<fileName.length()-1){
			fileName=fileName.substring(slashIndex+1);
		}
		resp.setContentType("application/octet-stream");
		resp.setHeader("Content-Disposition","attachment;filename=\""+URLEncoder.encode(fileName,"UTF-8")+"\"");
		IOUtils.copy(inputStream, resp.getOutputStream());
	}
	
	
	public void saveReportFile(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String file=req.getParameter("file");
		file=ReportUtils.decodeFileName(file);
		String content=req.getParameter("content");
		content=decodeContent(content);
		ReportProvider targetReportProvider=null;
		for(ReportProvider provider:reportProviders){
			if(file.startsWith(provider.getPrefix())){
				targetReportProvider=provider;
				break;
			}
		}
		if(targetReportProvider==null){
			throw new ReportDesignException("File ["+file+"] not found available report provider.");
		}
		targetReportProvider.saveReport(file, content);
		InputStream inputStream=IOUtils.toInputStream(content,"utf-8");
		ReportDefinition reportDef=reportParser.parse(inputStream, file);
		reportRender.rebuildReportDefinition(reportDef);
		CacheUtils.cacheReportDefinition(file, reportDef);
		IOUtils.closeQuietly(inputStream);
	}
	
	public void loadReportProviders(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		writeObjectToJson(resp, reportProviders);
	}
	
	public void setReportRender(ReportRender reportRender) {
		this.reportRender = reportRender;
	}
	
	public void setReportParser(ReportParser reportParser) {
		this.reportParser = reportParser;
	}
	
	@Override
	public void setApplicationContext(ApplicationContext applicationContext)throws BeansException {
		super.setApplicationContext(applicationContext);
		Collection<ReportProvider> providers=applicationContext.getBeansOfType(ReportProvider.class).values();
		for(ReportProvider provider:providers){
			if(provider.disabled() || provider.getName()==null){
				continue;
			}
			reportProviders.add(provider);
		}
	}

	@Override
	public String url() {
		return "/designer";
	}
}
