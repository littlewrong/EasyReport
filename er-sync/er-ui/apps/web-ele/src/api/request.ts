import { useAppConfig } from '@vben/hooks';
import { RequestClient } from '@vben/request';
import { useAccessStore } from '@vben/stores';

import { ElMessage } from 'element-plus';

import { useAuthStore } from '#/store';

import { SessionExpiredError } from './session-expired';

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

type SessionRequestConfig = { url?: string; headers?: { Authorization?: unknown } };

let expiringSession: Promise<void> | undefined;
async function expireSession(config: SessionRequestConfig) {
  if (config.url === '/logout') return;
  const token = useAccessStore().accessToken;
  // A delayed response from an old session must not log out a newly signed-in user.
  if (!token || config.headers?.Authorization !== `Bearer ${token}`) return;
  if (!expiringSession) {
    // Restoring a persisted session on startup is a normal check. An expired ticket
    // should lead directly to login instead of exposing the backend's diagnostic.
    if (config.url !== '/getInfo') ElMessage.warning('登录状态已过期，请重新登录');
    expiringSession = useAuthStore().logout(true, false).finally(() => {
      expiringSession = undefined;
    });
  }
  await expiringSession;
}

function isSessionRequest(config: SessionRequestConfig) {
  return config.url !== '/login' && (
    config.url === '/getInfo' || config.url === '/logout' || Boolean(config.headers?.Authorization)
  );
}
requestClient.addResponseInterceptor({
  async fulfilled(response) {
    const data = response.data;
    if (data?.code === 401 && isSessionRequest(response.config)) {
      await expireSession(response.config);
      throw new SessionExpiredError();
    }
    if (response.config.responseType === 'blob' || response.config.responseType === 'arraybuffer') return data;
    if (data?.code !== undefined && data.code !== 200) {
      const message = data.msg || '操作失败';
      ElMessage.error(message);
      throw new Error(message);
    }
    return data;
  },
  async rejected(error) {
    if (error.response?.status === 401 && isSessionRequest(error.config || {})) {
      await expireSession(error.config || {});
      throw new SessionExpiredError();
    }
    ElMessage.error(error.response?.data?.msg || error.message || '后端接口连接异常');
    return Promise.reject(error);
  },
});
export const baseRequestClient = requestClient;
