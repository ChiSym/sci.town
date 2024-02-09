(ns maria.cloud.firebase.prosemirror
  (:require ["/maria/cloud/firebase/prosemirror-firebase.js" :refer [FirebaseEditor]]
            ["prosemirror-view" :as p.view]
            ["prosemirror-state" :as p.state]
            [applied-science.js-interop :as j]
            [applied-science.js-interop.alpha :refer [js]]
            [maria.cloud.auth :as auth]
            [maria.cloud.firebase.database :as fdb]
            [maria.editor.code.NodeView :as NodeView]
            [maria.editor.code.sci :as sci]
            [maria.editor.prosemirror.schema :as schema]
            [maria.cloud.persistence :as persist]
            [maria.editor.util :as u]
            [promesa.core :as p]
            [re-db.api :as db]
            [yawn.hooks :as h]))

;; the prosemirror-firebase lib uses Firebase's `runTransaction` feature to serve as the
;; "central authority" to sequence edits from multiple collaborators. If A tries to commit
;; changes but B has committed in the meantime, A's attempt will fail. A will then receive
;; B's changes, commit them locally, and try again.

(defn infer-title! [prose-view id]
  (try
    (when (= "Untitled" (:doc/title @(persist/$doc id)))
      (when-let [title (-> prose-view
                           (j/get :state)
                           persist/state-source
                           u/extract-title)]
        (fdb/assoc-in+ [:doc id :title] title)
        true))
    (catch js/Error e)))

(defn use-firebase-view [{:keys [doc/id plugins]}]
  (let [!ref (h/use-state nil)
        ref-fn (h/use-callback #(when % (reset! !ref %)))
        !prose-view (h/use-state nil)
        !promises-ref (h/use-ref {})
        !has-title? (h/use-ref nil)
        firebase-ref (fdb/ref [:prosemirror id])
        ;; client-id should be unique per tab but include the user's id
        client-id (str (db/get ::auth/user :uid) ":" (js/Date.now))
        make-prose-view
        (fn [element]
          (js (new FirebaseEditor
                   {:firebaseRef firebase-ref
                    :stateConfig {:schema schema/schema
                                  :plugins plugins}
                    :clientId client-id
                    :view (fn [{:keys [stateConfig updateCollab selections]}]
                            ;; TODO
                            ;; show selections https://github.com/xylk/prosemirror-firebase/tree/master#cursors-and-selections
                            (-> (p.view/EditorView.
                                  {:mount element}
                                  {:state (.create p.state/EditorState stateConfig)
                                   :nodeViews {:code_block NodeView/editor}
                                   :handleKeyDown (fn [view event]
                                                    (when (and (= "Enter" (j/get event :key))
                                                               (not @!has-title?))
                                                      (reset! !has-title? (infer-title! view id)))
                                                    js/undefined)
                                   :dispatchTransaction (fn [tx]
                                                          (this-as ^js view
                                                            (let [new-state (.. view -state (apply tx))]
                                                              (.updateState view new-state)
                                                              (updateCollab tx new-state))))})
                                (j/assoc! :!sci-ctx (atom (sci/initial-context)))))})))]

    (h/use-effect
      ;; infer a title for Untitled docs on close
      (fn []
        (when @!prose-view
          #(infer-title! @!prose-view id)))
      [@!prose-view])

    (h/use-effect
      (fn []
        (if-let [element @!ref]
          (do (swap! !promises-ref assoc id (make-prose-view element))
              (p/let [editor (get @!promises-ref id)]
                (reset! !prose-view (j/get editor :view)))
              #(p/let [editor (@!promises-ref id)]
                 (swap! !promises-ref dissoc id)
                 (j/call editor :destroy)))
          (reset! !prose-view nil)))
      [@!ref id])
    [@!prose-view ref-fn]))