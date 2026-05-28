# OAuth 2.0 Authorization Code + PKCE

This document walks through the complete authentication flow used in this project — from the moment a user clicks "Log in" to the point where the React app has a working access token and can call protected API endpoints.

## What is OAuth 2.0?

[OAuth 2.0](https://oauth.net/2/) is a protocol that lets an application prove it has permission to act on behalf of a user — without the app ever seeing the user's password. The user authenticates directly with the authorization server (in this project, the Clojure API), which issues a short-lived token the app can use.

## The three actors in this project

| Actor | What it is |
|-------|-----------|
| **Client** | The React SPA (`apps/web`) — initiates the login flow and uses tokens to call the API |
| **Authorization server** | The Clojure API (`apps/api`) — authenticates the user and issues tokens |
| **Resource server** | Also the Clojure API — validates tokens and serves protected todo data |

The authorization server and resource server being the same process is a simplification for this learning project. In production systems they're often separate services.

## Why PKCE?

Traditional OAuth 2.0 uses a **client secret** to prove that the token request came from a legitimate app. This works for server-side apps (where the secret stays on a server), but it breaks for SPAs: there's no safe place to store a secret in browser code — anyone who opens DevTools can read it.

PKCE (Proof Key for Code Exchange, pronounced "pixie") solves this by replacing the client secret with a **one-time math proof**:

1. Before starting the flow, the client generates a random string called the `code_verifier`
2. It derives `code_challenge = base64url(SHA-256(code_verifier))` and sends only the challenge to the server
3. Later, when exchanging the authorization code for tokens, it sends the original `code_verifier`
4. The server recomputes `SHA-256(code_verifier)` and checks it matches the stored challenge

The insight: you can prove you know a value without sending the value itself — you send its hash upfront and the value later. An attacker who intercepts the auth code can't use it without also knowing the `code_verifier`, which was never sent over the wire.

## The full flow

### Step 1 — React initiates login

When the user needs to authenticate, `startOAuthFlow()` in `lib/oauth.ts` runs:

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

  const params = new URLSearchParams({
    client_id:             'react-app',
    redirect_uri:          'http://localhost:5173/callback',
    response_type:         'code',
    code_challenge:        challenge,
    code_challenge_method: 'S256',
    state,
  })

  window.location.href = `http://localhost:3000/oauth/authorize?${params}`
}
```

Two random values are generated using the Web Crypto API:
- `code_verifier` — 64 random bytes, base64url-encoded. This is the secret only React knows.
- `state` — 32 random bytes. Used to verify the redirect came from the same browser session (CSRF protection).

Both are stored in `sessionStorage` (survives page navigation, cleared when the tab closes). The browser is then redirected to the Clojure API.

### Step 2 — Clojure validates and stores the challenge

`authorize-handler` in `apps/api/src/api/oauth/handlers.clj` receives the redirect:

```clojure
(defn authorize-handler [{:keys [params session]}]
  (let [client-id    (get params "client_id")
        redirect-uri (get params "redirect_uri")
        challenge    (get params "code_challenge")
        state        (get params "state")
        client       (find-client client-id)]
    (cond
      (nil? client)
      {:status 400 :body {:error "unknown client_id"}}

      (not (valid-redirect? client redirect-uri))
      {:status 400 :body {:error "invalid redirect_uri"}}

      :else
      {:status  302
       :headers {"Location" "/auth/login"}
       :session (assoc session
                       :oauth/client_id      client-id
                       :oauth/redirect_uri   redirect-uri
                       :oauth/code_challenge challenge
                       :oauth/state          state)})))
```

The handler validates that `client_id` is registered in MongoDB and that `redirect_uri` is in the client's allowed list. It then stores the `code_challenge` and `state` in the Ring server-side session and redirects the browser to the login page.

Notice the return value: it's a plain Clojure map `{:status 302 :headers {...} :session {...}}`. This is Ring — every handler is just a function that returns data.

### Step 3 — User submits credentials

The login page is a server-rendered HTML form. When the user submits, `POST /auth/login` receives `{email, password}`. The handler:

1. Looks up the user by email or username in MongoDB
2. Verifies the password with bcrypt (`buddy/buddy-hashers`)
3. Generates a single-use authorization code (32 random bytes, base64url-encoded)
4. Stores it in the `auth_codes` collection:

```clojure
{:_id            (ObjectId.)
 :code           auth-code
 :client_id      (:oauth/client_id session)
 :user_id        (:_id user)
 :code_challenge (:oauth/code_challenge session)
 :redirect_uri   (:oauth/redirect_uri session)
 :expires_at     (Date. (+ now (* 10 60 1000)))   ; 10 minutes
 :used           false}
