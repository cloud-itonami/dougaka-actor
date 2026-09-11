(ns dougaka.store
  "SSoT for the dougaka (動画家) actor — the append-only video ledger behind a
  `Store` protocol so the backend is a swap, not a rewrite (MemStore default ‖
  DatomicStore via langchain.db, itself swappable to real Datomic Local /
  kotoba-server pod e.g. kotobase.net).

  Domain (superproject ADR-2607162200; minidrama ADR-2607071300 同型): a video
  theme → a VideoLLM-proposed production plan (title / logline / scenes /
  shot list for a ≤120 s vertical short video) → DougakaGovernor censoring →
  a committed video plan, optionally announced on app-aozora /videos
  (collection com.etzhayyim.apps.dougaka.video). The append-only ledger is
  the production provenance — every decision (commit / hold / publish) is an
  immutable fact, never overwritten.

  The store talks to its backend ONLY through the langchain.db `:db-api` map
  {:q :transact! :db :pull :entid}. `langchain.db/api` (in-process EAVT) and
  `langchain.kotoba-db/kotoba-api` (kotoba-server XRPC) both implement it, so
  the same `DatomicStore` record runs on either by construction."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [langchain.db :as d]))

(defprotocol Store
  (episode [s id] "the committed video plan for an episode-id, or nil")
  (all-episodes [s])
  (ledger [s])
  (commit-episode! [s id payload] "commit one governor-passed video plan")
  (append-ledger! [s fact] "append one immutable decision fact"))

;; ───────────────────────── MemStore (default) ─────────────────────────

(defrecord MemStore [a]
  Store
  (episode [_ id] (get-in @a [:episodes id]))
  (all-episodes [_] (sort-by :episode-id (vals (:episodes @a))))
  (ledger [_] (:ledger @a))
  (commit-episode! [s id payload] (swap! a assoc-in [:episodes id] payload) s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact))

(defn seed-db
  "An empty MemStore."
  []
  (->MemStore (atom {:episodes {} :ledger []})))

;; ───────────────────────── DatomicStore (langchain.db) ─────────────────────

(def ^:private schema
  {:dougaka.video/id  {:db/unique :db.unique/identity}
   :dougaka.ledger/seq {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- q* [{:keys [api conn]} query & inputs]
  (apply (:q api) query ((:db api) conn) inputs))
(defn- tx* [{:keys [api conn]} txd] ((:transact! api) conn txd))

(defrecord DatomicStore [api conn]
  Store
  (episode [this id]
    (dec* (q* this '[:find ?p . :in $ ?id :where
                     [?e :dougaka.video/id ?id]
                     [?e :dougaka.video/payload ?p]]
               id)))
  (all-episodes [this]
    (->> (q* this '[:find [?id ...] :where [?e :dougaka.video/id ?id]])
         (map #(episode this %)) (sort-by :episode-id)))
  (ledger [this]
    (->> (q* this '[:find ?s ?f :where
                    [?e :dougaka.ledger/seq ?s] [?e :dougaka.ledger/fact ?f]])
         (sort-by first) (mapv (comp dec* second))))
  (commit-episode! [s id payload]
    (tx* s [{:dougaka.video/id id :dougaka.video/payload (enc payload)}]) s)
  (append-ledger! [s fact]
    (tx* s [{:dougaka.ledger/seq (count (ledger s)) :dougaka.ledger/fact (enc fact)}]) fact))

(defn datomic-store
  "DatomicStore on the in-process langchain.db EAVT backend (verifiable
  offline, no network). For the kotoba-server pod (kotobase.net), bind the
  same record to langchain.kotoba-db/kotoba-api — same record, different
  :db-api (see docs/adr/0001-architecture.md).

  PERSIST is an optional sealed append/read port. Remote storage receives the
  transaction stream; this process hydrates it and executes every query
  locally."
  ([] (datomic-store nil))
  ([persist]
   (->DatomicStore d/api (d/create-conn schema persist))))
