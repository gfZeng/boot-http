(ns pandeiro.boot-http.impl
  (:import  [java.net URLDecoder])
  (:require [clojure.java.io :as io]
            [clojure.string  :as s]
            [ring.util.response :refer [resource-response content-type]]
            [ring.middleware
             [file :refer [wrap-file]]
             [resource :refer [wrap-resource]]
             [content-type :refer [wrap-content-type]]
             [not-modified :refer [wrap-not-modified]]
             [reload :refer [wrap-reload]]
             [cookies :refer [wrap-cookies]]]
            [pandeiro.boot-http.util :as u]
            [clj-http.client         :refer [request]]
            [boot.util :as util])
  (:import java.net.URI))

;;
;; Directory serving
;;
(def index-files #{"index.html" "index.htm"})

(defn index-file-exists? [files]
  (first (filter #(index-files (s/lower-case (.getName %))) files)))

(defn path-diff [root-path path]
  (s/replace path (re-pattern (str "^" root-path)) ""))

(defn filepath-from-uri [root-path uri]
  (str root-path (URLDecoder/decode uri "UTF-8")))

(defn list-item [root-path]
  (fn [file]
    (format "<li><a href=\"%s\">%s</a></li>"
            (path-diff root-path (.getPath file))
            (.getName file))))

(defn index-for [dir]
  (let [root-path (or (.getPath (io/file dir)) "")]
    (fn [{:keys [uri] :as req}]
      (let [directory (io/file (filepath-from-uri root-path uri))]
        (when (.isDirectory directory)
          (let [files (sort (.listFiles directory))]
            {:status  200
             :headers {"Content-Type" "text/html"}
             :body    (if-let [index-file (index-file-exists? files)]
                        (slurp index-file)
                        (format (str "<!doctype html><meta charset=\"utf-8\">"
                                     "<body><h1>Directory listing</h1><hr>"
                                     "<ul>%s</ul></body>")
                                (apply str (map (list-item root-path) files))))}))))))

(defn wrap-index [handler dir]
  (fn [req]
    (or ((index-for dir) req)
        (handler req))))

(defn- prepare-cookies
  "Removes the :domain and :secure keys and converts the :expires key (a Date)
  to a string in the ring response map resp. Returns resp with cookies properly
  munged."
  [resp]
  (let [prepare #(-> %
                     (update-in [1 :expires] str)
                     ;; :discard, :version is older version of rfc2965.
                     ;; kill my ass, spring
                     ;; http://docs.spring.io/spring/docs/current/javadoc-api/org/springframework/http/HttpHeaders.html#set_cookie2
                     (update 1 dissoc :domain :secure :discard :version))]
    (assoc resp :cookies (into {} (map prepare (:cookies resp))))))

(defn- slurp-binary
  [^java.io.InputStream in len]
  (with-open [in in]
    (let [buf (byte-array len)]
      (.read in buf)
      buf)))

(defn- proxy-request
  [req proxied-path remote-uri-base & [http-opts]]
  (let [remote-base (URI. (s/replace-first remote-uri-base #"/$" ""))
        remote-uri  (URI. (.getScheme remote-base)
                          (.getAuthority remote-base)
                          (str (.getPath remote-base)
                               (if (instance? java.util.regex.Pattern proxied-path)
                                 (s/replace-first (:uri req) proxied-path "")
                                 (:uri req)))
                          (:query-string req)
                          nil)]
    (-> (merge {:method           (:request-method req)
                :url              (str remote-uri)
                :headers          (dissoc (:headers req) "host" "content-length")
                :body             (when-let [len (some-> req
                                                         (get-in [:headers "content-length"])
                                                         Integer/parseInt)]
                                    (slurp-binary (:body req) len))
                :as               :stream
                :force-redirects  false
                :follow-redirects false
                :decompress-body  false}
               (dissoc http-opts :decompress-body))
        request
        prepare-cookies)))

(defn wrap-proxy
  [h proxied-path remote-uri-base & [http-opts]]
  (wrap-cookies
   (fn [req]
     (if (re-find (if (instance? java.util.regex.Pattern proxied-path)
                    proxied-path
                    (re-pattern (str "^" proxied-path)))
                  (:uri req))
       (proxy-request req proxied-path remote-uri-base http-opts)
       (h req)))))

;;
;; Handlers
;;

(defn- resolve-middleware [m]
  (cond
    (fn? m) m
    (symbol? m)  (u/resolve-sym m)
    (seq? m) (eval (list 'fn '[%] m))))


(defn wrap-handler [{:keys [handler proxy reload middlewares env-dirs]}]
  (when handler
    (cond-> ((apply comp (map resolve-middleware middlewares))
             (u/resolve-sym handler))
      proxy  (wrap-proxy (ffirst proxy) (fnext (first proxy)))
      reload (wrap-reload {:dirs (or env-dirs ["src"])}))))

(defn- maybe-create-dir! [dir]
  (let [dir-file (io/file dir)]
    (when-not (.exists dir-file)
      (util/warn "Directory '%s' was not found. Creating it..." dir)
      (.mkdirs dir-file))))

(defn not-found-handler [not-found]
  (if not-found
    (u/resolve-sym not-found)
    (fn [_] {:status  404
             :headers {"Content-Type" "text/plain; charset=utf-8"}
             :body    "Not found"})))

(defn dir-handler [{:keys [dir resource-root not-found]
                    :or {resource-root ""}}]
  (when dir
    (maybe-create-dir! dir)
    (-> (not-found-handler not-found)
      (wrap-resource resource-root)
      (wrap-file dir {:index-files? false})
      (wrap-index dir))))

(defn resources-handler [{:keys [resource-root]
                          :or {resource-root ""}}]
  (-> (fn [{:keys [request-method uri] :as req}]
        (if (and (= request-method :get))
          ; Remove start slash and add end slash
          (let [uri (if (.startsWith uri "/") (.substring uri 1) uri)
                uri (if (.endsWith uri "/") uri (str uri "/"))]
            (some-> (resource-response (str uri "index.html") {:root resource-root})
                    (content-type "text/html")))))
      (wrap-resource resource-root)))

(defn ring-handler [opts]
  (or (wrap-handler opts)
      (dir-handler opts)
      (resources-handler opts)))

;;
;; Jetty / HTTP Kit
;;

(defn- start-httpkit [handler opts]
  (require 'org.httpkit.server)
  (let [stop-server ((resolve 'org.httpkit.server/run-server) handler opts)]
    (merge (meta stop-server)
           {:stop-server stop-server
            :human-name "HTTP Kit"})))

(defn- start-jetty [handler opts]
  (require 'ring.adapter.jetty)
  (let [server ((resolve 'ring.adapter.jetty/run-jetty) handler opts)]
    {:server server
     :human-name "Jetty"
     :local-port (-> server .getConnectors first .getLocalPort)
     :stop-server #(.stop server)}))

(defn server [{:keys [port httpkit] :as opts}]
  ((if httpkit start-httpkit start-jetty)
   (-> (ring-handler opts)
       wrap-content-type
       wrap-not-modified)
   {:port port :join? false}))

;;
;; nREPL
;;

(defn nrepl-server [{:keys [nrepl]}]
  (require 'clojure.tools.nrepl.server)
  (let [{:keys [bind port] :or {bind "127.0.0.1"}} nrepl
        start-server (resolve 'clojure.tools.nrepl.server/start-server)
        repl-server  (if port
                       (start-server :port port :bind bind)
                       (start-server :bind bind))]
    (util/info
     "Started boot-http nREPL on nrepl://%s:%d\n"
     bind (:port repl-server))
    repl-server))
