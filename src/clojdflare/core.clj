(ns clojdflare.core
  (:gen-class)
  (:require [boot.cli :refer [defclifn]]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.set :refer [subset?]]
            [clojure.string :refer [ends-with? starts-with?]]
            [org.httpkit.client :as http]))


(defn ev-or
  "Get environment variable or set the default value."
  [k dv]
  (or (System/getenv k) dv))


(def cf_api "https://api.cloudflare.com/client/v4/")

(def cf_user (ev-or "CF_USER" ""))

(def cf_key (ev-or "CF_KEY" ""))

(def cf_options {:as :stream
                 :timeout 30000
                 :headers {"X-Auth-Email" cf_user
                           "X-Auth-Key"   cf_key
                           "Content-Type" "application/json"}})


(defn rem-last-slash
  [s]
  (if-not (ends-with? s "/")
    s
    (recur (subs s 0 (- (.length s) 1)))))


(defn prepend-slash
  [s]
  (if (starts-with? s "/")
    s
    (str "/" s)))


(defn join-path
  "Join 2 paths or more."
  [base & paths]
  (loop [base (rem-last-slash base)
         path (prepend-slash (first paths))
         paths (rest paths)]
    (if (empty? paths)
      (str base path)
      (recur (rem-last-slash(str base path))
             (prepend-slash (first paths))
             (rest paths)))))


(defn str-error
  "Return error string from given API result."
  [result]
  (println (map (fn [err]
                  (str (get err "code") ":" (get err "message")))
                (get result "errors")))
  (System/exit -9))


(defn unwrap-cf-result
  "Unwrap result from API."
  [result & kys]
  (if (= true (get result "success"))
    (get-in result kys)
    (str-error result)))


(defn cf-delete
  "DELETE from API."
  [path argsmap]
  (let [cf_options (into cf_options {:query-params argsmap})
        {:keys [status headers body error]} @(http/delete (join-path cf_api path) cf_options)]
    (if error
      (throw (Exception. (str "Failed delete " path ", result: " error)))
      (json/parse-stream (io/reader body)))))


(defn cf-get
  "GET from API."
  [path argsmap]
  (let [cf_options (into cf_options {:query-params argsmap})
        {:keys [status headers body error]} @(http/get (join-path cf_api path) cf_options)]
    (if error
      (throw (Exception. (str "Failed get " path ", result: " error)))
      (json/parse-stream (io/reader body)))))


(defn cf-post
  "POST to API."
  [path datamap]
  (let [cf_options (into cf_options {:body (json/generate-string datamap)})
        {:keys [status headers body error]} @(http/post (join-path cf_api path) cf_options)]
    (if error
      (throw (Exception. (str "Failed post " path ", result: " error)))
      (json/parse-stream (io/reader body)))))


(defn cf-put
  "PUT to API."
  [path datamap]
  (let [cf_options (into cf_options {:body (json/generate-string datamap)})
        {:keys [status headers body error]} @(http/put (join-path cf_api path) cf_options)]
    (if error
      (throw (Exception. (str "Failed post " path ", result: " error)))
      (json/parse-stream (io/reader body)))))


(defn get-domain-id
  "Get Domain id for given record."
  [zone-id args]
  (let [{:keys [content]} args
        args (dissoc args :content)
        domains (unwrap-cf-result
                  (cf-get (join-path "zones" zone-id "dns_records")
                          args)
                  "result")]
    (get (first (filter (fn [d] (= content (get d "content")))
                        domains)) "id")))

(defn get-zone-id
  "Get Zone id for given base domain,
  or CF_ZONE_ID environment variable."
  [d]
  (if (= nil d)
    (ev-or "CF_ZONE_ID" "")
    (unwrap-cf-result (cf-get "zones" {:name d
                                       :status "active"
                                       :page 1})
                      "result" 0 "id")))



(defn decide-action
  "Decide action to run, given cmd args."
  [args]
  (let [invalid (> (count (select-keys args [:create :delete :list :update])) 1)]
    (if invalid
      (throw (AssertionError. (str "Cannot decide action, create, delete, list and update cannot be specified together.")))
      (first (filter (fn [sym] (or (= sym :create)
                                   (= sym :delete)
                                   (= sym :list)
                                   (= sym :update)))
                     (keys args))))))


(defn get-opts-record
  "Get DNS record options, given cmd args."
  [args action]
  (let [result (into (select-keys args [:type :ttl :proxied]) {:name (get args action)})
        content (get args :value)]
    (if-not (= nil content)
      (into result {:content content})
      result)))


(defn delete-dns-record
  "Delete DNS record for given params."
  [zone-id domain-id]
  (json/generate-string (unwrap-cf-result (cf-delete (join-path "zones" zone-id "dns_records" domain-id) {})
                                          "result")
                        {:pretty true}))


(defn get-dns-record
  "Get DNS record for given params."
  [zone-id record]
  (json/generate-string (unwrap-cf-result (cf-get (join-path "zones" zone-id "dns_records")
                                                  record)
                                          "result")
                        {:pretty true}))


(defn post-dns-record
  "Create DNS record for given params."
  [zone-id record]
  (json/generate-string (unwrap-cf-result (cf-post (join-path "zones" zone-id "dns_records")
                                                   record)
                                          "result")
                        {:pretty true}))


(defn update-dns-record
  "Upadte DNS record for given params."
  [zone-id domain-id record]
  (json/generate-string (unwrap-cf-result (cf-put (join-path "zones" zone-id "dns_records" domain-id)
                                                  record)
                                          "result")
                        {:pretty true}))


(defclifn -main
  "Cloudflare client written in Clojure/boot."
  [b base    BSE str "Base domain to lookup. Or specify CF_ZONE_ID to save 1 API call."
   c create  CRT str "Domain to create."
   D delete  DEL str "Domain to delete."
   l list    LST str "Domain to list." 
   u update  UPD str "Domain to update."
   f up-from FRM str "Record value update from."
   t type    TYP str "Type of the record."
   _ ttl     TTL int "TTL value for given record."
   v value   VAL str "Record value to set."
   p proxied    bool "Whether this record will be proxied via Cloudflare."]
  (let [action  (decide-action *opts*)
        record  (get-opts-record *opts* action)
        zone-id (get-zone-id (get *opts* :base))]
    (println (condp = action
               :create (post-dns-record zone-id record)
               :delete (delete-dns-record zone-id (get-domain-id zone-id record))
               :list   (get-dns-record zone-id  record)
               :update (update-dns-record zone-id
                                          (get-domain-id zone-id
                                                         (assoc record :content
                                                                (get *opts* :up-from)))
                                          record)
               "Command not recognized."))))


; vi:filetype=clojure
