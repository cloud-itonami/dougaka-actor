(ns dougaka.outer-loop
  "Durable outer loop (ADR-2607162200 Layer B): consume ONE production tick
  per run — 1 run = 1 operation, no unbounded inner loops — driving the
  produce → dougaka engine → announce chain that scripts/produce-video.bb
  already orchestrates. Ported from minidrama.outer-loop (keep in sync).

  Tick source (Layer A): the aozora PDS cron emits `creatortick/<slug>/<date>/
  <slot>` datoms; this loop reads them via app.aozora.creator.getTicks
  (?actor=dougaka — the registry cadence for dougaka starts :active? false,
  so an empty tick list is the NORMAL idle state until the owner flips the
  registry line). The actor NEVER writes the tick db — consumption is
  recorded as records in the actor's OWN repo (collection
  com.etzhayyim.apps.dougaka.tick, rkey <date>-<slot>), which doubles as the
  lease: a parallel loop instance sees the record and skips, so consuming a
  tick is idempotent. A record stuck in \"started\" (crash mid-chain) is
  surfaced by `status` for owner retry (lease-TTL auto-retry is an R1
  follow-up).

  Publish policy (Layer D, ADR-2607162200): the run carries the
  :auto-publish grant — dougaka.phase/publish-allowed? admits it for
  phase 2 alongside the per-video human :publish. The DougakaGovernor stays
  the escalation boundary: a HOLD (content-veto / likeness / provenance /
  budget / rate-cap) exits the plan step non-zero, the tick is marked
  \"held\" and nothing is announced.

  Video selection: the next videos/*.edn catalog design not yet consumed
  (hand-authored designs still pass the DougakaGovernor via the
  design-advisor). DOUGAKA_USE_LLM=1 is the deploy-wired LLM path instead.

  Usage: clojure -M:dev -m dougaka.outer-loop            run once
         clojure -M:dev -m dougaka.outer-loop status     ticks + consumption
  Env:   DOUGAKA_PHASE        0 draft / 1 unlisted / 2 public (default 2 —
                              ADR-2607162200 scheduled operation)
         DOUGAKA_ENGINE_DIR   ai-gftd-dougaka/clj engine checkout
                              (produce-video.bb 既定は west sibling)"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dougaka.aozora :as aozora]
            [dougaka.cacao :as cacao]
            [dougaka.phase :as phase]
            [dougaka.publisher :as publisher])
  (:gen-class))

(def tick-collection "com.etzhayyim.apps.dougaka.tick")
(def actor-slug "dougaka")

(defn- getx [url]
  (let [{:keys [status body]} (aozora/jvm-http-fn {:url url :method :get})]
    (when (= 200 status) (json/read-str body :key-fn keyword))))

(defn ticks
  "Ticks the PDS cadence cron has emitted for this actor (optionally one date)."
  ([pds] (ticks pds nil))
  ([pds date]
   (:ticks (getx (str pds "/xrpc/app.aozora.creator.getTicks?actor=" actor-slug
                      (when date (str "&date=" date)))))))

(defn consumption
  "Tick-consumption records from the actor's OWN repo → {tick-id record-value}."
  [pds did]
  (let [rs (:records (getx (str pds "/xrpc/com.atproto.repo.listRecords?repo=" did
                                "&collection=" tick-collection "&limit=100")))]
    (into {} (keep (fn [{:keys [value]}]
                     (when-let [tid (:tick-id value)] [tid value]))
                   rs))))

(defn- record-consumption! [pub {:keys [tick episode-id status extra]}]
  (publisher/publish!
   pub (merge {:collection tick-collection
               :rkey (str (:date tick) "-" (:slot tick))
               :$type tick-collection
               :tick-id (:id tick)
               :episode-id episode-id
               :status status}
              extra)))

(defn catalog-designs
  "videos/*.edn catalog slugs (sorted) — the scheduled loop drains these."
  []
  (->> (.listFiles (io/file "videos"))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".edn"))
       (map #(str/replace (.getName ^java.io.File %) #"\.edn$" ""))
       sort vec))

(defn next-design
  "First catalog design no consumption record has produced yet."
  [consumed]
  (let [used (set (keep :episode-id (vals consumed)))]
    (first (remove used (catalog-designs)))))

(defn- run-chain!
  "produce → engine → announce via the existing orchestrator. Returns exit code."
  [design-slug announce?]
  (let [cmd (cond-> ["bb" "scripts/produce-video.bb"
                     "--plan" (str "videos/" design-slug ".edn")]
              announce? (conj "--announce"))
        pb (doto (ProcessBuilder. ^java.util.List cmd) (.inheritIO))]
    (.waitFor (.start pb))))

(defn run-once!
  "Consume at most one unconsumed tick for today (UTC). Returns a result map."
  []
  (let [pds aozora/default-pds
        id (cacao/load-or-create-identity! ".dougaka/identity.edn")
        pub (aozora/aozora-publisher {:pds pds :identity id
                                      :json-write json/write-str
                                      :json-read json/read-str})
        today (subs (str (java.time.Instant/now)) 0 10)
        due (vec (ticks pds today))
        consumed (consumption pds (:did id))
        open (first (remove #(consumed (:id %)) due))
        ph (or (some-> (System/getenv "DOUGAKA_PHASE") parse-long) 2)
        announce? (phase/publish-allowed? ph #{:auto-publish})]
    (cond
      (nil? open)
      {:status :idle :due (count due) :consumed (count consumed)}

      :else
      (let [design (next-design consumed)]
        (if-not design
          (do (record-consumption! pub {:tick open :status "held"
                                        :extra {:reason "catalog-exhausted"}})
              {:status :held :tick (:id open) :reason :catalog-exhausted})
          (do (record-consumption! pub {:tick open :episode-id design :status "started"})
              (let [exit (run-chain! design announce?)]
                (if (zero? exit)
                  (do (record-consumption! pub {:tick open :episode-id design :status "done"
                                                :extra {:phase ph :grant "auto-publish"
                                                        :announced (boolean announce?)}})
                      {:status :done :tick (:id open) :episode design :announced announce?})
                  (do (record-consumption! pub {:tick open :episode-id design :status "held"
                                                :extra {:exit exit}})
                      {:status :held :tick (:id open) :episode design :exit exit})))))))))

(defn -main [& [cmd]]
  (if (= cmd "status")
    (let [pds aozora/default-pds
          id (cacao/load-or-create-identity! ".dougaka/identity.edn")]
      (println "ticks      :" (pr-str (ticks pds)))
      (println "consumption:" (pr-str (consumption pds (:did id)))))
    (println "run-once!  :" (pr-str (run-once!))))
  (System/exit 0))
