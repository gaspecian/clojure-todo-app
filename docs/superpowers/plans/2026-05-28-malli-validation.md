# malli Request Validation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace hand-rolled request validation in `apps/api` with declarative malli schemas coerced by reitit, for the JSON bodies of `POST /auth/signup`, `POST /api/todos`, and `PUT /api/todos/:id`.

**Architecture:** New `api.schemas` holds the schemas; a `wrap-coercion-errors` middleware converts reitit's request-coercion failures into the existing `{:error <string>}` 400 contract; `api.system` wires malli coercion into the router and attaches `:parameters {:body …}` to the three routes; handlers read the coerced body from `(:parameters request)` and the hand-rolled checks are deleted. Request-only coercion; no new dependencies.

**Tech Stack:** Clojure 1.12, reitit + reitit-malli (already on classpath), malli 0.20.1, muuntaja, cognitect test-runner + ring-mock.

---

## Prerequisites & runner notes

- MongoDB must be running: `docker compose up -d` from the repo root. Tests use database `tododb_test`.
- **Run all `clojure`/test commands from `apps/api/`** (the `deps.edn` lives there) and **append `</dev/null`** (the `clj`/`clojure` REPL otherwise hangs on stdin under this shell). Use the `clojure` launcher, not `clj`.
- Focused test: `clojure -M:test -n <namespace> </dev/null`. Full: `clojure -M:test </dev/null`. Lint (CI gate): `clojure -M:clj-kondo --lint src </dev/null`.

## Verified facts (from REPL spikes)

- A coercion failure throws `clojure.lang.ExceptionInfo` with `(:type (ex-data e))` = `:reitit.coercion/request-coercion`; `ex-data` includes `:schema` and `:value`.
- `(malli.error/humanize (malli.core/explain schema value))` → a map like `{:title ["should be at least 1 character"]}`.
- On success, the coerced body is at `(get-in request [:parameters :body])`; coercion **strips keys not in the schema**; raw `:body-params` is left intact.

## File overview

| File | Change |
|------|--------|
| `src/api/schemas.clj` | **Create** — `Signup`, `NewTodo`, `UpdateTodo`. |
| `src/api/middleware/coercion.clj` | **Create** — `wrap-coercion-errors`. |
| `src/api/system.clj` | Wire `:coercion` + middleware (Task 3); attach `:parameters` to 3 routes (Tasks 4–5). |
| `src/api/auth/handlers.clj` | Remove `validate-signup`; `signup-handler` reads `:parameters` (Task 4). |
| `src/api/todos/handlers.clj` | `create-handler`/`update-handler` read `:parameters`; remove title check; drop `due_date` (Task 5). |
| `test/api/schemas_test.clj` | **Create** (Task 1). |
| `test/api/middleware/coercion_test.clj` | **Create** (Task 2). |
| `test/api/auth/handlers_test.clj` | Add short-password test (Task 4). |
| `test/api/todos/handlers_test.clj` | Add enum/defaults/empty-title tests (Task 5). |

---

### Task 1: `api.schemas`

**Files:**
- Create: `src/api/schemas.clj`
- Test: `test/api/schemas_test.clj`

- [ ] **Step 1: Write the failing test**

`test/api/schemas_test.clj`:
```clojure
(ns api.schemas-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [api.schemas :as schemas]))

(deftest signup-schema-test
  (testing "Signup requires all fields and a password of at least 6 chars"
    (is (m/validate schemas/Signup {:name "G" :login "g" :email "g@e.com" :password "secret1"}))
    (is (not (m/validate schemas/Signup {:name "G" :login "g" :email "g@e.com" :password "short"})))
    (is (not (m/validate schemas/Signup {:name "G"})))))

(deftest new-todo-schema-test
  (testing "NewTodo requires a non-empty title and restricts priority to the enum"
    (is (m/validate schemas/NewTodo {:title "x"}))
    (is (m/validate schemas/NewTodo {:title "x" :priority "high" :tags ["a"]}))
    (is (not (m/validate schemas/NewTodo {:title ""})))
    (is (not (m/validate schemas/NewTodo {:title "x" :priority "urgent"})))))

(deftest update-todo-schema-test
  (testing "UpdateTodo is fully partial but enforces constraints when a field is present"
    (is (m/validate schemas/UpdateTodo {}))
    (is (m/validate schemas/UpdateTodo {:completed true}))
    (is (not (m/validate schemas/UpdateTodo {:title ""})))
    (is (not (m/validate schemas/UpdateTodo {:priority "urgent"})))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clojure -M:test -n api.schemas-test </dev/null`
