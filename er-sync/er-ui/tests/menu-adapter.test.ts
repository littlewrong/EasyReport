import { createRequire } from 'node:module';

import { describe, expect, it, vi } from 'vitest';

import { adaptLegacyMenus } from '../apps/web-ele/src/api/core/menu-adapter';
import { generateMenus } from '../packages/utils/src/helpers/generate-menus';
import { generateRoutesByBackend } from '../packages/utils/src/helpers/generate-routes-backend';

// Resolve the app's declared dependency, without adding vue-router to the
// monorepo root solely for this integration test.
const { createMemoryHistory, createRouter } = createRequire(
  new URL('../apps/web-ele/package.json', import.meta.url),
)('vue-router');

describe('er-api menu conversion', () => {
  it('preserves database URLs through multiple directory levels', () => {
    const result = adaptLegacyMenus([
      {
        path: '/databasesource',
        component: 'Layout',
        name: 'Databasesource',
        children: [
          {
            path: 'datasource',
            component: 'system/datasource/index',
            name: 'Datasource',
            meta: { title: 'DB 数据源' },
          },
        ],
      },
      {
        path: '/dbsync',
        component: 'Layout',
        name: 'Dbsync',
        children: [
          {
            path: 'datatransfer',
            component: 'system/datatransfer/index',
            name: 'DataTransfer',
          },
          {
            path: 'combinesync',
            component: 'system/combinesync/index',
            name: 'DataCombine',
          },
        ],
      },
      {
        path: '/system',
        component: 'Layout',
        name: 'System',
        children: [
          {
            path: 'monitor',
            component: 'ParentView',
            name: 'Monitor',
            children: [
              {
                path: 'server',
                component: 'monitor/server/index',
                name: 'Server',
              },
            ],
          },
        ],
      },
    ]);
    expect(
      result.find((route) => route.name === 'Databasesource')?.children?.[0],
    ).toMatchObject({
      path: '/databasesource/datasource',
      component: '/system/datasource/index',
    });
    expect(
      result
        .find((route) => route.name === 'Dbsync')
        ?.children?.map((route) => route.path),
    ).toEqual(['/dbsync/datatransfer', '/dbsync/combinesync']);
    expect(
      result.find((route) => route.name === 'System')?.children?.[0]
        ?.children?.[0]?.path,
    ).toBe('/system/monitor/server');
    expect(
      result.find((route) => route.name === 'System')?.children?.[0]?.component,
    ).toBe('ParentView');
  });

  it('converts cache, hidden, affix, breadcrumb, query, and icons without mutating input', () => {
    const input = [
      {
        path: '/users',
        name: 'Users',
        component: 'system/user/index',
        hidden: true,
        query: '{"status":"0","deptId":10}',
        meta: {
          title: '用户管理',
          icon: 'user',
          noCache: true,
          affix: true,
          breadcrumb: false,
        },
      },
    ];
    const snapshot = JSON.stringify(input);
    const route = adaptLegacyMenus(input).find((item) => item.name === 'Users');
    expect(route?.meta).toMatchObject({
      affixTab: true,
      hideInMenu: true,
      hideInBreadcrumb: true,
      keepAlive: false,
      icon: 'lucide:user-round',
      query: { status: '0', deptId: 10 },
      title: '用户管理',
    });
    expect(JSON.stringify(input)).toBe(snapshot);
  });

  it('replaces RuoYi noRedirect with a valid first visible child path', () => {
    const routes = adaptLegacyMenus([
      {
        path: '/system',
        component: 'Layout',
        name: 'System',
        redirect: 'noRedirect',
        children: [
          {
            path: 'hidden',
            name: 'Hidden',
            component: 'system/config/index',
            hidden: true,
          },
          { path: 'user', name: 'Users', component: 'system/user/index' },
        ],
      },
    ]);
    expect(routes.find((route) => route.name === 'System')?.redirect).toBe(
      '/system/user',
    );
  });

  it('keeps InnerLink in the application while external menus open a new window', () => {
    const routes = adaptLegacyMenus([
      {
        path: '/docs',
        name: 'Docs',
        component: 'InnerLink',
        meta: { link: 'https://example.com/docs' },
      },
      { path: 'https://example.com', name: 'Website', component: 'Layout' },
    ]);
    const iframe = routes.find((route) => route.name === 'Docs');
    expect(iframe).toMatchObject({
      component: 'IFrameView',
      path: '/docs',
      meta: { iframeSrc: 'https://example.com/docs' },
    });
    expect(iframe?.meta?.link).toBeUndefined();
    expect(routes.find((route) => route.name === 'Website')).toMatchObject({
      path: '/external/Website',
      meta: { link: 'https://example.com', openInNewWindow: true },
    });
  });

  it('registers hidden detail pages only for authorized permissions and respects the superuser wildcard', () => {
    expect(adaptLegacyMenus([]).map((route) => route.name)).toEqual([
      'Index',
      'Profile',
    ]);
    const restricted = adaptLegacyMenus([], {
      accessCodes: ['system:user:edit'],
    });
    expect(restricted.find((route) => route.name === 'AuthRole')).toMatchObject(
      {
        component: '/system/user/authRole',
        path: '/system/user-auth/role/:userId(\\d+)',
        meta: { hideInMenu: true },
      },
    );
    expect(restricted.some((route) => route.name === 'AuthUser')).toBe(false);
    const all = adaptLegacyMenus([], { accessCodes: ['*:*:*'] });
    expect(
      ['AuthRole', 'AuthUser', 'Data', 'JobLog', 'GenEdit'].every((name) =>
        all.some((route) => route.name === name),
      ),
    ).toBe(true);
  });

  it('highlights the actual backend menu path on hidden pages', () => {
    const routes = adaptLegacyMenus(
      [
        {
          path: '/system',
          name: 'System',
          component: 'Layout',
          children: [
            { path: 'job', name: 'Job', component: 'monitor/job/index' },
          ],
        },
      ],
      { accessCodes: ['monitor:job:list'] },
    );
    expect(
      routes.find((route) => route.name === 'JobLog')?.meta?.activePath,
    ).toBe('/system/job');
  });

  it('filters explicit permission/role entries and removes empty groups', () => {
    const menus = [
      {
        path: '/restricted',
        name: 'Restricted',
        component: 'ParentView',
        children: [
          {
            path: 'write',
            name: 'Write',
            component: 'system/config/index',
            permissions: ['system:config:edit'],
          },
        ],
      },
      {
        path: '/role',
        name: 'RoleOnly',
        component: 'system/role/index',
        roles: ['operator'],
      },
    ];
    expect(adaptLegacyMenus(menus).map((route) => route.name)).toEqual([
      'Index',
      'Profile',
    ]);
    const routes = adaptLegacyMenus(menus, {
      accessCodes: ['system:config:edit'],
      roles: ['operator'],
    });
    expect(routes.some((route) => route.name === 'Restricted')).toBe(true);
    expect(routes.some((route) => route.name === 'RoleOnly')).toBe(true);
  });

  it('handles blank or duplicate names and malformed optional query parameters', () => {
    const routes = adaptLegacyMenus([
      {
        path: '/',
        component: 'Layout',
        name: '',
        children: [
          {
            path: 'child',
            component: 'system/config/index',
            name: 'Index',
            query: 'invalid json',
          },
        ],
      },
      { path: '/another', component: 'system/config/index', name: 'Index' },
    ]);
    const names = routes.flatMap((route) => [
      route.name,
      ...(route.children?.map((child) => child.name) || []),
    ]);
    expect(new Set(names).size).toBe(names.length);
    expect(routes[1]?.children?.[0]?.path).toBe('/child');
    expect(routes[1]?.children?.[0]?.meta?.query).toBeUndefined();
  });
});

