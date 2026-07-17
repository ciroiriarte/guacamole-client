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
BLIP=${1:-8}; PROFILE=${2:-clean}; RUN=$((BLIP+22))
./wan-mode.sh enable $PROFILE >/dev/null 2>&1
OUTF=survival-$BLIP.out; : > $OUTF
ip netns exec wan node run-survival.js --seconds=$RUN --blip=$BLIP --profile=$PROFILE >> $OUTF 2>&1 &
PID=$!
for i in $(seq 1 40); do grep -q SURVIVAL_READY $OUTF && break; sleep 1; done
sleep 2; echo "[blip=$BLIP] OUTAGE"; ./wan-mode.sh outage >/dev/null 2>&1
sleep $BLIP; echo "[blip=$BLIP] RESTORE"; ./wan-mode.sh restore $PROFILE >/dev/null 2>&1
wait $PID
./wan-mode.sh disable >/dev/null 2>&1
grep -E 'SURVIVAL |SURVIVAL_ERR' $OUTF
