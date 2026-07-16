# ADR 0001: dougaka actor architecture (R0) — scheduled vertical short-video creator

**Status**: accepted — 2026-07-16
**Deciders**: Jun Kawasaki
**Mirrors**: superproject `com-junkawasaki/root`
`90-docs/adr/2607162200-aozora-creator-scheduled-publishing-integration.md`
（4 層統合設計の正本 — cadence tick / outer loop / generation / governor
auto-publish。その Phase C「dougaka への横展開」の実装分が本 repo）。
テンプレート: sibling `etzhayyim/com-etzhayyim-minidrama`
（repo ADR-0001 R0 + ADR-0002 scheduled auto-publish、keep in sync）。

## Context

aozora creator actor「dougaka（動画家）」— 縦型ショート動画（街歩き /
ライフハック / 観察 vlog / ミニドキュメンタリー）を定期制作・公開する
autonomous actor。superproject ADR-2607162200 で minidrama の Phase A/B
（Publisher 実配線 / CACAO 鍵付き化 / cadence tick / outer loop /
:auto-publish grant）が本番検証され、registry には dougaka の cadence
（`:per-day 1 :active? false`）が登録済み — 残っていた actor repo 本体が
本 repo である。

## Topology

containment + independent governor + append-only ledger
（minidrama / tashikame / tsumugu と同型）:

- **VideoLLM**（`dougaka.advisor`、封じ込め）: theme → 縦型ショート動画の
  production plan proposal（title/logline/scenes/shots）。proposal のみ —
  生成 job も公開も決して自分では行わない。
- **DougakaGovernor**（`dougaka.governor`、別系統）: HARD → HOLD（no
  override）、SOFT → タグ付き commit。gate 構成は minidrama の
  DramaGovernor と同一（no-actuation / over-duration>120s /
  too-many-shots>24 / overlong-shot>10s / content-veto(Rider §2) /
  likeness / unprovenanced-asset / budget-exceeded / rate-limited）——
  汎用縦型動画も同じフォーマット・コンテンツ・予算制約が妥当。
- **台帳**（`dougaka.store`）: video plan は `:dougaka.video/id`、
  全 decision は `:dougaka.ledger/seq` の append-only fact。backend は
  langchain.db `:db-api` map 越しのみ（MemStore ≡ DatomicStore、contract
  test 保証。kotoba-server pod へは同 record + `kotoba-api` で接続）。

## R0 で固定した判断

1. **phase gate は minidrama ADR-0002 改訂版を最初から採用**（初版の
   「phase 2 は human :publish のみ」を経由しない）: phase 2 の承認は
   `:publish`（per-video human sign-off）**または** `:auto-publish`
   （スケジュール outer loop の standing grant、superproject
   ADR-2607162200 Layer D = 2026-07-10 恒久承認の反映）。どの grant で
   公開されたかは台帳 fact `:publish-grant` に監査記録。DougakaGovernor の
   HARD gate は不変の escalation 境界 — HOLD はどの phase・どの grant でも
   announce されず owner へ surface される。
2. **生成・合成は graph 外**。committed plan は dougaka エンジン
   （`gftdcojp/ai-gftd-dougaka/clj` の `dougaka.pipeline`: plan EDN +
   出力 dir → keyframes → ffmpeg → 縦 mp4 + SRT）への発注書。actor 側に
   生成実装を持たない（新規エンジンを書かない — 既存エンジンの消費のみ）。
   chain は `scripts/produce-video.bb`（produce → engine → announce、
   エンジン checkout は west sibling 既定 / `DOUGAKA_ENGINE_DIR` 上書き）。
3. **outer loop = `dougaka.outer-loop`**（1 run = 1 tick 消費、minidrama
   ADR-0002 と同型）: tick は PDS `app.aozora.creator.getTicks?actor=dougaka`
   から読む（actor は tick db に書かない）。消費は自 repo の record
   （collection `com.etzhayyim.apps.dougaka.tick`、rkey `<date>-<slot>`）で
   記録し、これが lease を兼ねる（並行インスタンスは record を見て skip、
   冪等）。episode は `videos/*.edn` カタログの未消費 design を順に採る
   （design-advisor 経由で DougakaGovernor の検閲を通る）。registry の
   dougaka cadence は `:active? false` 起点なので **getTicks 空 = :idle が
   正常** — 稼働開始は registry 1 行の flip（superproject 側の操作）。
   crash recovery R0: `"started"` のまま残った record は `status` で表示、
   owner retry（lease TTL 自動 retry は R1 follow-up）。
4. **鍵付き identity を R0 から**: `dougaka.aozora.app` は projected でなく
   最初から keyed（Ed25519 did:key、`.dougaka/identity.edn` gitignored、
   self-sovereign CACAO で createAccount / updateHandle / createSession →
   uploadBlob / createRecord）。minidrama の Phase A で実証済みの経路。
5. **videos/ カタログは Datomic/Datascript tx-data**（wrap-map ns=video、
   `:video/scenes` は pr-str blob）— minidrama episodes/ の datomize 慣習を
   踏襲。60 秒ちょうど / shot ≤10s / live-action 9:16 / 字幕 + :speaker を
   `video-designs-test` が全数検証する。

## Follow-ups

- registry cadence flip（`:active? true`、superproject 側 1 行）→ launchd
  `com.dougaka.outer-loop`（`deploy/*.plist.tmpl`）常駐化。
- 生成レグの murakumo queue + auction 経路化（ADR-2607162200 Layer C —
  現状はエンジン直呼び。seedance backend は ADR-2607170500 の API key 待ち）。
- RAD identity journal（etzhayyim/root `80-data/kotoba-rad/`、minidrama 同様の
  署名付き登録）。
- lease TTL による "started" スタック record の自動 retry（R1）。
- ON-MESH surface（minidrama `mesh/` 同型の profile/heartbeat guest）は
  意図的に R0 スコープ外。
