#!/usr/bin/env nbb
;; produce-video — theme 一発で縦型ショート動画を製造する orchestrator
;; (minidrama scripts/produce-episode 同型、keep in sync)。
;;
;;   dougaka.produce    企画→shot list→DougakaGovernor→commit (hold なら exit 1)
;;   dougaka.pipeline   committed plan → keyframes → ffmpeg → 縦 mp4（字幕焼き込み）+ SRT + legs.edn
;;                      (エンジンは cloud-itonami/ai-gftd-dougaka/clj — この actor
;;                       repo は生成を実装しない、発注するだけ)
;;   dougaka.announce   uploadBlob(認証) → app.aozora.embed.video post → /videos
;;
;; usage:
;;   nbb --classpath src scripts/produce-video.cljs --theme "商店街の朝" [--id v-x]
;;      [--duration 60] [--aspect portrait|landscape] [--no-burn] [--announce] [--title "…"]
;;   nbb --classpath src scripts/produce-video.cljs --plan videos/<slug>.edn [--announce]
;;
;; --announce が「この動画を公開してよい」という sign-off (per-video human、
;; または outer loop の :auto-publish standing grant — superproject
;; ADR-2607162200 Layer D)。無しなら mp4 製造まで (preview)。
;;
;; 判断はすべて dougaka.chain（純 .cljc、JVM テストで固定）にあり、ここは
;; spawn と exit code だけ。終了値は各段の終了値をそのまま返す（pipe の末尾では
;; なく）。HOLD は exit 1、エンジン失敗はエンジンの exit、engine 不在は exit 2。
;;
;; 2026-08-22: `.bb` から移植（ADR-2607173000 — script host は nbb のみ）。
(ns produce-video
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [dougaka.chain :as chain]
            ["child_process" :as cp]
            ["fs" :as fs]
            ["path" :as path]))

(defn- eprintln [& xs]
  (.write (.-stderr js/process) (str (str/join " " xs) "\n")))

(defn- run!
  "Run one step with inherited stdio. A non-zero exit ends the chain with
  THAT exit value — never with the exit of whatever printed last."
  [dir cmd]
  (let [r (cp/spawnSync (first cmd) (clj->js (vec (rest cmd)))
                        #js {:cwd dir :stdio "inherit"
                             :env (js/Object.assign #js {} (.-env js/process))})
        exit (if (nil? (.-status r)) 1 (.-status r))]
    (when-not (zero? exit)
      (eprintln "step failed (exit" exit "):" (str/join " " cmd))
      (js/process.exit exit))))

(defn- repo-root []
  (let [script (second (.-argv js/process))
        cand (when script (path/resolve (path/dirname script) ".."))]
    (if (and cand (fs/existsSync (path/join cand "deps.edn")))
      cand
      (path/resolve "."))))

(defn -main [& args]
  (let [plan-src (chain/opt args "--plan")
        design (when plan-src
                 (chain/reconstitute-design (edn/read-string (fs/readFileSync plan-src "utf8"))))
        {:keys [usage id title announce? aspect no-burn?] :as a}
        (chain/parse-args args {:now-ms (.now js/Date) :design design})
        _ (when usage
            (eprintln "usage: nbb --classpath src scripts/produce-video.cljs (--theme \"…\" | --plan videos/x.edn) [--id …] [--duration 60] [--aspect portrait|landscape] [--no-burn] [--announce] [--title …]")
            (js/process.exit 1))
        here (repo-root)
        engine (or (chain/engine-dir {:override (.-DOUGAKA_ENGINE_DIR (.-env js/process))
                                      :here here
                                      :exists? #(fs/existsSync %)
                                      :join #(path/resolve %1 %2)})
                   (do (eprintln "dougaka engine not found: set DOUGAKA_ENGINE_DIR or check out cloud-itonami/ai-gftd-dougaka as a west sibling")
                       (js/process.exit 2)))
        plan-file (path/join here ".dougaka" "videos" (str id ".edn"))
        out-dir (path/join here ".dougaka" "videos" id)]
    (println "=== 1/3 plan (dougaka actor: VideoLLM ⊣ DougakaGovernor) ===")
    (run! here (chain/plan-step a))
    (println "=== 2/3 produce (dougaka engine: keyframes → ffmpeg) ===")
    (run! engine (chain/engine-step {:plan-file plan-file :out-dir out-dir
                                     :aspect aspect :no-burn? no-burn?}))
    (let [mp4 (path/join out-dir (str id ".mp4"))
          legs (path/join out-dir "legs.edn")]
      (if announce?
        (do (println "=== 3/3 announce (uploadBlob → app.aozora.embed.video → /videos) ===")
            (run! here (chain/announce-step {:mp4 mp4 :id id :title title}))
            (println "done:" id "→ https://aozora.app/videos"))
        (do (println "=== preview (no --announce) ===")
            (println "video:" mp4)
            (println "legs :" legs)
            (let [plan (edn/read-string (fs/readFileSync plan-file "utf8"))]
              (println "title:" (:title plan) "| shots:" (:shots plan)
                       "| duration:" (:duration plan) "s"))
            (println "announce するには --announce を付けて再実行 (sign-off)"))))))

(apply -main (vec (drop 2 (.-argv js/process))))
