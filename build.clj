(ns build
  (:require
    [borkdude.gh-release-artifact :as gh]
    [clojure.tools.build.api :as b])
  (:import
    [clojure.lang ExceptionInfo]
    [java.nio.file Paths]
    [com.google.cloud.tools.jib.api Jib Containerizer RegistryImage]
    [com.google.cloud.tools.jib.api.buildplan AbsoluteUnixPath Port]))

(def lib 'io.replikativ/souffleuse)
(def version (format "0.1.%s" (b/git-count-revs nil)))
(def current-commit (gh/current-commit))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def uber-file (format "%s-%s-standalone.jar" (name lib) version))
(def uber-path (format "target/%s" uber-file))
(def image (format "docker.io/replikativ/souffleuse:%s" version))

(defn get-version
  [_]
  (println version))

(defn clean
  [_]
  (b/delete {:path "target"}))

(defn jar
  [_]
  (b/write-pom {:class-dir class-dir
                :src-pom "./template/pom.xml"
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src" "resources"]})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn uber
  [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :src-dirs ["src"]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-path
           :basis basis
           :main 'souffleuse.core}))

(defn fib [a b]
  (lazy-seq (cons a (fib b (+ a b)))))

(defn retry-with-fib-backoff [retries exec-fn test-fn]
  (loop [idle-times (take retries (fib 1 2))]
    (let [result (exec-fn)]
      (if (test-fn result)
        (if-let [sleep-ms (first idle-times)]
          (do (println "Returned: " result)
              (println "Retrying with remaining back-off times (in s): " idle-times)
              (Thread/sleep (* 1000 sleep-ms))
              (recur (rest idle-times)))
          (do (println "Failed deploying artifact.")
              (System/exit 1)))
        (println "Successfully created release-draft")))))

(defn try-release []
  (try (gh/overwrite-asset {:org "replikativ"
                            :repo (name lib)
                            :tag version
                            :commit current-commit
                            :file jar-file
                            :content-type "application/java-archive"})
       (catch ExceptionInfo e
         (assoc (ex-data e) :failure? true))))

(defn release
  [_]
  (retry-with-fib-backoff 10 try-release :failure?))

(defn install
  [_]
  (clean nil)
  (jar nil)
  (b/install {:basis (b/create-basis {})
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir}))

(defn deploy-image
  [{:keys [docker-login docker-password]}]
  (if-not (and docker-login docker-password)
    (println "Docker credentials missing.")
    (.containerize
      (-> (Jib/from "gcr.io/distroless/java17-debian11")
          (.addLayer [(Paths/get uber-path (into-array String[]))] (AbsoluteUnixPath/get "/"))
          (.setProgramArguments [(format "/%s" uber-file)])
          (.addExposedPort (Port/tcp 3000)))
      (Containerizer/to
        (-> (RegistryImage/named image)
            (.addCredential (str docker-login) (str docker-password))))))
  (println "Deployed new image to Docker Hub: " image))

(comment
  (def docker-login "")
  (def docker-password "")

  (b/pom-path {:lib lib :class-dir class-dir})
  (clean nil)
  (compile nil)
  (jar nil)
  (uber nil)
  (deploy-image {:docker-login docker-login
                 :docker-password docker-password})
  (release nil)
  (install nil))
