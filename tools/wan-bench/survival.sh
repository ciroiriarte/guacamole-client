#!/usr/bin/env bash
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
