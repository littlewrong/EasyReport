import { defineConfig } from '@vben/vite-config';
import ElementPlus from 'unplugin-element-plus/vite';
import { loadEnv } from 'vite';

export default defineConfig(async ({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  return {
    application: { nitroMock: false },
    vite: {
      plugins: [ElementPlus({ format: 'esm' })],
      resolve: { extensions: ['.mjs', '.js', '.ts', '.jsx', '.tsx', '.json', '.vue'] },
      server: {
        proxy: {
          '/ersyncapi': {
            target: env.VITE_API_TARGET || 'http://localhost:8080',
            changeOrigin: true,
            rewrite: (path) => path.replace(/^\/ersyncapi/, ''),
          },
        },
      },
    },
  };
});
