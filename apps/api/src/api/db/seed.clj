(ns api.db.seed
  (:require [monger.collection :as mc])
  (:import [org.bson.types ObjectId]))

(defn seed-clients! [db]
  (let [coll     "clients"
        existing (mc/find-one-as-map db coll {:client_id "react-app"})]
    (when (nil? existing)
      (mc/insert db coll
                 {:_id           (ObjectId.)
                  :client_id     "react-app"
                  :name          "React Todo App"
                  :redirect_uris ["http://localhost:5173/callback"]
                  :grant_types   ["authorization_code" "refresh_token"]}))))
