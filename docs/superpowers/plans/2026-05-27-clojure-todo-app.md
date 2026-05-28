# Clojure Todo App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a pnpm + Turborepo monorepo with a Clojure OAuth 2.0 authorization server + todo REST API and a responsive React + ShadCN frontend, wired together via Authorization Code + PKCE.

**Architecture:** `apps/api` is a single Clojure process (Ring + Reitit) serving public auth routes (`/auth/*`, `/oauth/*`) and protected todo routes (`/api/*`). `apps/web` is a Vite + React SPA that acts as the OAuth client. MongoDB stores users, clients, auth codes, refresh tokens, and todos. Turborepo orchestrates both apps with `turbo dev`.

**Tech Stack:** pnpm 10 workspaces, Turborepo, Clojure 1.12, Reitit 0.10.1, Ring 1.15.4, Monger 3.6.0, Muuntaja 0.6.11, buddy-sign 3.6.1-359, buddy-hashers 2.0.167, Aero 1.1.6, React 19, Vite 6, React Router 7, TanStack Query 5, ShadCN UI, Tailwind CSS, Zod 4.

**Spec:** `docs/superpowers/specs/2026-05-27-clojure-todo-app-design.md`

---

## Phase 1 — Monorepo scaffold

### Task 1: Root files

**Files:**
- Create: `package.json`
- Create: `pnpm-workspace.yaml`
- Create: `turbo.json`
- Create: `.gitignore`

- [ ] **Step 1: Create `package.json` (workspace root)**

```json
{
  "name": "clojure-todo-app",
  "private": true,
  "scripts": {
    "dev": "turbo dev",
    "build": "turbo build",
    "test": "turbo test"
  },
  "devDependencies": {
    "turbo": "latest"
  },
  "packageManager": "pnpm@10"
}
```

- [ ] **Step 2: Create `pnpm-workspace.yaml`**

```yaml
packages:
  - 'apps/*'
```

- [ ] **Step 3: Create `turbo.json`**

```json
{
  "$schema": "https://turbo.build/schema.json",
  "tasks": {
    "dev": {
      "cache": false,
      "persistent": true
    },
    "build": {
      "dependsOn": ["^build"],
      "outputs": ["target/**", "dist/**"]
    },
    "test": {
      "outputs": []
    }
  }
}
```

- [ ] **Step 4: Create `.gitignore`**

```
# Node
node_modules/
.turbo/
dist/

# Clojure
.cpcache/
target/
.nrepl-port
.clj-kondo/

# Environment
.env
.env.local
.env.*.local

# IDE
.idea/
.vscode/
*.iml

# OS
.DS_Store
Thumbs.db

# Superpowers
.superpowers/
```

- [ ] **Step 5: Install root dependencies**

```bash
pnpm install
```

Expected: `node_modules/.modules.yaml` created, `pnpm-lock.yaml` created.

- [ ] **Step 6: Commit**

```bash
git add package.json pnpm-workspace.yaml turbo.json .gitignore pnpm-lock.yaml
git commit -m "feat: initialize monorepo with pnpm workspaces and Turborepo"
```

---

### Task 2: Clojure API scaffold

**Files:**
- Create: `apps/api/deps.edn`
- Create: `apps/api/package.json`
- Create: `apps/api/resources/config.edn`
- Create: `apps/api/src/api/` (directory)
- Create: `apps/api/test/api/` (directory)

- [ ] **Step 1: Create directory structure**

```bash
mkdir -p apps/api/src/api/db
mkdir -p apps/api/src/api/middleware
mkdir -p apps/api/src/api/auth
mkdir -p apps/api/src/api/oauth
mkdir -p apps/api/src/api/todos
mkdir -p apps/api/resources/templates
mkdir -p apps/api/test/api/db
mkdir -p apps/api/test/api/auth
mkdir -p apps/api/test/api/oauth
mkdir -p apps/api/test/api/todos
```

- [ ] **Step 2: Create `apps/api/deps.edn`**

```clojure
{:paths ["src" "resources"]
 :deps  {org.clojure/clojure         {:mvn/version "1.12.0"}
         metosin/reitit               {:mvn/version "0.10.1"}
         ring/ring-core               {:mvn/version "1.15.4"}
         ring/ring-jetty-adapter      {:mvn/version "1.15.4"}
         buddy/buddy-hashers          {:mvn/version "2.0.167"}
         buddy/buddy-sign             {:mvn/version "3.6.1-359"}
         com.novemberain/monger       {:mvn/version "3.6.0"}
         metosin/muuntaja             {:mvn/version "0.6.11"}
         ring-cors/ring-cors          {:mvn/version "0.1.13"}
         aero                         {:mvn/version "1.1.6"}}
 :aliases
 {:run  {:main-opts ["-m" "api.core"]}
  :test {:extra-paths ["test"]
         :extra-deps  {io.github.cognitect-labs/test-runner
                       {:git/tag "v0.5.1" :git/sha "dfb30dd"}
                       ring/ring-mock {:mvn/version "1.0.0"}}
         :main-opts   ["-m" "cognitect.test-runner" "-d" "test"]}}}
```

- [ ] **Step 3: Create `apps/api/package.json`**

```json
{
  "name": "@todo/api",
  "version": "0.1.0",
  "scripts": {
    "dev": "clj -M:run",
    "test": "clj -M:test"
  }
}
```

- [ ] **Step 4: Create `apps/api/resources/config.edn`**

```clojure
{:server {:port #or [#env PORT 3000]}
 :db     {:uri #or [#env MONGODB_URI "mongodb://localhost:27017/tododb"]}
 :jwt    {:secret #or [#env JWT_SECRET "dev-secret-do-not-use-in-production"]}}
```

- [ ] **Step 5: Commit**

```bash
git add apps/api/
git commit -m "feat: scaffold Clojure API workspace (deps.edn, config)"
```

---

### Task 3: React frontend scaffold

**Files:**
- Create: `apps/web/` (via Vite template)

- [ ] **Step 1: Scaffold Vite + React + TypeScript app**

```bash
pnpm create vite apps/web --template react-ts
```

- [ ] **Step 2: Update `apps/web/package.json` — set workspace name**

Change the `"name"` field to `"@todo/web"`. Leave everything else as Vite generated.

- [ ] **Step 3: Install frontend dependencies**

```bash
cd apps/web
pnpm install
pnpm add react-router-dom @tanstack/react-query react-hook-form @hookform/resolvers zod@4 lucide-react
pnpm add -D tailwindcss@latest postcss autoprefixer
cd ../..
```

- [ ] **Step 4: Initialize Tailwind CSS**

```bash
cd apps/web
npx tailwindcss init -p
cd ../..
```

Replace the contents of `apps/web/tailwind.config.js`:

```js
/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: { extend: {} },
  plugins: [],
}
```

Replace `apps/web/src/index.css` with:

```css
@tailwind base;
@tailwind components;
@tailwind utilities;
```

- [ ] **Step 5: Initialize ShadCN UI**

```bash
cd apps/web
pnpm dlx shadcn@latest init
cd ../..
```

Answer the prompts:
- Style: **Default**
- Base color: **Slate**
- CSS variables: **Yes**

- [ ] **Step 6: Add core ShadCN components used in this project**

```bash
cd apps/web
pnpm dlx shadcn@latest add button input label card badge sheet
cd ../..
```

- [ ] **Step 7: Verify dev server starts**

```bash
cd apps/web && pnpm dev
```

Expected: Vite prints `Local: http://localhost:5173`. Open in browser — React logo should appear. Stop with Ctrl+C.

- [ ] **Step 8: Commit**

```bash
git add apps/web/
git commit -m "feat: scaffold React frontend with Vite, ShadCN, and Tailwind"
```

---

## Phase 2 — Clojure API foundation

### Task 4: Health endpoint (TDD)

**Files:**
- Create: `apps/api/src/api/core.clj`
- Create: `apps/api/test/api/core_test.clj`

- [ ] **Step 1: Write failing test**

Create `apps/api/test/api/core_test.clj`:

```clojure
(ns api.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [api.core :refer [app]]))

(deftest health-endpoint-test
  (testing "GET /health returns 200 OK"
    (let [response (app (mock/request :get "/health"))]
      (is (= 200 (:status response))))))
```

