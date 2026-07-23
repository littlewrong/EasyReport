import axios from 'axios'
import { API_BASE } from '@/api/config'
import { getToken, setToken, clearToken } from '@/utils/token'

export interface LoginParams {
  username: string
  password: string
}

export interface LoginResult {
  accessToken: string
  homePath?: string
  id?: number
  realName?: string
  roles?: string[]
  username?: string
}

export const loginApi = async (params: LoginParams): Promise<LoginResult> => {
  const res = await axios.post(`${API_BASE}/auth/login`, params, {
    withCredentials: true
  })
  const data = res.data?.data ?? res.data
  const token = data?.accessToken
  if (!token) throw new Error(res.data?.message || '登录接口未返回 accessToken')
  setToken(token)
  return data
}

/**
 * * 刷新 access token。
 * 后端 `/auth/refresh` 依赖 httpOnly 的 refresh cookie（浏览器自动携带），
 * 直接返回新的 access token 字符串。这里用裸 axios 调用，避免触发主实例
 * 响应拦截器里的 401 处理造成递归。
 */
export const refreshToken = async (): Promise<string> => {
  const res = await axios.post(`${API_BASE}/auth/refresh`, null, {
    withCredentials: true
  })
  // 兼容：直接返回字符串 或 {code,data} 包装
  const token = typeof res.data === 'string' ? res.data : res.data?.data
  if (!token) throw new Error('refresh token empty')
  setToken(token)
  return token
}

/**
 * 登录态彻底失效（refresh 也过期）时的处理。
 */
export const onAuthExpired = () => {
  clearToken()
  stopAutoRefresh()
  window['$message']?.error?.('登录已过期，请重新登录')
}

/**
 * 解析 JWT 的过期时间（毫秒）。解析失败返回 0。
 */
const parseExp = (token: string): number => {
  try {
    const base64 = token.split('.')[1]
    const json = decodeURIComponent(
      escape(window.atob(base64.replace(/-/g, '+').replace(/_/g, '/')))
    )
    const payload = JSON.parse(json)
    return payload.exp ? payload.exp * 1000 : 0
  } catch {
    return 0
  }
}

let timer: ReturnType<typeof setTimeout> | null = null

/**
 * * 在 token 过期前主动续期，规避使用过程中掉线。
 * 按 JWT 的 exp 提前 2 分钟刷新；无法解析 exp 时兜底 100 分钟后刷新。
 */
export const startAutoRefresh = () => {
  stopAutoRefresh()
  const schedule = () => {
    const token = getToken()
    if (!token) return
    const exp = parseExp(token)
    const now = Date.now()
    // 提前 2 分钟续期，至少 10 秒后执行，避免 exp 已过期时立即死循环
    const delay = exp ? Math.max(exp - now - 120_000, 10_000) : 100 * 60_000
    timer = setTimeout(async () => {
      try {
        await refreshToken()
      } catch (error) {
        console.error('[token] 自动续期失败', error)
        onAuthExpired()
        return
      }
      schedule()
    }, delay)
  }
  schedule()
}

export const stopAutoRefresh = () => {
  if (timer) {
    clearTimeout(timer)
    timer = null
  }
}
