(ns maria.cloud.sidebar
  (:require ["@radix-ui/react-accordion" :as acc]
            ["@radix-ui/react-dropdown-menu" :as Dropdown]
            [maria.cloud.auth :as auth]
            [maria.cloud.firebase.database :as fdb]
            [maria.cloud.persistence :as persist]
            [maria.cloud.routes :as routes]
            [maria.editor.icons :as icons]
            [maria.ui :as ui]
            [re-db.api :as db]
            [re-db.hooks :as hooks]
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

(def acc-item (v/from-element :a.block
                              {:class ["text-sm text-black no-underline flex gap-1"
                                       "pr-2 pl-4 mx-1 h-7 truncate items-center rounded cursor-default"
                                       "hover:bg-black/5 hover:text-black visited:text-black"
                                       "data-[selected=true]:bg-sky-500 data-[selected=true]:text-white"]}))

(defn acc-props [current? props]
  (merge {:data-selected current?}
         props))

(ui/defview acc-section [title items]
  [:> acc/Item
   {:value title
    :class ui/c:divider}
   [:> acc/Header
    {:class "flex flex-row h-[40px] m-0 group "}
    [:> acc/Trigger {:class "text-sm font-bold p-2 AccordionTrigger flex-grow"}
     [icons/chevron-right:mini "w-4 h-4 -ml-1 AccordionChevron text-gray-500 group-hover:text-black"]
     title]]
   (into [:el.flex.flex-col.gap-1 acc/Content] items)])

(defn file->path [{:as file :keys [file/provider]}]
  (when file
    (case provider
      :file.provider/prosemirror-firebase (routes/path-for 'maria.cloud.views/firebase {:doc/id (:file/id file)})
      :file.provider/curriculum (routes/path-for 'maria.cloud.views/curriculum file))))

(ui/defview recently-viewed [current-path]
  (when-let [user-id (db/get ::auth/user :uid)]
    (when-let [visited @(fdb/$value [:fire/query [:visited user-id]
                                     [:orderByChild :-ts]])]
      [acc-section "Recently Viewed"
       (doall
         (for [[id foo] (sort-by (comp :-ts val) visited)
               :let [doc (or @(persist/$doc id)
                             (db/get [:file/id (fdb/demunge (name id))]))]
               :when doc
               :let [path (file->path doc)]]
           [acc-item (acc-props (= current-path path)
                                {:key id
                                 :href path})
            (:file/title doc)]))])))

(defn dropdown [trigger & items]
  (v/x [:el Dropdown/Root
        [:el Dropdown/Trigger {:as-child true} trigger]
        [:el Dropdown/Portal
         (into [:el.rounded.bg-white.p-2 Dropdown/Content] items)]]))

(defn dropdown-item [{:keys [href on-click label]}]
  [:el Dropdown/Item {:as-child true}
   [(if href :a :div)
    {:href href :on-click on-click}
    label]])

(ui/defview my-docs [current-path]
  (when-let [docs (seq (persist/$my-firebase-docs))]
    [acc-section "My Docs"
     (for [{:file/keys [id title]} docs
           :let [href (routes/path-for 'maria.cloud.views/firebase {:doc/id id})]]
       [acc-item {:href href
                  :key id}
        title]
       #_[dropdown
          [acc-item {:href href
                     :key id}
           title]
          [dropdown-item {:on-click #(do
                                       (when (= href current-path)
                                         (routes/navigate! 'maria.cloud.pages.landing/page))
                                       (persist/delete-doc! id))}]])]))

(ui/defview curriculum-list [current-path]
  [acc-section "Curriculum"
   (map (fn [{:as m
              :keys [curriculum/name
                     file/hash
                     file/title]}]
          (let [path (routes/path-for 'maria.cloud.views/curriculum
                                      {:curriculum/name name
                                       :query {:v hash}})
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
      [:div.flex.items-center.px-3.icon-zinc
       {:on-click #(swap! ui/!state assoc :sidebar/visible? false)
        :style {:margin-top 3}}
       [icons/x-mark:mini "w-5 h-5 rotate-180"]]
      [:div.flex-grow]]
     [my-docs current-path]
     [recently-viewed current-path]
     [curriculum-list current-path]]))