(ns api.oauth.handlers-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ring.mock.request :as mock]
            [cheshire.core :as json]
            [api.core :refer [app]]
            [api.db.core :as db]
            [monger.collection :as mc])
  (:import [org.bson.types ObjectId]))

(defn db-fixture [f]
  (db/connect!)
  (doseq [coll ["users" "auth_codes" "refresh_tokens" "clients"]]
    (mc/drop (db/get-db) coll))
  (mc/insert (db/get-db) "clients"
             {:_id           (ObjectId.)
              :client_id     "react-app"
              :redirect_uris ["http://localhost:5173/callback"]
              :grant_types   ["authorization_code" "refresh_token"]})
  (f)
  (doseq [coll ["users" "auth_codes" "refresh_tokens" "clients"]]
    (mc/drop (db/get-db) coll)))

(use-fixtures :each db-fixture)

(defn parse-body [response]
  (-> response :body slurp (json/parse-string true)))

(deftest authorize-valid-client-test
  (testing "GET /oauth/authorize with valid client redirects to login"
    (let [response (app (mock/request :get "/oauth/authorize"
                                      {:client_id             "react-app"
                                       :redirect_uri          "http://localhost:5173/callback"
                                       :response_type         "code"
                                       :code_challenge        "abc123"
                                       :code_challenge_method "S256"
                                       :state                 "xyz"}))]
      (is (= 302 (:status response)))
      (is (clojure.string/includes?
           (get-in response [:headers "Location"] "")
           "/auth/login")))))

(deftest authorize-invalid-client-test
  (testing "GET /oauth/authorize with unknown client_id returns 400"
    (let [response (app (mock/request :get "/oauth/authorize"
                                      {:client_id     "unknown"
                                       :redirect_uri  "http://localhost:5173/callback"
                                       :response_type "code"}))]
      (is (= 400 (:status response))))))

(deftest token-exchange-test
  (testing "POST /oauth/token exchanges a valid code for tokens"
    (let [user-id  (ObjectId.)
          verifier "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
          challenge (let [digest (java.security.MessageDigest/getInstance "SHA-256")
                          bytes  (.digest digest (.getBytes verifier "UTF-8"))]
                      (.encodeToString (java.util.Base64/getUrlEncoder) bytes))]
      (mc/insert (db/get-db) "users"
                 {:_id      user-id :name "G" :login "g"
                  :email    "g@example.com"
                  :password "hash" :created_at (java.util.Date.)})
      (mc/insert (db/get-db) "auth_codes"
                 {:_id            (ObjectId.)
                  :code           "testcode123"
                  :client_id      "react-app"
                  :user_id        user-id
                  :code_challenge challenge
                  :redirect_uri   "http://localhost:5173/callback"
                  :expires_at     (java.util.Date. (+ (System/currentTimeMillis) 600000))
                  :used           false})
      (let [response (app (-> (mock/request :post "/oauth/token")
                              (mock/content-type "application/json")
                              (mock/header "accept" "application/json")
                              (mock/body (json/generate-string
                                          {:grant_type    "authorization_code"
                                           :code          "testcode123"
                                           :code_verifier verifier
                                           :client_id     "react-app"
                                           :redirect_uri  "http://localhost:5173/callback"}))))]
        (is (= 200 (:status response)))
        (is (contains? (parse-body response) :access_token))
        (is (= "Bearer" (:token_type (parse-body response))))))))
