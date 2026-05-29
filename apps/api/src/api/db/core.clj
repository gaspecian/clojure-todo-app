(ns api.db.core
  (:require [monger.core :as mg]
            [monger.collection :as mc]
            [aero.core :refer [read-config]]
            [clojure.java.io :as io]))

(defn connect [uri]
  (let [{:keys [conn db]} (mg/connect-via-uri uri)]
    (mc/ensure-index db "users" (array-map :email 1) {:unique true})
    (mc/ensure-index db "users" (array-map :login 1) {:unique true})
    {:conn conn :db db}))

(defn disconnect [conn]
  (mg/disconnect conn))

;; --- transitional global API (removed in Task 8 once all callers migrate) ---
(defonce ^:private connection (atom nil))
(defonce ^:private database   (atom nil))

(def ^:private config
  (delay (read-config (io/resource "config.edn"))))

(defn connect! []
  (when (nil? @connection)
    (let [{:keys [conn db]} (connect (get-in @config [:db :uri]))]
      (reset! connection conn)
      (reset! database db))))

(defn get-db [] @database)

(defn disconnect! []
  (when-let [conn @connection]
    (disconnect conn)
    (reset! connection nil)
    (reset! database nil)))
