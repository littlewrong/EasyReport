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
package cn.easyreport.parser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;

import org.apache.commons.lang3.StringUtils;
import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.xml.sax.Attributes;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.XMLFilterImpl;

import cn.easyreport.definition.CellDefinition;
import cn.easyreport.definition.ColumnDefinition;
import cn.easyreport.definition.HeaderFooterDefinition;
import cn.easyreport.definition.Paper;
import cn.easyreport.definition.ReportDefinition;
import cn.easyreport.definition.RowDefinition;
import cn.easyreport.definition.datasource.DatasourceDefinition;
import cn.easyreport.definition.searchform.SearchForm;
import cn.easyreport.exception.ReportException;
import cn.easyreport.exception.ReportParseException;
import cn.easyreport.parser.impl.CellParser;
import cn.easyreport.parser.impl.ColumnParser;
import cn.easyreport.parser.impl.DatasourceParser;
import cn.easyreport.parser.impl.HeaderFooterParser;
import cn.easyreport.parser.impl.PaperParser;
import cn.easyreport.parser.impl.RowParser;
import cn.easyreport.parser.impl.searchform.SearchFormParser;

/**
 * @author Jacky.gao
 * @since 2016年12月2日
 */
public class ReportParser {
	public static final int MAX_REPORT_BYTES=10*1024*1024;
	public static final int MAX_XML_DEPTH=64;
	public static final int MAX_XML_ELEMENTS=200000;
	public static final int MAX_REPORT_ROWS=100000;
	public static final int MAX_REPORT_COLUMNS=16384;
	public static final int MAX_REPORT_CELLS=100000;
	public static final int MAX_PARENT_DEPTH=1024;
	private static final int MAX_SPAN_WORK=1000000;
	private static final int MAX_PARENT_WORK=1000000;
	private static final String INVALID_REPORT="Invalid or unsafe report content.";
	private static final String DISALLOW_DOCTYPE="http://apache.org/xml/features/disallow-doctype-decl";
	private static final String EXTERNAL_GENERAL_ENTITIES="http://xml.org/sax/features/external-general-entities";
	private static final String EXTERNAL_PARAMETER_ENTITIES="http://xml.org/sax/features/external-parameter-entities";
	private static final String LOAD_EXTERNAL_DTD="http://apache.org/xml/features/nonvalidating/load-external-dtd";
	private static final EntityResolver REJECT_EXTERNAL_ENTITIES=new EntityResolver() {
		@Override
		public InputSource resolveEntity(String publicId,String systemId) throws SAXException {
			throw new SAXException(INVALID_REPORT);
		}
	};
	private Map<String,Parser<?>> parsers=new HashMap<String,Parser<?>>();
	public ReportParser() {
		parsers.put("row",new RowParser());
		parsers.put("column",new ColumnParser());
		parsers.put("cell",new CellParser());
		parsers.put("datasource",new DatasourceParser());
		parsers.put("paper",new PaperParser());
		parsers.put("header",new HeaderFooterParser());
		parsers.put("footer",new HeaderFooterParser());
		parsers.put("search-form",new SearchFormParser());
	}
	/** Check presence and UTF-8 byte size before allocating or persisting report content. */
	public static byte[] validateContent(String content) {
		if(content==null || content.length()>MAX_REPORT_BYTES || StringUtils.isBlank(content)){
			throw new ReportParseException(INVALID_REPORT);
		}
		byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
		if(bytes.length>MAX_REPORT_BYTES){
			throw new ReportParseException(INVALID_REPORT);
		}
		return bytes;
	}

	/** Validate the complete report, without executing any datasets or rendering it. */
	public ReportDefinition parseContent(String content,String file) {
		return parse(new ByteArrayInputStream(validateContent(content)),file);
	}

