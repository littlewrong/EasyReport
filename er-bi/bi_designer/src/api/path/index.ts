import { get, post, del } from '@/api/http'
import { API_BASE } from '@/api/config'

// 后端 BI 大屏接口前缀，开发态经 vite proxy、生产态经 nginx 同源转发到后端
const PREFIX = `${API_BASE}/bi/project`

export interface ProjectListItem {
  id: number
  projectName: string
  state: number
  indexImage?: string | null
  remarks?: string | null
  createTime?: string | null
}

export interface ProjectDetail extends ProjectListItem {
  // 画布数据：getStorageInfo() 序列化后的 JSON 字符串
  content?: string | null
}

/**
 * 新建大屏，返回新建项目 id
 */
export const createProjectApi = (data: {
  projectName: string
  remarks?: string
  indexImage?: string
}) => {
  return post(`${PREFIX}/create`, data)
}

/**
 * 大屏分页列表
 */
export const getProjectListApi = (params: { page?: number; limit?: number }) => {
  return get(`${PREFIX}/list`, params)
}

/**
 * 获取单个大屏的画布数据（编辑/预览用）
 */
export const getProjectDataApi = (projectId: string | number) => {
  return get(`${PREFIX}/getData`, { projectId })
}

/**
 * 保存大屏画布数据
 */
export const saveProjectDataApi = (data: {
  projectId: string | number
  content: string
  indexImage?: string
}) => {
  return post(`${PREFIX}/save/data`, data)
}

/**
 * 编辑大屏基础信息（名称 / 备注 / 封面）
 */
export const editProjectApi = (data: {
  id: string | number
  projectName?: string
  remarks?: string
  indexImage?: string
}) => {
  return post(`${PREFIX}/edit`, data)
}

/**
 * 发布 / 取消发布（state: -1 未发布，1 已发布）
 */
export const publishProjectApi = (data: { id: string | number; state: number }) => {
  return post(`${PREFIX}/publish`, data)
}

/**
 * 删除大屏
 */
export const deleteProjectApi = (id: string | number) => {
  return del(`${PREFIX}/${id}`)
}
