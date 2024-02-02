(ns maria.cloud.auth
  (:require ["firebase/app" :as Firebase]
            ["firebase/auth" :as Auth]
            ["firebase/database" :as Database]
            [maria.cloud.firebase.database :as fdb]
            [applied-science.js-interop :as j]
            [clojure.string :as str]
            [maria.cloud.local-sync :as local-sync]
            [maria.editor.keymaps :as keymaps]
            [maria.editor.util :as u]
            [promesa.core :as p]
            [re-db.api :as db]
            [re-db.reactive :as r]))

(defonce !app
         (delay
           (Firebase/initializeApp (clj->js
                                     (db/get :maria.cloud/env :firebase)))))

(defonce !initialized? (r/atom false))

(defn get-user [] (db/get ::user))

(defn set-user! [x]
  (db/transact! [(assoc x :db/id ::user)]))

(defn pending? []
  (and (not @!initialized?)
       (some? (local-sync/get-entity ::token))))

(def provider (new Auth/GithubAuthProvider))

(j/defn handle-auth-result! [^js {:as user :keys [photoURL email displayName uid]}]
  (if user
    (do
      (db/transact! [{:db/id ::user
                      :uid uid
                      :photo-url photoURL
                      :email email
                      :display-name displayName}])
      (fdb/assoc-in+ [:profile uid]
                     {:displayName displayName
                      :avatar photoURL}))
    (db/transact! [[:db/retractEntity ::user]]))
  (reset! !initialized? true))

(defn sign-in! []
  (p/-> (Auth/signInWithPopup (Auth/getAuth) provider)
        handle-auth-result!))

(defn sign-out! []
  (.signOut (Auth/getAuth)))

(defn init []
  (reset! fdb/!db (Database/getDatabase @!app))
  (when (str/includes? (j/get js/location :hostname) "local")
    (Database/connectDatabaseEmulator @fdb/!db "127.0.0.1" 9000))
  ;; this doesn't work yet, but redirects are preferred for mobile
  #_(p/-> (Auth/getRedirectResult (Auth/getAuth))
        (j/get :user)
        handle-auth-result!)

  (.onAuthStateChanged (Auth/getAuth) handle-auth-result!)

  (keymaps/register-commands!
    {:account/sign-in {:f (fn [_] (sign-in!))
                       :when (fn [_] (not (get-user)))}
     :account/sign-out {:f (fn [_] (sign-out!))
                        :when (fn [_] (some? (get-user)))}}))