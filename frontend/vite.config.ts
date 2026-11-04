import { defineConfig, loadEnv } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';
import tailwind from '@tailwindcss/vite';
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', 'DEV_');
  return {
    plugins: [svelte(), tailwind()],
    server: {
      proxy: { '/api': env.DEV_API_TARGET || 'http://localhost:8080' },
      watch: { usePolling: env.DEV_POLLING === 'true' },
    },
  };
});
