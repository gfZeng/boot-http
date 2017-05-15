(ns pandeiro.boot-http
  {:boot/export-tasks true}
  (:require
   [boot.pod           :as pod]
   [boot.util          :as util]
   [boot.core          :as core :refer [deftask]]
   [pandeiro.boot-http.impl :as http]
   [pandeiro.boot-http.util :as u]))

(def default-port 3000)

(deftask serve
  "Start a web server on localhost, serving resources and optionally a directory.
  Listens on port 3000 by default."

  [d dir           PATH  str   "The directory to serve; created if doesn't exist."
   H handler       SYM   sym   "The ring handler to serve."
   P proxy         PORXY edn  "proxy settings"
   m middlewares   MS    [edn] "Addtional middlewares for handler"
   i init          SYM   sym   "A function to run prior to starting the server."
   c cleanup       SYM   sym   "A function to run after the server stops."
   r resource-root ROOT  str   "The root prefix when serving resources from classpath"
   p port          PORT  int   "The port to listen on. (Default: 3000)"
   a adapter       SYM   sym   "<jetty|aleph>"
   s silent              bool  "Silent-mode (don't output anything)"
   R reload              bool  "Reload modified namespaces on each request."
   N not-found     SYM   sym   "a ring handler for requested resources that aren't in your directory. Useful for pushState."]

  (let [port (or port default-port)]
    (core/with-pre-wrap fileset
      (when init
        (u/resolve-and-invoke init))
      (let [server (http/server
                    {:dir           dir
                     :adapter       adapter
                     :silent        silent
                     :port          port
                     :handler       handler
                     :proxy         proxy
                     :middlewares   middlewares
                     :reload        reload
                     :env-dirs      (vec (:directories pod/env))
                     :not-found     not-found,
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
