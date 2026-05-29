# System & Dependency-Injection Refactor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove global mutable DB/config state from `apps/api` by building the system (config + DB connection + HTTP server) once and passing `db`/`config` into handlers explicitly, creating a real test seam.

**Architecture:** Hand-rolled system map. New `api.config` loads config once; `api.db.core` becomes pure `connect`/`disconnect`; new `api.system` holds `routes`, a `wrap-deps` middleware that injects `:db`/`:config` onto each request, `make-app`, and `start`/`stop`. Handlers read `db` (and the JWT secret) from the request. The refactor is done in green-at-every-commit slices: transitional globals are kept until all consumers migrate, then deleted.

**Tech Stack:** Clojure 1.12, reitit, Ring/Jetty, monger (MongoDB), buddy (JWT), aero (config), cognitect test-runner + ring-mock.

---

## Prerequisites

- A local MongoDB must be running for the test suite (tests hit a real DB). Start it with `docker compose up -d` from the repo root.
- All commands below run from `apps/api/`.
- Tests use a dedicated database `tododb_test` so dev data in `tododb` is never touched.
- Focused test run: `clj -M:test -n <namespace>`. Full run: `clj -M:test`. Lint: `clj -M:clj-kondo --lint src test`.

## File overview

| File | Change |
|------|--------|
| `src/api/config.clj` | **Create** — `load-config`. |
| `src/api/db/core.clj` | Add pure `connect`/`disconnect`; keep transitional globals (Task 2); delete globals (Task 8). |
| `src/api/db/seed.clj` | `seed-clients!` takes `db`. |
| `src/api/system.clj` | **Create** — `routes`, `wrap-deps`, `make-app`, `start`, `stop`. |
| `src/api/core.clj` | Shrinks to `-main` using `system/start` + shutdown hook (Task 7). |
| `src/api/middleware/auth.clj` | `wrap-auth` reads secret from injected request config. |
| `src/api/auth/handlers.clj` | Handlers read `db` from request; helpers take `db`. |
| `src/api/oauth/handlers.clj` | Handlers read `db`/secret from request; helpers take `db`/secret. |
| `src/api/todos/handlers.clj` | Handlers read `db` from request. |
| `test/api/test_helpers.clj` | **Create** — `test-config`, `make-test-system`. |
| `test/api/config_test.clj` | **Create**. |
| `test/api/system_test.clj` | **Create**. |
| `test/api/db/seed_test.clj` | **Create**. |
| `test/api/db/core_test.clj` | Migrate to `connect`/`disconnect` (Task 8). |
| `test/api/core_test.clj` | Repoint to `system/make-app` (Task 7). |
| `test/api/todos/handlers_test.clj` | Use injected app/db (Task 5). |
| `test/api/oauth/handlers_test.clj` | Use injected app/db (Task 6). |
| `test/api/auth/handlers_test.clj` | Use injected app/db; inject `:db` into direct `login-handler` calls (Task 6). |

---

### Task 1: `api.config` — load config once

**Files:**
- Create: `src/api/config.clj`
- Test: `test/api/config_test.clj`

- [ ] **Step 1: Write the failing test**

`test/api/config_test.clj`:
```clojure
(ns api.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [api.config :as config]))

(deftest load-config-test
  (testing "load-config returns a config map with an integer port, db uri, and jwt secret"
    (let [cfg (config/load-config)]
      (is (integer? (get-in cfg [:server :port])))
      (is (string? (get-in cfg [:db :uri])))
      (is (string? (get-in cfg [:jwt :secret]))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clj -M:test -n api.config-test`
Expected: FAIL — namespace `api.config` cannot be found / `load-config` undefined.

- [ ] **Step 3: Create the implementation**