- [ ] **Step 2: Run test — verify it fails**

```bash
cd apps/api && clj -M:test
```

Expected: `FileNotFoundException` or `namespace not found` — `api.core` doesn't exist yet.

- [ ] **Step 3: Implement `apps/api/src/api/core.clj`**

```clojure
(ns api.core
  (:require [ring.adapter.jetty :as jetty]
            [reitit.ring :as ring]
            [muuntaja.core :as m]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.middleware.session :refer [wrap-session]]
            [aero.core :refer [read-config]]
            [clojure.java.io :as io])
  (:gen-class))

(def config
  (delay (read-config (io/resource "config.edn"))))

(def routes
  [["/health" {:get (fn [_] {:status 200 :body {:status "ok"}})}]])

(def app
  (-> (ring/ring-handler
       (ring/router routes
                    {:data {:muuntaja   m/instance
                            :middleware [muuntaja/format-middleware]}})
       (ring/create-default-handler))
      (wrap-session)
      (wrap-cors
       :access-control-allow-origin  [#"http://localhost:5173"]
       :access-control-allow-methods [:get :post :put :delete :options]
       :access-control-allow-headers ["Authorization" "Content-Type"])))

(defn -main [& _]
  (let [port (get-in @config [:server :port])]
    (println (str "Server running on port " port))
    (jetty/run-jetty #'app {:port port :join? true})))
```

- [ ] **Step 4: Run test — verify it passes**

```bash
cd apps/api && clj -M:test
```

Expected:
```
Running tests in #{"test"}
Testing api.core-test
Ran 1 tests containing 1 assertions.
0 failures, 0 errors.
```

- [ ] **Step 5: Smoke-test the server manually**

```bash
cd apps/api && clj -M:run &
curl http://localhost:3000/health
```

Expected: `{"status":"ok"}`. Stop server: `kill %1`.

- [ ] **Step 6: Commit**

```bash
cd apps/api
git add src/api/core.clj test/api/core_test.clj
git commit -m "feat: add Ring+Reitit server with /health endpoint"
```

---

### Task 5: MongoDB connection

**Files:**
- Create: `apps/api/src/api/db/core.clj`
- Create: `apps/api/test/api/db/core_test.clj`

- [ ] **Step 1: Ensure MongoDB is running locally**

```bash
mongod --version
# If not installed: brew install mongodb-community
brew services start mongodb-community
```

- [ ] **Step 2: Write failing test**

Create `apps/api/test/api/db/core_test.clj`:

```clojure
(ns api.db.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [api.db.core :as db]))

(deftest connection-test
  (testing "connects to MongoDB and returns a db instance"
    (db/connect!)
    (is (some? (db/get-db)))))
```

- [ ] **Step 3: Run test — verify it fails**

```bash
cd apps/api && clj -M:test
```

Expected: `namespace not found: api.db.core`.

- [ ] **Step 4: Implement `apps/api/src/api/db/core.clj`**

```clojure
(ns api.db.core
  (:require [monger.core :as mg]
            [aero.core :refer [read-config]]
            [clojure.java.io :as io]))

(defonce ^:private connection (atom nil))
(defonce ^:private database   (atom nil))

(defn connect! []
  (let [uri              (get-in (read-config (io/resource "config.edn")) [:db :uri])
        {:keys [conn db]} (mg/connect-via-uri uri)]
    (reset! connection conn)
    (reset! database db)))

(defn get-db [] @database)
```

- [ ] **Step 5: Run test — verify it passes**

```bash
cd apps/api && clj -M:test
```

Expected:
```
Ran 2 tests containing 2 assertions.
0 failures, 0 errors.
```

- [ ] **Step 6: Wire DB connect into server startup — update `apps/api/src/api/core.clj`**

Add the require and call `connect!` in `-main`:

```clojure
(ns api.core
  (:require [ring.adapter.jetty :as jetty]
            [reitit.ring :as ring]
            [muuntaja.core :as m]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.middleware.session :refer [wrap-session]]
            [aero.core :refer [read-config]]
            [clojure.java.io :as io]
            [api.db.core :as db])
  (:gen-class))

(def config
  (delay (read-config (io/resource "config.edn"))))

(def routes
  [["/health" {:get (fn [_] {:status 200 :body {:status "ok"}})}]])

(def app
  (-> (ring/ring-handler
       (ring/router routes
                    {:data {:muuntaja   m/instance
                            :middleware [muuntaja/format-middleware]}})
       (ring/create-default-handler))
      (wrap-session)
      (wrap-cors
       :access-control-allow-origin  [#"http://localhost:5173"]
       :access-control-allow-methods [:get :post :put :delete :options]
       :access-control-allow-headers ["Authorization" "Content-Type"])))

(defn -main [& _]
  (db/connect!)
  (let [port (get-in @config [:server :port])]
    (println (str "Server running on port " port))
    (jetty/run-jetty #'app {:port port :join? true})))
```

- [ ] **Step 7: Commit**

```bash
cd apps/api
git add src/api/db/core.clj test/api/db/core_test.clj src/api/core.clj
git commit -m "feat: add MongoDB connection with monger"
```

---

## Phase 3 — Auth system (signup + login)

### Task 6: User signup endpoint (TDD)

**Files:**
- Create: `apps/api/src/api/auth/handlers.clj`
- Create: `apps/api/test/api/auth/handlers_test.clj`

- [ ] **Step 1: Write failing tests**

Create `apps/api/test/api/auth/handlers_test.clj`:

```clojure
(ns api.auth.handlers-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ring.mock.request :as mock]
            [cheshire.core :as json]
            [api.core :refer [app]]
            [api.db.core :as db]
            [monger.collection :as mc]))

(defn db-fixture [f]
  (db/connect!)
  (mc/drop (db/get-db) "users")
  (f)
  (mc/drop (db/get-db) "users"))

(use-fixtures :each db-fixture)

(defn json-request [method path body]
  (-> (mock/request method path)
      (mock/content-type "application/json")
      (mock/header "accept" "application/json")
      (mock/body (json/generate-string body))))

(defn parse-body [response]
  (-> response :body slurp (json/parse-string true)))

(deftest signup-success-test
  (testing "POST /auth/signup creates a user and returns 201"
    (let [response (app (json-request :post "/auth/signup"
                                      {:name "Gabriel" :login "gspecian"
                                       :email "g@example.com" :password "secret123"}))]
      (is (= 201 (:status response)))
      (is (= "gspecian" (:login (parse-body response)))))))

(deftest signup-duplicate-email-test
  (testing "POST /auth/signup with duplicate email returns 409"
    (app (json-request :post "/auth/signup"
                       {:name "Gabriel" :login "gspecian"
                        :email "g@example.com" :password "secret123"}))
    (let [response (app (json-request :post "/auth/signup"
                                      {:name "Other" :login "other"
                                       :email "g@example.com" :password "pass"}))]
      (is (= 409 (:status response))))))

(deftest signup-missing-fields-test
  (testing "POST /auth/signup missing required fields returns 400"
    (let [response (app (json-request :post "/auth/signup"
                                      {:name "Gabriel"}))]
      (is (= 400 (:status response))))))
```

- [ ] **Step 2: Add `cheshire` to test deps in `apps/api/deps.edn`**

Update the `:test` alias `extra-deps`:

```clojure
:test {:extra-paths ["test"]
       :extra-deps  {io.github.cognitect-labs/test-runner
                     {:git/tag "v0.5.1" :git/sha "dfb30dd"}
                     ring/ring-mock    {:mvn/version "1.0.0"}
                     cheshire/cheshire {:mvn/version "5.13.0"}}
       :main-opts   ["-m" "cognitect.test-runner" "-d" "test"]}
```

- [ ] **Step 3: Run tests — verify they fail**

```bash
cd apps/api && clj -M:test
```

Expected: tests fail with `namespace not found: api.auth.handlers`.

- [ ] **Step 4: Implement `apps/api/src/api/auth/handlers.clj`**

