(ns chabbod.core
  (:require [org.httpkit.server :as srv]
            [org.httpkit.client :as clnt]
            [clojure.core.match :refer [match]]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [cheshire.core :as json]))

(def port 3000)

(def slack-hook-url (System/getenv "SLACK_HOOK_URL"))
(def slack-channel "#datahike")

(defn trigger-slack-announcement [{:keys [release repository] :as json-payload}]
  (when slack-hook-url
    (let [text (format "We just released version %s of %s. Get your copy: %s"
                       (:tag_name release)
                       (:name repository)
                       (:url release))
          json (json/generate-string {:username "timo"
                                      :channel slack-channel
                                      :text text})]
      (clnt/post slack-hook-url {:headers {"content-type" "application/json"}
                                 :body json})))
  json-payload)


(defn parse-body [{:keys [body]}]
  (-> body
      slurp))
;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Handlers
;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn github-hook [{body :body :as req}]
  (-> (slurp body)
      (json/parse-string true)
      (trigger-slack-announcement)))

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

(when (= *file* (System/getProperty "babashka.file"))
  (let [url (str "http://localhost:" port "/")]
    (srv/run-server #'routes {:port port})
    (println "serving" url)
    @(promise)))

(comment
  (def payload (json/parse-string (slurp "payload.sample.json") true))
  (get-in payload [:release :url])
  (get-in payload [:release :tag_name])
  (:action payload)
  (def server (srv/run-server #'routes {:port port}))

  @(clnt/post "http://localhost:3000/github/release" {:headers {"Content-Type" "application/json"} :body (slurp "payload.sample.json")}))
