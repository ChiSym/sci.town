(ns maria.cloud.firebase.database
  (:refer-clojure :exclude [ref munge])
  (:require ["firebase/database" :as DB]
            ["react-firebase-hooks/database" :as hooks]
            [clojure.string :as str]))

(defonce !db (atom nil))

(defn ref [path]
  (cond (string? path) (DB/ref @!db path)
        (coll? path) (DB/ref @!db (->> path
                                       (map #(cond-> % (keyword? %) name))
                                       (str/join "/")))
        :else path))

(defn assoc-in! [path m]
  (DB/set (ref path) (clj->js m)))

(def on-disconnect (comp DB/onDisconnect ref))

(defn munge [x]
  (str/replace-all x #"[/\.#$\[\]]" "__"))

(defn use-map [path]
  (some-> (hooks/useObjectVal (ref path))
          vec
          (update 0 #(js->clj % :keywordize-keys true))))