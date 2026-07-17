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
# Simulate a WAN link on ONLY the browser<->webapp hop (TCP :8080) by running
# the test browser in a network namespace connected to the host by veth, and
# applying tc netem to that veth. guacd:4822 and ssh:22 stay on host loopback,
# unimpaired -- matching production, where only browser<->proxy crosses the WAN.
set -uo pipefail
NS=wan; HOST_IF=veth-host; NS_IF=veth-wan
HOST_IP=10.200.1.1; NS_IP=10.200.1.2; PORT=8080

profile_netem() {
  case "${1:-good}" in
    good)  echo "delay 40ms loss 0.1%" ;;
    poor)  echo "delay 100ms 10ms distribution normal loss 2%" ;;
    lossy) echo "delay 75ms 15ms distribution normal loss 1% reorder 2% 50%" ;;
    highrtt) echo "delay 900ms" ;;
    clean) echo "delay 0ms" ;;
    *) echo "unknown profile: $1" >&2; exit 2 ;;
  esac
}
setup_ns() {
  ip netns list | grep -qw "$NS" || ip netns add "$NS"
  if ! ip -n "$NS" link show "$NS_IF" >/dev/null 2>&1; then
    ip link show "$HOST_IF" >/dev/null 2>&1 && ip link del "$HOST_IF" 2>/dev/null || true
    ip link add "$NS_IF" type veth peer name "$HOST_IF"
    ip link set "$NS_IF" netns "$NS"
  fi
  ip addr replace "$HOST_IP/24" dev "$HOST_IF"; ip link set "$HOST_IF" up
  ip netns exec "$NS" ip addr replace "$NS_IP/24" dev "$NS_IF"
  ip netns exec "$NS" ip link set lo up
  ip netns exec "$NS" ip link set "$NS_IF" up
  ip netns exec "$NS" ip route replace default via "$HOST_IP"
}
apply_dir() { # runner dev match netem
  local run="$1" dev="$2" match="$3" netem="$4"
  $run tc qdisc replace dev "$dev" root handle 1: htb default 10
  $run tc class replace dev "$dev" parent 1: classid 1:10 htb rate 100gbit
  $run tc class replace dev "$dev" parent 1: classid 1:80 htb rate 100gbit
  $run tc qdisc replace dev "$dev" parent 1:80 handle 80: netem $netem
  $run tc filter replace dev "$dev" protocol ip parent 1: prio 1 u32 \
    match ip protocol 6 0xff match ip "$match" "$PORT" 0xffff flowid 1:80
}
enable() {
  tc qdisc del dev "$HOST_IF" root 2>/dev/null || true
  ip netns exec "$NS" tc qdisc del dev "$NS_IF" root 2>/dev/null || true
  local netem; netem="$(profile_netem "$1")"; setup_ns
  apply_dir "" "$HOST_IF" sport "$netem"                       # webapp->browser
  apply_dir "ip netns exec $NS" "$NS_IF" dport "$netem"        # browser->webapp
  echo "wan-mode ENABLED profile=$1 (netem: $netem) on :$PORT"
}
outage() { # 100% loss both dirs for the blip
  ip netns exec "$NS" tc qdisc replace dev "$NS_IF" parent 1:80 handle 80: netem loss 100%
  tc qdisc replace dev "$HOST_IF" parent 1:80 handle 80: netem loss 100%
}
restore() { local netem; netem="$(profile_netem "${1:-good}")"
  ip netns exec "$NS" tc qdisc replace dev "$NS_IF" parent 1:80 handle 80: netem $netem
  tc qdisc replace dev "$HOST_IF" parent 1:80 handle 80: netem $netem
}
disable() {
  tc qdisc del dev "$HOST_IF" root 2>/dev/null || true
  ip netns exec "$NS" tc qdisc del dev "$NS_IF" root 2>/dev/null || true
  echo "wan-mode DISABLED (netem cleared; namespace kept)"
}
teardown() { disable; ip link del "$HOST_IF" 2>/dev/null || true; ip netns del "$NS" 2>/dev/null || true; echo "teardown complete"; }
status() { echo "[host $HOST_IF]"; tc -s qdisc show dev "$HOST_IF" 2>/dev/null; echo "[ns $NS_IF]"; ip netns exec "$NS" tc -s qdisc show dev "$NS_IF" 2>/dev/null; }
case "${1:-}" in
  enable) enable "${2:-good}" ;;
  outage) outage ;;
  restore) restore "${2:-good}" ;;
  disable) disable ;;
  teardown) teardown ;;
  status) status ;;
  *) echo "usage: $0 {enable good|poor|lossy|clean | outage | restore <profile> | disable | teardown | status}" >&2; exit 2 ;;
esac
