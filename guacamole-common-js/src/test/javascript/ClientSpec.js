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

/* global Guacamole, describe, it, jasmine, expect */

describe("Guacamole.Client", function ClientSpec() {

    /**
     * Minimal tunnel implementation for exercising client tunnel lifecycle.
     *
     * @constructor
     */
    var TestTunnel = function TestTunnel() {

        this.oninstruction = null;
        this.connect = jasmine.createSpy('connect');
        this.disconnect = jasmine.createSpy('disconnect');
        this.sendMessage = jasmine.createSpy('sendMessage');

        this.receive = function receive(opcode, parameters) {
            if (this.oninstruction)
                this.oninstruction(opcode, parameters);
        };

    };

    it("should detach and close the previous tunnel when reconnecting", function() {

        var oldTunnel = new TestTunnel();
        var newTunnel = new TestTunnel();
        var client = new Guacamole.Client(oldTunnel);
        var states = [];

        client.onstatechange = function stateChanged(state) {
            states.push(state);
        };

        expect(oldTunnel.oninstruction).not.toBeNull();

        client.reconnect(newTunnel, "resume-data");

        expect(oldTunnel.oninstruction).toBeNull();
        expect(oldTunnel.disconnect).toHaveBeenCalled();
        expect(newTunnel.oninstruction).not.toBeNull();
        expect(newTunnel.connect).toHaveBeenCalledOnceWith("resume-data");

        newTunnel.sendMessage.calls.reset();

        oldTunnel.receive("sync", [ "1234" ]);

        expect(newTunnel.sendMessage).not.toHaveBeenCalled();
        expect(states).not.toContain(Guacamole.Client.State.CONNECTED);

    });

});
