(ns maria.cloud.persistence
  (:require ["prosemirror-compress" :as pm-compress]
            ["prosemirror-state$EditorState" :as EditorState]
            [applied-science.js-interop :as j]
            [lambdaisland.glogi :as log]
            [maria.cloud.auth :as auth]
            [maria.cloud.firebase.database :as fdb]
            [maria.cloud.local :as local]
            [maria.cloud.routes :as routes]
            [maria.editor.code.commands :as commands]
            [maria.editor.code.parse-clj :as parse-clj]
            [maria.editor.doc :as doc]
            [maria.editor.keymaps :as keymaps]
            [maria.editor.prosemirror.schema :as schema]
            [maria.editor.util :as u]
            [promesa.core :as p]
            [re-db.api :as db]
            [re-db.reactive :as r]))

(defn title->filename [source]
  (some-> (u/extract-title source)
          u/slug
          (str ".cljs")))

(def state-source (comp doc/doc->clj (j/get :doc)))

(defn swap-name [n f & args]
  (let [ext (re-find #"\..*$" n)
        pre (subs n 0 (- (count n) (count ext)))]
    (str (apply f pre args) ext)))

(defn new-firebase-doc! [& {:as opts :keys [title language content prosemirror/state copy-of]}]
  (when-let [user-id (db/get ::auth/user :uid)]
    (let [checkpoint (when-let [state (or state
                                          (some-> content
                                                  parse-clj/clojure->markdown
                                                  schema/markdown->doc
                                                  (as-> doc (EditorState/create #js{:doc doc}))))]
                       (-> (j/call state :toJSON)
                           pm-compress/compressStateJSON
                           (j/assoc! :k 0 :t fdb/TIMESTAMP)))
          ref (fdb/push [:doc])
          doc-id (j/get ref :key)
          new-doc (merge {[:doc doc-id] {:title (or title "Untitled")
                                         :copy-of copy-of
                                         :owner user-id
                                         :visibility "link"
                                         :provider "prosemirror-firebase"
                                         :created-at fdb/TIMESTAMP}
                          [:roles :by-user user-id doc-id] "admin"
                          [:roles :by-doc doc-id user-id] "admin"}
                         (when checkpoint
                           {[:prosemirror doc-id :checkpoint] checkpoint}))]
      (log/trace "Creating new firebase doc" new-doc)
      (p/do (fdb/update+ new-doc)
            (routes/navigate! 'maria.cloud.views/firebase {:doc/id doc-id})))))

(defn delete-doc! [doc-id]
  (p/let [user-id (db/get ::auth/user :uid)
          user-roles (fdb/once [:roles :by-doc doc-id])
          user-visits (fdb/once [:visitors doc-id])]
    (log/trace "delete doc from users" user-roles)
    (let [updates (merge {[:doc doc-id] nil
                          [:roles :by-doc doc-id] nil
                          [:visitors doc-id] nil
                          [:prosemirror doc-id] nil}
                         (for [user-id (keys user-roles)]
                           [[:roles :by-user user-id doc-id] nil])
                         (for [user-id (keys user-visits)]
                           [[:visited user-id doc-id] nil]))]
      (log/trace "delete doc" (fdb/format-update updates))
      (fdb/update+ updates))))

(defn parse-firebase-doc
  ([[id doc]] (parse-firebase-doc id doc))
  ([id {:keys [title language owner visibility provider created-at]}]
   #:file{:id id
          :title title
          :language language
          :provider (keyword "file.provider" provider)
          :visibility visibility
          :owner owner
          :created-at created-at}))

(defn $doc [id]
  (r/reaction
    (or
      ;; readonly sources are fetched and written to re-db
      (db/get [:file/id id])
      ;; firebase sources are accessed via the fdb/$value subscription
      (some->>
        @(fdb/$value [:doc id])
        (parse-firebase-doc id)))))

(defn $my-docs []
  (when-let [uid (db/get ::auth/user :uid)]
    (-> (fdb/$value [:fire/query [:doc]
                     [:orderByChild :owner]
                     [:equalTo uid]])
        deref
        (->> (mapv parse-firebase-doc)))))

(defn local-changes? [id]
  (let [doc (and id @($doc id))]
    (and (not= :file.provider/prosemirror-firebase (:file/provider doc))
         (let [local-source (:file/local-source @(local/ratom id))
               persisted-source (:file/source doc)]
           (and local-source
                persisted-source
                (not= local-source persisted-source))))))

(keymaps/register-commands!
  {:file/new {:bindings [:Shift-Mod-b]
              :f (fn [_]
                   (u/prevent-default!)
                   (p/do (auth/ensure-sign-in+)
                         (new-firebase-doc!))
                   true)}
   :file/delete {:when (fn [{:keys [file/id]}]
                         (and id
                              (= :file.provider/prosemirror-firebase (:file/provider @($doc id)))))
                 :f (fn [{:keys [file/id]}]
                      (routes/navigate! 'maria.cloud.pages.landing/page)
                      (delete-doc! id))}
   :file/save-a-copy {:when (every-pred :file/id :ProseView)
                      :f (fn [{:keys [ProseView file/id]}]
                           (let [file @($doc id)]
                             (p/do (auth/ensure-sign-in+)
                                   (new-firebase-doc! {:prosemirror/state (j/get ProseView :state)
                                                       :copy-of id
                                                       :title (some->> (:file/title file)
                                                                       (str "Copy of "))}))
                             true))}
   :file/revert {:when (comp local-changes? :file/id)
                 :f (fn [{:keys [file/id ProseView]}]
                      (j/let [source (:file/source @($doc id))]
                        (reset! (local/ratom id) nil)
                        (commands/prose:replace-doc ProseView source)))}
   :file/copy-source {:doc "Copy this doc's source code to clipboard"
                      :when :ProseView
                      :f (fn [{:keys [ProseView]}]
                           (j/call-in js/navigator [:clipboard :writeText]
                                      (state-source (j/get ProseView :state)))
                           true)}})