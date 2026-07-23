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
package cn.easyreport.build.aggregate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import cn.easyreport.Utils;
import cn.easyreport.build.BindData;
import cn.easyreport.build.Context;
import cn.easyreport.definition.value.Value;
import cn.easyreport.exception.ReportComputeException;
import cn.easyreport.expression.model.Condition;
import cn.easyreport.expression.model.expr.dataset.DatasetExpression;
import cn.easyreport.model.Cell;
import cn.easyreport.utils.DataUtils;

/**
 * @author Jacky.gao
 * @since 2017年1月20日
 */
public class MinAggregate extends Aggregate {
	@Override
	public List<BindData> aggregate(DatasetExpression expr, Cell cell,Context context) {
		String datasetName=expr.getDatasetName();
		String property=resolveProperty(expr, context);
		Cell leftCell=DataUtils.fetchLeftCell(cell, context, datasetName);
		Cell topCell=DataUtils.fetchTopCell(cell, context, datasetName);
		List<Object> leftList=null,topList=null;
		if(leftCell!=null){
			leftList=leftCell.getBindData();
		}
		if(topCell!=null){
			topList=topCell.getBindData();
		}
		BigDecimal result=null;
		if(leftList==null && topList==null){
			List<?> data=context.getDatasetData(datasetName);
			result=buildMin(data,property,cell,expr,context);
		}else if(leftList==null){
			result=buildMin(topList,property,cell,expr,context);
		}else if(topList==null){
			result=buildMin(leftList,property,cell,expr,context);
		}else{
			List<Object> list=null;
			Object data=null;
			String prop=null;
			if(leftList.size()>topList.size()){
				list=topList;
				data=leftCell.getData();
				Value value=leftCell.getValue();
				DatasetExpression de=DataUtils.fetchDatasetExpression(value);
				if(de==null){
					throw new ReportComputeException("Unsupport value : "+value);
				}
				prop=resolveProperty(de, context);
			}else{
				list=leftList;
				data=topCell.getData();
				Value value=topCell.getValue();
				DatasetExpression de=DataUtils.fetchDatasetExpression(value);
				if(de==null){
					throw new ReportComputeException("Unsupport value : "+value);
				}
				prop=resolveProperty(de, context);
			}
			Condition condition=getCondition(cell);
			if(condition==null){
				condition=expr.getCondition();
			}
			for(Object obj:list){
				if(condition!=null && !condition.filter(cell, cell, obj, context)){
					continue;
				}
				Object o=fetchValueByProperty(obj, prop);
				if((o!=null && data!=null && (o==data || o.equals(data))) || (o==null && data==null)){
					Object value=fetchValueByProperty(obj, property);
					if(value==null || value.toString().equals("")){
						continue;
					}
					BigDecimal b=Utils.toBigDecimal(value);
					if(result==null){
						result=b;
						continue;
					}
					int v=result.compareTo(b);
					if(v==1){
						result=b;
					}
				}
			}
		}
		List<BindData> list=new ArrayList<BindData>();
		if(result!=null){
			list.add(new BindData(result.doubleValue(),null));			
		}else{
			list.add(new BindData(0,null));			
		}
		return list;
	}
	
	private BigDecimal buildMin(List<?> list,String property,Cell cell,DatasetExpression expr,Context context){
		BigDecimal result=null;
		Condition condition=getCondition(cell);
		if(condition==null){
			condition=expr.getCondition();
		}
		for(Object obj:list){
			if(condition!=null && !condition.filter(cell, cell, obj, context)){
				continue;
			}
			Object value=fetchValue(expr, context, obj);
			if(value==null || value.toString().equals("")){
				continue;
			}
			BigDecimal b=Utils.toBigDecimal(value);
			if(result==null){
				result=b;
				continue;
			}
			int v=result.compareTo(b);
			if(v==1){
				result=b;
			}
		}
		if(result==null)result=new BigDecimal(0);
		return result;
	}
}