	public ReportDefinition parse(InputStream inputStream,String file) {
		ReportDefinition report=new ReportDefinition();
		report.setReportFullName(file);
		try{
			byte[] bytes=readLimited(inputStream);
			SAXReader saxReader=createSecureReader();
			Document document = saxReader.read(new ByteArrayInputStream(bytes));
			Element element = document.getRootElement();
			if(!element.getName().equals("easyreport")){
				throw new ReportParseException("Unknow report file.");
			}
			List<RowDefinition> rows=new ArrayList<RowDefinition>();
			report.setFreezeRows(parseFreezeCount(element.attributeValue("freeze-rows")));
			report.setFreezeColumns(parseFreezeCount(element.attributeValue("freeze-columns")));
			List<ColumnDefinition> columns=new ArrayList<ColumnDefinition>();
			List<CellDefinition> cells=new ArrayList<CellDefinition>();
			List<DatasourceDefinition> datasources=new ArrayList<DatasourceDefinition>();
			report.setRows(rows);
			report.setColumns(columns);
			report.setCells(cells);
			report.setDatasources(datasources); 
			for(Object obj:element.elements()){
				if(obj==null || !(obj instanceof Element)){
					continue;
				}
				Element ele=(Element)obj;
				Parser<?> parser=parsers.get(ele.getName());
				if(parser!=null){
					Object target=parser.parse(ele);
					if(target instanceof RowDefinition){
						rows.add((RowDefinition)target);
					}else if(target instanceof ColumnDefinition){
						columns.add((ColumnDefinition)target);
					}else if(target instanceof CellDefinition){
						cells.add((CellDefinition)target);
					}else if(target instanceof DatasourceDefinition){
						datasources.add((DatasourceDefinition)target);
					}else if(target instanceof Paper){
						Paper paper=(Paper)target;
						report.setPaper(paper);
					}else if(target instanceof HeaderFooterDefinition){
						HeaderFooterDefinition hf=(HeaderFooterDefinition)target;
						if(ele.getName().equals("header")){
							report.setHeader(hf);
						}else{
							report.setFooter(hf);
						}
					}else if(target instanceof SearchForm){
						SearchForm form=(SearchForm)target;
						report.setSearchForm(form);
						report.setSearchFormXml(ele.asXML());
					}
				}else{
					throw new ReportParseException("Unknow element :"+ele.getName());
				}
			}
			Collections.sort(rows);
			Collections.sort(columns);
			validateGeometry(report);
			rebuild(report);
			validateParents(report.getCells(),true);
			validateParents(report.getCells(),false);
		}catch(Exception ex){
			// Servlet error handling exposes root causes. Never retain parser messages,
			// which may contain server paths, external resource URLs or report data.
			throw new ReportParseException(INVALID_REPORT);
		}
		return report;
	}

	private byte[] readLimited(InputStream inputStream) throws Exception {
		if(inputStream==null){
			throw new ReportParseException(INVALID_REPORT);
		}
		ByteArrayOutputStream output=new ByteArrayOutputStream();
		byte[] buffer=new byte[8192];
		int count;
		while((count=inputStream.read(buffer))!=-1){
			if(count>MAX_REPORT_BYTES-output.size()){
				throw new ReportParseException(INVALID_REPORT);
			}
			output.write(buffer,0,count);
		}
		if(output.size()==0){
			throw new ReportParseException(INVALID_REPORT);
		}
		return output.toByteArray();
	}

