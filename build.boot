(set-env!
 :source-paths #{"src" "test"}
 :dev-dependencies '[[peridot "0.4.3"]]
 :dependencies     '[[adzerk/bootlaces    "0.1.12" :scope "test"]
                     [adzerk/boot-test    "1.0.4"  :scope "test"]
                     [clj-http "2.2.0"]
                     [ring/ring-core "1.4.0"]
                     [ring/ring-devel "1.4.0"]])

(require
 '[adzerk.bootlaces :refer :all] ;; tasks: build-jar push-snapshot push-release
 '[adzerk.boot-test :refer :all])

(def +version+ "0.7.4-SNAPSHOT")

(bootlaces! +version+)

(task-options!
 pom {:project     'pandeiro/boot-http
      :version     +version+
      :description "Boot task to serve HTTP."
      :url         "https://github.com/pandeiro/boot-http"
      :scm         {:url "https://github.com/pandeiro/boot-http"}
      :license     {"Eclipse Public License" "http://www.eclipse.org/legal/epl-v10.html"}})

(deftask test-boot-http []
  (merge-env!
   :dependencies (get-env :dev-dependencies)
   :resource-paths #{"test-extra/resources"})
  (test :namespaces #{'pandeiro.boot-http-tests}))
