(ns dougaka.video-designs-test
  "videos/ の縦型ショート動画設計カタログを、VideoLLM 提案と同一の検閲
  (DougakaGovernor) + フォーマット不変条件で全数検証する。設計が governor を
  通らないなら、それは出荷できない設計である (minidrama episode-designs-test
  同型)。

  videos/*.edn は Datomic/Datascript tx-data ([{:db/id -1 :video/...}])
  として保存されている (wrap-map, ns=video)。design map として消費するには
  reconstitute-design で :db/id を落とし :video/ 名前空間を剥がし、blob 化
  された :video/scenes を元の入れ子データへ戻す。"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dougaka.advisor :as advisor]
            [dougaka.governor :as governor]
            [dougaka.produce :as produce]))

(defn- unblob [v]
  (if (string? v)
    (try (let [parsed (edn/read-string v)] (if (coll? parsed) parsed v))
         (catch Exception _ v))
    v))

(defn- reconstitute-design [tx-data]
  (into {} (map (fn [[k v]] [(keyword (name k)) (unblob v)]))
        (dissoc (first tx-data) :db/id)))

(defn- designs []
  (->> (.listFiles (io/file "videos"))
       (filter #(str/ends-with? (.getName %) ".edn"))
       (map #(reconstitute-design (edn/read-string (slurp %))))
       (sort-by :episode-id)))

(deftest catalog-has-five-designs
  (is (= 5 (count (designs)))))

(deftest every-design-passes-the-dougaka-governor
  (doseq [{:keys [episode-id] :as d} (designs)]
    (testing episode-id
      (let [{:keys [disposition basis]}
            (produce/produce-plan! {:theme (:title d)
                                    :episode-id episode-id
                                    :advisor (produce/design-advisor d)})]
        (is (= :commit disposition) (pr-str basis))))))

(deftest every-design-meets-format-invariants
  (doseq [{:keys [episode-id duration-target premise scenes] :as d} (designs)]
    (testing episode-id
      (let [ep (select-keys d [:title :logline :scenes])
            total (advisor/shot-total ep)
            shots (for [sc scenes sh (:shots sc)] sh)]
        (is (= :live-action premise) "実写前提 (街歩き/vlog/ドキュメンタリー)")
        (is (= (double duration-target) total)
            "shot durations は duration-target にぴったり一致")
        (is (= 60.0 total) "動画家カタログの尺は 60 秒固定 (発注仕様)")
        (is (<= (count shots) governor/max-shots))
        (is (every? #(<= (double (:duration %)) governor/max-shot-duration) shots))
        (is (every? #(str/includes? (:prompt %) "live-action") shots)
            "全 shot prompt が実写指定")
        (is (every? #(str/includes? (:prompt %) "9:16") shots)
            "全 shot prompt が縦型 9:16 指定")
        (is (every? #(seq (str/trim (or (:subtitle %) ""))) shots)
            "全 shot に台詞/字幕 (voice レグが喋る)")
        (is (every? #(keyword? (:speaker %)) shots)
            "話者ヒント (将来の VOICEVOX 話者演じ分け)")))))
