#!/usr/bin/env bb
;; produce-video — theme 一発で縦型ショート動画を製造する orchestrator
;; (minidrama scripts/produce-episode.bb 同型、keep in sync)。
;;
;;   dougaka.produce    企画→shot list→DougakaGovernor→commit (hold なら exit 1)
;;   dougaka.pipeline   committed plan → keyframes → ffmpeg → 縦 mp4 + SRT
;;                      (エンジンは gftdcojp/ai-gftd-dougaka/clj — この actor
;;                       repo は生成を実装しない、発注するだけ)
;;   dougaka.announce   uploadBlob(認証) → app.aozora.embed.video post → /videos
;;
;; usage:
;;   bb scripts/produce-video.bb --theme "商店街の朝" [--id v-x]
;;      [--duration 60] [--announce] [--title "…"]
;;   bb scripts/produce-video.bb --plan videos/<slug>.edn [--announce]
;;
;; --announce が「この動画を公開してよい」という sign-off (per-video human、
;; または outer loop の :auto-publish standing grant — superproject
;; ADR-2607162200 Layer D)。無しなら mp4 製造まで (preview)。
;; エンジン repo は west 配置の sibling (../../gftdcojp/ai-gftd-dougaka/clj)
;; 既定、DOUGAKA_ENGINE_DIR で上書き可。
(require '[babashka.process :as p]
         '[babashka.fs :as fs]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(defn- opt [args flag]
  (when-let [i (some->> args (map-indexed vector)
                        (filter #(= flag (second %))) first first)]
    (get (vec args) (inc i))))

(defn- flag? [args flag] (boolean (some #{flag} args)))

(defn- unblob [v]
  (if (string? v)
    (try (let [parsed (edn/read-string v)] (if (coll? parsed) parsed v))
         (catch Exception _ v))
    v))

(defn- read-plan-src
  "videos/<slug>.edn is a Datomic/Datascript tx-data vector
  ([{:db/id -1 :video/... ...}], wrap-map ns=video) — reconstitute the bare
  design map (strip :db/id + :video/ namespace, unblob nested collections)
  so :episode-id/:title read the same as before."
  [path]
  (let [tx (edn/read-string (slurp path))]
    (into {} (map (fn [[k v]] [(keyword (name k)) (unblob v)]))
          (dissoc (first tx) :db/id))))

(defn- run! [dir cmd]
  (let [{:keys [exit]} @(p/process cmd {:dir dir :inherit true})]
    (when-not (zero? exit)
      (binding [*out* *err*] (println "step failed (exit" exit "):" (str/join " " cmd)))
      (System/exit exit))))

(let [args *command-line-args*
      plan-src (opt args "--plan")   ; videos/<slug>.edn (実写カタログ設計)
      theme (or (opt args "--theme") plan-src
                (do (binding [*out* *err*]
                      (println "usage: bb scripts/produce-video.bb (--theme \"…\" | --plan videos/x.edn) [--id …] [--duration 60] [--announce] [--title …]"))
                    (System/exit 1)))
      id (or (opt args "--id")
             (when plan-src (:episode-id (read-plan-src plan-src)))
             (str "v-" (System/currentTimeMillis)))
      duration (opt args "--duration")
      title (or (opt args "--title")
                (when plan-src (:title (read-plan-src plan-src)))
                theme)
      announce? (flag? args "--announce")
      here (str (fs/parent (fs/parent *file*)))          ; repo root
      engine (or (System/getenv "DOUGAKA_ENGINE_DIR")
                 (str (fs/normalize (fs/path here "../../gftdcojp/ai-gftd-dougaka/clj"))))
      plan-file (str here "/.dougaka/videos/" id ".edn")
      out-dir (str here "/.dougaka/videos/" id)]
  (println "=== 1/3 plan (dougaka actor: VideoLLM ⊣ DougakaGovernor) ===")
  (run! here (if plan-src
               ["clojure" "-M:dev" "-m" "dougaka.produce" "--from" plan-src]
               (cond-> ["clojure" "-M:dev" "-m" "dougaka.produce" theme id]
                 duration (conj duration))))
  (println "=== 2/3 produce (dougaka engine: keyframes → ffmpeg) ===")
  (run! engine ["clojure" "-M:dev" "-m" "dougaka.pipeline" plan-file out-dir])
  (let [mp4 (str out-dir "/" id ".mp4")]
    (if announce?
      (do (println "=== 3/3 announce (uploadBlob → app.aozora.embed.video → /videos) ===")
          (run! here ["clojure" "-M:dev" "-m" "dougaka.announce" mp4 id title])
          (println "done:" id "→ https://aozora.app/videos"))
      (do (println "=== preview (no --announce) ===")
          (println "video:" mp4)
          (let [plan (edn/read-string (slurp plan-file))]
            (println "title:" (:title plan) "| shots:" (:shots plan)
                     "| duration:" (:duration plan) "s"))
          (println "announce するには --announce を付けて再実行 (sign-off)")))))
