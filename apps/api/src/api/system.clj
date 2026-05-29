(ns api.system
  (:require [ring.adapter.jetty :as jetty]
            [reitit.ring :as ring]
            [muuntaja.core :as m]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [api.db.core :as db]
            [api.db.seed :as seed]
            [api.auth.handlers :as auth]
            [api.oauth.handlers :as oauth]
            [api.todos.handlers :as todos]
            [api.middleware.auth :refer [wrap-auth]]
            [api.schemas :as schemas]
            [reitit.coercion.malli]
            [reitit.ring.coercion :as rrc]
            [api.middleware.errors :as errors]))

(def routes
  [["/health"                                 {:get (fn [_] {:status 200 :body {:status "ok"}})}]
   ["/.well-known/oauth-authorization-server" {:get oauth/metadata-handler}]
   ["/auth/signup"                            {:post {:parameters {:body schemas/Signup}
                                                      :handler     auth/signup-handler}}]
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
      :post {:parameters {:body schemas/NewTodo}
             :handler     todos/create-handler}}]
    ["/todos/:id"
     {:get    todos/get-handler
      :put    {:parameters {:body schemas/UpdateTodo}
               :handler     todos/update-handler}
      :delete todos/delete-handler}]]])

(defn wrap-deps [handler db config]
  (fn [request]
    (handler (assoc request :db db :config config))))

(defn make-app [{:keys [db config]}]
  (-> (ring/ring-handler
       (ring/router routes
                    {:data {:muuntaja   m/instance
                            :coercion   reitit.coercion.malli/coercion
                            :middleware [muuntaja/format-middleware
                                         errors/exception-middleware
                                         rrc/coerce-request-middleware]}})
       (ring/create-default-handler))
      (wrap-deps db config)
      (wrap-cookies)
      (wrap-params)
      (wrap-cors
       :access-control-allow-origin      [#"^http://localhost:5173$"]
       :access-control-allow-methods     [:get :post :put :delete :options]
       :access-control-allow-headers     ["Authorization" "Content-Type"]
       :access-control-allow-credentials "true")))

(defn start [config]
  (let [{:keys [conn db]} (db/connect (get-in config [:db :uri]))]
    (seed/seed-clients! db)
    (let [server (jetty/run-jetty (make-app {:db db :config config})
                                  {:port (get-in config [:server :port]) :join? false})]
      {:config config :conn conn :db db :server server})))

(defn stop [{:keys [conn server]}]
  (when server (.stop ^org.eclipse.jetty.server.Server server))
  (when conn (db/disconnect conn)))
