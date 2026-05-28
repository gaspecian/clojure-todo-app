(ns api.db.core
  (:require [monger.core :as mg]
            [aero.core :refer [read-config]]
            [clojure.java.io :as io]))

(defonce ^:private connection (atom nil))
(defonce ^:private database   (atom nil))

(defn connect! []
  (let [uri              (get-in (read-config (io/resource "config.edn")) [:db :uri])
        {:keys [conn db]} (mg/connect-via-uri uri)]
    (reset! connection conn)
    (reset! database db)))

(defn get-db [] @database)
