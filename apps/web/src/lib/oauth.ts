const API = 'http://localhost:3000'
const CLIENT_ID = 'react-app'
const REDIRECT_URI = `${window.location.origin}/callback`

function base64urlEncode(buffer: ArrayBuffer | Uint8Array): string {
  const bytes = buffer instanceof Uint8Array ? buffer : new Uint8Array(buffer)
  return btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '')
}

export async function startOAuthFlow(): Promise<void> {
  const verifier = base64urlEncode(crypto.getRandomValues(new Uint8Array(64)))
  const state    = base64urlEncode(crypto.getRandomValues(new Uint8Array(32)))

  const challengeBytes = await crypto.subtle.digest(
    'SHA-256',
    new TextEncoder().encode(verifier)
  )
  const challenge = base64urlEncode(challengeBytes)

  sessionStorage.setItem('pkce_verifier', verifier)
  sessionStorage.setItem('pkce_state', state)

  const params = new URLSearchParams({
    client_id:             CLIENT_ID,
    redirect_uri:          REDIRECT_URI,
    response_type:         'code',
    code_challenge:        challenge,
    code_challenge_method: 'S256',
    state,
  })

  window.location.href = `${API}/oauth/authorize?${params}`
}

export async function exchangeCode(code: string, state: string): Promise<string> {
  const storedState  = sessionStorage.getItem('pkce_state')
  const codeVerifier = sessionStorage.getItem('pkce_verifier')

  if (state !== storedState) throw new Error('state mismatch')
  if (!codeVerifier)         throw new Error('missing code verifier')

  sessionStorage.removeItem('pkce_state')
  sessionStorage.removeItem('pkce_verifier')

  const res = await fetch(`${API}/oauth/token`, {
    method:      'POST',
    credentials: 'include',
    headers:     { 'Content-Type': 'application/json' },
    body:        JSON.stringify({
      grant_type:    'authorization_code',
      code,
      code_verifier: codeVerifier,
      client_id:     CLIENT_ID,
      redirect_uri:  REDIRECT_URI,
    }),
  })

  if (!res.ok) throw new Error('token exchange failed')
  const data = await res.json()
  return data.access_token as string
}

export async function refreshAccessToken(): Promise<string> {
  const res = await fetch(`${API}/oauth/token`, {
    method:      'POST',
    credentials: 'include',
    headers:     { 'Content-Type': 'application/json' },
    body:        JSON.stringify({ grant_type: 'refresh_token' }),
  })
  if (!res.ok) throw new Error('refresh failed')
  const data = await res.json()
  return data.access_token as string
}

export async function logout(): Promise<void> {
  await fetch(`${API}/oauth/revoke`, {
    method:      'POST',
    credentials: 'include',
  })
}
