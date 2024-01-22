(ns maria.editor.extensions.config
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [shadow.lazy :as lazy]
            [shadow.loader]
            [promesa.core :as p]
            [sci.ctx-store :as ctx]))

;; feel free to submit a PR with additional extension config here

(def modules
  "A map of shadow lazy modules to namespace prefixes that they provide."

  ;; our basic approach is to keep a (handwritten) index of modules, along with
  ;; their dependency and provided prefixes (first namespace segment).

  ;; A more principled approach for mapping modules to namespaces would be nice,
  ;; but at build-time we don't know which modules a given
  ;; sci loadable will load. (it depends not on the shadow-cljs module structure
  ;; but rather what happens inside the `install!` function)

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
  (->> modules
       (reduce-kv (fn [out k {:keys [prefixes]}]
                    (reduce (fn [out lib-prefix]
                              (assoc out lib-prefix k))
                            out prefixes)) {})))

(defn ordered-modules [module-id]
  ;; dep + transitive deps, sorted
  (let [dep (get modules module-id)
        deps (set (:depends-on dep))]
    (distinct (concat
                (mapcat ordered-modules deps)
                [module-id]))))

(defn load-module+ [ctx dep-name]
  (p/doseq [module-id (ordered-modules dep-name)]
    ;; install module once per env
    (when-not (contains? (-> ctx :env deref ::modules) module-id)
      (swap! (:env ctx) update ::installed (fnil conj #{}) module-id)
      (p/let [install! (lazy/load (:loadable (modules module-id)))]
        (ctx/with-ctx ctx (install!))))))

(defn libname-prefix [libname]
  (symbol (first (str/split (str libname) #"\."))))

(defn load-lib+ [{:as info :keys [libname ctx]}]
  (let [ns-before (-> ctx :env deref :namespaces keys set)]
    (if-let [module-id (some-> libname libname-prefix prefix->module-id)]
      (p/do (load-module+ ctx module-id)
            (swap! (:env ctx) update-in [::modules module-id]
                   (fn [namespaces]
                     (or namespaces (set/difference (-> ctx :env deref :namespaces keys set) ns-before))))
            (let [module-namespaces (-> (:env ctx) deref ::modules module-id)]
              (when-not (contains? module-namespaces libname)
                (throw
                  (ex-info (str libname " was not found in the " module-id " module, despite having the prefix `"
                                (libname-prefix libname) "`.")
                           {:module-id module-id
                            :module-namespaces module-namespaces})))))
      (throw (ex-info (str "No module found for " libname) info)))))

(comment
  (load-lib+ 'leva))

