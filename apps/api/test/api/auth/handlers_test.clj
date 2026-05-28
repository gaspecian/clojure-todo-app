(ns api.auth.handlers-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ring.mock.request :as mock]
            [cheshire.core :as json]
            [api.core :refer [app]]
            [api.auth.handlers :refer [login-handler]]
            [api.db.core :as db]
            [monger.collection :as mc]))

(defn db-fixture [f]
  (db/connect!)
  (mc/drop (db/get-db) "users")
  (mc/drop (db/get-db) "auth_codes")
  (mc/ensure-index (db/get-db) "users" (array-map :email 1) {:unique true})
  (mc/ensure-index (db/get-db) "users" (array-map :login 1) {:unique true})
  (f)
  (mc/drop (db/get-db) "users")
  (mc/drop (db/get-db) "auth_codes"))

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
    ;; Test the handler directly with injected session
    (let [session-data {:oauth/client_id      "react-app"
                        :oauth/redirect_uri   "http://localhost:5173/callback"
                        :oauth/code_challenge "abc123"
                        :oauth/state          "xyz"}
          request      {:form-params {"email"    "g@example.com"
                                      "password" "secret123"}
                        :session     session-data}
          response     (login-handler request)]
      (is (= 302 (:status response)))
      (is (clojure.string/includes?
           (get-in response [:headers "Location"] "")
           "http://localhost:5173/callback")))))

(deftest login-wrong-password-test
  (testing "POST /auth/login with wrong password returns 401"
    (app (json-request :post "/auth/signup"
                       {:name "Gabriel" :login "gspecian"
                        :email "g@example.com" :password "secret123"}))
    (let [response (app (-> (mock/request :post "/auth/login")
                            (mock/content-type "application/x-www-form-urlencoded")
                            (mock/body "email=g%40example.com&password=wrong")))]
      (is (= 401 (:status response))))))
