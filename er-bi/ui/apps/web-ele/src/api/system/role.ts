import type { Recordable } from '@vben/types';

import { requestClient } from '#/api/request';

export namespace SystemRoleApi {
  export interface SystemRole {
    [key: string]: any;
    /** 角色ID */
    id: number;
    /** 角色名称 */
    name: string;
    /** 拥有的菜单权限（菜单ID列表） */
    permissions: number[];
    /** 备注 */
    remark?: string;
    /** 状态：1 启用 0 禁用 */
    status: 0 | 1;
  }
}

/**
 * 获取角色列表数据（分页）
 */
async function getRoleList(params: Recordable<any>) {
  return requestClient.get<{
    items: Array<SystemRoleApi.SystemRole>;
    total: number;
  }>('/system/role/list', { params });
}

/**
 * 创建角色
 * @param data 角色数据
 */
async function createRole(data: Omit<SystemRoleApi.SystemRole, 'id'>) {
  return requestClient.post('/system/role', data);
}

/**
 * 更新角色
 *
 * @param id 角色 ID
 * @param data 角色数据
 */
async function updateRole(
  id: SystemRoleApi.SystemRole['id'],
  data: Partial<Omit<SystemRoleApi.SystemRole, 'id'>>,
) {
  return requestClient.put(`/system/role/${id}`, data);
}

/**
 * 删除角色
 * @param id 角色 ID
 */
async function deleteRole(id: SystemRoleApi.SystemRole['id']) {
  return requestClient.delete(`/system/role/${id}`);
}

export { createRole, deleteRole, getRoleList, updateRole };
