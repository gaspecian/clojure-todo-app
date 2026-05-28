import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Input }  from '@/components/ui/input'
import { Label }  from '@/components/ui/label'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card'
import { useState } from 'react'
import { useAuth } from '@/contexts/AuthContext'

const schema = z.object({
  name:     z.string().min(1, 'Name is required'),
  login:    z.string().min(2, 'Username must be at least 2 characters'),
  email:    z.string().email('Invalid email'),
  password: z.string().min(6, 'Password must be at least 6 characters'),
})

type FormData = z.infer<typeof schema>

export function SignupPage() {
  const { login } = useAuth()
  const [serverError, setServerError] = useState<string | null>(null)

  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<FormData>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(schema as any),
  })

  async function onSubmit(data: FormData) {
    setServerError(null)
    let res: Response
    try {
      res = await fetch('http://localhost:3000/auth/signup', {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify(data),
      })
    } catch {
      setServerError('Network error. Please check your connection and try again.')
      return
    }
    if (res.status === 409) {
      setServerError('Email or username already taken')
      return
    }
    if (!res.ok) {
      setServerError('Signup failed. Please try again.')
      return
    }
    login()
  }

  return (
    <div className="min-h-screen bg-slate-950 flex items-center justify-center p-4">
      <Card className="w-full max-w-sm bg-slate-900 border-slate-800">
        <CardHeader>
          <CardTitle className="text-slate-50">Create your account</CardTitle>
          <CardDescription className="text-slate-400">
            Already have one?{' '}
            <button
              type="button"
              onClick={login}
              className="text-indigo-400 hover:underline"
            >
              Sign in
            </button>
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
            {serverError && (
              <p className="text-sm text-red-400 bg-red-950 rounded-md px-3 py-2">
                {serverError}
              </p>
            )}
            <div className="space-y-1">
              <Label htmlFor="name" className="text-slate-300">Name</Label>
              <Input id="name" {...register('name')} className="bg-slate-950 border-slate-700 text-slate-50" />
              {errors.name && <p className="text-xs text-red-400">{errors.name.message}</p>}
            </div>
            <div className="space-y-1">
              <Label htmlFor="login" className="text-slate-300">Username</Label>
              <Input id="login" {...register('login')} className="bg-slate-950 border-slate-700 text-slate-50" />
              {errors.login && <p className="text-xs text-red-400">{errors.login.message}</p>}
            </div>
            <div className="space-y-1">
              <Label htmlFor="email" className="text-slate-300">Email</Label>
              <Input id="email" type="email" {...register('email')} className="bg-slate-950 border-slate-700 text-slate-50" />
              {errors.email && <p className="text-xs text-red-400">{errors.email.message}</p>}
            </div>
            <div className="space-y-1">
              <Label htmlFor="password" className="text-slate-300">Password</Label>
              <Input id="password" type="password" {...register('password')} className="bg-slate-950 border-slate-700 text-slate-50" />
              {errors.password && <p className="text-xs text-red-400">{errors.password.message}</p>}
            </div>
            <Button type="submit" disabled={isSubmitting} className="w-full bg-indigo-600 hover:bg-indigo-700">
              {isSubmitting ? 'Creating account…' : 'Create account'}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
