(ns api.db.seed
  (:require [monger.collection :as mc]
            [api.db.core :as db])
  (:import [org.bson.types ObjectId]))

(defn seed-clients! []
  (let [coll     "clients"
        existing (mc/find-one-as-map (db/get-db) coll {:client_id "react-app"})]
    (when (nil? existing)
      (mc/insert (db/get-db) coll
                 {:_id           (ObjectId.)
                  :client_id     "react-app"
                  :name          "React Todo App"
                  :redirect_uris ["http://localhost:5173/callback"]
                  :grant_types   ["authorization_code" "refresh_token"]}))))
