(ns api.db.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [api.db.core :as db]))

(deftest connection-test
  (testing "connects to MongoDB and returns a db instance"
    (db/connect!)
    (is (some? (db/get-db)))))

(deftest connect-returns-conn-and-db-test
  (testing "connect returns a map with a live conn and db"
    (let [{:keys [conn db]} (db/connect "mongodb://localhost:27017/tododb_test")]
      (is (some? conn))
      (is (some? db))
      (db/disconnect conn))))
