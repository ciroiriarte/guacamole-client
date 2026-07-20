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

/**
 * Tests the handling of the "multimon-layout" layer parameter. The value of
 * this parameter is supplied by the server and is parsed directly, so a
 * malformed payload must be absorbed rather than allowed to propagate out of
 * the instruction handler and tear down the client.
 */
describe("Guacamole.Client multimon-layout handling", function ClientSpec() {

    /**
     * The client being tested.
     *
     * @type Guacamole.Client
     */
    var client;

    /**
     * A minimal tunnel stub. Guacamole.Client assigns its instruction handler
     * to tunnel.oninstruction, which is how instructions are injected below.
     *
     * @type Object
     */
    var tunnel;

    /**
     * Sends a "set" instruction assigning the given raw value to the
     * "multimon-layout" parameter of the default layer.
     *
     * @param {!string} value
     *     The raw parameter value, as it would arrive from the server.
     */
    function sendLayout(value) {
        tunnel.oninstruction('set', [ '0', 'multimon-layout', value ]);
    }

    beforeEach(function() {

        tunnel = {
            sendMessage    : function() {},
            connect        : function() {},
            disconnect     : function() {},
            isConnected    : function() { return true; },
            oninstruction  : null,
            onerror        : null,
            onstatechange  : null
        };

        client = new Guacamole.Client(tunnel);

    });

    it("should pass a well-formed layout through to the handler", function() {

        var received = null;
        client.onmultimonlayout = function(layout) {
            received = layout;
        };

        sendLayout('{"0":{"left":0,"top":0,"width":2560,"height":1422}}');

        expect(received).not.toBeNull();
        expect(received['0'].width).toBe(2560);
        expect(received['0'].height).toBe(1422);

    });

    it("should not throw when the layout is not valid JSON", function() {

        client.onmultimonlayout = function() {};

        expect(function() {
            sendLayout('{"0":{"left":0,');
        }).not.toThrow();

    });

    it("should not invoke the handler when the layout is not valid JSON", function() {

        var called = false;
        client.onmultimonlayout = function() {
            called = true;
        };

        sendLayout('not json at all');

        expect(called).toBe(false);

    });

    it("should not invoke the handler for a JSON null", function() {

        var called = false;
        client.onmultimonlayout = function() {
            called = true;
        };

        sendLayout('null');

        expect(called).toBe(false);

    });

    it("should not invoke the handler for a non-object JSON value", function() {

        var calls = 0;
        client.onmultimonlayout = function() {
            calls++;
        };

        sendLayout('42');
        sendLayout('"a string"');
        sendLayout('true');

        expect(calls).toBe(0);

    });

    it("should absorb an exception thrown by the handler", function() {

        client.onmultimonlayout = function() {
            throw new Error('consumer failure');
        };

        expect(function() {
            sendLayout('{"0":{"left":0,"top":0,"width":2560,"height":1422}}');
        }).not.toThrow();

    });

    it("should not require a handler to be assigned", function() {

        client.onmultimonlayout = null;

        expect(function() {
            sendLayout('{"0":{"left":0,"top":0,"width":2560,"height":1422}}');
        }).not.toThrow();

    });

});
