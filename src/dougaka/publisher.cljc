(ns dougaka.publisher
  "Publisher — the outbound surface for a dougaka video announcement,
  injected so the network is a swap (MockPublisher default ‖ real app-aozora
  createRecord via dougaka.aozora). The graph never reaches the network
  directly; :commit calls `(publish! publisher record)` only after the
  DougakaGovernor passed AND the phase/approval gate allows announcement
  (dougaka.phase, superproject ADR-2607162200 Layer D).

  record shape (what gets announced):
    {:episode-id :title :logline :text (social-post body)
     :visibility :unlisted|:public
     :collection \"com.etzhayyim.apps.dougaka.video\"}

  Once produced media exists, the aozora announcement carries the
  app.aozora.embed.video embed ({:src <getBlob URL>} for VOD — ADR-2607071000),
  so it plays in aozora /videos.")

(def collection "com.etzhayyim.apps.dougaka.video")

(defprotocol Publisher
  (publish! [p record] "announce one video record → {:uri :cid}"))

(defrecord MockPublisher [a]
  Publisher
  (publish! [_ record]
    (swap! a conj record)
    {:uri (str "at://mock/dougaka/" (:episode-id record))
     :cid (str "mock:" (:episode-id record))}))

(defn mock-publisher
  "Deterministic in-memory publisher (default — records would-be posts).
  Optional atom arg lets a test read back what would have been announced."
  ([] (->MockPublisher (atom [])))
  ([a] (->MockPublisher a)))
