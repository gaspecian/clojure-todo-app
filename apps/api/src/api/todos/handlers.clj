(ns api.todos.handlers
  (:require [monger.collection :as mc]
            [monger.operators :refer [$set]]
            [api.db.core :as db])
  (:import [org.bson.types ObjectId]
           [java.util Date]))

(defn- todo->response [todo]
  {:id         (str (:_id todo))
   :title      (:title todo)
   :body       (:body todo "")
   :completed  (:completed todo false)
   :priority   (:priority todo "medium")
   :due_date   (some-> (:due_date todo) .toInstant .toString)
   :tags       (:tags todo [])
   :created_at (some-> (:created_at todo) .toInstant .toString)
   :updated_at (some-> (:updated_at todo) .toInstant .toString)})

(defn list-handler [{:keys [user-id]}]
  (let [todos (mc/find-maps (db/get-db) "todos" {:user_id user-id})]
    {:status 200
     :body   {:todos (mapv todo->response todos)}}))

(defn create-handler [{:keys [user-id body-params]}]
  (if (not (seq (:title body-params)))
    {:status 400 :body {:error "title is required"}}
    (let [now  (Date.)
          todo {:_id        (ObjectId.)
                :user_id    user-id
                :title      (:title body-params)
                :body       (:body body-params "")
                :completed  false
                :priority   (:priority body-params "medium")
                :due_date   nil
                :tags       (vec (:tags body-params []))
                :created_at now
                :updated_at now}]
      (mc/insert (db/get-db) "todos" todo)
      {:status 201
       :body   (todo->response todo)})))

(defn get-handler [{:keys [user-id path-params]}]
  (let [todo (mc/find-one-as-map (db/get-db) "todos"
                                 {:_id     (ObjectId. (:id path-params))
                                  :user_id user-id})]
    (if (nil? todo)
      {:status 404 :body {:error "todo not found"}}
      {:status 200 :body (todo->response todo)})))

(defn update-handler [{:keys [user-id path-params body-params]}]
  (let [id   (ObjectId. (:id path-params))
        todo (mc/find-one-as-map (db/get-db) "todos" {:_id id :user_id user-id})]
    (if (nil? todo)
      {:status 404 :body {:error "todo not found"}}
      (let [updates (cond-> {:updated_at (Date.)}
                      (contains? body-params :title)     (assoc :title (:title body-params))
                      (contains? body-params :body)      (assoc :body (:body body-params))
                      (contains? body-params :completed) (assoc :completed (:completed body-params))
                      (contains? body-params :priority)  (assoc :priority (:priority body-params))
                      (contains? body-params :due_date)  (assoc :due_date (:due_date body-params))
                      (contains? body-params :tags)      (assoc :tags (vec (:tags body-params))))]
        (mc/update (db/get-db) "todos" {:_id id} {$set updates})
        {:status 200
         :body   (todo->response (merge todo updates))}))))

(defn delete-handler [{:keys [user-id path-params]}]
  (mc/remove (db/get-db) "todos"
             {:_id     (ObjectId. (:id path-params))
              :user_id user-id})
  {:status 204 :body nil})
