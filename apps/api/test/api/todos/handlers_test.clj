(ns api.todos.handlers-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ring.mock.request :as mock]
            [cheshire.core :as json]
            [buddy.sign.jwt :as jwt]
            [api.test-helpers :as helpers]
            [monger.collection :as mc])
  (:import [org.bson.types ObjectId]
           [java.util Date]))

(def system (helpers/make-test-system))
(def app (:app system))
(def test-db (:db system))

(def test-user-id (ObjectId.))
;; Sign tokens with the same secret the injected app verifies against.
(def jwt-secret (get-in helpers/test-config [:jwt :secret]))

(defn make-token [user-id]
  (jwt/sign {:sub (str user-id)
             :exp (Date. (+ (System/currentTimeMillis) 900000))}
            jwt-secret))

(defn auth-request [method path token & [body]]
  (cond-> (mock/request method path)
    true  (mock/header "authorization" (str "Bearer " token))
    true  (mock/header "accept" "application/json")
    body  (mock/content-type "application/json")
    body  (mock/body (json/generate-string body))))

(defn parse-body [response]
  (let [body (:body response)]
    (cond
      (string? body) (json/parse-string body true)
      (bytes? body)  (json/parse-string (String. ^bytes body "UTF-8") true)
      :else          (json/parse-string (slurp body) true))))

(defn db-fixture [f]
  (mc/drop test-db "todos")
  (f)
  (mc/drop test-db "todos"))

(use-fixtures :each db-fixture)

(deftest list-todos-empty-test
  (testing "GET /api/todos returns empty list for new user"
    (let [token    (make-token test-user-id)
          response (app (auth-request :get "/api/todos" token))]
      (is (= 200 (:status response)))
      (is (= [] (:todos (parse-body response)))))))

(deftest create-todo-test
  (testing "POST /api/todos creates and returns a todo"
    (let [token    (make-token test-user-id)
          response (app (auth-request :post "/api/todos" token
                                      {:title    "Buy milk"
                                       :body     "2% please"
                                       :priority "high"
                                       :tags     ["personal"]}))
          body     (parse-body response)]
      (is (= 201 (:status response)))
      (is (= "Buy milk" (:title body)))
      (is (= false (:completed body))))))

(deftest create-todo-missing-title-test
  (testing "POST /api/todos without title returns 400"
    (let [token    (make-token test-user-id)
          response (app (auth-request :post "/api/todos" token {:body "no title"}))]
      (is (= 400 (:status response))))))

(deftest update-todo-test
  (testing "PUT /api/todos/:id updates the todo"
    (let [token       (make-token test-user-id)
          create-resp (app (auth-request :post "/api/todos" token {:title "Task"}))
          todo-id     (:id (parse-body create-resp))
          update-resp (app (auth-request :put (str "/api/todos/" todo-id) token
                                         {:title "Task updated" :completed true}))
          update-body (parse-body update-resp)]
      (is (= 200 (:status update-resp)))
      (is (= "Task updated" (:title update-body)))
      (is (= true (:completed update-body))))))

(deftest delete-todo-test
  (testing "DELETE /api/todos/:id removes the todo"
    (let [token       (make-token test-user-id)
          create-resp (app (auth-request :post "/api/todos" token {:title "Delete me"}))
          todo-id     (:id (parse-body create-resp))
          del-resp    (app (auth-request :delete (str "/api/todos/" todo-id) token))]
      (is (= 204 (:status del-resp))))))

(deftest create-todo-invalid-priority-test
  (testing "POST /api/todos with a priority outside the enum returns 400"
    (let [token    (make-token test-user-id)
          response (app (auth-request :post "/api/todos" token
                                      {:title "x" :priority "urgent"}))]
      (is (= 400 (:status response)))
      (is (string? (:error (parse-body response)))))))

(deftest create-todo-defaults-priority-test
  (testing "POST /api/todos without priority defaults to medium"
    (let [token    (make-token test-user-id)
          response (app (auth-request :post "/api/todos" token {:title "no priority"}))
          body     (parse-body response)]
      (is (= 201 (:status response)))
      (is (= "medium" (:priority body))))))

(deftest update-todo-empty-title-test
  (testing "PUT /api/todos/:id with an empty title returns 400"
    (let [token       (make-token test-user-id)
          create-resp (app (auth-request :post "/api/todos" token {:title "Task"}))
          todo-id     (:id (parse-body create-resp))
          update-resp (app (auth-request :put (str "/api/todos/" todo-id) token {:title ""}))]
      (is (= 400 (:status update-resp))))))

(deftest unauthorized-test
  (testing "GET /api/todos without token returns 401"
    (let [response (app (mock/request :get "/api/todos"))]
      (is (= 401 (:status response))))))
