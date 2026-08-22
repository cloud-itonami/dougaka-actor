(ns dougaka.advisor
  "VideoLLM — the *contained intelligence node* for dougaka (動画家).
  It takes a video request (theme + duration target) and returns a
  PROPOSAL: a full production plan for a vertical (720x1280) short video —
  title / logline / scenes / shot list (per-shot prompt, duration, subtitle).
  It NEVER returns a committed record, NEVER fires a generation job and NEVER
  decides publication — the DougakaGovernor censors every proposal downstream,
  and only :commit writes the SSoT (+ announces when the phase allows).
  Mirrors the `Advisor` protocol shape of minidrama.advisor /
  tashikame.factllm.

  Sealed by construction: the default `mock-advisor` is deterministic. The
  real advisor wires `langchain.model` against the Murakumo fleet
  (DEFAULT-PREFERRED per Rider v3.3 §2(i)) — still proposal-only, still
  governor-censored.

  Proposal shape:
    {:summary    str
     :rationale  str
     :episode    {:title str :logline str
                  :scenes [{:seq int :setting str
                            :shots [{:seq int :prompt str :duration sec
                                     :subtitle str}]}]}
     :effect     :production   ; dougaka only ever plans production
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.model :as model]))

(defprotocol Advisor
  (-plan [advisor store request] "store + request → proposal map"))

(defn shot-total
  "Total duration (seconds) of every shot in a video plan."
  [episode]
  (reduce + 0.0 (for [sc (:scenes episode) sh (:shots sc)]
                  (double (or (:duration sh) 0)))))

(defn shot-count [episode]
  (count (for [sc (:scenes episode) sh (:shots sc)] sh)))

(defn- plan* [{:keys [theme duration-target]}]
  (if (or (nil? theme) (str/blank? theme))
    {:summary "empty theme" :rationale "no theme text" :episode nil
     :effect :noop :confidence 0.0}
    (let [target (min 90 (or duration-target 60))
          ;; deterministic 3-scene / 2-shots-per-scene skeleton, evenly timed
          per-shot (double (/ target 6))
          scene (fn [i setting lines]
                  {:seq i :setting setting
                   :shots (vec (map-indexed
                                (fn [j line]
                                  {:seq j
                                   :prompt (str setting " — " line)
                                   :duration per-shot
                                   :subtitle line})
                                lines))})]
      {:summary (str "short video plan: " theme " (" target "s, 6 shots)")
       :rationale "mock heuristic: 3 scenes × 2 shots, even timing"
       :episode {:title (str theme "（ショート動画）")
                 :logline (str theme " を 60 秒で切り取る縦型ショート動画")
                 :scenes [(scene 0 "導入" [(str theme "、はじまる") "最初の一歩"])
                          (scene 1 "本編" ["いちばん見せたい瞬間" "ちいさな発見"])
                          (scene 2 "締め" ["静かな余韻" "また明日"])]}
       :effect :production :confidence 0.75})))

(defn mock-advisor
  "The deterministic advisor (default everywhere — no non-deterministic LLM
  free-write). Real-LLM wiring is a swap via `langchain.model` on Murakumo."
  []
  (reify Advisor (-plan [_ _store req] (plan* req))))

(defn trace
  "Decision-grounded audit record for the ledger."
  [request proposal]
  {:t          :videollm-proposal
   :op         (:op request)
   :episode-id (:episode-id request)
   :summary    (:summary proposal)
   :shots      (some-> (:episode proposal) shot-count)
   :duration   (some-> (:episode proposal) shot-total)
   :confidence (:confidence proposal)})

;; ───────────────────── real-LLM advisor (Murakumo fleet) ─────────────────────
;; Sealed just like the mock: it returns a PROPOSAL only — the DougakaGovernor
;; still censors every plan. The model is an INJECTED langchain.model/ChatModel.

(def allowed-infer-hosts
  "Murakumo-fleet inference hosts only (Rider §2(i)).

  The public fleet surface (`infer.murakumo.cloud`, what the `murakumo-main`
  alias resolves to — ADR-2607173100) and the legacy `*.gftd.ai` aliases are
  Murakumo too; the LAN entries are the on-site Ollama/llama-server slots."
  #{"127.0.0.1:11434" "localhost:11434"
    "127.0.0.1:4000"  "localhost:4000"
    "192.168.1.70:4000"
    "infer.murakumo.cloud" "api.murakumo.cloud"
    "qwen-gad.gftd.ai" "gemma-gad.gftd.ai" "gemma-fleet.gftd.ai"})

(defn- host-port [url]
  (when (string? url) (second (re-find #"(?i)^[a-z]+://([^/]+)" url))))

(defn assert-murakumo!
  "Throw if `ollama-url` is not a Murakumo-fleet inference host."
  [ollama-url]
  (let [hp (host-port ollama-url)]
    (when-not (contains? allowed-infer-hosts hp)
      (throw (ex-info (str "inference host " hp " is not Murakumo-fleet (Rider §2(i))")
                      {:host hp})))))

(def dougaka-system-prompt
  "You are dougaka (動画家), a vertical short-video creator: street walks,
life hacks, observational vlogs, mini documentaries — everyday subjects,
no drama scripts. Plan a 45-90 second vertical (720x1280) short video for
the user's theme.
Respond with ONLY a single-line EDN map, no prose, no code fences:
  {:title \"...\" :logline \"...\"
   :scenes [{:seq 0 :setting \"...\"
             :shots [{:seq 0 :prompt \"...\" :duration 8 :subtitle \"...\"}]}]}
Hard limits: total duration <= 120 seconds, <= 24 shots, each shot <= 10
seconds. No real-person likenesses, no brands.")

(defn- build-prompt [{:keys [theme duration-target]}]
  (str "Theme: " theme "\n"
       "Duration target (seconds): " (or duration-target 60) "\n\n"
       "Return ONLY the EDN map now."))

(defn parse-plan-edn
  "Defensively parse the LLM's EDN plan. Any parse failure → nil episode
  (the DougakaGovernor then holds it; the system never breaks on malformed
  model output)."
  [content]
  (let [s (-> (str content)
              (str/replace #"(?s)```[a-zA-Z]*" "")
              (str/replace "```" ""))]
    (try
      (when-let [m (some-> (re-find #"(?s)\{.*\}" s) edn/read-string)]
        (when (and (string? (:title m)) (sequential? (:scenes m)))
          m))
      (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn llm-advisor
  "Advisor backed by a langchain.model/ChatModel. Sealed: returns a PROPOSAL
  only; the DougakaGovernor still censors. gen-opts → model/-generate opts."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-plan [_ _store request]
       (let [content (:content
                      (model/-generate chat-model
                        [{:role :system :content dougaka-system-prompt}
                         {:role :user   :content (build-prompt request)}]
                        gen-opts)
                      {})
             ep (parse-plan-edn content)]
         (if ep
           {:summary (str "videollm plan: " (:title ep))
            :rationale "LLM plan (Murakumo); governor-censored downstream"
            :episode ep :effect :production :confidence 0.6}
           {:summary "videollm output unparseable"
            :rationale "malformed plan → no episode (governor holds)"
            :episode nil :effect :noop :confidence 0.1}))))))
