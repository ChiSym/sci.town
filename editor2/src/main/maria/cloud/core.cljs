(ns maria.cloud.core
  (:require ["@radix-ui/react-tooltip" :as Tip]
            [applied-science.js-interop :as j]
            [clojure.edn :as edn]
            [maria.clerkify]
            [maria.cloud.auth :as auth]
            [maria.cloud.firebase.database :as fdb]
            [maria.cloud.menubar :as menu]
            [maria.cloud.persistence]
            [maria.cloud.routes :as routes]
            [maria.cloud.sidebar :as sidebar]
            [maria.cloud.views]
            [maria.editor.code.docbar :as docbar]
            [maria.editor.code.docbar]
            [maria.editor.keymaps :as keymaps]
            [maria.scratch]
            [maria.ui :as ui :refer [defview]]
            [re-db.api :as db]
            [re-db.reactive :as r]
            [yawn.hooks :as h]
            [yawn.root :as root]
            [yawn.view :as v]
            [lambdaisland.glogi :as log]
            [lambdaisland.glogi.console :as glogi-console]))

(glogi-console/install!)

(log/set-levels '{:glogi/root :info
                  maria.cloud.firebase.database :trace
                  maria.cloud.persistence :trace})
;; TODO
;; - UI for sidebar,
;; - support per-attribute local-state persistence
;; - include curriculum as a re-db transaction,
;; -

(defn get-scripts [type]
  (->> (js/document.querySelectorAll (str "[type='" type "']"))
       (map (comp edn/read-string (j/get :innerHTML)))))

(defn init-re-db []
  (doseq [schema (get-scripts "application/re-db:schema")]
    (db/merge-schema! schema))
  (doseq [tx (get-scripts "application/re-db:tx")]
    (db/transact! tx)))

(defview root []
  [:el Tip/Provider {:delay-duration 50}
   (let [{:as location ::routes/keys [view]} (h/use-deref routes/!location)]
     (keymaps/use-global-keymap)
     (ui/provide-context {::menu/!content @(h/use-state #(r/atom nil))}
       (when @auth/!initialized?
         [:div.h-screen
          {:on-click #(when (= (j/get % :target)
                               (j/get % :currentTarget))
                        (keymaps/run-command :editor/focus!))}
          [sidebar/with-sidebar
           [sidebar/content]
           [:Suspense {:fallback [:div "Loading..."]}
            [:div
             [menu/menubar]
             (when view
               [view location])]]]
          [docbar/view]])))])

(defn ^:export init []
  (init-re-db)
  (auth/init)
  (routes/init)
  (root/create :sci-town (v/<> [root])))

(comment
  (init))
