(ns api.config
  (:require [aero.core :refer [read-config]]
            [clojure.java.io :as io]))

(defn load-config []
  (let [cfg (read-config (io/resource "config.edn"))]
    (update-in cfg [:server :port]
               (fn [p] (if (string? p) (Integer/parseInt p) p)))))