Expected: FAIL — namespace `api.schemas` not found.

- [ ] **Step 3: Create `src/api/schemas.clj`**

```clojure
(ns api.schemas)

(def Signup
  [:map
   [:name     [:string {:min 1}]]
   [:login    [:string {:min 1}]]
   [:email    [:string {:min 1}]]
   [:password [:string {:min 6}]]])

(def NewTodo
  [:map
   [:title    [:string {:min 1}]]
   [:body     {:optional true} :string]
   [:priority {:optional true} [:enum "low" "medium" "high"]]
   [:tags     {:optional true} [:vector :string]]])

(def UpdateTodo
  [:map
   [:title     {:optional true} [:string {:min 1}]]
   [:body      {:optional true} :string]
   [:completed {:optional true} :boolean]
   [:priority  {:optional true} [:enum "low" "medium" "high"]]
   [:tags      {:optional true} [:vector :string]]])
```

- [ ] **Step 4: Run test to verify it passes**

Run: `clojure -M:test -n api.schemas-test </dev/null`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/api/schemas.clj apps/api/test/api/schemas_test.clj
git commit -m "feat(api): add malli request schemas (Signup, NewTodo, UpdateTodo)"
```

---

### Task 2: `api.middleware.coercion/wrap-coercion-errors`

**Files:**
- Create: `src/api/middleware/coercion.clj`
- Test: `test/api/middleware/coercion_test.clj`

- [ ] **Step 1: Write the failing test**

`test/api/middleware/coercion_test.clj`:
```clojure
(ns api.middleware.coercion-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [api.middleware.coercion :refer [wrap-coercion-errors]]))

(deftest passes-through-success-test
  (testing "non-throwing handler is returned unchanged"
    (let [h (wrap-coercion-errors (fn [_] {:status 200 :body :ok}))]
      (is (= 200 (:status (h {})))))))

(deftest converts-request-coercion-error-test
  (testing "a reitit request-coercion failure becomes a 400 {:error <string>}"
    (let [throwing (fn [_] (throw (ex-info "coercion"
                                           {:type   :reitit.coercion/request-coercion
                                            :schema [:map [:title [:string {:min 1}]]]
                                            :value  {:title ""}})))
          res ((wrap-coercion-errors throwing) {})]
      (is (= 400 (:status res)))
      (is (string? (get-in res [:body :error])))
      (is (str/includes? (get-in res [:body :error]) "title")))))

