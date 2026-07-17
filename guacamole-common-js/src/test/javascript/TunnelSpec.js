/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/* global Guacamole, expect */

describe("Guacamole.WebSocketTunnel.getEffectiveTimeouts", function TunnelSpec() {

    var getEffectiveTimeouts = Guacamole.WebSocketTunnel.getEffectiveTimeouts;

    // The fixed defaults used by the WebSocket tunnel.
    var UNSTABLE = 1500;
    var RECEIVE  = 15000;
    var PING     = 500;

    it("uses the fixed floor values when adaptive timeouts are disabled", function() {
        var eff = getEffectiveTimeouts(false, 2000, 100, UNSTABLE, RECEIVE, PING);
        expect(eff.unstable).toBe(UNSTABLE);
        expect(eff.receive).toBe(RECEIVE);
    });

    it("uses the fixed floor values when no RTT estimate is available", function() {
        expect(getEffectiveTimeouts(true, null, null, UNSTABLE, RECEIVE, PING).unstable).toBe(UNSTABLE);
        expect(getEffectiveTimeouts(true, undefined, undefined, UNSTABLE, RECEIVE, PING).receive).toBe(RECEIVE);
    });

    it("never reduces the timeouts below their floors on a fast link", function() {
        // srtt 20ms, rttvar 5ms => margin 40ms; well below the fixed floors
        var eff = getEffectiveTimeouts(true, 20, 5, UNSTABLE, RECEIVE, PING);
        expect(eff.unstable).toBe(UNSTABLE);
        expect(eff.receive).toBe(RECEIVE);
    });

    it("scales the instability threshold with RTT and jitter", function() {
        // srtt 1800, rttvar 100 => margin 2200 => 500 + 2200 = 2700
        var eff = getEffectiveTimeouts(true, 1800, 100, UNSTABLE, RECEIVE, PING);
        expect(eff.unstable).toBe(2700);
        // receive: 500 + 6*2200 = 13700, still below the 15000 floor
        expect(eff.receive).toBe(RECEIVE);
    });

    it("scales the receive timeout once the margin is large enough", function() {
        // srtt 3000, rttvar 200 => margin 3800
        var eff = getEffectiveTimeouts(true, 3000, 200, UNSTABLE, RECEIVE, PING);
        expect(eff.unstable).toBe(4300);  // 500 + 3800
        expect(eff.receive).toBe(23300);  // 500 + 6*3800
    });

    it("caps both timeouts at their ceilings", function() {
        var eff = getEffectiveTimeouts(true, 100000, 1000, UNSTABLE, RECEIVE, PING);
        expect(eff.unstable).toBe(Guacamole.WebSocketTunnel.ADAPTIVE_UNSTABLE_CEILING);
        expect(eff.receive).toBe(Guacamole.WebSocketTunnel.ADAPTIVE_RECEIVE_CEILING);
    });

});