```clojure
(ns api.auth.handlers
  (:require [buddy.hashers :as hashers]
            [monger.collection :as mc]
            [monger.operators :refer [$set]]
            [api.db.core :as db])
  (:import [org.bson.types ObjectId]
           [com.mongodb MongoException]))

(defn- validate-signup [{:keys [name login email password]}]
  (cond
    (not (seq name))     "name is required"
    (not (seq login))    "login is required"
    (not (seq email))    "email is required"
    (not (seq password)) "password is required"
    (< (count password) 6) "password must be at least 6 characters"
    :else nil))

(defn signup-handler [{:keys [body-params]}]
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
          (mc/insert (db/get-db) "users" user)
          {:status 201
           :body   {:id    (str (:_id user))
                    :name  name
                    :login login
                    :email email}}
          (catch com.mongodb.MongoWriteException e
            {:status 409 :body {:error "email or login already taken"}}))))))
```

- [ ] **Step 5: Register the route — update `apps/api/src/api/core.clj`**

Add the require and signup route:

```clojure
(ns api.core
  (:require [ring.adapter.jetty :as jetty]
            [reitit.ring :as ring]
            [muuntaja.core :as m]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.middleware.session :refer [wrap-session]]
            [aero.core :refer [read-config]]
            [clojure.java.io :as io]
            [api.db.core :as db]
            [api.auth.handlers :as auth])
  (:gen-class))

(def config
  (delay (read-config (io/resource "config.edn"))))

(def routes
  [["/health"       {:get (fn [_] {:status 200 :body {:status "ok"}})}]
   ["/auth/signup"  {:post auth/signup-handler}]])

(def app
  (-> (ring/ring-handler
       (ring/router routes
                    {:data {:muuntaja   m/instance
                            :middleware [muuntaja/format-middleware]}})
       (ring/create-default-handler))
      (wrap-session)
      (wrap-cors
       :access-control-allow-origin  [#"http://localhost:5173"]
       :access-control-allow-methods [:get :post :put :delete :options]
       :access-control-allow-headers ["Authorization" "Content-Type"])))

(defn -main [& _]
  (db/connect!)
  (let [port (get-in @config [:server :port])]
    (println (str "Server running on port " port))
    (jetty/run-jetty #'app {:port port :join? true})))
```

- [ ] **Step 6: Run tests — verify they pass**

```bash
cd apps/api && clj -M:test
```

Expected:
```
Ran 5 tests containing 5 assertions.
0 failures, 0 errors.
```

- [ ] **Step 7: Commit**

```bash
cd apps/api
git add src/api/auth/handlers.clj test/api/auth/handlers_test.clj src/api/core.clj deps.edn
git commit -m "feat: add /auth/signup endpoint with bcrypt and duplicate detection"
```

---

### Task 7: Login endpoint (TDD)

**Files:**
- Modify: `apps/api/src/api/auth/handlers.clj`
- Modify: `apps/api/test/api/auth/handlers_test.clj`
- Create: `apps/api/resources/templates/login.html`

- [ ] **Step 1: Add login tests to `apps/api/test/api/auth/handlers_test.clj`**

Append to the existing file:

```clojure
(deftest login-page-test
  (testing "GET /auth/login returns HTML login page"
    (let [response (app (mock/request :get "/auth/login"))]
      (is (= 200 (:status response)))
      (is (= "text/html" (get-in response [:headers "Content-Type"]))))))

(deftest login-success-test
  (testing "POST /auth/login with valid credentials returns redirect to callback"
    ;; First create a user
    (app (json-request :post "/auth/signup"
                       {:name "Gabriel" :login "gspecian"
                        :email "g@example.com" :password "secret123"}))
    ;; Simulate authorize session by setting session data manually
    (let [session-data {:oauth/client_id "react-app"
                        :oauth/redirect_uri "http://localhost:5173/callback"
                        :oauth/code_challenge "abc123"
                        :oauth/state "xyz"}
          request      (-> (mock/request :post "/auth/login")
                           (mock/content-type "application/x-www-form-urlencoded")
                           (mock/body "email=g%40example.com&password=secret123")
                           (assoc :session session-data))
          response     (app request)]
      (is (= 302 (:status response))))))

(deftest login-wrong-password-test
  (testing "POST /auth/login with wrong password returns 401"
    (app (json-request :post "/auth/signup"
                       {:name "Gabriel" :login "gspecian"
                        :email "g@example.com" :password "secret123"}))
    (let [response (app (-> (mock/request :post "/auth/login")
                            (mock/content-type "application/x-www-form-urlencoded")
                            (mock/body "email=g%40example.com&password=wrong")))]
      (is (= 401 (:status response))))))
```

- [ ] **Step 2: Run tests — verify new tests fail**

```bash
cd apps/api && clj -M:test
```

Expected: 3 new failures (login handlers not implemented).

