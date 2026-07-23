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
          '/dsapi/http': {
            changeOrigin: true,
            rewrite: (path) => path.replace(/^\/dsapi\/http/, ''),
            target: 'http://localhost:5321/api',
          },
          '/dsapi/sql': {
            changeOrigin: true,
            rewrite: (path) => path.replace(/^\/dsapi\/sql/, ''),
            target: 'http://localhost:5321/api',
          },
          '/dsapi/python': {
            changeOrigin: true,
            rewrite: (path) => path.replace(/^\/dsapi\/python/, ''),
            target: 'http://localhost:5321/api',
          },
          '/biapi': {
            changeOrigin: true,
            rewrite: (path) => path.replace(/^\/biapi/, ''),
            // 其余系统、大屏接口仍然进入主 API。
            target: 'http://localhost:5320/api',
            ws: true,
          },
        },
      },
    },
  };
});
