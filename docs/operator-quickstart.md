# Operator quickstart — ai-gftd-dougaka-actor

Every command below was run against this tree on **2026-08-23** (macOS 25.3,
Temurin JVM, nbb, ffmpeg 8 from Homebrew, the engine checked out as the west
sibling `../ai-gftd-dougaka`). The recorded output is what it actually
printed. Steps that do not work are written down as not working.

Three things you can drive independently:

- **the actor alone** — steps 1–3. Plans a video through the DougakaGovernor.
- **the whole chain** — step 4. Plan → engine → a real mp4 with subtitles.
- **the Bots** — step 5. Who writes the designs the chain consumes.

## 1. Tests

```bash
clojure -M:dev:test
```

```
Ran 38 tests containing 278 assertions.
0 failures, 0 errors.
```

`-M:dev` points langgraph/langchain at the west siblings under
`orgs/kotoba-lang/`; outside the workspace it cannot resolve them. If this is
not green, stop.

## 2. Plan one theme with the deterministic advisor

```bash
clojure -M:dev -m dougaka.produce "商店街の朝" qs-1 60
```

```
disposition: commit
plan       : .dougaka/videos/qs-1.edn
title      : 商店街の朝（ショート動画）
shots      : 6 duration: 60.0 s
```

No model is called: `mock-advisor` is the default everywhere. The plan file
is the engine's work order.

## 3. Plan one theme with the fleet LLM

```bash
DOUGAKA_USE_LLM=1 clojure -M:dev -m dougaka.produce "駅の伝言板に残る、誰も消さないチョークの跡" qs-2 60
```

```
[dougaka.deploy] llm config → https://api.murakumo.cloud/v1/chat/completions model= qwen3.8-27b-fastmtp-aggressive thinking= false
disposition: commit
title      : …
shots      : 8 duration: 60.0 s
```

Which model and why is `resources/llm.edn`. Measured 2026-08-22 over 8
consecutive runs: 7 `commit`, 1 `hold` with `basis [:overlong-shot]` (the
governor doing its job), 0 parse failures. A hold prints its basis and
exits 1. When the model's EDN is unparseable twice the run holds as
`:no-actuation` and stderr carries `[dougaka.advisor] videollm output
unparseable after repair; error=… head=… tail=…` — read that line before
blaming the prompt.

## 4. The whole chain — a real mp4 with burned-in subtitles

```bash
nbb --classpath src scripts/produce-video.cljs --plan videos/shotengai-asa.edn --aspect landscape
```

```
=== 1/3 plan (dougaka actor: VideoLLM ⊣ DougakaGovernor) ===
disposition: commit
plan       : .dougaka/videos/shotengai-asa.edn
title      : 開店前の商店街を歩く
shots      : 8 duration: 60.0 s
=== 2/3 produce (dougaka engine: keyframes → ffmpeg) ===
[murakumo] video 0 skipped — no generation token
…
[murakumo] bgm skipped — no generation token
[kotobase] skipped shotengai-asa.mp4 — no KOTOBASE_ARCHIVE_TOKEN in env
video : …/.dougaka/videos/shotengai-asa/shotengai-asa.mp4
srt   : …/.dougaka/videos/shotengai-asa/shotengai-asa.srt
shots : 8
legs  : …/.dougaka/videos/shotengai-asa/legs.edn
codec_name=h264
width=1280
height=720
codec_name=aac
duration=60.042667
=== preview (no --announce) ===
title: 開店前の商店街を歩く | shots: 8 | duration: 60 s
announce するには --announce を付けて再実行 (sign-off)
```

`legs.edn` is the honest part:

```clojure
{:video [:placeholder ×8] :voice [:local ×8] :subtitles :burned :bed false …}
```

The picture is a flat card per shot with the subtitle burned at the lower
third; the narration is macOS `say`. That is what this machine can do with no
generation token, and `loop-ka-production` grades it `DEGRADED` and holds —
correctly. Omit `--aspect` for 720x1280; `--no-burn` keeps the cut flat and
the legs say `:subtitles :sidecar`. Add `--announce` only as the sign-off.

What a token changes, measured 2026-08-22 with the real
`MURAKUMO_DOUGAKA_GENERATION_TOKEN`: the gateway accepts it and the catalog
reads, every leg now states its model (`ai-gftd-dougaka/clj/resources/
generation.edn`), and submit reaches billing — where the ledger refused the
proxy's own service token (`401 … MURAKUMO_SERVICE_TOKEN`). Until that
fleet-side mismatch is fixed, no billed generation runs for anyone, and the
legs stay `:placeholder`. Nothing in this repo can change that.

## 5. The Bots

```bash
~/.cloud-itonami/app/bin/itonami bots list     # dougaka 企画 / 検品 / catalog
~/.cloud-itonami/app/bin/itonami bots handoff --from <検品 id> --to <企画 id> --depth 1 \
  --task "Resident startup job tick for dougaka / dougaka 企画: read topics/backlog.edn and topics/README.md, author ONE design …"
```

Measured 2026-08-22: the handoff completed in 2 rounds and the planner raised
an approval card for `videos/yakimae-no-hanikouchi.edn`. `bots task` (the
person path) returned no text — that path keeps reasoning on over a 512-token
cap, which is the documented no-answer shape; use the handoff or wait for the
resident tick. With omakase on (operator config, ADR-0070 in
cloud-itonami-app) the card decides itself.

## 6. What this quickstart does not cover

- **announce.** Step 4 stops before it; a real post needs the actor's did on
  the PDS and phase ≥ 1. Not run today.
- **the nightly loop on a fleet node.** `loop-ka-production` observe lists
  `dougaka DUE`; the producer it runs is `dougaka.outer-loop`, which calls
  the same script as step 4. Not run on a node today.
