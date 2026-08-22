#!/usr/bin/env nbb
;; produce-video — theme 一発で縦型ショート動画を製造する orchestrator
;; (minidrama scripts/produce-episode 同型、keep in sync)。
;;
;;   dougaka.produce    企画→shot list→DougakaGovernor→commit (hold なら exit 1)
;;   dougaka.pipeline   committed plan → keyframes → ffmpeg → 縦 mp4 + SRT + legs.edn
;;                      (エンジンは cloud-itonami/ai-gftd-dougaka/clj — この actor
;;                       repo は生成を実装しない、発注するだけ)
;;   dougaka.announce   uploadBlob(認証) → app.aozora.embed.video post → /videos
;;
;; usage:
;;   nbb scripts/produce-video.cljs --theme "商店街の朝" [--id v-x]
;;      [--duration 60] [--announce] [--title "…"]
;;   nbb scripts/produce-video.cljs --plan videos/<slug>.edn [--announce]
;;
;; --announce が「この動画を公開してよい」という sign-off (per-video human、
;; または outer loop の :auto-publish standing grant — superproject
;; ADR-2607162200 Layer D)。無しなら mp4 製造まで (preview)。
;;
;; エンジン checkout は west sibling `../ai-gftd-dougaka/clj`（同じ cloud-itonami
;; org）既定。旧 org 配置 `../../gftdcojp/ai-gftd-dougaka/clj` も探す。
;; DOUGAKA_ENGINE_DIR で上書き可。エンジンは `clojure -M`（git 依存 pin 済み）で
;; 呼ぶ — `-M:dev` は west sibling の douga を要求し、workspace 外で
;; 解決できない（engine の operator-quickstart §8 実測）。
;;
;; 終了値は各段の終了値をそのまま返す（pipe の末尾ではなく）。HOLD は exit 1、
;; エンジン失敗はエンジンの exit、announce 失敗は announce の exit。
;;
;; 2026-08-22: `.bb` から移植（ADR-2607173000 — script host は nbb のみ）。
(ns produce-video
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            ["child_process" :as cp]
            ["fs" :as fs]
            ["path" :as path]))

(defn- opt [args flag]
  (when-let [i (some->> args (map-indexed vector)
                        (filter #(= flag (second %))) first first)]
    (get (vec args) (inc i))))

(defn- flag? [args flag] (boolean (some #{flag} args)))

(defn- unblob [v]
  (if (string? v)
    (try (let [parsed (edn/read-string v)] (if (coll? parsed) parsed v))
         (catch :default _ v))
    v))

(defn read-plan-src
  "videos/<slug>.edn is a Datomic/Datascript tx-data vector
  ([{:db/id -1 :video/... ...}], wrap-map ns=video) — reconstitute the bare
  design map (strip :db/id + :video/ namespace, unblob nested collections)
  so :episode-id/:title read the same as before."
  [p]
  (let [tx (edn/read-string (fs/readFileSync p "utf8"))]
    (into {} (map (fn [[k v]] [(keyword (name k)) (unblob v)]))
          (dissoc (first tx) :db/id))))

(defn- eprintln [& xs]
  (.write (.-stderr js/process) (str (str/join " " xs) "\n")))

(defn- run!
  "Run one step with inherited stdio. A non-zero exit ends the chain with
  THAT exit value — never with the exit of whatever printed last."
  [dir cmd env]
  (let [r (cp/spawnSync (first cmd) (clj->js (vec (rest cmd)))
                        #js {:cwd dir :stdio "inherit"
                             ;; process.env is an exotic object; js->clj does
                             ;; not walk it, so extend it with Object.assign
                             :env (js/Object.assign #js {} (.-env js/process)
                                                    (clj->js env))})
        exit (if (nil? (.-status r)) 1 (.-status r))]
    (when-not (zero? exit)
      (eprintln "step failed (exit" exit "):" (str/join " " cmd))
      (js/process.exit exit))))

(defn engine-dir
  "Where the dougaka engine checkout is. Explicit env wins; then the two west
  sibling layouts, first one that has a deps.edn."
  [here]
  (or (some-> (.-DOUGAKA_ENGINE_DIR (.-env js/process)) not-empty)
      (->> ["../ai-gftd-dougaka/clj" "../../gftdcojp/ai-gftd-dougaka/clj"]
           (map #(path/resolve here %))
           (filter #(fs/existsSync (path/join % "deps.edn")))
           first)
      (do (eprintln "dougaka engine not found: set DOUGAKA_ENGINE_DIR or check out cloud-itonami/ai-gftd-dougaka as a west sibling")
          (js/process.exit 2))))

(defn- repo-root []
  ;; the script lives at <repo>/scripts/produce-video.cljs; fall back to cwd
  (let [script (second (.-argv js/process))
        cand (when script (path/resolve (path/dirname script) ".."))]
    (if (and cand (fs/existsSync (path/join cand "deps.edn")))
      cand
      (path/resolve "."))))

(defn -main [& args]
  (let [plan-src (opt args "--plan")
        theme (or (opt args "--theme") plan-src
                  (do (eprintln "usage: nbb scripts/produce-video.cljs (--theme \"…\" | --plan videos/x.edn) [--id …] [--duration 60] [--announce] [--title …]")
                      (js/process.exit 1)))
        design (when plan-src (read-plan-src plan-src))
        id (or (opt args "--id") (:episode-id design) (str "v-" (.now js/Date)))
        duration (opt args "--duration")
        title (or (opt args "--title") (:title design) theme)
        announce? (flag? args "--announce")
        here (repo-root)
        engine (engine-dir here)
        plan-file (path/join here ".dougaka" "videos" (str id ".edn"))
        out-dir (path/join here ".dougaka" "videos" id)]
    (println "=== 1/3 plan (dougaka actor: VideoLLM ⊣ DougakaGovernor) ===")
    (run! here (if plan-src
                 ["clojure" "-M:dev" "-m" "dougaka.produce" "--from" plan-src]
                 (cond-> ["clojure" "-M:dev" "-m" "dougaka.produce" theme id]
                   duration (conj duration)))
          {})
    (println "=== 2/3 produce (dougaka engine: keyframes → ffmpeg) ===")
    (run! engine ["clojure" "-M" "-m" "dougaka.pipeline" plan-file out-dir] {})
    (let [mp4 (path/join out-dir (str id ".mp4"))
          legs (path/join out-dir "legs.edn")]
      (if announce?
        (do (println "=== 3/3 announce (uploadBlob → app.aozora.embed.video → /videos) ===")
            (run! here ["clojure" "-M:dev" "-m" "dougaka.announce" mp4 id title] {})
            (println "done:" id "→ https://aozora.app/videos"))
        (do (println "=== preview (no --announce) ===")
            (println "video:" mp4)
            (println "legs :" legs)
            (let [plan (edn/read-string (fs/readFileSync plan-file "utf8"))]
              (println "title:" (:title plan) "| shots:" (:shots plan)
                       "| duration:" (:duration plan) "s"))
            (println "announce するには --announce を付けて再実行 (sign-off)"))))))

(apply -main (vec (drop 2 (.-argv js/process))))
