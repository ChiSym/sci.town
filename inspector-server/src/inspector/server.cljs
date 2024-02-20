(ns inspector.server
  (:require ["hono" :refer [Hono]]
            ["hono/bun" :refer [serveStatic]]
            ["util" :refer [parseArgs]]
            [inspector.proxy :as proxy]))

(def DEFAULT-PORT 3000)

(def COMMAND-LINE-ARGS
  ;; https://bun.sh/guides/process/argv
  (-> (parseArgs {:args js/Bun.argv
                  :options {:port {:type "string"}
                            :static-root {:type "string"}
                            :static-fallback {:type "string"}}
                  :strict true
                  :allowPositionals true})
      :values))

(defn get-port []
      (or (some-> (:port COMMAND-LINE-ARGS) js/parseInt)
          process.env.PORT
          DEFAULT-PORT))

(def app
  (let [{:keys [static-root
                static-fallback]} COMMAND-LINE-ARGS]
       (doto (new Hono)
             (.get "/" (fn [req] (.text req (str "Hello, world."))))
             (cond-> static-root
                     (.user "*" (serveStatic {:root static-root})))
             (cond-> static-fallback
                     (.get "*" (serveStatic {:path static-fallback})))
             (.get "*" #(.text % "Nothing to see here, folks.")))))

(defonce !server (atom nil))

(reset! !server
  (js/Bun.serve
    {:port (get-port)
     :fetch (fn [req server]
                (if (and (.. req -headers (get "upgrade"))
                         (proxy/upgrade! req server))
                  js/undefined                              ;; if problematic, try returning true
                  (.fetch app req server)))
     :websocket (proxy/websocket-handler !server)}))

(println "Inspector proxy running on port" (get-port))