# Centralized Error Handling — Design Spec

**Date:** 2026-05-29
**Status:** Approved
**Scope:** `apps/api` only.
**Source:** Improvement #3 from `2026-05-28-api-architecture-review.md`.
**Goal:** Introduce one centralized error boundary so unguarded exceptions become clean JSON responses instead of raw 500s, using reitit's standard exception middleware. Consolidates the bespoke `wrap-coercion-errors` (from item #2) into this single mechanism.

---

## 1. Problem being solved

- An unguarded `(ObjectId. (:id path-params))` in the todos `get`/`update`/`delete` handlers throws `IllegalArgumentException` on a malformed id, which currently escapes as a raw 500 (or an uncaught throw in tests).
- Error handling is split: coercion failures go through the custom `api.middleware.coercion/wrap-coercion-errors`, while everything else is unhandled. There is no catch-all, so any unexpected exception leaks as a 500 with no controlled body.

The fix is reitit's `reitit.ring.middleware.exception/create-exception-middleware`, which maps exception classes / `ex-info` `:type`s to response handlers in one place. A spike confirmed it can handle coercion errors, `IllegalArgumentException`, and a catch-all together when placed outermost in the route middleware stack.

---

## 2. Decision: consolidate

Replace the bespoke `wrap-coercion-errors` with the standard reitit exception middleware. Rationale: one idiomatic error boundary instead of two mechanisms; the coercion `{:error …}` behavior is preserved by reusing the same humanizer inside the new middleware's coercion handler.

---

## 3. Components

### 3.1 `api.middleware.errors` (replaces `api.middleware.coercion`)
Holds the humanizer (moved verbatim from the coercion ns) and a configured exception middleware.

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

- `coercion-error` reproduces item #2's `{:error <humanized string>}` 400.
- `bad-argument` maps the malformed-`ObjectId` `IllegalArgumentException` to a generic 400 (no Mongo internals leaked). 400 (not 404) because it is a malformed client argument.
- `internal-error` logs the throwable to stderr (so failures aren't silent — no logging *dependency* added; that remains a separate roadmap item) and returns a clean 500.
- The old `api.middleware.coercion` namespace and its test are deleted; `wrap-coercion-errors` no longer exists.

### 3.2 `api.system` — middleware stack
The exception middleware goes **outermost** in the router `:data` middleware (so it wraps, and therefore catches, the coercion middleware that follows it):

```clojure
:middleware [muuntaja/format-middleware
             errors/exception-middleware
             rrc/coerce-request-middleware]
```

Require changes: drop `[api.middleware.coercion :refer [wrap-coercion-errors]]`; add `[api.middleware.errors :as errors]`. `reitit.coercion.malli` and `rrc` requires stay.

### 3.3 Handlers — unchanged
No handler edits. The todos `(ObjectId. (:id path-params))` calls now surface as a clean 400 via the central boundary. The signup duplicate-key `try/catch` **stays local** — `MongoWriteException` is too broad to map to 409 globally (it would mislabel unrelated write failures), and the "email or login already taken" message is meaningful only at that call site.

---

## 4. Data flow

```
request → muuntaja
        → exception-middleware (try)
            → coerce-request-middleware → handler
                 ├─ coercion fails   → throw :reitit.coercion/request-coercion → coercion-error  → 400
                 ├─ bad ObjectId     → throw IllegalArgumentException           → bad-argument    → 400
                 ├─ unexpected throw → ::exception/default                      → internal-error  → 500 (logged)
                 └─ ok               → normal response
```

---

## 5. Testing

`test/api/middleware/errors_test.clj` (replaces `coercion_test.clj`) — a tiny reitit app wired with `errors/exception-middleware` + `coerce-request-middleware` and three routes:
- a `:parameters {:body …}` route posted an invalid body → 400, `:error` is a string.
- a route whose handler does `(ObjectId. "not-hex")` → 400, body `{:error "invalid request parameter"}`.
- a route whose handler throws `RuntimeException` → 500, body `{:error "internal server error"}`.

Integration (todos suite): `GET /api/todos/not-a-valid-id` with a valid token → 400 (previously an uncaught throw / 500).

Existing signup/todos coercion tests continue to pass unchanged (same `{:error …}` 400 contract, now produced by the consolidated middleware).

---

## 6. Non-goals

- Centralizing duplicate-key/409 handling (kept local to signup — see §3.3).
- Adding a logging framework (`clojure.tools.logging`/SLF4J) — stderr `println` + stack trace is the minimal stand-in.
- Mapping additional exception types (e.g. auth, Mongo connectivity) — only coercion, `IllegalArgumentException`, and the catch-all are in scope.

---

## 7. Acceptance criteria

- `api.middleware.coercion` and `wrap-coercion-errors` are gone; `api.middleware.errors/exception-middleware` is the sole error boundary.
- Malformed todo `:id` → 400 `{:error "invalid request parameter"}` (not 500/throw).
- Coercion failures still return `400 {:error <string>}` (contract unchanged).
- An unexpected exception returns `500 {:error "internal server error"}` and is logged to stderr.
- Full suite green; `clj-kondo --lint src` clean; no new dependency in `deps.edn`.
