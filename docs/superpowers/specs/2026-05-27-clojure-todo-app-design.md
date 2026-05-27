# Clojure Todo App — Design Spec

**Date:** 2026-05-27  
**Status:** Approved  
**Goal:** Learn Clojure fundamentals by building a production-shaped monorepo with a full OAuth 2.0 authorization server, a REST API for todos, and a React + ShadCN frontend.

---

## 1. Project Overview

A monorepo containing two applications:

- **`apps/api`** — Clojure REST API. Serves as both the OAuth 2.0 authorization server and the protected todo resource server. Single process, single MongoDB connection.
- **`apps/web`** — React SPA (Vite + ShadCN UI). Acts as the OAuth 2.0 client using Authorization Code + PKCE. Fully responsive — sidebar collapses to a slide-out drawer on mobile.

Managed with **pnpm workspaces** and **Turborepo** (`turbo dev` starts both apps in parallel).

---

## 2. Monorepo Structure

```
clojure-todo-app/
├── package.json               # pnpm workspace root
├── pnpm-workspace.yaml        # declares apps/*
├── turbo.json                 # pipeline: dev, build, test
├── .gitignore                 # includes .superpowers/
├── apps/
│   ├── api/                   # Clojure (Reitit + Ring)
│   │   ├── src/api/
│   │   │   ├── core.clj       # server entry point, router assembly
│   │   │   ├── oauth/         # OAuth 2.0 handlers
│   │   │   ├── auth/          # signup + login handlers
│   │   │   ├── todos/         # todo CRUD handlers
│   │   │   ├── db/            # MongoDB client + collection helpers
│   │   │   └── middleware/    # auth token validation, CORS, logging
│   │   ├── resources/
│   │   │   ├── config.edn     # aero config (reads env vars)
│   │   │   └── templates/     # server-rendered login HTML
│   │   ├── deps.edn
│   │   └── package.json       # turbo scripts: dev, test
│   └── web/                   # React + Vite + ShadCN
│       ├── src/
│       │   ├── pages/         # Login redirect, Signup, Callback, Todos
│       │   ├── components/    # ShadCN component wrappers
│       │   ├── hooks/         # useAuth, useTodos
│       │   └── lib/           # oauth.ts (PKCE helpers), api.ts (fetch + refresh)
│       ├── index.html
│       └── package.json
└── docs/
    └── superpowers/specs/     # design documents
```

---

## 3. Route Map

### Public routes (no auth required)

| Method | Path | Handler | Notes |
|--------|------|---------|-------|
| POST | `/auth/signup` | `auth/signup-handler` | Creates user account |
| GET | `/auth/login` | `auth/login-page-handler` | Server-renders login HTML |
| POST | `/auth/login` | `auth/login-handler` | Validates credentials, issues auth code |
| GET | `/oauth/authorize` | `oauth/authorize-handler` | Validates client, redirects to login |
| POST | `/oauth/token` | `oauth/token-handler` | Exchanges code or refresh token for tokens |
| POST | `/oauth/revoke` | `oauth/revoke-handler` | Revokes a refresh token (logout) |
| GET | `/oauth/userinfo` | `oauth/userinfo-handler` | Returns user profile (requires Bearer token) |
| GET | `/.well-known/oauth-authorization-server` | `oauth/metadata-handler` | OAuth server metadata |

### Protected routes (Bearer token required)

| Method | Path | Handler |
|--------|------|---------|
| GET | `/api/todos` | `todos/list-handler` |
| POST | `/api/todos` | `todos/create-handler` |
| GET | `/api/todos/:id` | `todos/get-handler` |
| PUT | `/api/todos/:id` | `todos/update-handler` |
| DELETE | `/api/todos/:id` | `todos/delete-handler` |

All protected routes extract `user-id` from the validated JWT — users can only access their own todos.

### Signup form fields

| Field | Type | Constraint |
|-------|------|-----------|
| `name` | String | Required, display name |
| `login` | String | Required, unique username/handle |
| `email` | String | Required, unique, validated format |
| `password` | String | Required, bcrypt-hashed on write |

