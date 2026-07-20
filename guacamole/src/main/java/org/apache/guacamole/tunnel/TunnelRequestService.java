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

package org.apache.guacamole.tunnel;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.GuacamoleResourceNotFoundException;
import org.apache.guacamole.GuacamoleSession;
import org.apache.guacamole.GuacamoleUnauthorizedException;
import org.apache.guacamole.environment.Environment;
import org.apache.guacamole.net.GuacamoleSocket;
import org.apache.guacamole.net.GuacamoleTunnel;
import org.apache.guacamole.net.InetGuacamoleSocket;
import org.apache.guacamole.net.SSLGuacamoleSocket;
import org.apache.guacamole.net.SimpleGuacamoleTunnel;
import org.apache.guacamole.net.auth.AuthenticatedUser;
import org.apache.guacamole.net.auth.Connectable;
import org.apache.guacamole.net.auth.Connection;
import org.apache.guacamole.net.auth.Credentials;
import org.apache.guacamole.net.auth.GuacamoleProxyConfiguration;
import org.apache.guacamole.net.auth.UserContext;
import org.apache.guacamole.net.event.TunnelCloseEvent;
import org.apache.guacamole.net.event.TunnelConnectEvent;
import org.apache.guacamole.rest.auth.AuthenticationService;
import org.apache.guacamole.protocol.ConfiguredGuacamoleSocket;
import org.apache.guacamole.protocol.GuacamoleClientInformation;
import org.apache.guacamole.protocol.GuacamoleConfiguration;
import org.apache.guacamole.rest.event.ListenerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class that takes a standard request from the Guacamole JavaScript
 * client and produces the corresponding GuacamoleTunnel. The implementation
 * of this utility is specific to the form of request used by the upstream
 * Guacamole web application, and is not necessarily useful to applications
 * that use purely the Guacamole API.
 */
@Singleton
public class TunnelRequestService {

    /**
     * Logger for this class.
     */
    private final Logger logger = LoggerFactory.getLogger(TunnelRequestService.class);

    /**
     * The grace window, in milliseconds, during which a resume token issued
     * for a freshly-created tunnel remains valid for rejoining the associated
     * guacd session. This mirrors the server-side grace window during which
     * guacd keeps the connection's process rejoinable after the last user
     * leaves.
     */
    private static final long RESUME_GRACE_PERIOD = 60000;

    /**
     * The name of the connection attribute which enables automatic session
     * resume for that connection. This must match the attribute name declared
     * by the authenticating extension (for example, the JDBC extension's
     * ModeledConnection). When the attribute is absent or not set to "true",
     * a dropped tunnel is not held for resume and the session ends normally.
     */
    private static final String ENABLE_SESSION_RESUME_ATTRIBUTE = "enable-session-resume";

    /**
     * The number of random bytes in a generated resume token. 32 bytes (256
     * bits) is well beyond guessability and matches the strength of the auth
     * tokens the session already relies on.
     */
    private static final int RESUME_TOKEN_BYTES = 32;

    /**
     * Cryptographically strong source of randomness for resume tokens.
     */
    private static final SecureRandom secureRandom = new SecureRandom();

