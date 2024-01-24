(ns maria.cloud.pages.landing
  (:require [clojure.string :as str]
            [maria.ui :as ui :refer [defview]]))

(defn link [title href]
  [:a (cond-> {:href href}
              (str/starts-with? title "http")
              (assoc :target "_blank")) title])

(defview page []
  [:div.font-serif.text-center.px-3.mx-auto.mt-3
   {:style {:max-width 500}}
   [:div.pt-5 {:class "text-[3rem]"} "Welcome to sci.town,"]
   [:div.text-2xl "an exploratory environment for probabilistic programming."]

   [:div.flex.flex-col.bg-zinc-200.rounded.my-6.p-4.gap-4
    [:div.text-2xl "Start Here: "]
    [:a.rounded-md.p-3.block.flex-grow
     {:class ["text-base" ui/c:button-light]
      :href "/gist/1a467e0075fc8d18609f715c11c28061"} "Introduction to Gen"]]

   [:div.text-left.pl-6
    [:div.italic.text-lg.text-left.mb-2.mt-4 "Further reading:"]

    [:ul.text-base.text-left.leading-normal.list-disc.pl-5
     [:li "TODO: add references/links, "
      (link "Example" "https://example.com")]]]])