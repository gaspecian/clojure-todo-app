(ns api.auth.handlers
  (:require [buddy.hashers :as hashers]
            [monger.collection :as mc]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [api.db.core :as db]
            [api.oauth.handlers :as oauth])
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
          (catch com.mongodb.DuplicateKeyException _e
            {:status 409 :body {:error "email or login already taken"}})
          (catch com.mongodb.MongoWriteException _e
            {:status 409 :body {:error "email or login already taken"}}))))))

(defn- html-escape [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")))

(defn- render-login-page [{:keys [error client_id redirect_uri code_challenge state]}]
  (-> (slurp (io/resource "templates/login.html"))
      (str/replace "{{error}}"          (html-escape error))
      (str/replace "{{error_class}}"    (if (seq error) " visible" ""))
      (str/replace "{{client_id}}"      (html-escape client_id))
      (str/replace "{{redirect_uri}}"   (html-escape redirect_uri))
      (str/replace "{{code_challenge}}" (html-escape code_challenge))
      (str/replace "{{state}}"          (html-escape state))))

(defn- login-page-response [status fields]
  {:status  status
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    (render-login-page fields)})

(defn login-page-handler [{:keys [params]}]
  (login-page-response 200 {:client_id      (get params "client_id")
                            :redirect_uri   (get params "redirect_uri")
                            :code_challenge (get params "code_challenge")
                            :state          (get params "state")}))

(defn- find-user-by-email-or-login [db-conn identifier]
  (or (mc/find-one-as-map db-conn "users" {:email identifier})
      (mc/find-one-as-map db-conn "users" {:login identifier})))

(defn- generate-auth-code []
  (let [bytes (byte-array 32)]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString (Base64/getUrlEncoder) bytes)))

(defn login-handler [{:keys [form-params]}]
  (let [identifier   (get form-params "email")
        password     (get form-params "password")
        client-id    (get form-params "client_id")
        redirect-uri (get form-params "redirect_uri")
        challenge    (get form-params "code_challenge")
        state        (get form-params "state")
        user         (find-user-by-email-or-login (db/get-db) identifier)
        client       (oauth/find-client client-id)
        fields       {:client_id      client-id
                      :redirect_uri   redirect-uri
                      :code_challenge challenge
                      :state          state}]
    (cond
      (or (nil? user) (not (hashers/check password (:password user))))
      (login-page-response 401 (assoc fields :error "Invalid email or password."))

      ;; Re-validate the (now client-supplied) authorization params so a tampered
      ;; redirect_uri can't be used to exfiltrate the auth code.
      (or (nil? client) (not (oauth/valid-redirect? client redirect-uri)))
      (login-page-response 400 (assoc fields :error "This sign-in session is invalid or expired. Open the app to start again."))

      :else
      (let [code      (generate-auth-code)
            auth-code {:_id            (ObjectId.)
                       :code           code
                       :client_id      client-id
                       :user_id        (:_id user)
                       :code_challenge challenge
                       :redirect_uri   redirect-uri
                       :expires_at     (java.util.Date. (+ (System/currentTimeMillis) (* 10 60 1000)))
                       :used           false}]
        (mc/insert (db/get-db) "auth_codes" auth-code)
        {:status  302
         :headers {"Location" (str redirect-uri "?code=" code "&state=" state)}}))))
