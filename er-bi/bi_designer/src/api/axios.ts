import axios, { AxiosResponse, InternalAxiosRequestConfig, AxiosError } from 'axios'
import { ResultEnum } from "@/enums/httpEnum"
import { ErrorPageNameMap } from "@/enums/pageEnum"
import { redirectErrorPage } from '@/utils'
import { getToken } from '@/utils/token'
import { refreshToken, onAuthExpired } from '@/api/auth'

const axiosInstance = axios.create({
  baseURL: import.meta.env.DEV ? import.meta.env.VITE_DEV_PATH : import.meta.env.VITE_PRO_PATH,
  timeout: ResultEnum.TIMEOUT,
  withCredentials: true,
})

// 请求拦截器：注入登录 token
axiosInstance.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = getToken()
    if (token) {
      config.headers = config.headers || {}
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error: AxiosError) => {
    return Promise.reject(error)
  }
)

// 单飞：并发的 401 共用同一次刷新
let refreshing: Promise<string> | null = null

// 响应拦截器
axiosInstance.interceptors.response.use(
  (res: AxiosResponse) => {
    const { code } = res.data as { code: number }
    if (code === undefined || code === null) return Promise.resolve(res.data)
    if (code === ResultEnum.DATA_SUCCESS) return Promise.resolve(res.data)
    // 重定向
    if (ErrorPageNameMap.get(code)) redirectErrorPage(code)
    return Promise.resolve(res.data)
  },
  async (err: AxiosError) => {
    const config = err.config as (InternalAxiosRequestConfig & { __isRetry?: boolean }) | undefined
    // access token 过期（HTTP 401）：刷新一次并重试原请求
    if (err.response?.status === 401 && config && !config.__isRetry) {
      try {
        if (!refreshing) refreshing = refreshToken()
        const newToken = await refreshing
        refreshing = null
        config.__isRetry = true
        config.headers = config.headers || {}
        config.headers.Authorization = `Bearer ${newToken}`
        return axiosInstance(config)
      } catch (refreshErr) {
        refreshing = null
        onAuthExpired()
        return Promise.reject(refreshErr)
      }
    }
    return Promise.reject(err)
  }
)

export default axiosInstance
