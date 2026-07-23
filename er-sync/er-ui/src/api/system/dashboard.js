import request from '@/utils/request'

// KPI汇总
export function getSummary() {
  return request({
    url: '/system/dashboard/summary',
    method: 'get'
  })
}

// 近7天同步趋势
export function getSyncTrend() {
  return request({
    url: '/system/dashboard/syncTrend',
    method: 'get'
  })
}

// 数据源类型分布
export function getDsTypeStats() {
  return request({
    url: '/system/dashboard/dsTypeStats',
    method: 'get'
  })
}

// 任务状态分布
export function getTaskStatus() {
  return request({
    url: '/system/dashboard/taskStatus',
    method: 'get'
  })
}

// 最近同步记录
export function getRecentLogs() {
  return request({
    url: '/system/dashboard/recentLogs',
    method: 'get'
  })
}
