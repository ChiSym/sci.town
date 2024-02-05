(ns maria.cloud.presence
  (:require ["@radix-ui/react-tooltip" :as Tip]
            [applied-science.js-interop :as j]
            [clojure.string :as str]
            [maria.cloud.auth :as auth]
            [maria.cloud.firebase.database :as fdb]
            [maria.ui :as ui]
            [re-db.api :as db]
            [yawn.hooks :as h]
            [yawn.view :as v]))

(defn initials [display-name]
  (let [words (str/split display-name #"\s+")]
    (str/upper-case
      (str/join "" (map first
                        (if (> (count words) 2)
                          [(first words) (last words)]
                          words))))))

(v/defview tooltip-small-black [tip target]
  [:el Tip/Root
   [:el Tip/Trigger {:as-child true} target]
   [:el Tip/Portal
    [:el.bg-gray-700.text-white.p-2.rounded.text-xs.z-30.flex.flex-col.gap-2 Tip/Content
     {:side-offset 4}
     [:el.fill-gray-700 Tip/Arrow]
     tip]]])

(defn time-since-phrase [ts]
  (let [now (js/Date.)
        past (js/Date. ts)
        / (comp Math/round /)
        seconds-past (/ (- now past) 1000)
        pluralized-phrase (fn [n unit]
                            (str n " " unit (when (> n 1) "s") " ago"))]
    (cond
      (< seconds-past 60) (pluralized-phrase seconds-past "second")
      (< seconds-past 3600) (pluralized-phrase (/ seconds-past 60) "minute")
      (< seconds-past 86400) (pluralized-phrase (/ seconds-past 3600) "hour")
      (< seconds-past 2592000) (let [days (/ seconds-past 86400)]
                                 (if (= days 1) "Yesterday" (pluralized-phrase days "day")))
      (< seconds-past 31536000) (pluralized-phrase (/ seconds-past 2592000) "month")
      :else (pluralized-phrase (/ seconds-past 31536000) "year"))))


(ui/defview show-presence-avatars [doc-id]
  ;; TODO
  ;; - on hover, show the full list in a popover
  ;; distinguish between online/offline (:online <boolean>)
  ;; the results here are maps of {:-ts .. :online ..}
  ;; notion: 1st opacity-100, others are staggered, tooltips
  (let [uid (db/get ::auth/user :uid)]
    (when-let [entries (some->> @(fdb/$value [:fire/query [:visitors doc-id] [:orderByChild :-ts]])
                                (remove #(= uid (name (key %))))
                                (take 3))]
      (let [overflow? (> (count entries) 2)]
        [:div.flex.gap-1.items-center
         (doall
           (for [[uid {:keys [ts]}] (cond->> entries
                                             overflow? (take 2))]
             (when-let [{:keys [avatar displayName]} @(fdb/$value [:profile uid])]
               [tooltip-small-black
                [:div displayName
                 [:div.text-gray-400 "Last visited " (time-since-phrase (- ts))]]
                (if avatar
                  [:img.w-5.h-5.rounded {:key uid :src avatar}]
                  [:div.w-6.h-5.rounded.inline-flex.items-center.justify-center.font-semibold.text-zinc-600.bg-zinc-200
                   {:key uid
                    :style {:font-size 11}}
                   (some-> displayName (initials))])])))
         (when overflow?
           [:div
            {:class ["bg-zinc-200 text-zinc-500"
                     "text-xs font-semibold"
                     "w-5 h-5 rounded"
                     "inline-flex items-center justify-center"]}
            (count entries)])]))))

(defn track-doc-presence! [doc-id]
  ;; record this session in the document's presence store
  (let [user-id (db/get ::auth/user :uid)]
    (h/use-effect (fn []
                    (when (and doc-id user-id)
                      ;; a user can have multiple tabs/sessions open, so we track them independently
                      ;; to avoid clobbering state (eg. tab A closes while tab B is still open)
                      (let [ts (- (js/Date.now))
                            start! #(fdb/update+ {[:visitors doc-id user-id :-ts] ts
                                                  [:visitors doc-id user-id :online ts] true
                                                  [:visited user-id doc-id] {:-ts ts}})
                            ts-ref [:visitors doc-id user-id :online ts]
                            end! #(fdb/assoc-in+ ts-ref nil)]
                        (-> (fdb/on-disconnect ts-ref)
                            (j/call :remove))
                        (start!)
                        end!)))
                  [doc-id user-id])))