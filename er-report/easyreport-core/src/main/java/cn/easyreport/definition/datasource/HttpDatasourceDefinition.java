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
package cn.easyreport.definition.datasource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import cn.easyreport.build.Dataset;
import cn.easyreport.definition.dataset.DatasetDefinition;
import cn.easyreport.definition.dataset.HttpDatasetDefinition;

/**
 * HTTP数据源定义
 * @since 2025年1月14日
 */
public class HttpDatasourceDefinition implements DatasourceDefinition {
	private String name;
	private List<DatasetDefinition> datasets;

	public List<Dataset> buildDatasets(Map<String,Object> parameters){
		if(datasets==null || datasets.size()==0){
			return null;
		}
		List<Dataset> list=new ArrayList<Dataset>();
		for(DatasetDefinition dsDef:datasets){
			HttpDatasetDefinition httpDataset=(HttpDatasetDefinition)dsDef;
			Dataset ds=httpDataset.buildDataset(parameters);
			list.add(ds);
		}
		return list;
	}

	@Override
	public DatasourceType getType() {
		return DatasourceType.http;
	}

	@Override
	public List<DatasetDefinition> getDatasets() {
		return datasets;
	}

	public void setDatasets(List<DatasetDefinition> datasets) {
		this.datasets = datasets;
	}

	@Override
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}
}
