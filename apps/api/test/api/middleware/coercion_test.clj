(ns api.middleware.coercion-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [api.middleware.coercion :refer [wrap-coercion-errors]]))

(deftest passes-through-success-test
  (testing "non-throwing handler is returned unchanged"
    (let [h (wrap-coercion-errors (fn [_] {:status 200 :body :ok}))]
      (is (= 200 (:status (h {})))))))

(deftest converts-request-coercion-error-test
  (testing "a reitit request-coercion failure becomes a 400 {:error <string>}"
    (let [throwing (fn [_] (throw (ex-info "coercion"
                                           {:type   :reitit.coercion/request-coercion
                                            :schema [:map [:title [:string {:min 1}]]]
                                            :value  {:title ""}})))
          res ((wrap-coercion-errors throwing) {})]
      (is (= 400 (:status res)))
      (is (string? (get-in res [:body :error])))
      (is (str/includes? (get-in res [:body :error]) "title")))))

(deftest rethrows-other-exceptions-test
  (testing "non-coercion exceptions propagate"
    (let [throwing (fn [_] (throw (ex-info "boom" {:type :something-else})))]
      (is (thrown? clojure.lang.ExceptionInfo ((wrap-coercion-errors throwing) {}))))))
