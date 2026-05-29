# System & Dependency-Injection Refactor — Design Spec

**Date:** 2026-05-28
**Status:** Approved
**Scope:** `apps/api` only.
**Source:** Improvement #1 from `2026-05-28-api-architecture-review.md`.
**Goal:** Remove global mutable state by building the system (config + DB connection + HTTP server) once at startup and passing `db`/`config` into handlers explicitly. The concrete payoff is a test seam: tests build an app pointed at a dedicated test database instead of mutating shared globals.

---

## 1. Problem being solved

Today:
- The DB lives in module-level atoms (`db/core.clj` `connection`/`database`) and handlers reach into it via `(db/get-db)`.
- Config is loaded three separate times: `core.clj`, `db/core.clj`, `middleware/auth.clj`, each with its own `(delay (read-config ...))`.
- Consequence: the dependency graph is implicit, there is no seam to inject a different DB, and tests (`todos/handlers_test.clj`, `oauth/handlers_test.clj`) are forced to connect to the real dev MongoDB and drop its collections. `disconnect!` is dead code and there is no shutdown hook.

The fix is a **hand-rolled system map** (chosen over Integrant/mount for learning clarity and zero new dependencies): explicit `start`/`stop` functions return a map of live resources, and those resources are passed into request handling.

---

## 2. Target namespace layout

| Namespace | Responsibility (after) |
|-----------|------------------------|
| `api.config` *(new)* | `load-config` — read `config.edn` once, return the config map. |
| `api.db.core` | Pure connection fns: `connect`, `disconnect`. No atoms, no global, no config. |
| `api.db.seed` | `seed-clients!` takes `db` as a parameter. |
| `api.system` *(new)* | `routes` (moved from core), `make-app`, `wrap-deps`, `start`, `stop`. The wiring + lifecycle. |
| `api.core` | `-main` only: load config, `start`, register shutdown hook, block on the server. |
| `api.middleware.auth` | `wrap-auth` reads the JWT secret from the injected request config; stays a 1-arg middleware. |
| `api.auth.handlers` | Handlers read `db` from the request; DB helper fns take `db`. |
| `api.oauth.handlers` | Handlers read `db` and JWT secret from the request; helper fns take `db`/secret as params. |
| `api.todos.handlers` | Handlers read `db` from the request. |

---

## 3. Component design

### 3.1 `api.config`
```clojure
(ns api.config
  (:require [aero.core :refer [read-config]]
            [clojure.java.io :as io]))

(defn load-config []
  (read-config (io/resource "config.edn")))
```
`config.edn` changes the port line to coerce via aero:
```clojure
:server {:port #long #or [#env PORT 3000]}
```
(If the installed aero version lacks the `#long` tag, fall back to coercing in `load-config`. The implementation plan must verify which applies.)

### 3.2 `api.db.core`
```clojure
(defn connect [uri]
  (let [{:keys [conn db]} (mg/connect-via-uri uri)]
    (mc/ensure-index db "users" (array-map :email 1) {:unique true})
    (mc/ensure-index db "users" (array-map :login 1) {:unique true})
    {:conn conn :db db}))

(defn disconnect [conn]
  (mg/disconnect conn))
```
Deleted: `connection` atom, `database` atom, `config` delay, `connect!`, `get-db`.

### 3.3 `api.system`
```clojure
(defn wrap-deps [handler db config]
  (fn [request]
    (handler (assoc request :db db :config config))))

(def routes [...])  ; moved verbatim from core.clj

(defn make-app [{:keys [db config]}]
  (-> (ring/ring-handler
       (ring/router routes {:data {:muuntaja m/instance
                                   :middleware [muuntaja/format-middleware]}})
       (ring/create-default-handler))
      (wrap-deps db config)
      (wrap-cookies)
      (wrap-params)
      (wrap-cors :access-control-allow-origin [#"^http://localhost:5173$"]
                 :access-control-allow-methods [:get :post :put :delete :options]
                 :access-control-allow-headers ["Authorization" "Content-Type"]
                 :access-control-allow-credentials "true")))

(defn start [config]
  (let [{:keys [conn db]} (db/connect (get-in config [:db :uri]))]
    (seed/seed-clients! db)
    (let [server (jetty/run-jetty (make-app {:db db :config config})
                                  {:port (get-in config [:server :port]) :join? false})]
      {:config config :conn conn :db db :server server})))

(defn stop [{:keys [conn server]}]
  (when server (.stop server))
  (when conn (db/disconnect conn)))
```

