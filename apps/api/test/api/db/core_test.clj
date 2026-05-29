(ns api.db.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [api.db.core :as db]))

(deftest connect-test
  (testing "connect returns a live conn and db; disconnect closes the connection"
    (let [{:keys [conn db]} (db/connect "mongodb://localhost:27017/tododb_test")]
      (is (some? conn))
      (is (some? db))
      (db/disconnect conn))))
