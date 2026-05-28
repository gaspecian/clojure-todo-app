(ns api.core
  (:require [ring.adapter.jetty :as jetty]
            [reitit.ring :as ring]
            [muuntaja.core :as m]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [aero.core :refer [read-config]]
            [clojure.java.io :as io]
            [api.db.core :as db]
            [api.db.seed :as seed]
            [api.auth.handlers :as auth]
            [api.oauth.handlers :as oauth]
            [api.todos.handlers :as todos]
            [api.middleware.auth :refer [wrap-auth]])
  (:import [java.io InputStream])
  (:gen-class))

(def config
  (delay (read-config (io/resource "config.edn"))))

(def routes
  [["/health"                                 {:get (fn [_] {:status 200 :body {:status "ok"}})}]
   ["/.well-known/oauth-authorization-server" {:get oauth/metadata-handler}]
   ["/auth/signup"                            {:post auth/signup-handler}]
   ["/auth/login"                             {:get  auth/login-page-handler
                                               :post auth/login-handler}]
   ["/oauth/authorize"                        {:get oauth/authorize-handler}]
   ["/oauth/token"                            {:post oauth/token-handler}]
   ["/oauth/revoke"                           {:post oauth/revoke-handler}]
   ["/oauth/userinfo"                         {:get oauth/userinfo-handler}]
   ["/api"
    {:middleware [wrap-auth]}
    ["/todos"
     {:get  todos/list-handler
      :post todos/create-handler}]
    ["/todos/:id"
     {:get    todos/get-handler
      :put    todos/update-handler
      :delete todos/delete-handler}]]])

(defn wrap-body-to-bytes
  "Converts InputStream response bodies to byte arrays so they can be read
   multiple times (e.g., in tests using parse-body more than once)."
  [handler]
  (fn [request]
    (let [response (handler request)
          body     (:body response)]
      (if (instance? InputStream body)
        (assoc response :body (.readAllBytes ^InputStream body))
        response))))

(def app
  (-> (ring/ring-handler
       (ring/router routes
                    {:data {:muuntaja   m/instance
                            :middleware [muuntaja/format-middleware]}})
       (ring/create-default-handler))
      (wrap-cookies)
      (wrap-params)
      (wrap-session)
      (wrap-cors
       :access-control-allow-origin  [#"^http://localhost:5173$"]
       :access-control-allow-methods [:get :post :put :delete :options]
       :access-control-allow-headers ["Authorization" "Content-Type"])
      (wrap-body-to-bytes)))

(defn -main [& _]
  (db/connect!)
  (seed/seed-clients!)
  (let [port (get-in @config [:server :port])]
    (println (str "Server running on port " port))
    (jetty/run-jetty #'app {:port port :join? true})))
