(ns dougaka.operation
  "OperationActor — one video plan = one supervised actor run, expressed as
  a langgraph-clj StateGraph. The VideoLLM (contained intelligence node) is
  sealed into :advise; its proposal is ALWAYS routed through the
  DougakaGovernor (:govern) before anything commits to the SSoT or announces
  on app-aozora. Mirrors the containment + independent-governor +
  append-only-ledger topology (minidrama.operation / tashikame.operation).

  Everything the actor depends on is injected (each a swap, not a rewrite):
    - the Store     (MemStore | DatomicStore | kotoba-server)  — `store` arg
    - the Advisor   (mock VideoLLM | real-LLM on Murakumo)     — :advisor opt
    - the Publisher (Mock | real app-aozora createRecord)      — :publisher opt
    - the Phase     (0 draft → 1 unlisted → 2 public+approval) — :phase in ctx

  One run = intake → advise → govern → decide → commit | hold. NO unbounded
  inner loop. PUBLIC announcement (phase 2) additionally requires an explicit
  approval grant in the run context (:publish human sign-off, or
  :auto-publish — the scheduled outer loop's standing grant, superproject
  ADR-2607162200 Layer D) — the phase gate withholds announcement, and a
  DougakaGovernor HARD violation withholds even the commit. Generation /
  assembly of actual video is NOT in this graph: the committed plan is the
  work order for the dougaka engine (ai-gftd-dougaka `dougaka.pipeline`)."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [dougaka.advisor :as advisor]
            [dougaka.governor :as governor]
            [dougaka.phase :as phase]
            [dougaka.publisher :as publisher]
            [dougaka.store :as store]))

(defn- post-body [episode]
  (str "【動画家】新作『" (:title episode) "』enqueue — " (:logline episode)))

(defn- episode-record [request context proposal visibility]
  (let [ep (:episode proposal)]
    {:episode-id (:episode-id request)
     :actor      (:actor-id context)
     :title      (:title ep)
     :logline    (:logline ep)
     :scenes     (:scenes ep)
     :duration   (advisor/shot-total ep)
     :shots      (advisor/shot-count ep)
     :visibility visibility
     :collection publisher/collection
     :text       (post-body ep)}))

(defn build
  "Compiles the dougaka OperationActor graph bound to `store`. opts:
    :advisor      — a `dougaka.advisor/Advisor` (default: mock-advisor)
    :publisher    — a `dougaka.publisher/Publisher` (default: mock-publisher)
    :checkpointer — langgraph checkpointer (default: in-mem)"
  [store & [{:keys [advisor publisher checkpointer]
             :or   {advisor      (advisor/mock-advisor)
                    publisher    (publisher/mock-publisher)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}   ; actor-id / phase / approvals / budget
         :proposal    {:default nil}
         :verdict     {:default nil}   ; DougakaGovernor result
         :disposition {:default nil}   ; :commit | :hold
         :record      {:default nil}   ; the video plan to commit/announce
         :published   {:default nil}   ; {:uri :cid} when announced
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      ;; VideoLLM (contained intelligence) — proposal only.
      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (advisor/-plan advisor store request)]
            {:proposal p :audit [(advisor/trace request p)]})))

      ;; DougakaGovernor — independent censor (separate system than VideoLLM).
      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (governor/check request context proposal)}))

      ;; Decide: HARD violation → :hold; else :commit.
      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (case (governor/verdict->disposition verdict)
            :hold
            {:disposition :hold
             :audit [(governor/hold-fact request context verdict)]}
            :commit
            (let [ph (:phase context phase/default-phase)
                  visibility (case (long ph) 2 :public 1 :unlisted :draft)]
              {:disposition :commit
               :record (assoc (episode-record request context proposal visibility)
                              :warnings (:warnings verdict))}))))

      ;; Commit — the ONLY node that writes the SSoT + audit ledger, and (when
      ;; the phase/approval gate allows) announces on app-aozora.
      (g/add-node :commit
        (fn [{:keys [request context proposal record]}]
          (let [ph       (:phase context phase/default-phase)
                publish? (and (phase/publish-allowed? ph (:approvals context))
                              (= :production (:effect proposal)))
                ;; audit which grant satisfied phase 2 (ADR-2607162200):
                ;; :publish (human) beats :auto-publish in the record when both
                ;; are present — the more explicit sign-off is the basis.
                grant    (when publish?
                           (first (filter (set (:approvals context)) [:publish :auto-publish])))
                pub      (when publish? (publisher/publish! publisher record))
                f        {:t           :committed
                          :op          (:op request)
                          :actor       (:actor-id context)
                          :episode     (:episode-id request)
                          :disposition :commit
                          :phase       ph
                          :published?  publish?
                          :publish-grant grant
                          :pub         pub
                          :warnings    (:warnings record)
                          :shots       (:shots record)
                          :duration    (:duration record)}]
            (store/commit-episode! store (:episode-id request) (dissoc record :warnings))
            (store/append-ledger! store f)
            {:published pub :audit [f]})))

      ;; Hold — write the rejection to the ledger; no SSoT mutation, no announce.
      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(= :governor-hold (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)
      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition :commit :commit :hold)))
      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph {:checkpointer checkpointer})))
