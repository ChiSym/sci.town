(ns maria.cloud.persistence.github
  (:require [applied-science.js-interop :as j]
            [clojure.string :as str]
            [maria.editor.util :as u]
            [promesa.core :as p]))

(j/defn parse-gist [^js {:keys [id
                                description
                                files
                                html_url
                                updated_at]
                         {:keys [login]} :owner}]
  (when-let [[file] (some->> files
                             (js/Object.values)
                             (keep (j/fn [^js {:keys [filename language content]}]
                                         (when (= language "Clojure")
                                               {:doc/id (str "gist:" id) ;; ":filename"
                                                :doc/title (or (some-> description
                                                                       str/trim
                                                                       (str/split-lines)
                                                                       first
                                                                       (u/guard (complement str/blank?)))
                                                               filename)
                                                :doc/name filename
                                                :doc/language language
                                                :doc/source content
                                                :doc/provider :doc.provider/gist})))
                             seq)]
            (merge file
                   {:gist/id id
                    :gist/owner login
                    :gist/description description
                    :gist/html_url html_url
                    :gist/updated_at updated_at})))

(defn gh-fetch [url options]
      (p/-> (js/fetch url (clj->js (-> options
                                       (u/update-some {:body (comp js/JSON.stringify clj->js)})
                                       (update :headers merge
                                               {:X-GitHub-Api-Version "2022-11-28"
                                                :accept "application/vnd.github+json"}))))
            (j/call :json)
            (clj->js :keywordize-keys true)))