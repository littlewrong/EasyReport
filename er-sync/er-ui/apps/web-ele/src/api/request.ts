import { useAppConfig } from '@vben/hooks';
import { RequestClient } from '@vben/request';
import { useAccessStore } from '@vben/stores';

import { ElMessage } from 'element-plus';

import { useAuthStore } from '#/store';

/** er-api uses code=200, with data OR rows/total at the top level. */
export interface ApiResult<T = any> {
  code: number;
  data: T;
  msg?: string;
  [key: string]: any;
}
const { apiURL } = useAppConfig(import.meta.env, import.meta.env.PROD);
export const apiBaseURL = apiURL;
export const requestClient = new RequestClient({ baseURL: apiURL, timeout: 20_000 });

requestClient.addRequestInterceptor({
  fulfilled(config) {
    const token = useAccessStore().accessToken;
    if (config.headers.isToken !== false && token && !config.headers.Authorization) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    delete config.headers.isToken;
    delete config.headers.repeatSubmit;
    return config;
  },
});

let redirecting = false;
async function expireSession(url?: string) {
  if (redirecting || url === '/logout' || !useAccessStore().accessToken) return;
  redirecting = true;
  try { await useAuthStore().logout(); }
  finally { redirecting = false; }
}
requestClient.addResponseInterceptor({
  async fulfilled(response) {
    const data = response.data;
    if (response.config.responseType === 'blob' || response.config.responseType === 'arraybuffer') return data;
    if (data?.code === 401) await expireSession(response.config.url);
    if (data?.code !== undefined && data.code !== 200) {
      const message = data.msg || '操作失败';
      ElMessage.error(message);
      throw new Error(message);
    }
    return data;
  },
  async rejected(error) {
    if (error.response?.status === 401) await expireSession(error.config?.url);
    ElMessage.error(error.response?.data?.msg || error.message || '后端接口连接异常');
    return Promise.reject(error);
  },
});
export const baseRequestClient = requestClient;
