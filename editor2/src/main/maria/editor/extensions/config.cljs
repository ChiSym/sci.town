(ns maria.editor.extensions.config
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [shadow.lazy :as lazy]
            [shadow.loader]
            [promesa.core :as p]
            [sci.ctx-store :as ctx]))

;; feel free to submit a PR with additional extension config here

(def loadables
  "A map of shadow lazy modules to namespace prefixes that they provide."
  {:reagent {:loadable (lazy/loadable maria.editor.extensions.reagent/install!)
             :prefixes '[reagent]}
   :gen {:loadable (lazy/loadable maria.editor.extensions.gen/install!)
         :prefixes '[gen]}
   :emmy {:loadable (lazy/loadable maria.editor.extensions.emmy/install!)
          :prefixes '[leva
                      emmy
                      mafs
                      jsxgraph
                      mathbox
                      mathlive]
          :depends-on #{:reagent}}})

(def prefix->module-id
  (->> loadables
       (reduce-kv (fn [out k {:keys [loadable prefixes]}]
                    (reduce (fn [out lib-prefix]
                              (assoc out lib-prefix k))
                            out prefixes)) {})))

(defn ordered-loadables [dep-name]
  (let [dep (get loadables dep-name)
        deps (set (:depends-on dep))]
    (concat
     (mapcat ordered-loadables deps)
     [(:loadable dep)])))

(defonce !installed (atom #{}))

(defn load-module+ [ctx dep-name]
  (p/doseq [loadable (ordered-loadables dep-name)]
    (p/let [install! (lazy/load loadable)]
      (when-not (@!installed install!)
        (ctx/with-ctx ctx (install!))))))

(defn libname-prefix [libname]
  (symbol (first (str/split (str libname) #"\."))))

;; keep track of the namespaces added by each module, for debugging
;; TODO - build this from shadow's manifest.edn?
(defonce !module-namespaces (atom {}))

(defn load-lib+ [{:as info :keys [libname ctx]}]
  (let [ns-before (-> ctx :env deref :namespaces keys set)]
    (if-let [module-id (some-> libname libname-prefix prefix->module-id)]
      (p/do (load-module+ ctx module-id)
            (swap! !module-namespaces update module-id
                   (fn [added] (or added
                                   (set/difference (-> ctx :env deref :namespaces keys set) ns-before))))
            (when-not (contains? (@!module-namespaces module-id) libname)
              ;; this indicates a limitation in our system for mapping libnames to modules. We rely on
              ;; "prefixes" instead of keeping track of exactly which namespaces are provided by each module.
              (throw
               (ex-info (str libname " was not found in the " module-id " module, despite having the prefix `"
                             (libname-prefix libname) "`.")
                        {:module-id module-id
                         :added (@!module-namespaces module-id)}))))
      (throw (ex-info (str "No module found for " libname) info)))))

(comment
 (load-lib+ 'leva))

