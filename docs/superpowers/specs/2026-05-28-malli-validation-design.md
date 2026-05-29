# malli Request Validation — Design Spec

**Date:** 2026-05-28
**Status:** Approved
**Scope:** `apps/api` only.
**Source:** Improvement #2 from `2026-05-28-api-architecture-review.md`.
**Goal:** Replace hand-rolled request validation with declarative malli schemas coerced by reitit, so handlers receive already-valid input. Covers JSON request bodies for signup and todo create/update only.

---

## 1. Problem being solved

Validation today is ad-hoc and scattered:
- `auth/handlers/validate-signup` — a hand-rolled `cond` checking each field.
- `todos/handlers/create-handler` — an inline `(seq (:title ...))` check.

reitit + muuntaja are already wired, and **malli 0.20.1 + reitit-malli 0.10.1 are already on the classpath** (transitively via the `metosin/reitit` bundle), so this adds **zero dependencies**. Declarative `:parameters` schemas let reitit reject invalid bodies with a 400 before the handler runs, removing the boilerplate and centralizing the contract.

---

## 2. Scope

**In scope** — request-body validation for:
- `POST /auth/signup`
- `POST /api/todos`
- `PUT  /api/todos/:id`

**Out of scope (non-goals):**
- Response coercion / `:responses` schemas.
- Path/query/form-param coercion (todo `:id`, `/oauth/*`, `/auth/login`). The OAuth flow is untouched.
- Email *format* validation — `email` stays "non-empty string", matching current behavior. (Easy future enhancement.)
- Any change to the `{"error": "..."}` 400 response contract the web client depends on.
- `due_date` as a *writable* field. It is dropped from the writable schemas/update path (see §3.4). It was previously settable via `PUT`, but storing a raw JSON string makes `todo->response` call `.toInstant` on a `String` — a latent crash the client never triggers (it doesn't send `due_date`). Deferring it until due-date handling is designed properly removes that dormant bug rather than enshrining it.

---

## 3. Components

### 3.1 `api.schemas` (new namespace)
Canonical request schemas. reitit's malli coercion **strips keys not declared in the schema** from the coerced `:parameters :body` (the raw `:body-params` is left untouched). Net behavior matches today — the handlers already build their persisted maps from known keys only — so stripping is harmless and actually cleaner. The one consequence is `due_date`, handled in §3.4.

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

Rationale: `Signup` mirrors `validate-signup` exactly (all four required, password ≥ 6). `NewTodo` requires `title`; `body`/`priority`/`tags` optional with the handler's existing defaults still applied. The `:priority` enum is a small, intentional tightening (review item). `UpdateTodo` is fully partial — the handler's `contains?`-based update logic is preserved.

### 3.2 `api.middleware.coercion` (new namespace) — `wrap-coercion-errors`
Preserves the `{:error <string>}` contract instead of reitit's default structured error body.

```clojure
(ns api.middleware.coercion
  (:require [malli.error :as me]))

(defn- humanize->message [explained]
  ;; turn {:password ["should be at least 6 characters"]} into
  ;; "password: should be at least 6 characters" (first error per first field)
  ...)

(defn wrap-coercion-errors [handler]
  (fn [request]
    (try
      (handler request)
      (catch clojure.lang.ExceptionInfo e
        (if (= :reitit.coercion/request-coercion (:type (ex-data e)))
          {:status 400 :body {:error (humanize->message (ex-data e))}}
          (throw e))))))
```

The exact `me/humanize` call and ex-data keys (`:schema` / `:value` / `:errors`) are verified during implementation; the contract is: request-coercion failure → `{:status 400 :body {:error <human-readable string>}}`, anything else re-thrown.

### 3.3 `api.system` — wire coercion
Router `:data` gains the malli coercion and the middleware stack is extended (order matters: muuntaja decodes the body first, then our error wrapper, then reitit's request coercion):

```clojure
{:data {:muuntaja   m/instance
        :coercion   reitit.coercion.malli/coercion
        :middleware [muuntaja/format-middleware
                     coercion/wrap-coercion-errors
                     reitit.ring.coercion/coerce-request-middleware]}}
```

The three target routes attach schemas (sibling methods stay in their current bare-fn form):

```clojure
["/auth/signup" {:post {:parameters {:body schemas/Signup}
                        :handler auth/signup-handler}}]
...
["/todos"      {:get  todos/list-handler
                :post {:parameters {:body schemas/NewTodo}
                       :handler todos/create-handler}}]
["/todos/:id"  {:get    todos/get-handler
                :put    {:parameters {:body schemas/UpdateTodo}
                         :handler todos/update-handler}
                :delete todos/delete-handler}]
```

### 3.4 Handlers — trust coerced input
- `signup-handler` — delete `validate-signup` and its `(if error ...)` branch; read `(get-in request [:parameters :body])`. Keep the duplicate-key → 409 `try/catch` (a DB concern, not coercion).
- `create-handler` — delete the inline `title is required` check; read the body from `:parameters`. Existing defaults (`(:priority body "medium")`, `(:tags body [])`, etc.) remain.
- `update-handler` — read the body from `:parameters`; keep the `contains?`-based partial-update logic for `title`/`body`/`completed`/`priority`/`tags`. **Drop the `due_date` branch** — it is no longer in the writable schema, so coercion strips it (see §2 non-goals).

---

## 4. Data flow

```
request → muuntaja (decode JSON → :body-params)
        → wrap-coercion-errors (try)
        → coerce-request-middleware (validate :body-params vs schema)
              ├─ valid   → assoc :parameters {:body <coerced>} → handler
              └─ invalid → throw :reitit.coercion/request-coercion
                            → caught by wrap-coercion-errors → 400 {:error ...}
```

---

## 5. Testing

Existing tests that must stay green (they assert only status, not the error message):
- `signup-missing-fields-test` → 400 (now via coercion).
- `create-todo-missing-title-test` → 400 (now via coercion).

New tests (through the injected app + `tododb_test`, per the existing `api.test-helpers`):
- `POST /api/todos` with `priority "urgent"` → 400, body has `:error`.
- `POST /auth/signup` with a 3-char password → 400, body has `:error`.
- `POST /api/todos` valid body (no priority) → 201, defaults applied (`priority "medium"`).
- `PUT /api/todos/:id` with `{:title ""}` → 400 (min-1 enforced on partial update).

---

## 6. Acceptance criteria

- No `validate-signup`; no inline title check in `create-handler`.
- The three routes carry `:parameters {:body …}` schemas defined in `api.schemas`.
- Invalid bodies return `{:status 400 :body {:error <string>}}` (contract preserved); valid bodies behave exactly as before.
- Full suite green; `clj-kondo --lint src` clean. No new dependencies in `deps.edn`.
