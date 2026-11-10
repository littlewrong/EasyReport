import { defineConfig } from '@vben/vite-config';

import ElementPlus from 'unplugin-element-plus/vite';

export default defineConfig(async () => {
  return {
    application: {},
    vite: {
      plugins: [
        ElementPlus({
          format: 'esm',
        }),
      ],
      server: {
        proxy: {
          '/erdsapi/http': {
            changeOrigin: true,
            rewrite: (path) => path.replace(/^\/erdsapi\/http/, ''),
            target: 'http://localhost:5321/api',
          },
          '/erdsapi/sql': {
            changeOrigin: true,
            rewrite: (path) => path.replace(/^\/erdsapi\/sql/, ''),
            target: 'http://localhost:5321/api',
          },
          '/erdsapi/python': {
            changeOrigin: true,
            rewrite: (path) => path.replace(/^\/erdsapi\/python/, ''),
            target: 'http://localhost:5321/api',
          },
          '/erbiapi': {
            changeOrigin: true,
            rewrite: (path) => path.replace(/^\/erbiapi/, ''),
            // 其余系统、大屏接口仍然进入主 API。
            target: 'http://localhost:5320/api',
            ws: true,
          },
        },
      },
    },
  };
});
