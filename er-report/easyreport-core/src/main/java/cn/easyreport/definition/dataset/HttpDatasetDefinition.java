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
package cn.easyreport.definition.dataset;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;

import cn.easyreport.Utils;
import cn.easyreport.build.Context;
import cn.easyreport.build.Dataset;
import cn.easyreport.exception.ReportComputeException;
import cn.easyreport.expression.ExpressionUtils;
import cn.easyreport.expression.model.Expression;
import cn.easyreport.expression.model.data.ExpressionData;
import cn.easyreport.expression.model.data.ObjectExpressionData;
import cn.easyreport.expression.model.expr.BaseExpression;
import cn.easyreport.expression.model.expr.ExpressionBlock;
import cn.easyreport.expression.model.expr.JoinExpression;
import cn.easyreport.expression.model.expr.ParenExpression;
import cn.easyreport.expression.model.expr.VariableExpression;

import org.codehaus.jackson.map.ObjectMapper;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * HTTP数据集定义
 * @since 2025年1月14日
 */
public class HttpDatasetDefinition implements DatasetDefinition {
	private static final long serialVersionUID = -1134526105416805871L;

	// 使用ThreadLocal存储总行数信息，避免修改Dataset类结构
	private static final ThreadLocal<Map<String, Integer>> TOTAL_COUNT_HOLDER = new ThreadLocal<>();

	private String name;
	private String url; // HTTP地址
	private String method = "POST"; // 请求方式，默认POST
	private String protocolForce = "https"; // 协议强制：auto/http/https，默认https
	private String requestBody; // 请求体模板
	private String dataPath; // JSON数据路径，例如："data.rows"
	private List<Parameter> parameters;
	private List<Field> fields;

	public Dataset buildDataset(Map<String,Object> parameterMap){
		try {
			// 合并参数：如果parameterMap中没有某个参数，使用参数定义中的默认值
			Map<String,Object> mergedParams = mergeParameters(parameterMap);

			// 构建请求体，替换参数
			String requestBodyStr = buildRequestBody(this.requestBody, mergedParams);

			String requestUrl = resolveUrl(url);

			// 打印HTTP请求信息
			Utils.logToConsole("HTTP REQUEST URL: " + requestUrl);
			Utils.logToConsole("HTTP REQUEST METHOD: " + method);
			Utils.logToConsole("HTTP REQUEST BODY: " + requestBodyStr);

			// 发送HTTP请求
			String response = sendHttpRequest(requestUrl, method, requestBodyStr);

			// 解析JSON响应
			List<Map<String,Object>> resultList = parseJsonResponse(response);

			// 解析并保存总行数到Context（如果配置了总行数路径）
			extractAndStoreTotalCount(response);

			List<String> fieldNames=buildFieldNames(resultList);
			return new Dataset(name, resultList,fieldNames);
		} catch (Exception e) {
			throw new ReportComputeException(e);
		}
	}

	/**
	 * 合并参数：使用URL参数和默认值
	 */
	private Map<String,Object> mergeParameters(Map<String,Object> parameterMap) {
		Map<String,Object> merged = new HashMap<String,Object>();

		// 首先添加所有URL参数
		if(parameterMap != null) {
			merged.putAll(parameterMap);
		}

		// 然后添加参数定义中的默认值（如果URL参数中没有提供）
		if(this.parameters != null && !this.parameters.isEmpty()) {
			for(Parameter param : this.parameters) {
				String paramName = param.getName();
				String defaultValue = param.getDefaultValue();

				// 如果参数在URL中没有提供，且有默认值，则使用默认值
				if(!merged.containsKey(paramName) || merged.get(paramName) == null || merged.get(paramName).toString().isEmpty()) {
					if(defaultValue != null && !defaultValue.isEmpty()) {
						merged.put(paramName, defaultValue);
					}
				}
			}
		}

		return merged;
	}

