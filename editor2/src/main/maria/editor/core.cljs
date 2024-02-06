(ns maria.editor.core
  (:require ["prosemirror-view" :as p.view]
            ["prosemirror-state" :as p.state]
            ["prosemirror-history" :refer [history]]
            ["prosemirror-dropcursor" :refer [dropCursor]]
            ["prosemirror-gapcursor" :refer [gapCursor]]
            ["prosemirror-schema-list" :as cmd-list]
            ["react" :as react]
            ["react-dom" :as react-dom]
            [applied-science.js-interop :as j]
            [applied-science.js-interop.alpha :refer [js]]
            [maria.cloud.firebase.database :as fdb]
            [maria.cloud.github :as gh]
            [maria.cloud.menubar :as menu]
            [maria.cloud.persistence :as persist]
            [maria.cloud.routes :as routes]
            [maria.editor.code.NodeView :as NodeView]
            [maria.editor.code.commands :as commands]
            [maria.editor.code.parse-clj :as parse-clj :refer [clojure->markdown]]
            [maria.editor.code.sci :as sci]
            [maria.editor.keymaps :as keymaps]
            [maria.editor.prosemirror.input-rules :as input-rules]
            [maria.editor.prosemirror.links :as links]
            [maria.editor.prosemirror.schema :as schema]
            [maria.editor.util :as u]
            [maria.ui :as ui]
            [re-db.api :as db]
            [yawn.hooks :as h]
            [yawn.view :as v]))

(defonce focused-state-key (new p.state/PluginKey "focused-state"))

(defn focused-state-plugin []
  (js
    (new p.state/Plugin {:key focused-state-key
                         :state {:init (fn [] false)
                                 :apply (fn [transaction prev-focus]
                                          (if-some [new-focus (.getMeta transaction focused-state-key)]
                                            new-focus
                                            prev-focus))}
                         :props {:handleDOMEvents
                                 {:blur
                                  (fn [view]
                                    (.dispatch view (.. view -state -tr (setMeta focused-state-key false)))
                                    false)
                                  :focus
                                  (fn [view]
                                    (.dispatch view (.. view -state -tr (setMeta focused-state-key true)))
                                    false)}}})))


(defn selection-decoration []
  (new p.state/Plugin (js
                        {:props
                         {:decorations
                          (fn [{:as ProseState :keys [doc] {:keys [from to]} :selection}]
                            (if (.getState focused-state-key ProseState)
                              (.-empty p.view/DecorationSet)
                              (.create p.view/DecorationSet doc
                                       [(.inline p.view/Decoration from to {:class "bg-selection"})])))}})))

(js
  (defn plugins []
    [keymaps/prose-keymap
     keymaps/default-keys
     input-rules/maria-rules
     (dropCursor)
     (gapCursor)
     (history)
     ~@(links/plugins)
     (focused-state-plugin)
     (selection-decoration)]))

(defonce !mounted-view (atom nil))

(def clj->doc
  "Returns a ProseMirror Markdown doc representing Clojure source code."
  (comp schema/markdown->doc parse-clj/clojure->markdown))

