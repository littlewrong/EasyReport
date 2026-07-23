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
package cn.easyreport.export.excel.high.builder;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import cn.easyreport.Utils;
import cn.easyreport.definition.dataset.HttpDatasetDefinition;
import cn.easyreport.exception.ReportComputeException;
import cn.easyreport.export.excel.high.ExportProgressCallback;

/**
 * 流式Excel导出器
 * 适用于大数据量明细导出（10万+）
 * 核心特性：分批查询 + 边查边写 + 内存可控
 *
 * @since 2025年1月15日
 */
public class ExcelBuilderStreaming {

	/**
	 * 流式构建Excel
	 * @param dataset HTTP数据集定义
	 * @param parameterMap 参数映射
	 * @param userPage 用户指定的页码
	 * @param userPageSize 用户指定的每页大小
	 * @param outputStream 输出流
	 * @param progressCallback 进度回调（可选）
	 * @param startDate 开始日期（可选，用于按日期循环导出）
	 * @param endDate 结束日期（可选，用于按日期循环导出）
	 * @param startFieldName 开始日期参数名（可选）
	 * @param endFieldName 结束日期参数名（可选）
	 * @param paper Paper配置对象（可选，用于读取字段映射等配置）
	 * @param summaryDataset 汇总数据集（可选，为null则不写汇总行）
	 */
	public void buildStreaming(
		HttpDatasetDefinition dataset,
		Map<String,Object> parameterMap,
		int userPage,
		int userPageSize,
		OutputStream outputStream,
		ExportProgressCallback progressCallback,
		String startDate,
		String endDate,
		String startFieldName,
		String endFieldName,
		cn.easyreport.definition.Paper paper,
		HttpDatasetDefinition summaryDataset
	) {
		// 流式Workbook，只保留100行在内存中，其余自动写入临时文件
		SXSSFWorkbook wb = new SXSSFWorkbook(100);

		int totalRows = 0;

		try {
			Sheet sheet = wb.createSheet("数据明细");

			Utils.logToConsole("========== 开始流式Excel导出 ==========");
			Utils.logToConsole("用户参数: page=" + userPage + ", pageSize=" + userPageSize);

			// 打印HTTP数据源信息
			Utils.logToConsole("HTTP REQUEST URL: " + dataset.getUrl());
			Utils.logToConsole("HTTP REQUEST METHOD: " + dataset.getMethod());
			if (dataset.getRequestBody() != null && !dataset.getRequestBody().trim().isEmpty()) {
				Utils.logToConsole("HTTP REQUEST BODY: " + dataset.getRequestBody());
			}

			// 打印参数信息
			if (parameterMap != null && !parameterMap.isEmpty()) {
				StringBuilder params = new StringBuilder();
				for (Map.Entry<String,Object> entry : parameterMap.entrySet()) {
					if (params.length() > 0) params.append(", ");
					params.append(entry.getKey()).append("=").append(entry.getValue());
				}
				Utils.logToConsole("请求参数: " + params.toString());
			}

		// 内部批次大小：从Paper配置中读取apiDefaultPageSize（导出批数量）
			int batchSize = 10000;  // 默认值
			Map<String, String> fieldMappingConfig = null;  // 字段映射配置

			// 从传入的Paper参数读取配置
			if (paper != null) {
				Integer configuredBatchSize = paper.getApiDefaultPageSize();
				if (configuredBatchSize != null && configuredBatchSize > 0) {
					batchSize = configuredBatchSize;
					Utils.logToConsole("使用配置的导出批数量: " + batchSize);
				}

				// 读取字段映射配置
				String fieldMapping = paper.getApiFieldMapping();
				if (fieldMapping != null && !fieldMapping.trim().isEmpty()) {
					fieldMappingConfig = parseFieldMapping(fieldMapping);
					Utils.logToConsole("使用字段映射配置，共 " + fieldMappingConfig.size() + " 个字段");
				}
			}

			// 获取流式数据迭代器
			Iterator<List<Map<String,Object>>> dataIterator;
			boolean dateRangeMode = startDate != null && !startDate.isEmpty() && endDate != null && !endDate.isEmpty();

			// 如果提供了日期参数，使用按日期循环的迭代器
			if (dateRangeMode) {
				Utils.logToConsole("使用按日期循环导出模式");
				Utils.logToConsole("日期范围: " + startDate + " 至 " + endDate);
				Utils.logToConsole("参数名: " + startFieldName + " / " + endFieldName);
				dataIterator = dataset.queryDataStreamingByDateRange(
					parameterMap,
					startDate,
					endDate,
					startFieldName,
					endFieldName,
					userPageSize,
					batchSize,
					progressCallback
				);
			} else {
				// 否则使用传统的offset分页迭代器
				Utils.logToConsole("使用传统offset分页导出模式");
				dataIterator = dataset.queryDataStreaming(parameterMap, userPage, userPageSize, batchSize, progressCallback);
			}

			// Excel 最大行数限制（1,048,576 行，索引从0开始，所以最大索引是1048575）
			final int EXCEL_MAX_ROWS = 1048576;
			// 每个Sheet最多50万行数据（加上表头共500001行）
			final int SAFE_MAX_ROWS = 500000;

			int rowIndex = 0;
			int sheetIndex = 1;
			boolean isFirstBatch = true;
			List<String> columnNames = null;
		List<String> headerNames = null;  // Excel表头显示名称（中文）
			XSSFCellStyle headerStyle = null;

			// 分批处理数据
			while (dataIterator.hasNext()) {
				List<Map<String,Object>> batch = dataIterator.next();

				if (batch == null || batch.isEmpty()) {
					if (dateRangeMode) {
						continue;
					}
					break;
				}

				// 第一批数据时，写入表头
				if (isFirstBatch && !batch.isEmpty()) {
					Map<String,Object> firstRow = batch.get(0);
				// 根据字段映射配置决定导出哪些字段
					if (fieldMappingConfig != null && !fieldMappingConfig.isEmpty()) {
						// 使用配置的字段映射
						columnNames = new ArrayList<>(fieldMappingConfig.keySet());
						headerNames = new ArrayList<>(fieldMappingConfig.values());
						Utils.logToConsole("使用字段映射，导出字段: " + String.join(", ", columnNames));
						Utils.logToConsole("表头中文名: " + String.join(", ", headerNames));
					} else {
						// 使用API返回的所有字段
						columnNames = new ArrayList<>(firstRow.keySet());
						headerNames = columnNames;  // 使用字段名作为表头
						Utils.logToConsole("导出所有API字段: " + String.join(", ", columnNames));
					}

					// 创建表头行
					Row headerRow = sheet.createRow(rowIndex++);
					headerStyle = createHeaderStyle(wb);

					for (int i = 0; i < columnNames.size(); i++) {
						Cell cell = headerRow.createCell(i);
						cell.setCellValue(headerNames.get(i));
						if (headerStyle != null) {
							cell.setCellStyle(headerStyle);
						}

						// 设置列宽（可选，单位是1/256字符宽度）
						sheet.setColumnWidth(i, 4000);
					}

					isFirstBatch = false;
				}

				// 写入当前批次的数据
				for (Map<String,Object> dataRow : batch) {
					// 检查是否需要创建新Sheet（接近Excel行数限制）
					if (rowIndex >= SAFE_MAX_ROWS) {
						Utils.logToConsole("========== Sheet满，创建新Sheet ==========");
						Utils.logToConsole("当前Sheet: " + sheetIndex + ", 行数: " + rowIndex);
						
						sheetIndex++;
						sheet = wb.createSheet("数据明细" + sheetIndex);
						rowIndex = 0;
						
						// 在新Sheet中复制表头
						Utils.logToConsole("在Sheet" + sheetIndex + "中创建表头");
						Row newHeaderRow = sheet.createRow(rowIndex++);
						for (int i = 0; i < columnNames.size(); i++) {
							Cell cell = newHeaderRow.createCell(i);
							cell.setCellValue(headerNames.get(i));
							if (headerStyle != null) {
								cell.setCellStyle(headerStyle);
							}
							sheet.setColumnWidth(i, 4000);
						}
						
						Utils.logToConsole("Sheet" + sheetIndex + "创建完成，继续写入数据");
					}
					
					Row excelRow = sheet.createRow(rowIndex++);

					// 安全检查：如果表头未初始化（理论上不应该发生）
					if (columnNames != null) {
						for (int i = 0; i < columnNames.size(); i++) {
							String colName = columnNames.get(i);
							Object value = dataRow.get(colName);

							Cell cell = excelRow.createCell(i);
							setCellValue(cell, value);
						}
					}
				}

				totalRows += batch.size();

				// SXSSFWorkbook会自动将超过100行的数据写入临时文件
				// 因此这里不需要手动flush，内存占用恒定在很低水平
			}

			// 写入汇总行
			Utils.logToConsole("========== 汇总行检查 ==========");
			Utils.logToConsole("summaryDataset=" + (summaryDataset != null ? summaryDataset.getName() : "null"));
			Utils.logToConsole("columnNames=" + (columnNames != null ? columnNames.size() + "列" : "null"));
			Utils.logToConsole("headerNames=" + (headerNames != null ? String.join(",", headerNames) : "null"));
			if (summaryDataset != null && columnNames != null) {
				try {
					Utils.logToConsole("========== 开始写入汇总行 ==========");
					cn.easyreport.build.Dataset summaryResult =
						summaryDataset.buildDataset(parameterMap);
					Utils.logToConsole("汇总数据集返回数据量: " + (summaryResult.getData() != null ? summaryResult.getData().size() : 0));
					@SuppressWarnings("unchecked")
					List<Map<String,Object>> summaryRows = (List<Map<String,Object>>)(List<?>)summaryResult.getData();
					if (summaryRows != null && !summaryRows.isEmpty()) {
						Utils.logToConsole("汇总数据第一行字段: " + summaryRows.get(0).keySet());
						// 解析汇总字段映射
						Map<String, String> summaryMapping = null;
						if (paper != null && paper.getApiSummaryFieldMapping() != null
							&& !paper.getApiSummaryFieldMapping().trim().isEmpty()) {
							summaryMapping = parseSummaryFieldMapping(paper.getApiSummaryFieldMapping());
							Utils.logToConsole("使用汇总字段映射，共 " + summaryMapping.size() + " 个字段");
						}

						// 构建表头名→列索引的映射
						Map<String, Integer> headerIndexMap = new java.util.HashMap<>();
						for (int i = 0; i < headerNames.size(); i++) {
							headerIndexMap.put(headerNames.get(i), i);
						}
						// 同时用字段名（columnNames）建索引，用于无映射时自动匹配
						Map<String, Integer> fieldIndexMap = new java.util.HashMap<>();
						for (int i = 0; i < columnNames.size(); i++) {
							fieldIndexMap.put(columnNames.get(i), i);
						}

						// 创建汇总行样式（加粗）
						XSSFCellStyle summaryStyle = createHeaderStyle(wb);

						// 获取汇总行前缀文字
						String summaryLabel = (paper != null) ? paper.getApiSummaryLabel() : null;

						for (Map<String,Object> summaryRow : summaryRows) {
							Row excelRow = sheet.createRow(rowIndex++);
							// 第一列写前缀（如果配置了）
							if (summaryLabel != null && !summaryLabel.trim().isEmpty()) {
								Cell firstCell = excelRow.createCell(0);
								firstCell.setCellValue(summaryLabel.trim());
								if (summaryStyle != null) {
									firstCell.setCellStyle(summaryStyle);
								}
							}

							if (summaryMapping != null && !summaryMapping.isEmpty()) {
								// 有映射：按映射关系写入
								for (Map.Entry<String, String> entry : summaryMapping.entrySet()) {
									String apiField = entry.getKey();
									String targetHeader = entry.getValue();
									Integer colIdx = headerIndexMap.get(targetHeader);
									if (colIdx != null) {
										Object value = summaryRow.get(apiField);
										Cell cell = excelRow.createCell(colIdx);
										setCellValue(cell, value);
									}
								}
							} else {
								// 无映射：按字段名自动匹配
								for (Map.Entry<String,Object> entry : summaryRow.entrySet()) {
									String fieldName = entry.getKey();
									Integer colIdx = fieldIndexMap.get(fieldName);
									if (colIdx != null) {
										Cell cell = excelRow.createCell(colIdx);
										setCellValue(cell, entry.getValue());
									}
								}
							}
						}
						Utils.logToConsole("汇总行写入完成，共 " + summaryRows.size() + " 行");
					}
				} catch (Exception ex) {
					Utils.logToConsole("写入汇总行失败: " + ex.getMessage());
					ex.printStackTrace();
				}
			}

			Utils.logToConsole("========== Excel导出完成 ==========");
			Utils.logToConsole("总行数: " + totalRows + " (不含表头)");

			// 通知完成
			if (progressCallback != null) {
				progressCallback.onComplete(totalRows);
			}

			// 写入输出流
			wb.write(outputStream);

		} catch (Exception ex) {
			Utils.logToConsole("导出失败: " + ex.getMessage());
			ex.printStackTrace();
			if (progressCallback != null) {
				progressCallback.onError(ex.getMessage());
			}
			throw new ReportComputeException(ex);
		} finally {
			try {
				wb.dispose();  // 清理临时文件
			} catch (Exception e) {
				Utils.logToConsole("清理临时文件失败: " + e.getMessage());
			}
		}
	}

