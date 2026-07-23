import request from '@/utils/request'

// 查询数据同步任务列表
export function listDataSync(query) {
  return request({
    url: '/system/datasync/list',
    method: 'get',
    params: query
  })
}

// 查询数据同步任务详细
export function getDataSync(syncId) {
  return request({
    url: '/system/datasync/' + syncId,
    method: 'get'
  })
}

// 新增数据同步任务
export function addDataSync(data) {
  return request({
    url: '/system/datasync',
    method: 'post',
    data: data
  })
}

// 修改数据同步任务
export function updateDataSync(data) {
  return request({
    url: '/system/datasync',
    method: 'put',
    data: data
  })
}

// 删除数据同步任务
export function delDataSync(syncId) {
  return request({
    url: '/system/datasync/' + syncId,
    method: 'delete'
  })
}

// 执行同步任务
export function executeSync(syncId) {
  return request({
    url: '/system/datasync/execute/' + syncId,
    method: 'post'
  })
}

// 预览同步（查询匹配的表列表）
export function previewSync(syncId) {
  return request({
    url: '/system/datasync/preview/' + syncId,
    method: 'get'
  })
}

// 查询同步日志列表
export function listSyncLog(query) {
  return request({
    url: '/system/datasync/log/list',
    method: 'get',
    params: query
  })
}

// 根据同步任务ID查询日志
export function getSyncLogBySyncId(syncId) {
  return request({
    url: '/system/datasync/log/' + syncId,
    method: 'get'
  })
}

// 获取同步进度
export function getSyncProgress(syncId) {
  return request({
    url: '/system/datasync/progress/' + syncId,
    method: 'get'
  })
}

// 停止同步任务
export function stopSync(syncId) {
  return request({
    url: '/system/datasync/stop/' + syncId,
    method: 'post'
  })
}
