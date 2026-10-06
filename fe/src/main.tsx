import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import AuthProvider from './auth/AuthProvider'
import { ReactQueryDevtools } from '@tanstack/react-query-devtools'
import App from './App.tsx'
import './index.css'
import { isLocalDemo } from './auth/authMode'



createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <AuthProvider>
        <App />
        {!isLocalDemo && <ReactQueryDevtools initialIsOpen={false} />}
      </AuthProvider>
    </BrowserRouter>
  </StrictMode>,
)
