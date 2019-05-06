(ns maria.persistence.local
  (:require [re-db.d :as d]
            [maria.persistence.transit :as t]
            [goog.functions :as gf]
            [applied-science.js-interop :as j]))

(defn local-id [id] (str "maria.local/" id))

(defn local-get [id]
  (some-> (j/get-in js/window [:localStorage (local-id id)])
          (t/deserialize)))

(defn local-put! [id data]
  (j/assoc! (.-localStorage js/window) (local-id id) (t/serialize data)))

(defn local-update! [id f & args]
  (local-put! id (apply f (local-get id) args)))

(def init-storage
  "Given a unique id, initialize a local-storage backed source"
  (memoize (fn
             ([id]
              (d/transact! [{:local (local-get id)
                             :db/id id}])
              (d/listen {:ea_ [[id :local]]}
                        (gf/throttle #(local-put! id (d/get id :local)) 300)))
             ([id initial-content]
              (when (and (nil? (local-get id)) initial-content)
                (local-put! id initial-content))
              (init-storage id)))))

