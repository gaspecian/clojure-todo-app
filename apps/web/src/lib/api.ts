import { refreshAccessToken } from './oauth'

const API = 'http://localhost:3000'

let accessToken: string | null = null

export function setAccessToken(token: string | null): void {
  accessToken = token
}

export function getAccessToken(): string | null {
  return accessToken
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string>),
  }
  if (accessToken) headers['Authorization'] = `Bearer ${accessToken}`

  const res = await fetch(`${API}${path}`, { ...options, headers, credentials: 'include' })

  if (res.status === 401 && accessToken) {
    try {
      const newToken = await refreshAccessToken()
      setAccessToken(newToken)
      headers['Authorization'] = `Bearer ${newToken}`
      const retry = await fetch(`${API}${path}`, { ...options, headers, credentials: 'include' })
      if (!retry.ok) throw new Error(`${retry.status}`)
      return retry.json() as Promise<T>
    } catch {
      setAccessToken(null)
      throw new Error('session expired')
    }
  }

  if (!res.ok) throw new Error(`${res.status}`)
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

export const api = {
  get:    <T>(path: string)                => request<T>(path),
  post:   <T>(path: string, body: unknown) => request<T>(path, { method: 'POST',  body: JSON.stringify(body) }),
  put:    <T>(path: string, body: unknown) => request<T>(path, { method: 'PUT',   body: JSON.stringify(body) }),
  delete: <T>(path: string)               => request<T>(path, { method: 'DELETE' }),
}
