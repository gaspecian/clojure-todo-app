(ns api.middleware.errors-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [reitit.ring :as ring]
            [reitit.coercion.malli]
            [reitit.ring.coercion :as rrc]
            [api.middleware.errors :as errors])
  (:import [org.bson.types ObjectId]))

(def app
  (ring/ring-handler
   (ring/router
    [["/coerce" {:post {:parameters {:body [:map [:title [:string {:min 1}]]]}
                        :handler    (fn [_] {:status 200 :body :ok})}}]
     ["/bad-id" {:get {:handler (fn [_] {:status 200 :body (str (ObjectId. "not-hex"))})}}]
     ["/boom"   {:get {:handler (fn [_] (throw (RuntimeException. "kaboom")))}}]]
    {:data {:coercion   reitit.coercion.malli/coercion
            :middleware [errors/exception-middleware
                         rrc/coerce-request-middleware]}})))

(deftest coercion-error-test
  (testing "a coercion failure becomes 400 {:error <string>}"
    (let [res (app {:request-method :post :uri "/coerce" :body-params {:title ""}})]
      (is (= 400 (:status res)))
      (is (string? (get-in res [:body :error])))
      (is (str/includes? (get-in res [:body :error]) "title")))))

(deftest bad-argument-test
  (testing "an IllegalArgumentException becomes a generic 400"
    (let [res (app {:request-method :get :uri "/bad-id"})]
      (is (= 400 (:status res)))
      (is (= "invalid request parameter" (get-in res [:body :error]))))))

(deftest internal-error-test
  (testing "an unexpected exception becomes a 500 (stack trace is logged to stderr)"
    (let [res (app {:request-method :get :uri "/boom"})]
      (is (= 500 (:status res)))
      (is (= "internal server error" (get-in res [:body :error]))))))
