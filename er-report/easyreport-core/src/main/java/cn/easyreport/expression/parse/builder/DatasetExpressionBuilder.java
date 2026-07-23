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
package cn.easyreport.expression.parse.builder;

import org.antlr.v4.runtime.tree.TerminalNode;

import cn.easyreport.definition.Order;
import cn.easyreport.definition.value.AggregateType;
import cn.easyreport.dsl.ReportParserParser.ConditionsContext;
import cn.easyreport.dsl.ReportParserParser.DatasetContext;
import cn.easyreport.dsl.ReportParserParser.UnitContext;
import cn.easyreport.expression.model.condition.BaseCondition;
import cn.easyreport.expression.model.expr.BaseExpression;
import cn.easyreport.expression.model.expr.dataset.DatasetExpression;

/**
 * @author Jacky.gao
 * @since 2016年12月26日
 */
public class DatasetExpressionBuilder extends BaseExpressionBuilder {
	@Override
	public BaseExpression build(UnitContext unitContext) {
		DatasetContext context=(DatasetContext)unitContext.dataset();
		DatasetExpression expr=new DatasetExpression();
		expr.setExpr(context.getText());
		expr.setDatasetName(context.Identifier().getText());
		expr.setAggregate(AggregateType.valueOf(context.aggregate().getText()));
		if(context.property()!=null){
			expr.setProperty(context.property().getText());			
		}
		ConditionsContext conditionsContext=context.conditions();
		if(conditionsContext!=null){
			BaseCondition condition=buildConditions(conditionsContext);
			expr.setCondition(condition);
		}
		TerminalNode orderNode=context.ORDER();
		if(orderNode!=null){
			expr.setOrder(Order.valueOf(orderNode.getText()));
		}
		return expr;
	}

	@Override
	public boolean support(UnitContext unitContext) {
		return unitContext.dataset()!=null;
	}
}
