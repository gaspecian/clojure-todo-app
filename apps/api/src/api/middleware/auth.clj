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
