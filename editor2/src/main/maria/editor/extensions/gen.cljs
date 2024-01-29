(ns maria.editor.extensions.gen
  (:require [applied-science.js-interop :as j]
            [clojure.edn :refer [read-string]]
            [gen.generative-function :as gf]
            [gen.sci]
            [gen.dynamic :as dynamic]
            [gen.trace :as trace]
            [gen.choicemap]
            [gen.choice-map :as choice-map]
            [gen.dynamic.choice-map :as dynamic.choice-map]
            [maria.editor.code.commands :as commands]
            [maria.editor.code.show-values :as show]
            [sci.ctx-store :refer [get-ctx]]
            [yawn.view :as v]
            [yawn.hooks :as h]
            [sci.core :as sci]))

(defn show-choice-map [opts cm]
  (show/show opts (dynamic.choice-map/unwrap cm)))

(defn show-choice [opts choice]
  (show/show opts (:choice choice)))

(v/defview show-trace [opts trace]
  (let [show (partial show/show opts)]
    [:<>
     [:div.grid.gap-2
      {:class "grid-cols-[minmax(min-content,_0fr)_minmax(min-content,_1fr)]"}
      (when-let [args (trace/get-args trace)]
        [:<>
         [:div.font-semibold "Args"]
         (show args)])
      (when-let [choices (trace/get-choices trace)]
        [:<>
         [:div.font-semibold "Choices"]
         (show choices)])
      (let [result (trace/get-retval trace)]
        [:<>
         [:div.font-semibold "Result"]
         (show result)])]]))

(v/defview show-gf [opts f]
  (let [[result set-trace!] (h/use-state nil)
        [args-str set-args-str!] (h/use-state "")
        [choices-str set-choices-str!] (h/use-state "")
        run! (fn [^js e]
               (.preventDefault e)
               (try (let [argv (commands/code:eval-form-in-show opts (read-string args-str))
                          choice-map (when (seq choices-str)
                                       (commands/code:eval-form-in-show opts (dynamic.choice-map/choice-map (read-string choices-str))))]
                      (set-trace! (if choice-map
                                    (gf/generate f argv choice-map)
                                    (gf/simulate f argv))))
                    (catch js/Error e (set-trace! e))))]
    (if (> (:depth opts) 1)                                 ;; only show simulator for toplevel gen fns
      "#DynamicDSLFunction"
      [:div.flex.flex-col.mb-1
       [:form.flex.gap-2.p-2.rounded-md.bg-white.shadow.relative.z-1
        {:on-submit run!}
        [:div.inline-flex.rounded.p-2.bg-zinc-200.hover:bg-zinc-300.cursor-default
         {:on-click run!}
         (if (seq choices-str) "Generate" "Simulate")]
        [:input.px-1.bg-transparent.border-b-2
         {:placeholder "Arguments"
          :value args-str
          :on-change (comp set-args-str! (j/get-in [:target :value]))}]
        [:input.px-1.bg-transparent.border-b-2
         {:placeholder "Choice Map"
          :value choices-str
          :on-change (comp set-choices-str! (j/get-in [:target :value]))}]
        [:button.hidden {:type "submit"}]]
       (when result
         [:div.mx-2.border.border-zinc-300.rounded-b.p-2
          (show/show opts (:value result result))])])))

(comment
  ;; in case we need to evaluate the generative function fully within the sci context of its definition:
  (commands/code:eval-form-in-show opts
                                   `(gf/simulate @~(:sci/root-value opts)
                                                 ~(read-string (str "[" args "]")))))

(comment

  (gf/simulate gf args)
  ;; => trace

  (trace/get-choices trace)
  (trace/get-args trace)
  (trace/get-retval trace)

  )

(defn install! []
  (show/add-global-viewers!
    (get-ctx) :before :object
    [(show/by-type {dynamic/DynamicDSLFunction show-gf
                    gen.dynamic.trace/Trace show-trace
                    gen.dynamic.choice-map/ChoiceMap show-choice-map
                    gen.dynamic.choice-map/Choice show-choice
                    })])
  (gen.sci/install!))