(ns api.system-test
  (:require [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [api.system :as system]))

(deftest make-app-serves-health-test
  (testing "make-app builds a handler that serves /health without needing a db"
    (let [app (system/make-app {:db nil :config nil})
          res (app (-> (mock/request :get "/health")
                       (mock/header "accept" "application/json")))]
      (is (= 200 (:status res))))))
