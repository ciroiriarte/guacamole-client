#!/usr/bin/env bash
cd /root/wan-harness
OUT=baseline-results.jsonl; : > "$OUT"
URL=http://10.200.1.1:8080/guacamole/wan-bench.html
RUNS=${1:-6}
for prof in clean good poor lossy; do
  ./wan-mode.sh enable $prof >/dev/null 2>&1
  ip netns exec wan node run-workload.js --profile=$prof --echo=10 >/dev/null 2>&1   # warm-up (discard)
  for r in $(seq 1 $RUNS); do
    ip netns exec wan node run-workload.js --profile=$prof --echo=10 | grep '^RUN ' | sed 's/^RUN //' >> "$OUT"
    echo "  $prof run $r/$RUNS"
  done
  ./wan-mode.sh disable >/dev/null 2>&1
done
echo "BENCH_DONE $(wc -l < "$OUT") runs captured"