After signup, the user is redirected to `/auth/login` which continues the OAuth flow.

---

## 4. Data Models (MongoDB)

### `users`
```
_id         ObjectId
email       String   (unique index)
login       String   (unique index)
name        String
password    String   (bcrypt hash — never returned in responses)
created_at  DateTime
```

### `clients`
```
_id            ObjectId
client_id      String   (unique — e.g. "react-app")
name           String
redirect_uris  [String]
grant_types    [String] (["authorization_code", "refresh_token"])
```
The React app registers as a public client (no client secret — PKCE is the security mechanism).

### `auth_codes`
```
_id             ObjectId
code            String   (unique, random)
client_id       String
user_id         ObjectId
code_challenge  String   (SHA-256 of code_verifier, base64url)
redirect_uri    String
expires_at      DateTime (+10 minutes from issue)
used            Boolean  (default false — single-use enforcement)
```

### `refresh_tokens`
```
_id         ObjectId
token       String   (unique, random)
user_id     ObjectId
client_id   String
expires_at  DateTime (+7 days from issue)
revoked     Boolean  (default false)
```

### `todos`
```
_id         ObjectId
user_id     ObjectId  (owner — always set from JWT, never from request body)
title       String    (required)
body        String
completed   Boolean   (default false)
priority    String    ("low" | "medium" | "high")
due_date    DateTime  (nullable)
tags        [String]
created_at  DateTime
updated_at  DateTime
```

---

## 5. OAuth 2.0 Authorization Code + PKCE Flow

### Step 1 — React initiates login
React generates a random `code_verifier` (64 bytes) and `state` (32 bytes), derives `code_challenge = base64url(SHA-256(code_verifier))`, stores both in `sessionStorage`, then redirects the browser to:

```
GET /oauth/authorize
  ?client_id=react-app
  &redirect_uri=http://localhost:5173/callback
  &response_type=code
  &code_challenge=<challenge>
  &code_challenge_method=S256
  &state=<state>
```

### Step 2 — Clojure validates and serves login page
Clojure validates `client_id` and `redirect_uri`, stores `code_challenge` in session, and serves the server-rendered HTML login page at `GET /auth/login`.

### Step 3 — User submits credentials
`POST /auth/login` receives `{ email, password }`. Clojure verifies the password with bcrypt, generates a single-use auth code, stores it in `auth_codes` (with `code_challenge`, `expires_at = now + 10 min`, `used = false`), then redirects to:

```
http://localhost:5173/callback?code=<auth_code>&state=<state>
```

### Step 4 — React handles callback
The `/callback` route verifies `state` matches `sessionStorage`, extracts the code, then POSTs:

```
POST /oauth/token
{
  grant_type:    "authorization_code",
  code:          "<auth_code>",
  code_verifier: "<original verifier from sessionStorage>",
  client_id:     "react-app",
  redirect_uri:  "http://localhost:5173/callback"
}
```

### Step 5 — Clojure validates and issues tokens
1. Look up code in `auth_codes` — must exist, not expired, not used
2. Verify `SHA-256(code_verifier) == stored code_challenge`
3. Mark code `used = true`
4. Issue JWT `access_token` (HS256, 15-minute expiry, contains `sub`, `user_id`, `login`)
5. Issue `refresh_token` (random, stored in `refresh_tokens`, 7-day expiry)
6. Return response:

```json
{ "access_token": "eyJ...", "token_type": "Bearer", "expires_in": 900 }
```

With header: `Set-Cookie: refresh_token=<token>; HttpOnly; Secure; SameSite=Strict; Path=/oauth/token`

### Token storage
| Token | Storage | Rationale |
|-------|---------|-----------|
| `access_token` | React in-memory state | Short-lived; never persisted to localStorage (XSS) |
| `refresh_token` | `httpOnly` cookie | JavaScript cannot read it; safe from XSS |

### Token refresh (silent)
When any API call returns 401, the TanStack Query error handler calls `POST /oauth/token` with `grant_type: "refresh_token"`. The browser sends the `httpOnly` refresh token cookie automatically. Clojure validates and issues a new `access_token`. The original request retries transparently.

