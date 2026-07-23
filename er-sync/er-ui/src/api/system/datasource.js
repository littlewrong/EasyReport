import request from '@/utils/request'

// 查询数据源管理列表
export function listDatasource(query) {
  return request({
    url: '/system/datasource/list',
    method: 'get',
    params: query
  })
}

// 查询数据源管理详细
export function getDatasource(datasourceId) {
  return request({
    url: '/system/datasource/' + datasourceId,
    method: 'get'
  })
}

// 新增数据源管理
export function addDatasource(data) {
  return request({
    url: '/system/datasource',
    method: 'post',
    data: data
  })
}

// 修改数据源管理
export function updateDatasource(data) {
  return request({
    url: '/system/datasource',
    method: 'put',
    data: data
  })
}

// 删除数据源管理
export function delDatasource(datasourceId) {
  return request({
    url: '/system/datasource/' + datasourceId,
    method: 'delete'
  })
}

// 获取数据源下拉选项
export function getDatasourceOptions() {
  return request({
    url: '/system/datasource/options',
    method: 'get'
  })
}

// 测试数据源连接
export function testConnection(data) {
  return request({
    url: '/system/datasource/test',
    method: 'post',
    data: data
  })
}
