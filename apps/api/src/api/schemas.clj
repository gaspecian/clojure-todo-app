(ns api.schemas)

(def Signup
  [:map
   [:name     [:string {:min 1}]]
   [:login    [:string {:min 1}]]
   [:email    [:string {:min 1}]]
   [:password [:string {:min 6}]]])

(def NewTodo
  [:map
   [:title    [:string {:min 1}]]
   [:body     {:optional true} :string]
   [:priority {:optional true} [:enum "low" "medium" "high"]]
   [:tags     {:optional true} [:vector :string]]])

(def UpdateTodo
  [:map
   [:title     {:optional true} [:string {:min 1}]]
   [:body      {:optional true} :string]
   [:completed {:optional true} :boolean]
   [:priority  {:optional true} [:enum "low" "medium" "high"]]
   [:tags      {:optional true} [:vector :string]]])
