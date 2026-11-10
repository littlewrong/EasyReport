import type { RouteRecordStringComponent } from '@vben/types';

export interface LegacyMenuRoute {
  alwaysShow?: boolean;
  children?: LegacyMenuRoute[];
  component?: string;
  hidden?: boolean;
  meta?: {
    activeMenu?: string;
    affix?: boolean;
    breadcrumb?: boolean;
    icon?: string;
    link?: string;
    noCache?: boolean;
    title?: string;
    [key: string]: unknown;
  };
  name?: string;
  path: string;
  permissions?: string[];
  query?: Record<string, unknown> | string;
  redirect?: string;
  roles?: string[];
}

interface AccessOptions {
  accessCodes?: string[];
  roles?: string[];
}

const icons: Record<string, string> = {
  '404': 'file-question',
  build: 'blocks',
  button: 'square-mouse-pointer',
  cascader: 'git-fork',
  chart: 'chart-no-axes-combined',
  checkbox: 'square-check',
  clipboard: 'clipboard',
  code: 'code-xml',
  color: 'palette',
  component: 'component',
  dashboard: 'layout-dashboard',
  date: 'calendar',
  'date-range': 'calendar-range',
  dict: 'book-open',
  documentation: 'book-open-text',
  download: 'download',
  drag: 'grip',
  druid: 'database',
  edit: 'pencil',
  education: 'graduation-cap',
  email: 'mail',
  example: 'files',
  excel: 'file-spreadsheet',
  eye: 'eye',
  'eye-open': 'eye',
  form: 'notebook-pen',
  fullscreen: 'maximize',
  github: 'github',
  guide: 'compass',
  icon: 'smile',
  input: 'text-cursor-input',
  international: 'globe',
  job: 'calendar-clock',
  language: 'languages',
  link: 'link',
  list: 'list',
  lock: 'lock-keyhole',
  log: 'logs',
  logininfor: 'log-in',
  message: 'message-square',
  money: 'banknote',
  monitor: 'monitor',
  nested: 'list-tree',
  number: 'hash',
  online: 'users-round',
  password: 'key-round',
  pdf: 'file-text',
  people: 'user-round',
  peoples: 'users-round',
  phone: 'phone',
  post: 'briefcase-business',
  question: 'circle-help',
  radio: 'circle-dot',
  rate: 'star',
  redis: 'database',
  'redis-list': 'list-tree',
  search: 'search',
  select: 'list-filter',
  server: 'server',
  shopping: 'shopping-cart',
  size: 'type',
  skill: 'lightbulb',
  slider: 'sliders-horizontal',
  star: 'star',
  swagger: 'braces',
  switch: 'toggle-right',
  system: 'settings',
  tab: 'panels-top-left',
  table: 'table',
  textarea: 'text',
  theme: 'palette',
  time: 'clock',
  'time-range': 'timer',
  tool: 'wrench',
  tree: 'network',
  'tree-table': 'list-tree',
  upload: 'upload',
  user: 'user-round',
  validCode: 'shield-check',
  zip: 'file-archive',
};

const detailRoutes = [
  {
    component: '/system/user/authRole',
    menuComponent: '/system/user/index',
    name: 'AuthRole',
    path: '/system/user-auth/role/:userId(\\d+)',
    permission: 'system:user:edit',
    title: '分配角色',
  },
  {
    component: '/system/role/authUser',
    menuComponent: '/system/role/index',
    name: 'AuthUser',
    path: '/system/role-auth/user/:roleId(\\d+)',
    permission: 'system:role:edit',
    title: '分配用户',
  },
  {
    component: '/system/dict/data',
    menuComponent: '/system/dict/index',
    name: 'Data',
    path: '/system/dict-data/index/:dictId(\\d+)',
    permission: 'system:dict:list',
    title: '字典数据',
  },
  {
    component: '/monitor/job/log',
    menuComponent: '/monitor/job/index',
    name: 'JobLog',
    path: '/monitor/job-log/index/:jobId(\\d+)',
    permission: 'monitor:job:list',
    title: '调度日志',
  },
  {
    component: '/tool/gen/editTable',
    menuComponent: '/tool/gen/index',
    name: 'GenEdit',
    path: '/tool/gen-edit/index/:tableId(\\d+)',
    permission: 'tool:gen:edit',
    title: '修改生成配置',
  },
];

function isHttp(value: string | undefined): value is string {
  return !!value && /^https?:\/\//i.test(value);
}

function parseQuery(value: LegacyMenuRoute['query']) {
  if (!value) return undefined;
  try {
    const query = typeof value === 'string' ? JSON.parse(value) : value;
    return query && typeof query === 'object' && !Array.isArray(query)
      ? { ...query }
      : undefined;
  } catch {
    // A malformed optional menu parameter must not prevent logging in.
    return undefined;
  }
}

function normalizePath(path: string, parent: string) {
  return (
    (path.startsWith('/') ? path : `${parent}/${path}`)
      .replaceAll(/\/{2,}/g, '/')
      .replace(/\/$/, '') || '/'
  );
}

function normalizeComponent(component: string) {
  return `/${component.replace(/^\/?(?:views\/)?/, '').replace(/\.vue$/, '')}`;
}

