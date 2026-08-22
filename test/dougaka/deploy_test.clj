(ns dougaka.deploy-test
  "The LLM endpoint is resolved, never baked (ADR-2607173100). Three rungs,
  each pinned by the :source it reports so a log line says which one won."
  (:require [clojure.test :refer [deftest is testing]]
            [dougaka.advisor :as advisor]
            [dougaka.deploy :as deploy]))

(deftest alias-resolution
  (testing "② the alias answers → its endpoint and serving model"
    (let [r (deploy/resolve-chat-endpoint
             {:alias-fn (fn [url]
                          (is (= deploy/alias-url url))
                          {:endpoint "https://infer.murakumo.cloud/v1/chat/completions"
                           :alias-for "qwen3.8-27b"})})]
      (is (= :alias (:source r)))
      (is (= "https://infer.murakumo.cloud/v1/chat/completions" (:endpoint r)))
      (is (= "qwen3.8-27b" (:model r)))))
  (testing "③ the alias cannot be read → endpoint-only fallback, model = alias name"
    (let [r (deploy/resolve-chat-endpoint {:alias-fn (constantly nil)})]
      (is (= :fallback (:source r)))
      (is (= "murakumo-main" (:model r)))
      (is (string? (:endpoint r)))))
  (testing "③ a malformed alias body is the same as no alias"
    (is (= :fallback (:source (deploy/resolve-chat-endpoint
                               {:alias-fn (constantly {:note "no endpoint key"})}))))))

(deftest resolved-hosts-are-murakumo
  (testing "every rung this resolver can return passes the Rider §2(i) host check"
    (advisor/assert-murakumo! (:endpoint deploy/endpoint-fallback))
    (advisor/assert-murakumo! "https://infer.murakumo.cloud/v1/chat/completions")
    (is (thrown? clojure.lang.ExceptionInfo
                 (advisor/assert-murakumo! "https://api.openai.com/v1/chat/completions")))))
