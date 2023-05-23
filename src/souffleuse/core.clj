(ns souffleuse.core
  (:gen-class)
  (:require [souffleuse.scheduler :as sch]
            [org.httpkit.server :as srv]
            [org.httpkit.client :as clnt]
            [clojure.core.match :refer [match]]
            [taoensso.timbre :as log]
            [cheshire.core :as json]
            [twitter.oauth :as oauth]
            [twitter.api.restful :as r]
            [failjure.core :as f]
            [aero.core :as aero]
            [clojure.string :as s]
            [clojure.java.io :as io])
  (:import [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def config (aero/read-config (clojure.java.io/resource "config.edn")))

(log/trace "Config Loaded" config)

(log/set-level! (get-in config [:log :level]))

(def twitter-creds (let [{:keys [api-key api-secret access-token access-token-secret]} (:twitter config)]
                     (oauth/make-oauth-creds api-key
                                             api-secret
                                             access-token
                                             access-token-secret)))

(def github-bearer-token (get-in config [:github :token]))

(def slack-hook-url (get-in config [:slack :hook-url]))
(def slack-channel (get-in config [:slack :channel]))

(def rocketchat-channel (get-in config [:rocketchat :channel]))
(def rocketchat-secret (get-in config [:rocketchat :secret]))
(def rocketchat-token (get-in config [:rocketchat :token]))
(def rocketchat-hook-url (str "https://chat.lambdaforge.io/hooks/" rocketchat-token "/" rocketchat-secret))

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
  (let [gh-token (get-in config [:github :token])
        hmac (str "sha256=" (hmac-sha-256 body-str gh-token))
        header (get headers "x-hub-signature-256")]
    (if (not= hmac header)
      (f/fail "HMAC does not match")
      body-str)))

(defn trigger-slack-reminder [_]
  (if slack-hook-url
    (let [message (get-in config [:scheduler :announcement])
          json (json/generate-string {:username "replikativ"
                                      :channel slack-channel
                                      :text message})]
      (clnt/post slack-hook-url {:headers {"content-type" "application/json"}
                                 :body json})
      (log/debug "Weekly OSS meeting announcement triggered"))
    (log/error "Slack Hook URL not provided")))

(defn trigger-slack-announcement [body]
  (if slack-hook-url
    (let [[release repository] ((juxt :release :repository) body)
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
  (let [[release repository] ((juxt :release :repository) body)
        message (format "Version %s of %s was just released. Take a look at the changelog over on GitHub: %s"
                        (:tag_name release)
                        (:name repository)
                        (:html_url release))]
    (if (not-any? nil? (:twitter config))
      (r/statuses-update :oauth-creds twitter-creds
                         :params {:status message})
      (log/error "Twitter secrets not provided")))
  body)

(defn trigger-rocketchat-announcement [body]
  (if rocketchat-hook-url
    (let [[release repository] ((juxt :release :repository) body)
          message (format "Version %s of %s was just released. Take a look at the changelog over on GitHub: %s"
                          (:tag_name release)
                          (:name repository)
                          (:html_url release))
          json (json/generate-string {:username "rocket.chat"
                                      :channel rocketchat-channel
                                      :text message})]
      (clnt/post rocketchat-hook-url {:headers {"content-type" "application/json"}
                                      :body json}))
    (log/error "RocketChat Hook URL not provided"))
  body)

(defn check-if-release [body]
  (let [action (:action body)]
    (log/debug "Received Action" {:action action})
    (if (not= action "published")
      (f/fail :not-a-release)
      body)))

(defn filter-relevant-releases [{{url :commits_url} :repository
                                 {sha :target_commitish} :release
                                 :as body}]
  (let [url (s/replace url "{/sha}" (str "/" sha))
        commit-data (-> (clnt/get url {:header {"Accept" "application/vnd.github+json"
                                                "Authorization" (str "Bearer " github-bearer-token)
                                                "X-GitHub-Api-Version" "2022-11-28"}})
                        deref
                        :body
                        (json/parse-string true))
        conventional-commit-type (when-let [matches (->> (get-in commit-data [:commit :message]) (re-find #"^(\S+):"))]
                                   (-> (second matches) keyword))]
    (if (and conventional-commit-type (conventional-commit-type #{:ci :docs :style}))
      (f/fail :not-a-relevant-release)
      body)))

(comment
  (filter-relevant-releases {:release {:target_commitish "d7609c8f6a6636da208fef5e467327c716a6c792"}
                             :repository {:commits_url "https://api.github.com/repos/replikativ/datahike-jdbc/commits{/sha}"}})
  (filter-relevant-releases {:release {:target_commitish "fad8985c73331b441301cff7b851907d5c2b1eb8"}
                             :repository {:commits_url "https://api.github.com/repos/replikativ/datahike-jdbc/commits{/sha}"}}))

;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Handlers
;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn github-hook [{:keys [body headers] :as req}]
  (let [result (f/ok-> (slurp body)
                       (check-hmac req)
                       (json/parse-string true)
                       check-if-release
                       filter-relevant-releases
                       log-request
                       trigger-slack-announcement
                       trigger-twitter-announcement
                       trigger-rocketchat-announcement)]
    (if (f/failed? result)
      (case (f/message result)
        :not-a-release {:status 202}
        :not-a-relevant-release {:status 202}
        (do (log/error (f/message result) {:delivery (get headers "X-GitHub-Delivery")
                                           :event (get headers "X-GitHub-Event")})
            {:status 500 :body (f/message result)}))
      {:status 204})))

;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Routes
;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn routes [{:keys [request-method uri] :as req}]
  (let [path (vec (rest (s/split uri #"/")))]
    (match [request-method path]
      [:post ["github" "release"]] (github-hook req)
      :else {:status 404 :body "Error 404: Page not found"})))

;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Server
;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn server []
  (let [port (:port config)
        url (str "http://0.0.0.0:" port "/")]
    (log/info "Server started" {:url url
                                :port port})
    (srv/run-server #'routes {:port port})))

(defn scheduler []
  (sch/start-scheduler trigger-slack-reminder (:scheduler config))
  (log/info "Scheduler started"))

(defn -main [& args]
  (server)
  (scheduler))

(comment
  (def payload (json/parse-string (slurp "test/payload.sample.json") true))
  ((juxt :release :repository) payload)

  (trigger-rocketchat-announcement {:release {:tag_name "0.0.0"
                                              :html_url "foobar.com"}
                                    :repository {:name "foobar"}})

  (-main)
  @(clnt/post "http://localhost:3000/github/release" {:headers {"Content-Type" "application/json"} :body (slurp "test/payload.sample.json")}))
