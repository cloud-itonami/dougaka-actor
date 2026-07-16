# dougaka-actor

dougaka (動画家) — 縦型ショート動画（街歩き / ライフハック / 観察 vlog /
ミニドキュメンタリー）を定期制作・公開する aozora creator actor。core
contract は `README.md`、pattern は full-repo `../../../CLAUDE.md` "Actors" 節
（containment + independent governor + append-only ledger）。テンプレートは
sibling `../com-etzhayyim-minidrama`（keep in sync）。
Superproject decision record:
`../../../90-docs/adr/2607162200-aozora-creator-scheduled-publishing-integration.md`
（4 層: cadence tick / outer loop / generation / governor auto-publish、
Phase C の dougaka 展開が本 repo）。
Design 正本: `docs/adr/0001-architecture.md`。

## Invariant

dougaka は DougakaGovernor が拒否したプランを NEVER commit / announce する。
over-duration(>120s) / too-many-shots(>24) / overlong-shot(>10s) /
content-veto(Rider §2) / likeness / unprovenanced-asset / budget-exceeded /
rate-limited は HELD — append-only 台帳に hold として記録され、SSoT には
書かれない。`:commit` だけが Store 書込 + announce を行い、全 commit/hold は
不変の台帳 fact。**public announcement (phase 2) は run context の
approval grant（`:publish` = per-video human sign-off、または
`:auto-publish` = スケジュール outer loop の standing grant、
superproject ADR-2607162200 Layer D）が無い限り行わない**。
`:auto-publish` は 2026-07-10 恒久承認（公開コンテンツの発行も agent 判断で
可）を反映した grant で、**DougakaGovernor の HARD gate（content-veto /
likeness / provenance / budget / rate-cap）が escalation 境界として不変**:
HOLD はどの phase でも announce されず、owner へ surface される。どの grant
で公開されたかは台帳 fact の `:publish-grant` に監査記録される。
unlisted (phase 1) までは grant 無しで自動可。
low-confidence は block せず `:low-confidence` タグで commit（透明性）。
生成・合成はこの actor に実装しない — committed plan は dougaka エンジン
（`gftdcojp/ai-gftd-dougaka/clj` の `dougaka.pipeline`）への発注書。

## Conventions

- `.cljc` for anything portable (operation/governor/advisor/publisher/phase/
  store/sim) — `.clj` は JVM-only I/O（cacao / aozora / announce / produce /
  outer-loop / deploy）のみ。
- actor 自身の Ed25519 identity は `.dougaka/identity.edn`（gitignored）—
  NEVER commit a private key。製造した mp4 も `.dougaka/videos/`（gitignored）。
- `clojure -M:lint`（clj-kondo, errors fail）/ `clojure -M:dev:test`。
- videos/ カタログ設計は Datomic/Datascript tx-data（wrap-map ns=video、
  :video/scenes は pr-str blob）— `video-designs-test` が governor +
  フォーマット不変条件（60s ちょうど / shot ≤10s / live-action / 9:16 /
  subtitle / :speaker）を全数検証する。