	/**
	 * 构建请求体，替换参数占位符
	 */
	private String buildRequestBody(String template, Map<String,Object> parameterMap) {
		if(template == null || template.isEmpty()) {
			return "{}";
		}

		String result = template;
		Context context = new Context(null, parameterMap);

		// 处理${param}形式的参数占位符
		Pattern pattern = Pattern.compile("\\$\\{.*?\\}");
		Matcher matcher = pattern.matcher(result);
		while(matcher.find()){
			String substr = matcher.group();
			String paramExpr = substr.substring(2, substr.length()-1);
			Expression expr = ExpressionUtils.parseExpression(paramExpr);
			String value = executeParamExpr(expr, context, parameterMap);
			result = result.replace(substr, value);
		}

		return result;
	}

	/**
	 * 执行参数表达式
	 */
	private String executeParamExpr(Expression expr, Context context, Map<String,Object> parameterMap) {
		String paramName = null;

		// 首先尝试从表达式中提取参数名
		paramName = extractParameterName(expr);

		// 如果成功提取参数名，直接从 parameterMap 中获取
		if(paramName != null && parameterMap.containsKey(paramName)) {
			Object value = parameterMap.get(paramName);
			return value != null ? value.toString() : "";
		}

		// 如果没有找到，尝试通过表达式执行获取值
		try {
			ExpressionData<?> exprData = expr.execute(null, null, context);
			if(exprData instanceof ObjectExpressionData){
				ObjectExpressionData data = (ObjectExpressionData)exprData;
				Object obj = data.getData();
				if(obj != null){
					return obj.toString();
				}
			}
		} catch (Exception e) {
			// 忽略表达式执行失败
		}

		return "";
	}

	private List<String> buildFieldNames(List<Map<String,Object>> resultList){
		List<String> names=new ArrayList<String>();
		if(resultList!=null && !resultList.isEmpty()){
			Map<String,Object> first=resultList.get(0);
			if(first!=null){
				for(String key:first.keySet()){
					names.add(key);
				}
			}
		}
		if(names.isEmpty() && fields!=null){
			for(Field f:fields){
				names.add(f.getName());
			}
		}
		return names;
	}

	/**
	 * 从表达式中提取参数名
	 */
	private String extractParameterName(Expression expr) {
		if(expr == null) {
			return null;
		}

		// 如果是 ExpressionBlock，需要获取其 returnExpression
		if(expr instanceof ExpressionBlock) {
			ExpressionBlock block = (ExpressionBlock)expr;
			Expression returnExpr = block.getReturnExpression();

			// 如果 returnExpression 为 null，尝试从 expressionList 中获取
			if(returnExpr == null) {
				java.util.List<Expression> exprList = block.getExpressionList();
				if(exprList != null && !exprList.isEmpty()) {
					for(Expression e : exprList) {
						String name = extractParameterName(e);
						if(name != null) {
							return name;
						}
					}
				}
			} else {
				return extractParameterName(returnExpr);
			}
		}

		// 如果是 ParenExpression 或 JoinExpression，需要获取其内部的 expressions
		if(expr instanceof JoinExpression) {
			JoinExpression joinExpr = (JoinExpression)expr;
			java.util.List<BaseExpression> expressions = joinExpr.getExpressions();
			if(expressions != null && !expressions.isEmpty()) {
				// 通常变量在第一个表达式中
				for(BaseExpression e : expressions) {
					String name = extractParameterName(e);
					if(name != null) {
						return name;
					}
				}
			}
		}

		// 如果是 VariableExpression，直接获取变量名
		if(expr instanceof VariableExpression) {
			VariableExpression varExpr = (VariableExpression)expr;
			// VariableExpression 的 text 字段是私有的，我们需要通过反射获取
			try {
				java.lang.reflect.Field textField = VariableExpression.class.getDeclaredField("text");
				textField.setAccessible(true);
				String text = (String)textField.get(varExpr);
				return text;
			} catch (Exception e) {
				// 反射获取失败，忽略
			}
		}

		return null;
	}

