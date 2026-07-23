import request from '@/utils/request'

// 查询数据同步任务列表
export function listDataTransfer(query) {
  return request({
    url: '/system/datatransfer/list',
    method: 'get',
    params: query
  })
}

// 查询数据同步任务详细
export function getDataTransfer(transferId) {
  return request({
    url: '/system/datatransfer/' + transferId,
    method: 'get'
  })
}

// 新增数据同步任务
export function addDataTransfer(data) {
  return request({
    url: '/system/datatransfer',
    method: 'post',
    data: data
  })
}

// 修改数据同步任务
export function updateDataTransfer(data) {
  return request({
    url: '/system/datatransfer',
    method: 'put',
    data: data
  })
}

// 删除数据同步任务
export function delDataTransfer(transferId) {
  return request({
    url: '/system/datatransfer/' + transferId,
    method: 'delete'
  })
}

// 执行同步任务
export function executeTransfer(transferId) {
  return request({
    url: '/system/datatransfer/execute/' + transferId,
    method: 'post'
  })
}

// 预览同步（查询匹配的表列表）
export function previewTransfer(transferId) {
  return request({
    url: '/system/datatransfer/preview/' + transferId,
    method: 'get'
  })
}

// 查询同步日志列表（带分页）
export function listTransferLog(query) {
  return request({
    url: '/system/datatransfer/log/list',
    method: 'get',
    params: query
  })
}

// 查询表级最后同步时间戳
export function getTransferProgress(transferId) {
  return request({
    url: '/system/datatransfer/progress/' + transferId,
    method: 'get'
  })
}

// 从目标表最大时间戳刷新表级同步进度
export function refreshTransferProgress(transferId) {
  return request({
    url: '/system/datatransfer/progress/refresh/' + transferId,
    method: 'post'
  })
}

// 获取同步进度（异步执行时查询进度）
export function getTransferSyncProgress(transferId) {
  return request({
    url: '/system/datatransfer/syncProgress/' + transferId,
    method: 'get'
  })
}

// 停止同步任务
export function stopTransfer(transferId) {
  return request({
    url: '/system/datatransfer/stop/' + transferId,
    method: 'post'
  })
}
