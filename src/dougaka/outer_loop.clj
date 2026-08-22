(ns dougaka.outer-loop
  "Durable outer loop (ADR-2607162200 Layer B): consume ONE production tick
  per run — 1 run = 1 operation, no unbounded inner loops — driving the
  produce → dougaka engine → announce chain that scripts/produce-video.cljs
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
                              (produce-video.cljs 既定は west sibling)"
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

(def ^:private terminal-status? #{"done" "held"})

(def ^:private status-rank {"done" 2 "held" 1 "started" 0})

(defn merge-consumption
  "records (record values) -> {tick-id record}, highest status-rank wins
  (done > held > started), order-independent. Each terminal status writes to
  its OWN rkey precisely so no record ever overwrites a different status --
  re-asserting the same uri makes reads non-deterministic while novelty is
  unfolded (observed live 2026-07-16: a done tick read back as \"started\"),
  which would reopen a finished tick after lease TTL and over-produce."
  [values]
  (reduce (fn [m v]
            (if-let [tid (:tick-id v)]
              (let [cur (m tid)]
                (if (or (nil? cur)
                        (> (status-rank (:status v) 0)
                           (status-rank (:status cur) 0)))
                  (assoc m tid v)
                  m))
              m))
          {} values))

(defn consumption
  "Tick-consumption records from the actor's OWN repo → {tick-id record},
  terminal-preferring (see merge-consumption)."
  [pds did]
  (let [rs (:records (getx (str pds "/xrpc/com.atproto.repo.listRecords?repo=" did
                                "&collection=" tick-collection "&limit=100")))]
    (merge-consumption (map :value rs))))

(defn- record-consumption! [pub {:keys [tick episode-id status extra]}]
  (publisher/publish!
   pub (merge {:collection tick-collection
               ;; lease keeps the bare rkey; terminal state gets its own — a
               ;; write NEVER lands on a uri that already has a different
               ;; status (read-determinism, see merge-consumption).
               :rkey (str (:date tick) "-" (:slot tick)
                          (when (terminal-status? status) (str "-" status)))
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

(defn published-posts
  "rkeys of the actor's existing app.bsky.feed.post records + how many were
  created today — dedup source (a design already announced outside the tick
  system, e.g. the shotengai-asa operator E2E, must never be re-produced) and
  the deterministic rate-cap input for the governor's :rate-limited gate.
  (minidrama loop-hardening `bfc24df` port.)"
  [pds did today]
  (let [rs (:records (getx (str pds "/xrpc/com.atproto.repo.listRecords?repo=" did
                                "&collection=app.bsky.feed.post&limit=100")))]
    {:rkeys (set (keep (fn [{:keys [uri]}] (last (str/split (str uri) #"/"))) rs))
     :today (count (filter #(str/starts-with? (str (get-in % [:value :createdAt])) today)
                           rs))}))

(defn next-design
  "First catalog design neither a consumption record nor an existing feed
  post (dedup vs out-of-band announces) has used yet."
  [consumed published-rkeys]
  (let [used (into (set (keep :episode-id (vals consumed))) published-rkeys)]
    (first (remove used (catalog-designs)))))

(def lease-ttl-minutes
  "A consumption record stuck in \"started\" longer than this is a dead lease
  (crashed chain); the tick becomes consumable again — re-consume overwrites
  the same rkey, so recovery is idempotent. Override: DOUGAKA_LEASE_TTL_MIN."
  120)

(defn- lease-expired? [record now-ms ttl-min]
  (and (= "started" (:status record))
       (when-let [ts (:createdAt record)]
         (try (> (- now-ms (.toEpochMilli (java.time.Instant/parse ts)))
                 (* ttl-min 60000))
              (catch Exception _ false)))))

(defn open-ticks
  "Due ticks that are unconsumed OR whose \"started\" lease has expired."
  [due consumed now-ms ttl-min]
  (remove (fn [t]
            (when-let [r (consumed (:id t))]
              (not (lease-expired? r now-ms ttl-min))))
          due))

(defn- notify!
  "Best-effort owner escalation on HOLD (macOS user notification). Never
  fails the run — the consumption record is the durable escalation fact."
  [msg]
  (try (.waitFor (.start (ProcessBuilder.
                          ^java.util.List
                          ["osascript" "-e"
                           (str "display notification \"" msg
                                "\" with title \"dougaka outer-loop\"")])))
       (catch Exception _ nil)))

(defn- run-chain!
  "produce → engine → announce via the existing orchestrator. Returns exit code.
  DOUGAKA_PUBLISHED_TODAY feeds the governor's deterministic rate cap."
  [design-slug announce? published-today]
  (let [cmd (cond-> ["nbb" "scripts/produce-video.cljs"
                     "--plan" (str "videos/" design-slug ".edn")]
              announce? (conj "--announce"))
        pb (doto (ProcessBuilder. ^java.util.List cmd) (.inheritIO))]
    (when published-today
      (.put (.environment pb) "DOUGAKA_PUBLISHED_TODAY" (str published-today)))
    (.waitFor (.start pb))))

(defn run-once!
  "Consume at most one unconsumed tick for today (UTC). Returns a result map."
  []
  (let [pds aozora/default-pds
        id (cacao/load-or-create-identity! ".dougaka/identity.edn")
        pub (aozora/aozora-publisher {:pds pds :identity id
                                      :json-write json/write-str
                                      :json-read json/read-str})
        now-ms (System/currentTimeMillis)
        today (subs (str (java.time.Instant/now)) 0 10)
        ttl (or (some-> (System/getenv "DOUGAKA_LEASE_TTL_MIN") parse-long)
                lease-ttl-minutes)
        due (vec (ticks pds today))
        consumed (consumption pds (:did id))
        open (first (open-ticks due consumed now-ms ttl))
        ph (or (some-> (System/getenv "DOUGAKA_PHASE") parse-long) 2)
        announce? (phase/publish-allowed? ph #{:auto-publish})
        pubs (delay (published-posts pds (:did id) today))]
    (cond
      (nil? open)
      {:status :idle :due (count due) :consumed (count consumed)}

      :else
      (let [design (next-design consumed (:rkeys @pubs))]
        (if-not design
          (do (record-consumption! pub {:tick open :status "held"
                                        :extra {:reason "catalog-exhausted"}})
              (notify! (str "HOLD " (:id open) " — catalog exhausted"))
              {:status :held :tick (:id open) :reason :catalog-exhausted})
          (do (record-consumption! pub {:tick open :episode-id design :status "started"})
              (let [exit (run-chain! design announce? (:today @pubs))]
                (if (zero? exit)
                  (do (record-consumption! pub {:tick open :episode-id design :status "done"
                                                :extra {:phase ph :grant "auto-publish"
                                                        :announced (boolean announce?)}})
                      {:status :done :tick (:id open) :episode design :announced announce?})
                  (do (record-consumption! pub {:tick open :episode-id design :status "held"
                                                :extra {:exit exit}})
                      (notify! (str "HOLD " (:id open) " — chain exit " exit))
                      {:status :held :tick (:id open) :episode design :exit exit})))))))))

(defn -main [& [cmd]]
  (if (= cmd "status")
    (let [pds aozora/default-pds
          id (cacao/load-or-create-identity! ".dougaka/identity.edn")]
      (println "ticks      :" (pr-str (ticks pds)))
      (println "consumption:" (pr-str (consumption pds (:did id)))))
    (println "run-once!  :" (pr-str (run-once!))))
  (System/exit 0))
