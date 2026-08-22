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

(defn resolve-chat-endpoint
  "Resolution order (CLAUDE.md 'LLM モデル選択'):
   ① env override — DOUGAKA_LLM_URL / DOUGAKA_OLLAMA_URL (+ DOUGAKA_LLM_MODEL)
   ② `murakumo-main` alias → {:endpoint :alias-for}
   ③ endpoint-only fallback (no model name baked)
  Returns {:endpoint :model :source}. `alias-fn` is injectable for tests and
  takes the alias URL, returning the parsed alias map or nil."
  ([] (resolve-chat-endpoint {:alias-fn (fn [url]
                                          (try (let [{:keys [status body]} (jvm-http-fn {:url url :method :get})]
                                                 (when (= 200 status)
                                                   (json/read-str body :key-fn keyword)))
                                               (catch Exception _ nil)))}))
  ([{:keys [alias-fn]}]
   (let [override-url (or (env "DOUGAKA_LLM_URL")
                          (some-> (env "DOUGAKA_OLLAMA_URL")
                                  (str "/v1/chat/completions")))]
     (cond
       override-url
       {:endpoint override-url
        :model (or (env "DOUGAKA_LLM_MODEL") (env "DOUGAKA_OLLAMA_MODEL") "murakumo-main")
        :source :env}

       :else
       (let [a (when alias-fn (alias-fn alias-url))]
         (if (and (map? a) (string? (:endpoint a)) (seq (:endpoint a)))
           {:endpoint (:endpoint a)
            :model (or (env "DOUGAKA_LLM_MODEL") (:alias-for a) "murakumo-main")
            :source :alias}
           endpoint-fallback))))))

(defn murakumo-chat-model
  "Build a langchain.model/openai-model against the Murakumo fleet, resolving
  the endpoint and model through `resolve-chat-endpoint`. Refuses non-Murakumo
  hosts (Rider §2(i)) whatever the resolution said."
  ([] (murakumo-chat-model (resolve-chat-endpoint)))
  ([{:keys [endpoint model source]}]
   (advisor/assert-murakumo! endpoint)
   (binding [*out* *err*]
     (println "[dougaka.deploy] llm" (name (or source :unknown)) "→" endpoint "model=" model))
   (model/openai-model
    {:url        endpoint
     :model      model
     :api-key    (env "MURAKUMO_INFER_TOKEN")
     :http-fn    jvm-http-fn
     :json-write json/write-str
     :json-read  #(json/read-str % :key-fn keyword)})))

(def ollama-chat-model
  "Legacy name kept for callers (dougaka.produce); same resolution."
  murakumo-chat-model)

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
        chat    (murakumo-chat-model)
        adv     (advisor/llm-advisor chat {:max-tokens 1024})
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