	/**
	 * Resolve relative HTTP dataset URL against current request when scheme is missing.
	 */
	private String resolveUrl(String rawUrl) {
		if(rawUrl == null || rawUrl.trim().isEmpty()) {
			throw new ReportComputeException("HTTP dataset url can not be empty.");
		}
		String trimmed = rawUrl.trim();
		if(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
			return trimmed;
		}
		// 优先使用配置的显式前缀（系统属性/环境变量）
		String explicitBase = System.getProperty("easyreport.http.base-url");
		if(explicitBase == null || explicitBase.trim().isEmpty()){
			explicitBase = System.getenv("EASYREPORT_HTTP_BASE_URL");
		}
		if(explicitBase != null && !explicitBase.trim().isEmpty()){
			String base = explicitBase.trim();
			if(base.endsWith("/")){
				base = base.substring(0, base.length()-1);
			}
			String path = trimmed.startsWith("/") ? trimmed : "/" + trimmed;
			String resolved = base + path;
			return resolved;
		}
		try{
			ServletRequestAttributes requestAttributes = (ServletRequestAttributes)RequestContextHolder.getRequestAttributes();
			if(requestAttributes != null){
				HttpServletRequest request = requestAttributes.getRequest();
				String scheme = request.getScheme();
				boolean schemeForced = protocolForce != null && !protocolForce.equals("auto");

				// 应用协议强制设置
				if(schemeForced) {
					scheme = protocolForce;
				}

				int port = request.getServerPort();
				// 当强制切换协议时，使用目标协议的默认端口，避免 http->https 仍携带 80 导致 SSL 异常
				if(schemeForced){
					if("https".equalsIgnoreCase(scheme) && port == 80){
						port = 443;
					}else if("http".equalsIgnoreCase(scheme) && port == 443){
						port = 80;
					}
				}
				boolean defaultPort = ("http".equalsIgnoreCase(scheme) && port==80) || ("https".equalsIgnoreCase(scheme) && port==443);
				StringBuilder builder=new StringBuilder();
				builder.append(scheme).append("://").append(request.getServerName());
				if(!defaultPort){
					builder.append(":").append(port);
				}
				if(trimmed.startsWith("/")) {
					builder.append(trimmed);
				} else {
					String contextPath=request.getContextPath();
					if(contextPath!=null && !contextPath.isEmpty()) {
						if(!contextPath.startsWith("/")) {
							builder.append("/");
						}
						builder.append(contextPath);
					}
					builder.append("/").append(trimmed);
				}
				String resolved=builder.toString();
				return resolved;
			}
		}catch(Exception ex){
			// URL解析失败，使用原始值
		}
		if(trimmed.startsWith("/")){
			throw new ReportComputeException("Relative HTTP dataset url ["+trimmed+"] can not be resolved. Configure easyreport.http.base-url or invoke within an HTTP request.");
		}
		return trimmed;
	}

	/**
	 * 发送HTTP请求
	 */
	private String sendHttpRequest(String urlStr, String method, String requestBody) throws Exception {
		URL url = new URL(urlStr);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod(method);
		conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
		conn.setRequestProperty("Accept", "application/json");
		conn.setDoOutput(true);
		conn.setDoInput(true);
		conn.setConnectTimeout(30000); // 连接超时: 30秒
		conn.setReadTimeout(300000); // 读取超时: 300秒(5分钟) - 支持大数据量查询

		// 发送请求体
		if(requestBody != null && !requestBody.isEmpty() && !"GET".equalsIgnoreCase(method)) {
			try(OutputStream os = conn.getOutputStream()) {
				byte[] input = requestBody.getBytes("UTF-8");
				os.write(input, 0, input.length);
			}
		}

		// 读取响应
		int responseCode = conn.getResponseCode();
		if(responseCode != 200) {
			throw new Exception("HTTP请求失败，响应码: " + responseCode);
		}

		StringBuilder response = new StringBuilder();
		try(BufferedReader br = new BufferedReader(
				new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
			String line;
			while ((line = br.readLine()) != null) {
				response.append(line);
			}
		}

		return response.toString();
	}

	/**
	 * 解析JSON响应
	 */
	@SuppressWarnings("unchecked")
	private List<Map<String,Object>> parseJsonResponse(String jsonStr) throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		Map<String,Object> jsonMap = mapper.readValue(jsonStr, Map.class);

		// 根据dataPath提取数据
		Object data = jsonMap;
		if(dataPath != null && !dataPath.isEmpty()) {
			String[] paths = dataPath.split("\\.");
			for(String path : paths) {
				if(data instanceof Map) {
					data = ((Map<String,Object>)data).get(path);
				} else {
					break;
				}
			}
		}

		// 确保返回的是List<Map>格式
		List<Map<String,Object>> resultList = new ArrayList<>();
		if(data instanceof List) {
			List<?> list = (List<?>)data;
			for(Object item : list) {
				if(item instanceof Map) {
					resultList.add((Map<String,Object>)item);
				}
			}
		} else if(data instanceof Map) {
			// 如果是单个对象，包装成列表
			resultList.add((Map<String,Object>)data);
		}

		return resultList;
	}

