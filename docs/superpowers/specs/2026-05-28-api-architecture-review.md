# API Architecture Review — clojure-todo-app

**Date:** 2026-05-28
**Scope:** The Clojure API (`apps/api`). The React web app is out of scope except where it crosses the wire.
**Context:** This is a learning project (see the original design spec, `2026-05-27-clojure-todo-app-design.md`). The goal of this document is to teach idiomatic Clojure by contrasting it with the current code — not to demand a production rewrite. Recommendations are sequenced and each is optional.

## How to read this

Every issue below names the **principle** behind the recommendation, not just the fix. The point is that you walk away understanding *why* idiomatic Clojure leans the way it does, so you can apply the judgment elsewhere. Effort/risk labels are rough guides for sequencing.

---

## Strengths (keep doing these)

- **Feature-layered namespaces.** `core.clj` assembles routes; handlers live under `auth/`, `oauth/`, `todos/`; DB is isolated in `db/`. Conventional, readable Clojure web layout.
- **Data-driven routing.** Routes are plain data and middleware is attached declaratively (`core.clj:32-40`). This is exactly how reitit is intended to be used.
- **Handlers as `request → response` functions** that destructure with `{:keys [...]}`. Idiomatic Ring.
- **`cond->` for partial updates** (`todos/handlers.clj:55-61`) — the elegant Clojure idiom where many beginners reach for mutable accumulation.
- **`some->` for nil-safe date formatting** (`todos/handlers.clj:14-17`).
- **Map lookups with defaults**, e.g. `(:priority body-params "medium")`.
- **Security is thoughtful for a learning project:** `user_id` always from the JWT (never the request body); single-use auth codes; PKCE S256 verification (`oauth/handlers.clj:78`); `redirect_uri` re-validated at login to block code exfiltration (`auth/handlers.clj:99-102`); HTML-escaping in the login template; `SecureRandom`; HttpOnly/SameSite cookies.

The overall code is clean and readable. The improvements below are about *structure and idiom*, not correctness of the happy path.

---

## Improvement opportunities (prioritized)

### 1. Replace global mutable state with an explicit system — **highest learning payoff**

**Where:** `db/core.clj:7-8` (module-level `connection`/`database` atoms), every handler's `(db/get-db)` call, and config loaded three separate times in `core.clj:19`, `db/core.clj:10`, `middleware/auth.clj:8`.

**The principle:** Idiomatic Clojure pushes stateful resources (DB connections, config, the running server) into a single **system value** that is *built once at startup and passed in explicitly*, rather than reached for through global vars. This makes the dependency graph visible, makes startup/shutdown ordered and explicit, and — critically — creates a **seam** so tests can inject a fake or test-scoped DB.

**Evidence of the cost today:** the test fixtures (`todos/handlers_test.clj:38-44`, `oauth/handlers_test.clj:10-21`) are forced to connect to a *real* MongoDB and drop collections, because there is no way to hand the handlers a different db. There is also a `disconnect!` that is never called and no shutdown hook (`core.clj:56-62`).

**Idiomatic shape (illustrative, not prescriptive):**

```clojure
;; Build the system once; pass `db` (and config) into handlers explicitly.
(defn start [config]
  (let [db     (connect (:db config))
        app    (make-app {:db db :config config})
        server (jetty/run-jetty app {:port (:port config) :join? false})]
    {:db db :server server}))

(defn stop [{:keys [db server]}]
  (.stop server)
  (disconnect db))
```

Handlers then take the db from the request (injected by a small middleware) or via a closure, instead of `(db/get-db)`. Tests build a system pointed at a throwaway db and tear it down.

**Tooling options to learn from:** plain functions + a system map (simplest, no deps), **Integrant** (data-driven, very popular), **mount** (var-based, least intrusive), **Component** (the original). For a learning project, doing it *by hand first* and *then* adopting Integrant teaches the most.

**Effort:** Medium–High. **Blast radius:** every namespace. **Risk:** moderate — but the existing tests give you a safety net.

### 2. Declarative validation with malli — **removes hand-rolled boilerplate**

**Where:** `validate-signup` (`auth/handlers.clj:12-19`) and the inline title check (`todos/handlers.clj:24-26`).

**The principle:** With reitit + muuntaja (both already in `deps.edn`), request/response shapes are best expressed as **data schemas** attached to routes. reitit coerces and rejects bad input with a 400 *before* your handler runs, so handlers only deal with valid data. malli is the modern choice (spec is the alternative).

