(ns api.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [api.core :refer [app]]))

(deftest health-endpoint-test
  (testing "GET /health returns 200 OK"
    (let [response (app (mock/request :get "/health"))]
      (is (= 200 (:status response))))))
