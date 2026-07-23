import { defineOverridesPreferences } from '@vben/preferences';

/**
 * @description 项目配置文件
 * 只需要覆盖项目中的一部分配置，不需要的配置不用覆盖，会自动使用默认配置
 * !!! 更改配置后请清空缓存，否则可能不生效
 */
export const overridesPreferences = defineOverridesPreferences({
  // overrides
  app: {
    // 后端权限模式：菜单与路由由后端 /menu/all 接口下发
    accessMode: 'backend',
    defaultHomePath: '/workspace',
    name: import.meta.env.VITE_APP_TITLE,
  },
  copyright: {
    companyName: '易报表',
    companySiteLink: '',
    date: '2026',
  },
  logo: {
    source: '',
    sourceDark: '',
  },
});
