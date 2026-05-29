(ns api.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [api.config :as config]))

(deftest load-config-test
  (testing "load-config returns a config map with an integer port, db uri, and jwt secret"
    (let [cfg (config/load-config)]
      (is (integer? (get-in cfg [:server :port])))
      (is (string? (get-in cfg [:db :uri])))
      (is (string? (get-in cfg [:jwt :secret]))))))