	private SAXReader createSecureReader() throws Exception {
		SAXParserFactory factory=SAXParserFactory.newInstance();
		factory.setNamespaceAware(true);
		factory.setValidating(false);
		factory.setXIncludeAware(false);
		factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
		factory.setFeature(DISALLOW_DOCTYPE,true);
		factory.setFeature(EXTERNAL_GENERAL_ENTITIES,false);
		factory.setFeature(EXTERNAL_PARAMETER_ENTITIES,false);
		factory.setFeature(LOAD_EXTERNAL_DTD,false);
		XMLReader reader=factory.newSAXParser().getXMLReader();
		reader.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
		reader.setFeature(DISALLOW_DOCTYPE,true);
		reader.setFeature(EXTERNAL_GENERAL_ENTITIES,false);
		reader.setFeature(EXTERNAL_PARAMETER_ENTITIES,false);
		reader.setFeature(LOAD_EXTERNAL_DTD,false);
		reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD,"");
		reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
		reader.setEntityResolver(REJECT_EXTERNAL_ENTITIES);
		SAXReader saxReader=new SAXReader(new LimitedXmlFilter(reader));
		saxReader.setEntityResolver(REJECT_EXTERNAL_ENTITIES);
		saxReader.setErrorHandler(new DefaultHandler() {
			@Override
			public void error(SAXParseException exception) throws SAXException {
				throw exception;
			}
			@Override
			public void fatalError(SAXParseException exception) throws SAXException {
				throw exception;
			}
		});
		return saxReader;
	}

	private static class LimitedXmlFilter extends XMLFilterImpl {
		private int depth;
		private int elements;
		LimitedXmlFilter(XMLReader parent) {
			super(parent);
		}
		@Override
		public void startElement(String uri,String localName,String name,Attributes attributes) throws SAXException {
			if(++depth>MAX_XML_DEPTH || ++elements>MAX_XML_ELEMENTS){
				throw new SAXException(INVALID_REPORT);
			}
			super.startElement(uri,localName,name,attributes);
		}
		@Override
		public void endElement(String uri,String localName,String name) throws SAXException {
			super.endElement(uri,localName,name);
			depth--;
		}
	}

	private void validateGeometry(ReportDefinition report) {
		Set<Integer> rows=new HashSet<Integer>();
		for(RowDefinition row:report.getRows()){
			int index=row.getRowNumber();
			if(index<1 || index>MAX_REPORT_ROWS || !rows.add(index)){
				throw new ReportParseException(INVALID_REPORT);
			}
		}
		Set<Integer> columns=new HashSet<Integer>();
		for(ColumnDefinition column:report.getColumns()){
			int index=column.getColumnNumber();
			if(index<1 || index>MAX_REPORT_COLUMNS || !columns.add(index)){
				throw new ReportParseException(INVALID_REPORT);
			}
		}
		if(report.getCells().size()>MAX_REPORT_CELLS){
			throw new ReportParseException(INVALID_REPORT);
		}
		Set<String> names=new HashSet<String>();
		Set<String> positions=new HashSet<String>();
		long spanWork=0;
		for(CellDefinition cell:report.getCells()){
			int row=cell.getRowNumber(),column=cell.getColumnNumber();
			int rowSpan=cell.getRowSpan(),columnSpan=cell.getColSpan();
			if(StringUtils.isBlank(cell.getName()) || !names.add(cell.getName())
					|| !positions.add(row+","+column) || !rows.contains(row) || !columns.contains(column)
					|| rowSpan<0 || columnSpan<0
					|| (long)row+Math.max(1,rowSpan)-1>MAX_REPORT_ROWS
					|| (long)column+Math.max(1,columnSpan)-1>MAX_REPORT_COLUMNS){
				throw new ReportParseException(INVALID_REPORT);
			}
			spanWork+=Math.max(1,rowSpan)+(long)Math.max(1,columnSpan);
			if(spanWork>MAX_SPAN_WORK){
				throw new ReportParseException(INVALID_REPORT);
			}
		}
	}

	private void validateParents(List<CellDefinition> cells,boolean left) {
		Map<CellDefinition,Integer> depths=new IdentityHashMap<CellDefinition,Integer>();
		long parentWork=0;
		for(CellDefinition cell:cells){
			List<CellDefinition> chain=new ArrayList<CellDefinition>();
			Set<CellDefinition> visiting=Collections.newSetFromMap(new IdentityHashMap<CellDefinition,Boolean>());
			CellDefinition current=cell;
			while(current!=null && !depths.containsKey(current)){
				if(!visiting.add(current) || chain.size()>=MAX_PARENT_DEPTH){
					throw new ReportParseException(INVALID_REPORT);
				}
				chain.add(current);
				current=left ? current.getLeftParentCell() : current.getTopParentCell();
			}
			int depth=current==null ? 0 : depths.get(current);
			for(int i=chain.size()-1;i>=0;i--){
				if(++depth>MAX_PARENT_DEPTH){
					throw new ReportParseException(INVALID_REPORT);
				}
				parentWork+=depth;
				if(parentWork>MAX_PARENT_WORK){
					throw new ReportParseException(INVALID_REPORT);
				}
				depths.put(chain.get(i),depth);
			}
		}
	}

	private int parseFreezeCount(String value) {
		if(StringUtils.isBlank(value)){
			return 0;
		}
		try{
			return Math.max(0,Integer.parseInt(value.trim()));
		}catch(NumberFormatException ex){
			return 0;
		}
	}

	private void rebuild(ReportDefinition report) {
		List<CellDefinition> cells=report.getCells();
		Map<String,CellDefinition> cellsMap=new HashMap<String,CellDefinition>();
		Map<String,CellDefinition> cellsRowColMap=new HashMap<String,CellDefinition>();
		for(CellDefinition cell:cells){
			cellsMap.put(cell.getName(), cell);
			int rowNum=cell.getRowNumber(),colNum=cell.getColumnNumber(),rowSpan=cell.getRowSpan(),colSpan=cell.getColSpan();
			rowSpan = Math.max(1,rowSpan);
			colSpan = Math.max(1,colSpan);
			int rowStart=rowNum,rowEnd=rowNum+rowSpan,colStart=colNum,colEnd=colNum+colSpan;
			for(int i=rowStart;i<rowEnd;i++){
				cellsRowColMap.put(i+","+colNum, cell);				
			}
			for(int i=colStart;i<colEnd;i++){
				cellsRowColMap.put(rowNum+","+i, cell);								
			}
		}
		for(CellDefinition cell:cells){
			int rowNumber=cell.getRowNumber();
			int colNumber=cell.getColumnNumber();
			String leftParentCellName=cell.getLeftParentCellName();
			if(StringUtils.isNotBlank(leftParentCellName)){
				if(!leftParentCellName.equals("root")){
					CellDefinition targetCell=cellsMap.get(leftParentCellName);
					if(targetCell==null){
						throw new ReportException("Cell ["+cell.getName()+"] 's left parent cell ["+leftParentCellName+"] not exist.");
					}
					cell.setLeftParentCell(targetCell);					
				}
			}else{
				if(colNumber>1){
					CellDefinition targetCell=cellsRowColMap.get(rowNumber+","+(colNumber-1));
					cell.setLeftParentCell(targetCell);
				}
			}
			String topParentCellName=cell.getTopParentCellName();
			if(StringUtils.isNotBlank(topParentCellName)){
				if(!topParentCellName.equals("root")){
					CellDefinition targetCell=cellsMap.get(topParentCellName);
					if(targetCell==null){
						throw new ReportException("Cell ["+cell.getName()+"] 's top parent cell ["+topParentCellName+"] not exist.");
					}
					cell.setTopParentCell(targetCell);					
				}
			}else{
				if(rowNumber>1){
					CellDefinition targetCell=cellsRowColMap.get((rowNumber-1)+","+colNumber);
					cell.setTopParentCell(targetCell);
				}
			}
		}
	}
}
