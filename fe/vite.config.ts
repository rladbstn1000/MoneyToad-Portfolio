import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), {
    name: 'moneytoad-auth-mode',
    configResolved(config) {
      const mode = config.env.VITE_AUTH_MODE;
      if (mode !== undefined && mode !== 'oauth' && mode !== 'demo') {
        throw new Error('VITE_AUTH_MODE must be oauth or demo');
      }
    },
  }],
})
