(ns maria.editor.extensions.gen
  (:require [gen.sci]
            [gen.dynamic :as dynamic]
            [maria.editor.code.show-values :as show]
            [sci.ctx-store :refer [get-ctx]]
            ))

(defn install! []
  (show/add-global-viewers! (get-ctx) :before :object [(show/by-type {dynamic/DynamicDSLFunction (constantly "#DynamicDSLFunction[]")})])
  (gen.sci/install!))