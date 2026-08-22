(ns dougaka.chain-test
  "The chain's decisions, pinned where the script cannot drift from them."
  (:require [clojure.test :refer [deftest is testing]]
            [dougaka.chain :as chain]))

(deftest parse-args-theme-and-plan-forms
  (testing "a theme run gets a clock-derived id and the theme as title"
    (let [a (chain/parse-args ["--theme" "商店街の朝" "--duration" "60"] {:now-ms 42})]
      (is (= "商店街の朝" (:theme a)))
      (is (= "v-42" (:id a)))
      (is (= "60" (:duration a)))
      (is (= "商店街の朝" (:title a)))
      (is (false? (:announce? a)))
      (is (nil? (:aspect a)))
      (is (false? (:no-burn? a)))))
  (testing "a --plan run takes id and title from the design"
    (let [a (chain/parse-args ["--plan" "videos/neko.edn" "--announce" "--aspect" "landscape" "--no-burn"]
                              {:now-ms 1 :design {:episode-id "neko" :title "猫"}})]
      (is (= "neko" (:id a)))
      (is (= "猫" (:title a)))
      (is (true? (:announce? a)))
      (is (= "landscape" (:aspect a)))
      (is (true? (:no-burn? a)))))
  (testing "explicit --id / --title beat the design"
    (let [a (chain/parse-args ["--plan" "p.edn" "--id" "x" "--title" "T"] {:now-ms 1 :design {:episode-id "neko" :title "猫"}})]
      (is (= "x" (:id a))) (is (= "T" (:title a)))))
  (testing "neither --theme nor --plan is usage, not a run"
    (is (= {:usage true} (chain/parse-args ["--announce"] {:now-ms 1})))))

(deftest engine-dir-prefers-override-then-the-cloud-itonami-sibling
  (let [join (fn [a b] (str a "/" b))
        exists-only (fn [p] (= p "/r/../ai-gftd-dougaka/clj/deps.edn"))]
    (is (= "/x/clj" (chain/engine-dir {:override "/x/clj" :here "/r" :exists? (constantly false) :join join})))
    (is (= "/r/../ai-gftd-dougaka/clj" (chain/engine-dir {:here "/r" :exists? exists-only :join join})))
    (is (= "/r/../../gftdcojp/ai-gftd-dougaka/clj"
           (chain/engine-dir {:here "/r" :exists? #(= % "/r/../../gftdcojp/ai-gftd-dougaka/clj/deps.edn") :join join})))
    (is (nil? (chain/engine-dir {:override "  " :here "/r" :exists? (constantly false) :join join}))
        "blank override is not an override, and no candidate means nil (the script exits 2)")))

(deftest step-argvs
  (is (= ["clojure" "-M:dev" "-m" "dougaka.produce" "--from" "videos/x.edn"]
         (chain/plan-step {:plan-src "videos/x.edn"})))
  (is (= ["clojure" "-M:dev" "-m" "dougaka.produce" "t" "v-1" "60"]
         (chain/plan-step {:theme "t" :id "v-1" :duration "60"})))
  (is (= ["clojure" "-M:dev" "-m" "dougaka.produce" "t" "v-1"]
         (chain/plan-step {:theme "t" :id "v-1"})))
  (testing "the engine runs with plain -M and only the flags that were given"
    (is (= ["clojure" "-M" "-m" "dougaka.pipeline" "p.edn" "out"]
           (chain/engine-step {:plan-file "p.edn" :out-dir "out"})))
    (is (= ["clojure" "-M" "-m" "dougaka.pipeline" "p.edn" "out" "--aspect" "landscape" "--no-burn"]
           (chain/engine-step {:plan-file "p.edn" :out-dir "out" :aspect "landscape" :no-burn? true}))))
  (is (= ["clojure" "-M:dev" "-m" "dougaka.announce" "a.mp4" "v-1" "T"]
         (chain/announce-step {:mp4 "a.mp4" :id "v-1" :title "T"}))))

(deftest reconstitute-design-strips-the-namespace-and-unblobs
  (let [d (chain/reconstitute-design
           [{:db/id -1 :video/episode-id "neko" :video/title "猫"
             :video/scenes "[{:seq 0 :shots [{:seq 0 :duration 7}]}]"
             :video/logline "plain string stays a string"}])]
    (is (= "neko" (:episode-id d)))
    (is (= [{:seq 0 :shots [{:seq 0 :duration 7}]}] (:scenes d)))
    (is (= "plain string stays a string" (:logline d)))
    (is (not (contains? d :db/id)))))
