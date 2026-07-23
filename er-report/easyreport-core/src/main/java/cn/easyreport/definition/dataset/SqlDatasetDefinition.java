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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.core.ResultSetExtractor;

import cn.easyreport.Utils;
import cn.easyreport.build.Context;
import cn.easyreport.build.Dataset;
import cn.easyreport.definition.datasource.DataType;
import cn.easyreport.expression.ExpressionUtils;
import cn.easyreport.expression.model.Expression;
import cn.easyreport.expression.model.data.ExpressionData;
import cn.easyreport.expression.model.data.ObjectExpressionData;
import cn.easyreport.utils.ProcedureUtils;


/**
 * @author Jacky.gao
 * @since 2016年12月27日
 */
public class SqlDatasetDefinition implements DatasetDefinition {
	private static final long serialVersionUID = -1134526105416805870L;
	private String name;
	private String sql;
	private List<Parameter> parameters;
	private List<Field> fields;
	private Expression sqlExpression;
	public Dataset buildDataset(Map<String,Object> parameterMap,Connection conn){
		String sqlForUse=sql;
		Context context=new Context(null,parameterMap);
		if(sqlExpression!=null){
			sqlForUse=executeSqlExpr(sqlExpression, context);
		}else{
			Pattern pattern=Pattern.compile("\\$\\{.*?\\}");
			Matcher matcher=pattern.matcher(sqlForUse);
			while(matcher.find()){
				String substr=matcher.group();
				String sqlExpr=substr.substring(2,substr.length()-1);
				Expression expr=ExpressionUtils.parseExpression(sqlExpr);
				String result=executeSqlExpr(expr, context);
				sqlForUse=sqlForUse.replace(substr, result);
			}
		}
		Utils.logToConsole("RUNTIME SQL:"+sqlForUse);
		Map<String, Object> pmap = buildParameters(parameterMap);
		if(ProcedureUtils.isProcedure(sqlForUse)){
			List<Map<String,Object>> result = ProcedureUtils.procedureQuery(sqlForUse,pmap,conn);
			List<String> fieldNames=buildFieldsFromResult(result);
			return new Dataset(name,result,fieldNames);
		}
		SingleConnectionDataSource datasource=new SingleConnectionDataSource(conn,false);
		NamedParameterJdbcTemplate jdbcTemplate=new NamedParameterJdbcTemplate(datasource);
		final List<String> fieldNames=new ArrayList<String>();
		List<Map<String,Object>> list= jdbcTemplate.query(sqlForUse, pmap, new ResultSetExtractor<List<Map<String,Object>>>() {
			@Override
			public List<Map<String, Object>> extractData(ResultSet rs) throws java.sql.SQLException {
				List<Map<String,Object>> rows=new ArrayList<Map<String,Object>>();
				ResultSetMetaData metadata=rs.getMetaData();
				int columnCount=metadata.getColumnCount();
				for(int i=1;i<=columnCount;i++){
					fieldNames.add(metadata.getColumnLabel(i));
				}
				while(rs.next()){
					Map<String,Object> row=new LinkedHashMap<String,Object>();
					for(int i=1;i<=columnCount;i++){
						row.put(fieldNames.get(i-1), rs.getObject(i));
					}
					rows.add(row);
				}
				return rows;
			}
		});
		return new Dataset(name,list,fieldNames);
	}
	
	private String executeSqlExpr(Expression sqlExpr,Context context){
		String sqlForUse=null;
		ExpressionData<?> exprData=sqlExpr.execute(null, null, context);
		if(exprData instanceof ObjectExpressionData){
			ObjectExpressionData data=(ObjectExpressionData)exprData;
			Object obj=data.getData();
			if(obj!=null){
				String s=obj.toString();
				s=s.replaceAll("\\\\", "");
				sqlForUse=s;
			}
		}
		return sqlForUse;
	}

	
	private Map<String,Object> buildParameters(Map<String,Object> params){
		Map<String,Object> map=new HashMap<String,Object>();
		for(Parameter param:parameters){
			String name=param.getName();
			DataType datatype=param.getType();
			Object value=param.getDefaultValue();
			if(params!=null && params.containsKey(name)){
				value=params.get(name);
			}
			map.put(name, datatype.parse(value));
		}
		return map;
	}
	
	@Override
	public List<Field> getFields() {
		return fields;
	}
	
	public void setSqlExpression(Expression sqlExpression) {
		this.sqlExpression = sqlExpression;
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

	public void setSql(String sql) {
		this.sql = sql;
	}
	public String getSql() {
		return sql;
	}

	private List<String> buildFieldsFromResult(List<Map<String,Object>> result){
		List<String> fieldNames=new ArrayList<String>();
		if(result!=null && !result.isEmpty()){
			Map<String,Object> first=result.get(0);
			for(String key:first.keySet()){
				fieldNames.add(key);
			}
		}
		if(fieldNames.isEmpty() && fields!=null){
			for(Field f:fields){
				fieldNames.add(f.getName());
			}
		}
		return fieldNames;
	}
}
