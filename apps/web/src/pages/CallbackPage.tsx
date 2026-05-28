import { useEffect, useRef } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { exchangeCode } from '@/lib/oauth'
import { useAuth } from '@/contexts/AuthContext'

export function CallbackPage() {
  const [params]     = useSearchParams()
  const navigate     = useNavigate()
  const { setToken } = useAuth()
  const called       = useRef(false)

  useEffect(() => {
    if (called.current) return
    called.current = true

    const code  = params.get('code')
    const state = params.get('state')

    if (!code || !state) {
      navigate('/signup')
      return
    }

    exchangeCode(code, state)
      .then(token => {
        setToken(token)
        navigate('/todos')
      })
      .catch(() => navigate('/signup'))
  }, [])

  return (
    <div className="min-h-screen bg-slate-950 flex items-center justify-center">
      <p className="text-slate-400">Signing you in…</p>
    </div>
  )
}
