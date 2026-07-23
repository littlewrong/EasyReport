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
package cn.easyreport.expression.model.expr.ifelse;

import cn.easyreport.build.Context;
import cn.easyreport.expression.model.data.ExpressionData;
import cn.easyreport.expression.model.expr.BaseExpression;
import cn.easyreport.expression.model.expr.ExpressionBlock;
import cn.easyreport.model.Cell;

/**
 * @author Jacky.gao
 * @since 2017年1月16日
 */
public class ElseIfExpression extends BaseExpression {
	private static final long serialVersionUID = -198920923804292977L;
	private ExpressionConditionList conditionList;
	
	private ExpressionBlock expression;
	@Override
	protected ExpressionData<?> compute(Cell cell,Cell currentCell, Context context) {
		return expression.execute(cell, currentCell,context);
	}
	public boolean conditionsEval(Cell cell,Cell currentCell, Context context) {
		return conditionList.eval(context, cell,currentCell);
	}
	public void setConditionList(ExpressionConditionList conditionList) {
		this.conditionList = conditionList;
	}
	public ExpressionBlock getExpression() {
		return expression;
	}
	public void setExpression(ExpressionBlock expression) {
		this.expression = expression;
	}
}
