(ns dougaka.deploy
  "Deploy entrypoint — wires a REAL Murakumo-fleet LLM (langchain.model
  OpenAI-compatible, resolved through the `murakumo-main` alias) into the
  dougaka advisor and runs ONE video plan end-to-end.

  Publication is MockPublisher by default: a real aozora announcement needs
  (a) the actor's did registered on the PDS, (b) phase ≥1 (unlisted) or, for
  phase 2 public, a :publish / :auto-publish grant in the run context
  (superproject ADR-2607162200 Layer D), and (c) the real Publisher wired via
  `dougaka.aozora`. This entrypoint proves the real-LLM → governor →
  (mock) announce path against the live Murakumo model.

  Usage: clojure -M:dev -m dougaka.deploy \"<theme>\" [duration-seconds]
         clojure -M:dev -m dougaka.deploy identify-live
         clojure -M:dev -m dougaka.deploy register-handle
         clojure -M:dev -m dougaka.deploy create-account
  Env:   DOUGAKA_LLM_URL     chat-completions endpoint override (①)
         DOUGAKA_LLM_MODEL   model id override (①; default follows the alias)
         DOUGAKA_OLLAMA_URL  legacy: an Ollama base URL (/v1/chat/completions
                             is appended); same rank as DOUGAKA_LLM_URL
         KOTOBA_REPOSITORY_STATE_FILE (required editable state.edn)
         KOTOBA_REPOSITORY_STREAM (optional; default actor/dougaka)"
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [langchain.edn-persist :as edn-persist]
            [langchain.model :as model]
            [langgraph.graph :as g]
            [dougaka.advisor :as advisor]
            [dougaka.aozora :as aozora]
            [dougaka.cacao :as cacao]
            [dougaka.publisher :as publisher]
            [dougaka.store :as store]
            [dougaka.operation :as op])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers])
  (:gen-class))

