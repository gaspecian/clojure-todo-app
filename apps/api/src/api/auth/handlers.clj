(ns api.auth.handlers
  (:require [buddy.hashers :as hashers]
            [monger.collection :as mc]
            [api.db.core :as db])
  (:import [org.bson.types ObjectId]
           [java.security SecureRandom]
           [java.util Base64]))

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

(defn login-page-handler [_]
  {:status  200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    (slurp (clojure.java.io/resource "templates/login.html"))})

(defn- find-user-by-email-or-login [db-conn identifier]
  (or (mc/find-one-as-map db-conn "users" {:email identifier})
      (mc/find-one-as-map db-conn "users" {:login identifier})))

(defn- generate-auth-code []
  (let [bytes (byte-array 32)]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString (Base64/getUrlEncoder) bytes)))

(defn login-handler [{:keys [form-params session]}]
  (let [identifier (get form-params "email")
        password   (get form-params "password")
        user       (find-user-by-email-or-login (db/get-db) identifier)]
    (cond
      (nil? user)
      {:status 401 :body {:error "invalid credentials"}}

      (not (hashers/check password (:password user)))
      {:status 401 :body {:error "invalid credentials"}}

      (nil? (:oauth/client_id session))
      {:status 400 :body {:error "no active authorization request"}}

      :else
      (let [code      (generate-auth-code)
            auth-code {:_id            (ObjectId.)
                       :code           code
                       :client_id      (:oauth/client_id session)
                       :user_id        (:_id user)
                       :code_challenge (:oauth/code_challenge session)
                       :redirect_uri   (:oauth/redirect_uri session)
                       :expires_at     (java.util.Date. (+ (System/currentTimeMillis) (* 10 60 1000)))
                       :used           false}]
        (mc/insert (db/get-db) "auth_codes" auth-code)
        {:status  302
         :headers {"Location" (str (:oauth/redirect_uri session)
                                   "?code=" code
                                   "&state=" (:oauth/state session))}
         :session nil}))))