`src/api/config.clj`:
```clojure
(ns api.config
  (:require [aero.core :refer [read-config]]
            [clojure.java.io :as io]))

(defn load-config []
  (let [cfg (read-config (io/resource "config.edn"))]
    (update-in cfg [:server :port]
               (fn [p] (if (string? p) (Integer/parseInt p) p)))))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `clj -M:test -n api.config-test`
Expected: PASS (1 test, 3 assertions).

- [ ] **Step 5: Commit**

```bash
git add src/api/config.clj test/api/config_test.clj
git commit -m "feat(api): add api.config/load-config to load config once"
```

---

### Task 2: `api.db.core` — pure `connect`/`disconnect` (keep transitional globals)

**Files:**
- Modify: `src/api/db/core.clj`
- Test: `test/api/db/core_test.clj`

- [ ] **Step 1: Write the failing test (add to existing file)**

Add this deftest to `test/api/db/core_test.clj` (keep the existing `connection-test` for now):
```clojure
(deftest connect-returns-conn-and-db-test
  (testing "connect returns a map with a live conn and db"
    (let [{:keys [conn db]} (db/connect "mongodb://localhost:27017/tododb_test")]
      (is (some? conn))
      (is (some? db))
      (db/disconnect conn))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clj -M:test -n api.db.core-test`
Expected: FAIL — `db/connect` undefined.

- [ ] **Step 3: Modify `src/api/db/core.clj`**

Replace the whole file with (adds `connect`/`disconnect`; reimplements the globals on top so existing callers keep working):
```clojure
(ns api.db.core
  (:require [monger.core :as mg]
            [monger.collection :as mc]
            [aero.core :refer [read-config]]
            [clojure.java.io :as io]))

(defn connect [uri]
  (let [{:keys [conn db]} (mg/connect-via-uri uri)]
    (mc/ensure-index db "users" (array-map :email 1) {:unique true})
    (mc/ensure-index db "users" (array-map :login 1) {:unique true})
    {:conn conn :db db}))

(defn disconnect [conn]
  (mg/disconnect conn))

;; --- transitional global API (removed in Task 8 once all callers migrate) ---
(defonce ^:private connection (atom nil))
(defonce ^:private database   (atom nil))

(def ^:private config
  (delay (read-config (io/resource "config.edn"))))

(defn connect! []
  (when (nil? @connection)
    (let [{:keys [conn db]} (connect (get-in @config [:db :uri]))]
      (reset! connection conn)
      (reset! database db))))

(defn get-db [] @database)

(defn disconnect! []
  (when-let [conn @connection]
    (disconnect conn)
    (reset! connection nil)
    (reset! database nil)))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `clj -M:test -n api.db.core-test`
Expected: PASS (both `connection-test` and `connect-returns-conn-and-db-test`).

- [ ] **Step 5: Commit**

```bash
git add src/api/db/core.clj test/api/db/core_test.clj
git commit -m "refactor(api): add pure db/connect and db/disconnect alongside globals"
```

---

### Task 3: `seed-clients!` takes a `db` argument

**Files:**
- Modify: `src/api/db/seed.clj`
- Modify: `src/api/core.clj` (the only caller, line 58)
- Test: `test/api/db/seed_test.clj`

- [ ] **Step 1: Write the failing test**

`test/api/db/seed_test.clj`:
```clojure
(ns api.db.seed-test
  (:require [clojure.test :refer [deftest is testing]]
            [api.db.core :as db]
            [api.db.seed :as seed]
            [monger.collection :as mc]))

(deftest seed-clients-inserts-react-app-test
  (testing "seed-clients! inserts the react-app client when it is missing"
    (let [{:keys [conn db]} (db/connect "mongodb://localhost:27017/tododb_test")]
      (mc/drop db "clients")
      (seed/seed-clients! db)
      (is (some? (mc/find-one-as-map db "clients" {:client_id "react-app"})))
      (mc/drop db "clients")
      (db/disconnect conn))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clj -M:test -n api.db.seed-test`
Expected: FAIL — `seed-clients!` is currently 0-arity (wrong number of args).

- [ ] **Step 3: Modify `src/api/db/seed.clj`**

Replace the file with:
```clojure
(ns api.db.seed
  (:require [monger.collection :as mc])
  (:import [org.bson.types ObjectId]))

(defn seed-clients! [db]
  (let [coll     "clients"
        existing (mc/find-one-as-map db coll {:client_id "react-app"})]
    (when (nil? existing)
      (mc/insert db coll
                 {:_id           (ObjectId.)
                  :client_id     "react-app"
                  :name          "React Todo App"
                  :redirect_uris ["http://localhost:5173/callback"]
                  :grant_types   ["authorization_code" "refresh_token"]}))))
```

- [ ] **Step 4: Update the caller in `src/api/core.clj`**

Change line 58 from:
```clojure
  (seed/seed-clients!)
```
to:
```clojure
  (seed/seed-clients! (db/get-db))
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `clj -M:test`
Expected: PASS — all existing tests still green, plus the new seed test.

- [ ] **Step 6: Commit**

```bash
git add src/api/db/seed.clj src/api/core.clj test/api/db/seed_test.clj
git commit -m "refactor(api): seed-clients! takes db as an argument"
```

---

### Task 4: `api.system` — wiring and lifecycle

**Files:**
- Create: `src/api/system.clj`
- Test: `test/api/system_test.clj`

- [ ] **Step 1: Write the failing test**

`test/api/system_test.clj`:
```clojure
(ns api.system-test
  (:require [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [api.system :as system]))

(deftest make-app-serves-health-test
  (testing "make-app builds a handler that serves /health without needing a db"
    (let [app (system/make-app {:db nil :config nil})
          res (app (-> (mock/request :get "/health")
                       (mock/header "accept" "application/json")))]
      (is (= 200 (:status res))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clj -M:test -n api.system-test`
Expected: FAIL — namespace `api.system` cannot be found.

- [ ] **Step 3: Create `src/api/system.clj`**

```clojure
(ns api.system
  (:require [ring.adapter.jetty :as jetty]
            [reitit.ring :as ring]
            [muuntaja.core :as m]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [api.db.core :as db]
            [api.db.seed :as seed]
            [api.auth.handlers :as auth]
            [api.oauth.handlers :as oauth]
            [api.todos.handlers :as todos]
            [api.middleware.auth :refer [wrap-auth]]))

(def routes
  [["/health"                                 {:get (fn [_] {:status 200 :body {:status "ok"}})}]
   ["/.well-known/oauth-authorization-server" {:get oauth/metadata-handler}]
   ["/auth/signup"                            {:post auth/signup-handler}]
   ["/auth/login"                             {:get  auth/login-page-handler
                                               :post auth/login-handler}]
   ["/oauth/authorize"                        {:get oauth/authorize-handler}]
   ["/oauth/token"                            {:post oauth/token-handler}]
   ["/oauth/revoke"                           {:post oauth/revoke-handler}]
   ["/oauth/userinfo"                         {:get oauth/userinfo-handler}]
   ["/api"
    {:middleware [wrap-auth]}
    ["/todos"
     {:get  todos/list-handler
      :post todos/create-handler}]
    ["/todos/:id"
     {:get    todos/get-handler
      :put    todos/update-handler
      :delete todos/delete-handler}]]])

(defn wrap-deps [handler db config]
  (fn [request]
    (handler (assoc request :db db :config config))))

(defn make-app [{:keys [db config]}]
  (-> (ring/ring-handler
       (ring/router routes
                    {:data {:muuntaja   m/instance
                            :middleware [muuntaja/format-middleware]}})
       (ring/create-default-handler))
      (wrap-deps db config)
      (wrap-cookies)
      (wrap-params)
      (wrap-cors
       :access-control-allow-origin      [#"^http://localhost:5173$"]
       :access-control-allow-methods     [:get :post :put :delete :options]
       :access-control-allow-headers     ["Authorization" "Content-Type"]
       :access-control-allow-credentials "true")))

(defn start [config]
  (let [{:keys [conn db]} (db/connect (get-in config [:db :uri]))]
    (seed/seed-clients! db)
    (let [server (jetty/run-jetty (make-app {:db db :config config})
                                  {:port (get-in config [:server :port]) :join? false})]
      {:config config :conn conn :db db :server server})))

(defn stop [{:keys [conn server]}]
  (when server (.stop ^org.eclipse.jetty.server.Server server))
  (when conn (db/disconnect conn)))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `clj -M:test -n api.system-test`
Expected: PASS.

- [ ] **Step 5: Run full suite (nothing else should have changed)**

Run: `clj -M:test`
Expected: PASS — all tests green. (Handlers still use the globals; `system` is additive.)

- [ ] **Step 6: Commit**

```bash
git add src/api/system.clj test/api/system_test.clj
git commit -m "feat(api): add api.system with make-app, wrap-deps, start, stop"
```

---

### Task 5: Migrate todos handlers to injected `db` (+ test helper)

**Files:**
- Create: `test/api/test_helpers.clj`
- Modify: `src/api/todos/handlers.clj`
- Modify: `test/api/todos/handlers_test.clj`

- [ ] **Step 1: Create the shared test helper**

`test/api/test_helpers.clj`:
```clojure
(ns api.test-helpers
  (:require [api.config :as config]
            [api.db.core :as db]
            [api.system :as system]))

(def test-config
  (assoc-in (config/load-config) [:db :uri] "mongodb://localhost:27017/tododb_test"))

(defn make-test-system []
  (let [{:keys [conn db]} (db/connect (get-in test-config [:db :uri]))]
    {:conn conn
     :db   db
     :app  (system/make-app {:db db :config test-config})}))
```

- [ ] **Step 2: Rewrite `test/api/todos/handlers_test.clj` to use the injected app/db**

Replace the top of the file (the ns form and fixture) so it builds the app from the helper instead of the global `api.core/app`. Replace lines 1–44 with:
```clojure
(ns api.todos.handlers-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ring.mock.request :as mock]
            [cheshire.core :as json]
            [buddy.sign.jwt :as jwt]
            [api.test-helpers :as helpers]
            [monger.collection :as mc])
  (:import [org.bson.types ObjectId]
           [java.util Date]))

(def system (helpers/make-test-system))
(def app (:app system))
(def test-db (:db system))

(def test-user-id (ObjectId.))
;; Sign tokens with the same secret the injected app verifies against.
(def jwt-secret (get-in helpers/test-config [:jwt :secret]))

(defn make-token [user-id]
  (jwt/sign {:sub (str user-id)
             :exp (Date. (+ (System/currentTimeMillis) 900000))}
            jwt-secret))

(defn auth-request [method path token & [body]]
  (cond-> (mock/request method path)
    true  (mock/header "authorization" (str "Bearer " token))
    true  (mock/header "accept" "application/json")
    body  (mock/content-type "application/json")
    body  (mock/body (json/generate-string body))))

(defn parse-body [response]
  (let [body (:body response)]
    (cond
      (string? body) (json/parse-string body true)
      (bytes? body)  (json/parse-string (String. ^bytes body "UTF-8") true)
      :else          (json/parse-string (slurp body) true))))

(defn db-fixture [f]
  (mc/drop test-db "todos")
  (f)
  (mc/drop test-db "todos"))

(use-fixtures :each db-fixture)
```
Leave the existing `deftest` blocks (lines 46–96) unchanged.

- [ ] **Step 3: Run todos tests to verify they FAIL**

Run: `clj -M:test -n api.todos.handlers-test`
Expected: FAIL/ERROR — handlers still call `(db/get-db)`, which is `nil` because the global was never connected; the injected `db` is ignored.

- [ ] **Step 4: Migrate `src/api/todos/handlers.clj` to read `db` from the request**

Replace the file with:
```clojure
(ns api.todos.handlers
  (:require [monger.collection :as mc]
            [monger.operators :refer [$set]])
  (:import [org.bson.types ObjectId]
           [java.util Date]))

(defn- todo->response [todo]
  {:id         (str (:_id todo))
   :title      (:title todo)
   :body       (:body todo "")
   :completed  (:completed todo false)
   :priority   (:priority todo "medium")
   :due_date   (some-> (:due_date todo) .toInstant .toString)
   :tags       (:tags todo [])
   :created_at (some-> (:created_at todo) .toInstant .toString)
   :updated_at (some-> (:updated_at todo) .toInstant .toString)})

(defn list-handler [{:keys [user-id db]}]
  (let [todos (mc/find-maps db "todos" {:user_id user-id})]
    {:status 200
     :body   {:todos (mapv todo->response todos)}}))

(defn create-handler [{:keys [user-id db body-params]}]
  (if (not (seq (:title body-params)))
    {:status 400 :body {:error "title is required"}}
    (let [now  (Date.)
          todo {:_id        (ObjectId.)
                :user_id    user-id
                :title      (:title body-params)
                :body       (:body body-params "")
                :completed  false
                :priority   (:priority body-params "medium")
                :due_date   nil
                :tags       (vec (:tags body-params []))
                :created_at now
                :updated_at now}]
      (mc/insert db "todos" todo)
      {:status 201
       :body   (todo->response todo)})))

(defn get-handler [{:keys [user-id db path-params]}]
  (let [todo (mc/find-one-as-map db "todos"
                                 {:_id     (ObjectId. (:id path-params))
                                  :user_id user-id})]
    (if (nil? todo)
      {:status 404 :body {:error "todo not found"}}
      {:status 200 :body (todo->response todo)})))

(defn update-handler [{:keys [user-id db path-params body-params]}]
  (let [id   (ObjectId. (:id path-params))
        todo (mc/find-one-as-map db "todos" {:_id id :user_id user-id})]
    (if (nil? todo)
      {:status 404 :body {:error "todo not found"}}
      (let [updates (cond-> {:updated_at (Date.)}
                      (contains? body-params :title)     (assoc :title (:title body-params))
                      (contains? body-params :body)      (assoc :body (:body body-params))
                      (contains? body-params :completed) (assoc :completed (:completed body-params))
                      (contains? body-params :priority)  (assoc :priority (:priority body-params))
                      (contains? body-params :due_date)  (assoc :due_date (:due_date body-params))
                      (contains? body-params :tags)      (assoc :tags (vec (:tags body-params))))]
        (mc/update db "todos" {:_id id} {$set updates})
        {:status 200
         :body   (todo->response (merge todo updates))}))))

(defn delete-handler [{:keys [user-id db path-params]}]
  (mc/remove db "todos"
             {:_id     (ObjectId. (:id path-params))
              :user_id user-id})
  {:status 204 :body nil})
```

- [ ] **Step 5: Run todos tests to verify they PASS**

Run: `clj -M:test -n api.todos.handlers-test`
Expected: PASS (6 tests).

- [ ] **Step 6: Run full suite (auth/oauth still use the globals and stay green)**

Run: `clj -M:test`
Expected: PASS — all tests green.

- [ ] **Step 7: Commit**

```bash
git add test/api/test_helpers.clj src/api/todos/handlers.clj test/api/todos/handlers_test.clj
git commit -m "refactor(api): todos handlers read db from request; add test helper"
```

---

### Task 6: Migrate auth + oauth handlers + auth middleware (and their tests)

These three migrate together because `auth.handlers` depends on `oauth/find-client` (signature changes) and both share `wrap-auth`.

**Files:**
- Modify: `src/api/middleware/auth.clj`
- Modify: `src/api/oauth/handlers.clj`
- Modify: `src/api/auth/handlers.clj`
- Modify: `test/api/oauth/handlers_test.clj`
- Modify: `test/api/auth/handlers_test.clj`

- [ ] **Step 1: Rewrite the oauth test to use the injected app/db**

Replace lines 1–23 of `test/api/oauth/handlers_test.clj` with:
```clojure
(ns api.oauth.handlers-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ring.mock.request :as mock]
            [cheshire.core :as json]
            [api.test-helpers :as helpers]
            [monger.collection :as mc])
  (:import [org.bson.types ObjectId]))

(def system (helpers/make-test-system))
(def app (:app system))
(def test-db (:db system))

(defn db-fixture [f]
  (doseq [coll ["users" "auth_codes" "refresh_tokens" "clients"]]
    (mc/drop test-db coll))
  (mc/insert test-db "clients"
             {:_id           (ObjectId.)
              :client_id     "react-app"
              :redirect_uris ["http://localhost:5173/callback"]
              :grant_types   ["authorization_code" "refresh_token"]})
  (f)
  (doseq [coll ["users" "auth_codes" "refresh_tokens" "clients"]]
    (mc/drop test-db coll)))

(use-fixtures :each db-fixture)
```
In the body of `token-exchange-test`, replace the two `(db/get-db)` calls (the `mc/insert` for `"users"` and `"auth_codes"`) with `test-db`. Leave the rest unchanged.

- [ ] **Step 2: Rewrite the auth test to use the injected app/db**

Replace lines 1–23 of `test/api/auth/handlers_test.clj` with:
```clojure
(ns api.auth.handlers-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ring.mock.request :as mock]
            [cheshire.core :as json]
            [api.test-helpers :as helpers]
            [api.auth.handlers :refer [login-handler]]
            [monger.collection :as mc])
  (:import [org.bson.types ObjectId]))

(def system (helpers/make-test-system))
(def app (:app system))
(def test-db (:db system))

(defn db-fixture [f]
  (doseq [coll ["users" "auth_codes" "clients"]] (mc/drop test-db coll))
  ;; dropping "users" drops its unique indexes too — re-create them so the
  ;; duplicate-email test still gets a 409.
  (mc/ensure-index test-db "users" (array-map :email 1) {:unique true})
  (mc/ensure-index test-db "users" (array-map :login 1) {:unique true})
  (mc/insert test-db "clients"
             {:_id           (ObjectId.)
              :client_id     "react-app"
              :redirect_uris ["http://localhost:5173/callback"]})
  (f)
  (doseq [coll ["users" "auth_codes" "clients"]] (mc/drop test-db coll)))

(use-fixtures :each db-fixture)
```
Then update the two **direct** `login-handler` calls so they carry an injected `:db`:
- In `login-success-test` (around line 73), change `(let [request {:form-params {...}} ...)` to include `:db`:
```clojure
    (let [request  {:db test-db
                    :form-params {"email"          "g@example.com"
                                  "password"       "secret123"
                                  "client_id"      "react-app"
                                  "redirect_uri"   "http://localhost:5173/callback"
                                  "code_challenge" "abc123"
                                  "state"          "xyz"}}
          response (login-handler request)]
```
- In `login-rejects-untrusted-redirect-test` (around line 90), change the map passed to `login-handler` to include `:db test-db`:
```clojure
    (let [response (login-handler
                    {:db test-db
                     :form-params {"email"          "g@example.com"
                                   "password"       "secret123"
                                   "client_id"      "react-app"
                                   "redirect_uri"   "http://evil.example.com/steal"
                                   "code_challenge" "abc123"
                                   "state"          "xyz"}})]
```
Leave the `json-request`/`parse-body` helpers and all assertions unchanged.

- [ ] **Step 3: Run auth + oauth tests to verify they FAIL**

Run: `clj -M:test -n api.oauth.handlers-test -n api.auth.handlers-test`
Expected: FAIL/ERROR — handlers/middleware still use the globals and `(db/get-db)` is `nil`.

- [ ] **Step 4: Migrate `src/api/middleware/auth.clj`**

Replace the file with:
```clojure
(ns api.middleware.auth
  (:require [buddy.sign.jwt :as jwt]
            [clojure.string :as str])
  (:import [org.bson.types ObjectId]))

(defn wrap-auth [handler]
  (fn [request]
    (let [auth-header (get-in request [:headers "authorization"] "")
          token       (second (str/split auth-header #" " 2))
          secret      (get-in request [:config :jwt :secret])]
      (if (empty? token)
        {:status 401 :body {:error "missing authorization header"}}
        (try
          (let [claims (jwt/unsign token secret)]
            (handler (assoc request :user-id (ObjectId. (:sub claims)))))
          (catch Exception _
            {:status 401 :body {:error "invalid or expired token"}}))))))
```

- [ ] **Step 5: Migrate `src/api/oauth/handlers.clj`**

Replace the file with (helpers take `db`/`secret`; handlers read them from the request; the config `delay` and `jwt-secret` helper are gone):
```clojure
(ns api.oauth.handlers
  (:require [buddy.sign.jwt :as jwt]
            [monger.collection :as mc]
            [monger.operators :refer [$set]]
            [clojure.string :as str]
            [ring.util.codec :as codec])
  (:import [org.bson.types ObjectId]
           [java.util Date]))

(defn find-client [db client-id]
  (mc/find-one-as-map db "clients" {:client_id client-id}))

(defn valid-redirect? [client redirect-uri]
  (some #(= % redirect-uri) (:redirect_uris client)))

(defn authorize-handler [{:keys [params db]}]
  (let [client-id     (get params "client_id")
        redirect-uri  (get params "redirect_uri")
        response-type (get params "response_type")
        challenge     (get params "code_challenge")
        state         (get params "state")
        client        (find-client db client-id)]
    (cond
      (nil? client)
      {:status 400 :body {:error "unknown client_id"}}

      (not (valid-redirect? client redirect-uri))
      {:status 400 :body {:error "invalid redirect_uri"}}

      (not= "code" response-type)
      {:status 400 :body {:error "unsupported response_type"}}

      :else
      {:status  302
       :headers {"Location" (str "/auth/login?"
                                 (codec/form-encode {:client_id      client-id
                                                     :redirect_uri   redirect-uri
                                                     :code_challenge (or challenge "")
                                                     :state          (or state "")}))}})))

(defn- sha256-base64url [s]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        bytes  (.digest digest (.getBytes s "UTF-8"))]
    (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) bytes)))

(defonce ^:private secure-random (java.security.SecureRandom.))

(defn- generate-token []
  (let [bytes (byte-array 32)]
    (.nextBytes secure-random bytes)
    (.encodeToString (java.util.Base64/getUrlEncoder) bytes)))

(defn- exchange-code [db secret {:keys [code code_verifier client_id redirect_uri]}]
  (let [auth-code (mc/find-one-as-map db "auth_codes" {:code code :used false})]
    (cond
      (nil? auth-code)
      {:status 400 :body {:error "invalid or used code"}}

      (.before ^Date (:expires_at auth-code) (Date.))
      {:status 400 :body {:error "code expired"}}

      (not= client_id (:client_id auth-code))
      {:status 400 :body {:error "client_id mismatch"}}

      (not= redirect_uri (:redirect_uri auth-code))
      {:status 400 :body {:error "redirect_uri mismatch"}}

      (not= (sha256-base64url code_verifier) (:code_challenge auth-code))
      {:status 400 :body {:error "invalid code_verifier"}}

      :else
      (do
        (mc/update db "auth_codes" {:code code} {$set {:used true}})
        (let [user-id       (:user_id auth-code)
              user          (mc/find-one-as-map db "users" {:_id user-id})
              now           (System/currentTimeMillis)
              access-token  (jwt/sign {:sub      (str user-id)
                                       :login    (:login user)
                                       :email    (:email user)
                                       :exp      (Date. (+ now (* 15 60 1000)))}
                                      secret)
              refresh-token (generate-token)]
          (mc/insert db "refresh_tokens"
                     {:_id        (ObjectId.)
                      :token      refresh-token
                      :user_id    user-id
                      :client_id  client_id
                      :expires_at (Date. (+ now (* 7 24 60 60 1000)))
                      :revoked    false})
          {:status  200
           :headers {"Set-Cookie" (str "refresh_token=" refresh-token
                                       "; HttpOnly; SameSite=Strict; Path=/oauth")}
           :body    {:access_token access-token
                     :token_type   "Bearer"
                     :expires_in   900}})))))

(defn token-handler [{:keys [body-params db config] :as request}]
  (let [secret (get-in config [:jwt :secret])]
    (case (:grant_type body-params)
      "authorization_code"
      (exchange-code db secret body-params)

      "refresh_token"
      (let [token-val    (get-in (:cookies request) ["refresh_token" :value])
            stored-token (mc/find-one-as-map db "refresh_tokens"
                                             {:token token-val :revoked false})]
        (if (or (nil? stored-token)
                (.before ^Date (:expires_at stored-token) (Date.)))
          {:status 401 :body {:error "invalid refresh token"}}
          (let [user         (mc/find-one-as-map db "users" {:_id (:user_id stored-token)})
                access-token (jwt/sign {:sub   (str (:_id user))
                                        :login (:login user)
                                        :email (:email user)
                                        :exp   (Date. (+ (System/currentTimeMillis) (* 15 60 1000)))}
                                       secret)]
            {:status 200
             :body   {:access_token access-token
                      :token_type   "Bearer"
                      :expires_in   900}})))

      {:status 400 :body {:error "unsupported grant_type"}})))

(defn revoke-handler [{:keys [body-params cookies db]}]
  (let [token (or (:token body-params)
                  (get-in cookies ["refresh_token" :value]))]
    (mc/update db "refresh_tokens" {:token token} {$set {:revoked true}})
    {:status  200
     :headers {"Set-Cookie" "refresh_token=; HttpOnly; SameSite=Strict; Path=/oauth; Max-Age=0"}
     :body    {:revoked true}}))

(defn- verify-access-token [request]
  (let [auth-header (get-in request [:headers "authorization"] "")
        token       (second (str/split auth-header #" " 2))
        secret      (get-in request [:config :jwt :secret])]
    (when (seq token)
      (try
        (jwt/unsign token secret)
        (catch Exception _ nil)))))

(defn userinfo-handler [{:keys [db] :as request}]
  (let [claims (verify-access-token request)]
    (if (nil? claims)
      {:status 401 :body {:error "invalid or missing token"}}
      (let [user (mc/find-one-as-map db "users" {:_id (ObjectId. (:sub claims))})]
        {:status 200
         :body   {:sub   (:sub claims)
                  :login (:login user)
                  :name  (:name user)
                  :email (:email user)}}))))

(defn metadata-handler [_]
  {:status 200
   :body   {:issuer                           "http://localhost:3000"
            :authorization_endpoint           "http://localhost:3000/oauth/authorize"
            :token_endpoint                   "http://localhost:3000/oauth/token"
            :revocation_endpoint              "http://localhost:3000/oauth/revoke"
            :userinfo_endpoint                "http://localhost:3000/oauth/userinfo"
            :response_types_supported         ["code"]
            :grant_types_supported            ["authorization_code" "refresh_token"]
            :code_challenge_methods_supported ["S256"]}})
```

- [ ] **Step 6: Migrate `src/api/auth/handlers.clj`**

Replace the file with (handlers read `db` from the request; `db` namespace require removed; `oauth/find-client` now takes `db`):
```clojure
(ns api.auth.handlers
  (:require [buddy.hashers :as hashers]
            [monger.collection :as mc]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [api.oauth.handlers :as oauth])
  (:import [org.bson.types ObjectId]
           [java.security SecureRandom]
           [java.util Base64]))

(defn- validate-signup [{:keys [name login email password]}]
  (cond
    (not (seq name))     "name is required"
    (not (seq login))    "login is required"
    (not (seq email))    "email is required"
    (not (seq password)) "password is required"
    (< (count password) 6) "password must be at least 6 characters"
    :else nil))

(defn signup-handler [{:keys [body-params db]}]
  (let [error (validate-signup body-params)]
    (if error
      {:status 400 :body {:error error}}
      (let [{:keys [name login email password]} body-params
            hashed (hashers/derive password)
            user   {:_id        (ObjectId.)
                    :name       name
                    :login      login
                    :email      email
                    :password   hashed
                    :created_at (java.util.Date.)}]
        (try
          (mc/insert db "users" user)
          {:status 201
           :body   {:id    (str (:_id user))
                    :name  name
                    :login login
                    :email email}}
          (catch com.mongodb.DuplicateKeyException _e
            {:status 409 :body {:error "email or login already taken"}})
          (catch com.mongodb.MongoWriteException _e
            {:status 409 :body {:error "email or login already taken"}}))))))

(defn- html-escape [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")))

(defn- render-login-page [{:keys [error client_id redirect_uri code_challenge state]}]
  (-> (slurp (io/resource "templates/login.html"))
      (str/replace "{{error}}"          (html-escape error))
      (str/replace "{{error_class}}"    (if (seq error) " visible" ""))
      (str/replace "{{client_id}}"      (html-escape client_id))
      (str/replace "{{redirect_uri}}"   (html-escape redirect_uri))
      (str/replace "{{code_challenge}}" (html-escape code_challenge))
      (str/replace "{{state}}"          (html-escape state))))

(defn- login-page-response [status fields]
  {:status  status
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    (render-login-page fields)})

(defn login-page-handler [{:keys [params]}]
  (login-page-response 200 {:client_id      (get params "client_id")
                            :redirect_uri   (get params "redirect_uri")
                            :code_challenge (get params "code_challenge")
                            :state          (get params "state")}))

(defn- find-user-by-email-or-login [db identifier]
  (or (mc/find-one-as-map db "users" {:email identifier})
      (mc/find-one-as-map db "users" {:login identifier})))

(defn- generate-auth-code []
  (let [bytes (byte-array 32)]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString (Base64/getUrlEncoder) bytes)))

(defn login-handler [{:keys [form-params db]}]
  (let [identifier   (get form-params "email")
        password     (get form-params "password")
        client-id    (get form-params "client_id")
        redirect-uri (get form-params "redirect_uri")
        challenge    (get form-params "code_challenge")
        state        (get form-params "state")
        user         (find-user-by-email-or-login db identifier)
        client       (oauth/find-client db client-id)
        fields       {:client_id      client-id
                      :redirect_uri   redirect-uri
                      :code_challenge challenge
                      :state          state}]
    (cond
      (or (nil? user) (not (hashers/check password (:password user))))
      (login-page-response 401 (assoc fields :error "Invalid email or password."))

      (or (nil? client) (not (oauth/valid-redirect? client redirect-uri)))
      (login-page-response 400 (assoc fields :error "This sign-in session is invalid or expired. Open the app to start again."))

      :else
      (let [code      (generate-auth-code)
            auth-code {:_id            (ObjectId.)
                       :code           code
                       :client_id      client-id
                       :user_id        (:_id user)
                       :code_challenge challenge
                       :redirect_uri   redirect-uri
                       :expires_at     (java.util.Date. (+ (System/currentTimeMillis) (* 10 60 1000)))
                       :used           false}]
        (mc/insert db "auth_codes" auth-code)
        {:status  302
         :headers {"Location" (str redirect-uri "?code=" code "&state=" state)}}))))
```

- [ ] **Step 7: Run auth + oauth tests to verify they PASS**

Run: `clj -M:test -n api.oauth.handlers-test -n api.auth.handlers-test`
Expected: PASS.

- [ ] **Step 8: Run full suite**

Run: `clj -M:test`
Expected: PASS — all tests green. (`api.core/app` still exists and is exercised only by `core_test`, which still uses the globals via `db/connect!`. That migrates next.)

- [ ] **Step 9: Commit**

```bash
git add src/api/middleware/auth.clj src/api/oauth/handlers.clj src/api/auth/handlers.clj test/api/oauth/handlers_test.clj test/api/auth/handlers_test.clj
git commit -m "refactor(api): auth/oauth handlers and wrap-auth read db/config from request"
```

---

### Task 7: Shrink `api.core` to the entry point; repoint `core_test`

**Files:**
- Modify: `src/api/core.clj`
- Modify: `test/api/core_test.clj`

- [ ] **Step 1: Repoint `test/api/core_test.clj` to `system/make-app`**

Replace the whole file with (these tests don't touch the DB):
```clojure
(ns api.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [api.system :as system]))

(def app (system/make-app {:db nil :config nil}))

(deftest health-endpoint-test
  (testing "GET /health returns 200 OK"
    (let [response (app (mock/request :get "/health"))]
      (is (= 200 (:status response))))))

(deftest cors-preflight-allows-credentials-test
  (testing "CORS preflight grants credentials so the SPA's credentialed fetches succeed"
    (let [response (app (-> (mock/request :options "/oauth/token")
                            (mock/header "origin" "http://localhost:5173")
                            (mock/header "access-control-request-method" "POST")))]
      (is (= "http://localhost:5173"
             (get-in response [:headers "Access-Control-Allow-Origin"])))
      (is (= "true"
             (get-in response [:headers "Access-Control-Allow-Credentials"]))))))
```

- [ ] **Step 2: Replace `src/api/core.clj` with the slim entry point**

```clojure
(ns api.core
  (:require [api.config :as config]
            [api.system :as system])
  (:gen-class))

(defn -main [& _]
  (let [sys (system/start (config/load-config))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn [] (system/stop sys))))
    (println (str "Server running on port " (get-in sys [:config :server :port])))
    (.join ^org.eclipse.jetty.server.Server (:server sys))))
```

- [ ] **Step 3: Run full suite**

Run: `clj -M:test`
Expected: PASS — all tests green. `api.core` no longer defines `app` or `routes`; both live in `api.system`.

- [ ] **Step 4: Smoke-test the server boots and the shutdown hook is wired**

Run: `clj -M:run` and confirm it prints `Server running on port 3000` and serves `curl -s localhost:3000/health` → `{"status":"ok"}`. Then stop with Ctrl-C and confirm no error on shutdown.
Expected: health responds 200; clean shutdown.

- [ ] **Step 5: Commit**

```bash
git add src/api/core.clj test/api/core_test.clj
git commit -m "refactor(api): core becomes thin entry point using system/start"
```

---

### Task 8: Remove the transitional global DB state

**Files:**
- Modify: `src/api/db/core.clj`
- Modify: `test/api/db/core_test.clj`

- [ ] **Step 1: Confirm nothing still *calls* the globals**

The transitional `connect!`/`get-db`/`disconnect!` are still *defined* in `src/api/db/core.clj` at this point — we only need to confirm no other file still calls them:

Run: `grep -rn "get-db\|connect!\|disconnect!" src test | grep -v "src/api/db/core.clj"`
Expected: no matches. If anything appears, migrate that caller before continuing.

- [ ] **Step 2: Replace `src/api/db/core.clj` with the pure version**

```clojure
(ns api.db.core
  (:require [monger.core :as mg]
            [monger.collection :as mc]))

(defn connect [uri]
  (let [{:keys [conn db]} (mg/connect-via-uri uri)]
    (mc/ensure-index db "users" (array-map :email 1) {:unique true})
    (mc/ensure-index db "users" (array-map :login 1) {:unique true})
    {:conn conn :db db}))

(defn disconnect [conn]
  (mg/disconnect conn))
```

- [ ] **Step 3: Replace `test/api/db/core_test.clj` (drop the global-based test)**

```clojure
(ns api.db.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [api.db.core :as db]))

(deftest connect-test
  (testing "connect returns a live conn and db; disconnect closes the connection"
    (let [{:keys [conn db]} (db/connect "mongodb://localhost:27017/tododb_test")]
      (is (some? conn))
      (is (some? db))
      (db/disconnect conn))))
```

- [ ] **Step 4: Run full suite**

Run: `clj -M:test`
Expected: PASS — all tests green with no global DB state remaining.

- [ ] **Step 5: Commit**

```bash
git add src/api/db/core.clj test/api/db/core_test.clj
git commit -m "refactor(api): remove global DB atoms and config delay"
```

---

### Task 9: Final verification

**Files:** none (verification only).

- [ ] **Step 1: Confirm no scattered config delays or global DB access remain**

Run: `grep -rn "read-config\|get-db\|defonce" src`
Expected: `read-config` appears only in `src/api/config.clj`; no `get-db`; `defonce` appears only for `secure-random` in `src/api/oauth/handlers.clj`.

- [ ] **Step 2: Run the full test suite**

Run: `clj -M:test`
Expected: PASS — every test green.

- [ ] **Step 3: Run the linter**

Run: `clj -M:clj-kondo --lint src test`
Expected: no errors. Fix any warnings the refactor introduced (e.g. unused requires), then re-run.

- [ ] **Step 4: Commit any lint fixes**

```bash
git add -A
git commit -m "chore(api): clean up lint warnings after DI refactor"
```
(Skip this commit if Step 3 reported nothing to fix.)

---

## Self-review notes

- **Spec coverage:** §3.1 → Task 1; §3.2 → Tasks 2 & 8; seed → Task 3; §3.3 (`api.system`) → Task 4; §3.5 handler migration → Tasks 5–6; §3.4 (`-main`) → Task 7; §4 test strategy (`tododb_test`, injected app) → Tasks 5–8; acceptance criteria → Task 9.
- **Port coercion:** the plan coerces the port in `load-config` (deterministic) rather than relying on aero's `#long` tag, superseding the spec's tentative `#long` suggestion (the spec flagged this fallback).
- **Index re-creation:** the auth test fixture re-ensures unique indexes after dropping `users`, preserving the 409 duplicate-email behavior the original fixture depended on.
- **Direct handler calls:** the auth test calls `login-handler` directly, so Task 6 injects `:db` into those bare request maps.
- **Green at every commit:** transitional globals (Task 2) keep unmigrated handlers working until Task 8 deletes them; each handler namespace migrates together with its test.
