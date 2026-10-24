import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';

// The engine serves the built UI in web mode (cpiwb-xml serve --http 7777 --ui dist).
// During `npm run dev`, RPC calls go to the engine started separately on port 7777.
export default defineConfig({
  plugins: [svelte()],
  clearScreen: false,
  base: './',
  server: {
    port: 5173,
    strictPort: true,
    proxy: { '/rpc': 'http://127.0.0.1:7777' },
  },
  build: {
    target: 'es2022',
    chunkSizeWarningLimit: 4000,
    sourcemap: false,
  },
});
