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

'use strict';

/**
 * The name of the connection attribute which opts a connection in to automatic
 * session resume. Must match the attribute declared server-side (see the JDBC
 * ModeledConnection and the webapp TunnelRequestService).
 *
 * @type {!String}
 */
var ENABLE_SESSION_RESUME_ATTRIBUTE = 'enable-session-resume';

/**
 * Resolves whether automatic session resume should be attempted for a
 * connection, given whether the target is a direct connection, that
 * connection's attributes (if known), and the global default toggle.
 *
 * This mirrors the authoritative server-side gate in TunnelRequestService:
 * resume is offered only for direct connections whose "enable-session-resume"
 * attribute is explicitly "true". When the attribute is absent (or the
 * connection's attributes are not yet loaded) the global default applies.
 * Connection groups and shared/active (anonymous) connections are never
 * resumable.
 *
 * @param {!Boolean} isDirectConnection
 *     Whether the target is a direct connection, as opposed to a connection
 *     group or a shared/active connection.
 *
 * @param {Object.<String, String>} [attributes]
 *     The connection's attributes, or null/undefined if not yet known.
 *
 * @param {Boolean} [globalDefault=false]
 *     The global fallback applied when the connection specifies no
 *     per-connection preference via its attributes.
 *
 * @returns {!Boolean}
 *     true if automatic session resume should be attempted, false otherwise.
 */
function resolveResumeEnabled(isDirectConnection, attributes, globalDefault) {

    // Connection groups and shared/active connections are never resumable.
    if (!isDirectConnection)
        return false;

    // Until the connection's attributes are known, defer to the global default.
    if (!attributes)
        return !!globalDefault;

    var value = attributes[ENABLE_SESSION_RESUME_ATTRIBUTE];

    // An explicit opt-in enables resume; any other explicit value disables it.
    if (value === 'true')
        return true;
    if (value !== undefined && value !== null)
        return false;

    // No per-connection preference set - use the global default.
    return !!globalDefault;

}

module.exports = {
    ENABLE_SESSION_RESUME_ATTRIBUTE : ENABLE_SESSION_RESUME_ATTRIBUTE,
    resolveResumeEnabled : resolveResumeEnabled
};
