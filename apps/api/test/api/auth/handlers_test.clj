(ns api.auth.handlers-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ring.mock.request :as mock]
            [cheshire.core :as json]
            [api.test-helpers :as helpers]
            [api.auth.handlers :refer [login-handler]]
            [monger.collection :as mc])
  (:import [org.bson.types ObjectId]))

(def system (helpers/make-test-system))
(def app (:app system))
(def test-db (:db system))

(defn db-fixture [f]
  (doseq [coll ["users" "auth_codes" "clients"]] (mc/drop test-db coll))
  ;; dropping "users" drops its unique indexes too — re-create them so the
  ;; duplicate-email test still gets a 409.
  (mc/ensure-index test-db "users" (array-map :email 1) {:unique true})
  (mc/ensure-index test-db "users" (array-map :login 1) {:unique true})
  (mc/insert test-db "clients"
             {:_id           (ObjectId.)
              :client_id     "react-app"
              :redirect_uris ["http://localhost:5173/callback"]})
  (f)
  (doseq [coll ["users" "auth_codes" "clients"]] (mc/drop test-db coll)))

(use-fixtures :each db-fixture)

(defn json-request [method path body]
  (-> (mock/request method path)
      (mock/content-type "application/json")
      (mock/header "accept" "application/json")
      (mock/body (json/generate-string body))))

(defn parse-body [response]
  (-> response :body slurp (json/parse-string true)))

(deftest signup-success-test
  (testing "POST /auth/signup creates a user and returns 201"
    (let [response (app (json-request :post "/auth/signup"
                                      {:name "Gabriel" :login "gspecian"
                                       :email "g@example.com" :password "secret123"}))]
      (is (= 201 (:status response)))
      (is (= "gspecian" (:login (parse-body response)))))))

(deftest signup-duplicate-email-test
  (testing "POST /auth/signup with duplicate email returns 409"
    (app (json-request :post "/auth/signup"
                       {:name "Gabriel" :login "gspecian"
                        :email "g@example.com" :password "secret123"}))
    (let [response (app (json-request :post "/auth/signup"
                                      {:name "Other" :login "other"
                                       :email "g@example.com" :password "pass456"}))]
      (is (= 409 (:status response))))))

(deftest signup-missing-fields-test
  (testing "POST /auth/signup missing required fields returns 400"
    (let [response (app (json-request :post "/auth/signup"
                                      {:name "Gabriel"}))]
      (is (= 400 (:status response))))))

(deftest login-page-test
  (testing "GET /auth/login returns HTML login page"
    (let [response (app (mock/request :get "/auth/login"))]
      (is (= 200 (:status response)))
      (is (clojure.string/includes?
           (get-in response [:headers "Content-Type"] "")
           "text/html")))))

(deftest login-success-test
  (testing "POST /auth/login with valid credentials returns 302 redirect"
    ;; First create a user
    (app (json-request :post "/auth/signup"
                       {:name "Gabriel" :login "gspecian"
                        :email "g@example.com" :password "secret123"}))
    ;; OAuth params now arrive as form fields (carried from the authorize redirect)
    (let [request  {:db test-db
                    :form-params {"email"          "g@example.com"
                                  "password"       "secret123"
                                  "client_id"      "react-app"
                                  "redirect_uri"   "http://localhost:5173/callback"
                                  "code_challenge" "abc123"
                                  "state"          "xyz"}}
          response (login-handler request)]
      (is (= 302 (:status response)))
      (is (clojure.string/includes?
           (get-in response [:headers "Location"] "")
           "http://localhost:5173/callback")))))

(deftest login-rejects-untrusted-redirect-test
  (testing "POST /auth/login with a redirect_uri not registered for the client is rejected"
    (app (json-request :post "/auth/signup"
                       {:name "Gabriel" :login "gspecian"
                        :email "g@example.com" :password "secret123"}))
    (let [response (login-handler
                    {:db test-db
                     :form-params {"email"          "g@example.com"
                                   "password"       "secret123"
                                   "client_id"      "react-app"
                                   "redirect_uri"   "http://evil.example.com/steal"
                                   "code_challenge" "abc123"
                                   "state"          "xyz"}})]
      (is (= 400 (:status response))))))

(deftest signup-short-password-test
  (testing "POST /auth/signup with a password under 6 chars returns 400"
    (let [response (app (json-request :post "/auth/signup"
                                      {:name "Gabriel" :login "gspecian"
                                       :email "g@example.com" :password "short"}))]
      (is (= 400 (:status response)))
      (is (string? (:error (parse-body response)))))))

(deftest login-wrong-password-test
  (testing "POST /auth/login with wrong password returns 401"
    (app (json-request :post "/auth/signup"
                       {:name "Gabriel" :login "gspecian"
                        :email "g@example.com" :password "secret123"}))
    (let [response (app (-> (mock/request :post "/auth/login")
                            (mock/content-type "application/x-www-form-urlencoded")
                            (mock/body "email=g%40example.com&password=wrong")))]
      (is (= 401 (:status response))))))