/** Pure conversion so routing can be verified without a backend or Pinia. */
export function adaptLegacyMenus(
  menus: LegacyMenuRoute[],
  { accessCodes = [], roles = [] }: AccessOptions = {},
): RouteRecordStringComponent[] {
  const reservedNames = [
    'Root',
    'Authentication',
    'Login',
    'Register',
    'FallbackNotFound',
    'Index',
    'Profile',
    ...detailRoutes.map((route) => route.name),
  ];
  const names = new Set(reservedNames);
  const componentPaths = new Map<string, string>();
  const hasPermission = (permissions: string[]) =>
    accessCodes.includes('*:*:*') ||
    permissions.some((permission) => accessCodes.includes(permission));

  function uniqueName(name: string | undefined, path: string) {
    const base = name || `Er${path.replaceAll(/[^\p{L}\p{N}]+/gu, '_')}`;
    let candidate = base;
    let suffix = 2;
    while (names.has(candidate)) candidate = `${base}_${suffix++}`;
    names.add(candidate);
    return candidate;
  }

  function convert(
    items: LegacyMenuRoute[],
    parent = '',
  ): RouteRecordStringComponent[] {
    return items.flatMap((item) => {
      // Most permissions are already filtered by getRouters. These optional
      // fields also support the legacy manually configured dynamic routes.
      if (item.permissions?.length && !hasPermission(item.permissions))
        return [];
      if (
        !item.permissions?.length &&
        item.roles?.length &&
        !roles.includes('admin') &&
        !item.roles.some((role) => roles.includes(role))
      )
        return [];

      const externalPath = isHttp(item.path) ? item.path : undefined;
      const rawPath = externalPath
        ? `external/${encodeURIComponent(item.name || item.path)}`
        : item.path;
      const path = normalizePath(rawPath, parent);
      const legacyMeta = item.meta || {};
      const children = item.children?.length
        ? convert(item.children, path)
        : undefined;
      if (item.children?.length && !children?.length) return [];

      const frame = item.component === 'InnerLink';
      const link = isHttp(legacyMeta.link) ? legacyMeta.link : externalPath;
      const directory =
        item.component === 'Layout' ||
        item.component === 'ParentView' ||
        (!item.component && !!children?.length);
      const component =
        frame || externalPath
          ? 'IFrameView'
          : directory
            ? item.component === 'Layout'
              ? 'BasicLayout'
              : 'ParentView'
            : normalizeComponent(item.component || '_core/fallback/not-found');
      const {
        activeMenu,
        affix,
        breadcrumb,
        icon,
        link: _link,
        noCache,
        ...restMeta
      } = legacyMeta;
      const meta: NonNullable<RouteRecordStringComponent['meta']> = {
        ...restMeta,
        hideInMenu: !!item.hidden,
        hideInBreadcrumb: breadcrumb === false,
        keepAlive: !noCache,
        title: legacyMeta.title || item.name || path,
      };
      if (icon && icon !== '#') {
        meta.icon =
          icon.includes(':') || icon.startsWith('/')
            ? icon
            : `lucide:${icons[icon] || 'circle'}`;
      }
      if (affix) meta.affixTab = true;
      if (activeMenu) meta.activePath = activeMenu;
      if (item.alwaysShow !== undefined) meta.alwaysShow = item.alwaysShow;
      if (item.permissions) meta.permissions = [...item.permissions];
      if (item.roles) meta.authority = [...item.roles];
      const query = parseQuery(item.query);
      if (query) meta.query = query;
      if (link) {
        if (frame) meta.iframeSrc = link;
        else {
          meta.link = link;
          meta.openInNewWindow = true;
        }
      }

      const route: RouteRecordStringComponent = {
        component,
        meta,
        name: uniqueName(item.name, path),
        path,
      };
      if (!directory && !frame && !externalPath)
        componentPaths.set(component, path);
      if (children?.length) route.children = children;
      if (item.redirect && !/^noredirect$/i.test(item.redirect)) {
        route.redirect = isHttp(item.redirect)
          ? item.redirect
          : normalizePath(item.redirect, path);
      } else if (children?.length) {
        route.redirect = (children.find((child) => !child.meta?.hideInMenu) ||
          children[0])!.path;
      }
      return [route];
    });
  }

  const adapted = convert(menus);
  const hidden = detailRoutes
    .filter((route) => hasPermission([route.permission]))
    .map((route) => ({
      component: route.component,
      meta: {
        activePath: componentPaths.get(route.menuComponent),
        hideInMenu: true,
        permissions: [route.permission],
        title: route.title,
      },
      name: route.name,
      path: route.path,
    }));

  return [
    {
      component: '/index',
      meta: {
        affixTab: true,
        icon: 'lucide:layout-dashboard',
        order: -1,
        title: '首页',
      },
      name: 'Index',
      path: '/index',
    },
    ...adapted,
    {
      alias: '/profile',
      component: '/system/user/profile/index',
      meta: { hideInMenu: true, icon: 'lucide:user-round', title: '个人中心' },
      name: 'Profile',
      path: '/user/profile',
    },
    ...hidden,
  ];
}
