(ns pandeiro.boot-http
  {:boot/export-tasks true}
  (:require
   [boot.pod           :as pod]
   [boot.util          :as util]
   [boot.core          :as core :refer [deftask]]
   [pandeiro.boot-http.impl :as http]
   [pandeiro.boot-http.util :as u]))

(def default-port 3000)

(def jetty-dep
  '[ring/ring-jetty-adapter "1.4.0"])

(def httpkit-dep
  '[http-kit "2.1.19"])

(defn- silence-jetty! []
  (.put (System/getProperties) "org.eclipse.jetty.LEVEL" "WARN"))

(deftask serve
  "Start a web server on localhost, serving resources and optionally a directory.
  Listens on port 3000 by default."

  [d dir           PATH str   "The directory to serve; created if doesn't exist."
   H handler       SYM  sym   "The ring handler to serve."
   m middlewares   MS   [sym] "Addtional middlewares for handler"
   i init          SYM  sym   "A function to run prior to starting the server."
   c cleanup       SYM  sym   "A function to run after the server stops."
   r resource-root ROOT str   "The root prefix when serving resources from classpath"
   p port          PORT int   "The port to listen on. (Default: 3000)"
   k httpkit            bool  "Use Http-kit server instead of Jetty"
   s silent             bool  "Silent-mode (don't output anything)"
   R reload             bool  "Reload modified namespaces on each request."
   N not-found     SYM  sym   "a ring handler for requested resources that aren't in your directory. Useful for pushState."]

  (let [port        (or port default-port)
        server-dep  (if httpkit httpkit-dep jetty-dep)]
    (core/set-env! :dependencies #(conj % server-dep))
    (when (and silent (not httpkit))
      (silence-jetty!))
    (core/with-pre-wrap fileset
      (when init
        (u/resolve-and-invoke init))
      (let [server (http/server
                    {:dir dir, :port port, :handler handler,
                     :middlewares middlewares
                     :reload reload, :env-dirs (vec (:directories pod/env)), :httpkit httpkit,
                     :not-found not-found,
                     :resource-root resource-root})]
        (core/cleanup
         (when server
           (when-not silent
             (util/info "Stopping %s\n" (:human-name server)))
           ((:stop-server server))))
        (when-not silent
          (util/info "Started %s on http://localhost:%d\n"
                     (:human-name server)
                     (:local-port server)))
        (assoc fileset :http-port (:local-port server))))))
