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
package cn.easyreport.parser.impl;

import org.apache.commons.lang.StringUtils;
import org.dom4j.Element;

import cn.easyreport.definition.HtmlReportAlign;
import cn.easyreport.definition.Orientation;
import cn.easyreport.definition.PagingMode;
import cn.easyreport.definition.Paper;
import cn.easyreport.definition.PaperSize;
import cn.easyreport.definition.PaperType;
import cn.easyreport.parser.Parser;

/**
 * @author Jacky.gao
 * @since 2017年1月19日
 */
public class PaperParser implements Parser<Paper> {
	@Override
	public Paper parse(Element element) {
		Paper paper=new Paper();
		String orientation=element.attributeValue("orientation");
		paper.setOrientation(Orientation.valueOf(orientation));
		paper.setPaperType(PaperType.valueOf(element.attributeValue("type")));
		if(paper.getPaperType().equals(PaperType.CUSTOM)){
			paper.setWidth(Integer.valueOf(element.attributeValue("width")));
			paper.setHeight(Integer.valueOf(element.attributeValue("height")));
		}else{
			PaperSize size=paper.getPaperType().getPaperSize();
			paper.setWidth(size.getWidth());
			paper.setHeight(size.getHeight());
		}
		String leftMargin=element.attributeValue("left-margin");
		if(StringUtils.isNotBlank(leftMargin)){
			paper.setLeftMargin(Integer.valueOf(leftMargin));			
		}
		String rightMargin=element.attributeValue("right-margin");
		if(StringUtils.isNotBlank(rightMargin)){
			paper.setRightMargin(Integer.valueOf(rightMargin));			
		}
		String topMargin=element.attributeValue("top-margin");
		if(StringUtils.isNotBlank(topMargin)){
			paper.setTopMargin(Integer.valueOf(topMargin));			
		}
		String bottomMargin=element.attributeValue("bottom-margin");
		if(StringUtils.isNotBlank(bottomMargin)){
			paper.setBottomMargin(Integer.valueOf(bottomMargin));			
		}
		paper.setPagingMode(PagingMode.valueOf(element.attributeValue("paging-mode")));
		if(paper.getPagingMode().equals(PagingMode.fixrows)){
			paper.setFixRows(Integer.valueOf(element.attributeValue("fixrows")));
		}
		String columnEnabled=element.attributeValue("column-enabled");
		if(StringUtils.isNotBlank(columnEnabled)){
			paper.setColumnEnabled(Boolean.valueOf(columnEnabled));
		}
		if(paper.isColumnEnabled()){
			paper.setColumnCount(Integer.valueOf(element.attributeValue("column-count")));
			paper.setColumnMargin(Integer.valueOf(element.attributeValue("column-margin")));
		}
		String htmlReportAlign=element.attributeValue("html-report-align");
		if(StringUtils.isNotBlank(htmlReportAlign)){
			paper.setHtmlReportAlign(HtmlReportAlign.valueOf(htmlReportAlign));
		}
		String htmlIntervalRefreshValue=element.attributeValue("html-interval-refresh-value");
		if(StringUtils.isNotBlank(htmlIntervalRefreshValue)){
			paper.setHtmlIntervalRefreshValue(Integer.valueOf(htmlIntervalRefreshValue));
		}
		String apiPagingEnabled=element.attributeValue("api-paging-enabled");
		if(StringUtils.isNotBlank(apiPagingEnabled)){
			paper.setApiPagingEnabled(Boolean.valueOf(apiPagingEnabled));
		}
		String apiDefaultPageSize=element.attributeValue("api-default-page-size");
		if(StringUtils.isNotBlank(apiDefaultPageSize)){
			paper.setApiDefaultPageSize(Integer.valueOf(apiDefaultPageSize));
		}
		String apiMaxPageSize=element.attributeValue("api-max-page-size");
		if(StringUtils.isNotBlank(apiMaxPageSize)){
			paper.setApiMaxPageSize(Integer.valueOf(apiMaxPageSize));
		}
		String apiPageParamName=element.attributeValue("api-page-param");
		if(StringUtils.isNotBlank(apiPageParamName)){
			paper.setApiPageParamName(apiPageParamName);
		}
		String apiPageSizeParamName=element.attributeValue("api-page-size-param");
		if(StringUtils.isNotBlank(apiPageSizeParamName)){
			paper.setApiPageSizeParamName(apiPageSizeParamName);
		}
		String apiTotalCountPath=element.attributeValue("api-total-count-path");
		if(StringUtils.isNotBlank(apiTotalCountPath)){
			paper.setApiTotalCountPath(apiTotalCountPath);
		}
		String apiPageSize=element.attributeValue("api-page-size");
		if(StringUtils.isNotBlank(apiPageSize)){
			paper.setApiPageSize(Integer.valueOf(apiPageSize));
		}
		String apiStartFieldName=element.attributeValue("api-start-field");
		if(StringUtils.isNotBlank(apiStartFieldName)){
			paper.setApiStartFieldName(apiStartFieldName);
		}
		String apiEndFieldName=element.attributeValue("api-end-field");
		if(StringUtils.isNotBlank(apiEndFieldName)){
			paper.setApiEndFieldName(apiEndFieldName);
		}
		String apiFieldMapping=element.attributeValue("api-field-mapping");
		if(StringUtils.isNotBlank(apiFieldMapping)){
			paper.setApiFieldMapping(apiFieldMapping);
		}
		String apiDatasetName=element.attributeValue("api-dataset-name");
		if(StringUtils.isNotBlank(apiDatasetName)){
			paper.setApiDatasetName(apiDatasetName);
		}
		String apiSummaryEnabled=element.attributeValue("api-summary-enabled");
		if(StringUtils.isNotBlank(apiSummaryEnabled)){
			paper.setApiSummaryEnabled(Boolean.valueOf(apiSummaryEnabled));
		}
		String apiSummaryDatasetName=element.attributeValue("api-summary-dataset-name");
		if(StringUtils.isNotBlank(apiSummaryDatasetName)){
			paper.setApiSummaryDatasetName(apiSummaryDatasetName);
		}
		String apiSummaryFieldMapping=element.attributeValue("api-summary-field-mapping");
		if(StringUtils.isNotBlank(apiSummaryFieldMapping)){
			paper.setApiSummaryFieldMapping(apiSummaryFieldMapping);
		}
		String apiSummaryLabel=element.attributeValue("api-summary-label");
		if(StringUtils.isNotBlank(apiSummaryLabel)){
			paper.setApiSummaryLabel(apiSummaryLabel);
		}
		paper.setBgImage(element.attributeValue("bg-image"));
		return paper;
	}
}
