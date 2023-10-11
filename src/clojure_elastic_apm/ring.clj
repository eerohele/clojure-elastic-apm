(ns clojure-elastic-apm.ring
  (:require
   [clojure-elastic-apm.core :refer [type-request with-apm-transaction]]
   [clojure.string :as string]))

(defn match-uri [pattern uri]
  (let [pattern-segs (string/split pattern #"/")
        uri-segs (string/split uri #"/")
        matcher (fn [p u]
                  (cond
                    (= p "*") u
                    (p "_") "_"
                    (p u) u
                    :else false))]
    (if (> (count pattern-segs)
           (count uri-segs))
      false
      (let [matches (map matcher pattern-segs uri-segs)
            matched? (reduce #(and %1 %2) matches)]
        (if matched?
          (string/join "/" matches)
          matched?)))))

(defn match-patterns [patterns uri]
  (->> patterns
       (map #(match-uri % uri))
       (drop-while false?)
       (first)))

(defn wrap-apm-transaction
  ([handler]
   (fn
     ([{:keys [request-method uri headers] :as request}]
      (let [tx-name (str (.toUpperCase (name request-method)) " " uri)
            traceparent (get-in headers ["traceparent"])]
        (with-apm-transaction [tx {:name tx-name :type type-request :traceparent traceparent}]
          (let [{:keys [status] :as response} (handler (assoc request :clojure-elastic-apm/transaction tx))]
            (when status
              (.setResult tx (str "HTTP " status)))
            response))))
     ([{:keys [request-method uri headers] :as request} respond raise]
      (let [tx-name (str (.toUpperCase (name request-method)) " " uri)
            traceparent (get-in headers ["traceparent"])]
        (with-apm-transaction [tx {:name tx-name :type type-request :traceparent traceparent}]
          (let [req (assoc request :clojure-elastic-apm/transaction tx)]
            (handler req (fn [response]
                           (when (:status response)
                             (.setResult tx (str "HTTP " (:status response))))
                           (respond response)) raise)))))))
  ([handler patterns]
   (fn
     ([{:keys [request-method uri headers] :as request}]
      (let [matched (match-patterns patterns uri)
            tx-name (str (.toUpperCase (name request-method)) " " matched)
            traceparent (get-in headers ["traceparent"])]
        (if matched
          (with-apm-transaction [tx {:name tx-name :type type-request :traceparent traceparent}]
            (let [{:keys [status] :as response} (handler (assoc request :clojure-elastic-apm/transaction tx))]
              (when status
                (.setResult tx (str "HTTP " status)))
              response))
          (handler request))))
     ([{:keys [request-method uri headers] :as request} respond raise]

      (let [matched (match-patterns patterns uri)
            tx-name (str (.toUpperCase (name request-method)) " " matched)
            traceparent (get-in headers ["traceparent"])]
        (if matched
          (with-apm-transaction [tx {:name tx-name :type type-request :traceparent traceparent}]
            (let [req (assoc request :clojure-elastic-apm/transaction tx)]
              (handler req (fn [response]
                             (when (:status response)
                               (.setResult tx (str "HTTP " (:status response))))
                             (respond response)) raise)))
          (handler respond raise)))))))
