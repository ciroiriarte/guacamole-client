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

package org.apache.guacamole.tunnel.http;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.apache.guacamole.tunnel.TunnelRequest;

/**
 * HTTP-specific implementation of TunnelRequest.
 */
public class HTTPTunnelRequest extends TunnelRequest {

    /**
     * A copy of the parameters obtained from the HttpServletRequest used to
     * construct the HTTPTunnelRequest.
     */
    private final Map<String, List<String>> parameterMap =
            new HashMap<String, List<String>>();

    /**
     * The WebSocket subprotocols requested by the client, as read from the
     * "Sec-WebSocket-Protocol" request header. Empty if the header is absent,
     * which is the normal HTTP tunnel case.
     */
    private final List<String> requestedSubprotocols =
            new ArrayList<String>();

    /**
     * Creates a HTTPTunnelRequest which copies and exposes the parameters
     * from the given HttpServletRequest.
     *
     * @param request
     *     The HttpServletRequest to copy parameter values from.
     */
    @SuppressWarnings("unchecked") // getParameterMap() is defined as returning Map<String, String[]>
    public HTTPTunnelRequest(HttpServletRequest request) {

        // For each parameter
        for (Map.Entry<String, String[]> mapEntry : ((Map<String, String[]>)
                request.getParameterMap()).entrySet()) {

            // Get parameter name and corresponding values
            String parameterName = mapEntry.getKey();
            List<String> parameterValues = Arrays.asList(mapEntry.getValue());

            // Store copy of all values in our own map
            parameterMap.put(
                parameterName,
                new ArrayList<String>(parameterValues)
            );

        }

        // Capture any WebSocket subprotocols requested via the
        // "Sec-WebSocket-Protocol" header (each header value may itself be a
        // comma-separated list of subprotocols)
        Enumeration<String> subprotocolHeaders =
                request.getHeaders("Sec-WebSocket-Protocol");
        if (subprotocolHeaders != null) {
            while (subprotocolHeaders.hasMoreElements()) {
                for (String subprotocol : subprotocolHeaders.nextElement().split(",")) {
                    subprotocol = subprotocol.trim();
                    if (!subprotocol.isEmpty())
                        requestedSubprotocols.add(subprotocol);
                }
            }
        }

    }

    @Override
    public String getParameter(String name) {
        List<String> values = getParameterValues(name);

        // Return the first value from the list if available
        if (values != null && !values.isEmpty())
            return values.get(0);

        return null;
    }

    @Override
    public List<String> getParameterValues(String name) {
        return parameterMap.get(name);
    }

    @Override
    protected List<String> getRequestedSubprotocols() {
        return Collections.unmodifiableList(requestedSubprotocols);
    }

}