- [ ] **Step 3: Create `apps/api/resources/templates/login.html`**

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Sign in — TodoApp</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { font-family: system-ui, sans-serif; background: #0f172a; color: #e2e8f0; display: flex; align-items: center; justify-content: center; min-height: 100vh; }
    .card { background: #1e293b; border-radius: 12px; padding: 32px; width: 100%; max-width: 380px; }
    h1 { font-size: 22px; font-weight: 700; margin-bottom: 4px; }
    p { font-size: 14px; color: #94a3b8; margin-bottom: 24px; }
    p a { color: #818cf8; text-decoration: none; }
    label { display: block; font-size: 13px; color: #94a3b8; margin-bottom: 6px; }
    input { display: block; width: 100%; padding: 10px 12px; background: #0f172a; border: 1px solid #334155; border-radius: 8px; color: #e2e8f0; font-size: 14px; margin-bottom: 16px; }
    button { width: 100%; padding: 11px; background: #6366f1; color: white; border: none; border-radius: 8px; font-size: 15px; font-weight: 600; cursor: pointer; }
    .error { background: #450a0a; color: #fca5a5; border-radius: 8px; padding: 10px 14px; font-size: 13px; margin-bottom: 16px; display: none; }
    .error.visible { display: block; }
  </style>
</head>
<body>
  <div class="card">
    <h1>Sign in</h1>
    <p>Don't have an account? <a href="/signup">Sign up</a></p>
    <div id="error-box" class="error"></div>
    <form method="POST" action="/auth/login">
      <label for="email">Email or username</label>
      <input id="email" name="email" type="text" autocomplete="username" required autofocus>
      <label for="password">Password</label>
      <input id="password" name="password" type="password" autocomplete="current-password" required>
      <button type="submit">Sign in</button>
    </form>
  </div>
</body>
</html>
```

- [ ] **Step 4: Add login handlers to `apps/api/src/api/auth/handlers.clj`**

Add these functions at the bottom of the existing file:

```clojure
(defn login-page-handler [_]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    (slurp (clojure.java.io/resource "templates/login.html"))})

(defn- find-user-by-email-or-login [db-conn identifier]
  (or (mc/find-one-as-map db-conn "users" {:email identifier})
      (mc/find-one-as-map db-conn "users" {:login identifier})))

(defn- generate-auth-code []
  (let [bytes (byte-array 32)]
    (.nextBytes (java.security.SecureRandom.) bytes)
    (.encodeToString (java.util.Base64/getUrlEncoder) bytes)))

(defn login-handler [{:keys [form-params session]}]
  (let [identifier (get form-params "email")
        password   (get form-params "password")
        user       (find-user-by-email-or-login (db/get-db) identifier)]
    (cond
      (nil? user)
      {:status 401 :body {:error "invalid credentials"}}

      (not (hashers/check password (:password user)))
      {:status 401 :body {:error "invalid credentials"}}

      (nil? (:oauth/client_id session))
      {:status 400 :body {:error "no active authorization request"}}

      :else
      (let [code      (generate-auth-code)
            auth-code {:_id            (ObjectId.)
                       :code           code
                       :client_id      (:oauth/client_id session)
                       :user_id        (:_id user)
                       :code_challenge (:oauth/code_challenge session)
                       :redirect_uri   (:oauth/redirect_uri session)
                       :expires_at     (java.util.Date. (+ (System/currentTimeMillis) (* 10 60 1000)))
                       :used           false}]
        (mc/insert (db/get-db) "auth_codes" auth-code)
        {:status  302
         :headers {"Location" (str (:oauth/redirect_uri session)
                                   "?code=" code
                                   "&state=" (:oauth/state session))}
         :session nil}))))
```

- [ ] **Step 5: Register login routes in `apps/api/src/api/core.clj`**

Update the `routes` def:

```clojure
(def routes
  [["/health"       {:get (fn [_] {:status 200 :body {:status "ok"}})}]
   ["/auth/signup"  {:post auth/signup-handler}]
   ["/auth/login"   {:get  auth/login-page-handler
                     :post auth/login-handler}]])
```

Also add `ring.middleware.params/wrap-params` to the middleware chain (needed for form parsing):

Add to `:require` in `core.clj`:
```clojure
[ring.middleware.params :refer [wrap-params]]
```

Update `app`:
```clojure
(def app
  (-> (ring/ring-handler
       (ring/router routes
                    {:data {:muuntaja   m/instance
                            :middleware [muuntaja/format-middleware]}})
       (ring/create-default-handler))
      (wrap-params)
      (wrap-session)
      (wrap-cors
       :access-control-allow-origin  [#"http://localhost:5173"]
       :access-control-allow-methods [:get :post :put :delete :options]
       :access-control-allow-headers ["Authorization" "Content-Type"])))
```

- [ ] **Step 6: Run tests — verify all pass**

```bash
cd apps/api && clj -M:test
```

Expected:
```
Ran 8 tests containing 8 assertions.
0 failures, 0 errors.
```

- [ ] **Step 7: Commit**

```bash
cd apps/api
git add src/api/auth/handlers.clj test/api/auth/handlers_test.clj src/api/core.clj resources/templates/login.html
git commit -m "feat: add /auth/login page and credential verification"
```

---

## Phase 4 — OAuth 2.0 server

### Task 8: OAuth authorize + token endpoints (TDD)

**Files:**
- Create: `apps/api/src/api/oauth/handlers.clj`
- Create: `apps/api/test/api/oauth/handlers_test.clj`

- [ ] **Step 1: Seed OAuth client on startup — create `apps/api/src/api/db/seed.clj`**

```clojure
(ns api.db.seed
  (:require [monger.collection :as mc]
            [api.db.core :as db])
  (:import [org.bson.types ObjectId]))

(defn seed-clients! []
  (let [coll "clients"
        existing (mc/find-one-as-map (db/get-db) coll {:client_id "react-app"})]
    (when (nil? existing)
      (mc/insert (db/get-db) coll
                 {:_id           (ObjectId.)
                  :client_id     "react-app"
                  :name          "React Todo App"
                  :redirect_uris ["http://localhost:5173/callback"]
                  :grant_types   ["authorization_code" "refresh_token"]}))))
```

Update `-main` in `apps/api/src/api/core.clj` to call `(seed/seed-clients!)`:

```clojure
;; Add to :require
[api.db.seed :as seed]

;; In -main, after (db/connect!):
(seed/seed-clients!)
```

- [ ] **Step 2: Write failing tests**

Create `apps/api/test/api/oauth/handlers_test.clj`:

```clojure
(ns api.oauth.handlers-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ring.mock.request :as mock]
            [cheshire.core :as json]
            [api.core :refer [app]]
            [api.db.core :as db]
            [monger.collection :as mc])
  (:import [org.bson.types ObjectId]))

(defn db-fixture [f]
  (db/connect!)
  (doseq [coll ["users" "auth_codes" "refresh_tokens"]]
    (mc/drop (db/get-db) coll))
  (mc/insert (db/get-db) "clients"
             {:_id           (ObjectId.)
              :client_id     "react-app"
              :redirect_uris ["http://localhost:5173/callback"]
              :grant_types   ["authorization_code" "refresh_token"]})
  (f)
  (doseq [coll ["users" "auth_codes" "refresh_tokens" "clients"]]
    (mc/drop (db/get-db) coll)))

(use-fixtures :each db-fixture)

(defn parse-body [response]
  (-> response :body slurp (json/parse-string true)))

(deftest authorize-valid-client-test
  (testing "GET /oauth/authorize with valid client redirects to login"
    (let [response (app (mock/request :get "/oauth/authorize"
                                      {:client_id              "react-app"
                                       :redirect_uri           "http://localhost:5173/callback"
                                       :response_type          "code"
                                       :code_challenge         "abc123"
                                       :code_challenge_method  "S256"
                                       :state                  "xyz"}))]
      (is (= 302 (:status response)))
      (is (clojure.string/includes? (get-in response [:headers "Location"]) "/auth/login")))))

(deftest authorize-invalid-client-test
  (testing "GET /oauth/authorize with unknown client_id returns 400"
    (let [response (app (mock/request :get "/oauth/authorize"
                                      {:client_id     "unknown"
                                       :redirect_uri  "http://localhost:5173/callback"
                                       :response_type "code"}))]
      (is (= 400 (:status response))))))

(deftest token-exchange-test
  (testing "POST /oauth/token exchanges a valid code for tokens"
    ;; Create user and valid auth_code directly in DB
    (let [user-id  (ObjectId.)
          verifier "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
          ;; code_challenge = BASE64URL(SHA256(verifier))
          challenge (let [digest (java.security.MessageDigest/getInstance "SHA-256")
                          bytes  (.digest digest (.getBytes verifier "UTF-8"))]
                      (.encodeToString (java.util.Base64/getUrlEncoder) bytes))]
      (mc/insert (db/get-db) "users"
                 {:_id      user-id :name "G" :login "g"
                  :email    "g@example.com"
                  :password "hash" :created_at (java.util.Date.)})
      (mc/insert (db/get-db) "auth_codes"
                 {:_id            (ObjectId.)
                  :code           "testcode123"
                  :client_id      "react-app"
                  :user_id        user-id
                  :code_challenge challenge
                  :redirect_uri   "http://localhost:5173/callback"
                  :expires_at     (java.util.Date. (+ (System/currentTimeMillis) 600000))
                  :used           false})
      (let [response (app (-> (mock/request :post "/oauth/token")
                              (mock/content-type "application/json")
                              (mock/header "accept" "application/json")
                              (mock/body (json/generate-string
                                          {:grant_type    "authorization_code"
                                           :code          "testcode123"
                                           :code_verifier verifier
                                           :client_id     "react-app"
                                           :redirect_uri  "http://localhost:5173/callback"}))))]
        (is (= 200 (:status response)))
        (is (contains? (parse-body response) :access_token))
        (is (= "Bearer" (:token_type (parse-body response))))))))
```

- [ ] **Step 3: Run tests — verify they fail**

```bash
cd apps/api && clj -M:test
```

Expected: 3 new failures — `api.oauth.handlers` namespace missing.

- [ ] **Step 4: Implement `apps/api/src/api/oauth/handlers.clj`**

```clojure
(ns api.oauth.handlers
  (:require [buddy.sign.jwt :as jwt]
            [monger.collection :as mc]
            [monger.operators :refer [$set]]
            [api.db.core :as db]
            [aero.core :refer [read-config]]
            [clojure.java.io :as io])
  (:import [org.bson.types ObjectId]
           [java.util Date]))

(def ^:private config
  (delay (read-config (io/resource "config.edn"))))

(defn- jwt-secret [] (get-in @config [:jwt :secret]))

(defn- find-client [client-id]
  (mc/find-one-as-map (db/get-db) "clients" {:client_id client-id}))

(defn- valid-redirect? [client redirect-uri]
  (some #(= % redirect-uri) (:redirect_uris client)))

(defn authorize-handler [{:keys [params session]}]
  (let [client-id     (get params "client_id")
        redirect-uri  (get params "redirect_uri")
        response-type (get params "response_type")
        challenge     (get params "code_challenge")
        state         (get params "state")
        client        (find-client client-id)]
    (cond
      (nil? client)
      {:status 400 :body {:error "unknown client_id"}}

      (not (valid-redirect? client redirect-uri))
      {:status 400 :body {:error "invalid redirect_uri"}}

      (not= "code" response-type)
      {:status 400 :body {:error "unsupported response_type"}}

      :else
      {:status  302
       :headers {"Location" "/auth/login"}
       :session (assoc session
                       :oauth/client_id      client-id
                       :oauth/redirect_uri   redirect-uri
                       :oauth/code_challenge challenge
                       :oauth/state          state)})))

(defn- sha256-base64url [s]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        bytes  (.digest digest (.getBytes s "UTF-8"))]
    (.encodeToString (java.util.Base64/getUrlEncoder) bytes)))

(defn- generate-token []
  (let [bytes (byte-array 32)]
    (.nextBytes (java.security.SecureRandom.) bytes)
    (.encodeToString (java.util.Base64/getUrlEncoder) bytes)))

(defn- exchange-code [{:keys [code code_verifier client_id redirect_uri]}]
  (let [auth-code (mc/find-one-as-map (db/get-db) "auth_codes" {:code code :used false})]
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
        (mc/update (db/get-db) "auth_codes" {:code code} {$set {:used true}})
        (let [user-id       (:user_id auth-code)
              user          (mc/find-one-as-map (db/get-db) "users" {:_id user-id})
              now           (System/currentTimeMillis)
              access-token  (jwt/sign {:sub      (str user-id)
                                       :login    (:login user)
                                       :email    (:email user)
                                       :exp      (Date. (+ now (* 15 60 1000)))}
                                      (jwt-secret))
              refresh-token (generate-token)]
          (mc/insert (db/get-db) "refresh_tokens"
                     {:_id        (ObjectId.)
                      :token      refresh-token
                      :user_id    user-id
                      :client_id  client_id
                      :expires_at (Date. (+ now (* 7 24 60 60 1000)))
                      :revoked    false})
          {:status  200
           :headers {"Set-Cookie" (str "refresh_token=" refresh-token
                                       "; HttpOnly; SameSite=Strict; Path=/oauth/token")}
           :body    {:access_token access-token
                     :token_type   "Bearer"
                     :expires_in   900}})))))
```

- [ ] **Step 5: Add `token-handler` to `apps/api/src/api/oauth/handlers.clj`**

Append to the file:

```clojure
(defn token-handler [{:keys [body-params] :as request}]
  (case (:grant_type body-params)
    "authorization_code"
    (exchange-code body-params)

    "refresh_token"
    (let [token-val    (get-in (:cookies request) ["refresh_token" :value])
          stored-token (mc/find-one-as-map (db/get-db) "refresh_tokens"
                                           {:token token-val :revoked false})]
      (if (or (nil? stored-token)
              (.before ^Date (:expires_at stored-token) (Date.)))
        {:status 401 :body {:error "invalid refresh token"}}
        (let [user         (mc/find-one-as-map (db/get-db) "users" {:_id (:user_id stored-token)})
              access-token (jwt/sign {:sub   (str (:_id user))
                                      :login (:login user)
                                      :email (:email user)
                                      :exp   (Date. (+ (System/currentTimeMillis) (* 15 60 1000)))}
                                     (jwt-secret))]
          {:status 200
           :body   {:access_token access-token
                    :token_type   "Bearer"
                    :expires_in   900}})))

    {:status 400 :body {:error "unsupported grant_type"}}))
```

- [ ] **Step 6: Add revoke + userinfo handlers**

Append to `apps/api/src/api/oauth/handlers.clj`:

```clojure
(defn revoke-handler [{:keys [body-params cookies]}]
  (let [token (or (:token body-params)
                  (get-in cookies ["refresh_token" :value]))]
    (mc/update (db/get-db) "refresh_tokens" {:token token} {$set {:revoked true}})
    {:status  200
     :headers {"Set-Cookie" "refresh_token=; HttpOnly; SameSite=Strict; Path=/oauth/token; Max-Age=0"}
     :body    {:revoked true}}))

(defn- verify-token [request]
  (let [auth-header (get-in request [:headers "authorization"] "")
        token       (second (clojure.string/split auth-header #" " 2))]
    (when (seq token)
      (try
        (jwt/unsign token (jwt-secret))
        (catch Exception _ nil)))))

(defn userinfo-handler [request]
  (let [claims (verify-token request)]
    (if (nil? claims)
      {:status 401 :body {:error "invalid or missing token"}}
      (let [user (mc/find-one-as-map (db/get-db) "users"
                                     {:_id (ObjectId. (:sub claims))})]
        {:status 200
         :body   {:sub   (:sub claims)
                  :login (:login user)
                  :name  (:name user)
                  :email (:email user)}}))))
```

- [ ] **Step 7: Add `metadata-handler` to `apps/api/src/api/oauth/handlers.clj`**

Append to the file:

```clojure
(defn metadata-handler [_]
  {:status 200
   :body   {:issuer                                "http://localhost:3000"
            :authorization_endpoint                "http://localhost:3000/oauth/authorize"
            :token_endpoint                        "http://localhost:3000/oauth/token"
            :revocation_endpoint                   "http://localhost:3000/oauth/revoke"
            :userinfo_endpoint                     "http://localhost:3000/oauth/userinfo"
            :response_types_supported              ["code"]
            :grant_types_supported                 ["authorization_code" "refresh_token"]
            :code_challenge_methods_supported       ["S256"]}})
```

- [ ] **Step 8: Register OAuth routes in `apps/api/src/api/core.clj`**

Add require and routes:

```clojure
;; Add to :require
[api.oauth.handlers :as oauth]
[ring.middleware.cookies :refer [wrap-cookies]]

;; Update routes def
(def routes
  [["/health"                              {:get (fn [_] {:status 200 :body {:status "ok"}})}]
   ["/.well-known/oauth-authorization-server" {:get oauth/metadata-handler}]
   ["/auth/signup"                         {:post auth/signup-handler}]
   ["/auth/login"                          {:get  auth/login-page-handler
                                            :post auth/login-handler}]
   ["/oauth/authorize"                     {:get  oauth/authorize-handler}]
   ["/oauth/token"                         {:post oauth/token-handler}]
   ["/oauth/revoke"                        {:post oauth/revoke-handler}]
   ["/oauth/userinfo"                      {:get  oauth/userinfo-handler}]])

;; Add wrap-cookies to middleware chain in app:
(def app
  (-> (ring/ring-handler
       (ring/router routes
                    {:data {:muuntaja   m/instance
                            :middleware [muuntaja/format-middleware]}})
       (ring/create-default-handler))
      (wrap-cookies)
      (wrap-params)
      (wrap-session)
      (wrap-cors
       :access-control-allow-origin  [#"http://localhost:5173"]
       :access-control-allow-methods [:get :post :put :delete :options]
       :access-control-allow-headers ["Authorization" "Content-Type"])))
```

- [ ] **Step 9: Run tests — verify all pass**

```bash
cd apps/api && clj -M:test
```

Expected:
```
Ran 11 tests containing 13 assertions.
0 failures, 0 errors.
```

- [ ] **Step 10: Commit**

```bash
cd apps/api
git add src/api/oauth/handlers.clj test/api/oauth/handlers_test.clj src/api/db/seed.clj src/api/core.clj
git commit -m "feat: add OAuth 2.0 authorize, token, revoke, userinfo, and metadata endpoints"
```

---

## Phase 5 — Todo CRUD API

### Task 9: Auth middleware + todo endpoints (TDD)

**Files:**
- Create: `apps/api/src/api/middleware/auth.clj`
- Create: `apps/api/src/api/todos/handlers.clj`
- Create: `apps/api/test/api/todos/handlers_test.clj`

- [ ] **Step 1: Create `apps/api/src/api/middleware/auth.clj`**

```clojure
(ns api.middleware.auth
  (:require [buddy.sign.jwt :as jwt]
            [aero.core :refer [read-config]]
            [clojure.java.io :as io])
  (:import [org.bson.types ObjectId]))

(def ^:private config
  (delay (read-config (io/resource "config.edn"))))

(defn wrap-auth [handler]
  (fn [request]
    (let [auth-header (get-in request [:headers "authorization"] "")
          token       (second (clojure.string/split auth-header #" " 2))]
      (if (empty? token)
        {:status 401 :body {:error "missing authorization header"}}
        (try
          (let [claims (jwt/unsign token (get-in @config [:jwt :secret]))]
            (handler (assoc request :user-id (ObjectId. (:sub claims)))))
          (catch Exception _
            {:status 401 :body {:error "invalid or expired token"}}))))))
```

- [ ] **Step 2: Write failing tests**

Create `apps/api/test/api/todos/handlers_test.clj`:

```clojure
(ns api.todos.handlers-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ring.mock.request :as mock]
            [cheshire.core :as json]
            [buddy.sign.jwt :as jwt]
            [api.core :refer [app]]
            [api.db.core :as db]
            [monger.collection :as mc])
  (:import [org.bson.types ObjectId]
           [java.util Date]))

(def test-user-id (ObjectId.))
(def jwt-secret "dev-secret-do-not-use-in-production")

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
  (-> response :body slurp (json/parse-string true)))

(defn db-fixture [f]
  (db/connect!)
  (mc/drop (db/get-db) "todos")
  (f)
  (mc/drop (db/get-db) "todos"))

(use-fixtures :each db-fixture)

(deftest list-todos-empty-test
  (testing "GET /api/todos returns empty list for new user"
    (let [token    (make-token test-user-id)
          response (app (auth-request :get "/api/todos" token))]
      (is (= 200 (:status response)))
      (is (= [] (:todos (parse-body response)))))))

(deftest create-todo-test
  (testing "POST /api/todos creates and returns a todo"
    (let [token    (make-token test-user-id)
          response (app (auth-request :post "/api/todos" token
                                      {:title    "Buy milk"
                                       :body     "2% please"
                                       :priority "high"
                                       :tags     ["personal"]}))]
      (is (= 201 (:status response)))
      (is (= "Buy milk" (:title (parse-body response))))
      (is (= false (:completed (parse-body response)))))))

(deftest create-todo-missing-title-test
  (testing "POST /api/todos without title returns 400"
    (let [token    (make-token test-user-id)
          response (app (auth-request :post "/api/todos" token {:body "no title"}))]
      (is (= 400 (:status response))))))

(deftest update-todo-test
  (testing "PUT /api/todos/:id updates the todo"
    (let [token         (make-token test-user-id)
          create-resp   (app (auth-request :post "/api/todos" token {:title "Task"}))
          todo-id       (:id (parse-body create-resp))
          update-resp   (app (auth-request :put (str "/api/todos/" todo-id) token
                                           {:title "Task updated" :completed true}))]
      (is (= 200 (:status update-resp)))
      (is (= "Task updated" (:title (parse-body update-resp))))
      (is (= true (:completed (parse-body update-resp)))))))

(deftest delete-todo-test
  (testing "DELETE /api/todos/:id removes the todo"
    (let [token       (make-token test-user-id)
          create-resp (app (auth-request :post "/api/todos" token {:title "Delete me"}))
          todo-id     (:id (parse-body create-resp))
          del-resp    (app (auth-request :delete (str "/api/todos/" todo-id) token))]
      (is (= 204 (:status del-resp))))))

(deftest unauthorized-test
  (testing "GET /api/todos without token returns 401"
    (let [response (app (mock/request :get "/api/todos"))]
      (is (= 401 (:status response))))))
```

- [ ] **Step 3: Run tests — verify they fail**

```bash
cd apps/api && clj -M:test
```

Expected: 6 failures — `api.todos.handlers` missing.

- [ ] **Step 4: Implement `apps/api/src/api/todos/handlers.clj`**

```clojure
(ns api.todos.handlers
  (:require [monger.collection :as mc]
            [monger.operators :refer [$set]]
            [api.db.core :as db])
  (:import [org.bson.types ObjectId]
           [java.util Date]))

(defn- todo->response [todo]
  {:id        (str (:_id todo))
   :title     (:title todo)
   :body      (:body todo "")
   :completed (:completed todo false)
   :priority  (:priority todo "medium")
   :due_date  (some-> (:due_date todo) .toInstant .toString)
   :tags      (:tags todo [])
   :created_at (some-> (:created_at todo) .toInstant .toString)
   :updated_at (some-> (:updated_at todo) .toInstant .toString)})

(defn list-handler [{:keys [user-id]}]
  (let [todos (mc/find-maps (db/get-db) "todos" {:user_id user-id})]
    {:status 200
     :body   {:todos (mapv todo->response todos)}}))

(defn create-handler [{:keys [user-id body-params]}]
  (if (not (seq (:title body-params)))
    {:status 400 :body {:error "title is required"}}
    (let [now  (Date.)
          todo {:_id        (ObjectId.)
                :user_id    user-id
                :title      (:title body-params)
                :body       (:body body-params "")
                :completed  false
                :priority   (:priority body-params "medium")
                :due_date   (some-> (:due_date body-params) java.util.Date.)
                :tags       (vec (:tags body-params []))
                :created_at now
                :updated_at now}]
      (mc/insert (db/get-db) "todos" todo)
      {:status 201
       :body   (todo->response todo)})))

(defn get-handler [{:keys [user-id path-params]}]
  (let [todo (mc/find-one-as-map (db/get-db) "todos"
                                 {:_id     (ObjectId. (:id path-params))
                                  :user_id user-id})]
    (if (nil? todo)
      {:status 404 :body {:error "todo not found"}}
      {:status 200 :body (todo->response todo)})))

(defn update-handler [{:keys [user-id path-params body-params]}]
  (let [id   (ObjectId. (:id path-params))
        todo (mc/find-one-as-map (db/get-db) "todos" {:_id id :user_id user-id})]
    (if (nil? todo)
      {:status 404 :body {:error "todo not found"}}
      (let [updates (cond-> {:updated_at (Date.)}
                      (contains? body-params :title)     (assoc :title (:title body-params))
                      (contains? body-params :body)      (assoc :body (:body body-params))
                      (contains? body-params :completed) (assoc :completed (:completed body-params))
                      (contains? body-params :priority)  (assoc :priority (:priority body-params))
                      (contains? body-params :due_date)  (assoc :due_date (:due_date body-params))
                      (contains? body-params :tags)      (assoc :tags (vec (:tags body-params))))]
        (mc/update (db/get-db) "todos" {:_id id} {$set updates})
        {:status 200
         :body   (todo->response (merge todo updates))}))))

(defn delete-handler [{:keys [user-id path-params]}]
  (mc/remove (db/get-db) "todos"
             {:_id     (ObjectId. (:id path-params))
              :user_id user-id})
  {:status 204})
```

- [ ] **Step 5: Register todo routes with auth middleware — update `apps/api/src/api/core.clj`**

Add requires:

```clojure
[api.todos.handlers :as todos]
[api.middleware.auth :refer [wrap-auth]]
```

Add todo routes:

```clojure
(def routes
  [["/health"          {:get (fn [_] {:status 200 :body {:status "ok"}})}]
   ["/auth/signup"     {:post auth/signup-handler}]
   ["/auth/login"      {:get  auth/login-page-handler
                        :post auth/login-handler}]
   ["/oauth/authorize" {:get oauth/authorize-handler}]
   ["/oauth/token"     {:post oauth/token-handler}]
   ["/oauth/revoke"    {:post oauth/revoke-handler}]
   ["/oauth/userinfo"  {:get oauth/userinfo-handler}]
   ["/api"
    {:middleware [wrap-auth]}
    ["/todos"
     {:get  todos/list-handler
      :post todos/create-handler}]
    ["/todos/:id"
     {:get    todos/get-handler
      :put    todos/update-handler
      :delete todos/delete-handler}]]])
```

- [ ] **Step 6: Run tests — verify all pass**

```bash
cd apps/api && clj -M:test
```

Expected:
```
Ran 17 tests containing 20 assertions.
0 failures, 0 errors.
```

- [ ] **Step 7: Commit**

```bash
cd apps/api
git add src/api/middleware/auth.clj src/api/todos/handlers.clj test/api/todos/handlers_test.clj src/api/core.clj
git commit -m "feat: add todo CRUD endpoints with JWT auth middleware"
```

---

## Phase 6 — React frontend

### Task 10: App shell — routing, auth context, API client

**Files:**
- Create: `apps/web/src/lib/oauth.ts`
- Create: `apps/web/src/lib/api.ts`
- Create: `apps/web/src/contexts/AuthContext.tsx`
- Create: `apps/web/src/components/ProtectedRoute.tsx`
- Modify: `apps/web/src/main.tsx`
- Modify: `apps/web/src/App.tsx`

- [ ] **Step 1: Create `apps/web/src/lib/oauth.ts` (PKCE helpers)**

```typescript
const API = 'http://localhost:3000'
const CLIENT_ID = 'react-app'
const REDIRECT_URI = `${window.location.origin}/callback`

function base64urlEncode(buffer: ArrayBuffer): string {
  return btoa(String.fromCharCode(...new Uint8Array(buffer)))
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
  const storedState    = sessionStorage.getItem('pkce_state')
  const codeVerifier   = sessionStorage.getItem('pkce_verifier')

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
```

- [ ] **Step 2: Create `apps/web/src/lib/api.ts` (fetch wrapper with auto-refresh)**

```typescript
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
    // Try refresh
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
```

- [ ] **Step 3: Create `apps/web/src/contexts/AuthContext.tsx`**

```tsx
import { createContext, useContext, useState, ReactNode } from 'react'
import { setAccessToken } from '../lib/api'
import { logout as oauthLogout, startOAuthFlow } from '../lib/oauth'

interface AuthContextValue {
  isAuthenticated: boolean
  login:           () => void
  logout:          () => Promise<void>
  setToken:        (token: string) => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [isAuthenticated, setIsAuthenticated] = useState(false)

  function login() {
    startOAuthFlow()
  }

  async function logout() {
    await oauthLogout()
    setAccessToken(null)
    setIsAuthenticated(false)
  }

  function setToken(token: string) {
    setAccessToken(token)
    setIsAuthenticated(true)
  }

  return (
    <AuthContext.Provider value={{ isAuthenticated, login, logout, setToken }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider')
  return ctx
}
```

- [ ] **Step 4: Create `apps/web/src/components/ProtectedRoute.tsx`**

```tsx
import { useAuth } from '../contexts/AuthContext'
import { useEffect } from 'react'

export function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { isAuthenticated, login } = useAuth()

  useEffect(() => {
    if (!isAuthenticated) login()
  }, [isAuthenticated, login])

  if (!isAuthenticated) return null
  return <>{children}</>
}
```

- [ ] **Step 5: Update `apps/web/src/main.tsx`**

```tsx
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthProvider } from './contexts/AuthContext'
import App from './App'
import './index.css'

const queryClient = new QueryClient()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <App />
        </AuthProvider>
      </QueryClientProvider>
    </BrowserRouter>
  </StrictMode>
)
```

- [ ] **Step 6: Update `apps/web/src/App.tsx`**

```tsx
import { Routes, Route, Navigate } from 'react-router-dom'
import { SignupPage }   from './pages/SignupPage'
import { CallbackPage } from './pages/CallbackPage'
import { TodosPage }    from './pages/TodosPage'
import { ProtectedRoute } from './components/ProtectedRoute'

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

- [ ] **Step 7: Commit**

```bash
cd apps/web
git add src/lib/ src/contexts/ src/components/ProtectedRoute.tsx src/main.tsx src/App.tsx
git commit -m "feat: add OAuth client, auth context, and app routing"
```

---

### Task 11: Signup page

**Files:**
- Create: `apps/web/src/pages/SignupPage.tsx`

- [ ] **Step 1: Create `apps/web/src/pages/SignupPage.tsx`**

```tsx
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Button } from '../components/ui/button'
import { Input }  from '../components/ui/input'
import { Label }  from '../components/ui/label'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '../components/ui/card'
import { useNavigate, Link } from 'react-router-dom'
import { useState } from 'react'

const schema = z.object({
  name:     z.string().min(1, 'Name is required'),
  login:    z.string().min(2, 'Username must be at least 2 characters'),
  email:    z.string().email('Invalid email'),
  password: z.string().min(6, 'Password must be at least 6 characters'),
})

type FormData = z.infer<typeof schema>

export function SignupPage() {
  const navigate = useNavigate()
  const [serverError, setServerError] = useState<string | null>(null)

  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<FormData>({
    resolver: zodResolver(schema),
  })

  async function onSubmit(data: FormData) {
    setServerError(null)
    const res = await fetch('http://localhost:3000/auth/signup', {
      method:  'POST',
      headers: { 'Content-Type': 'application/json' },
      body:    JSON.stringify(data),
    })
    if (res.status === 409) {
      setServerError('Email or username already taken')
      return
    }
    if (!res.ok) {
      setServerError('Signup failed. Please try again.')
      return
    }
    navigate('/todos')
  }

  return (
    <div className="min-h-screen bg-slate-950 flex items-center justify-center p-4">
      <Card className="w-full max-w-sm bg-slate-900 border-slate-800">
        <CardHeader>
          <CardTitle className="text-slate-50">Create your account</CardTitle>
          <CardDescription className="text-slate-400">
            Already have one?{' '}
            <Link to="/todos" className="text-indigo-400 hover:underline">
              Sign in
            </Link>
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
```

- [ ] **Step 2: Commit**

```bash
cd apps/web
git add src/pages/SignupPage.tsx
git commit -m "feat: add signup page with Zod validation and ShadCN components"
```

---

### Task 12: OAuth callback page

**Files:**
- Create: `apps/web/src/pages/CallbackPage.tsx`

- [ ] **Step 1: Create `apps/web/src/pages/CallbackPage.tsx`**

```tsx
import { useEffect, useRef } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { exchangeCode } from '../lib/oauth'
import { useAuth } from '../contexts/AuthContext'

export function CallbackPage() {
  const [params]   = useSearchParams()
  const navigate   = useNavigate()
  const { setToken } = useAuth()
  const called     = useRef(false)

  useEffect(() => {
    if (called.current) return
    called.current = true

    const code  = params.get('code')
    const state = params.get('state')

    if (!code || !state) {
      navigate('/signup')
      return
    }

    exchangeCode(code, state)
      .then(token => {
        setToken(token)
        navigate('/todos')
      })
      .catch(() => navigate('/signup'))
  }, [])

  return (
    <div className="min-h-screen bg-slate-950 flex items-center justify-content-center">
      <p className="text-slate-400">Signing you in…</p>
    </div>
  )
}
```

- [ ] **Step 2: Commit**

```bash
cd apps/web
git add src/pages/CallbackPage.tsx
git commit -m "feat: add OAuth callback page with PKCE code exchange"
```

---

### Task 13: Todos page

**Files:**
- Create: `apps/web/src/hooks/useTodos.ts`
- Create: `apps/web/src/pages/TodosPage.tsx`

- [ ] **Step 1: Create `apps/web/src/hooks/useTodos.ts`**

```typescript
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'

export interface Todo {
  id:        string
  title:     string
  body:      string
  completed: boolean
  priority:  'low' | 'medium' | 'high'
  due_date:  string | null
  tags:      string[]
}

interface CreateTodoInput {
  title:    string
  body?:    string
  priority?: 'low' | 'medium' | 'high'
  tags?:    string[]
}

interface UpdateTodoInput extends Partial<CreateTodoInput> {
  completed?: boolean
}

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

  const update = useMutation({
    mutationFn: ({ id, ...input }: UpdateTodoInput & { id: string }) =>
      api.put<Todo>(`/api/todos/${id}`, input),
    onSuccess:  () => queryClient.invalidateQueries({ queryKey: ['todos'] }),
  })

  const remove = useMutation({
    mutationFn: (id: string) => api.delete<void>(`/api/todos/${id}`),
    onSuccess:  () => queryClient.invalidateQueries({ queryKey: ['todos'] }),
  })

  return { todos: data ?? [], isLoading, create, update, remove }
}
```

- [ ] **Step 2: Create `apps/web/src/pages/TodosPage.tsx`**

```tsx
import { useState } from 'react'
import { useAuth } from '../contexts/AuthContext'
import { useTodos, Todo } from '../hooks/useTodos'
import { Button }  from '../components/ui/button'
import { Input }   from '../components/ui/input'
import { Badge }   from '../components/ui/badge'
import { Sheet, SheetContent, SheetTrigger } from '../components/ui/sheet'
import { Menu, Plus, Check, Trash2, X } from 'lucide-react'

const PRIORITY_COLORS: Record<string, string> = {
  high:   'bg-red-900 text-red-300',
  medium: 'bg-yellow-900 text-yellow-300',
  low:    'bg-green-900 text-green-300',
}

function TodoItem({ todo, onToggle, onDelete }: {
  todo:     Todo
  onToggle: (todo: Todo) => void
  onDelete: (id: string) => void
}) {
  return (
    <div className={`flex items-start gap-3 bg-slate-800 rounded-lg p-4 ${todo.completed ? 'opacity-50' : ''}`}>
      <button
        onClick={() => onToggle(todo)}
        className={`mt-0.5 w-5 h-5 rounded flex items-center justify-center border-2 flex-shrink-0 ${
          todo.completed ? 'bg-indigo-600 border-indigo-600' : 'border-slate-500'
        }`}
      >
        {todo.completed && <Check size={12} className="text-white" />}
      </button>
      <div className="flex-1 min-w-0">
        <p className={`font-medium text-slate-100 ${todo.completed ? 'line-through' : ''}`}>
          {todo.title}
        </p>
        {todo.body && <p className="text-sm text-slate-400 mt-0.5 truncate">{todo.body}</p>}
        <div className="flex flex-wrap gap-2 mt-2">
          <span className={`text-xs px-2 py-0.5 rounded-full ${PRIORITY_COLORS[todo.priority]}`}>
            {todo.priority}
          </span>
          {todo.tags.map(tag => (
            <span key={tag} className="text-xs px-2 py-0.5 rounded-full bg-indigo-950 text-indigo-300">
              #{tag}
            </span>
          ))}
        </div>
      </div>
      <button onClick={() => onDelete(todo.id)} className="text-slate-500 hover:text-red-400 flex-shrink-0">
        <Trash2 size={16} />
      </button>
    </div>
  )
}

function CreateTodoForm({ onSubmit }: { onSubmit: (title: string) => void }) {
  const [value, setValue] = useState('')
  return (
    <div className="flex gap-2">
      <Input
        value={value}
        onChange={e => setValue(e.target.value)}
        onKeyDown={e => { if (e.key === 'Enter' && value.trim()) { onSubmit(value.trim()); setValue('') } }}
        placeholder="Add a new todo… (press Enter)"
        className="bg-slate-800 border-slate-700 text-slate-100 placeholder:text-slate-500"
      />
      <Button
        onClick={() => { if (value.trim()) { onSubmit(value.trim()); setValue('') } }}
        className="bg-indigo-600 hover:bg-indigo-700 flex-shrink-0"
      >
        <Plus size={16} />
      </Button>
    </div>
  )
}

type Filter = 'all' | 'active' | 'completed'
type Priority = 'all' | 'high' | 'medium' | 'low'

function Sidebar({ filter, setFilter, priority, setPriority }: {
  filter:      Filter
  setFilter:   (f: Filter) => void
  priority:    Priority
  setPriority: (p: Priority) => void
}) {
  return (
    <nav className="space-y-6">
      <div>
        <p className="text-xs text-slate-500 uppercase tracking-widest mb-2">Status</p>
        {(['all', 'active', 'completed'] as Filter[]).map(f => (
          <button
            key={f}
            onClick={() => setFilter(f)}
            className={`block w-full text-left px-3 py-2 rounded-lg text-sm capitalize ${
              filter === f ? 'bg-slate-700 text-slate-100' : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            {f}
          </button>
        ))}
      </div>
      <div>
        <p className="text-xs text-slate-500 uppercase tracking-widest mb-2">Priority</p>
        {(['all', 'high', 'medium', 'low'] as Priority[]).map(p => (
          <button
            key={p}
            onClick={() => setPriority(p)}
            className={`block w-full text-left px-3 py-2 rounded-lg text-sm capitalize ${
              priority === p ? 'bg-slate-700 text-slate-100' : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            {p === 'all' ? 'All priorities' : p}
          </button>
        ))}
      </div>
    </nav>
  )
}

export function TodosPage() {
  const { logout } = useAuth()
  const { todos, isLoading, create, update, remove } = useTodos()
  const [filter,   setFilter]   = useState<Filter>('all')
  const [priority, setPriority] = useState<Priority>('all')
  const [search,   setSearch]   = useState('')

  const filtered = todos.filter(t => {
    if (filter === 'active'    && t.completed)  return false
    if (filter === 'completed' && !t.completed) return false
    if (priority !== 'all' && t.priority !== priority) return false
    if (search && !t.title.toLowerCase().includes(search.toLowerCase())) return false
    return true
  })

  function handleCreate(title: string) {
    create.mutate({ title })
  }

  function handleToggle(todo: Todo) {
    update.mutate({ id: todo.id, completed: !todo.completed })
  }

  function handleDelete(id: string) {
    remove.mutate(id)
  }

  const sidebarContent = (
    <Sidebar filter={filter} setFilter={setFilter} priority={priority} setPriority={setPriority} />
  )

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100">
      {/* Nav */}
      <header className="border-b border-slate-800 px-4 py-3 flex items-center justify-between">
        <div className="flex items-center gap-3">
          {/* Mobile hamburger */}
          <Sheet>
            <SheetTrigger asChild>
              <button className="md:hidden text-slate-400 hover:text-slate-200">
                <Menu size={20} />
              </button>
            </SheetTrigger>
            <SheetContent side="left" className="bg-slate-900 border-slate-800 w-64 p-6">
              {sidebarContent}
            </SheetContent>
          </Sheet>
          <span className="font-bold text-slate-100">✓ TodoApp</span>
        </div>
        <button
          onClick={logout}
          className="text-sm text-slate-400 hover:text-red-400"
        >
          Log out
        </button>
      </header>

      <div className="flex">
        {/* Desktop sidebar */}
        <aside className="hidden md:block w-56 flex-shrink-0 border-r border-slate-800 p-6 min-h-[calc(100vh-57px)]">
          {sidebarContent}
        </aside>

        {/* Main content */}
        <main className="flex-1 p-4 md:p-6 max-w-2xl">
          <div className="space-y-4">
            <Input
              value={search}
              onChange={e => setSearch(e.target.value)}
              placeholder="Search todos…"
              className="bg-slate-800 border-slate-700 text-slate-100 placeholder:text-slate-500"
            />
            <CreateTodoForm onSubmit={handleCreate} />
            {isLoading && <p className="text-slate-400 text-sm">Loading…</p>}
            <div className="space-y-2">
              {filtered.map(todo => (
                <TodoItem key={todo.id} todo={todo} onToggle={handleToggle} onDelete={handleDelete} />
              ))}
              {!isLoading && filtered.length === 0 && (
                <p className="text-slate-500 text-sm text-center py-8">No todos yet. Add one above.</p>
              )}
            </div>
          </div>
        </main>
      </div>
    </div>
  )
}
```

- [ ] **Step 3: Commit**

```bash
cd apps/web
git add src/hooks/useTodos.ts src/pages/TodosPage.tsx
git commit -m "feat: add todos page with CRUD, filtering, and responsive sidebar"
```

---

### Task 14: End-to-end smoke test + turbo dev

- [ ] **Step 1: Start MongoDB (if not running)**

```bash
brew services start mongodb-community
```

- [ ] **Step 2: Start both apps with Turborepo**

```bash
pnpm dev
```

Expected: Turborepo starts both `apps/api` (port 3000) and `apps/web` (port 5173) in parallel.

- [ ] **Step 3: Smoke test the API**

```bash
curl http://localhost:3000/health
```

Expected: `{"status":"ok"}`

- [ ] **Step 4: Smoke test the full auth flow**

1. Open `http://localhost:5173/signup` in a browser
2. Fill in name, username, email, password — submit
3. You should be redirected to `/todos` after the OAuth flow completes
4. Add a todo, mark it complete, delete it

- [ ] **Step 5: Verify responsive layout**

Open browser DevTools → toggle mobile view (e.g. iPhone 14). Verify:
- Sidebar is hidden
- Hamburger icon appears in the nav
- Tapping hamburger opens the Sheet drawer with filters

- [ ] **Step 6: Run all API tests one final time**

```bash
cd apps/api && clj -M:test
```

Expected: all tests pass, 0 failures, 0 errors.

- [ ] **Step 7: Final commit**

```bash
git add -A
git commit -m "feat: complete Clojure todo app MVP — OAuth 2.0 + CRUD + responsive React UI"
```
