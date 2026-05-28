(ns api.core
  (:require [ring.adapter.jetty :as jetty]
            [reitit.ring :as ring]
            [muuntaja.core :as m]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.middleware.session :refer [wrap-session]]
            [aero.core :refer [read-config]]
            [clojure.java.io :as io]
            [api.db.core :as db])
  (:gen-class))

(def config
  (delay (read-config (io/resource "config.edn"))))

(def routes
  [["/health" {:get (fn [_] {:status 200 :body {:status "ok"}})}]])

(def app
  (-> (ring/ring-handler
       (ring/router routes
                    {:data {:muuntaja   m/instance
                            :middleware [muuntaja/format-middleware]}})
       (ring/create-default-handler))
      (wrap-session)
      (wrap-cors
       :access-control-allow-origin  [#"^http://localhost:5173$"]
       :access-control-allow-methods [:get :post :put :delete :options]
       :access-control-allow-headers ["Authorization" "Content-Type"])))

(defn -main [& _]
  (db/connect!)
  (let [port (get-in @config [:server :port])]
    (println (str "Server running on port " port))
    (jetty/run-jetty #'app {:port port :join? true})))
