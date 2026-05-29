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
