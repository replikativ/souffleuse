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
  (:import (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(def config (aero/read-config (clojure.java.io/resource "config.edn")))

(log/set-level! (get-in config [:log :level]))

(def twitter-creds (let [{:keys [api-key api-secret access-token access-token-secret]} (:twitter config)]
                     (oauth/make-oauth-creds api-key
                                             api-secret
                                             access-token
                                             access-token-secret)))

(def slack-hook-url (get-in config [:slack :hook-url]))
(def slack-channel (get-in config [:slack :channel]))

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

(defn check-if-release [body]
  (let [action (:action body)]
    (when (not= action "published")
      (f/fail :not-a-release))
    body))

;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Handlers
;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn github-hook [{:keys [body headers] :as req}]
  (let [result (f/ok-> (slurp body)
                       (check-hmac req)
                       (json/parse-string true)
                       check-if-release
                       log-request
                       trigger-slack-announcement
                       trigger-twitter-announcement)]
    (if (f/failed? result)
      (if (= (f/message result) :not-a-release)
        {:status 202}
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
  (log/info "Scheduler started")
  (sch/start-scheduler trigger-slack-reminder))

(defn -main [& args]
  (server)
  (scheduler))

(comment
  (def payload (json/parse-string (slurp "test/payload.sample.json") true))
  ((juxt :release :repository) payload)

  (-main)

  @(clnt/post "http://localhost:3000/github/release" {:headers {"Content-Type" "application/json"} :body (slurp "test/payload.sample.json")}))
