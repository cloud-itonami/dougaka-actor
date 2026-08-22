(ns dougaka.deploy-test
  "The LLM endpoint is resolved, never baked (ADR-2607173100). Three rungs,
  each pinned by the :source it reports so a log line says which one won."
  (:require [clojure.data.json]
            [clojure.test :refer [deftest is testing]]
            [dougaka.advisor :as advisor]
            [dougaka.deploy :as deploy]))

(deftest alias-resolution
  (testing "② the repo's explicit selection wins over the alias, and carries its budget"
    (let [r (deploy/resolve-chat-endpoint
             {:alias-fn (fn [_] (is false "alias must not be consulted when config chose"))
              :config {:endpoint "https://api.murakumo.cloud/v1/chat/completions"
                       :model "qwen3.8-27b-fastmtp-aggressive" :max-tokens 4096}})]
      (is (= :config (:source r)))
      (is (= "qwen3.8-27b-fastmtp-aggressive" (:model r)))
      (is (= 4096 (:max-tokens r)))))
  (testing "② the checked-in resources/llm.edn is the owner's 2026-08-22 choice"
    (let [c (deploy/read-llm-config)]
      (is (= "qwen3.8-27b-fastmtp-aggressive" (:model c)))
      (is (= "https://api.murakumo.cloud/v1/chat/completions" (:endpoint c)))
      (is (<= 2048 (:max-tokens c)) "the owner said the budget may be larger than 1024")
      (is (false? (:thinking? c)) "reasoning on + a 2048 clamp = no answer (measured)")))
  (testing "the no-think switch rides in the request body, and only there"
    (let [seen (atom nil)
          f (deploy/thinking-off-http-fn (fn [req] (reset! seen req) {:status 200 :body "{}"}))]
      (f {:url "u" :method :post :body "{\"model\":\"m\",\"messages\":[]}"})
      (is (= {"enable_thinking" false}
             (get (clojure.data.json/read-str (:body @seen)) "chat_template_kwargs")))
      (is (= "m" (get (clojure.data.json/read-str (:body @seen)) "model")))
      (f {:url "u" :method :get})
      (is (nil? (:body @seen)) "a bodiless request passes through untouched")))
  (testing "③ no config → the alias answers → its endpoint and serving model"
    (let [r (deploy/resolve-chat-endpoint
             {:config nil
              :alias-fn (fn [url]
                          (is (= deploy/alias-url url))
                          {:endpoint "https://infer.murakumo.cloud/v1/chat/completions"
                           :alias-for "qwen3.8-27b"})})]
      (is (= :alias (:source r)))
      (is (= "https://infer.murakumo.cloud/v1/chat/completions" (:endpoint r)))
      (is (= "qwen3.8-27b" (:model r)))
      (is (= deploy/default-max-tokens (:max-tokens r)))))
  (testing "④ the alias cannot be read → endpoint-only fallback, model = alias name"
    (let [r (deploy/resolve-chat-endpoint {:config nil :alias-fn (constantly nil)})]
      (is (= :fallback (:source r)))
      (is (= "murakumo-main" (:model r)))
      (is (string? (:endpoint r)))))
  (testing "④ a malformed alias body is the same as no alias"
    (is (= :fallback (:source (deploy/resolve-chat-endpoint
                               {:config nil :alias-fn (constantly {:note "no endpoint key"})}))))))

(deftest resolved-hosts-are-murakumo
  (testing "every rung this resolver can return passes the Rider §2(i) host check"
    (advisor/assert-murakumo! (:endpoint deploy/endpoint-fallback))
    (advisor/assert-murakumo! (:endpoint (deploy/read-llm-config)))
    (advisor/assert-murakumo! "https://infer.murakumo.cloud/v1/chat/completions")
    (is (thrown? clojure.lang.ExceptionInfo
                 (advisor/assert-murakumo! "https://api.openai.com/v1/chat/completions")))))
