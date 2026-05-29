# Centralized Error Handling — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the bespoke `wrap-coercion-errors` with a single reitit exception-middleware error boundary in `apps/api`, so coercion failures, malformed `ObjectId`s, and unexpected exceptions all become clean JSON responses.

**Architecture:** New `api.middleware.errors` exposes a `create-exception-middleware` instance mapping `:reitit.coercion/request-coercion` → 400, `IllegalArgumentException` → 400, and `::exception/default` → 500 (logged to stderr). `api.system` puts it outermost in the route middleware stack; the old `api.middleware.coercion` is deleted. Handlers are unchanged.

**Tech Stack:** Clojure 1.12, reitit (`reitit.ring.middleware.exception`, `reitit.coercion.malli`), malli, cognitect test-runner + ring-mock.

---

## Prerequisites & runner notes

- MongoDB running: `docker compose up -d` (repo root). Tests use `tododb_test`.
- **Run all commands from `apps/api/`** and append **`</dev/null`** (the `clj`/`clojure` REPL hangs on stdin otherwise). Use the **`clojure`** launcher, not `clj`. `git commit` commands `cd` to the repo root, so re-`cd apps/api` in each test command.
- Focused test: `clojure -M:test -n <ns> </dev/null`. Full: `clojure -M:test </dev/null`. Lint: `clojure -M:clj-kondo --lint src </dev/null`.

## Verified facts (from REPL spike)

- `reitit.ring.middleware.exception/create-exception-middleware`, configured with a handler map and placed **outermost** in `:data :middleware`, catches: coercion `ex-info` (`:type :reitit.coercion/request-coercion`), `IllegalArgumentException` (thrown by `(ObjectId. "not-hex")`), and any other throwable via `::exception/default`. A valid `ObjectId` passes through to a 200.

## File overview

| File | Change |
|------|--------|
| `src/api/middleware/errors.clj` | **Create** — `exception-middleware` + `humanize->message`. |
| `test/api/middleware/errors_test.clj` | **Create** — isolated mini-app tests. |
| `src/api/system.clj` | Swap middleware stack to `errors/exception-middleware` (Task 2). |
| `src/api/middleware/coercion.clj` | **Delete** (Task 2). |
| `test/api/middleware/coercion_test.clj` | **Delete** (Task 2). |
| `test/api/todos/handlers_test.clj` | Add malformed-id integration test (Task 2). |

---

### Task 1: `api.middleware.errors`

**Files:**
- Create: `src/api/middleware/errors.clj`
- Test: `test/api/middleware/errors_test.clj`

- [ ] **Step 1: Write the failing test**

`test/api/middleware/errors_test.clj`:
```clojure
(ns api.middleware.errors-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [reitit.ring :as ring]
            [reitit.coercion.malli]
            [reitit.ring.coercion :as rrc]
            [api.middleware.errors :as errors])
  (:import [org.bson.types ObjectId]))

(def app
  (ring/ring-handler
   (ring/router
    [["/coerce" {:post {:parameters {:body [:map [:title [:string {:min 1}]]]}
                        :handler    (fn [_] {:status 200 :body :ok})}}]
     ["/bad-id" {:get {:handler (fn [_] {:status 200 :body (str (ObjectId. "not-hex"))})}}]
     ["/boom"   {:get {:handler (fn [_] (throw (RuntimeException. "kaboom")))}}]]
    {:data {:coercion   reitit.coercion.malli/coercion
            :middleware [errors/exception-middleware
                         rrc/coerce-request-middleware]}})))

(deftest coercion-error-test
  (testing "a coercion failure becomes 400 {:error <string>}"
    (let [res (app {:request-method :post :uri "/coerce" :body-params {:title ""}})]
      (is (= 400 (:status res)))
      (is (string? (get-in res [:body :error])))
      (is (str/includes? (get-in res [:body :error]) "title")))))

(deftest bad-argument-test
  (testing "an IllegalArgumentException becomes a generic 400"
    (let [res (app {:request-method :get :uri "/bad-id"})]
      (is (= 400 (:status res)))
      (is (= "invalid request parameter" (get-in res [:body :error]))))))

(deftest internal-error-test
  (testing "an unexpected exception becomes a 500 (stack trace is logged to stderr)"
    (let [res (app {:request-method :get :uri "/boom"})]
      (is (= 500 (:status res)))
      (is (= "internal server error" (get-in res [:body :error]))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clojure -M:test -n api.middleware.errors-test </dev/null`
Expected: FAIL — namespace `api.middleware.errors` not found. (If the runner prints a confusing `-n (No such file)` error, that is the same "namespace failed to load" symptom — it means `api.middleware.errors` is missing, which is expected here.)

- [ ] **Step 3: Create `src/api/middleware/errors.clj`**

```clojure
(ns api.middleware.errors
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [reitit.ring.middleware.exception :as exception]))

(defn- humanize->message [{:keys [schema value]}]
  (let [humanized (me/humanize (m/explain schema value))]
    (if (map? humanized)
      (->> humanized
           (map (fn [[field msgs]] (str (name field) ": " (first msgs))))
           (str/join "; "))
      (str humanized))))

(defn- coercion-error [e _request]
  {:status 400 :body {:error (humanize->message (ex-data e))}})

(defn- bad-argument [_e _request]
  {:status 400 :body {:error "invalid request parameter"}})

(defn- internal-error [^Throwable e _request]
  (.printStackTrace e)                ; full trace to stderr; no logging dependency
  {:status 500 :body {:error "internal server error"}})

(def exception-middleware
  (exception/create-exception-middleware
   (merge exception/default-handlers
          {:reitit.coercion/request-coercion coercion-error
           IllegalArgumentException           bad-argument
           ::exception/default                internal-error})))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `clojure -M:test -n api.middleware.errors-test </dev/null`
Expected: PASS (3 tests). A stack trace from the `RuntimeException` prints to stderr during `internal-error-test` — that is expected.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/api/middleware/errors.clj apps/api/test/api/middleware/errors_test.clj
git commit -m "feat(api): add api.middleware.errors exception boundary"
```

