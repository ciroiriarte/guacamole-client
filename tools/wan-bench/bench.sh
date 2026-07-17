#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
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
