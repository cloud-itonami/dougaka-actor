# dougaka-actor (動画家)

縦型（720x1280）45〜90 秒ショート動画（街歩き / ライフハック / 観察 vlog /
ミニドキュメンタリー）を定期制作・公開する autonomous actor。企画 → shot list
までを VideoLLM が *proposal* として提案し、**DougakaGovernor が検閲**して
可決分だけを append-only 台帳に commit する。可決済みプランは dougaka
エンジン（`gftdcojp/ai-gftd-dougaka/clj` の `dougaka.pipeline`）への work
order であり、公開は app-aozora `/videos`（`app.aozora.embed.video`、
ADR-2607071000 経路）。テンプレートは sibling `com-etzhayyim-minidrama`
（keep in sync）。

設計 正本: superproject
`90-docs/adr/2607162200-aozora-creator-scheduled-publishing-integration.md`
（4 層: cadence tick / outer loop / generation / governor auto-publish、
Phase C の dougaka 展開が本 repo）。
actor identity: `dougaka.aozora.app`（keyed did:key、
`aozora.appview.creator-actors` registry — cadence は `:active? false` 起点、
flip は registry 1 行）。

## Overview

```
theme ──▶ :advise (VideoLLM, sealed) ──▶ :govern (DougakaGovernor) ──▶ :decide
                                                        │
                :commit ◀── clean ──────────────────────┴── HARD ──▶ :hold
                  │  SSoT (video plan) + ledger                ledger only
                  └─ phase/approval gate ──▶ Publisher (announce)
```

## StateGraph (one video plan = one run)

`dougaka.operation/build` — intake → advise → govern → decide →
commit | hold。無限内部ループ無し。生成・合成はこの graph に含めない
（committed plan がエンジンへの発注書）。

## DougakaGovernor gates (minidrama と同一構成)

HARD → HOLD（台帳に記録、commit も announce もしない）:
`:no-actuation` `:over-duration`(>120s) `:too-many-shots`(>24)
`:overlong-shot`(>10s) `:content-veto`(Rider §2) `:likeness`
`:unprovenanced-asset` `:budget-exceeded` `:rate-limited`

SOFT → commit + タグ: `:low-confidence`

## Phase rollout

| phase | label | announce |
|---|---|---|
| 0 (default) | draft | しない（台帳のみ） |
| 1 | unlisted | 自動（unlisted preview） |
| 2 | public | **`:publish`（human）または `:auto-publish`（outer loop standing grant、ADR-2607162200 Layer D）がある run だけ** — grant は台帳 `:publish-grant` に監査記録 |

## Scheduled outer loop (ADR-2607162200 Layer A/B)

aozora PDS cron が `creatortick/dougaka/<date>/<slot>` を発行（registry
cadence が `:active? true` の間）→ `dougaka.outer-loop` が 1 run = 1 tick で
消費。消費 record は自 repo の `com.etzhayyim.apps.dougaka.tick`（rkey
`<date>-<slot>`、lease 兼用・冪等）。episode は videos/ カタログの未消費
design を順に採り、chain は `scripts/produce-video.bb`（produce → engine →
announce）。cadence が inactive の間は getTicks が空 = `:idle` が正常。

```bash
clojure -M:dev -m dougaka.outer-loop          # run once (launchd: deploy/*.plist.tmpl)
clojure -M:dev -m dougaka.outer-loop status   # ticks + consumption
```

## Injected seams (each a swap, core unchanged)

- **Store** — `MemStore`（既定）‖ `DatomicStore`（langchain.db `:db-api`、
  kotoba-server pod へも同 record で接続可）
- **Advisor** — `mock-advisor`（既定、決定的）‖ `llm-advisor`
  （`langchain.model` ChatModel、Murakumo fleet 限定 `assert-murakumo!`）
- **Publisher** — `MockPublisher`（既定）‖ 実 app-aozora createRecord
  （`dougaka.aozora`、self-sovereign CACAO）
- **Phase / approvals / budget / daily-cap** — run の `:context`

## Run

```bash
clojure -M:lint       # clj-kondo (errors fail)
clojure -M:dev:test   # cognitect test-runner
clojure -M:dev:run    # offline demo (mock advisor/publisher, MemStore)

# theme 一発でショート動画を製造 (actor→dougaka engine→announce):
bb scripts/produce-video.bb --theme "商店街の朝" --duration 60   # preview (mp4 まで)
bb scripts/produce-video.bb --theme "…" --announce               # 公開 = sign-off

# videos/ のカタログ設計から製造 (手書き設計も同じ DougakaGovernor を通る):
bb scripts/produce-video.bb --plan videos/shotengai-asa.edn [--announce]

# identity (keyed actor):
clojure -M:dev -m dougaka.deploy create-account    # createAccount (self-CACAO)
clojure -M:dev -m dougaka.deploy register-handle   # dougaka.aozora.app keyed flip
```

## videos/ — 縦型ショート動画 設計カタログ (実写前提)

5 本の手書き設計 (60s 縦型 / shot list + ナレーション字幕 + :speaker ヒント /
prompt は live-action 9:16)。全設計は `video-designs-test` で
DougakaGovernor + フォーマット不変条件を全数検証される — **governor を通らない
設計はカタログに置けない**。

| slug | title | genre | 尺 |
|---|---|---|---|
| shotengai-asa | 開店前の商店街を歩く | 街歩きvlog | 60s |
| hyakuen-asagohan | 百円で最強の朝食を組む | ライフハック | 60s |
| neko-no-michi | 猫が決めた通勤路 | 観察vlog | 60s |
| jihanki-meguri | 街角のレトロ自販機をめぐる | ミニドキュメンタリー | 60s |
| shuden-ato-no-homu | 終電のあとのホームで | サウンド観察vlog | 60s |

実写前提のため、エンジン側は photoreal 系 checkpoint を
`DOUGAKA_DEFAULT_CKPT` で指定して製造する (エンジン既定は env 差し替え可)。

## Related files

- `src/dougaka/operation.cljc` — StateGraph
- `src/dougaka/governor.cljc` — DougakaGovernor
- `src/dougaka/advisor.cljc` — VideoLLM (mock ‖ Murakumo LLM)
- `src/dougaka/store.cljc` — Store (MemStore ‖ DatomicStore)
- `src/dougaka/publisher.cljc` — Publisher (Mock ‖ dougaka.aozora)
- `src/dougaka/phase.cljc` — phase 0 draft / 1 unlisted / 2 public+grant
- `src/dougaka/outer_loop.clj` — tick 消費 outer loop (Layer B)
- `scripts/produce-video.bb` — produce → engine → announce orchestrator
- `deploy/com.dougaka.outer-loop.plist.tmpl` — launchd スケジューラ
- `docs/adr/0001-architecture.md` — repo-local design note
