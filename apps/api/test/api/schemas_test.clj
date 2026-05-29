(ns api.schemas-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [api.schemas :as schemas]))

(deftest signup-schema-test
  (testing "Signup requires all fields and a password of at least 6 chars"
    (is (m/validate schemas/Signup {:name "G" :login "g" :email "g@e.com" :password "secret1"}))
    (is (not (m/validate schemas/Signup {:name "G" :login "g" :email "g@e.com" :password "short"})))
    (is (not (m/validate schemas/Signup {:name "G"})))))

(deftest new-todo-schema-test
  (testing "NewTodo requires a non-empty title and restricts priority to the enum"
    (is (m/validate schemas/NewTodo {:title "x"}))
    (is (m/validate schemas/NewTodo {:title "x" :priority "high" :tags ["a"]}))
    (is (not (m/validate schemas/NewTodo {:title ""})))
    (is (not (m/validate schemas/NewTodo {:title "x" :priority "urgent"})))))

(deftest update-todo-schema-test
  (testing "UpdateTodo is fully partial but enforces constraints when a field is present"
    (is (m/validate schemas/UpdateTodo {}))
    (is (m/validate schemas/UpdateTodo {:completed true}))
    (is (not (m/validate schemas/UpdateTodo {:title ""})))
    (is (not (m/validate schemas/UpdateTodo {:priority "urgent"})))))
