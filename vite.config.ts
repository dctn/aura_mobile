import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// Plain static files in dist/, with relative paths so they work from any folder or host.
export default defineConfig({
  base: './',
  plugins: [react()],
  build: { target: 'es2020', sourcemap: false },
  // Tests always draw the bundled sample: never the live data, whatever .env.local says.
  test: { environment: 'jsdom', testTimeout: 15_000, env: { VITE_DEMO_API: '' }, include: ['src/**/*.test.ts', 'src/**/*.test.tsx'] },
});