	/**
	 * 从JSON响应中提取总行数并保存到ThreadLocal
	 */
	@SuppressWarnings("unchecked")
	private void extractAndStoreTotalCount(String jsonStr) {
		try {
			// 从Spring RequestContextHolder获取Paper配置中的总行数路径
			String totalCountPath = null;
			try {
				ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
				if (attrs != null) {
					HttpServletRequest request = attrs.getRequest();
					Object paperObj = request.getAttribute("__easyreport_paper");
					if (paperObj instanceof cn.easyreport.definition.Paper) {
						cn.easyreport.definition.Paper paper = (cn.easyreport.definition.Paper) paperObj;
						totalCountPath = paper.getApiTotalCountPath();
					}
				}
			} catch (Exception e) {
				// 忽略获取Paper失败的异常
			}

			// 如果没有配置总行数路径，使用默认值
			if (totalCountPath == null || totalCountPath.trim().isEmpty()) {
				totalCountPath = "data.total_count";
			}

			ObjectMapper mapper = new ObjectMapper();
			Map<String, Object> jsonMap = mapper.readValue(jsonStr, Map.class);

			// 根据totalCountPath提取总行数
			Object countObj = jsonMap;
			String[] paths = totalCountPath.split("\\.");
			for (String path : paths) {
				if (countObj instanceof Map) {
					countObj = ((Map<String, Object>) countObj).get(path);
				} else {
					countObj = null;
					break;
				}
			}

			// 如果成功提取到总行数，保存到ThreadLocal
			if (countObj != null) {
				int totalCount = 0;
				if (countObj instanceof Number) {
					totalCount = ((Number) countObj).intValue();
				} else {
					try {
						totalCount = Integer.parseInt(countObj.toString());
					} catch (NumberFormatException e) {
						Utils.logToConsole("解析总行数失败: " + countObj);
					}
				}

				if (totalCount > 0) {
					Map<String, Integer> countMap = TOTAL_COUNT_HOLDER.get();
					if (countMap == null) {
						countMap = new HashMap<>();
						TOTAL_COUNT_HOLDER.set(countMap);
					}
					countMap.put(this.name, totalCount);
					Utils.logToConsole("HTTP数据集 [" + this.name + "] 总行数: " + totalCount);
				}
			}
		} catch (Exception e) {
			// 解析总行数失败不影响主流程
			Utils.logToConsole("提取总行数失败: " + e.getMessage());
		}
	}

	/**
	 * 获取已存储的总行数
	 */
	public static Integer getTotalCount(String datasetName) {
		Map<String, Integer> countMap = TOTAL_COUNT_HOLDER.get();
		if (countMap != null) {
			return countMap.get(datasetName);
		}
		return null;
	}

	/**
	 * 清理ThreadLocal（防止内存泄漏）
	 */
	public static void clearTotalCount() {
		TOTAL_COUNT_HOLDER.remove();
	}

	@Override
	public List<Field> getFields() {
		return fields;
	}

	public void setFields(List<Field> fields) {
		this.fields = fields;
	}

	public List<Parameter> getParameters() {
		return parameters;
	}

	public void setParameters(List<Parameter> parameters) {
		this.parameters = parameters;
	}

	@Override
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDataPath() {
		return dataPath;
	}

