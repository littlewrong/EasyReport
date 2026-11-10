import type { ApiResult } from '#/api/request';

import { useAccessStore, useUserStore } from '@vben/stores';

import { requestClient } from '#/api/request';

import { adaptLegacyMenus } from './menu-adapter';
import type { LegacyMenuRoute } from './menu-adapter';

/** Keep the er-api menu database and its existing URLs unchanged. */
export async function getAllMenusApi() {
  const result =
    await requestClient.get<ApiResult<LegacyMenuRoute[]>>('/getRouters');
  return adaptLegacyMenus(result.data || [], {
    accessCodes: useAccessStore().accessCodes,
    roles: useUserStore().userRoles,
  });
}