describe('Vben backend route generation for legacy directories', () => {
  const createPage = () => ({ render: () => null });
  const layoutLoader = async () => createPage();
  const onlineLoader = async () => createPage();
  const buildLoader = async () => createPage();
  const fallbackLoader = async () => createPage();
  const pageMap = {
    '../views/index.vue': async () => createPage(),
    '../views/system/user/profile/index.vue': async () => createPage(),
    '../views/monitor/online/index.vue': onlineLoader,
    '../views/tool/build/index.vue': buildLoader,
    '../views/_core/fallback/not-found.vue': fallbackLoader,
  };

  function nestedMenus() {
    return adaptLegacyMenus([
      {
        path: '/system',
        name: 'System',
        component: 'Layout',
        meta: { title: '系统管理' },
        children: [
          {
            path: 'monitor',
            name: 'Monitor',
            component: 'ParentView',
            meta: { title: '系统监控' },
            children: [
              {
                path: 'online',
                name: 'Online',
                component: 'monitor/online/index',
                meta: { title: '在线用户' },
              },
            ],
          },
          {
            path: 'tool',
            name: 'Tool',
            component: 'ParentView',
            meta: { title: '系统工具' },
            children: [
              {
                path: 'build',
                name: 'Build',
                component: 'tool/build/index',
                meta: { title: '表单构建' },
              },
            ],
          },
        ],
      },
    ]);
  }

  async function generate(menuRoutes = nestedMenus()) {
    const router = createRouter({ history: createMemoryHistory(), routes: [] });
    const parentLoader = vi.fn(async () => createPage());
    const generated = await generateRoutesByBackend({
      fetchMenuListAsync: async () => menuRoutes,
      // A stale ParentView registration must not reintroduce a nested view.
      layoutMap: { BasicLayout: layoutLoader, ParentView: parentLoader },
      pageMap,
      router,
      routes: [],
    });
    generated.forEach((route) => router.addRoute(route));
    return { generated, parentLoader, router };
  }

  it('removes directory component properties while keeping real layouts and pages', async () => {
    const { generated, parentLoader } = await generate();
    const system = generated.find((route) => route.name === 'System')!;
    const monitor = system.children![0]!;
    expect(system.component).toBe(layoutLoader);
    expect(Object.hasOwn(monitor, 'component')).toBe(false);
    expect(monitor).toMatchObject({
      name: 'Monitor',
      path: '/system/monitor',
      redirect: '/system/monitor/online',
      meta: { title: '系统监控' },
    });
    expect(monitor.children![0]!.component).toBe(onlineLoader);
    expect(monitor.children![0]!.meta?.keepAlive).toBe(true);
    expect(parentLoader).not.toHaveBeenCalled();
  });

  it('lets Vue Router skip pure directory records across sibling page navigation', async () => {
    const { router } = await generate();
    const resolved = router.resolve('/system/monitor/online');
    expect(
      resolved.matched.map((route: { name: string }) => route.name),
    ).toEqual(['System', 'Monitor', 'Online']);
    expect(resolved.matched[1].components).toBeUndefined();
    expect(resolved.matched[2].components.default).toBe(onlineLoader);

    await router.push('/system');
    expect(router.currentRoute.value.name).toBe('Online');
    await router.push('/system/tool/build');
    expect(router.currentRoute.value.name).toBe('Build');
    expect(router.currentRoute.value.matched[1].components).toBeUndefined();
    await router.push({ name: 'Online' });
    expect(router.currentRoute.value.path).toBe('/system/monitor/online');
  });

  it('retains the complete menu hierarchy after removing directory components', async () => {
    const { generated, router } = await generate();
    const menus = generateMenus(generated, router);
    const system = menus.find((menu) => menu.path === '/system')!;
    expect(system.name).toBe('系统管理');
    expect(system.children?.map((menu) => menu.name)).toEqual([
      '系统监控',
      '系统工具',
    ]);
    expect(system.children?.[0]?.children?.[0]).toMatchObject({
      name: '在线用户',
      path: '/system/monitor/online',
    });
    expect(system.children?.[1]?.children?.[0]).toMatchObject({
      name: '表单构建',
      path: '/system/tool/build',
    });
    expect(menus.some((menu) => menu.path === '/user/profile')).toBe(false);
  });

  it('also removes blank directory markers without changing invalid leaf fallback behavior', async () => {
    const missingPage = vi.spyOn(console, 'error').mockImplementation(() => {});
    try {
      const { generated } = await generate([
        {
          path: '/empty-marker',
          name: 'EmptyMarker',
          component: '',
          meta: { title: '目录' },
          children: [
            {
              path: '/empty-marker/online',
              name: 'EmptyOnline',
              component: '/monitor/online/index',
              meta: { title: '在线用户' },
            },
          ],
        },
        {
          path: '/missing-page',
          name: 'MissingPage',
          component: '/missing-page',
          meta: { title: '未知页面' },
        },
      ]);
      expect(Object.hasOwn(generated[0]!, 'component')).toBe(false);
      expect(generated[0]?.children?.[0]?.component).toBe(onlineLoader);
      expect(generated[1]?.component).toBe(fallbackLoader);
      expect(missingPage).toHaveBeenCalledTimes(1);
    } finally {
      missingPage.mockRestore();
    }
  });
});