### Logout
`POST /oauth/revoke` sets `revoked = true` on the refresh token in the DB. React clears the in-memory `access_token`. Browser clears the cookie via `Set-Cookie: refresh_token=; Max-Age=0`.

---

## 6. Frontend Architecture

### Pages (React Router v7)

| Route | Component | Auth |
|-------|-----------|------|
| `/` | Redirects to `/todos` if authenticated, else `/signup` | — |
| `/signup` | `SignupPage` — form with name, login, email, password | Public |
| `/callback` | `CallbackPage` — handles OAuth code exchange, redirects to `/todos` | Public |
| `/todos` | `TodosPage` — main app | Protected |

Protected routes redirect unauthenticated users to begin the OAuth flow (`/oauth/authorize`).

### Todos page layout

**Desktop (≥ md breakpoint):**
- Persistent left sidebar (220px): filter by status, priority, tags
- Main area: search bar + "New todo" button + todo list

**Mobile (< md breakpoint):**
- Compact top nav with hamburger icon
- ShadCN `Sheet` component slides in as drawer, replacing the sidebar
- Full-width todo list

### Todo item fields displayed
- Title (bold), body (secondary text)
- Priority badge: 🔴 High / 🟡 Medium / 🟢 Low
- Tags as chips
- Due date (formatted, highlighted red if overdue)
- Completion checkbox (toggles `completed` via PATCH-style PUT)

---

## 7. Tech Stack

### Clojure backend (`apps/api`)

| Library | Version | Role |
|---------|---------|------|
| metosin/reitit | 0.10.1 | Data-driven routing |
| ring/ring-core | 1.15.4 | HTTP abstraction |
| ring/ring-jetty-adapter | 1.15.4 | Embedded Jetty server |
| buddy/buddy-hashers | 2.0.167 | bcrypt password hashing |
| buddy/buddy-sign | 3.6.1-359 | JWT signing + verification |
| com.novemberain/monger | 3.6.0 | MongoDB client |
| metosin/muuntaja | 0.6.11 | JSON content negotiation |
| ring-cors | 0.1.13 | CORS middleware |
| aero | 1.1.6 | Config via EDN + env vars |

### React frontend (`apps/web`)

| Package | Version | Role |
|---------|---------|------|
| react + react-dom | 19 | UI rendering |
| vite | 6 | Dev server + bundler |
| react-router-dom | 7 | Client-side routing |
| @shadcn/ui + tailwindcss | latest | Components + styling |
| @tanstack/react-query | 5.100.11 | Server state, caching, token refresh |
| react-hook-form | 7 | Form state |
| zod | 4 | Schema validation (breaking changes from v3 — use `@hookform/resolvers@latest`) |
| lucide-react | latest | Icons |

### Monorepo tooling

| Tool | Role |
|------|------|
| pnpm workspaces | Shared node_modules, single lockfile |
| turbo | Task orchestration — `turbo dev` starts api + web in parallel |
| clojure CLI | `clj -M:run` starts API, `clj -M:test` runs tests |

---

## 8. Key Design Decisions

1. **Single Clojure process for auth + todos** — auth server and resource server are namespaced routes in one app. Simpler to run and learn from. Can be split later.
2. **Stateless access tokens** — JWTs are never stored in the DB; verified by cryptographic signature alone. This means logout does not invalidate access tokens — only refresh tokens are revocable. Access tokens are short-lived (15 min) to bound the exposure window.
3. **httpOnly cookie for refresh token** — the browser manages the cookie lifecycle; JavaScript (including XSS payloads) cannot read it.
4. **Single-use auth codes** — `used = true` is set atomically on first exchange; any replay attempt returns an error.
5. **`user_id` always from JWT** — todo handlers never trust the request body for ownership. `user_id` is extracted from the validated token in middleware.
6. **Responsive via ShadCN Sheet** — the sidebar is conditionally rendered as a `Sheet` (slide-over drawer) on mobile breakpoints, keeping the component tree simple.
7. **Zod v4** — breaking change from v3; use `zod@4` and `@hookform/resolvers@latest` explicitly when scaffolding the frontend.
