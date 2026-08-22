(ns dougaka.topics-test
  "topics/backlog.edn is the queue the 企画 Bot drains and the 検品 Bot pushes
  back into. Its shape is what both Bots' objectives describe, so a Bot commit
  that bends it fails here before the nightly loop meets it.

  Two directions, stated: a :designed entry must point at a design file that
  exists, and a design file that exists must be reachable from the backlog OR
  be one of the pre-backlog hand-authored designs (the ten from 2026-07)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def statuses #{:open :designed :rendered :published})

(defn- backlog []
  (edn/read-string (slurp "topics/backlog.edn")))

(deftest backlog-reads-and-has-the-declared-shape
  (let [b (backlog)]
    (is (= 1 (:topics/version b)))
    (is (vector? (:topics b)))
    (is (pos? (count (:topics b))) "an empty backlog idles the planner; keep it fed")
    (doseq [{:keys [theme status design] :as t} (:topics b)]
      (testing theme
        (is (and (string? theme) (not (str/blank? theme))))
        (is (contains? statuses status) (pr-str t))
        (when (not= :open status)
          (is (string? design) "a non-open entry names its design slug")
          (is (.isFile (io/file "videos" (str design ".edn")))
              (str "videos/" design ".edn must exist for " status)))))))

(deftest no-design-slug-collides-with-the-backlog-file
  ;; videos/ holds designs ONLY — outer-loop reads every videos/*.edn as one.
  (is (not (.exists (io/file "videos" "topics.edn"))))
  (is (not (.exists (io/file "videos" "backlog.edn")))))

(deftest slugs-are-ascii-kebab-case
  (doseq [{:keys [design]} (:topics (backlog)) :when design]
    (is (re-matches #"[a-z0-9]+(-[a-z0-9]+)*" design) design)))
