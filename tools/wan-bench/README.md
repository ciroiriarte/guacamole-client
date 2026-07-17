# wan-bench — WAN simulation + UX benchmark harness

Tools for reproducing high-latency/lossy WAN conditions on a LAN test host and
measuring the resulting **user-perceptible** Guacamole experience (typing
latency, connect time, session survival), so the WAN-optimization work can be
validated with before/after numbers rather than intuition.

## What it does

- **`wan-mode.sh`** — injects a simulated WAN link on **only** the
  browser↔webapp hop (TCP `:8080`), leaving guacd↔target on the LAN untouched.
  It runs the test browser in a network namespace (`wan`) connected to the host
  by a `veth` pair, and applies `tc netem` to that link, filtered to port 8080.
  This mirrors production, where only the browser↔reverse-proxy hop crosses the
  WAN.
- **`run-workload.js`** — a headless Chromium (puppeteer) workload that loads
  the real `guacamole-common-js`, opens a WebSocket tunnel to guacd, connects an
  SSH terminal session, and measures: time-to-first-frame, input-to-echo
  latency (keystroke → next rendered frame), round-trip latency
  (`tunnel.onlatency`), display statistics (`display.onstatistics`), and
  WebSocket bytes (via CDP).
- **`run-survival.js` + `survival.sh`** — drive a mid-session network blackout
  (`wan-mode outage` = 100% loss, which keeps the socket open but silent, like a
  real WAN stall) and measure whether the session survives and how long the
  screen freezes.
- **`bench.sh` + `summarize.js`** — run the workload N times per profile and
  summarise p50/p95.
- **`wan-bench.html`** — the minimal page the workload loads; copy it into the
  deployed webapp (`.../webapps/guacamole/wan-bench.html`).

## Prerequisites (test host)

- Full Guacamole stack reachable at `http://<host>:8080/guacamole/` (Tomcat +
  guacd), with a file-auth user `guacadmin/guacadmin` and an SSH connection
  named `ssh-localhost`.
- A **monospace font** installed (guacd's SSH terminal aborts without one).
- **`tc netem`**: on openSUSE the cloud image ships `kernel-default-base`, which
  lacks `sch_netem`/`sch_htb`. Install the full `kernel-default` (it conflicts
  with `-base`, so swap and reboot), then `modprobe sch_netem sch_htb`.
- Node + `puppeteer-core` and a Chromium at `/usr/bin/chromium`.
- Run as root (network namespace + `tc`).

## Usage

```sh
# 1. impair the browser<->webapp hop
./wan-mode.sh enable poor          # good | poor | lossy | clean
./wan-mode.sh status
./wan-mode.sh disable              # clear impairment (keep namespace)
./wan-mode.sh teardown             # remove namespace + veth

# 2. one workload run (Chromium must run inside the namespace)
ip netns exec wan node run-workload.js --profile=poor --echo=10

# 3. full baseline matrix (N runs x {clean,good,poor,lossy}) + summary
./bench.sh 6
node summarize.js baseline-results.jsonl

# 4. session survival across a blip (host-side outage, faithful blackhole)
./survival.sh 8  clean             # short blip
./survival.sh 20 clean             # long blip
```

## Profiles

`netem delay` is **one-way**, so RTT is twice the configured delay.

| Profile | netem (per direction) | approx RTT |
|---|---|---|
| good  | `delay 40ms loss 0.1%` | 80 ms |
| poor  | `delay 100ms 10ms distribution normal loss 2%` | 200 ms |
| lossy | `delay 75ms 15ms distribution normal loss 1% reorder 2% 50%` | 150 ms |
| clean | `delay 0ms` | ~0 |

## Baseline results (unoptimized code)

Captured with `bench.sh 6` + the survival scenarios (see
`baseline-results.jsonl`). p50/p95:

| Profile | TTFF p50/p95 | input-to-echo p50/p95 (ms) | srtt p50 |
|---|---|---|---|
| clean | 431/469 | 4/6 | 2 |
| good  | 569/611 | 84/86 | 82 |
| poor  | 847/897 | 210/586 | 212 |
| lossy | 781/1216 | 157/201 | 164 |

Session survival (mid-session blip):

| Blip | Outcome | Perceived stall |
|---|---|---|
| 8 s  | survives (OPEN→UNSTABLE→OPEN) | ~14 s frozen (TCP retransmit backoff) |
| 20 s | dies (15 s `receiveTimeout` → CLOSED, no reconnect) | session lost |

## Scope / honesty

This rig plus the baseline above is the current deliverable: it proves the
harness can detect user-level WAN pain with precision (measured `srtt` tracks
injected RTT). The **optimized** comparison is deferred until the optimizations
themselves land (adaptive heartbeat, reconnect/resume, input coalescing, …) —
ideally behind runtime toggles so baseline-vs-optimized can be A/B'd within one
build. `input-to-echo` uses a next-frame approximation (cursor-blink noise
averages out over samples); treat it as directional, not sub-millisecond exact.