```

5. Redirects the browser back to React:

```
http://localhost:5173/callback?code=<auth_code>&state=<state>
```

### Step 4 — React handles the callback

`pages/CallbackPage.tsx` runs `exchangeCode(code, state)` from `lib/oauth.ts`:

```typescript
// lib/oauth.ts
export async function exchangeCode(code: string, state: string): Promise<string> {
  const storedState  = sessionStorage.getItem('pkce_state')
  const codeVerifier = sessionStorage.getItem('pkce_verifier')

  if (state !== storedState) throw new Error('state mismatch')

  sessionStorage.removeItem('pkce_state')
  sessionStorage.removeItem('pkce_verifier')

  const res = await fetch('http://localhost:3000/oauth/token', {
    method:      'POST',
    credentials: 'include',
    headers:     { 'Content-Type': 'application/json' },
    body:        JSON.stringify({
      grant_type:    'authorization_code',
      code,
      code_verifier: codeVerifier,
      client_id:     'react-app',
      redirect_uri:  'http://localhost:5173/callback',
    }),
  })

  const data = await res.json()
  return data.access_token
}
```

First it verifies `state` matches what's in `sessionStorage` — if not, the redirect was tampered with. Then it sends the original `code_verifier` (not the challenge — the actual secret) to `/oauth/token`. After the exchange succeeds, both values are removed from `sessionStorage`.

### Step 5 — Clojure validates the PKCE proof and issues tokens

`exchange-code` in `oauth/handlers.clj` performs five checks in sequence:

```clojure
(defn- exchange-code [{:keys [code code_verifier client_id redirect_uri]}]
  (let [auth-code (mc/find-one-as-map (db/get-db) "auth_codes" {:code code :used false})]
    (cond
      (nil? auth-code)                                          {:status 400 :body {:error "invalid or used code"}}
      (.before ^Date (:expires_at auth-code) (Date.))          {:status 400 :body {:error "code expired"}}
      (not= client_id (:client_id auth-code))                  {:status 400 :body {:error "client_id mismatch"}}
      (not= redirect_uri (:redirect_uri auth-code))            {:status 400 :body {:error "redirect_uri mismatch"}}
      (not= (sha256-base64url code_verifier)
            (:code_challenge auth-code))                        {:status 400 :body {:error "invalid code_verifier"}}

      :else
      (do
        (mc/update (db/get-db) "auth_codes" {:code code} {$set {:used true}})
        ;; issue tokens...
      ))))
```

The PKCE check is line 5: `(sha256-base64url code_verifier)` recomputes the challenge from the verifier the client just sent, and compares it to the challenge stored in Step 3. If they match, the client has proven it started this flow.

Once all checks pass:
- The auth code is marked `used = true` (replay protection — a stolen code is useless after first exchange)
- A JWT access token is signed with a 15-minute expiry
- A random refresh token is stored in MongoDB and set as an `httpOnly` cookie

```clojure
{:status  200
 :headers {"Set-Cookie" "refresh_token=<token>; HttpOnly; SameSite=Strict; Path=/oauth/token"}
 :body    {:access_token "<jwt>"
           :token_type   "Bearer"
           :expires_in   900}}
```

## Token storage strategy

| Token | Where it lives | Why |
|-------|---------------|-----|
| `access_token` | React in-memory state (`let accessToken`) | Short-lived (15 min). Never in `localStorage` — XSS can read localStorage. Disappears on page refresh, which triggers a silent refresh. |
| `refresh_token` | `httpOnly` cookie | JavaScript cannot read `httpOnly` cookies — XSS cannot steal it. The browser sends it automatically on requests to `/oauth/token`. |

## Silent token refresh

Every API call goes through `request()` in `lib/api.ts`. When a `401` comes back, it automatically attempts a refresh before giving up:

```typescript
// lib/api.ts
if (res.status === 401 && accessToken) {
  try {
    const newToken = await refreshAccessToken()  // uses the httpOnly cookie
    setAccessToken(newToken)
    headers['Authorization'] = `Bearer ${newToken}`
    const retry = await fetch(/* same request */)
    return retry.json()
  } catch {
    setAccessToken(null)
    throw new Error('session expired')
  }
}
```

`refreshAccessToken()` posts to `/oauth/token` with `grant_type: "refresh_token"`. The browser automatically includes the `httpOnly` cookie — no JavaScript code needs to read or send it explicitly. Clojure validates the token, issues a new access token, and the original request retries. The user never sees a login screen.

## Logout and revocation

`POST /oauth/revoke` marks the refresh token as revoked in MongoDB and clears the cookie:

```clojure
(defn revoke-handler [{:keys [body-params cookies]}]
  (let [token (or (:token body-params)
                  (get-in cookies ["refresh_token" :value]))]
    (mc/update (db/get-db) "refresh_tokens" {:token token} {$set {:revoked true}})
    {:status  200
     :headers {"Set-Cookie" "refresh_token=; HttpOnly; SameSite=Strict; Path=/oauth/token; Max-Age=0"}
     :body    {:revoked true}}))
```

`Max-Age=0` tells the browser to delete the cookie immediately.

**One important limitation:** revoking the refresh token prevents new access tokens from being issued, but any access tokens already issued remain valid until they expire (up to 15 minutes). This is the trade-off described in [Architecture — Stateless access tokens](ARCHITECTURE.md#stateless-access-tokens).
