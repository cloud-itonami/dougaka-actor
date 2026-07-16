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

(deftest merge-consumption-prefers-terminal-records
  ;; observed live 2026-07-16: same-rkey overwrites read back non-deterministically
  ;; while novelty is unfolded -- per-status rkeys + rank merge (done > held > started).
  (let [tid "creatortick/x/2026-07-16/0"
        started {:tick-id tid :status "started" :episode-id "a"}
        held    {:tick-id tid :status "held" :episode-id "a"}
        done    {:tick-id tid :status "done" :episode-id "a"}]
    (is (= {tid done} (ol/merge-consumption [started done])))
    (is (= {tid done} (ol/merge-consumption [done started])) "order-independent")
    (is (= {tid started} (ol/merge-consumption [started])))
    (is (= "done" (:status (get (ol/merge-consumption [started held done]) tid)))
        "done outranks held (a successful retry supersedes an earlier hold)")
    (is (= "held" (:status (get (ol/merge-consumption [held started]) tid))))))
