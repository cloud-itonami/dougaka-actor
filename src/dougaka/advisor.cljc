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
    "infer.murakumo.cloud" "api.murakumo.cloud"})

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

(defn missing-closers
  "The closing brackets an EDN text is short of, innermost first — counted
  OUTSIDE strings (a `}` inside a subtitle is text, not structure). Returns
  \"\" when balanced, nil when there are MORE closers than openers (that is
  not a truncation and appending cannot fix it)."
  [s]
  (loop [cs (seq s) in-str? false esc? false stack ()]
    (if-let [c (first cs)]
      (cond
        in-str? (recur (rest cs) (if (and (not esc?) (= c \")) false true)
                       (and (not esc?) (= c \\)) stack)
        (= c \") (recur (rest cs) true false stack)
        (= c \;) (recur (drop-while #(not= % \newline) cs) false false stack)
        (#{\{ \[ \(} c) (recur (rest cs) false false (conj stack c))
        (#{\} \] \)} c) (if (and (seq stack)
                                 (= (peek stack) ({\} \{ \] \[ \) \(} c)))
                          (recur (rest cs) false false (pop stack))
                          nil)
        :else (recur (rest cs) false false stack))
      (apply str (map {\{ \} \[ \] \( \)} stack)))))

(defn reclose
  "Strip the run of closing brackets at the very end of `s` (outside any
  string, so it can only be structure) and append the closers the remaining
  text is actually short of. Returns [mended closers] or nil when nothing
  changes or the prefix has surplus closers.

  Only ever called after the raw text failed to read, so a valid plan is
  never touched. What it fixes, measured 2026-08-22 on the fleet model with
  reasoning off: the final closers dropped after a long last subtitle (EOF),
  and `]`/`}` swapped in the last run (`}]}}` → Unmatched delimiter). Both
  leave the CONTENT complete; only the tail is wrong, and a model asked to
  repair it got the tail wrong again."
  [s]
  (let [trimmed (str/replace s #"[\s\]\}\)]+$" "")
        closers (missing-closers trimmed)]
    (when (and (seq closers) (not= (str trimmed closers) s))
      [(str trimmed closers) closers])))

(defn parse-plan-edn*
  "Parse the LLM's EDN plan. Returns {:episode m} or {:error <why>} — the
  reason is what the repair round sends back to the model, so it is kept
  rather than collapsed into nil.

  One deterministic mend before giving up: when the reader fails, `reclose`
  the tail and read again (`:closed` names what the tail became). It never
  runs on text that read fine."
  [content]
  (let [s (-> (str content)
              (str/replace #"(?s)```[a-zA-Z]*" "")
              (str/replace "```" ""))
        candidate (re-find #"(?s)\{.*\}" s)
        validate (fn [m closed]
                   (cond
                     (not (map? m)) {:error "the reply is not a single map"}
                     (not (string? (:title m))) {:error ":title must be a string"}
                     (not (sequential? (:scenes m))) {:error ":scenes must be a vector of scene maps"}
                     (not (every? #(sequential? (:shots %)) (:scenes m)))
                     {:error "every scene needs a :shots vector"}
                     :else (cond-> {:episode m} (seq closed) (assoc :closed closed))))
        read* (fn [text closed]
                (try (validate (edn/read-string text) closed)
                     (catch #?(:clj Throwable :cljs :default) e
                       {:error (str "EDN reader: " #?(:clj (.getMessage ^Throwable e) :cljs (str e)))})))]
    (if-not candidate
      {:error "no {...} map found in the reply"}
      (let [r (read* candidate "")]
        (if (and (:error r) (str/starts-with? (:error r) "EDN reader"))
          (if-let [[mended closers] (reclose candidate)]
            (let [r2 (read* mended closers)]
              (if (:episode r2) r2 r))
            r)
          r)))))

(defn parse-plan-edn
  "Defensively parse the LLM's EDN plan. Any parse failure → nil episode
  (the DougakaGovernor then holds it; the system never breaks on malformed
  model output)."
  [content]
  (:episode (parse-plan-edn* content)))

(defn repair-prompt
  "The structured error, sent back once. Measured 2026-08-22 on the fleet
  model with reasoning off: 2 of 6 first answers were structurally wrong EDN
  (a stray `:total_duration 60` inside the scenes vector; unbalanced closing
  brackets) while the content was fine — exactly the class a repair round
  fixes and a bigger budget does not."
  [error]
  (str "Your previous answer was not a valid plan: " error "\n"
       "Return ONLY the corrected single-line EDN map — same content, valid EDN, "
       "no prose, no code fences, no extra keys. The shape is "
       "{:title \"…\" :logline \"…\" :scenes [{:seq 0 :setting \"…\" "
       ":shots [{:seq 0 :prompt \"…\" :duration 8 :subtitle \"…\"}]}]}"))

(defn llm-advisor
  "Advisor backed by a langchain.model/ChatModel. Sealed: returns a PROPOSAL
  only; the DougakaGovernor still censors. gen-opts → model/-generate opts."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-plan [_ _store request]
       (let [messages [{:role :system :content dougaka-system-prompt}
                       {:role :user   :content (build-prompt request)}]
             out (model/-generate chat-model messages gen-opts)
             content (:content out "")
             first-try (parse-plan-edn* content)
             ;; One repair round, with the structured reason. Not a loop: a
             ;; second failure is held by the governor and reported, so a
             ;; model that cannot write EDN today shows up as held runs, not as
             ;; retries nobody counts.
             {:keys [episode error repaired? out2]}
             (if (:episode first-try)
               first-try
               (let [out2 (model/-generate chat-model
                            (conj messages
                                  {:role :assistant :content (str content)}
                                  {:role :user :content (repair-prompt (:error first-try))})
                            gen-opts)
                     second-try (parse-plan-edn* (:content out2 ""))]
                 (assoc second-try :repaired? (boolean (:episode second-try))
                        :out2 out2
                        :error (or (:error second-try) (:error first-try)))))]
         (if episode
           {:summary (str "videollm plan: " (:title episode)
                          (when repaired? " (repaired once)")
                          (when-let [c (:closed (if repaired? (dissoc first-try :episode) first-try))]
                            (str " (closed " (pr-str c) ")")))
            :rationale (if repaired?
                         (str "LLM plan (Murakumo), first answer rejected: " (:error first-try)
                              "; repaired on the second; governor-censored downstream")
                         "LLM plan (Murakumo); governor-censored downstream")
            :episode episode :effect :production
            :confidence (if repaired? 0.5 0.6)}
           (do
             ;; Say WHY on stderr. Measured 2026-08-22: the governor held three
             ;; runs in a row as :no-actuation and nothing said what the model
             ;; had actually returned — the same prompt parsed fine when called
             ;; by hand. Keeping the head of the body is what makes the next
             ;; hold diagnosable instead of a repeat of this one.
             (let [o (or out2 out) c (str (:content o "")) n (count c)
                   line (str "[dougaka.advisor] videollm output unparseable after repair;"
                             " error=" (pr-str error)
                             " chars=" n " stop=" (pr-str (:stop-reason o))
                             " usage=" (pr-str (:usage o))
                             " head=" (pr-str (subs c 0 (min 200 n)))
                             " tail=" (pr-str (subs c (max 0 (- n 200)))))]
               #?(:clj (binding [*out* *err*] (println line))
                  :cljs (js/console.error line)))
             {:summary (str "videollm output unparseable: " error)
              :rationale "malformed plan twice → no episode (governor holds)"
              :episode nil :effect :noop :confidence 0.1})))))))
