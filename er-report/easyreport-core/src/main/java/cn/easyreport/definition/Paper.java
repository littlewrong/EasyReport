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
package cn.easyreport.definition;

import java.io.Serializable;

/**
 * @author Jacky.gao
 * @since 2014年4月29日
 */
public class Paper implements Serializable{
	private static final long serialVersionUID = -6153150083492704136L;
	private int leftMargin=90;
	private int rightMargin=90;
	private int topMargin=72;
	private int bottomMargin=72;
	private PaperType paperType;
	private PagingMode pagingMode;
	private int fixRows;
	private int width;
	private int height;
	private Orientation orientation;
	private HtmlReportAlign htmlReportAlign=HtmlReportAlign.left;
	private String bgImage;
	private boolean columnEnabled;
	private int columnCount=2;
	private int columnMargin=5;
	private int htmlIntervalRefreshValue=0;
	private boolean apiPagingEnabled;
	private Integer apiDefaultPageSize;
	private Integer apiMaxPageSize;
	private String apiPageParamName;
	private String apiPageSizeParamName;
	private String apiTotalCountPath = "data.total_count";
	private Integer apiPageSize = 100;
	private String apiStartFieldName;
	private String apiEndFieldName;
	private String apiFieldMapping;  // 字段映射关系：id->主键,projectname->项目名称
	private String apiDatasetName;  // 主数据集名称（为空则取第一个）
	private boolean apiSummaryEnabled;  // 是否启用汇总行
	private String apiSummaryDatasetName;  // 汇总数据集名称
	private String apiSummaryFieldMapping;  // 汇总字段映射：API字段->对应列名
	private String apiSummaryLabel;  // 汇总行前缀文字，为空则不写
	public int getLeftMargin() {
		return leftMargin;
	}

	public void setLeftMargin(int leftMargin) {
		this.leftMargin = leftMargin;
	}

	public int getRightMargin() {
		return rightMargin;
	}

	public void setRightMargin(int rightMargin) {
		this.rightMargin = rightMargin;
	}

	public int getTopMargin() {
		return topMargin;
	}

	public void setTopMargin(int topMargin) {
		this.topMargin = topMargin;
	}

	public int getBottomMargin() {
		return bottomMargin;
	}

	public void setBottomMargin(int bottomMargin) {
		this.bottomMargin = bottomMargin;
	}
	
	public PaperType getPaperType() {
		return paperType;
	}
	public void setPaperType(PaperType paperType) {
		this.paperType = paperType;
	}
	public int getWidth() {
		return width;
	}
	public void setWidth(int width) {
		this.width = width;
	}
	public int getHeight() {
		return height;
	}
	public void setHeight(int height) {
		this.height = height;
	}
	
	public Orientation getOrientation() {
		return orientation;
	}

	public void setOrientation(Orientation orientation) {
		this.orientation = orientation;
	}

	public PagingMode getPagingMode() {
		return pagingMode;
	}
	public void setPagingMode(PagingMode pagingMode) {
		this.pagingMode = pagingMode;
	}
	public int getFixRows() {
		return fixRows;
	}
	public void setFixRows(int fixRows) {
		this.fixRows = fixRows;
	}

	public boolean isColumnEnabled() {
		return columnEnabled;
	}

	public void setColumnEnabled(boolean columnEnabled) {
		this.columnEnabled = columnEnabled;
	}

	public int getColumnCount() {
		return columnCount;
	}

	public void setColumnCount(int columnCount) {
		this.columnCount = columnCount;
	}

	public int getColumnMargin() {
		return columnMargin;
	}

	public void setColumnMargin(int columnMargin) {
		this.columnMargin = columnMargin;
	}

	public HtmlReportAlign getHtmlReportAlign() {
		return htmlReportAlign;
	}

	public void setHtmlReportAlign(HtmlReportAlign htmlReportAlign) {
		this.htmlReportAlign = htmlReportAlign;
	}

	public String getBgImage() {
		return bgImage;
	}

	public void setBgImage(String bgImage) {
		this.bgImage = bgImage;
	}

	public int getHtmlIntervalRefreshValue() {
		return htmlIntervalRefreshValue;
	}

	public void setHtmlIntervalRefreshValue(int htmlIntervalRefreshValue) {
		this.htmlIntervalRefreshValue = htmlIntervalRefreshValue;
	}

	public boolean isApiPagingEnabled() {
		return apiPagingEnabled;
	}

	public void setApiPagingEnabled(boolean apiPagingEnabled) {
		this.apiPagingEnabled = apiPagingEnabled;
	}

	public Integer getApiDefaultPageSize() {
		return apiDefaultPageSize;
	}

	public void setApiDefaultPageSize(Integer apiDefaultPageSize) {
		this.apiDefaultPageSize = apiDefaultPageSize;
	}

	public Integer getApiMaxPageSize() {
		return apiMaxPageSize;
	}

	public void setApiMaxPageSize(Integer apiMaxPageSize) {
		this.apiMaxPageSize = apiMaxPageSize;
	}

	public String getApiPageParamName() {
		return apiPageParamName;
	}

	public void setApiPageParamName(String apiPageParamName) {
		this.apiPageParamName = apiPageParamName;
	}

	public String getApiPageSizeParamName() {
		return apiPageSizeParamName;
	}

	public void setApiPageSizeParamName(String apiPageSizeParamName) {
		this.apiPageSizeParamName = apiPageSizeParamName;
	}

	public String getApiTotalCountPath() {
		return apiTotalCountPath;
	}

	public void setApiTotalCountPath(String apiTotalCountPath) {
		this.apiTotalCountPath = apiTotalCountPath;
	}

	public Integer getApiPageSize() {
		return apiPageSize;
	}

	public void setApiPageSize(Integer apiPageSize) {
		this.apiPageSize = apiPageSize;
	}

	public String getApiStartFieldName() {
		return apiStartFieldName;
	}

	public void setApiStartFieldName(String apiStartFieldName) {
		this.apiStartFieldName = apiStartFieldName;
	}

	public String getApiEndFieldName() {
		return apiEndFieldName;
	}

	public void setApiEndFieldName(String apiEndFieldName) {
		this.apiEndFieldName = apiEndFieldName;
	}

	public String getApiFieldMapping() {
		return apiFieldMapping;
	}

	public void setApiFieldMapping(String apiFieldMapping) {
		this.apiFieldMapping = apiFieldMapping;
	}

	public String getApiDatasetName() {
		return apiDatasetName;
	}

	public void setApiDatasetName(String apiDatasetName) {
		this.apiDatasetName = apiDatasetName;
	}

	public boolean isApiSummaryEnabled() {
		return apiSummaryEnabled;
	}

	public void setApiSummaryEnabled(boolean apiSummaryEnabled) {
		this.apiSummaryEnabled = apiSummaryEnabled;
	}

	public String getApiSummaryDatasetName() {
		return apiSummaryDatasetName;
	}

	public void setApiSummaryDatasetName(String apiSummaryDatasetName) {
		this.apiSummaryDatasetName = apiSummaryDatasetName;
	}

	public String getApiSummaryFieldMapping() {
		return apiSummaryFieldMapping;
	}

	public void setApiSummaryFieldMapping(String apiSummaryFieldMapping) {
		this.apiSummaryFieldMapping = apiSummaryFieldMapping;
	}

	public String getApiSummaryLabel() {
		return apiSummaryLabel;
	}

	public void setApiSummaryLabel(String apiSummaryLabel) {
		this.apiSummaryLabel = apiSummaryLabel;
	}
}
