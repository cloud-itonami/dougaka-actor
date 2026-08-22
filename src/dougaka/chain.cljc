(ns dougaka.chain
  "The produce → engine → announce chain as DATA: argument parsing, engine
  checkout resolution and the three step argvs, with no IO. `scripts/
  produce-video.cljs` (nbb) is the only thing that runs them, and the JVM
  test suite pins their shape here — the script used to hold all of this
  inline, where nothing could test it without spawning clojure three times.

  Portable .cljc on purpose: the script loads it under nbb, the tests under
  the JVM, and both see the same functions."
  (:require #?(:clj [clojure.edn] :cljs [cljs.reader])
            [clojure.string :as str]))

(defn opt
  "Value following `flag` in `args`, or nil."
  [args flag]
  (let [v (vec args)]
    (when-let [i (some (fn [[i a]] (when (= flag a) i)) (map-indexed vector v))]
      (get v (inc i)))))

(defn flag? [args flag] (boolean (some #{flag} args)))

(defn parse-args
  "argv -> {:plan-src :theme :id :duration :title :announce? :aspect :no-burn?}
  or {:usage true} when neither --theme nor --plan is given. `now-ms` and
  `design` are injected: the id default needs a clock and the --plan title
  needs the file, neither of which this namespace reads."
  [args {:keys [now-ms design]}]
  (let [plan-src (opt args "--plan")
        theme (or (opt args "--theme") plan-src)]
    (if-not theme
      {:usage true}
      {:plan-src plan-src
       :theme theme
       :id (or (opt args "--id") (:episode-id design) (str "v-" now-ms))
       :duration (opt args "--duration")
       :title (or (opt args "--title") (:title design) theme)
       :announce? (flag? args "--announce")
       :aspect (opt args "--aspect")
       :no-burn? (flag? args "--no-burn")})))

(def engine-candidates
  "Where the dougaka engine checkout may sit relative to this repo: the
  cloud-itonami west sibling (current), then the pre-move gftdcojp layout."
  ["../ai-gftd-dougaka/clj" "../../gftdcojp/ai-gftd-dougaka/clj"])

(defn engine-dir
  "Explicit override wins; else the first candidate whose deps.edn `exists?`;
  else nil (the script then refuses with exit 2 rather than guessing)."
  [{:keys [override here exists? join]}]
  (or (some-> override str str/trim not-empty)
      (->> engine-candidates
           (map #(join here %))
           (filter #(exists? (join % "deps.edn")))
           first)))

(defn plan-step
  "argv for step 1 (the actor: VideoLLM ⊣ DougakaGovernor)."
  [{:keys [plan-src theme id duration]}]
  (if plan-src
    ["clojure" "-M:dev" "-m" "dougaka.produce" "--from" plan-src]
    (cond-> ["clojure" "-M:dev" "-m" "dougaka.produce" theme id]
      duration (conj duration))))

(defn engine-step
  "argv for step 2 (the engine: keyframes → ffmpeg → mp4 + SRT + legs.edn).
  Plain -M: the engine's git deps are pinned, -M:dev needs a west sibling."
  [{:keys [plan-file out-dir aspect no-burn?]}]
  (cond-> ["clojure" "-M" "-m" "dougaka.pipeline" plan-file out-dir]
    aspect (conj "--aspect" aspect)
    no-burn? (conj "--no-burn")))

(defn announce-step
  "argv for step 3 (uploadBlob → app.aozora.embed.video → /videos)."
  [{:keys [mp4 id title]}]
  ["clojure" "-M:dev" "-m" "dougaka.announce" mp4 id title])

(defn unblob [v]
  (if (string? v)
    (let [parsed (try (#?(:clj clojure.edn/read-string :cljs cljs.reader/read-string) v)
                      (catch #?(:clj Exception :cljs :default) _ nil))]
      (if (coll? parsed) parsed v))
    v))

(defn reconstitute-design
  "videos/<slug>.edn tx-data ([{:db/id -1 :video/... ...}]) -> the bare design
  map (strip :db/id + the :video/ namespace, unblob nested collections)."
  [tx]
  (into {} (map (fn [[k v]] [(keyword (name k)) (unblob v)]))
        (dissoc (first tx) :db/id)))