	public void setDataPath(String dataPath) {
		this.dataPath = dataPath;
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public String getMethod() {
		return method;
	}

	public void setMethod(String method) {
		this.method = method;
	}

	public String getProtocolForce() {
		return protocolForce;
	}

	public void setProtocolForce(String protocolForce) {
		this.protocolForce = protocolForce;
	}

	public String getRequestBody() {
		return requestBody;
	}

	public void setRequestBody(String requestBody) {
		this.requestBody = requestBody;
	}

	/**
	 * 流式查询数据（分批查询）
	 * 用于大数据量导出，根据用户指定的页码和总条数，内部自动分批查询
	 *
	 * @param parameterMap 参数映射
	 * @param userPage 用户指定的页码（如：第1页）
	 * @param userPageSize 用户指定的每页大小（如：100000条）
	 * @param batchSize 内部批次大小（建议10000）
	 * @param progressCallback 进度回调（可选）
	 * @return 数据迭代器，每次返回一批数据
	 */
	public java.util.Iterator<List<Map<String,Object>>> queryDataStreaming(
		Map<String,Object> parameterMap,
		int userPage,
		int userPageSize,
		int batchSize,
		cn.easyreport.export.excel.high.ExportProgressCallback progressCallback
	) {
		return new java.util.Iterator<List<Map<String,Object>>>() {
			// 计算起始偏移量（用户要第2页10万条，则offset=100000）
			private int offset = (userPage - 1) * userPageSize;

			// 计算总批次数（100000 / 10000 = 10批）
			private int totalBatches = (int) Math.ceil((double) userPageSize / batchSize);

			// 当前批次索引
			private int currentBatch = 0;

			// 已获取的数据总量
			private int fetchedCount = 0;

			// 是否还有更多数据
			private boolean hasMore = true;

			@Override
			public boolean hasNext() {
				return hasMore && currentBatch < totalBatches;
			}

			@Override
			public List<Map<String,Object>> next() {
				if (!hasNext()) {
					return new ArrayList<>();
				}

				try {
					// 计算当前批次的API分页参数
					// 例如：用户要第1页的100000条，内部拆分为：
					//   批次1: page=1, size=10000
					//   批次2: page=2, size=10000
					//   ...
					//   批次10: page=10, size=10000
					int apiPage = offset / batchSize + currentBatch + 1;
					int apiPageSize = batchSize;

					// 最后一批可能需要调整数量
					if (currentBatch == totalBatches - 1) {
						int remainder = userPageSize % batchSize;
						if (remainder > 0) {
							apiPageSize = remainder;
						}
					}

					// 确保不超过用户期望的总量
					int remainingCount = userPageSize - fetchedCount;
					if (apiPageSize > remainingCount) {
						apiPageSize = remainingCount;
					}

					Utils.logToConsole(String.format(
						"[流式查询] 批次 %d/%d, API参数 page=%d size=%d",
						currentBatch + 1, totalBatches, apiPage, apiPageSize
					));

					// 合并参数
					Map<String,Object> mergedParams = mergeParameters(parameterMap);
					mergedParams.put("page", apiPage);
					mergedParams.put("page_size", apiPageSize);

					// 发送HTTP请求
					String requestBodyStr = buildRequestBody(requestBody, mergedParams);
					String requestUrl = resolveUrl(url);
					String response = sendHttpRequest(requestUrl, method, requestBodyStr);

					// 解析响应
					List<Map<String,Object>> batch = parseJsonResponse(response);

					int batchCount = (batch == null) ? 0 : batch.size();
					fetchedCount += batchCount;

					Utils.logToConsole(String.format(
						"[流式查询] 批次 %d 返回 %d 条，累计 %d/%d 条",
						currentBatch + 1, batchCount, fetchedCount, userPageSize
					));

					// 更新进度
					if (progressCallback != null) {
						int percent = (int) ((currentBatch + 1) * 100.0 / totalBatches);
						String message = String.format(
							"正在导出: %d/%d 批次 (%d/%d 条数据)",
							currentBatch + 1, totalBatches, fetchedCount, userPageSize
						);
						progressCallback.onProgress(fetchedCount, userPageSize, percent, message);
					}

					// 判断停止条件
					if (batch == null || batch.isEmpty()) {
						Utils.logToConsole("[流式查询] API返回空数据，提前结束");
						hasMore = false;
					} else if (fetchedCount >= userPageSize) {
						Utils.logToConsole("[流式查询] 已达到用户期望的数据量，结束查询");
						hasMore = false;
					}

					currentBatch++;
					return batch;

				} catch (Exception e) {
					hasMore = false;
					Utils.logToConsole("[流式查询] 查询失败: " + e.getMessage());
					if (progressCallback != null) {
						progressCallback.onError("查询失败: " + e.getMessage());
					}
					throw new ReportComputeException(e);
				}
			}
		};
	}

	/**
	 * 按日期范围循环查询数据（流式导出）
	 * 解决offset性能问题：每天都从page=1开始，使用日期过滤
	 *
	 * @param parameterMap 参数映射
	 * @param startDate 开始日期（格式：yyyy-MM-dd）
	 * @param endDate 结束日期（格式：yyyy-MM-dd）
	 * @param startFieldName 开始日期参数名
	 * @param endFieldName 结束日期参数名
	 * @param maxExportRows 最大导出行数
	 * @param batchSize 内部批次大小
	 * @param progressCallback 进度回调
	 * @return 数据迭代器
	 */
	public java.util.Iterator<List<Map<String,Object>>> queryDataStreamingByDateRange(
		Map<String,Object> parameterMap,
		String startDate,
		String endDate,
		String startFieldName,
		String endFieldName,
		int maxExportRows,
		int batchSize,
		cn.easyreport.export.excel.high.ExportProgressCallback progressCallback
	) {
		return new java.util.Iterator<List<Map<String,Object>>>() {
			// 日期格式化器
			private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
			private SimpleDateFormat dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

			// 当前日期（迭代器状态）
			private Calendar currentDate = parseDate(startDate);
			private Calendar endDateCal = parseDate(endDate);

			// 当前日期的页码（每切换到新的日期时重置为1）
			private int currentPageForDay = 1;

			// 当前日期的总页数（从API响应中获取）
			private int totalPagesForDay = 1;

			// 当前日期是否还有更多页
			private boolean hasMorePagesInDay = true;

			// 累计导出的总行数
			private int totalFetchedCount = 0;

			// 当前批次数据（用于分批返回）
			private List<Map<String,Object>> currentBatch = null;
			private int batchIndex = 0;

			// 是否还有更多数据
			private boolean hasMore = true;

			// 解析日期字符串
			private Calendar parseDate(String dateStr) {
				try {
					Date date = dateFormat.parse(dateStr);
					Calendar cal = Calendar.getInstance();
					cal.setTime(date);
					return cal;
				} catch (Exception e) {
					throw new ReportComputeException("日期格式错误: " + dateStr + "，期望格式: yyyy-MM-dd");
				}
			}

			@Override
			public boolean hasNext() {
				// 如果已达到最大导出行数，停止
				if (totalFetchedCount >= maxExportRows) {
					return false;
				}

				// 如果还有数据可查询，继续
				return hasMore && (currentDate.compareTo(endDateCal) <= 0);
			}

			@Override
			public List<Map<String,Object>> next() {
				if (!hasNext()) {
					return new ArrayList<>();
				}

				try {
					// 如果当前日期的所有页都查询完了，切换到下一天
					if (!hasMorePagesInDay) {
						currentDate.add(Calendar.DAY_OF_MONTH, 1);
						currentPageForDay = 1;
						totalPagesForDay = 1;
						hasMorePagesInDay = true;

						// 如果超出日期范围，结束
						if (currentDate.compareTo(endDateCal) > 0) {
							hasMore = false;
							return new ArrayList<>();
						}
					}

					// 构建当前日期的开始和结束时间
					String currentDateStr = dateFormat.format(currentDate.getTime());
					String startDateTime = currentDateStr + " 00:00:00";
					String endDateTime = currentDateStr + " 23:59:59";

					Utils.logToConsole(String.format(
						"[按日期循环] 日期: %s, 页码: %d/%d",
						currentDateStr, currentPageForDay, totalPagesForDay
					));

					// 合并参数
					Map<String,Object> mergedParams = mergeParameters(parameterMap);
					mergedParams.put(startFieldName, startDateTime);
					mergedParams.put(endFieldName, endDateTime);
					mergedParams.put("page", currentPageForDay);
					mergedParams.put("page_size", batchSize);

					// 发送HTTP请求
					String requestBodyStr = buildRequestBody(requestBody, mergedParams);
					String requestUrl = resolveUrl(url);

					// 输出完整的请求URL和参数（便于调试）
					Utils.logToConsole("[请求URL] " + requestUrl);
					Utils.logToConsole("[请求方法] " + method);
					if (requestBodyStr != null && !requestBodyStr.isEmpty()) {
						Utils.logToConsole("[请求Body] " + requestBodyStr);
					}

					String response = sendHttpRequest(requestUrl, method, requestBodyStr);

					// 解析响应
					List<Map<String,Object>> pageData = parseJsonResponse(response);

					// 获取总条数（用于判断是否需要继续分页）
					int totalCount = getTotalCountFromResponse(response);
					if (totalCount > 0 && currentPageForDay == 1) {
						totalPagesForDay = (int) Math.ceil((double) totalCount / batchSize);
						Utils.logToConsole(String.format(
							"[按日期循环] 当前日期总条数: %d, 总页数: %d",
							totalCount, totalPagesForDay
						));
					}

					int pageDataCount = (pageData == null) ? 0 : pageData.size();
					totalFetchedCount += pageDataCount;

					Utils.logToConsole(String.format(
						"[按日期循环] 页面 %d 返回 %d 条，累计 %d/%d 条",
						currentPageForDay, pageDataCount, totalFetchedCount, maxExportRows
					));

					// 更新进度
					if (progressCallback != null) {
						int percent = Math.min(100, (int) ((totalFetchedCount * 100.0) / maxExportRows));
						String message = String.format(
							"正在导出: %s (%d/%d 条)",
							currentDateStr, totalFetchedCount, maxExportRows
						);
						progressCallback.onProgress(totalFetchedCount, maxExportRows, percent, message);
					}

					// 判断当前日期是否还有更多页
					if (pageData == null || pageData.isEmpty()) {
						// 当前日期没有数据，切换到下一天
						hasMorePagesInDay = false;
					} else if (currentPageForDay >= totalPagesForDay) {
						// 当前日期的所有页都查询完了
						hasMorePagesInDay = false;
					} else {
						// 还有更多页，页码+1
						currentPageForDay++;
					}

					// 判断是否达到最大导出行数
					if (totalFetchedCount >= maxExportRows) {
						Utils.logToConsole("[按日期循环] 已达到最大导出行数，结束查询");
						hasMore = false;
						// 截断超出的数据
						if (pageData != null && totalFetchedCount > maxExportRows) {
							int overflow = totalFetchedCount - maxExportRows;
							int keepSize = pageData.size() - overflow;
							if (keepSize > 0) {
								pageData = pageData.subList(0, keepSize);
							} else {
								pageData = new ArrayList<>();
							}
							totalFetchedCount = maxExportRows;
						}
					}

					currentBatch = pageData;
					return currentBatch;

				} catch (Exception e) {
					hasMore = false;
					Utils.logToConsole("[按日期循环] 查询失败: " + e.getMessage());
					if (progressCallback != null) {
						progressCallback.onError("查询失败: " + e.getMessage());
					}
					throw new ReportComputeException(e);
				}
			}

			/**
			 * 从响应中提取总条数
			 */
			@SuppressWarnings("rawtypes")
			private int getTotalCountFromResponse(String response) {
				try {
					// 从Paper配置中获取总行数路径
					String tcPath = null;
					try {
						ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
						if (attrs != null) {
							HttpServletRequest request = attrs.getRequest();
							Object paperObj = request.getAttribute("__easyreport_paper");
							if (paperObj instanceof cn.easyreport.definition.Paper) {
								cn.easyreport.definition.Paper paper = (cn.easyreport.definition.Paper) paperObj;
								tcPath = paper.getApiTotalCountPath();
							}
						}
					} catch (Exception e) {
						// 忽略获取Paper失败的异常
					}

					// 如果没有配置，使用默认值
					if (tcPath == null || tcPath.trim().isEmpty()) {
						tcPath = "data.total_count";
					}

					ObjectMapper mapper = new ObjectMapper();
					Object jsonObj = mapper.readValue(response, Object.class);

					String[] pathParts = tcPath.split("\\.");
					Object current = jsonObj;

					for (String part : pathParts) {
						if (current instanceof Map) {
							current = ((Map) current).get(part);
						} else {
							return 0;
						}
					}

					if (current instanceof Number) {
						return ((Number) current).intValue();
					}

					return 0;
				} catch (Exception e) {
					Utils.logToConsole("[警告] 无法获取总条数: " + e.getMessage());
					return 0;
				}
			}
		};
	}

}
