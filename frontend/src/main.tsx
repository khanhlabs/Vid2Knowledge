import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './app/App'
import { AuthProvider } from './features/auth/AuthProvider'
import { SessionQueryProvider } from './features/auth/SessionQueryProvider'
import './style.css'

const root = document.getElementById('app')

if (!root) {
  throw new Error('Application root element was not found')
}

createRoot(root).render(
  <StrictMode>
    <BrowserRouter>
      <AuthProvider>
        <SessionQueryProvider>
          <App />
        </SessionQueryProvider>
      </AuthProvider>
    </BrowserRouter>
  </StrictMode>,
)
