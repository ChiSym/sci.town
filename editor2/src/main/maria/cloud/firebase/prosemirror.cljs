(ns maria.cloud.firebase.prosemirror
  (:require ["/maria/cloud/firebase/prosemirror-firebase.js" :refer [FirebaseEditor]]
            ["prosemirror-view" :as p.view]
            ["prosemirror-state" :as p.state]
            [applied-science.js-interop :as j]
            [applied-science.js-interop.alpha :refer [js]]
            [maria.cloud.github :as gh]
            [maria.cloud.firebase.database :as fdb]
            [maria.editor.code.NodeView :as NodeView]
            [maria.editor.code.sci :as sci]
            [maria.editor.prosemirror.schema :as schema]
            [promesa.core :as p]
            [re-db.api :as db]
            [yawn.hooks :as h]))

;; the prosemirror-firebase lib uses Firebase's `runTransaction` feature to serve as the
;; "central authority" to sequence edits from multiple collaborators. If A tries to commit
;; changes but B has committed in the meantime, A's attempt will fail. A will then receive
;; B's changes, commit them locally, and try again.

;; TODO
;; - create a doc, duplicate a doc => this goes into Firebase
;; - list my docs (or any user's docs)
;; - set public/private
;; - invite collaborators, manage collaborators
;; - create a sharing link, manage sharing links

;; - ensure that github, gist, and http links still work
;;   - these are "editable" but persist only to localStorage
;;     "Save...", pick a name, it goes into your profile and is now managed in Firebase.

;; - add "Copy source" and "Copy as Markdown" commands

(defn use-firebase-view [{:keys [id plugins]}]
  (let [!ref (h/use-state nil)
        ref-fn (h/use-callback #(when % (reset! !ref %)))
        !prose-view (h/use-state nil)
        !promises-ref (h/use-ref {})
        firebase-ref (fdb/ref [:prosemirror (fdb/munge id)])
        client-id (db/get ::gh/user :uid)
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
                                   :dispatchTransaction (fn [tx]
                                                          (this-as ^js view
                                                            (let [new-state (.. view -state (apply tx))]
                                                              (.updateState view new-state)
                                                              (updateCollab tx new-state))))})
                                (j/assoc! :!sci-ctx (atom (sci/initial-context)))))})))]
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