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
        (let [claims (try
                       (jwt/unsign token secret)
                       (catch Exception _ nil))]
          (if (nil? claims)
            {:status 401 :body {:error "invalid or expired token"}}
            (handler (assoc request :user-id (ObjectId. (:sub claims))))))))))
