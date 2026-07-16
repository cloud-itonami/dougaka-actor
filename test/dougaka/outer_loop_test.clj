(ns dougaka.outer-loop-test
  "Pure-function coverage for the scheduled loop's dedup + lease semantics
  (minidrama loop-hardening port, superproject ADR-2607162200)."
  (:require [clojure.test :refer [deftest is testing]]
            [dougaka.outer-loop :as ol]))

(def ^:private tick {:id "creatortick/dougaka/2026-07-16/0"
                     :slug "dougaka" :date "2026-07-16" :slot "0"})

(deftest open-ticks-lease-semantics
  (let [now 10000000 ttl 120
        at (fn [ms] (str (java.time.Instant/ofEpochMilli ms)))]
    (is (= [tick] (vec (ol/open-ticks [tick] {} now ttl))))
    (doseq [status ["done" "held"]]
      (is (empty? (ol/open-ticks [tick] {(:id tick) {:status status}} now ttl))))
    (is (empty? (ol/open-ticks [tick]
                               {(:id tick) {:status "started" :createdAt (at now)}}
                               now ttl)))
    (is (= [tick] (vec (ol/open-ticks
                        [tick]
                        {(:id tick) {:status "started"
                                     :createdAt (at (- now (* (inc ttl) 60000)))}}
                        now ttl))))
    (is (empty? (ol/open-ticks [tick]
                               {(:id tick) {:status "started" :createdAt "garbage"}}
                               now ttl)))))

(deftest next-design-dedups-consumption-and-published-posts
  (let [designs (ol/catalog-designs)]
    (is (seq designs) "videos/ catalog present")
    (testing "the operator-announced shotengai-asa post rkey excludes it"
      (is (not= "shotengai-asa" (ol/next-design {} #{"shotengai-asa"}))))
    (testing "everything used → nil (catalog exhausted)"
      (is (nil? (ol/next-design {} (set designs)))))))
