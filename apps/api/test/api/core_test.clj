(ns api.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [api.core :refer [app]]))

(deftest health-endpoint-test
  (testing "GET /health returns 200 OK"
    (let [response (app (mock/request :get "/health"))]
      (is (= 200 (:status response))))))

(deftest cors-preflight-allows-credentials-test
  (testing "CORS preflight grants credentials so the SPA's credentialed fetches succeed"
    (let [response (app (-> (mock/request :options "/oauth/token")
                            (mock/header "origin" "http://localhost:5173")
                            (mock/header "access-control-request-method" "POST")))]
      (is (= "http://localhost:5173"
             (get-in response [:headers "Access-Control-Allow-Origin"])))
      (is (= "true"
             (get-in response [:headers "Access-Control-Allow-Credentials"]))))))
