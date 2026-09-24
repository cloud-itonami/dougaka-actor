#!/usr/bin/env python3
"""dougaka video production driver (no_agent pre-run).

One measured production step per tick:
  1. mint a murakumo access token for subject "dougaka" (kagi item
     MURAKUMO_GENERATION_TOKEN_SECRET — same secret the generation proxy's
     MURAKUMO_TOKEN_SECRET_2 now verifies; measured 2026-09-05)
  2. submit one video job to oppai.fans's own Worker route (10eros-max,
     512x320 — the measured-good size on gad's 48GB HIP node)
  3. poll until terminal (bounded)
  4. on done: download the mp4 into workspace/works/<job-id>/
  5. append one line to workspace/dougaka-ledger.jsonl

No publish here — the agent (or a follow-up cron) runs the dougaka-vector
publish.cljs on the downloaded work. Failures are recorded as failures.
"""
import json
import os
import subprocess
import time
import urllib.request
import urllib.error
from datetime import datetime, timezone
from pathlib import Path

PROFILE = Path(os.environ.get("HERMES_HOME", Path.home() / ".hermes/profiles/dougaka"))
LEDGER = PROFILE / "workspace" / "dougaka-ledger.jsonl"
KAGI = Path.home() / "github/com-junkawasaki/orgs/kotoba-lang/kagi/bin/kagi"
KAGI_ITEM = "MURAKUMO_CHAT_TOKEN_SECRET_2"  # the value the proxy's _2 verifies (measured)
SUBMIT_URL = "https://generation.murakumo.cloud/api/v1/generation"
POLL_URL = "https://generation.murakumo.cloud/api/v1/generation/jobs/{}"

MODEL = "10eros-max"
W, H, FRAMES, SECONDS = 512, 320, 9, 2
PROMPT = "gentle abstract loop, soft warm light, slow drift"  # SFW default; agent can edit
MAX_POLL_S = 420


def sh(cmd, timeout=120, cwd=None, input_text=None, env=None):
    return subprocess.run(cmd, capture_output=True, text=True,
                          timeout=timeout, cwd=cwd, input=input_text, env=env)


def mint_token():
    k = sh([str(KAGI), "get", KAGI_ITEM], timeout=60)
    if k.returncode != 0 or not k.stdout.strip():
        raise RuntimeError(f"kagi get failed: {k.stderr[:120]}")
    sec = k.stdout.strip()
    r = sh(["clojure", "-M:token", "issue", "dougaka", "generation", "900"],
           timeout=240,
           cwd=str(Path.home() / "github/com-junkawasaki/orgs/network-awai/cloud-murakumo"),
           env={**os.environ, "MURAKUMO_TOKEN_SECRET": sec})
    if r.returncode != 0:
        raise RuntimeError(f"token mint failed: {r.stderr[:200]}")
    return r.stdout.strip().splitlines()[-1]


def post_json(url, body, headers, timeout=60):
    req = urllib.request.Request(
        url, data=json.dumps(body).encode(), method="POST",
        headers={"content-type": "application/json",
                 "user-agent": "dougaka-bot/1.0 (murakumo fleet; cloth-making)",
                 **headers})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, {}
    except Exception as e:
        return None, {"error": str(e)[:150]}


def get_json(url, headers, timeout=30):
    req = urllib.request.Request(url, headers={
        "user-agent": "dougaka-bot/1.0 (murakumo fleet; cloth-making)", **headers})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, {}
    except Exception as e:
        return None, {"error": str(e)[:150]}


def main():
    ts = datetime.now(timezone.utc).isoformat(timespec="seconds")
    row = {"ts": ts}
    f = []
    try:
        token = mint_token()
        auth = {"authorization": f"Bearer {token}"}
        status, body = post_json(SUBMIT_URL, {
            "type": "video", "model": MODEL,
            "input": {"prompt": PROMPT},
            "params": {"width": W, "height": H, "frames": FRAMES, "seconds": SECONDS},
        }, auth)
        row["submit_status"] = status
        job = (body or {}).get("jobId")
        row["job_id"] = job
        if status not in (200, 202) or not job:
            row["error"] = (body or {}).get("error", (body or {}))
            f.append(f"submit failed: {status} {json.dumps(body)[:150]}")
        else:
            deadline = time.monotonic() + MAX_POLL_S
            final = None
            while time.monotonic() < deadline:
                time.sleep(25)
                s, jb = get_json(POLL_URL.format(job), auth)
                if s != 200:
                    continue
                st = jb.get("status")
                row["poll_status"] = st
                if st in ("done", "failed", "cancelled"):
                    final = (st, jb)
                    break
            if final is None:
                f.append(f"poll timeout after {MAX_POLL_S}s (job {job})")
                row["final_status"] = "timeout"
            elif final[0] != "done":
                row["final_status"] = final[0]
                row["error"] = str(final[1].get("error"))[:300]
                f.append(f"job {job} {final[0]}: {str(final[1].get('error'))[:200]}")
            else:
                row["final_status"] = "done"
                work_dir = PROFILE / "workspace" / "works" / job
                work_dir.mkdir(parents=True, exist_ok=True)
                # artifact download through the token-gated route
                req = urllib.request.Request(
                    f"{POLL_URL.format(job)}/artifact", headers={
                        "authorization": auth["authorization"],
                        "user-agent": "dougaka-bot/1.0 (murakumo fleet; cloth-making)"})
                with urllib.request.urlopen(req, timeout=120) as resp:
                    (work_dir / "output.mp4").write_bytes(resp.read())
                size = (work_dir / "output.mp4").stat().st_size
                row["artifact_bytes"] = size
                row["work_dir"] = str(work_dir)
                if size < 10000:
                    f.append(f"artifact suspiciously small: {size}B")
                else:
                    f.append(f"WORK READY: {work_dir} ({size}B) — publish step pending")
    except Exception as e:
        row["error"] = str(e)[:250]
        f.append(f"exception: {str(e)[:200]}")

    LEDGER.parent.mkdir(parents=True, exist_ok=True)
    with LEDGER.open("a") as fh:
        fh.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
    seq = sum(1 for _ in LEDGER.open())
    row["ledger_seq"] = seq
    print(json.dumps({"ok": not any(x.startswith(("submit", "job", "poll", "exception"))
                                    for x in f),
                      "ledger_seq": seq, "findings": f,
                      "snapshot": {k: row.get(k) for k in
                                   ("submit_status", "job_id", "final_status",
                                    "artifact_bytes", "work_dir")}},
                     ensure_ascii=False))


if __name__ == "__main__":
    main()