	/**
	 * 创建表头样式
	 */
	private XSSFCellStyle createHeaderStyle(SXSSFWorkbook wb) {
		try {
			XSSFCellStyle style = (XSSFCellStyle) wb.createCellStyle();
			// 设置表头样式：加粗
			XSSFFont font = (XSSFFont) wb.createFont();
			font.setBold(true);
			style.setFont(font);
			return style;
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * 设置单元格值
	 */
	private void setCellValue(Cell cell, Object value) {
		if (value == null) {
			cell.setCellValue("");
			cell.setCellType(CellType.STRING);
		} else if (value instanceof Number) {
			BigDecimal bigDecimal = Utils.toBigDecimal(value);
			cell.setCellValue(bigDecimal.doubleValue());
			cell.setCellType(CellType.NUMERIC);
		} else if (value instanceof Boolean) {
			cell.setCellValue((Boolean) value);
			cell.setCellType(CellType.BOOLEAN);
		} else if (value instanceof Date) {
			cell.setCellValue((Date) value);
		} else {
			cell.setCellValue(value.toString());
			cell.setCellType(CellType.STRING);
		}
	}

	/**
	 * 解析汇总字段映射配置
	 * @param fieldMapping 格式：API字段->对应列名，例如：total_receive->收取金额,total_unreceive->未收金额
	 * @return LinkedHashMap key=API字段名 value=目标列表头名
	 */
	private Map<String, String> parseSummaryFieldMapping(String fieldMapping) {
		Map<String, String> result = new java.util.LinkedHashMap<>();
		if (fieldMapping == null || fieldMapping.trim().isEmpty()) {
			return result;
		}
		String[] mappings = fieldMapping.split(",");
		for (String mapping : mappings) {
			mapping = mapping.trim();
			if (mapping.isEmpty()) continue;
			String[] parts = mapping.split("->");
			if (parts.length == 2) {
				String apiField = parts[0].trim();
				String targetHeader = parts[1].trim();
				if (!apiField.isEmpty() && !targetHeader.isEmpty()) {
					result.put(apiField, targetHeader);
				}
			}
		}
		return result;
	}

	/**
	 * 解析字段映射配置
	 * @param fieldMapping 格式：中文名->字段名，例如：区域->area_name,项目名称->comm_name
	 * @return LinkedHashMap保持顺序 key=API字段名 value=中文显示名
	 */
	private Map<String, String> parseFieldMapping(String fieldMapping) {
		Map<String, String> result = new java.util.LinkedHashMap<>();
		if (fieldMapping == null || fieldMapping.trim().isEmpty()) {
			return result;
		}
		String[] mappings = fieldMapping.split(",");
		for (String mapping : mappings) {
			mapping = mapping.trim();
			if (mapping.isEmpty()) continue;
			String[] parts = mapping.split("->");
			if (parts.length == 2) {
				String chineseName = parts[0].trim();   // 箭头左边是中文显示名
				String fieldName = parts[1].trim();     // 箭头右边是API字段名
				if (!fieldName.isEmpty() && !chineseName.isEmpty()) {
					result.put(fieldName, chineseName);  // key=字段名, value=中文名
				}
			}
		}
		return result;
	}
}
