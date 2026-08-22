(ns dougaka.run-tests
  "Test runner for dougaka-actor (new actors ship run_tests.clj, not
  .sh — per etzhayyim/root CLAUDE.md). Canonical path: `clojure -M:dev:test`
  (cognitect test-runner). This runner: `clojure -M -m dougaka.run-tests`."
  (:require [clojure.test :refer [run-tests]]
            [dougaka.governor-contract-test]
            [dougaka.store-contract-test]
            [dougaka.operation-test]
            [dougaka.video-designs-test]
            [dougaka.topics-test]
            [dougaka.deploy-test]
            [dougaka.advisor-repair-test])
  (:gen-class))

(defn -main [& _args]
  (let [res (run-tests
             'dougaka.governor-contract-test
             'dougaka.store-contract-test
             'dougaka.operation-test
             'dougaka.video-designs-test
             'dougaka.topics-test
             'dougaka.deploy-test
             'dougaka.advisor-repair-test)]
    (when (pos? (+ (:fail res 0) (:error res 0)))
      (System/exit 1))))
