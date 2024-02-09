(ns maria.cloud.sidebar
  (:require ["@radix-ui/react-accordion" :as acc]
            [maria.cloud.auth :as auth]
            [maria.cloud.firebase.database :as fdb]
            [maria.cloud.persistence :as persist]
            [maria.cloud.routes :as routes]
            [maria.editor.icons :as icons]
            [maria.ui :as ui]
            [re-db.api :as db]
            [re-db.hooks :as hooks]
            [yawn.hooks :as h]
            [yawn.view :as v]))

(defn sidebar-width []
  (let [{:sidebar/keys [visible? width]} @ui/!state]
    (if visible? width 0)))

(ui/defview with-sidebar [sidebar content]
  (let [{:sidebar/keys [visible? width]} (hooks/use-deref ui/!state)]
    [:div
     {:style {:padding-left (if visible? width 0)
              :transition ui/sidebar-transition}}
     [:div.fixed.top-0.bottom-0.bg-white.rounded.z-10.drop-shadow-md.divide-y.overflow-hidden.border-r.border-zinc-100.flex
      {:style {:width width
               :transition ui/sidebar-transition
               :left (if visible? 0 (- width))}}
      [:div.flex-grow.overflow-y-auto sidebar]]
     content]))

(defn acc-props [current? props]
  (merge {:data-selected current?}
         props))

(def acc-item (v/from-element :a.block
                              {:class ["text-sm text-black no-underline flex gap-1 last:mb-1"
                                       "pr-2 pl-5 mx-1 h-7 truncate items-center rounded cursor-default"
                                       "hover:bg-black/5 hover:text-black visited:text-black"
                                       "data-[selected=true]:bg-sky-500 data-[selected=true]:text-white"]}))

(defn use-acc-limit [limit items]
  (let [!expanded? (h/use-state false)]
    (if (and (not @!expanded?)
             (> (count items) limit))
      (concat (take limit items)
              [[acc-item {:on-click #(swap! !expanded? true)} [icons/ellipsis:mini "text-zinc-400 w-4 h-4"]]])
      items)))

(ui/defview acc-section [{:keys [title
                                 limit]
                          :or {limit 20}} items]
  [:> acc/Item
   {:value title
    :class ui/c:divider}
   [:> acc/Header
    {:class "flex flex-row h-[40px] mt-0 group "}
    [:> acc/Trigger {:class "text-sm font-bold AccordionTrigger flex-grow flex items-center"}
     [icons/chevron-right:mini "mx-1 w-4 h-4 flex items-center justify-center AccordionChevron text-gray-500 group-hover:text-black"]
     title]]
   (into [:el.flex.flex-col.gap-1 acc/Content] (use-acc-limit limit items))])

(defn file->path [{:as file :keys [doc/provider]}]
      (when file
            (case provider
      :doc.provider/prosemirror-firebase (routes/path-for 'maria.cloud.views/firebase {:doc/id (:doc/id file)})
      :doc.provider/curriculum (routes/path-for 'maria.cloud.views/curriculum file)
      :doc.provider/gist (routes/path-for 'maria.cloud.views/gist file)
      :doc.provider/http-text (routes/path-for 'maria.cloud.views/http-text file))))

(ui/defview recently-viewed [current-path]
            (when-let [user-id (db/get ::auth/user :uid)]
                      (when-let [visited @(fdb/$value [:fire/query [:visited user-id]
                                     [:orderByChild :-ts]])]
                                [acc-section {:title "Recently Viewed" :limit 3}
       (doall
         (for [[id foo] (sort-by (comp :-ts val) visited)
               :let [doc @(persist/$doc id)]
               :when doc
               :let [path (file->path doc)]]
              [acc-item (acc-props (= current-path path)
                                {:key id
                                 :href path})
            (:doc/title doc)]))])))

(ui/defview my-docs [current-path]
  (when-let [docs (seq (persist/$my-docs))]
    [acc-section {:title "My Docs"}
     (for [{:keys [:doc/id :doc/title]} docs
           :let [href (routes/path-for 'maria.cloud.views/firebase {:doc/id id})]]
       [acc-item {:href href
                  :key id}
        title])]))

(ui/defview curriculum-list [current-path]
  [acc-section {:title "Curriculum"}
   (map (fn [{:as m
              :keys [curriculum/name
                     doc/url
                     doc/title]}]
          (let [path (routes/path-for 'maria.cloud.views/curriculum
                                      {:curriculum/name name})
                current? (= path current-path)]
            (v/x [acc-item
                  (acc-props current?
                             {:key name
                              :href path})
                  title])))
        (db/where [:curriculum/name]))])

(ui/defview content []
  (let [{current-path ::routes/path} @routes/!location]
    [:> acc/Root {:type "multiple"
                  :value (-> @ui/!state :sidebar/open to-array)
                  :on-value-change #(swap! ui/!state assoc :sidebar/open (set %))
                  :class "relative"}

     [:div {:class "flex flex-row h-[40px] items-stretch border-b border-zinc-100"}
      [:div.flex.items-center.pl-1.pr-3.icon-zinc
       {:on-click #(swap! ui/!state assoc :sidebar/visible? false)
        :style {:margin-top 3}}
       [icons/x-mark:mini "w-4 h-4 rotate-180"]]
      [:div.flex-grow]]
     [recently-viewed current-path]
     [my-docs current-path]
     [curriculum-list current-path]]))