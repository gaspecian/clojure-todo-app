(ns api.test-helpers
  (:require [api.config :as config]
            [api.db.core :as db]
            [api.system :as system]))

(def test-config
  (assoc-in (config/load-config) [:db :uri] "mongodb://localhost:27017/tododb_test"))

(defn make-test-system []
  (let [{:keys [conn db]} (db/connect (get-in test-config [:db :uri]))]
    {:conn conn
     :db   db
     :app  (system/make-app {:db db :config test-config})}))
