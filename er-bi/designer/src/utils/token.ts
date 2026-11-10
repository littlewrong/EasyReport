import { StorageEnum } from '@/enums/storageEnum'
import { getLocalStorage, setLocalStorage, clearLocalStorage } from '@/utils/storage'

const TOKEN_KEY = StorageEnum.GO_ACCESS_TOKEN_STORE
const TOKEN_QUERY_KEYS = ['token', 'accessToken']

const normalizeToken = (token: string) => token.replace(/^Bearer\s+/i, '').trim()

const takeTokenFromSearchParams = (params: URLSearchParams) => {
  for (const key of TOKEN_QUERY_KEYS) {
    const token = params.get(key)
    if (token) return normalizeToken(token)
  }
  return ''
}

const deleteTokenSearchParams = (params: URLSearchParams) => {
  TOKEN_QUERY_KEYS.forEach(key => params.delete(key))
}

/**
 * 获取本地存储的登录 token
 */
export const getToken = (): string => {
  return getLocalStorage(TOKEN_KEY) || ''
}

/**
 * 写入登录 token
 */
export const setToken = (token: string) => {
  setLocalStorage(TOKEN_KEY, normalizeToken(token))
}

/**
 * 清除登录 token
 */
export const clearToken = () => {
  clearLocalStorage(TOKEN_KEY)
}

/**
 * * 从打开窗口的 URL 中捕获主系统带入的 token 并落地，随后清理地址栏。
 * 主系统（vben）打开大屏设计器新窗口时会拼接 `?token=xxx`，
 * 这里在应用启动最早阶段同步取出，保证后续请求拦截器能拿到。
 */
export const captureTokenFromUrl = () => {
  try {
    const url = new URL(window.location.href)
    let token = takeTokenFromSearchParams(url.searchParams)
    if (token) {
      deleteTokenSearchParams(url.searchParams)
    }

    // 兼容形如 /index.html#/project/items?token=xxx 的 hash 路由地址
    const [hashPath, hashSearch = ''] = url.hash.split('?')
    if (hashSearch) {
      const hashParams = new URLSearchParams(hashSearch)
      token = token || takeTokenFromSearchParams(hashParams)
      deleteTokenSearchParams(hashParams)
      const nextHashSearch = hashParams.toString()
      url.hash = nextHashSearch ? `${hashPath}?${nextHashSearch}` : hashPath
    }

    if (token) {
      setToken(token)
      // 清理地址栏中的 token，避免泄漏与刷新残留
      window.history.replaceState(
        {},
        document.title,
        `${url.pathname}${url.search}${url.hash}`
      )
    }
  } catch (error) {
    console.error('[token] 捕获 URL token 失败', error)
  }
}
