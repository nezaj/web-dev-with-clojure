(ns guestbook.core
  (:require [reagent.core :as r]
            [reagent.dom :as dom]
            [re-frame.core :as rf]
            [mount.core :as mount]
            [ajax.core :refer [GET POST]]
            [clojure.string :as string]
            [reitit.coercion.spec :as reitit-spec]
            [reitit.frontend :as rtf]
            [reitit.frontend.easy :as rtfe]
            [reitit.frontend.controllers :as rtfc]

            [guestbook.validation :refer [validate-message]]
            [guestbook.websockets :as ws]))

(def SEND_CB_TIMEOUT 10000)

;; ajax
;; ------------------------
(rf/reg-fx
 :ajax/get
 (fn [{:keys [url success-event error-event success-path]}]
   (GET url
     (cond-> {:headers {"Accept" "application/transit+json"}}
       success-event (assoc :handler
                            #(rf/dispatch
                              (conj success-event
                                    (if success-path
                                      (get-in % success-path)
                                      %))))
       error-event (assoc :error-handler
                          #(rf/dispatch
                            (conj error-event %)))))))

(rf/reg-fx
 :ajax/post
 (fn [{:keys [url success-event error-event success-path params]}]
   (POST url
     (cond-> {:headers {"Accept" "application/transit+json"}}
       params       (assoc :params params)
       success-event (assoc :handler
                            #(rf/dispatch
                              (conj success-event
                                    (if success-path
                                      (get-in % success-path)
                                      %))))
       error-event (assoc :error-handler
                          #(rf/dispatch
                            (conj error-event %)))))))
;; sessions
;; ------------------------
(rf/reg-event-fx
 :session/load
 (fn [{:keys [db]} _]
   {:db (assoc db :session/loading? true)
    :ajax/get {:url "/api/session"
               :success-path [:session]
               :success-event [:session/set]}}))

(rf/reg-event-db
 :session/set
 (fn [db [_ {:keys [identity]}]]
   (assoc db
          :auth/user identity
          :session/loading? false)))

(rf/reg-sub
 :session/loading?
 (fn [db _]
   (:session/loading? db)))

;; profile
;; -------------------
(rf/reg-sub
 :profile/changes
 (fn [db _]
   (get db :profile/changes)))

(rf/reg-sub
 :profile/changed?
 :<- [:profile/changes]
 (fn [changes _]
   (not (empty? changes))))

(rf/reg-sub
 :profile/field-changed?
 :<- [:profile/changes]
 (fn [changes [_ k]]
   (contains? changes k)))

(rf/reg-sub
 :profile/field
 :<- [:profile/changes]
 :<- [:auth/user]
 (fn [[changes {:keys [profile]}] [_ k default]]
   (or (get changes k) (get profile k) default)))

(rf/reg-sub
 :profile/profile
 :<- [:profile/changes]
 :<- [:auth/user]
 (fn [[changes {:keys [profile]}] _]
   (merge profile changes)))

