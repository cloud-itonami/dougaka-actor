(ns dougaka.store-contract-test
  "MemStore ≡ DatomicStore — the same video + ledger facts committed to both
  backends must read back identically (the Store is a swap, not a rewrite)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dougaka.store :as store]
            [langchain.edn-persist :as edn-persist])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest mem-and-datomic-stores-agree
  (doseq [s [(store/seed-db) (store/datomic-store)]]
    (testing (str (type s))
      (store/commit-episode! s "v1" {:episode-id "v1" :title "t1" :shots 6})
      (store/commit-episode! s "v2" {:episode-id "v2" :title "t2" :shots 4})
      (store/append-ledger! s {:t :committed :episode "v1" :seq-hint 0})
      (store/append-ledger! s {:t :governor-hold :episode "v2" :seq-hint 1})
      (is (= "t1" (:title (store/episode s "v1"))))
      (is (nil? (store/episode s "nope")))
      (is (= ["v1" "v2"] (mapv :episode-id (store/all-episodes s))))
      (is (= [:committed :governor-hold] (mapv :t (store/ledger s)))))))

(deftest ledger-is-append-only-ordered
  (doseq [s [(store/seed-db) (store/datomic-store)]]
    (dotimes [i 5] (store/append-ledger! s {:t :fact :i i}))
    (is (= [0 1 2 3 4] (mapv :i (store/ledger s))))))

(deftest repository-backed-store-restores-after-restart
  (let [dir (.toFile (Files/createTempDirectory
                      "dougaka-repository-" (make-array FileAttribute 0)))
        file (io/file dir "state.edn")
        environment {"KOTOBA_REPOSITORY_STATE_FILE" (.getPath file)}
        open-store #(store/datomic-store
                     (edn-persist/configured-persist environment
                                                     "actor/dougaka"))
        first-process (open-store)]
    (store/commit-episode! first-process "restart-1"
                           {:episode-id "restart-1" :title "restored"})
    (store/append-ledger! first-process {:t :committed :episode "restart-1"})
    (let [second-process (open-store)]
      (is (= "restored" (:title (store/episode second-process "restart-1"))))
      (is (= [:committed] (mapv :t (store/ledger second-process)))))))
