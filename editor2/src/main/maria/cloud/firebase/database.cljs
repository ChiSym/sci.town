(ns maria.cloud.firebase.database
  (:refer-clojure :exclude [munge demunge DEMUNGE_MAP])
  (:require ["firebase/database" :as DB]
            [applied-science.js-interop :as j]
            [clojure.string :as str]
            [promesa.core :as p]
            [re-db.hooks :as h]
            [re-db.memo :as memo]
            [re-db.reactive :as r]
            [lambdaisland.glogi :as log]))

(def TIMESTAMP (DB/serverTimestamp))

(defonce !db (atom nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; munging - 2-way transform of strings into valid firebase paths

(def munge-map* {"." "_DOT_"
                 "#" "_POUND_"
                 "$" "_DOLLAR_"
                 "[" "_LBRACK_"
                 "]" "_RBRACK_"})

(def MUNGE_MAP (clj->js munge-map*))
(def DEMUNGE_MAP (clj->js (zipmap (vals munge-map*) (keys munge-map*))))

(defn munge [x]
  (str/replace-all x #"[/\.#$\[\]]" (fn [ch _ _]
                                      (j/!get MUNGE_MAP ch ch))))
(defn demunge [x]
  (str/replace-all x #"_DOT_|_POUND_|_DOLLAR_|_LBRACK_|_RBRACK_"
                   (fn [ch _ _]
                     (j/!get DEMUNGE_MAP ch ch))))

(defn join
  "Permissive fn to return a string from a path, which can be
   - a string
   - a keyword
   - a collection of strings/keywords
   Independent segments are joined with `/`"
  [path]
  (cond (string? path) path
        (keyword? path) (name path)
        :else
        (->> path
             (map #(cond (keyword? %) (name %)
                         (string? %) (munge %)
                         :else %))
             (str/join "/"))))

(defn kw->str [x] (cond-> x (keyword? x) name))

(def query-specs
  ;; for the purposes of memoizing query functions we have a data description
  ;; of firebase queries using the following mappings.
  {:orderByChild (comp DB/orderByChild kw->str)
   :orderByKey DB/orderByKey
   :orderByValue DB/orderByValue
   :startAt DB/startAt
   :startAfter DB/startAfter
   :endAt DB/endAt
   :endBefore DB/endBefore
   :equalTo DB/equalTo
   :limitToFirst DB/limitToFirst
   :limitToLast DB/limitToLast})

(declare ref)

(defn query [path & queries]
  (apply DB/query (ref path)
         (for [x queries]
           (if (keyword? x)
             ((query-specs x))
             (let [[x arg] x]
               ((query-specs x) arg))))))

(defn ref
  "Given a path or query, returns a Firebase reference using the globally registered db."
  ;; a query is a vector of the form [:fire/query [path] [query-spec arg] ...]
  [path-or-query]
  (cond (string? path-or-query) (DB/ref @!db path-or-query)
        (coll? path-or-query) (if (= :fire/query (first path-or-query))
                                (apply query (rest path-or-query))
                                (DB/ref @!db (join path-or-query)))
        :else path-or-query))

(defn assoc-in+
  "Writes data to path in db, returns promise."
  [path v]
  (log/trace :assoc-in+ {:path path
                         :value v})
  (DB/set (ref path) (clj->js v)))

(defn format-update [m]
  (-> m (update-keys join) clj->js))

(defn update+
  "Updates data at paths in db, returns promise.
  Paths may be strings, keywords or vectors of strings/keywords."
  ([m] (update+ (DB/ref @!db) m))
  ([path m]
   (log/trace :update+ {:path path :update m})
   (DB/update (ref path) (format-update m))))

(def push (comp DB/push ref))

(defn once [path]
  (p/-> (DB/get (ref path))
        (j/call :val)
        (js->clj :keywordize-keys true)))

(defn on-child-changed [path cb] (DB/onChildChanged (ref path) cb))
(defn on-child-removed [path cb] (DB/onChildRemoved (ref path) cb))
(defn on-child-added [path cb] (DB/onChildAdded (ref path) cb))
(defn on-child-moved [path cb] (DB/onChildMoved (ref path) cb))
(def off DB/off)

(def on-disconnect (comp DB/onDisconnect ref))

(defn on-value [path cb]
  ;; on-value calls *synchronously* if the value is in cache.
  ;; we wrap this to always be async to play nice with re-db reaction hooks,
  ;; while still returning an "unsubscribe" function.
  (let [ref (ref path)]
    (js/setTimeout #(DB/onValue ref cb) 0)
    #(off ref cb)))

(memo/defn-memo $value [path]
  (r/reaction
    ;; delay disposal to avoid resubscription when components
    ;; rapidly unsubscribe and resubscribe to the same queries.
    {:meta {:dispose-delay 1000}}
    (let [!state (h/use-state nil)]
      (h/use-effect
        (fn []
          (on-value (ref path)
                    (fn [^js snap]
                      (->> (js->clj (.val snap) :keywordize-keys true)
                           (reset! !state)))))
        path)
      @!state)))