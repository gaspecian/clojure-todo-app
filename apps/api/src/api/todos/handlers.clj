(ns api.todos.handlers
  (:require [monger.collection :as mc]
            [monger.operators :refer [$set]])
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

(defn list-handler [{:keys [user-id db]}]
  (let [todos (mc/find-maps db "todos" {:user_id user-id})]
    {:status 200
     :body   {:todos (mapv todo->response todos)}}))

(defn create-handler [{:keys [user-id db parameters]}]
  (let [data (:body parameters)
        now  (Date.)
        todo {:_id        (ObjectId.)
              :user_id    user-id
              :title      (:title data)
              :body       (:body data "")
              :completed  false
              :priority   (:priority data "medium")
              :due_date   nil
              :tags       (vec (:tags data []))
              :created_at now
              :updated_at now}]
    (mc/insert db "todos" todo)
    {:status 201
     :body   (todo->response todo)}))

(defn get-handler [{:keys [user-id db path-params]}]
  (let [todo (mc/find-one-as-map db "todos"
                                 {:_id     (ObjectId. (:id path-params))
                                  :user_id user-id})]
    (if (nil? todo)
      {:status 404 :body {:error "todo not found"}}
      {:status 200 :body (todo->response todo)})))

(defn update-handler [{:keys [user-id db path-params parameters]}]
  (let [id   (ObjectId. (:id path-params))
        data (:body parameters)
        todo (mc/find-one-as-map db "todos" {:_id id :user_id user-id})]
    (if (nil? todo)
      {:status 404 :body {:error "todo not found"}}
      (let [updates (cond-> {:updated_at (Date.)}
                      (contains? data :title)     (assoc :title (:title data))
                      (contains? data :body)      (assoc :body (:body data))
                      (contains? data :completed) (assoc :completed (:completed data))
                      (contains? data :priority)  (assoc :priority (:priority data))
                      (contains? data :tags)      (assoc :tags (vec (:tags data))))]
        (mc/update db "todos" {:_id id} {$set updates})
        {:status 200
         :body   (todo->response (merge todo updates))}))))

(defn delete-handler [{:keys [user-id db path-params]}]
  (mc/remove db "todos"
             {:_id     (ObjectId. (:id path-params))
              :user_id user-id})
  {:status 204 :body nil})
