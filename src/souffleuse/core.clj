(ns souffleuse.core
  (:require [souffleuse.scheduler :as s]
            [org.httpkit.server :as srv]
            [org.httpkit.client :as clnt]
            [clojure.core.match :refer [match]]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [cheshire.core :as json]
            [twitter.oauth :as oauth]
            [twitter.api.restful :as r]))

(def port 3000)

(def slack-hook-url (System/getenv "SLACK_HOOK_URL"))
(def slack-channel "#datahike")

(def twitter-api-key (System/getenv "API_KEY"))
(def twitter-api-secret (System/getenv "API_SECRET"))
(def twitter-access-token (System/getenv "ACCESS_TOKEN"))
(def twitter-access-token-secret (System/getenv "ACCESS_TOKEN_SECRET"))
(def twitter-creds (oauth/make-oauth-creds
                    twitter-api-key
                    twitter-api-secret
                    twitter-access-token
                    twitter-access-token-secret))

(defn log-request [d]
  (log/info "Received webhook" d)
  d)

(defn trigger-slack-reminder [_]
  (if slack-hook-url
    (let [message "In a quarter hour we will have our weekly open-source meeting
                   where we are talking about the latest progress on Datahike and
                   other replikativ libraries."
          json (json/generate-string {:username "replikativ"
                                      :channel slack-channel
                                      :text message})]
      (clnt/post slack-hook-url {:headers {"content-type" "application/json"}
                                 :body json})
      (log/debug "Weekly OSS meeting announcement triggered"))
    (log/error "Slack Hook URL not provided")))

(defn trigger-slack-announcement [[release repository :as d]]
  (if slack-hook-url
    (let [message (format "Version %s of %s was just released. Find the changelog or get in contact with us <%s|over on GitHub.>"
                          (:tag_name release)
                          (:name repository)
                          (:html_url release))
          json (json/generate-string {:username "timo"
                                      :channel slack-channel
                                      :text message})]
      (clnt/post slack-hook-url {:headers {"content-type" "application/json"}
                                 :body json}))
    (log/error "Slack Hook URL not provided"))
  d)

(defn trigger-twitter-announcement [[release repository :as d]]
  (let [message (format "Version %s of %s was just released. Take a look at the changelog over on GitHub: %s"
                        (:tag_name release)
                        (:name repository)
                        (:html_url release))]
    (if (not-any? nil? [twitter-api-key twitter-api-secret twitter-access-token twitter-access-token-secret])
      (r/statuses-update :oauth-creds twitter-creds
                         :params {:status message})
      (log/error "Twitter secrets not provided")))
  d)

;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Handlers
;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn github-hook [{body :body :as req}]
  (-> (slurp body)
      (json/parse-string true)
      log-request
      ((juxt :release :repository))
      trigger-slack-announcement
      trigger-twitter-announcement))

;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Routes
;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn routes [{:keys [request-method uri] :as req}]
  (let [path (vec (rest (str/split uri #"/")))]
    (match [request-method path]
      [:post ["github" "release"]] {:body (github-hook req)}
      :else {:status 404 :body "Error 404: Page not found"})))

;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Server
;;;;;;;;;;;;;;;;;;;;;;;;;;

(def server (let [url (str "http://localhost:" port "/")]
              (srv/run-server #'routes {:port port})
              (println "serving" url)))

(def scheduler (s/start-scheduler trigger-slack-reminder))

(comment
  (def payload (json/parse-string (slurp "test/payload.sample.json") true))

  (defonce server (atom nil))
  (reset! server (srv/run-server #'routes {:port port}))

  @(clnt/post "http://localhost:3000/github/release" {:headers {"Content-Type" "application/json"} :body (slurp "test/payload.sample.json")}))
