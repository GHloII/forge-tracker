import { defineConfig, loadEnv } from 'vite';
import { sveltekit } from '@sveltejs/kit/vite';
import tailwind from '@tailwindcss/vite';
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', 'DEV_');
  return {
    plugins: [sveltekit(), tailwind()],
    server: {
      proxy: { '/api': env.DEV_API_TARGET || 'http://localhost:8080' },
      watch: { usePolling: env.DEV_POLLING === 'true' },
    },
  };
});
