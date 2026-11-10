import type { ApiResult } from '#/api/request';
import { requestClient } from '#/api/request';

export namespace AuthApi {
  export interface LoginParams {
    username?: string;
    password?: string;
    code?: string;
    uuid?: string;
  }
}
export async function loginApi(data: AuthApi.LoginParams) {
  const result = await requestClient.post<ApiResult>('/login', data, { headers: { isToken: false } });
  return { accessToken: result.token as string };
}
export function logoutApi() { return requestClient.post('/logout'); }
export async function getAccessCodesApi() {
  const result = await requestClient.get<ApiResult>('/getInfo');
  return result.permissions as string[];
}
export function getCaptchaApi() {
  return requestClient.get<ApiResult>('/captchaImage', {
    headers: { isToken: false },
    params: { style: 'ersync' },
  });
}