(defn jvm-http-fn
  "langchain.model :http-fn backed by the JDK HTTP client (no dependency)."
  [{:keys [url method headers body]}]
  (let [b (HttpRequest/newBuilder (URI/create url))]
    (doseq [[k v] headers] (.header b k v))
    (let [req  (-> b (.method (str/upper-case (name (or method :post)))
                             (if body
                               (HttpRequest$BodyPublishers/ofString body)
                               (HttpRequest$BodyPublishers/noBody)))
                   (.build))
          resp (.send (HttpClient/newHttpClient) req (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode resp) :body (.body resp)})))

(def alias-url
  "Fleet main model SSoT (ADR-2607173100). A concrete model id is never baked
  here — switching the fleet's main model is one PUT on this KV entry."
  "https://api.murakumo.cloud/infer/models/murakumo-main")

(def endpoint-fallback
  "Endpoint-only fallback when the alias cannot be read: the serving model
  behind it is still whatever the fleet runs, so the switch still follows.
  `model` is the alias name, which the worker resolves server-side."
  {:endpoint "https://infer.murakumo.cloud/v1/chat/completions"
   :model "murakumo-main"
   :source :fallback})

(defn- env [k] (let [v (System/getenv k)] (when-not (str/blank? v) v)))

(defn read-llm-config
  "resources/llm.edn — the repo's explicit model selection, or nil when the
  file is absent or unreadable (then the alias decides). An explicit choice
  lives in data next to the reason it was made, not in a code default."
  []
  (try (some-> (io/resource "llm.edn") slurp edn/read-string)
       (catch Exception _ nil)))

(def default-max-tokens
  "Plan budget when nothing chose one. A 9-shot EDN plan is ~600 tokens."
  1024)

(defn resolve-chat-endpoint
  "Resolution order (CLAUDE.md 'LLM モデル選択', + the repo's explicit choice):
   ① env override — DOUGAKA_LLM_URL / DOUGAKA_OLLAMA_URL (+ DOUGAKA_LLM_MODEL)
   ② resources/llm.edn — an explicit, reasoned selection checked into the repo
   ③ `murakumo-main` alias → {:endpoint :alias-for}
   ④ endpoint-only fallback (no model name baked)
  Returns {:endpoint :model :max-tokens :thinking? :source}. `alias-fn` (takes the alias
  URL, returns the parsed map or nil) and `config` are injectable for tests."
  ([] (resolve-chat-endpoint {:alias-fn (fn [url]
                                          (try (let [{:keys [status body]} (jvm-http-fn {:url url :method :get})]
                                                 (when (= 200 status)
                                                   (json/read-str body :key-fn keyword)))
                                               (catch Exception _ nil)))
                              :config (read-llm-config)}))
  ([{:keys [alias-fn config]}]
   (let [override-url (or (env "DOUGAKA_LLM_URL")
                          (some-> (env "DOUGAKA_OLLAMA_URL")
                                  (str "/v1/chat/completions")))
         max-tokens (or (some-> (env "DOUGAKA_LLM_MAX_TOKENS") parse-long)
                        (:max-tokens config)
                        default-max-tokens)
         ;; reasoning is OFF unless the config says otherwise; see
         ;; thinking-off-http-fn for the measurement behind the default
         thinking? (boolean (if (contains? (or config {}) :thinking?)
                              (:thinking? config)
                              (= "1" (env "DOUGAKA_LLM_THINKING"))))]
     (cond
       override-url
       {:endpoint override-url
        :model (or (env "DOUGAKA_LLM_MODEL") (env "DOUGAKA_OLLAMA_MODEL") "murakumo-main")
        :max-tokens max-tokens
        :thinking? thinking?
        :source :env}

       (and (map? config) (string? (:endpoint config)) (seq (:endpoint config)))
       {:endpoint (:endpoint config)
        :model (or (env "DOUGAKA_LLM_MODEL") (:model config) "murakumo-main")
        :max-tokens max-tokens
        :thinking? thinking?
        :source :config}

       :else
       (let [a (when alias-fn (alias-fn alias-url))]
         (if (and (map? a) (string? (:endpoint a)) (seq (:endpoint a)))
           {:endpoint (:endpoint a)
            :model (or (env "DOUGAKA_LLM_MODEL") (:alias-for a) "murakumo-main")
            :max-tokens max-tokens
            :thinking? thinking?
            :source :alias}
           (assoc endpoint-fallback :max-tokens max-tokens :thinking? thinking?)))))))

(defn thinking-off-http-fn
  "Wrap an http-fn so the chat body carries
  `chat_template_kwargs.enable_thinking=false`.

  langchain's openai-request-body emits model / messages / max_tokens / tools
  and nothing else, so the Qwen switch has to ride in here. Why it matters,
  measured 2026-08-22 on qwen3.8-27b-fastmtp-aggressive: with thinking on, 3
  of 8 planning calls returned chars=0 stop=length at completion_tokens=2048
  — the whole budget spent reasoning, no text block, governor :no-actuation.
  The gateway clamps completion at 2048 whatever max_tokens asks (asked 4096,
  got finish=length at exactly 2048), so a bigger budget cannot fix it;
  turning reasoning off does (a plan is ~600 tokens). cloud-itonami-app
  recorded the same shape on 2026-08-20: 'capping the budget and leaving
  reasoning on is the same as asking for no answer'."
  [http-fn]
  (fn [{:keys [body] :as req}]
    (http-fn
     (if (string? body)
       (try (let [m (json/read-str body)]
              (assoc req :body (json/write-str
                                (assoc m "chat_template_kwargs" {"enable_thinking" false}))))
            (catch Exception _ req))
       req))))

(defn murakumo-chat-model
  "Build a langchain.model/openai-model against the Murakumo fleet, resolving
  the endpoint and model through `resolve-chat-endpoint`. Refuses non-Murakumo
  hosts (Rider §2(i)) whatever the resolution said. `:thinking?` false (the
  llm.edn default for planning) injects the Qwen no-think switch."
  ([] (murakumo-chat-model (resolve-chat-endpoint)))
  ([{:keys [endpoint model source thinking?]}]
   (advisor/assert-murakumo! endpoint)
   (binding [*out* *err*]
     (println "[dougaka.deploy] llm" (name (or source :unknown)) "→" endpoint
              "model=" model "thinking=" (boolean thinking?)))
   (model/openai-model
    {:url        endpoint
     :model      model
     :api-key    (env "MURAKUMO_INFER_TOKEN")
     :http-fn    (if thinking? jvm-http-fn (thinking-off-http-fn jvm-http-fn))
     :json-write json/write-str
     :json-read  #(json/read-str % :key-fn keyword)})))

(def ollama-chat-model
  "Legacy name kept for callers (dougaka.produce); same resolution."
  murakumo-chat-model)

(defn planning-advisor
  "The real-LLM advisor with the resolved budget: one place decides
  max-tokens, so produce and deploy cannot drift apart again (they did —
  both had 1024 as a literal)."
  ([] (planning-advisor (resolve-chat-endpoint)))
  ([resolved]
   (advisor/llm-advisor (murakumo-chat-model resolved)
                        {:max-tokens (:max-tokens resolved)})))


(defn identify-live
  "Live identify test: generate the actor's self-sovereign did:key, then
  createSession(self-CACAO)→JWT→createRecord a profile record to
  pds.aozora.app. Proves the app-aozora-pds auth flow for dougaka.
  clojure -M:dev -m dougaka.deploy identify-live"
  []
  (let [id  (cacao/load-or-create-identity! ".dougaka/identity.edn")
        pub (aozora/aozora-publisher {:pds        "https://pds.aozora.app"
                                      :identity   id
                                      :json-write json/write-str
                                      :json-read  json/read-str})
        profile {:$type       "com.etzhayyim.apps.dougaka.profile"
                 :collection  "com.etzhayyim.apps.dougaka.profile"
                 :rkey        "self"
                 :displayName "動画家 — Vertical Short-Video Creator Actor"
                 :description "dougaka (動画家) live identify via createSession→createRecord (self-sovereign did:key). Registry handle: dougaka.aozora.app (superproject ADR-2607162200 Phase C)."
                 :lexicons    ["com.etzhayyim.apps.dougaka.video"]}]
    (println "actor did:key :" (:did id))
    (println "createSession→createRecord profile @ pds.aozora.app, repo=" (:did id))
    (try
      (let [r (publisher/publish! pub profile)] (println "PUBLISHED:" r))
      (catch Exception e
        (println "FAILED:" (ex-message e) (pr-str (ex-data e)))))))

(defn register-handle
  "Keyed flip (ADR-2607070400 系列): bind dougaka.aozora.app to the actor's
  own did:key on the PDS via com.atproto.identity.updateHandle. After this,
  resolveHandle returns the did:key (not the did:web fallback) and the appview
  attributes the actor's real records to the friendly handle.
  clojure -M:dev -m dougaka.deploy register-handle"
  []
  (let [id (cacao/load-or-create-identity! ".dougaka/identity.edn")]
    (println "actor did:key :" (:did id))
    (println "updateHandle dougaka.aozora.app → " (:did id) "@ pds.aozora.app")
    (try
      (let [r (aozora/register-handle! {:pds        "https://pds.aozora.app"
                                        :identity   id
                                        :handle     "dougaka.aozora.app"
                                        :json-write json/write-str
                                        :json-read  json/read-str})]
        (println "REGISTERED:" r))
      (catch Exception e
        (println "FAILED:" (ex-message e) (pr-str (ex-data e)))))))

(defn create-account
  "createAccount 昇格 (ADR-2607071700 follow-up): persist the actor's
  `:atproto.account/*` datom on the PDS with a fresh self-CACAO proof, so
  getAccount answers for dougaka.aozora.app (account-store 整合).
  clojure -M:dev -m dougaka.deploy create-account"
  []
  (let [id (cacao/load-or-create-identity! ".dougaka/identity.edn")]
    (println "actor did:key :" (:did id))
    (println "createAccount dougaka.aozora.app @ pds.aozora.app")
    (try
      (let [r (aozora/create-account! {:pds        "https://pds.aozora.app"
                                       :identity   id
                                       :handle     "dougaka.aozora.app"
                                       :json-write json/write-str
                                       :json-read  json/read-str})]
        (println "ACCOUNT:" r))
      (catch Exception e
        (println "FAILED:" (ex-message e) (pr-str (ex-data e)))))))

(defn -main
  [& args]
  (when (= (first args) "identify-live") (identify-live) (System/exit 0))
  (when (= (first args) "register-handle") (register-handle) (System/exit 0))
  (when (= (first args) "create-account") (create-account) (System/exit 0))
  (let [[theme dur] (if (seq args) args ["商店街の朝、開店前の音" nil])
        adv     (planning-advisor)
        s       (store/datomic-store
                 (edn-persist/required-persist-from-env "actor/dougaka"))
        pub     (publisher/mock-publisher)
        actor   (op/build s {:advisor adv :publisher pub})
        eid     "deploy-1"
        req     {:op :episode/plan :episode-id eid :theme theme
                 :duration-target (when dur (parse-long dur))}
        r       (g/run* actor {:request req :context {:actor-id "dougaka" :phase 1}}
                         {:thread-id eid})]
    (println "=== dougaka deploy (real LLM @ Murakumo, murakumo-main alias) ===")
    (println "theme      :" theme)
    (println "disposition:" (get-in r [:state :disposition]))
    (println "title      :" (:title (store/episode s eid)))
    (println "shots      :" (:shots (store/episode s eid))
             "duration:" (:duration (store/episode s eid)) "s")
    (println "announced? :" (boolean (get-in r [:state :published])) "(mock publisher)")
    (println "ledger tail:" (pr-str (last (store/ledger s))))))
