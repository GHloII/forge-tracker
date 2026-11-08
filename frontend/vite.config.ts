import { defineConfig, loadEnv } from 'vite';
import { sveltekit } from '@sveltejs/kit/vite';
import tailwind from '@tailwindcss/vite';
import adapter from '@sveltejs/adapter-static';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', 'DEV_');
  return {
    plugins: [sveltekit({
      preprocess: vitePreprocess(),
      adapter: adapter({ pages: 'dist', assets: 'dist', fallback: '200.html' }),
      files: { assets: 'public' },
      paths: { relative: false },
    }), tailwind()],
    server: {
      proxy: { '/api': env.DEV_API_TARGET || 'http://localhost:8080' },
      watch: { usePolling: env.DEV_POLLING === 'true' },
    },
  };
});
