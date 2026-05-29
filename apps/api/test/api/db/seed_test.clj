(ns api.db.seed-test
  (:require [clojure.test :refer [deftest is testing]]
            [api.db.core :as db]
            [api.db.seed :as seed]
            [monger.collection :as mc]))

(deftest seed-clients-inserts-react-app-test
  (testing "seed-clients! inserts the react-app client when it is missing"
    (let [{:keys [conn db]} (db/connect "mongodb://localhost:27017/tododb_test")]
      (mc/drop db "clients")
      (seed/seed-clients! db)
      (is (some? (mc/find-one-as-map db "clients" {:client_id "react-app"})))
      (mc/drop db "clients")
      (db/disconnect conn))))
