(set-env!
  :resource-paths #{"src"}
  :dependencies '[[org.clojure/clojure "1.8.0"]
                  [boot/base "2.7.2"]
                  [boot/core "2.7.2"]
                  [http-kit "2.2.0"]
                  [cheshire "5.8.0"]])

(task-options!
  pom {:project 'clojdflare
       :version "0.0.1"}
  aot {:namespace #{'clojdflare.core}}
  jar {:main 'clojdflare.core})

(deftask build
  []
  (comp (aot) (pom) (uber) (jar) (target)))

;;; vi:filetype=clojure
