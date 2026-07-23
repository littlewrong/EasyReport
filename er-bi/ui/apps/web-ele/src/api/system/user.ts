import type { Recordable } from '@vben/types';

import { requestClient } from '#/api/request';

export namespace SystemUserApi {
  export interface SystemUser {
    [key: string]: any;
    /** 用户ID */
    id: number;
    /** 用户名 */
    username: string;
    /** 姓名 */
    realName: string;
    /** 登录后首页 */
    homePath?: string;
    /** 角色ID列表 */
    roles: number[];
    /** 角色名称列表 */
    roleNames?: string[];
    /** 备注 */
    remark?: string;
    /** 状态：1 启用 0 禁用 */
    status: 0 | 1;
  }
}

/**
 * 获取用户列表数据（分页）
 */
async function getUserList(params: Recordable<any>) {
  return requestClient.get<{
    items: Array<SystemUserApi.SystemUser>;
    total: number;
  }>('/system/user/list', { params });
}

/**
 * 创建用户（不传 password 时后端默认 123456）
 * @param data 用户数据
 */
async function createUser(data: Omit<SystemUserApi.SystemUser, 'id'>) {
  return requestClient.post('/system/user', data);
}

/**
 * 更新用户（传 password 时重置密码）
 *
 * @param id 用户 ID
 * @param data 用户数据
 */
async function updateUser(
  id: SystemUserApi.SystemUser['id'],
  data: Partial<Omit<SystemUserApi.SystemUser, 'id'>>,
) {
  return requestClient.put(`/system/user/${id}`, data);
}

/**
 * 删除用户
 * @param id 用户 ID
 */
async function deleteUser(id: SystemUserApi.SystemUser['id']) {
  return requestClient.delete(`/system/user/${id}`);
}

export { createUser, deleteUser, getUserList, updateUser };
