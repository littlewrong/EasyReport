import type { RouteRecordRaw } from 'vue-router';
import { LOGIN_PATH } from '@vben/constants';
import { preferences } from '@vben/preferences';

const fallbackNotFoundRoute: RouteRecordRaw = {
  component: () => import('#/views/_core/fallback/not-found.vue'),
  meta: { hideInBreadcrumb: true, hideInMenu: true, hideInTab: true, title: '404' },
  name: 'FallbackNotFound',
  path: '/:path(.*)*',
};
const coreRoutes: RouteRecordRaw[] = [
  {
    component: () => import('#/layouts/basic.vue'),
    meta: { hideInBreadcrumb: true, title: 'Root' },
    name: 'Root',
    path: '/',
    redirect: preferences.app.defaultHomePath,
    children: [],
  },
  {
    component: () => import('#/layouts/auth.vue'),
    meta: { hideInTab: true, title: '登录' },
    name: 'Authentication',
    path: '/auth',
    redirect: LOGIN_PATH,
    children: [{
      name: 'Login',
      path: 'login',
      component: () => import('#/views/_core/authentication/login.vue'),
      meta: { title: '登录' },
    }],
  },
  { name: 'LegacyLogin', path: '/login', redirect: LOGIN_PATH, meta: { title: '登录' } },
];
export { coreRoutes, fallbackNotFoundRoute };