**Idiomatic shape:**

```clojure
["/todos" {:post {:parameters {:body [:map
                                      [:title :string]
                                      [:priority {:optional true} [:enum "low" "medium" "high"]]
                                      [:tags {:optional true} [:vector :string]]]}
                  :handler create-handler}}]
;; Invalid body -> automatic 400 with a structured error; handler never sees it.
```

This also gives you a place to define the canonical Todo shape once and reuse it for responses.

**Effort:** Medium. **Blast radius:** route definitions + handler bodies shrink. **Risk:** low, self-contained per route.

### 3. Centralized error-handling middleware — **no more raw 500s**

**Where:** Today each handler catches its own exceptions (signup's duplicate-key, `auth/handlers.clj:40-43`). Unguarded throws escape — e.g. `(ObjectId. (:id path-params))` on a malformed id (`todos/handlers.clj:44`, `:51`) throws `IllegalArgumentException`, which becomes a raw 500.

**The principle:** Cross-cutting concerns belong in middleware, not scattered through handlers. reitit ships exception middleware that maps exception classes to responses in one place.

**Idiomatic shape:**

```clojure
(exception/create-exception-middleware
 {IllegalArgumentException (fn [_ _] {:status 400 :body {:error "invalid id"}})
  ;; map duplicate-key here too, replacing the inline try/catch
  ::exception/default       (fn [_ _] {:status 500 :body {:error "internal error"}})})
```

**Effort:** Low. **Blast radius:** `core.clj` middleware stack + delete a couple of inline `try/catch`. **Risk:** low.

### 4. Remove duplication around tokens — **DRY**

**Where:**
- Bearer-header parsing + `jwt/unsign` is implemented twice: `middleware/auth.clj:11-21` and `verify-access-token` in `oauth/handlers.clj:140-146`. (`/oauth/userinfo` sits outside the `/api` group so it doesn't get `wrap-auth`; consider restructuring so it can reuse the same verification, or extract a shared `verify-token` fn.)
- "Find user → sign access token → build token response" is repeated in `exchange-code` (`oauth/handlers.clj:84-105`) and the refresh branch of `token-handler` (`oauth/handlers.clj:119-128`). Extract one `issue-access-token` fn.

**Effort:** Low. **Risk:** low.

### 5. Minor smells

- **Namespace boundary:** `auth.handlers` requires `oauth.handlers` for `find-client`/`valid-redirect?` (`auth/handlers.clj:90-101`). Client logic arguably belongs in its own `clients` (or `oauth.clients`) namespace that both depend on, removing the auth→oauth coupling.
- **Magic time constants** like `(* 15 60 1000)` and `(* 7 24 60 60 1000)` recur (`oauth/handlers.clj`, `auth/handlers.clj`). Name them (`access-token-ttl-ms`, etc.).
- **Lifecycle:** `disconnect!` is never called and there's no JVM shutdown hook (folded into item 1).
- **Port coercion** `(if (string? raw) (Integer/parseInt raw) raw)` (`core.clj:59-60`) can be handled by aero with a `#long` tag in `config.edn`.

---

## Recommended sequence

1. **System & DI (item 1)** first — it's the biggest lesson and it creates the test seam everything else benefits from.
2. **Validation (item 2)** next — independent, shrinks handlers, teaches malli.
3. **Error middleware (item 3)** — quick win, pairs naturally with validation.
4. **DRY cleanups (items 4–5)** — easiest, do anytime; nice warm-up if you'd rather start small.

If you'd prefer to build confidence first, items 3 → 4 → 5 are low-risk and can be done before the larger item 1.

---

## Deliberately NOT recommended (YAGNI for this project)

These would be over-engineering for a single-developer learning app, and skipping them is the right call:

- **Splitting the auth server and resource server into separate services.** The single-process design is a stated, sound decision (original spec §8.1).
- **Swapping MongoDB or adding a SQL layer / migrations.** Not a learning goal here.
- **A full logging/metrics/observability stack.** A single request-logging middleware is plenty if you want any; more is premature.
- **Token rotation, JWKS, multi-client support, rate limiting.** Real OAuth-server concerns, but noise for a learning exercise.

---

## Status

This is a review + roadmap, not an implementation plan. Pick a thread when ready; each chosen thread should get its own brainstorm → plan → implement cycle.
