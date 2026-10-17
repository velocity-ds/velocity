import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { loopbackDevelopmentServer, operatorDevelopmentProxy } from './dev-server';

export default defineConfig({
  plugins: [loopbackDevelopmentServer(), react()],
  base: '/',
  build: {
    outDir: '../target/frontend',
    emptyOutDir: true,
    rollupOptions: { output: { manualChunks: { markdown: ['react-markdown', 'remark-gfm'] } } },
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    cors: false,
    proxy: operatorDevelopmentProxy(process.env.VELOCITY_DEV_BACKEND_PORT),
  },
  // Preview is a static build inspection server, not an operator API entry point.
  preview: { proxy: {} },
});