---

### Task 2: Wire it in; delete `api.middleware.coercion`

**Files:**
- Modify: `test/api/todos/handlers_test.clj` (add a malformed-id test)
- Modify: `src/api/system.clj` (swap middleware)
- Delete: `src/api/middleware/coercion.clj`, `test/api/middleware/coercion_test.clj`

- [ ] **Step 1: Add the failing integration test**

In `test/api/todos/handlers_test.clj`, add (next to the other todo tests):
```clojure
(deftest get-todo-invalid-id-test
  (testing "GET /api/todos/:id with a malformed id returns 400 (not a 500)"
    (let [token    (make-token test-user-id)
          response (app (auth-request :get "/api/todos/not-a-valid-id" token))]
      (is (= 400 (:status response))))))
```

- [ ] **Step 2: Run it to verify it fails**

Run: `clojure -M:test -n api.todos.handlers-test </dev/null`
Expected: FAIL/ERROR — the current `wrap-coercion-errors` only catches coercion `ex-info`, so `(ObjectId. "not-a-valid-id")` throws `IllegalArgumentException`, which escapes `(app …)` as an uncaught exception (an error, not a 400).

- [ ] **Step 3: Swap the middleware stack in `src/api/system.clj`**

In the `ns` `:require`, **remove**:
```clojure
            [api.middleware.coercion :refer [wrap-coercion-errors]]
```
and **add**:
```clojure
            [api.middleware.errors :as errors]
```
Then change the router `:data` middleware vector from:
```clojure
                            :middleware [muuntaja/format-middleware
                                         wrap-coercion-errors
                                         rrc/coerce-request-middleware]}})
```
to:
```clojure
                            :middleware [muuntaja/format-middleware
                                         errors/exception-middleware
                                         rrc/coerce-request-middleware]}})
```

- [ ] **Step 4: Delete the obsolete coercion namespace and its test**

```bash
git rm apps/api/src/api/middleware/coercion.clj apps/api/test/api/middleware/coercion_test.clj
```

- [ ] **Step 5: Run the todos suite to verify the new test passes**

Run: `clojure -M:test -n api.todos.handlers-test </dev/null`
Expected: PASS — `get-todo-invalid-id-test` now returns 400 via the central boundary; the other todo tests (including coercion-driven 400s) still pass.

- [ ] **Step 6: Run the full suite**

Run: `clojure -M:test </dev/null`
Expected: PASS — all tests green. Signup/todos coercion 400s now flow through `errors/exception-middleware` with the same `{:error …}` body.

- [ ] **Step 7: Commit**

```bash
git add apps/api/src/api/system.clj apps/api/test/api/todos/handlers_test.clj
git commit -m "refactor(api): centralize error handling via errors/exception-middleware"
```
(The `git rm` from Step 4 is already staged and included in this commit.)

---

### Task 3: Final verification

**Files:** none (verification only).

- [ ] **Step 1: Confirm the old coercion middleware is gone**

Run: `grep -rn "wrap-coercion-errors\|middleware.coercion\|middleware/coercion" apps/api/src apps/api/test`
Expected: no matches.

- [ ] **Step 2: Confirm no new dependency**

Run: `git -C /home/gspecian/Projetos/clojure-todo-app diff --stat HEAD -- apps/api/deps.edn`
Expected: no output.

- [ ] **Step 3: Run the full suite**

Run (from `apps/api`): `clojure -M:test </dev/null`
Expected: PASS.

- [ ] **Step 4: Run the linter (CI gate)**

Run (from `apps/api`): `clojure -M:clj-kondo --lint src </dev/null`
Expected: 0 errors, 0 warnings. Fix any warnings introduced (e.g. unused require), then re-run.

- [ ] **Step 5: Commit any lint fixes**

```bash
git add -A
git commit -m "chore(api): clean up lint after error-handling consolidation"
```
(Skip if Step 4 reported nothing.)

---

## Self-review notes

- **Spec coverage:** §3.1 `api.middleware.errors` → Task 1; §3.2 stack swap → Task 2; §3.3 handlers unchanged (no task needed); §5 tests → Task 1 (isolated) + Task 2 (integration); §7 acceptance → Task 3.
- **Consolidation:** Task 2 deletes `api.middleware.coercion` and its test in the same commit as the swap, so nothing references a missing namespace at any commit boundary.
- **Green at every commit:** Task 1 adds the errors ns tested in isolation (system untouched); Task 2 is red→green on the malformed-id test and keeps the full suite passing.
- **No new deps:** `reitit.ring.middleware.exception` ships in `reitit-middleware`, already on the classpath; Task 3 Step 2 asserts `deps.edn` is unchanged.
- **Non-goals honored:** signup's duplicate-key `try/catch` is not touched; no logging framework added (stderr stack trace only).
