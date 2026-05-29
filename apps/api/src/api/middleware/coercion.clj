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
