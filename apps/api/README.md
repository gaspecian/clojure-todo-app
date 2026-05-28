# Clojure API

This is the backend for the clojure-todo-app — a single Clojure process that plays two roles: an OAuth 2.0 authorization server (`/auth/*`, `/oauth/*`) and a protected todo resource server (`/api/*`). The entry point is `src/api/core.clj`, which assembles the router and middleware stack and starts Jetty.

If you're new to Clojure web development, this document walks through the key patterns used here and explains why they work the way they do.

---

## Ring: HTTP as pure functions

[Ring](https://github.com/ring-clojure/ring) is the foundation of Clojure web development. Its core insight is simple: **an HTTP handler is just a function from a request map to a response map**.

Here's the simplest handler in this project:

```clojure
;; src/api/core.clj
["/health" {:get (fn [_] {:status 200 :body {:status "ok"}})}]
```

The anonymous function ignores the request (`_`) and returns a map with `:status` and `:body`. That's it. No framework magic, no annotations, no class hierarchies — just a function that returns data.

### The request map

Every handler receives a Clojure map representing the HTTP request. The keys you'll use most:

| Key | What it contains |
|-----|-----------------|
| `:headers` | Map of request headers, e.g. `{"authorization" "Bearer eyJ..."}` |
| `:body-params` | Parsed JSON body (added by Muuntaja middleware) |
| `:params` | Query string parameters |
| `:path-params` | URL path segments, e.g. `{:id "abc123"}` for `/todos/:id` |
| `:session` | Server-side session map (used during OAuth login flow) |
| `:cookies` | Parsed cookies map |
| `:user-id` | MongoDB ObjectId of the authenticated user (added by `wrap-auth`) |

### The response map

Handlers return a map. The keys Ring understands:

```clojure
{:status  201
 :headers {"Content-Type" "application/json"}
 :body    {:id "abc" :title "Buy milk"}}
```

Muuntaja middleware serialises the `:body` map to JSON automatically. You never write JSON manually.

---

## Reitit: data-driven routing

[Reitit](https://github.com/metosin/reitit) is the router. In most frameworks, routes are defined via annotations or a DSL. In Reitit, routes are **plain Clojure data** — a vector of `[path option-map]` pairs:

```clojure
;; src/api/core.clj
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
    ["/todos"     {:get  todos/list-handler   :post todos/create-handler}]
    ["/todos/:id" {:get  todos/get-handler    :put  todos/update-handler
                   :delete todos/delete-handler}]]])
```

Because routes are data, you can inspect, test, and transform them with standard Clojure functions. There's nothing special about the route table — `routes` is just a var holding a vector.

### Scoped middleware with nested routes

Notice the `/api` group:

```clojure
["/api"
 {:middleware [wrap-auth]}
 ["/todos"     ...]
 ["/todos/:id" ...]]
```

The `{:middleware [wrap-auth]}` map applies `wrap-auth` to every route nested under `/api`. The public auth and OAuth routes above it are unaffected. This is how you protect a group of routes without touching each handler individually.

---

## Middleware composition

Middleware in Ring is a function that takes a handler and returns a new handler. That's the entire contract. Here's `wrap-auth` from `src/api/middleware/auth.clj`:

```clojure
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

`wrap-auth` returns a new function. That function either short-circuits with `401`, or calls the original `handler` with an enriched request map that includes `:user-id`. The handler never needs to think about token validation — it just reads `:user-id` off the request.

### The full middleware stack

The outer middleware stack wraps the entire router in `core.clj`:

```clojure
(def app
  (-> (ring/ring-handler ...)   ; innermost — the router
      (wrap-cookies)            ; parses Cookie header
      (wrap-params)             ; parses query string + form params
      (wrap-session)            ; manages server-side session
      (wrap-cors ...)))         ; sets CORS headers — outermost
```

The `->` threading macro pipes the router through each middleware function. Reading bottom to top gives you the execution order: a request enters at `wrap-cors`, passes through `wrap-session`, `wrap-params`, `wrap-cookies`, then reaches the router — which applies `wrap-auth` on `/api/*` routes before dispatching to the handler.

---

## EDN config with Aero

Configuration lives in `resources/config.edn`:

```clojure
{:server {:port   #or [#env PORT 3000]}
 :db     {:uri    #or [#env MONGODB_URI "mongodb://localhost:27017/tododb"]}
 :jwt    {:secret #or [#env JWT_SECRET "dev-secret-do-not-use-in-production"]}}
```

[EDN](https://github.com/edn-format/edn) (Extensible Data Notation) is Clojure's native data format — like JSON, but with richer types and support for reader tags like `#or` and `#env`. [Aero](https://github.com/juxt/aero) processes those tags:

- `#env PORT` reads the `PORT` environment variable
- `#or [x y]` returns `x` if it's truthy, otherwise `y`

So `#or [#env PORT 3000]` means "use the `PORT` env var if set, otherwise default to `3000`". The same config file works in development (defaults) and production (env vars override).

Config is loaded using `delay` so it evaluates once on first access, not on every request:

```clojure
;; src/api/core.clj
(def config
  (delay (read-config (io/resource "config.edn"))))

;; later: (get-in @config [:server :port])
```

`@config` dereferences the delay. On first deref, it reads and parses the file; on subsequent derefs, it returns the cached value.

---

## MongoDB with Monger

[Monger](https://github.com/michaelklishin/monger) is the MongoDB client. `src/api/db/core.clj` manages the connection:

```clojure
(defonce ^:private connection (atom nil))
(defonce ^:private database   (atom nil))

(defn connect! []
  (when (nil? @connection)
    (let [uri              (get-in @config [:db :uri])
          {:keys [conn db]} (mg/connect-via-uri uri)]
      (reset! connection conn)
      (reset! database   db)
      (mc/ensure-index db "users" (array-map :email 1) {:unique true})
      (mc/ensure-index db "users" (array-map :login 1) {:unique true}))))

(defn get-db [] @database)
```

`defonce` is important here: it only initialises the var if it doesn't already have a value. During REPL-driven development, reloading the namespace won't disconnect and reconnect — the existing connection is reused.

### Working with documents

Monger maps MongoDB documents to Clojure maps. Here's `list-handler` from `todos/handlers.clj`:

```clojure
(defn list-handler [{:keys [user-id]}]
  (let [todos (mc/find-maps (db/get-db) "todos" {:user_id user-id})]
    {:status 200
     :body   {:todos (mapv todo->response todos)}}))
```

`mc/find-maps` takes a collection name and a query map, and returns a sequence of Clojure maps. The query `{:user_id user-id}` is plain data — no query builder, no ORM.

### The `cond->` pattern for partial updates

`update-handler` uses `cond->` to build an update map that only includes fields the client actually sent:

```clojure
(defn update-handler [{:keys [user-id path-params body-params]}]
  (let [id      (ObjectId. (:id path-params))
        updates (cond-> {:updated_at (Date.)}
                  (contains? body-params :title)     (assoc :title (:title body-params))
                  (contains? body-params :completed) (assoc :completed (:completed body-params))
                  (contains? body-params :priority)  (assoc :priority (:priority body-params)))]
    (mc/update (db/get-db) "todos" {:_id id :user_id user-id} {$set updates})
    {:status 200 :body (todo->response (merge todo updates))}))
```

`cond->` passes the initial map (`{:updated_at ...}`) as the first argument through each `assoc` — but only when the condition on its left is true. The result is a map containing only the fields present in the request body — no accidentally overwriting fields the client didn't send.

---

## Authentication with Buddy

[Buddy](https://github.com/funcool/buddy) provides two things: JWT handling (`buddy-sign`) and password hashing (`buddy-hashers`).

### JWT tokens

Signing a token on successful login:

```clojure
;; src/api/oauth/handlers.clj
(jwt/sign {:sub   (str user-id)
           :login (:login user)
           :email (:email user)
           :exp   (Date. (+ now (* 15 60 1000)))}  ; 15 minutes
          (jwt-secret))
```

Verifying a token in `wrap-auth`:

```clojure
(let [claims (jwt/unsign token (get-in @config [:jwt :secret]))]
  (handler (assoc request :user-id (ObjectId. (:sub claims)))))
```

`jwt/sign` takes a claims map and a secret, returns a signed token string. `jwt/unsign` takes a token string and a secret, returns the claims map — or throws if the signature is invalid or the token is expired. No database lookup required.

### Password hashing

Hashing on signup:

```clojure
;; src/api/auth/handlers.clj
(hashers/derive password)  ; returns a bcrypt hash string
```

Verifying on login:

```clojure
(hashers/check password (:password user))  ; returns true/false
```

`hashers/derive` uses bcrypt by default and includes the salt in the returned string, so you only store one value per user. `hashers/check` extracts the salt, rehashes, and compares — you never handle salts manually.

---

## Running tests

```bash
cd apps/api
clj -M:test
```

The test suite has 17 tests across five namespaces:

| Namespace | What it tests |
|-----------|--------------|
| `api.core-test` | `GET /health` returns 200 |
| `api.db.core-test` | MongoDB connection and `get-db` |
| `api.auth.handlers-test` | Signup (valid, duplicate, missing fields), login page, login success, wrong password |
| `api.oauth.handlers-test` | Authorize endpoint validation, token exchange with PKCE verification |
| `api.todos.handlers-test` | List, create, create without title, update, delete, unauthorized access |

Tests run against a real MongoDB connection — no mocks. Make sure `docker compose up -d` is running before you run the suite. The `MONGODB_URI` environment variable defaults to `mongodb://localhost:27017/tododb` if not set.