(deftest rethrows-other-exceptions-test
  (testing "non-coercion exceptions propagate"
    (let [throwing (fn [_] (throw (ex-info "boom" {:type :something-else})))]
      (is (thrown? clojure.lang.ExceptionInfo ((wrap-coercion-errors throwing) {}))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clojure -M:test -n api.middleware.coercion-test </dev/null`
Expected: FAIL — namespace `api.middleware.coercion` not found.

- [ ] **Step 3: Create `src/api/middleware/coercion.clj`**

```clojure
(ns api.middleware.coercion
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

(defn- humanize->message [{:keys [schema value]}]
  (let [humanized (me/humanize (m/explain schema value))]
    (if (map? humanized)
      (->> humanized
           (map (fn [[field msgs]] (str (name field) ": " (first msgs))))
           (str/join "; "))
      (str humanized))))

(defn wrap-coercion-errors [handler]
  (fn [request]
    (try
      (handler request)
      (catch clojure.lang.ExceptionInfo e
        (if (= :reitit.coercion/request-coercion (:type (ex-data e)))
          {:status 400 :body {:error (humanize->message (ex-data e))}}
          (throw e))))))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `clojure -M:test -n api.middleware.coercion-test </dev/null`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/api/middleware/coercion.clj apps/api/test/api/middleware/coercion_test.clj
git commit -m "feat(api): add wrap-coercion-errors preserving the {:error ...} contract"
```

---

### Task 3: Wire malli coercion into `api.system` (no route schemas yet)

This adds the coercion plumbing globally. With no route declaring `:parameters`, coercion is a per-route no-op, so behavior is unchanged and the suite stays green.

**Files:**
- Modify: `src/api/system.clj`

- [ ] **Step 1: Add requires**

In the `ns` form of `src/api/system.clj`, add these to `:require`:
```clojure
            [reitit.coercion.malli]
            [reitit.ring.coercion :as rrc]
            [api.middleware.coercion :refer [wrap-coercion-errors]]
```

- [ ] **Step 2: Extend the router `:data` in `make-app`**

Replace the `(ring/router routes {:data {...}})` data map so it reads:
```clojure
       (ring/router routes
                    {:data {:muuntaja   m/instance
                            :coercion   reitit.coercion.malli/coercion
                            :middleware [muuntaja/format-middleware
                                         wrap-coercion-errors
                                         rrc/coerce-request-middleware]}})
```

- [ ] **Step 3: Run the full suite (behavior unchanged)**

Run: `clojure -M:test </dev/null`
Expected: PASS — all existing tests green (no route declares `:parameters` yet, so coercion does nothing).

- [ ] **Step 4: Commit**

```bash
git add apps/api/src/api/system.clj
git commit -m "feat(api): wire reitit malli coercion middleware into the router"
```

---

### Task 4: Validate `POST /auth/signup`

**Files:**
- Modify: `src/api/system.clj` (signup route)
- Modify: `src/api/auth/handlers.clj`
- Modify: `test/api/auth/handlers_test.clj` (add one test)

- [ ] **Step 1: Add a failing test for the new rule (short password → 400)**

In `test/api/auth/handlers_test.clj`, add:
```clojure
(deftest signup-short-password-test
  (testing "POST /auth/signup with a password under 6 chars returns 400"
    (let [response (app (json-request :post "/auth/signup"
                                      {:name "Gabriel" :login "gspecian"
                                       :email "g@example.com" :password "short"}))]
      (is (= 400 (:status response)))
      (is (string? (:error (parse-body response)))))))
```

- [ ] **Step 2: Run auth tests to verify the new test fails**

Run: `clojure -M:test -n api.auth.handlers-test </dev/null`
Expected: FAIL — currently `validate-signup` returns the 400 but the test also asserts `:error` is a string; more importantly this test will pass only once coercion is the 400 source. (If it already passes because `validate-signup` returns `{:error "password must be at least 6 characters"}`, that's fine — proceed; the point of this task is to move the rule into the schema.)

- [ ] **Step 3: Add the schema to the signup route in `src/api/system.clj`**

Add `[api.schemas :as schemas]` to the `ns` `:require`. Change the signup route from:
```clojure
   ["/auth/signup"                            {:post auth/signup-handler}]
```
to:
```clojure
   ["/auth/signup"                            {:post {:parameters {:body schemas/Signup}
                                                      :handler     auth/signup-handler}}]
```

- [ ] **Step 4: Simplify `signup-handler` and delete `validate-signup` in `src/api/auth/handlers.clj`**

Delete the entire `validate-signup` defn. Replace `signup-handler` with:
```clojure
(defn signup-handler [{:keys [parameters db]}]
  (let [{:keys [name login email password]} (:body parameters)
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
        {:status 409 :body {:error "email or login already taken"}}))))
```

- [ ] **Step 5: Run auth tests to verify they pass**

Run: `clojure -M:test -n api.auth.handlers-test </dev/null`
Expected: PASS — `signup-success-test` (201), `signup-duplicate-email-test` (409), `signup-missing-fields-test` (400 via coercion), `signup-short-password-test` (400 via coercion), and the login tests are unchanged.

- [ ] **Step 6: Run the full suite**

Run: `clojure -M:test </dev/null`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add apps/api/src/api/system.clj apps/api/src/api/auth/handlers.clj apps/api/test/api/auth/handlers_test.clj
git commit -m "refactor(api): validate signup body with malli schema, drop validate-signup"
```

---

### Task 5: Validate `POST /api/todos` and `PUT /api/todos/:id`

**Files:**
- Modify: `src/api/system.clj` (todos routes)
- Modify: `src/api/todos/handlers.clj`
- Modify: `test/api/todos/handlers_test.clj` (add tests)

- [ ] **Step 1: Add failing tests**

In `test/api/todos/handlers_test.clj`, add:
```clojure
(deftest create-todo-invalid-priority-test
  (testing "POST /api/todos with a priority outside the enum returns 400"
    (let [token    (make-token test-user-id)
          response (app (auth-request :post "/api/todos" token
                                      {:title "x" :priority "urgent"}))]
      (is (= 400 (:status response)))
      (is (string? (:error (parse-body response)))))))

(deftest create-todo-defaults-priority-test
  (testing "POST /api/todos without priority defaults to medium"
    (let [token    (make-token test-user-id)
          response (app (auth-request :post "/api/todos" token {:title "no priority"}))
          body     (parse-body response)]
      (is (= 201 (:status response)))
      (is (= "medium" (:priority body))))))

(deftest update-todo-empty-title-test
  (testing "PUT /api/todos/:id with an empty title returns 400"
    (let [token       (make-token test-user-id)
          create-resp (app (auth-request :post "/api/todos" token {:title "Task"}))
          todo-id     (:id (parse-body create-resp))
          update-resp (app (auth-request :put (str "/api/todos/" todo-id) token {:title ""}))]
      (is (= 400 (:status update-resp))))))
```

- [ ] **Step 2: Run todos tests to verify the new ones fail**

Run: `clojure -M:test -n api.todos.handlers-test </dev/null`
Expected: FAIL — without schemas, `priority "urgent"` is accepted (201, not 400) and `{:title ""}` update is accepted (200, not 400).

- [ ] **Step 3: Add schemas to the todos routes in `src/api/system.clj`**

Change the `/api` subtree routes from:
```clojure
    ["/todos"
     {:get  todos/list-handler
      :post todos/create-handler}]
    ["/todos/:id"
     {:get    todos/get-handler
      :put    todos/update-handler
      :delete todos/delete-handler}]
```
to:
```clojure
    ["/todos"
     {:get  todos/list-handler
      :post {:parameters {:body schemas/NewTodo}
             :handler     todos/create-handler}}]
    ["/todos/:id"
     {:get    todos/get-handler
      :put    {:parameters {:body schemas/UpdateTodo}
               :handler     todos/update-handler}
      :delete todos/delete-handler}]
```
(`schemas` is already required from Task 4.)

- [ ] **Step 4: Migrate the handlers in `src/api/todos/handlers.clj`**

Replace `create-handler`, `get-handler`, `update-handler`, `delete-handler` with (note: `create-handler`/`update-handler` now read `(:body parameters)` as `data`; the inline title check and the `due_date` update branch are removed; `get`/`delete` are unchanged except shown for completeness):
```clojure
(defn list-handler [{:keys [user-id db]}]
  (let [todos (mc/find-maps db "todos" {:user_id user-id})]
    {:status 200
     :body   {:todos (mapv todo->response todos)}}))

(defn create-handler [{:keys [user-id db parameters]}]
  (let [data (:body parameters)
        now  (Date.)
        todo {:_id        (ObjectId.)
              :user_id    user-id
              :title      (:title data)
              :body       (:body data "")
              :completed  false
              :priority   (:priority data "medium")
              :due_date   nil
              :tags       (vec (:tags data []))
              :created_at now
              :updated_at now}]
    (mc/insert db "todos" todo)
    {:status 201
     :body   (todo->response todo)}))

(defn get-handler [{:keys [user-id db path-params]}]
  (let [todo (mc/find-one-as-map db "todos"
                                 {:_id     (ObjectId. (:id path-params))
                                  :user_id user-id})]
    (if (nil? todo)
      {:status 404 :body {:error "todo not found"}}
      {:status 200 :body (todo->response todo)})))

(defn update-handler [{:keys [user-id db path-params parameters]}]
  (let [id   (ObjectId. (:id path-params))
        data (:body parameters)
        todo (mc/find-one-as-map db "todos" {:_id id :user_id user-id})]
    (if (nil? todo)
      {:status 404 :body {:error "todo not found"}}
      (let [updates (cond-> {:updated_at (Date.)}
                      (contains? data :title)     (assoc :title (:title data))
                      (contains? data :body)      (assoc :body (:body data))
                      (contains? data :completed) (assoc :completed (:completed data))
                      (contains? data :priority)  (assoc :priority (:priority data))
                      (contains? data :tags)      (assoc :tags (vec (:tags data))))]
        (mc/update db "todos" {:_id id} {$set updates})
        {:status 200
         :body   (todo->response (merge todo updates))}))))

(defn delete-handler [{:keys [user-id db path-params]}]
  (mc/remove db "todos"
             {:_id     (ObjectId. (:id path-params))
              :user_id user-id})
  {:status 204 :body nil})
```
The `ns` form is unchanged (`list-handler` shown only for context — leave it as-is if already identical).

- [ ] **Step 5: Run todos tests to verify they pass**

Run: `clojure -M:test -n api.todos.handlers-test </dev/null`
Expected: PASS — existing 6 tests plus the 3 new ones (invalid priority 400, default priority medium, empty-title update 400).

- [ ] **Step 6: Run the full suite**

Run: `clojure -M:test </dev/null`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add apps/api/src/api/system.clj apps/api/src/api/todos/handlers.clj apps/api/test/api/todos/handlers_test.clj
git commit -m "refactor(api): validate todo create/update bodies with malli schemas"
```

---

### Task 6: Final verification

**Files:** none (verification only).

- [ ] **Step 1: Confirm hand-rolled validation is gone**

Run: `grep -rn "validate-signup\|title is required" apps/api/src`
Expected: no matches.

- [ ] **Step 2: Confirm no new dependency was added**

Run: `git diff --stat HEAD -- apps/api/deps.edn`
Expected: no output (deps.edn unchanged).

- [ ] **Step 3: Run the full suite**

Run (from `apps/api`): `clojure -M:test </dev/null`
Expected: PASS — all tests green.

- [ ] **Step 4: Run the linter (CI gate)**

Run (from `apps/api`): `clojure -M:clj-kondo --lint src </dev/null`
Expected: 0 errors, 0 warnings. Fix any warnings the change introduced (e.g. unused requires), then re-run.

- [ ] **Step 5: Commit any lint fixes**

```bash
git add -A
git commit -m "chore(api): clean up lint warnings after malli validation"
```
(Skip if Step 4 reported nothing.)

---

## Self-review notes

- **Spec coverage:** §3.1 schemas → Task 1; §3.2 `wrap-coercion-errors` → Task 2; §3.3 wiring → Task 3, route `:parameters` → Tasks 4–5; §3.4 handler migration → Tasks 4–5; §2 `due_date` non-goal → Task 5 (branch dropped); §5 tests → Tasks 4–5; §6 acceptance → Task 6.
- **No new deps:** malli + reitit-malli are already transitive via `metosin/reitit`; Task 6 Step 2 asserts `deps.edn` is untouched.
- **Green at every commit:** Task 3 adds coercion plumbing with no route schemas (no-op); each route family (signup, then todos) migrates with its tests in one commit.
- **`due_date`:** intentionally dropped from the writable path (coercion strips it; the old branch would have stored a string that later crashes `todo->response`). Documented in the spec.
- **Naming:** the coerced body is bound to `data` in todo handlers to avoid colliding with the todo's own `:body` field.
