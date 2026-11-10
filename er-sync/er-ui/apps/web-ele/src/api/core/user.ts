import type { UserInfo } from '@vben/types';
import type { ApiResult } from '#/api/request';
import { useAccessStore } from '@vben/stores';
import { requestClient } from '#/api/request';

export async function getUserInfoApi(): Promise<UserInfo> {
  const result = await requestClient.get<ApiResult>('/getInfo');
  const user = result.user;
  useAccessStore().setAccessCodes(result.permissions || []);
  const baseURL = requestClient.getBaseUrl() || '';
  return {
    ...user,
    userId: String(user.userId),
    username: user.userName,
    realName: user.nickName || user.userName,
    avatar: user.avatar?.startsWith('/') ? `${baseURL}${user.avatar}` : user.avatar,
    roles: result.roles || [],
    desc: user.remark || '',
    homePath: '/index',
    token: '',
  };
}
