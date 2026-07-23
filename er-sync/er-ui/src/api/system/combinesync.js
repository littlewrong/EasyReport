import request from '@/utils/request'

// 查询合并同步任务列表
export function listCombineSync(query) {
  return request({
    url: '/system/combinesync/list',
    method: 'get',
    params: query
  })
}

// 查询合并同步任务详细
export function getCombineSync(combineId) {
  return request({
    url: '/system/combinesync/' + combineId,
    method: 'get'
  })
}

// 新增合并同步任务
export function addCombineSync(data) {
  return request({
    url: '/system/combinesync',
    method: 'post',
    data: data
  })
}

// 修改合并同步任务
export function updateCombineSync(data) {
  return request({
    url: '/system/combinesync',
    method: 'put',
    data: data
  })
}

// 删除合并同步任务
export function delCombineSync(combineId) {
  return request({
    url: '/system/combinesync/' + combineId,
    method: 'delete'
  })
}

// 执行合并同步任务
export function executeCombineSync(combineId) {
  return request({
    url: '/system/combinesync/execute/' + combineId,
    method: 'post'
  })
}

// 预览合并同步（查询匹配的分库分表列表）
export function previewCombineSync(combineId) {
  return request({
    url: '/system/combinesync/preview/' + combineId,
    method: 'get'
  })
}

// 查询合并同步日志列表（带分页）
export function listCombineLog(query) {
  return request({
    url: '/system/combinesync/log/list',
    method: 'get',
    params: query
  })
}

// 查询表级最后同步时间戳
export function getCombineProgress(combineId) {
  return request({
    url: '/system/combinesync/progress/' + combineId,
    method: 'get'
  })
}

// 从目标表最大时间戳刷新表级同步进度
export function refreshCombineProgress(combineId) {
  return request({
    url: '/system/combinesync/progress/refresh/' + combineId,
    method: 'post'
  })
}

// 获取同步进度（异步执行时查询进度）
export function getCombineSyncProgress(combineId) {
  return request({
    url: '/system/combinesync/syncProgress/' + combineId,
    method: 'get'
  })
}

// 停止合并同步任务
export function stopCombineSync(combineId) {
  return request({
    url: '/system/combinesync/stop/' + combineId,
    method: 'post'
  })
}