**Middleware order rationale:** `wrap-deps` wraps the router directly, so `:db`/`:config` are present on the request before reitit dispatches. Route-data middleware (`wrap-auth` inside `/api`) therefore also sees them. `make-app` takes a system-shaped map so tests can call it with a test `db`/`config`.

### 3.4 `api.core`
```clojure
(defn -main [& _]
  (let [system (system/start (config/load-config))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(system/stop system)))
    (println (str "Server running on port " (get-in system [:config :server :port])))
    (.join ^org.eclipse.jetty.server.Server (:server system))))
```

### 3.5 Handler migration
- **todos**: each handler already destructures the request — add `db` to `{:keys [...]}` and replace `(db/get-db)` with `db`.
- **auth**: `signup-handler`/`login-handler` read `db` from request. `find-user-by-email-or-login` already takes a `db-conn` arg — pass `db`. `login-handler` calls `(oauth/find-client db client-id)` and `(oauth/valid-redirect? client redirect-uri)`.
- **oauth**: `find-client` takes `db`. `exchange-code` and the refresh branch take `db` + `secret` (read from request config). `verify-access-token`/`userinfo-handler` read the secret from `(:config request)`. `jwt-secret` helper is replaced by reading the injected config. `metadata-handler` is unaffected.
- **middleware/auth**: `wrap-auth` reads `(get-in request [:config :jwt :secret])` instead of its own config delay.

---

## 4. Test strategy

- Add a test config value for a **separate database** so the suite never touches dev data — e.g. `MONGODB_URI` defaulting to `mongodb://localhost:27017/tododb` for the app, with tests overriding to `…/tododb_test` (via a test helper that builds a config map with the test URI).
- A shared test helper builds the app once per fixture:
  ```clojure
  (def test-config {:db {:uri "mongodb://localhost:27017/tododb_test"}
                    :jwt {:secret (get-in (config/load-config) [:jwt :secret])}
                    :server {:port 0}})
  (defn with-app [f]
    (let [{:keys [conn db]} (db/connect (get-in test-config [:db :uri]))
          app (system/make-app {:db db :config test-config})]
      ;; bind app + db for the test, drop collections before/after, disconnect at end
      ...))
  ```
- Existing tests change from `(app …)` on the global handler + `(db/connect!)`/`(db/get-db)` to the injected `app`/`db` from the helper. Test assertions and request-building stay the same.
- **Out of scope (YAGNI):** no in-memory/fake Mongo this cycle. Tests still hit a real Mongo; the seam now merely *allows* a fake later without touching handlers.

---

## 5. Build order (TDD)

1. `api.config` + `config.edn` port coercion.
2. `api.db.core` → `connect`/`disconnect` returning `{:conn :db}`.
3. `api.system` → `wrap-deps`, move `routes`, `make-app`, `start`, `stop`.
4. Migrate handlers + `wrap-auth` to read `db`/`config` from the request.
5. `api.core` `-main` → start + shutdown hook + join.
6. Update tests to use the injected app + `tododb_test`.

Each step keeps the suite green; the existing tests are the regression net. Run `clj -M:test` after each step, and `clj -M:clj-kondo` at the end.

---

## 6. Explicit non-goals

- No Integrant/mount/Component (hand-rolled chosen deliberately).
- No change to routes, request/response shapes, the OAuth flow, or the web app.
- No validation (malli) or error-middleware work — those are separate roadmap items (#2, #3).
- No in-memory database fake.

---

## 7. Acceptance criteria

- No `defonce`/atom-backed global DB state and no `(db/get-db)` calls remain.
- Config is loaded exactly once (no `read-config` delays scattered across namespaces).
- `start`/`stop` build and tear down the full system; `-main` uses them and cleans up via a shutdown hook.
- Handlers obtain `db` (and JWT secret) from the request.
- Tests run against `tododb_test` via the injected app and pass; `clj-kondo` is clean.
