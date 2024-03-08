(ns studio.inspector
  (:require [applied-science.js-interop :as j]
            [maria.ui :as ui]
            [re-db.hooks :as rh]
            [re-db.memo :as memo :refer [defn-memo]]
            [re-db.reactive :as r]
            [yawn.hooks :as h]
            [yawn.root :as root]
            [yawn.view :as v]
            ["msgpack-lite" :as msgpack]
            [promesa.core :as p]))

(defonce session-id (str (random-uuid)))

;; a list of all messages received from the server
(defonce !messages (r/atom ()))

;; a map of producers connected to the server
(defonce !producers (r/atom {}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Websocket wrapper

(defn send
  "Sends message to !ws if connected, otherwise stores it in :to-send"
  [!ws message]
  (let [{:keys [connected? ^js ws]} @!ws]
    (if connected?
      (.send ws (msgpack/encode (clj->js message)))
      (swap! !ws update :to-send (fnil conj []) message))))

(defn close
  "Closes websocket connection, or marks as canceled if not yet connected"
  [!ws]
  (.close ^js (:ws @!ws)))

(defonce !counter (atom 0))

(defn make-!ws [url & {:keys [on-message]}]
  (let [^js ws (js/WebSocket. url)
        !ws (r/reaction {:on-dispose close
                         :meta {:dispose-delay 100}}
                        {:ws ws
                         :to-send []})]
    (j/!set ws
            :onopen
            #(let [to-send (:to-send @!ws)]
               (swap! !ws (fn [ws]
                            (-> ws
                                (assoc :connected? true)
                                (dissoc :to-send))))
               (doseq [message to-send]
                 (.send ws (msgpack/encode (clj->js message)))))

            :onclose
            #(swap! !ws assoc :connected? false)

            :onmessage (or on-message identity))
    !ws))

(memo/once
 (defn-memo $connect-gen-mock
   "Returns a !ws pretending to be a provider"
   []
   (make-!ws "ws://localhost:3000/ws-gen")))

(memo/once
 (defn-memo $connect
   "Returns a !ws for this studio instance"
   [!producers !messages]
   (make-!ws "ws://localhost:3000/ws-studio"
             :on-message (j/fn [^js {:as e :keys [data]}]
                           (p/let [abuf (.arrayBuffer data)] 
                             (let [payload (msgpack/decode (js/Uint8Array. abuf))]
                               (case (first payload)
                                 "producers"
                                 (reset! !producers (js->clj (second payload)))
                                 (swap! !messages (comp #(take 100 %) conj)
                                        (let [[_ id data] payload]
                                          {:id id
                                           :data data})))))))))

(ui/defview gen-mock []
  (let [!message (h/use-state (str "Example data " (rand-int 1000)))
        !ws ($connect-gen-mock)]
    [:<>
     "Mock Gen Client"
     [:form.contents
      {:on-submit (fn [^js e]
                    (.preventDefault e)
                    (send !ws [:trace session-id @!message])
                    (reset! !message (str "Example data " (rand-int 1000))))}
      [:textarea.bg-zinc-700
       {:on-change (comp (partial reset! !message)
                         (j/get-in [:target :value]))
        :value @!message}]
      [:button.bg-black.p-2.rounded.inline-flex {:type "submit"} "Send"]]]))

(ui/defview producer-checkbox
  "Displays checkbox and is responsible for subscribing/unsubscribing to channel"
  {:key (fn [_ id] id)}
  [{:keys [!hidden !ws]} id]
  (h/use-effect (fn []
                  (when-not (@!hidden id)
                    (send !ws [:subscribe id])
                    #(send !ws [:unsubscribe id])))
                [id !ws @!hidden])
  [:label.p-2.flex.items-center.select-none.hover:bg-slate-400 {:for id} "#" id
   [:input.ml-1 {:type "checkbox"
                 :id id
                 :on-change (comp (fn [value]
                                    (swap! !hidden (if value disj conj) id))
                                  (j/get-in [:target :checked]))
                 :checked (not (@!hidden id))}]])

(ui/defview inspector []
  (let [!hidden (h/use-state #{})
        !ws ($connect !producers !messages)
        {:keys [ws connected?]} @!ws]
    (def WS !ws)
    (if connected?
      [:div.flex
       [:div {:class "w-1/2"}
        [:div.inline-flex.bg-slate-300.rounded.divide-x-2.m-1.overflow-hidden
         [:div.flex.items-center.p-2.bg-slate-200.rounded-l "Processes:"]
         (doall (map (partial producer-checkbox {:!hidden !hidden
                                                 :!ws !ws}) (keys @!producers)))]
        [:div.p-2.gap-2.flex.flex-col
         (doall
          (for [{:keys [id data]} (->> @!messages
                                       (remove (comp @!hidden str :id)))]
            [:div.flex.flex-col
             [:div (str "#" id)]
             (str data)]))]]
       [:div.flex.flex-col.gap-2.p-2.bg-zinc-800.text-white {:class "w-1/2 min-h-[100vh]"}
        [gen-mock]]]
      (str @!ws))))

(defn init []
  (root/create :inspector (v/<> [inspector])))

(comment
  @WS
  (= WS ($connect))
  @($connect)
  (-> (meta $connect)
      :re-db.memo/state
      deref
      :cache
      (get nil)
      deref)
  (swap! WS assoc :connected? true))