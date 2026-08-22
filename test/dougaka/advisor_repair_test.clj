(ns dougaka.advisor-repair-test
  "The repair round is a contract with the model: one structured reason back,
  one more answer, then the governor. Pinned both ways — a first answer that
  parses never triggers a second call, and a second failure is a noop with
  the reason kept."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dougaka.advisor :as advisor]
            [langchain.model :as model]))

(def good "{:title \"T\" :logline \"L\" :scenes [{:seq 0 :setting \"S\" :shots [{:seq 0 :prompt \"vertical\" :duration 8 :subtitle \"a\"}]}]}")
(def bad-stray
  ;; The real 2026-08-22 sample ended `:total_duration 60}]` — short of a `}`.
  ;; The closer mend turns it into a valid map (an extra key on the scene is
  ;; not a parse failure), so this is a MENDED case, not a repair case.
  "{:title \"T\" :logline \"L\" :scenes [{:seq 0 :setting \"S\" :shots [{:seq 0 :prompt \"p\" :duration 5 :subtitle \"s\"}] :total_duration 60}]")
(def bad-structural
  ;; Not a truncation: :scenes is a string. Only the model can fix this.
  "{:title \"T\" :logline \"L\" :scenes \"see below\"}")
(def bad-unbalanced
  ;; EOF inside the map. NOTE a trailing extra `]` is NOT a failure: the reader
  ;; takes the first form and ignores what follows — found while writing this.
  "{:title \"T\" :scenes [{:seq 0 :shots [{:seq 0 :prompt \"p\" :duration 5}")

(defn scripted-model
  "A ChatModel that answers from a queue and records every call."
  [answers calls]
  (reify model/ChatModel
    (-generate [_ messages _opts]
      (swap! calls conj messages)
      {:role :assistant :content (nth answers (dec (count @calls)) "")})))

(deftest truncated-closers-are-mended-without-the-model
  (testing "counted outside strings"
    (is (= "" (advisor/missing-closers "{:a [1 2] :b \"}]\"}")))
    (is (= "]}" (advisor/missing-closers "{:a [1 2")))
    (is (= "}]}" (advisor/missing-closers "{:scenes [{:shots [{:a \"x]\"}]")))
    (is (nil? (advisor/missing-closers "{:a 1}}")) "too many closers is not a truncation"))
  (testing "the run-5 shape: complete content, final closers dropped"
    (let [r (advisor/parse-plan-edn* "{:title \"T\" :scenes [{:seq 0 :setting \"S\" :shots [{:seq 0 :prompt \"p\" :duration 6 :subtitle \"And so on.\"}]")]
      (is (:episode r) (pr-str r))
      ;; :closed is the whole re-closed tail: the last shot's `}` is stripped
      ;; with the rest of the closing run and put back with what was missing
      (is (= "}]}]}" (:closed r)))
      (is (= "T" (get-in r [:episode :title])))))
  (testing "the run-4 shape: ] and } swapped in the closing run"
    (let [r (advisor/parse-plan-edn* "{:title \"T\" :scenes [{:seq 0 :setting \"S\" :shots [{:seq 0 :prompt \"p\" :duration 8 :subtitle \"Watched\"}]}}")]
      (is (:episode r) (pr-str r))
      (is (= "}]}]}" (:closed r)))))
  (testing "a reader error in the MIDDLE is not mended — only the tail is ever touched"
    (is (:error (advisor/parse-plan-edn* "{:title \"T\" :scenes [{:seq 0 :shots [}] :setting \"S\"}]}")))))

(deftest parse-reasons-are-named
  (is (:episode (advisor/parse-plan-edn* good)))
  (is (:closed (advisor/parse-plan-edn* bad-unbalanced)) "EOF is mended, not an error")
  (is (str/starts-with? (:error (advisor/parse-plan-edn* "{:title \"T\" :scenes [{:seq 0 :shots [}] :setting \"S\"}]}")) "EDN reader")
      "a mismatched closer before the tail is a reader error no re-close can fix")
  (is (= "no {...} map found in the reply" (:error (advisor/parse-plan-edn* "sorry"))))
  (is (= ":scenes must be a vector of scene maps"
         (:error (advisor/parse-plan-edn* "{:title \"T\" :scenes \"nope\"}")))))

(deftest a-good-first-answer-makes-one-call
  (let [calls (atom [])
        p (advisor/-plan (advisor/llm-advisor (scripted-model [good] calls))
                         nil {:theme "x" :duration-target 60})]
    (is (= 1 (count @calls)))
    (is (= :production (:effect p)))
    (is (= "T" (get-in p [:episode :title])))
    (is (not (str/includes? (:summary p) "repaired")))))

(deftest a-mendable-first-answer-makes-one-call
  (let [calls (atom [])
        p (advisor/-plan (advisor/llm-advisor (scripted-model [bad-stray] calls))
                         nil {:theme "x" :duration-target 60})]
    (is (= 1 (count @calls)) "closers are appended locally; no second model call")
    (is (= :production (:effect p)))
    (is (str/includes? (:summary p) "closed"))))

(deftest a-bad-first-answer-is-repaired-once-with-the-reason
  (let [calls (atom [])
        p (advisor/-plan (advisor/llm-advisor (scripted-model [bad-structural good] calls))
                         nil {:theme "x" :duration-target 60})]
    (is (= 2 (count @calls)))
    (testing "the second call carries the first answer and the structured reason"
      (let [msgs (second @calls)]
        (is (= bad-structural (:content (nth msgs 2))))
        (is (str/includes? (:content (nth msgs 3)) ":scenes must be a vector"))
        (is (str/includes? (:content (nth msgs 3)) "Return ONLY the corrected"))))
    (is (= :production (:effect p)))
    (is (str/includes? (:summary p) "repaired once"))
    (is (= 0.5 (:confidence p)))))

(deftest two-bad-answers-are-a-noop-not-a-third-call
  (let [calls (atom [])
        p (advisor/-plan (advisor/llm-advisor (scripted-model [bad-structural "no map here"] calls))
                         nil {:theme "x" :duration-target 60})]
    (is (= 2 (count @calls)) "no loop")
    (is (= :noop (:effect p)))
    (is (nil? (:episode p)))
    (is (str/includes? (:summary p) "unparseable"))))
