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
      const dataMode = config.env.VITE_DEMO_DATA_MODE;
      if (dataMode !== undefined && dataMode !== 'local' && dataMode !== 'remote') {
        throw new Error('VITE_DEMO_DATA_MODE must be local or remote');
      }
      if (dataMode === 'local' && mode !== 'demo') {
        throw new Error('VITE_DEMO_DATA_MODE local requires VITE_AUTH_MODE demo');
      }
    },
  }],
})
