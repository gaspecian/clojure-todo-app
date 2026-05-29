(ns api.core
  (:require [api.config :as config]
            [api.system :as system])
  (:gen-class))

(defn -main [& _]
  (let [sys (system/start (config/load-config))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn [] (system/stop sys))))
    (println (str "Server running on port " (get-in sys [:config :server :port])))
    (.join ^org.eclipse.jetty.server.Server (:server sys))))
