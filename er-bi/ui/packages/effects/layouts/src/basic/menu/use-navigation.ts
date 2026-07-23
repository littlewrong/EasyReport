import type { RouteRecordNormalized } from 'vue-router';

import { useRouter } from 'vue-router';

import { useAccessStore } from '@vben/stores';
import { isHttpUrl, openRouteInNewWindow, openWindow } from '@vben/utils';

function useNavigation() {
  const router = useRouter();
  const accessStore = useAccessStore();
  const routeMetaMap = new Map<string, RouteRecordNormalized>();

  // 对标记了 attachToken 的外链（如 BI 大屏设计器），打开时动态带上当前登录 token
  const appendToken = (url: string): string => {
    const token = accessStore.accessToken;
    if (!token) return url;
    try {
      const parsedUrl = new URL(url, window.location.origin);
      parsedUrl.searchParams.set('token', token);
      return parsedUrl.toString();
    } catch {
      const sep = url.includes('?') ? '&' : '?';
      return `${url}${sep}token=${encodeURIComponent(token)}`;
    }
  };

  const shouldAttachToken = (
    route: RouteRecordNormalized | undefined,
  ): boolean => {
    const meta = route?.meta as Record<string, unknown> | undefined;
    return meta?.attachToken === true || route?.name === 'BiDesigner';
  };

  const findRouteByPathOrLink = (
    path: string,
  ): RouteRecordNormalized | undefined => {
    const route = routeMetaMap.get(path);
    if (route) return route;

    for (const item of routeMetaMap.values()) {
      if (item.meta?.link === path) return item;
    }
  };

  // 初始化路由映射
  const initRouteMetaMap = () => {
    const routes = router.getRoutes();
    routes.forEach((route) => {
      routeMetaMap.set(route.path, route);
    });
  };

  initRouteMetaMap();

  // 监听路由变化
  router.afterEach(() => {
    initRouteMetaMap();
  });

  // 检查是否应该在新窗口打开
  const shouldOpenInNewWindow = (path: string): boolean => {
    if (isHttpUrl(path)) {
      return true;
    }
    const route = routeMetaMap.get(path);
    // 如果有外链或者设置了在新窗口打开，返回 true
    return !!(route?.meta?.link || route?.meta?.openInNewWindow);
  };

  const resolveHref = (path: string): string => {
    return router.resolve(path).href;
  };

  const navigation = async (path: string) => {
    try {
      const route = findRouteByPathOrLink(path);
      const { openInNewWindow = false, query = {}, link } = route?.meta ?? {};

      // 检查是否有外链
      if (link && typeof link === 'string') {
        const target = shouldAttachToken(route) ? appendToken(link) : link;
        openWindow(target, { target: '_blank' });
        return;
      }

      if (isHttpUrl(path)) {
        const target = shouldAttachToken(route) ? appendToken(path) : path;
        openWindow(target, { target: '_blank' });
      } else if (openInNewWindow) {
        openRouteInNewWindow(resolveHref(path));
      } else {
        await router.push({
          path,
          query,
        });
      }
    } catch (error) {
      console.error('Navigation failed:', error);
      throw error;
    }
  };

  const willOpenedByWindow = (path: string) => {
    return shouldOpenInNewWindow(path);
  };

  return { navigation, willOpenedByWindow };
}

export { useNavigation };