    /**
     * Generates a fresh, opaque, unguessable resume token. The token is a
     * URL-safe Base64 encoding of {@link #RESUME_TOKEN_BYTES} random bytes and
     * carries no meaning of its own - it is purely a bearer key mapped, server
     * side, to a resumable guacd session. A new token is minted for every
     * tunnel and again on every successful resume, so any given token is used
     * at most once.
     *
     * @return
     *     A newly-generated, opaque resume token.
     */
    private static String generateResumeToken() {
        byte[] bytes = new byte[RESUME_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Mints a fresh opaque resume token for the given tunnel, records it in the
     * session's resume registry (pointing at the given guacd connection ID and
     * owned by the given user, valid for one grace window), and stores it on the
     * tunnel so it can be delivered to the client out-of-band. Used both when a
     * tunnel is first created and again after each successful resume, so every
     * token is single-use.
     *
     * @param session
     *     The session whose resume registry should record the token.
     *
     * @param tunnel
     *     The tunnel the token authorizes rejoining. If this is a UserTunnel,
     *     the token is stored on it for delivery to the client.
     *
     * @param guacdConnectionId
     *     The guacd connection ID ($&lt;uuid&gt;) the token authorizes rejoining.
     *
     * @param ownerIdentifier
     *     The identifier of the user permitted to redeem the token.
     *
     * @return
     *     The newly-minted resume token.
     */
    private String registerResumeToken(GuacamoleSession session,
            GuacamoleTunnel tunnel, String guacdConnectionId,
            String ownerIdentifier) {

        String resumeToken = generateResumeToken();
        session.addResumeEntry(resumeToken, guacdConnectionId, ownerIdentifier,
                System.currentTimeMillis() + RESUME_GRACE_PERIOD);

        if (tunnel instanceof UserTunnel)
            ((UserTunnel) tunnel).setResumeToken(resumeToken);

        return resumeToken;

    }

    /**
     * A service for authenticating users from auth tokens.
     */
    @Inject
    private AuthenticationService authenticationService;

    /**
     * The Guacamole server environment, used to determine how to reach guacd
     * when rebinding a reconnecting client to an existing session.
     */
    @Inject
    private Environment environment;

    /**
     * A service for notifying listeners about tunnel connect/closed events.
     */
    @Inject
    private ListenerService listenerService;

    /**
     * Notifies bound listeners that a new tunnel has been connected.
     * Listeners may veto a connected tunnel by throwing any GuacamoleException.
     *
     * @param authenticatedUser
     *      The AuthenticatedUser associated with the user for whom the tunnel
     *      is being created.
     *
     * @param credentials
     *      Credentials that authenticate the user.
     *
     * @param tunnel
     *      The tunnel that was connected.
     *
     * @throws GuacamoleException
     *     If thrown by a listener or if any listener vetoes the connected tunnel.
     */
    private void fireTunnelConnectEvent(AuthenticatedUser authenticatedUser,
            Credentials credentials, GuacamoleTunnel tunnel) throws GuacamoleException {
        listenerService.handleEvent(new TunnelConnectEvent(authenticatedUser,
                credentials, tunnel));
    }

    /**
     * Notifies bound listeners that a tunnel is to be closed.
     * Listeners are allowed to veto a request to close a tunnel by throwing any
     * GuacamoleException.
     *
     * @param authenticatedUser
     *      The AuthenticatedUser associated with the user for whom the tunnel
     *      is being closed.
     *
     * @param credentials
     *      Credentials that authenticate the user.
     *
     * @param tunnel
     *      The tunnel that was connected.
     *
     * @throws GuacamoleException
     *     If thrown by a listener.
     */
    private void fireTunnelClosedEvent(AuthenticatedUser authenticatedUser,
            Credentials credentials, GuacamoleTunnel tunnel)
            throws GuacamoleException {
        listenerService.handleEvent(new TunnelCloseEvent(authenticatedUser,
                credentials, tunnel));
    }

    /**
     * Reads and returns the client information provided within the given
     * request.
     *
     * @param request
     *     The request describing tunnel to create.
     *
     * @return GuacamoleClientInformation
     *     An object containing information about the client sending the tunnel
     *     request.
     *
     * @throws GuacamoleException
     *     If the parameters of the tunnel request are invalid.
     */
    protected GuacamoleClientInformation getClientInformation(TunnelRequest request)
        throws GuacamoleException {

        // Get client information
        GuacamoleClientInformation info = new GuacamoleClientInformation();

        // Set width if provided
        Integer width = request.getWidth();
        if (width != null)
            info.setOptimalScreenWidth(width);

        // Set height if provided
        Integer height = request.getHeight();
        if (height != null)
            info.setOptimalScreenHeight(height);

        // Set resolution if provided
        Integer dpi = request.getDPI();
        if (dpi != null)
            info.setOptimalResolution(dpi);

        // Add audio mimetypes
        List<String> audioMimetypes = request.getAudioMimetypes();
        if (audioMimetypes != null)
            info.getAudioMimetypes().addAll(audioMimetypes);

        // Add video mimetypes
        List<String> videoMimetypes = request.getVideoMimetypes();
        if (videoMimetypes != null)
            info.getVideoMimetypes().addAll(videoMimetypes);

        // Add image mimetypes
        List<String> imageMimetypes = request.getImageMimetypes();
        if (imageMimetypes != null)
            info.getImageMimetypes().addAll(imageMimetypes);
        
        // Set timezone if provided
        String timezone = request.getTimezone();
        if (timezone != null && !timezone.isEmpty())
            info.setTimezone(timezone);

        return info;
    }

    /**
     * Creates a new tunnel using which is connected to the connection or
     * connection group identifier by the given ID. Client information
     * is specified in the {@code info} parameter.
     *
     * @param context
     *     The UserContext associated with the user for whom the tunnel is
     *     being created.
     *
     * @param type
     *     The type of object being connected to (connection or group).
     *
     * @param id
     *     The id of the connection or group being connected to.
     *
     * @param info
     *     Information describing the connected Guacamole client.
     *
     * @param tokens
     *     A Map containing the token names and corresponding values to be
     *     applied as parameter tokens when establishing the connection.
     *
     * @return
     *     A new tunnel, connected as required by the request.
     *
     * @throws GuacamoleException
     *     If an error occurs while creating the tunnel.
     */
    protected GuacamoleTunnel createConnectedTunnel(UserContext context,
            final TunnelRequestType type, String id,
            GuacamoleClientInformation info, Map<String, String> tokens)
            throws GuacamoleException {

        // Retrieve requested destination object
        Connectable connectable = type.getConnectable(context, id);
        if (connectable == null)
            throw new GuacamoleResourceNotFoundException("Requested tunnel "
                    + "destination does not exist.");

        // Connect tunnel to destination
        GuacamoleTunnel tunnel = connectable.connect(info, tokens);
        logger.info("User \"{}\" connected to {} \"{}\".",
                context.self().getIdentifier(), type.NAME, id);
        return tunnel;

    }

    /**
     * Creates a new tunnel which rejoins an existing guacd session, identified
     * by the given guacd connection ID, rather than establishing a fresh
     * connection. This is achieved by connecting directly to guacd and issuing
     * a "select" instruction referencing the existing connection ID (the guacd
     * join path).
     *
     * @param guacdConnectionId
     *     The guacd connection ID ($&lt;uuid&gt;) of the existing session to
     *     rejoin.
     *
     * @param info
     *     Information describing the connected Guacamole client.
     *
     * @return
     *     A new tunnel which has rejoined the existing guacd session.
     *
     * @throws GuacamoleException
     *     If an error occurs while connecting to guacd or if guacd rejects the
     *     attempt to rejoin (for example, because the session is no longer
     *     available).
     */
    protected GuacamoleTunnel createResumedTunnel(String guacdConnectionId,
            GuacamoleClientInformation info) throws GuacamoleException {

        // Determine how to reach guacd
        GuacamoleProxyConfiguration proxy =
                environment.getDefaultGuacamoleProxyConfiguration();

        // Establish the underlying socket to guacd, honoring the configured
        // encryption method
        GuacamoleSocket socket;
        if (proxy.getEncryptionMethod() == GuacamoleProxyConfiguration.EncryptionMethod.SSL)
            socket = new SSLGuacamoleSocket(proxy.getHostname(), proxy.getPort());
        else
            socket = new InetGuacamoleSocket(proxy.getHostname(), proxy.getPort());

        // Request that guacd join the existing connection rather than starting
        // a fresh protocol session. A GuacamoleConfiguration with its
        // connection ID set causes ConfiguredGuacamoleSocket to send
        // "select <connectionID>".
        GuacamoleConfiguration config = new GuacamoleConfiguration();
        config.setConnectionID(guacdConnectionId);

        ConfiguredGuacamoleSocket configuredSocket =
                new ConfiguredGuacamoleSocket(socket, config, info);

        return new SimpleGuacamoleTunnel(configuredSocket);

    }

    /**
     * Returns the guacd connection ID ($&lt;uuid&gt;) associated with the given
     * tunnel, as reported by guacd itself during the "ready" handshake. This is
     * NOT the same as the tunnel's own UUID (as returned by
     * {@link GuacamoleTunnel#getUUID()}), which is a webapp-generated random
     * identifier used only for client/tunnel routing.
     *
     * <p>Note: DelegatingGuacamoleSocket only exposes its wrapped socket via a
     * protected accessor, so arbitrary layers of delegation cannot be unwrapped
     * from outside its package. In practice, tunnels created via
     * SimpleConnection.connect() (and therefore via createConnectedTunnel())
     * wrap a ConfiguredGuacamoleSocket directly, with no additional
     * intervening DelegatingGuacamoleSocket layers, so a direct instanceof
     * check is sufficient here.
     *
     * @param tunnel
     *     The tunnel whose associated guacd connection ID should be returned.
     *
     * @return
     *     The guacd connection ID associated with the given tunnel, or null if
     *     the tunnel is not directly backed by a ConfiguredGuacamoleSocket or
     *     no connection ID was reported by guacd.
     */
    private String getGuacdConnectionId(GuacamoleTunnel tunnel) {

        GuacamoleSocket socket = tunnel.getSocket();

        if (socket instanceof ConfiguredGuacamoleSocket)
            return ((ConfiguredGuacamoleSocket) socket).getConnectionID();

        return null;

    }

    /**
     * Closes any still-active tunnels within the given session that are backed
     * by the given guacd connection ID, other than the tunnel identified by
     * keepUuid. This is used immediately after a successful session resume to
     * reap the "zombie" tunnel left behind by a hard network drop: because the
     * old browser's WebSocket close never reached the webapp, the previous
     * tunnel remains associated with the session (and joined to the shared guacd
     * connection) until the webapp's own guacd read eventually times out, many
     * seconds later. Leaving it in place lets guacd stall the shared client on
     * the unresponsive user and ultimately terminate the whole session, so it
     * must be closed proactively once the resumed tunnel has taken its place.
     *
     * @param session
     *     The session whose tunnels should be examined.
     *
     * @param guacdConnectionId
     *     The guacd connection ID ($&lt;uuid&gt;) whose stale tunnels should be
     *     reaped. If null, no tunnels are reaped.
     *
     * @param keepUuid
     *     The UUID of the tunnel that must NOT be closed (the newly-resumed
     *     tunnel, which is backed by the same guacd connection ID).
     */
    private void reapStaleTunnels(GuacamoleSession session,
            String guacdConnectionId, String keepUuid) {

        if (guacdConnectionId == null)
            return;

        // ConcurrentHashMap's iterator is weakly consistent, so closing a
        // tunnel (which removes it from this same map) during iteration is safe.
        for (GuacamoleTunnel tunnel : session.getTunnels().values()) {

            // Never close the tunnel we just resumed onto
            if (tunnel.getUUID().toString().equals(keepUuid))
                continue;

            // Only reap tunnels bound to the same underlying guacd session
            if (!guacdConnectionId.equals(getGuacdConnectionId(tunnel)))
                continue;

            try {
                logger.debug("Reaping stale tunnel \"{}\" left over from a "
                        + "dropped connection to resumed session \"{}\".",
                        tunnel.getUUID(), guacdConnectionId);
                tunnel.close();
            }
            catch (GuacamoleException e) {
                logger.debug("Failed to reap stale tunnel \"{}\".",
                        tunnel.getUUID(), e);
            }

        }

    }

    /**
     * Associates the given tunnel with the given session, returning a wrapped
     * version of the same tunnel which automatically handles closure and
     * removal from the session.
     *
     * @param tunnel
     *     The connected tunnel to wrap and monitor.
     *
     * @param authToken
     *     The authentication token associated with the given session. If
     *     provided, this token will be automatically invalidated (and the
     *     corresponding session destroyed) if tunnel errors imply that the
     *     user is no longer authorized.
     *
     * @param session
     *     The Guacamole session to associate the tunnel with.
     *
     * @param context
     *     The UserContext associated with the user for whom the tunnel is
     *     being created.
     *
     * @param type
     *     The type of object being connected to (connection or group).
     *
     * @param id
     *     The id of the connection or group being connected to.
     *
     * @return
     *     A new tunnel, associated with the given session, which delegates all
     *     functionality to the given tunnel while monitoring and automatically
     *     handling closure.
     *
     * @throws GuacamoleException
     *     If an error occurs while obtaining the tunnel.
     */
    protected GuacamoleTunnel createAssociatedTunnel(final GuacamoleTunnel tunnel,
            final String authToken, final GuacamoleSession session,
            final UserContext context, final TunnelRequestType type,
            final String id) throws GuacamoleException {

        // Monitor tunnel closure and data
        UserTunnel monitoredTunnel = new UserTunnel(context, tunnel) {

            /**
             * The time the connection began, measured in milliseconds since
             * midnight, January 1, 1970 UTC.
             */
            private final long connectionStartTime = System.currentTimeMillis();

            @Override
            public void close() throws GuacamoleException {

                // Notify listeners to allow close request to be vetoed
                AuthenticatedUser authenticatedUser = session.getAuthenticatedUser();
                fireTunnelClosedEvent(authenticatedUser,
                    authenticatedUser.getCredentials(), tunnel);

                long connectionEndTime = System.currentTimeMillis();
                long duration = connectionEndTime - connectionStartTime;

                logger.info("User \"{}\" disconnected from {} \"{}\". Duration: {} milliseconds",
                        session.getAuthenticatedUser().getIdentifier(),
                        type.NAME, id, duration);

                try {

                    // Close and clean up tunnel
                    session.removeTunnel(getUUID().toString());
                    super.close();

                }

                // Ensure any associated session is invalidated if unauthorized
                catch (GuacamoleUnauthorizedException e) {

                    // If there is an associated auth token, invalidate it
                    if (authenticationService.destroyGuacamoleSession(authToken))
                        logger.debug("Implicitly invalidated session for token \"{}\".", authToken);

                    // Continue with exception processing
                    throw e;

                }

            }

        };

        // Associate tunnel with session
        session.addTunnel(monitoredTunnel);
        return monitoredTunnel;
        
    }

    /**
     * Attempts to rejoin an existing guacd session identified by the given
     * resume token. The resume entry must exist within the given session, must
     * be owned by the given authenticated user, and must not have expired. If
     * these conditions are met, guacd is contacted directly and asked to join
     * the existing connection, and the resulting tunnel is associated with the
     * session and returned. If the resume entry is missing, not owned by the
     * requesting user, expired, or if guacd rejects the rejoin (for example,
     * because the session is no longer available), null is returned to indicate
     * that the caller should proceed with a fresh connection instead.
     *
     * @param resumeToken
     *     The resume token presented by the reconnecting client.
     *
     * @param authToken
     *     The authentication token associated with the given session.
     *
     * @param session
     *     The Guacamole session associated with the requesting user.
     *
     * @param authenticatedUser
     *     The authenticated user requesting the tunnel.
     *
     * @param userContext
     *     The UserContext associated with the requesting user.
     *
     * @param type
     *     The type of object being connected to (connection or group).
     *
     * @param id
     *     The id of the connection or group being connected to.
     *
     * @param info
     *     Information describing the connected Guacamole client.
     *
     * @return
     *     A tunnel which has rejoined the existing guacd session, or null if
     *     the session could not be rejoined and a fresh connection should be
     *     established instead.
     *
     * @throws GuacamoleException
     *     If an error occurs while associating the rejoined tunnel with the
     *     session.
     */
    private GuacamoleTunnel resumeTunnel(String resumeToken, String authToken,
            GuacamoleSession session, AuthenticatedUser authenticatedUser,
            UserContext userContext, TunnelRequestType type, String id,
            GuacamoleClientInformation info) throws GuacamoleException {

        // Verify a matching resume entry exists and has not expired
        GuacamoleSession.ResumeEntry entry = session.getResumeEntry(resumeToken);
        if (entry == null) {
            logger.debug("Resume requested but no valid resume entry exists "
                    + "for the provided token. Falling back to a fresh connection.");
            return null;
        }

        // Verify the resume entry is owned by the requesting user
        if (!entry.getOwnerIdentifier().equals(authenticatedUser.getIdentifier())) {
            logger.warn("User \"{}\" attempted to resume a session not owned by "
                    + "them. Falling back to a fresh connection.",
                    authenticatedUser.getIdentifier());
            return null;
        }

        // Consume the token immediately: it is single-use, so even if the
        // rejoin below fails, this token can never be presented again. A fresh
        // token is minted for the resumed tunnel on success.
        session.removeResumeEntry(resumeToken);

        // Attempt to rejoin the existing guacd session
        String guacdConnectionId = entry.getGuacdConnectionId();
        try {

            GuacamoleTunnel tunnel = createResumedTunnel(guacdConnectionId, info);
            logger.info("User \"{}\" resumed existing session \"{}\".",
                    authenticatedUser.getIdentifier(), guacdConnectionId);

            // Notify listeners to allow connection to be vetoed
            fireTunnelConnectEvent(authenticatedUser,
                    authenticatedUser.getCredentials(), tunnel);

            // Associate the rejoined tunnel with the session so teardown and
            // removal continue to work as with a fresh connection
            GuacamoleTunnel associatedTunnel = createAssociatedTunnel(tunnel,
                    authToken, session, userContext, type, id);

            // Mint a fresh opaque resume token for the resumed tunnel, pointing
            // at the same guacd connection ID, so the client can resume again
            // next time within a new grace window. The previous token was
            // already consumed above, keeping every token single-use.
            registerResumeToken(session, associatedTunnel, guacdConnectionId,
                    authenticatedUser.getIdentifier());

            // Reap the previous (now-abandoned) tunnel(s) for this same guacd
            // session. After a hard network drop the webapp has not yet noticed
            // the old browser is gone - its WebSocket close never arrived - so
            // the previous tunnel lingers as a "zombie" user on the shared guacd
            // connection. If left in place, guacd stalls the shared client on
            // that unresponsive user and eventually tears the whole session down
            // (GUAC_STATUS_UPSTREAM_TIMEOUT), killing the freshly-resumed tunnel
            // too. Reaping it here restores the intended clean hand-off.
            reapStaleTunnels(session, guacdConnectionId,
                    associatedTunnel.getUUID().toString());

            return associatedTunnel;

        }

        // If guacd rejects the rejoin (RESOURCE_NOT_FOUND, socket error, etc.),
        // the session is no longer available; fall back to a fresh connection
        catch (GuacamoleException e) {
            logger.info("Resume failed for session \"{}\", connection no longer "
                    + "available. Falling back to a fresh connection.",
                    guacdConnectionId);
            logger.debug("Rejoin of guacd session failed.", e);
            return null;
        }

    }

    /**
     * Returns whether automatic session resume is enabled for the object
     * targeted by a tunnel request. Resume is offered only for direct
     * connections whose "enable-session-resume" attribute is explicitly set to
     * "true". It is never offered for balancing groups, shared/active
     * connections, or anonymous sessions, all of which must end when their
     * tunnel drops. Any failure to read the attribute is treated as disabled.
     *
     * @param userContext
     *     The UserContext of the user requesting the tunnel, used to look up
     *     the targeted connection and its attributes.
     *
     * @param type
     *     The type of object being connected to.
     *
     * @param id
     *     The identifier of the object being connected to.
     *
     * @return
     *     true if session resume is enabled for the targeted connection,
     *     false otherwise.
     */
    private boolean isSessionResumeEnabled(UserContext userContext,
            TunnelRequestType type, String id) {

        // Only direct connections may be resumed; short-circuit before the
        // directory lookup for balancing groups and shared/active connections.
        if (type != TunnelRequestType.CONNECTION)
            return false;

        try {
            return isResumeEnabled(type, userContext.getConnectionDirectory().get(id));
        }

        // A connection we cannot read is simply not resumable
        catch (GuacamoleException e) {
            logger.debug("Unable to read the \"{}\" attribute for connection "
                    + "\"{}\"; session resume will be disabled for it.",
                    ENABLE_SESSION_RESUME_ATTRIBUTE, id, e);
            return false;
        }

    }

    /**
     * Returns whether session resume is enabled for an already-resolved
     * connection. Resume is offered only for direct connections (type
     * {@link TunnelRequestType#CONNECTION}) whose "enable-session-resume"
     * attribute is explicitly "true"; balancing groups, shared/active
     * (anonymous) connections, and a null connection are never resumable. This
     * is the pure decision behind
     * {@link #isSessionResumeEnabled(UserContext, TunnelRequestType, String)},
     * separated out so it can be unit tested without a UserContext.
     *
     * @param type
     *     The type of object being connected to.
     *
     * @param connection
     *     The resolved connection, or null if none was found.
     *
     * @return
     *     true if session resume is enabled for the connection, false
     *     otherwise.
     */
    static boolean isResumeEnabled(TunnelRequestType type, Connection connection) {
        return type == TunnelRequestType.CONNECTION
                && connection != null
                && "true".equals(connection.getAttributes().get(ENABLE_SESSION_RESUME_ATTRIBUTE));
    }

    /**
     * Creates a new tunnel using the parameters and credentials present in
     * the given request.
     *
     * @param request
     *     The request describing the tunnel to create.
     *
     * @return
     *     The created tunnel, or null if the tunnel could not be created.
     *
     * @throws GuacamoleException
     *     If an error occurs while creating the tunnel.
     */
    public GuacamoleTunnel createTunnel(TunnelRequest request)
            throws GuacamoleException {

        // Parse request parameters
        String authToken                = request.getAuthenticationToken();
        String id                       = request.getIdentifier();
        TunnelRequestType type          = request.getType();
        String authProviderIdentifier   = request.getAuthenticationProviderIdentifier();
        GuacamoleClientInformation info = getClientInformation(request);

        GuacamoleSession session = authenticationService.getGuacamoleSession(authToken);
        AuthenticatedUser authenticatedUser = session.getAuthenticatedUser();
        UserContext userContext = session.getUserContext(authProviderIdentifier);
        
        // Attempt to get the user's name and set it for the tunnel client.
        String name = authenticatedUser.getIdentifier();
        if (name != null)
            info.setName(name);

        // Determine whether the client is attempting to rejoin an existing
        // guacd session via a resume token
        String resumeToken = request.getResumeToken();

        try {

            // Attempt to rebind to an existing guacd session if a resume token
            // was provided and refers to a valid, still-available session owned
            // by the requesting user. On any failure, fall back to a fresh
            // connect below.
            if (resumeToken != null) {

                GuacamoleTunnel resumedTunnel = resumeTunnel(resumeToken,
                        authToken, session, authenticatedUser, userContext,
                        type, id, info);

                if (resumedTunnel != null)
                    return resumedTunnel;

            }

            // Create connected tunnel using provided connection ID and client information
            GuacamoleTunnel tunnel = createConnectedTunnel(userContext, type,
                    id, info, new StandardTokenMap(authenticatedUser));

            // Notify listeners to allow connection to be vetoed
            fireTunnelConnectEvent(authenticatedUser, authenticatedUser.getCredentials(), tunnel);

            // Associate tunnel with session
            GuacamoleTunnel associatedTunnel = createAssociatedTunnel(tunnel,
                    authToken, session, userContext, type, id);

            // Record a resume entry so a later reconnect by this same user may
            // rejoin this guacd session within the grace window. The entry is
            // keyed by a freshly-minted opaque token (NOT the tunnel UUID, which
            // is a client-visible object identifier that appears in request URLs
            // and logs) so that knowledge of the identifier alone does not grant
            // the ability to rejoin. The token is handed to the client
            // out-of-band via the tunnel REST resource; the client presents it
            // back as GUAC_RESUME, and it is invalidated on first use.
            String guacdConnectionId = getGuacdConnectionId(associatedTunnel);
            if (guacdConnectionId == null)
                logger.debug("Tunnel \"{}\" is not backed by a "
                        + "ConfiguredGuacamoleSocket; connection will not be "
                        + "resumable.", associatedTunnel.getUUID());
            else if (!isSessionResumeEnabled(userContext, type, id))
                logger.debug("Session resume is not enabled for connection "
                        + "\"{}\"; tunnel \"{}\" will not be resumable.",
                        id, associatedTunnel.getUUID());
            else
                registerResumeToken(session, associatedTunnel, guacdConnectionId,
                        authenticatedUser.getIdentifier());

            return associatedTunnel;

        }

        // Ensure any associated session is invalidated if unauthorized
        catch (GuacamoleUnauthorizedException e) {

            // If there is an associated auth token, invalidate it
            if (authenticationService.destroyGuacamoleSession(authToken))
                logger.debug("Implicitly invalidated session for token \"{}\".", authToken);

            // Continue with exception processing
            throw e;

        }

    }

}
