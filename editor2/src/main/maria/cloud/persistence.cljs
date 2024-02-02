(ns maria.cloud.persistence
  (:require [applied-science.js-interop :as j]
            [goog.functions :as gf]
            [lambdaisland.glogi :as log]
            [maria.cloud.auth :as auth]
            [maria.cloud.firebase.database :as fdb]
            [maria.cloud.local :as local]
            [maria.cloud.local-sync :as local-sync]
            [maria.cloud.routes :as routes]
            [maria.editor.code.commands :as commands]
            [maria.editor.doc :as doc]
            [maria.editor.keymaps :as keymaps]
            [maria.editor.util :as u]
            [promesa.core :as p]
            [re-db.api :as db]
            [re-db.memo :as memo]
            [re-db.reactive :as r]
            [yawn.hooks :as h]))

(defn title->filename [source]
  (some-> (u/extract-title source)
          u/slug
          (str ".cljs")))

(def entity-ratom
  (memoize
    (fn [db-id]
      (reify
        IDeref
        (-deref [o] (db/get db-id))
        ISwap
        (-swap! [o f] (reset! o (f @o)))
        (-swap! [o f a] (reset! o (f @o a)))
        (-swap! [o f a b] (reset! o (f @o a b)))
        (-swap! [o f a b xs] (reset! o (apply f @o a b xs)))
        IReset
        (-reset! [o new-value]
          (let [prev-value @o]
            (db/transact! (into [(assoc new-value :db/id (:db/id prev-value db-id))]
                                (for [[k v] (dissoc prev-value :db/id)
                                      :when (not (contains? new-value k))]
                                  [:db/retract db-id k v])))
            @o))))))

(defn local-ratom [id]
  (local-sync/sync-entity! id)
  (entity-ratom (local-sync/db-id id)))

(defn readonly-ratom [id]
  (entity-ratom [:file/id id]))

(def state-source (comp doc/doc->clj (j/get :doc)))

(comment
  ;; new local file
  (let [id (str (random-uuid))]
    (reset! (local-ratom id)
            {:file/source (or source "")
             :file/name (or name (title->filename source))})
    (routes/navigate! 'maria.cloud.views/local {:local/id id})
    true))

(defn current-file [id]
  (merge @(readonly-ratom id)
         @(local-ratom id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Keep track of recently viewed files


(defn changes [id]
  (when id
    (let [readonly @(readonly-ratom id)
          local @(local-ratom id)]
      (not-empty
        (into {}
              (keep (fn [k]
                      (let [before (k readonly)
                            after (k local)]
                        (when (and after (not= before after))
                          [k [before after]]))))
              [:file/name
               :file/source])))))

(defn autosave-local-fn
  "Returns a callback that will save the current doc to local storage after a 1s debounce."
  []
  (-> (fn [id ^js prev-state ^js next-state]
        (when-not (.eq (.-doc prev-state) (.-doc next-state))
          (swap! (local-ratom id) assoc :file/source (state-source next-state))))
      (gf/debounce 100)))

(defn swap-name [n f & args]
  (let [ext (re-find #"\..*$" n)
        pre (subs n 0 (- (count n) (count ext)))]
    (str (apply f pre args) ext)))

(defn firebase-doc? [id]
  (= :file.provider/prosemirror-firebase (:file/provider (current-file id))))

(defn use-readonly-file
  "Syncs readonly file to re-db"
  [{:as file :file/keys [id source provider]}]
  (h/use-effect (fn []
                  (when (not= :file.provider/local provider)
                    (reset! (readonly-ratom id) file)))
                [id source]))

(defn new-firebase-doc! [& {:keys [title language content]}]
  ;; TODO
  ;; - figure out how to put content into firebase doc
  (when-let [user-id (db/get ::auth/user :uid)]
    (let [ref (fdb/push [:doc])
          doc-id (j/get ref :key)
          new-doc {[:doc doc-id] {:title (or title "Untitled")
                                  :owner user-id
                                  :visibility "private"
                                  :provider "prosemirror-firebase"
                                  :created-at fdb/TIMESTAMP}
                   [:roles :by-user user-id doc-id] "admin"
                   [:roles :by-doc doc-id user-id] "admin"}]
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
  ;; todo - check for  (db/get [:file/id (fdb/demunge (name id))]) first
  (r/reaction
    (some->>
      @(fdb/$value [:doc id])
      (parse-firebase-doc id))))

(defn $my-firebase-docs []
  (when-let [uid (db/get ::auth/user :uid)]
    (-> (fdb/$value [:fire/query [:doc]
                     [:orderByChild :owner]
                     [:equalTo uid]])
        deref
        (->> (mapv parse-firebase-doc)))))

;; TODO
;; X use memo/defn-memo for reading from firebase (avoid hooks!)
;; X only allow changing file title for firebase docs.
;; X handle changes to doc names on blur
;; X parse-firebase-doc into common file/* format for use in editor
;; - read gists using public url (plain http) if possible
;; - clarify the supported persistence modes and how they interact
;;   - show gist titles in the menubar
;;   - get rid of "persisted-ratom"? fetch persisted state via use-promise and pass down the tree.
;;     - local-ratom can still be used to persist local changes.
;; - do the extract-filename thing when moving out of a code cell in an untitled doc?
;; - create new firebase doc with existing content. then,
;;   - duplicate/make-a-copy/fork/remix = create a new firebase doc with the same content, add a "forked-from" key to doc/meta.
;; - when viewing a readonly doc,
;;   - start by persisting locally,
;;   - show "Local changes" in menu,
;;   - allow saving to firebase


;; Behaviour Design
;;
;;
;;

(keymaps/register-commands!
  {:file/new {:bindings [:Shift-Mod-b]
              :f (fn [_]
                   (u/prevent-default!)
                   (new-firebase-doc!)
                   true)}
   :file/delete {:f (fn [{:keys [file/id]}]
                      (routes/navigate! 'maria.cloud.pages.landing/page)
                      (delete-doc! id))}
   :file/duplicate {:when (every-pred :file/id :ProseView)
                    ;; create a new gist with contents of current doc.
                    :f (fn [{:keys [ProseView file/id]}]
                         (let [source (state-source (j/get ProseView :state))]
                           (new-firebase-doc! {:file/source source
                                               :file/name (some-> (:file/name (current-file id))
                                                                  (swap-name (partial str "copy_of_")))})))}
   :file/revert {:when (comp seq changes :file/id)
                 :f (fn [{:keys [file/id ProseView]}]
                      (j/let [source (:file/source @(readonly-ratom id))]
                        (reset! (local-ratom id) nil)
                        (commands/prose:replace-doc ProseView source)))}
   :file/copy-source {:when :ProseView
                      :f (fn [{:keys [ProseView]}]
                           (j/call-in js/navigator [:clipboard :writeText]
                                      (state-source (j/get ProseView :state)))
                           true)}
   :file/save {:bindings [:Ctrl-s]
               :when (fn [{:file/keys [id provider]}]
                       (and id (not (firebase-doc? id))))
               ;; if local, create a new firebase doc and then navigate there.
               :f (fn [{:keys [file/id]}]
                    (new-firebase-doc! (current-file id)))}})