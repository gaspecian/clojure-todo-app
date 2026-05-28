import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { exchangeCode } from '@/lib/oauth'
import { useAuth } from '@/contexts/AuthContext'
import { Button } from '@/components/ui/button'

export function CallbackPage() {
  const [params]            = useSearchParams()
  const navigate            = useNavigate()
  const { setToken, login } = useAuth()
  const called              = useRef(false)
  const [failed, setFailed] = useState(false)

  const code  = params.get('code')
  const state = params.get('state')

  useEffect(() => {
    if (called.current) return
    if (!code || !state) return
    called.current = true

    exchangeCode(code, state)
      .then(token => {
        setToken(token)
        navigate('/todos')
      })
      .catch(() => setFailed(true))
  }, [])

  if (failed || !code || !state) {
    return (
      <div className="min-h-screen bg-slate-950 flex items-center justify-center p-4">
        <div className="w-full max-w-sm text-center space-y-4">
          <h1 className="text-xl font-semibold text-slate-50">We couldn't sign you in</h1>
          <p className="text-sm text-slate-400">
            Your sign-in link may have expired or already been used. Please try again.
          </p>
          <Button onClick={login} className="w-full bg-indigo-600 hover:bg-indigo-700">
            Try again
          </Button>
          <p className="text-sm text-slate-400">
            <Link to="/signup" className="text-indigo-400 hover:underline">Back to sign up</Link>
          </p>
        </div>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-slate-950 flex items-center justify-center">
      <p className="text-slate-400">Signing you in…</p>
    </div>
  )
}
