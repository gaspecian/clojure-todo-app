(ns api.db.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [api.db.core :as db]))

(deftest connection-test
  (testing "connects to MongoDB and returns a db instance"
    (db/connect!)
    (is (some? (db/get-db)))))
