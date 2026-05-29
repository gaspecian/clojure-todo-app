(ns api.db.core
  (:require [monger.core :as mg]
            [monger.collection :as mc]))

(defn connect [uri]
  (let [{:keys [conn db]} (mg/connect-via-uri uri)]
    (mc/ensure-index db "users" (array-map :email 1) {:unique true})
    (mc/ensure-index db "users" (array-map :login 1) {:unique true})
    {:conn conn :db db}))

(defn disconnect [conn]
  (mg/disconnect conn))
