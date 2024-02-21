(ns inspector.server
  (:require ["hono" :refer [Hono]]
            ["hono/bun" :refer [serveStatic]]
            ["util" :refer [parseArgs]]
            [inspector.proxy :as proxy]))

(def DEFAULT-OPTIONS {:port 3000
                      :static-root "../editor2/public"
                      :static-fallback "../editor2/public/inspector.html"})

(def COMMAND-LINE-OPTIONS
  ;; parse argv using https://bun.sh/guides/process/argv
  (-> (parseArgs {:args js/Bun.argv
                  :options {:port {:type "string"}
                            :static-root {:type "string"}
                            :static-fallback {:type "string"}}
                  :strict true
                  :allowPositionals true})
      :values))

(defn get-port []
      (or (some-> (:port COMMAND-LINE-OPTIONS) js/parseInt)
          process.env.PORT
          (:port DEFAULT-OPTIONS)))

(def app
  (let [{:keys [static-root
                static-fallback]} (merge DEFAULT-OPTIONS
                                         COMMAND-LINE-OPTIONS)
        serve-fallback #(js/Response. (js/Bun.file static-fallback))]
       (doto (new Hono)
             (.get "/" serve-fallback)
             (.use "*" (serveStatic {:root static-root}))
             (.get "*" serve-fallback))))

(defonce !server (atom nil))

(reset! !server
        (js/Bun.serve
          {:port (get-port)
           :fetch (fn [req server]
                      (if (and (.. req -headers (get "upgrade"))
                               (proxy/upgrade! req server))
                        js/undefined                        ;; if problematic, try returning true
                        (.fetch app req server)))
           :websocket (proxy/websocket-handler !server)}))

(println "Inspector running on port" (get-port))