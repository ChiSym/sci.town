(ns maria.cloud.views
  (:require [applied-science.js-interop :as j]
            [clojure.string :as str]
            [maria.cloud.routes :as routes]
            [maria.editor.core :as editor.core]
            [maria.editor.util :as u]
            [maria.ui :as ui]
            [promesa.core :as p]
            [re-db.api :as db]
            [re-db.reactive :as r]
            [yawn.hooks :as h]
            [yawn.view :as y]
            [maria.cloud.persistence :as persist]
            [maria.editor.code.show-values :as show]
            [maria.cloud.firebase.database :as fdb]
            [maria.cloud.persistence.github :as gh]))


(comment
  (j/defn prose:eval-clojure! [ctx ^js doc]
    (let [sources (doc->cells doc)]
      (vreset! (:last-ns ctx) (sci.ns/sci-find-ns ctx 'user))
      (reduce (fn [out source]
                (p/let [out out
                        v (sci/eval-string ctx source)
                        value (:value v)]
                  (conj out (assoc v :value value))))
              []
              sources)))
  ;; this was one approach to "exporting" a cell as a webview.
  ;; would rather try something else: let a user 'maximize' a cell
  ;; and then share a link to it (retaining possibility of minimizing it)
  (if-let [eval (:eval (::routes/query-params params))]
    [show-expr source eval])
  (ui/defview show-expr [source expr]
    (let [ctx (h/use-memo maria.editor.code.sci/initial-context)
          result (u/use-promise #(p/let [results (editor.core/prose:eval-clojure!
                                                   ctx
                                                   (editor.core/clj->doc (str source
                                                                              \newline
                                                                              expr)))]
                                   (last results))
                                [source expr])]
      [show/show {:sci/context ctx
                  :sci/get-ns #(deref (:last-ns ctx))}
       (or (:error result)
           (:value result))])))

(defonce !r (r/atom "foo"))

(y/defview curriculum
  [{:as params :curriculum/keys [name]}]
  (let [{:as file
         :keys [file/id
                file/url]} (db/get [:curriculum/name name])
        file (u/use-promise #(when url
                               (p/let [source (p/-> (u/fetch url)
                                                    (j/call :text))]
                                 (db/transact! [[:db/add [:file/id id] :file/source source]])
                                 (assoc file :file/source source)))
                            [id])]
    [editor.core/editor params file]))

(ui/defview gist
  {:key :gist/id}
  [{:as params gist-id :gist/id}]
  (let [file (u/use-promise #(p/-> (u/fetch (str "https://api.github.com/gists/" gist-id))
                                   (j/call :json)
                                   gh/parse-gist
                                   (fn [file] (db/transact! [file])))
                            [gist-id])]
    [editor.core/editor params file]))

(ui/defview firebase
  {:key :doc/id}
  [{:as params :keys [doc/id]}]
  [editor.core/editor params @(persist/$doc id)])

(ui/defview http-text [{:as params :keys [http-text/url]}]
  (let [url (cond-> url
                    (str/includes? url "%2F")
                    (js/decodeURIComponent))
        url (cond->> url
                     (not (str/starts-with? url "http"))
                     (str "https://"))
        file (u/use-promise #(p/let [source (p/-> (u/fetch url)
                                                  (j/call :text))
                                     file  {:file/id url
                                            :http-text/url url
                                            :file/source source}]
                               (db/transact! [file])
                               file)
                            [url])]
    [editor.core/editor params file]))