(rf/reg-event-db
 :profile/save-change
 (fn [db [_ k v]]
   (update db :profile/changes
           (if (nil? v)
             #(dissoc % k)
             #(assoc % k v)))))

(rf/reg-event-fx
 :profile/update-profile
 (fn [_ [_ profile]]
   {:ajax/post {:url "/api/my-account/update-profile"
                :params {:profile profile}
                :success-event [:profile/after-update profile]}}))

(rf/reg-event-db
 :profile/after-update
 (fn [db [_ profile]]
   (-> db
       (assoc-in [:auth/user :profile] profile)
       (dissoc :profile/changes))))

;; messages
;; ------------------------
(rf/reg-event-fx
 :messages/load
 (fn [{:keys [db]} _]
   {:db (assoc db
               :messages/loading? true
               :messages/list nil
               :messages/filter nil)
    :ajax/get {:url "/api/messages"
               :success-path [:messages]
               :success-event [:messages/set]}}))

(rf/reg-event-fx
 :messages/load-by-author
 (fn [{:keys [db]} [_ author]]
   {:db (assoc db
               :messages/loading? true
               :messages/list nil
               :messages/filter {:author author})
    :ajax/get {:url (str "/api/messages/by/" author)
               :success-path [:messages]
               :success-event [:messages/set]}}))

(rf/reg-event-db
 :messages/set
 (fn [db [_ messages]]
   (-> db
       (assoc :messages/loading? false
              :messages/list messages))))

(rf/reg-sub
 :messages/loading?
 (fn [db _]
   (:messages/loading? db)))

(rf/reg-sub
 :messages/list
 (fn [db _]
   (:messages/list db [])))

(defn reload-messages-button []
  (let [loading? (rf/subscribe [:messages/loading?])]
    [:button.button.is-info.is-fullwidth
     {:on-click #(rf/dispatch [:messages/load])
      :disabled @loading?}
     (if @loading? "Loading messages..." "Refresh messages")]))

(defn message-list [messages]
  [:ul.messages
   (for [{:keys [timestamp message name author]} @messages]
     ^{:key timestamp}
     [:li
      [:time (.toLocaleString timestamp)]
      [:p message]
      [:p "-" name
       " <"
       (if author
         [:a {:href (str "/user/" author)} (str "@" author)]
         [:span.is-italic "account not found"]) ">"]])])

(defn message-list-placeholder []
  [:ul.messages
   [:li
    [:p "Loading messages..."]
    [:div {:style {:width "10em"}}
     [:progress.progress.is-dark {:max 100} "30%"]]]])

(defn add-message?
  "Validates whether a message matches criteria specified in filter map
  Expects filter-map of the form {key matcher}"
  [filter-map msg]
  (every?
   (fn [[k matcher]]
     (let [v (get msg k)]
       (cond
         (set? matcher) (matcher v)
         (fn? matcher) (matcher v)
         :else
         (= matcher v))))
   filter-map))

(rf/reg-event-db
 :message/add
 (fn [db [_ message]]
   (.log js/console (str "Adding message " message))
   (if (add-message? (:messages/filter db) message)
     (update db :messages/list conj message)
     db)))

(rf/reg-sub
 :form/fields
 (fn [db _]
   (:form/fields db)))

(rf/reg-sub
 :form/field
 :<- [:form/fields]
 (fn [fields [_ id]]
   (get fields id)))

(rf/reg-event-db
 :form/set-field
 [(rf/path :form/fields)]
 (fn [fields [_ id value]]
   (assoc fields id value)))

(rf/reg-event-db
 :form/clear-fields
 [(rf/path :form/fields)]
 (fn [_ _]
   {}))

(rf/reg-event-db
 :form/set-server-errors
 [(rf/path :form/server-errors)]
 (fn [_ [_ errors]]
   errors))

(rf/reg-sub
 :form/server-errors
 (fn [db _]
   (:form/server-errors db)))

(rf/reg-sub
 :form/validation-errors
 :<- [:form/fields]
 (fn [fields _]
   (validate-message fields)))

(rf/reg-sub
 :form/validation-errors?
 :<- [:form/validation-errors]
 (fn [errors _]
   (not (empty? errors))))

(rf/reg-sub
 :form/errors
 :<- [:form/validation-errors]
 :<- [:form/server-errors]
 (fn [[validation server] _]
   (merge validation server)))

(rf/reg-sub
 :form/error
 :<- [:form/errors]
 (fn [errors [_ id]]
   (get errors id)))

(rf/reg-event-fx
 :message/send!-called-back
 (fn [_ [_ {:keys [success errors]}]]
   (.log js/console
         "Called-back from server! with success: " success
         " error: " errors)
   (if success
     {:dispatch [:form/clear-fields]}
     {:dispatch [:form/set-server-errors errors]})))

(rf/reg-event-fx
 :message/send!
 (fn [{:keys [db]} [_ fields]]
   (.log js/console (str "Sending message with fields " fields))
   {:db (dissoc db :form/server-errors)
    :ws/send! {:message [:message/create! fields]
               :timeout SEND_CB_TIMEOUT
               :callback-event [:message/send!-called-back]}}))

(defn form-errors-component
  "Form level errors"
  [id & [message]]
  (when-let [error @(rf/subscribe [:form/error id])]
    [:div.notification.is-danger (if message
                                   message
                                   (string/join error))]))
(defn field-errors-component
  "Field level errors"
  [id & [message]]
  (if-let [_ @(rf/subscribe [:form/field id])]
    (when-let [error @(rf/subscribe [:form/error id])]
      [:div.notification.is-danger (if message
                                     message
                                     (string/join error))])))

(defn text-input [{val :value
                   attrs :attrs
                   :keys [on-save]}]
  (let [draft (r/atom nil)
        value (r/track #(or @draft @val ""))]
    (fn []
      [:input.input
       (merge attrs
              {:type :text
               :on-focus #(reset! draft (or @val ""))
               :on-blur (fn []
                          (on-save (or @draft ""))
                          (reset! draft nil))
               :on-change #(reset! draft (.. % -target -value))
               :value @value})])))

(defn textarea-input [{val :value
                       attrs :attrs
                       :keys [on-save]}]
  (let [draft (r/atom nil)
        value (r/track #(or @draft @val ""))]
    (fn []
      [:textarea.textarea
       (merge attrs
              {:type :text
               :on-focus #(reset! draft (or @val ""))
               :on-blur (fn []
                          (on-save (or @draft ""))
                          (reset! draft nil))
               :on-change #(reset! draft (.. % -target -value))
               :value @value})])))

(defn message-form []
  [:div
   [form-errors-component :server-error]
   [form-errors-component :unauthorized]
   [:div.field
    [:label.label {:for :name} "Name"]
    [field-errors-component :name]
    [text-input {:attrs {:name :name}
                 :value (rf/subscribe [:form/field :name])
                 :on-save #(rf/dispatch [:form/set-field :name %])}]]
   [:div.field
    [:label.label {:for :message} "Message"]
    [field-errors-component :message]
    [textarea-input
     {:attrs {:name :message}
      :value (rf/subscribe [:form/field :message])
      :on-save #(rf/dispatch [:form/set-field :message %])}]]
   [:input.button.is-primary
    {:type :submit
     :disabled @(rf/subscribe [:form/validation-errors?])
     :on-click #(rf/dispatch [:message/send!
                              @(rf/subscribe [:form/fields])])
     :value "comment"}]])

;; modals
;; -------------------
(rf/reg-event-db
 :app/show-modal
 (fn [db [_ modal-id]]
   (assoc-in db [:app/active-modals modal-id] true)))

(rf/reg-event-db
 :app/hide-modal
 (fn [db [_ modal-id]]
   (update db :app/active-modals dissoc modal-id)))

(rf/reg-sub
 :app/active-modals
 (fn [db _]
   (:app/active-modals db {})))

(rf/reg-sub
 :app/modal-showing?
 :<- [:app/active-modals]
 (fn [modals [_ modal-id]]
   (get modals modal-id false)))

(defn modal-card [id title body footer]
  [:div.modal
   {:class (when @(rf/subscribe [:app/modal-showing? id]) "is-active")}
   [:div.modal-background
    {:on-click #(rf/dispatch [:app/hide-modal id])}]
   [:div.modal-card
    [:header.modal-card-head
     [:p.modal-card-title title]
     [:button.delete
      {:on-click #(rf/dispatch [:app/hide-modal id])}]]
    [:section.modal-card-body body]
    [:footer.modal-card-foot footer]]])

(defn modal-button [id title body footer]
  [:div
   [:button.button.is-primary
    {:on-click #(rf/dispatch [:app/show-modal id])}
    title]
   [modal-card id title body footer]])

;; auth
;; ------------------------
(rf/reg-event-db
 :auth/handle-login
 (fn [db [_ {:keys [identity]}]]
   (assoc db :auth/user identity)))

(rf/reg-event-db
 :auth/handle-logout
 (fn [db _]
   (rf/dispatch [:form/clear-fields])
   (dissoc db :auth/user)))

(rf/reg-sub
 :auth/user
 (fn [db _]
   (:auth/user db)))

(rf/reg-sub
 :auth/user-state
 :<- [:auth/user]
 :<- [:session/loading?]
 (fn [[user loading?]]
   (cond
     (true? loading?)  :loading
     user              :authenticated
     :else             :anonymous)))

(defn do-login [fields error]
  (reset! error nil)
  (POST "/api/login"
    {:headers {"Accept" "application/transit+json"}
     :params @fields
     :handler (fn [response]
                (reset! fields {})
                (rf/dispatch [:auth/handle-login response])
                (rf/dispatch [:app/hide-modal :user/login]))
     :error-handler (fn [error-response]
                      (reset! error
                              (or (:message (:response error-response))
                                  (:status-text error-response)
                                  "Unknown Error")))}))

(defn login-button []
  (r/with-let [fields (r/atom {})
               error (r/atom nil)]
    [modal-button :user/login
     ;; Title
     "Log in"
     ;; Body
     [:div
      (when-not (string/blank? @error)
        [:div.notification.is-danger @error])
      [:div.field
       [:div.label "Login"]
       [:div.control
        [:input.input
         {:type "text"
          :value (:login @fields)
          :on-change #(swap! fields assoc :login (.. % -target -value))}]]]
      [:div.field
       [:div.label "Password"]
       [:div.control
        [:input.input
         {:type "password"
          :value (:password @fields)
          :on-change #(swap! fields assoc :password (.. % -target -value))
          ;; Submit login form when 'Enter' key is pressed
          :on-key-down #(when (= (.-keyCode %) 13)
                          (do-login fields error))}]]]]
     ;; Footer
     [:button.button.is-primary.is-fullwidth
      {:on-click #(do-login fields error)
       :disabled (or (string/blank? (:login @fields)) (string/blank? (:password @fields)))}
      "Log In"]]))

(defn logout-button []
  [:button.button
   {:on-click #(POST "/api/logout"
                 :handler (fn [_] (rf/dispatch [:auth/handle-logout])))}
   "Log Out"])

(defn nameplate [{:keys [login]}]
  [:a.button.is-primary
   {:href "/my-account/edit-profile"}
   login])

(defn do-register [fields error]
  (reset! error nil)
  (POST "/api/register"
    {:header {"Accept" "application/transit+json"}
     :params @fields
     :handler (fn [response]
                (reset! fields {})
                (rf/dispatch [:auth/handle-login response])
                (rf/dispatch [:app/hide-modal :user/register]))
     :error-handler (fn [error-response]
                      (reset! error
                              (or (:message (:response error-response))
                                  (:status-text error-response)
                                  "Unknown Error")))}))

(defn register-button []
  (r/with-let
    [fields (r/atom {})
     error (r/atom nil)]
    [modal-button :user/register
     ;; Title
     "Create Account"
     ;; Body
     [:div
      (when-not (string/blank? @error)
        [:div.notification.is-danger
         @error])
      [:div.field
       [:div.label "Login"]
       [:div.control
        [:input.input
         {:type "text"
          :value (:login @fields)
          :on-change #(swap! fields assoc :login (.. % -target -value))}]]]
      [:div.field
       [:div.label "Password"]
       [:div.control
        [:input.input
         {:type "password"
          :value (:password @fields)
          :on-change #(swap! fields assoc :password (.. % -target -value))}]]]
      [:div.field
       [:div.label "Confirm Password"]
       [:div.control
        [:input.input
         {:type "password"
          :value (:confirm @fields)
          :on-change #(swap! fields assoc :confirm (.. % -target -value))
          :on-key-down #(when (= (.-keyCode %) 13)
                          (do-register fields error))}]]]]
     [:button.button.is-primary.is-fullwidth
      {:on-click #(do-register fields error)
       :disabled (some string/blank? [(:login @fields)
                                      (:password @fields)
                                      (:confirm @fields)])}
      "Create Account"]]))

;; views
;; ------------------------
(def home-controllers
  [{:start (fn [_]
             (rf/dispatch [:messages/load]))}])

(defn home []
  (let [messages (rf/subscribe [:messages/list])]
    (fn []
      [:div.content>div.columns.is-centered>div.column.is-two-thirds
       (if @(rf/subscribe [:messages/loading?])
         [message-list-placeholder]
         [:div
          [:div.columns>div.column
           [:h3 "Messages"]
           [message-list messages]
           [reload-messages-button]]
          [:div.columns>div.column
           (case @(rf/subscribe [:auth/user-state])
             :loading
             [:div {:style {:width "5em"}}
              [:progress.progress.is-dark.is-small {:max 100} "30%"]]

             :authenticated
             [message-form]

             :anonymous
             [:div.notification.is-clearfix
              [:span "Log in or create an account to post a message!"]
              [:div.buttons.is-pulled-right
               [login-button]
               [register-button]]])]])])))

(def author-controllers
  [{:parameters {:path [:user]}
    :start (fn [{{:keys [user]} :path}]
             (rf/dispatch [:messages/load-by-author user]))}])

(defn author []
  (let [messages (rf/subscribe [:messages/list])]
    (fn [{{{:keys [user]} :path} :parameters}]
      [:div.content>div.columns.is-centered>div.column.is-two-thirds
       [:div.columns>div.column
        [:h3 "Messages by " user]
        (if @(rf/subscribe [:messages/loading?])
          [message-list-placeholder]
          [message-list messages])]])))


;; profile componenets
;; ------------------------


(defn display-name []
  (r/with-let [k :display-name
               value (rf/subscribe [:profile/field k ""])]
    [:div.field
     [:label.label {:for k} "Display Name"
      (when @(rf/subscribe [:profile/field-changed? k])
        " (Changed)")]
     [:div.field.has-addons
      [:div.control.is-expanded
       [text-input {:value value
                    :on-save #(rf/dispatch [:profile/save-change k %])}]]]
     [:div.control>button.button.is-danger
      {:disabled (not @(rf/subscribe [:profile/field-changed? k]))
       :on-click #(rf/dispatch [:profile/save-change k nil])} "Reset"]]))

(defn bio []
  (r/with-let [k :bio
               value (rf/subscribe [:profile/field k ""])]
    [:div.field
     [:label.label {:for k} "Bio"
      (when @(rf/subscribe [:profile/field-changed? k])
        " (Changed)")]
     [:div.control {:style {:margin-bottom "0.5em"}}
      [textarea-input {:value value
                       :on-save #(rf/dispatch [:profile/save-change k %])}]]
     [:div.control>button.button.is-danger
      {:disabled (not @(rf/subscribe [:profile/field-changed? k]))
       :on-click #(rf/dispatch [:profile/save-change k nil])} "Reset"]]))

(defn edit-profile []
  (if-let [{:keys [login created_at]} @(rf/subscribe [:auth/user])]
    [:div.content
     [:h1 "My Account"
      (str " <@" login ">")]
     [:p (str "Joined: " (.toString created_at))]
     [display-name]
     [bio]
     [:button.button.is-primary
      {:on-click
       #(rf/dispatch [:profile/update-profile @(rf/subscribe [:profile/profile])])
       :disabled (not @(rf/subscribe [:profile/changed?]))}
      "Update Profile"]]
    [:div.content
     [:div {:style {:width "100%"}}
      [:progress.progress.is-dark {:max 100} "30%"]]]))

;; router
;; ------------------------
(def app-routes
  ["/"
   [""
    {:name ::home
     :controllers home-controllers
     :view home}]
   ["user/:user"
    {:name ::author
     :controllers author-controllers
     :view author}]
   ["my-account/edit-profile"
    {:name ::edit-profile
     :controllers nil
     :view edit-profile}]])

(rf/reg-event-db
 :router/navigated
 (fn [db [_ new-match]]
   (assoc db :router/current-route new-match)))

(rf/reg-sub
 :router/current-route
 (fn [db]
   (:router/current-route db)))

(def router
  (rtf/router
   app-routes
   {:data {:coercion reitit-spec/coercion}}))

(defn init-routes! []
  (rtfe/start!
   router
   (fn [new-match]
     (when new-match
       (let [{:keys [controllers]}
             @(rf/subscribe [:router/current-route])

             new-match-with-controllers
             (assoc new-match
                    :controllers
                    (rtfc/apply-controllers controllers new-match))]
         (rf/dispatch [:router/navigated new-match-with-controllers]))))
   {:use-fragment false}))

;; app
;; ------------------------
(rf/reg-event-fx
 :app/initialize
 (fn [_ _]
   {:db {:session/loading? true}
    :dispatch [:session/load]}))

(defn navbar []
  (let [burger-active (r/atom false)]
    (fn []
      [:nav.navbar.is-info
       [:div.container
        [:div.navbar-brand
         [:a.navbar-item
          {:href "/"
           :style {:font-weight "bold"}}
          "guestbook"]
         [:span.navbar-burger.burger
          {:data-target "nav-menu"
           :on-click #(swap! burger-active not)
           :class (when @burger-active "is-active")}
          [:span]
          [:span]
          [:span]]]
        [:div#nav-menu.navbar-menu
         {:class (when @burger-active "is-active")}
         [:div.navbar-start
          [:a.navbar-item
           {:href "/"}
           "Home"]
          (when (= @(rf/subscribe [:auth/user-state]) :authenticated)
            [:a.navbar-item
             {:href (rtfe/href :guestbook.core/author
                               {:user (:login @(rf/subscribe [:auth/user]))})}
             "My Posts"])]
         [:div.navbar-end
          [:div.navbar-item
           (case @(rf/subscribe [:auth/user-state])
             :loading
             [:div {:style {:width "5em"}}
              [:progress.progress.is-dark.is-small {:max 100} "30%"]]

             :authenticated
             [:div.buttons
              [nameplate @(rf/subscribe [:auth/user])]
              [logout-button]]

             :anonymous
             [:div.buttons
              [login-button]
              [register-button]])]]]]])))

(defn page [{{:keys [view name]}  :data
             path                 :path
             :as                  match}]
  (.log js/console
        "Reitit router rendering: " name "\n"
        "with match " match "\n")
  [:section.section>div.container
   (if view
     [view match]
     [:div "No views specified for route: " name " (" path ")"])])

(defn app []
  (let [current-route @(rf/subscribe [:router/current-route])]
    [:div.app
     [navbar]
     [page current-route]]))

(defn ^:dev/after-load mount-components []
  (rf/clear-subscription-cache!)
  (.log js/console "Mounting components...")
  (init-routes!)
  (dom/render [#'app] (.getElementById js/document "content"))
  (.log js/console "Components Mounted!"))

(defn init! []
  (.log js/console "Initializing App...")
  (mount/start)
  (rf/dispatch-sync [:app/initialize])
  (mount-components))
