(ns api.auth.handlers
  (:require [buddy.hashers :as hashers]
            [monger.collection :as mc]
            [api.db.core :as db])
  (:import [org.bson.types ObjectId]))

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
          (catch com.mongodb.DuplicateKeyException e
            {:status 409 :body {:error "email or login already taken"}})
          (catch com.mongodb.MongoWriteException e
            {:status 409 :body {:error "email or login already taken"}}))))))
