import { get } from '@/api/http'
import { API_BASE } from '@/api/config'

export interface UserInfoResult {
  id: number
  username: string
  realName: string
  roles: string[]
  homePath?: null | string
  // 预留：后端 /user/info 当前未返回头像，展示时回退默认头像
  avatar?: null | string
}

/**
 * 获取当前登录用户信息（与主系统右上角展示保持一致）
 */
export const getUserInfoApi = () => {
  return get(`${API_BASE}/user/info`)
}
