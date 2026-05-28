# React Web App

This is the frontend for the clojure-todo-app — a React 19 SPA that acts as the OAuth 2.0 client for the Clojure API. It's not the main focus of this project; it exists to show how a real browser client consumes an OAuth server built from scratch. If you want to understand the full authentication flow end to end, read [OAuth 2.0 + PKCE flow](../../docs/OAUTH_FLOW.md) first, then come back here for the implementation details.

---

## OAuth client pattern

### What a public client is

OAuth 2.0 has two client types. Confidential clients (server-side apps) can store a secret on a server — they prove their identity to the authorization server by sending that secret alongside their token requests. Public clients (SPAs, mobile apps) can't do this: there's no safe place in browser code to store a secret. Anyone can open DevTools and read it.

PKCE (Proof Key for Code Exchange) is the solution. Instead of a client secret, the client generates a one-time math proof it can verify without ever sending the secret over the wire. See [Why PKCE?](../../docs/OAUTH_FLOW.md#why-pkce) for the full explanation.

### `startOAuthFlow()` — pure browser crypto, no library

`lib/oauth.ts` generates the PKCE values using the [Web Crypto API](https://developer.mozilla.org/en-US/docs/Web/API/Web_Crypto_API) — no external library:

```typescript
// lib/oauth.ts
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

  // redirect to /oauth/authorize...
}
```

Three things happen in sequence:
1. Generate a `verifier` — 64 random bytes, base64url-encoded. This is the secret only this browser tab knows.
2. SHA-256 hash it → `challenge`. The challenge goes to the server; the verifier stays local.
3. Generate `state` — 32 random bytes used to detect tampered redirects (CSRF protection).

### `sessionStorage` — not `localStorage`

Both values are stored in `sessionStorage`, not `localStorage`. The distinction matters:

- `sessionStorage` survives navigation within a tab (needed — the OAuth flow redirects away from the app and back)
- `sessionStorage` is cleared when the tab closes (good — no tokens persist across sessions)
- `localStorage` persists across tabs and browser restarts, which is unnecessary exposure for short-lived PKCE values

### `exchangeCode()` — state check before token exchange

When the browser returns to `/callback` with `?code=...&state=...`, `exchangeCode()` verifies `state` before doing anything:

```typescript
// lib/oauth.ts
export async function exchangeCode(code: string, state: string): Promise<string> {
  const storedState  = sessionStorage.getItem('pkce_state')
  const codeVerifier = sessionStorage.getItem('pkce_verifier')

  if (state !== storedState) throw new Error('state mismatch')

  sessionStorage.removeItem('pkce_state')
  sessionStorage.removeItem('pkce_verifier')

  // POST /oauth/token with code + code_verifier...
}
```

If `state` doesn't match what was stored before the redirect, the callback was tampered with — the function throws immediately without touching the token endpoint. After a successful exchange, both values are removed from `sessionStorage`. They've served their purpose.

---

## Token storage and security

### Access token — module-level variable

The access token lives in a module-level `let` variable in `lib/api.ts`:

```typescript
// lib/api.ts
let accessToken: string | null = null

export function setAccessToken(token: string | null): void { accessToken = token }
export function getAccessToken(): string | null            { return accessToken }
```

It's not in React state. It's not in `localStorage`. It's a plain JavaScript variable in a module. This has two security properties:

- **XSS can't reach it.** An XSS payload can call global functions and read `localStorage`, but it can't access a `let` variable inside a module closure without a reference to `getAccessToken`.
- **It disappears on page refresh.** This is intentional — a refresh forces a silent re-authentication using the refresh token cookie, which keeps the access token short-lived in practice.

### Refresh token — `httpOnly` cookie

The refresh token is set by the Clojure server as an `httpOnly` cookie. JavaScript — including any XSS payload — cannot read `httpOnly` cookies. The browser sends it automatically on requests to `/oauth/token`. No code in this app ever reads, stores, or sends the refresh token explicitly.

When `refreshAccessToken()` posts to `/oauth/token`, it just sends `{ grant_type: 'refresh_token' }`. The browser attaches the cookie automatically.

### The full picture

| Token | Where it lives | Wiped by |
|-------|---------------|----------|
| `access_token` | Module-level `let` | Page refresh, logout |
| `refresh_token` | `httpOnly` cookie | `POST /oauth/revoke` (sets `Max-Age=0`) |

---

## Automatic token refresh

### `request()` as the single choke point

Every API call goes through `request()` in `lib/api.ts`:

```typescript
// lib/api.ts
async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
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
  return res.json() as Promise<T>
}
```

On a `401`, `request()` calls `refreshAccessToken()`, updates the stored token, and retries the original request with the new token. The caller — `useTodos`, the UI — sees the eventual result. The token exchange is invisible to the rest of the app.

`credentials: 'include'` appears on every `fetch` call. This tells the browser to include cookies on the request even for cross-origin calls. Without it, the `httpOnly` refresh token cookie would never be sent.

### `useTodos` — one hook for all CRUD

`hooks/useTodos.ts` wraps TanStack Query for the entire todo resource:

```typescript
// hooks/useTodos.ts
export function useTodos() {
  const queryClient = useQueryClient()

  const { data, isLoading } = useQuery({
    queryKey: ['todos'],
    queryFn:  () => api.get<{ todos: Todo[] }>('/api/todos').then(r => r.todos),
  })

  const create = useMutation({
    mutationFn: (input: CreateTodoInput) => api.post<Todo>('/api/todos', input),
    onSuccess:  () => queryClient.invalidateQueries({ queryKey: ['todos'] }),
  })

  // update, remove follow the same pattern...
  return { todos: data ?? [], isLoading, create, update, remove }
}
```

Each mutation calls `invalidateQueries(['todos'])` on success. TanStack Query marks the cache stale and re-runs the `useQuery` fetch automatically. You never update the list manually — you just invalidate and let the query re-run.

---

## Routing and protected routes

### The route table

`App.tsx` defines four routes:

```typescript
// App.tsx
export default function App() {
  return (
    <Routes>
      <Route path="/"         element={<Navigate to="/todos" replace />} />
      <Route path="/signup"   element={<SignupPage />} />
      <Route path="/callback" element={<CallbackPage />} />
      <Route path="/todos"    element={
        <ProtectedRoute>
          <TodosPage />
        </ProtectedRoute>
      } />
    </Routes>
  )
}
```

`/` redirects to `/todos`, which is protected. An unauthenticated visitor hitting `/` immediately ends up at the OAuth flow.

### `ProtectedRoute` — redirect, not block

`ProtectedRoute` doesn't show a "you need to log in" page. It starts the OAuth flow:

```typescript
// components/ProtectedRoute.tsx
export function ProtectedRoute({ children }: { children: ReactNode }) {
  const { isAuthenticated, login } = useAuth()

  useEffect(() => {
    if (!isAuthenticated) login()  // starts OAuth — redirects the browser
  }, [isAuthenticated, login])

  if (!isAuthenticated) return null
  return <>{children}</>
}
```

If `isAuthenticated` is false, `login()` calls `startOAuthFlow()`, which redirects the browser to the Clojure API's `/oauth/authorize`. The component returns `null` — there's nothing to render while the redirect is in flight. The user never sees a broken intermediate state.

### `CallbackPage` — StrictMode guard

`CallbackPage` uses a `useRef` flag to prevent `exchangeCode` from running twice:

```typescript
// pages/CallbackPage.tsx
const called = useRef(false)

useEffect(() => {
  if (called.current) return
  called.current = true

  exchangeCode(code, state).then(token => { setToken(token); navigate('/todos') })
}, [])
```

React StrictMode fires effects twice in development to help surface side effects. `exchangeCode` is not idempotent — it reads and deletes values from `sessionStorage`, and it makes a network request. The ref guard ensures it runs exactly once regardless of environment.

---

## Responsive layout with ShadCN

`TodosPage` uses a single `Sidebar` component rendered in two different containers depending on screen size:

```tsx
// pages/TodosPage.tsx — simplified
const sidebarContent = <Sidebar filter={...} setFilter={...} ... />

return (
  <div className="min-h-screen bg-slate-950">
    <header>
      {/* Mobile: hamburger triggers a Sheet drawer */}
      <Sheet>
        <SheetTrigger className="md:hidden">
          <Menu size={20} />
        </SheetTrigger>
        <SheetContent side="left" className="w-64 p-6">
          {sidebarContent}
        </SheetContent>
      </Sheet>
    </header>

    <div className="flex">
      {/* Desktop: sidebar always visible */}
      <aside className="hidden md:block w-56 border-r p-6">
        {sidebarContent}
      </aside>

      <main className="flex-1 p-4 md:p-6">
        {/* todo list */}
      </main>
    </div>
  </div>
)
```

`sidebarContent` is defined once. On desktop (`md:block`), it renders inside a permanent `<aside>`. On mobile (`md:hidden` on the trigger), it renders inside a ShadCN [`Sheet`](https://ui.shadcn.com/docs/components/sheet) — a slide-in drawer. The same filter state is shared because it's the same component instance in both places.

---

## Running and building

From the repo root, `pnpm dev` starts both apps via Turborepo. To work on the frontend alone:

```bash
cd apps/web
pnpm dev          # Vite dev server at http://localhost:5173
pnpm lint         # ESLint
pnpm typecheck    # tsc --noEmit — type check without emitting files
pnpm build        # production Vite bundle → dist/
```

The production build is a static directory — `index.html`, JS bundles, and CSS. The `apps/web/Dockerfile` serves it with nginx, which handles SPA routing via `try_files $uri $uri/ /index.html`.
