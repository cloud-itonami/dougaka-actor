(ns dougaka.sim
  "Offline demo: drive two sample video themes (one clean, one over-budget)
  through the dougaka actor on a MemStore + mock advisor + mock publisher
  (no network). `clojure -M:dev:run`."
  (:require [langgraph.graph :as g]
            [dougaka.operation :as op]
            [dougaka.store :as store]
            [dougaka.advisor :as advisor]
            [dougaka.publisher :as publisher])
  (:gen-class))

(defn -main [& _args]
  (let [s   (store/seed-db)
        pub (publisher/mock-publisher)
        a   (op/build s {:advisor (advisor/mock-advisor) :publisher pub})]
    (doseq [[ctx req] [[{:actor-id "dougaka" :phase 1}
                        {:op :episode/plan :episode-id "v1"
                         :theme "商店街の朝、開店前の音" :duration-target 60}]
                       [{:actor-id "dougaka" :phase 1
                         :budget {:cost-per-shot 10 :episode-budget 24}}
                        {:op :episode/plan :episode-id "v2"
                         :theme "百円で作る最強の朝食" :duration-target 60}]]]
      (let [r (g/run* a {:request req :context ctx}
                      {:thread-id (:episode-id req)})]
        (println (get-in r [:state :disposition]) "←" (:episode-id req)
                 "published?" (some? (get-in r [:state :published])))))
    (println "--- would-be announced records ---")
    (doseq [p @(:a pub)] (println (:episode-id p) "→" (:title p) "|" (:text p)))
    (println "--- ledger ---")
    (doseq [f (store/ledger s)] (prn f))))
