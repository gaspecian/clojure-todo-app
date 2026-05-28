# Architecture

This document explains how the pieces of this project connect, traces a real request through the system, and documents the key design decisions made along the way.

## System overview

```
┌──────────────────────────────────────────────┐
│  Browser — localhost:5173                     │
│                                               │
│  React SPA                                    │
│  pages/  hooks/  lib/  contexts/              │
└────────────────────┬─────────────────────────┘
                     │ HTTP + Bearer token
                     │ (refresh token via httpOnly cookie)
                     ▼
┌──────────────────────────────────────────────┐
│  Clojure API — localhost:3000                 │
│                                               │
│  Middleware stack                             │
│  CORS → cookies → params → session → router  │
│                                  └─ wrap-auth │
│                                    (/api/* only)
│                                               │
│  oauth/   auth/   todos/   db/   middleware/  │
└────────────────────┬─────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────┐
│  MongoDB — localhost:27017 (Docker)           │
│                                               │
│  users  clients  auth_codes                   │
│  refresh_tokens  todos                        │
└──────────────────────────────────────────────┘
```

The Clojure API is a **single process doing two jobs**: it acts as both the OAuth 2.0 authorization server (`/auth/*`, `/oauth/*`) and the protected todo resource server (`/api/*`). They share one Jetty instance, one router, one MongoDB connection, and one middleware stack.

This is a deliberate simplification for learning — a production system might split these into separate services. Here, keeping them together means you can read the entire backend without jumping between repos.

## Request lifecycle

Let's trace what happens when the React frontend creates a todo. By the time the user clicks "Add", a lot has already happened (auth flow, token storage) — this picks up from a request that already has a valid access token.

**1. User submits the form (`pages/TodosPage.tsx`)**

The `create` mutation from `useTodos()` in `hooks/useTodos.ts` calls `api.post('/api/todos', { title, body, priority })`.

**2. `lib/api.ts` adds the Bearer token**

`api.post` retrieves the in-memory access token and sets `Authorization: Bearer <token>` on the request. The refresh token cookie is automatically attached by the browser (it's `httpOnly` — JavaScript never touches it).

**3. The request hits the Clojure middleware stack**

Every incoming request passes through this chain, outermost to innermost:

```
wrap-cors       → sets CORS headers for localhost:5173
wrap-cookies    → parses Cookie header into a Clojure map
wrap-params     → parses query string and form params
wrap-session    → manages the server-side session (used during OAuth login flow)
router          → matches the path and dispatches to the right handler
  └─ wrap-auth  → only applied to /api/* routes — validates the Bearer token
```

`wrap-auth` extracts the JWT from `Authorization: Bearer`, verifies its signature against `JWT_SECRET`, and assocs `:user-id` onto the request map. If the token is missing or invalid, it short-circuits with `401` before the handler runs.

**4. `todos/create-handler` runs**

The handler receives a request map. It reads `:user-id` (set by `wrap-auth`) and `:body-params` (parsed by Muuntaja). It inserts a new document into the `todos` MongoDB collection and returns a `201` response map with the created todo.

Note: the handler never reads `user_id` from the request body. It always comes from the validated JWT. This means a malicious client can't create todos owned by another user.

**5. Muuntaja serialises the response**

The `:body` of the response map is a Clojure map. Muuntaja middleware converts it to JSON automatically based on the `Accept` header.

**6. React updates the UI**

TanStack Query receives the `201` response, calls `queryClient.invalidateQueries(['todos'])`, and the todo list re-fetches with the new item.

## Key design decisions

### Stateless access tokens

JWTs are verified by cryptographic signature — the server doesn't look them up in the database. This means logout (`POST /oauth/revoke`) **cannot invalidate an access token that's already been issued**. It only revokes the refresh token, which prevents new access tokens from being issued.

This is an intentional trade-off: stateless tokens are fast (no DB lookup on every request) but have a bounded exposure window. Access tokens expire after 15 minutes. If a token is stolen, the attacker has at most 15 minutes to use it.

### httpOnly cookie for refresh tokens

The refresh token is stored in an `httpOnly` cookie set by the Clojure server. JavaScript — including any XSS payload — cannot read it. The browser sends it automatically on requests to `/oauth/token`. This is why `api.ts` doesn't need to do anything special to include it on refresh calls.

The access token, by contrast, lives in a module-level variable in `api.ts`. It disappears on page refresh, which forces a silent refresh via the cookie. This is intentional — it keeps the access token out of `localStorage` (vulnerable to XSS) and out of cookies (vulnerable to CSRF if not handled carefully).

### `user_id` always comes from the JWT

Todo handlers never read `user_id` from the request body or query string. `wrap-auth` extracts it from the validated token and assocs it onto the request map as `:user-id`. Handlers read it from there. This makes ownership spoofing structurally impossible regardless of what the client sends.

### Data-driven everything

Clojure encourages representing things as plain data rather than objects or annotations. You'll see this pattern throughout the project:

- **Routes** are a vector of vectors: `["/api/todos" {:get list-handler :post create-handler}]`
- **Config** is an EDN file: `{:server {:port #or [#env PORT 3000]}}`
- **Responses** are plain maps: `{:status 201 :body {:id "..." :title "..."}}`
- **Middleware** is just a function wrapping another function

This means the entire routing table is inspectable data. You can print it, query it, transform it — it's not locked inside a framework's internal state.

## How the pieces connect

### Monorepo tooling

`pnpm dev` at the repo root calls `turbo dev`, which runs the `dev` script in both `apps/api/package.json` and `apps/web/package.json` concurrently. The Clojure app's `dev` script calls `clj -M:run`; the React app's calls `vite`.

Both apps share a single `pnpm-lock.yaml` and `node_modules` via pnpm workspaces, but they're otherwise independent. Turborepo caches task outputs so rebuilding one app doesn't unnecessarily rebuild the other.

### MongoDB collections

| Collection | What it stores |
|------------|---------------|
| `users` | Accounts with bcrypt-hashed passwords. `email` and `login` are unique-indexed. |
| `clients` | Registered OAuth clients. Seeded on startup with the `react-app` client. |
| `auth_codes` | Single-use authorization codes, valid for 10 minutes, tied to a PKCE challenge. |
| `refresh_tokens` | Long-lived tokens (7 days) stored as random strings, revocable via `POST /oauth/revoke`. |
| `todos` | Todo items owned by a user. `user_id` is always an ObjectId from the JWT — never from the request body. |

## What this project doesn't include

This project is intentionally scoped as a learning exercise. The following are absent by design, not oversight:

- **Email verification** — signup creates an account immediately
- **Rate limiting** — no throttling on auth endpoints
- **Multi-tenancy** — all users share one MongoDB database
- **HTTPS** — assumed to be handled by a reverse proxy in production
- **Password reset flow** — not implemented
- **Refresh token rotation** — tokens are revoked but not rotated on use
