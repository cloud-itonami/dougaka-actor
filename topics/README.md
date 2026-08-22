# topics/ — 企画 Bot が読む 2 ファイル

この repo の制作は **Bot 主体**で回る（loop-yakuwari `dougaka` 事業、2026-08-22）。
Bot の 1 tick は repository read が 2 回までなので、必要なものは全部ここにある。

| ファイル | 誰が | 何を |
|---|---|---|
| `topics/backlog.edn` | 企画 Bot が読み、1 件を `:designed` に | topic の待ち行列 |
| `topics/README.md`（このファイル） | 企画 Bot / 検品 Bot が読む | 設計ファイルの **正確な** 形 |

連鎖は: `topics/backlog.edn` → 企画 Bot が `videos/<slug>.edn` を書く（held write、承認で
着地）→ 検品 Bot が bounds を反証 → 夜間 loop（`kotoba-lang/loop-ka-production` の
`:dougaka` channel）が `dougaka.outer-loop` で次の未消費設計を採り、
`scripts/produce-video.cljs` → DougakaGovernor → engine（`cloud-itonami/ai-gftd-dougaka`）
→ mp4 + SRT + `legs.edn` → `record --legs` で採点 → `:clean` だけ announce。

## 設計ファイルの形（`videos/<slug>.edn`）

**Datomic/Datascript の tx-data**: 要素 1 個のベクタ。map は `:db/id -1` と `:video/`
名前空間のキーを持ち、**`:video/scenes` は入れ子データを `pr-str` した文字列**で持つ
（`dougaka.chain/reconstitute-design` が `:video/` を剥がして unblob する）。

**完全な実例（`videos/neko-no-michi.edn` をそのまま転記。このファイルを読めば
3 つ目の read は要らない —— tick の read は 2 回まで）:**

```clojure
[{:db/id -1, :video/episode-id "neko-no-michi", :video/title "猫が決めた通勤路", :video/logline "最短ルートを捨てた朝、案内人は塀の上にいた。", :video/genre "観察vlog", :video/premise :live-action, :video/duration-target 60, :video/scenes "[{:seq 0, :setting \"住宅街の路地・朝\", :shots [{:seq 0, :duration 7, :speaker :narrator, :prompt \"vertical 9:16 live-action, quiet residential alley in the morning, a tabby cat sitting on a low wall looking back at the camera, walking POV, observational vlog\", :subtitle \"いつもの角に、いつもの猫。\"} {:seq 1, :duration 8, :speaker :narrator, :prompt \"vertical 9:16 live-action, the cat hopping off the wall and trotting ahead down the alley, tail up, camera following at a respectful distance, handheld documentary\", :subtitle \"今日は、こっちだと言う。\"} {:seq 2, :duration 7, :speaker :narrator, :prompt \"vertical 9:16 live-action, narrow gap between old wooden fences with morning glories climbing, the cat slipping through, POV pausing at the entrance, soft light\", :subtitle \"人間には少し狭い、正規ルート。\"}]} {:seq 1, :setting \"知らない小道\", :shots [{:seq 0, :duration 8, :speaker :narrator, :prompt \"vertical 9:16 live-action, hidden footpath behind houses, potted plants lining the way, laundry lines overhead, the cat walking ahead, discovery mood, vlog style\", :subtitle \"五年住んで、初めて通る道。\"} {:seq 1, :duration 7, :speaker :narrator, :prompt \"vertical 9:16 live-action, small neighborhood shrine at the end of the path, the cat stopping to groom itself by the stone steps, dappled sunlight, calm observational shot\", :subtitle \"案内料は、見学時間で支払う。\"} {:seq 2, :duration 8, :speaker :narrator, :prompt \"vertical 9:16 live-action, view opening from the path onto the station street below, commuters in the distance, the cat sitting like a signpost at the corner, morning haze\", :subtitle \"近道ではなかった。いい道だった。\"}]} {:seq 2, :setting \"駅前・別れ\", :shots [{:seq 0, :duration 7, :speaker :narrator, :prompt \"vertical 9:16 live-action, the cat turning back up the path away from the station, tail disappearing around the corner, POV watching it leave, gentle ending\", :subtitle \"ここから先は、人間の領域らしい。\"} {:seq 1, :duration 8, :speaker :narrator, :prompt \"vertical 9:16 live-action, station street in full morning light, camera turning back once toward the empty alley corner, quiet documentary closing shot\", :subtitle \"明日も、あの角で待っていてほしい。\"}]}]"}]
```

