import { createContext, useContext, useState, useEffect, type ReactNode } from 'react'
import { setAccessToken } from '../lib/api'
import { logout as oauthLogout, startOAuthFlow, refreshAccessToken } from '../lib/oauth'

interface AuthContextValue {
  isAuthenticated: boolean
  isLoading:       boolean
  login:           () => void
  logout:          () => Promise<void>
  setToken:        (token: string) => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [isAuthenticated, setIsAuthenticated] = useState(false)
  const [isLoading, setIsLoading]             = useState(true)

  // On load the in-memory access token is gone; restore the session from the
  // persistent httpOnly refresh_token cookie before deciding auth state.
  useEffect(() => {
    let active = true
    refreshAccessToken()
      .then(token => { if (active) { setAccessToken(token); setIsAuthenticated(true) } })
      .catch(()    => { if (active) { setAccessToken(null); setIsAuthenticated(false) } })
      .finally(()  => { if (active) setIsLoading(false) })
    return () => { active = false }
  }, [])

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
    <AuthContext.Provider value={{ isAuthenticated, isLoading, login, logout, setToken }}>
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
