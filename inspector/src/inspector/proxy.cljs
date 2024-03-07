(ns inspector.proxy
  "Proxy websocket messages from producers to inspectors"
  (:require ["msgpack-lite" :as msgpack]))

;; https://bun.sh/guides/websocket/simple
;; https://bun.sh/guides/websocket/pubsub

;; keep track of active producers
(def !producers (atom {}))

(def !counter (atom 0))

;; to establish websocket connections
(defn upgrade! [{:as req :keys [url]} server]
      ;; create a websocket connection, assigning a random id and mode
      (let [URL (js/URL. url)
            id (or (-> (js/URLSearchParams (.-search URL))
                       (.get "id"))
                   (swap! !counter inc))
            mode (case (.-pathname URL)
                       ;; inspector clients should initialize websocket at /ws-inspector
                       "/ws-inspector" :inspector
                       ;; gen clients should initialize websocket at /ws-gen
                       "/ws-gen" :gen
                       nil)]
           (when (.upgrade server req {:data {:id id
                                              :mode mode}})
                 true)))

;; message handling
(defn websocket-handler [!server]
      (add-watch !producers :producers
                 (fn [_ _ _ value]
                     (prn :producers-changed value) 
                     (.publish @!server :producers (msgpack/encode [:producers value]))))
      (let [send (fn [ws op message]
                     (.send ws (msgpack/encode [op message])))]
           {:maxPayloadLength (* 1024 1024 64)              ;; max message size 64MB
            :idleTimeout 120                                ;; seconds
            :backpressureLimit (* 1024 1024)                ;; 1MB
            :closeOnBackpressureLimit false
            :sendPings true
            :publishToSelf false

            :open (fn [{:as ws {:keys [id mode] :as data} :data}]
                      (case mode
                            :gen (swap! !producers assoc id {:connected-at (js/Date.now)})
                            :inspector (do
                                         ;; always subscribe inspectors to the list of gen clients
                                         (.subscribe ws :producers)
                                         ;; send initial value of producers to inspector
                                         (send ws :producers @!producers))
                            (prn :unknown-data data)))

            :message (fn [{:as ws {:keys [id mode] :as data} :data} message] 
                         (case mode
                               ;; use bun's built-in publish/subscribe api, using the gen client's id
                               ;; as the channel name. By default, proxy all messages directly from
                               ;; gen clients to subscribed inspectors.
                               :gen (.publish @!server id message) ;; proxy message without touching it

                               :inspector (let [[op & args] (msgpack/decode message)]
                                               (case op
                                                     :subscribe (.subscribe ws (first args))
                                                     :unsubscribe (.unsubscribe ws (first args))
                                                     (println :op op "is not implemented.")))
                               (prn :unknown-data data)))

            :close (fn [{:as ws {:keys [id mode] :as data} :data} code message]
                       (prn :close mode id)
                       (case mode
                             :gen (swap! !producers dissoc id)
                             :inspector nil
                             (prn :unknown-data data)))
            #_#_:drain (fn [ws])}))