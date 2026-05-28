(ns api.db.core
  (:require [monger.core :as mg]
            [aero.core :refer [read-config]]
            [clojure.java.io :as io]))

(defonce ^:private connection (atom nil))
(defonce ^:private database   (atom nil))

(def ^:private config
  (delay (read-config (io/resource "config.edn"))))

(defn connect! []
  (when (nil? @connection)
    (let [uri              (get-in @config [:db :uri])
          {:keys [conn db]} (mg/connect-via-uri uri)]
      (reset! connection conn)
      (reset! database db))))

(defn disconnect! []
  (when-let [conn @connection]
    (mg/disconnect conn)
    (reset! connection nil)
    (reset! database nil)))

(defn get-db [] @database)
