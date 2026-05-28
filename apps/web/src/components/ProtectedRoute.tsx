import { useEffect, type ReactNode } from 'react'
import { useAuth } from '../contexts/AuthContext'

export function ProtectedRoute({ children }: { children: ReactNode }) {
  const { isAuthenticated, login } = useAuth()

  useEffect(() => {
    if (!isAuthenticated) login()
  }, [isAuthenticated, login])

  if (!isAuthenticated) return null
  return <>{children}</>
}