キーの意味:

| キー | 値 |
|---|---|
| `:video/episode-id` | ファイル名の slug。ASCII kebab-case |
| `:video/title` / `:video/logline` | 日本語 |
| `:video/genre` | 街歩き / ライフハック / 観察vlog / ミニドキュメンタリー |
| `:video/premise` | `:live-action` 固定 |
| `:video/duration-target` | `60` 固定（shot の合計と一致させる） |
| `:video/scenes` | `[{:seq :setting :shots [{:seq :duration :speaker :prompt :subtitle}]}]` を **pr-str した 1 本の文字列** |

## 守る bounds（DougakaGovernor と `test/dougaka/video_designs_test.clj` が機械検査する）

| 検査 | 値 | 超えると |
|---|---|---|
| shot の `:duration` 合計 | **ちょうど 60**（= `:duration-target`） | test 赤 |
| 総尺の天井 | 120 s | governor HOLD `:over-duration` |
| shot 数 | ≤ 24（実用は 6〜9） | HOLD `:too-many-shots` |
| 1 shot | ≤ 10 s（実用は 6〜8） | HOLD `:overlong-shot` |
| `:prompt` | 英語、`vertical 9:16 live-action` を含む | test 赤 |
| `:premise` | `:live-action` | test 赤 |
| 内容 | 実在人物の肖像・ブランド・他社キャラクターを出さない | HOLD `:likeness` / `:content-veto` |
| `:subtitle` | 1 shot 1 行の日本語ナレーション（TTS がこれを読む） | — |

`:seq` は scene / shot とも 0 始まりの連番。`:speaker` は `:narrator` 固定でよい。

## 1 tick でやること（企画 Bot）

1. `topics/backlog.edn` の最初の `:status :open` を取る。無ければ**何もしない**と報告する。
2. `videos/<slug>.edn` を上の形で書く（slug は theme から ASCII kebab-case で付ける）。
3. 同じ commit で backlog のその entry を `:status :designed :design "<slug>"` にする。
4. commit は held write。承認は人（または steward の omakase）が出す — Bot は出さない。

## 1 tick でやること（検品 Bot）

1. backlog の最新 `:designed` を取り、その `videos/<slug>.edn` を読む。
2. 上の表を 1 行ずつ yes/no で測る。
3. 1 つでも no なら最小の反例（shot `:seq` とフィールド）を書いて、entry を
   `:status :open :qa-note "…"` に戻す。全部 yes なら clean と報告して触らない。

## 何がまだ足りないか（正直に、2026-08-22 実測）

- 連鎖は end-to-end で通る（`docs/operator-quickstart.md` §4: 60.04 s、9:16 / 16:9、
  字幕は画に焼き込み済み、`legs.edn` あり）。**ただし legs は
  `{:video [:placeholder ×8] :voice [:local ×8] :subtitles :burned :bed false}`** ——
  画は単色板。2026-08-22 に実 token で測った結果、生成 API は token を受けるが課金層
  `/infer/spend` が proxy の service token を 401 で拒否しており、**billed な生成は
  全 caller で止まっている**（fleet 側の secret 不一致）。設計がどれだけ良くても、
  そこが直るまで `:clean` には届かない。Bot の仕事ではない。
- MoneyPrinterTurbo が持つ「stock footage 検索」の同等物は無い（素材は生成に寄せている）。
