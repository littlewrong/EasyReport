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
package cn.easyreport.expression.function;

import java.util.List;

import cn.easyreport.build.Context;
import cn.easyreport.expression.model.data.ExpressionData;
import cn.easyreport.expression.model.data.ObjectExpressionData;
import cn.easyreport.model.Cell;

/**
 * 按数据集列序号返回列名，序号从1开始。
 * 用法：field_name(datasetName, index)
 * @author codex
 */
public class FieldNameFunction implements Function{
    @Override
    public Object execute(List<ExpressionData<?>> dataList, Context context, Cell currentCell) {
        if(dataList==null || dataList.size()<2){
            return null;
        }
        String datasetName=null;
        Integer index=null;
        ExpressionData<?> dsData=dataList.get(0);
        if(dsData instanceof ObjectExpressionData){
            ObjectExpressionData obj=(ObjectExpressionData)dsData;
            if(obj.getData()!=null){
                datasetName=obj.getData().toString();
            }
        }
        ExpressionData<?> idxData=dataList.get(1);
        if(idxData instanceof ObjectExpressionData){
            ObjectExpressionData obj=(ObjectExpressionData)idxData;
            if(obj.getData()!=null){
                try{
                    index=Integer.valueOf(obj.getData().toString());
                }catch(Exception ex){
                    index=null;
                }
            }
        }
        if(datasetName==null || index==null || index<1){
            return null;
        }
        List<String> fields=context.getDatasetFields(datasetName);
        if(fields==null || fields.isEmpty()){
            return null;
        }
        if(index>fields.size()){
            return null;
        }
        return fields.get(index-1);
    }

    @Override
    public String name() {
        return "field_name";
    }
}
