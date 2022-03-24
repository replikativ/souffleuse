(ns souffleuse.core
  (:require [souffleuse.scheduler :as s]
            [org.httpkit.server :as srv]
            [org.httpkit.client :as clnt]
            [clojure.core.match :refer [match]]
            [taoensso.timbre :as log]
            [clojure.string :as str]
            [cheshire.core :as json]
            [twitter.oauth :as oauth]
            [twitter.api.restful :as r]
            [failjure.core :as f])
  (:import (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(def port 3000)

(def github-token (System/getenv "GITHUB_TOKEN"))

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

(defn hmac-sha-256
  "Takes the webhook body as string
   Takes the secret webhook token as string
   Computes an HMAC
   Returns HMAC as string
   https://stackoverflow.com/a/15444056"
  [^String body-str ^String key-str]
  (let [key-spec (SecretKeySpec. (.getBytes key-str) "HmacSHA256")
        hmac (doto (Mac/getInstance "HmacSHA256") (.init key-spec))
        result (.doFinal hmac (.getBytes body-str))]
    (apply str (map #(format "%02x" %) result))))

(defn check-hmac [body-str {:keys [headers]}]
  (let [hmac (str "sha256=" (hmac-sha-256 body-str github-token))
        header (get headers "x-hub-signature-256")]
    (if (not= hmac header)
      (throw (ex-info "HMAC does not match" {:hmac hmac
                                             :header header}))
      body-str)))

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

(defn trigger-slack-announcement [body]
  (if slack-hook-url
    (let [[release repository] (juxt body :release :repository)
          message (format "Version %s of %s was just released. Find the changelog or get in contact with us <%s|over on GitHub.>"
                          (:tag_name release)
                          (:name repository)
                          (:html_url release))
          json (json/generate-string {:username "timo"
                                      :channel slack-channel
                                      :text message})]
      (clnt/post slack-hook-url {:headers {"content-type" "application/json"}
                                 :body json}))
    (log/error "Slack Hook URL not provided"))
  body)

(defn trigger-twitter-announcement [body]
  (let [[release repository] (juxt body :release :repository)
        message (format "Version %s of %s was just released. Take a look at the changelog over on GitHub: %s"
                        (:tag_name release)
                        (:name repository)
                        (:html_url release))]
    (if (not-any? nil? [twitter-api-key twitter-api-secret twitter-access-token twitter-access-token-secret])
      (r/statuses-update :oauth-creds twitter-creds
                         :params {:status message})
      (log/error "Twitter secrets not provided")))
  body)

;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Handlers
;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn github-hook [{:keys [body headers] :as req}]
  (let [result (f/ok-> (slurp body)
                       (check-hmac req)
                       (json/parse-string true)
                       log-request
                       trigger-slack-announcement
                       trigger-twitter-announcement)]
    (if (f/failed? result)
      (do (log/error (f/message result) {:delivery (get headers "X-GitHub-Delivery")
                                         :event (get headers "X-GitHub-Event")})
          {:status 500 :body (f/message result)})
      {:status 201})))

;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Routes
;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn routes [{:keys [request-method uri] :as req}]
  (let [path (vec (rest (str/split uri #"/")))]
    (match [request-method path]
      [:post ["github" "release"]] (github-hook req)
      :else {:status 404 :body "Error 404: Page not found"})))

;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Server
;;;;;;;;;;;;;;;;;;;;;;;;;;

(def server (let [url (str "http://localhost:" port "/")]
              (println "serving" url)
              (srv/run-server #'routes {:port port})))

(def scheduler (s/start-scheduler trigger-slack-reminder))

(comment
  (def payload (json/parse-string (slurp "test/payload.sample.json") true))

  (srv/server-stop! @server)

  @(clnt/post "http://localhost:3000/github/release" {:headers {"Content-Type" "application/json"} :body (slurp "test/payload.sample.json")}))
