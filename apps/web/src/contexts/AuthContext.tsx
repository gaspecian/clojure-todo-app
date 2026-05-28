import { createContext, useContext, useState, type ReactNode } from 'react'
import { setAccessToken } from '../lib/api'
import { logout as oauthLogout, startOAuthFlow } from '../lib/oauth'

interface AuthContextValue {
  isAuthenticated: boolean
  login:           () => void
  logout:          () => Promise<void>
  setToken:        (token: string) => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [isAuthenticated, setIsAuthenticated] = useState(false)

  function login() {
    startOAuthFlow()
  }

  async function logout() {
    await oauthLogout()
    setAccessToken(null)
    setIsAuthenticated(false)
  }

  function setToken(token: string) {
    setAccessToken(token)
    setIsAuthenticated(true)
  }

  return (
    <AuthContext.Provider value={{ isAuthenticated, login, logout, setToken }}>
      {children}
    </AuthContext.Provider>
  )
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider')
  return ctx
}
