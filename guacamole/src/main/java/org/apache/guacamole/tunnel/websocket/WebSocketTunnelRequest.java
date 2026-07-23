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

package org.apache.guacamole.tunnel.websocket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.websocket.server.HandshakeRequest;
import org.apache.guacamole.tunnel.TunnelRequest;

/**
 * WebSocket-specific implementation of TunnelRequest.
 */
public class WebSocketTunnelRequest extends TunnelRequest {

    /**
     * All parameters passed via HTTP to the WebSocket handshake.
     */
    private final Map<String, List<String>> handshakeParameters;

    /**
     * The WebSocket subprotocols requested by the client during the handshake.
     */
    private final List<String> requestedSubprotocols;

    /**
     * Creates a TunnelRequest implementation which delegates parameter and
     * session retrieval to the given HandshakeRequest.
     *
     * @param request The HandshakeRequest to wrap.
     */
    public WebSocketTunnelRequest(HandshakeRequest request) {
        this.handshakeParameters = request.getParameterMap();

        // Requested subprotocols are carried in the Sec-WebSocket-Protocol
        // handshake header (javax.websocket.server.HandshakeRequest does not
        // expose them directly). Each header value may list several,
        // comma-separated. Header names are matched case-insensitively.
        List<String> subprotocols = new ArrayList<String>();
        Map<String, List<String>> headers = request.getHeaders();
        if (headers != null) {
            for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                if (!"sec-websocket-protocol".equalsIgnoreCase(header.getKey())
                        || header.getValue() == null)
                    continue;
                for (String value : header.getValue()) {
                    if (value == null)
                        continue;
                    for (String token : value.split(",")) {
                        token = token.trim();
                        if (!token.isEmpty())
                            subprotocols.add(token);
                    }
                }
            }
        }
        this.requestedSubprotocols = subprotocols;
    }

    @Override
    public String getParameter(String name) {

        // Pull list of values, if present
        List<String> values = getParameterValues(name);
        if (values == null || values.isEmpty())
            return null;

        // Return first parameter value arbitrarily
        return values.get(0);

    }

    @Override
    public List<String> getParameterValues(String name) {
        return handshakeParameters.get(name);
    }

    @Override
    protected List<String> getRequestedSubprotocols() {
        return Collections.unmodifiableList(requestedSubprotocols);
    }

}