(defn doc->cells
  "Returns vector of code cells in a doc."
  [^js doc]
  (into []
        (comp (filter #(some->> (j/get-in % [:attrs :params])
                                (re-find #"^clj")))
              (map (j/get :textContent)))
        (j/get-in doc [:content :content])))

(ui/defview show-avatar [uid]
  (when-let [{:as foo :keys [avatar displayName]} (first (fdb/use-map [:profile uid]))]
    [:img.w-5.h-5.rounded {:key uid :src avatar :title displayName}]))

(ui/defview show-presence-avatars [doc-id]
  ;; TODO
  ;; - on hover, show the full list in a popover
  ;; - if user has no avatar, show initials
  (when-let [uids (some->> (fdb/use-map [:presence (fdb/munge doc-id)])
                           first
                           (sort-by (fn [a b] (compare b a)) val)
                           (map (comp name key))
                           ;; comment this to show current user, for testing
                           (remove #{(db/get ::gh/user :uid)})
                           (take 3))]
    (let [overflow? (> (count uids) 2)
          show-uids (if overflow?
                      (take 2 uids)
                      uids)]
      [:div.flex.gap-1.items-center
       (doall (map show-avatar show-uids))
       (when overflow?
         [:div
          {:class ["bg-zinc-200 text-zinc-500"
                   "text-xs font-semibold"
                   "w-5 h-5 rounded"
                   "inline-flex items-center justify-center"]}
          (count uids)])])))

(defn use-presence-tracking! [doc-id]
  ;; record this session in the document's presence store
  (let [uid (db/get ::gh/user :uid)
        doc-id (some-> doc-id fdb/munge)]
    (h/use-effect (fn []
                    (when (and doc-id uid)
                      ;; a user can have multiple tabs/sessions open, so we track them independently
                      ;; to avoid clobbering state (eg. tab A closes while tab B is still open)
                      ;; {:presence {<doc-id> {<user-id> {<timestamp> true}}}}
                      (let [ref (fdb/ref [:presence doc-id uid (js/Date.now)])]
                        (-> (fdb/on-disconnect ref)
                            (j/call :remove))
                        (fdb/assoc-in! ref true)
                        #(fdb/assoc-in! ref nil))))
                  [doc-id uid])))

(defn use-doc-menu-content [doc-id]
  (let [!content (ui/use-context ::menu/!content)]
    (h/use-effect
      (fn []
        (let [content (v/x
                        [:<>
                         [menu/doc-menu doc-id]
                         [show-presence-avatars doc-id]])]
          (reset! !content content)
          #(swap! !content (fn [x]
                             (if (identical? x content)
                               nil
                               x)))))
      [doc-id])))

(defn use-prose-view [{:keys [default-value on-change-state]} deps]
  (let [!ref (h/use-state nil)
        ref-fn (h/use-callback #(when % (reset! !ref %)))
        !prose-view (h/use-state nil)
        make-prose-view (fn [element]
                          (js (-> (p.view/EditorView. {:mount element}
                                                      {:state (js (.create p.state/EditorState
                                                                           {:doc (clj->doc default-value)
                                                                            :plugins (plugins)}))
                                                       :nodeViews {:code_block NodeView/editor}
                                                       #_#_:handleDOMEvents {:blur #(js/console.log "blur" %1 %2)}
                                                       ;; no-op tx for debugging
                                                       :dispatchTransaction (fn [tx]
                                                                              (this-as ^js view
                                                                                (let [prev-state (.-state view)
                                                                                      next-state (.apply prev-state tx)]
                                                                                  (.updateState view next-state)
                                                                                  (when on-change-state
                                                                                    (on-change-state prev-state next-state)))))})
                                  (j/assoc! :!sci-ctx (atom (sci/initial-context))))))]
    (h/use-effect
      (fn []
        (if-let [element (and default-value @!ref)]
          (let [ProseView (make-prose-view element)]
            (reset! !prose-view ProseView)
            #(j/call ProseView :destroy))
          (reset! !prose-view nil)))
      (conj deps @!ref))
    [@!prose-view ref-fn]))

(ui/defview editor* [params {:as file :keys [file/id]}]
  "Returns a ref for the element where the editor is to be mounted."

  (persist/use-persisted-file file)
  (use-presence-tracking! id)
  (use-doc-menu-content id)
  (persist/use-recents! (::routes/path params) file)

  (let [autosave! (h/use-memo persist/autosave-local-fn)
        [ProseView ref-fn] (use-prose-view {:default-value (or (:file/source @(persist/local-ratom id))
                                                               (:file/source file)
                                                               "")
                                            :on-change-state (fn [prev-state next-state]
                                                               (autosave! id prev-state next-state))}
                                           [])]

    ;; initialize new editors
    (h/use-effect
      (fn []
        (when (some-> ProseView (u/guard (complement (j/get :isDestroyed))))

          (keymaps/add-context :ProseView ProseView)
          (commands/prose:eval-prose-view! ProseView)
          (j/call ProseView :focus)))
      [ProseView])

    ;; set file/id context
    (h/use-effect (fn []
                    (keymaps/add-context :file/id id)
                    #(keymaps/remove-context :file/id id)))
    [:div.relative.notebook.my-4 {:ref ref-fn}]))

(ui/defview editor
  {:key (fn [params file] (:file/id file))}
  [params file]
  (if file
    [editor* params file]
    "Loading..."))