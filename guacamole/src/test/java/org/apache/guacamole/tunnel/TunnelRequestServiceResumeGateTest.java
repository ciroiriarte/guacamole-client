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

import java.util.HashMap;
import java.util.Map;
import org.apache.guacamole.net.auth.Connection;
import org.apache.guacamole.net.auth.simple.SimpleConnection;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests which verify that the per-connection session-resume gate
 * (TunnelRequestService.isResumeEnabled) only permits resume for direct
 * connections that explicitly opt in via the "enable-session-resume"
 * attribute.
 */
public class TunnelRequestServiceResumeGateTest {

    /**
     * The connection attribute which opts a connection in to session resume.
     */
    private static final String ATTRIBUTE = "enable-session-resume";

    /**
     * Returns a Connection whose attributes are exactly the given map.
     *
     * @param attributes
     *     The attributes the returned connection should report.
     *
     * @return
     *     A Connection reporting the given attributes.
     */
    private static Connection connectionWith(final Map<String, String> attributes) {
        return new SimpleConnection() {

            @Override
            public Map<String, String> getAttributes() {
                return attributes;
            }

        };
    }

    /**
     * Returns a Connection whose "enable-session-resume" attribute has the
     * given value, or which lacks the attribute entirely if the value is null.
     *
     * @param value
     *     The value of the resume attribute, or null to omit it.
     *
     * @return
     *     A Connection with the requested resume attribute state.
     */
    private static Connection connectionWithResume(String value) {
        Map<String, String> attributes = new HashMap<String, String>();
        if (value != null)
            attributes.put(ATTRIBUTE, value);
        return connectionWith(attributes);
    }

    /**
     * Verifies that a direct connection explicitly opted in is resumable.
     */
    @Test
    public void testEnabledWhenAttributeTrue() {
        Assert.assertTrue(TunnelRequestService.isResumeEnabled(
                TunnelRequestType.CONNECTION, connectionWithResume("true")));
    }

    /**
     * Verifies that an explicit "false" disables resume.
     */
    @Test
    public void testDisabledWhenAttributeFalse() {
        Assert.assertFalse(TunnelRequestService.isResumeEnabled(
                TunnelRequestType.CONNECTION, connectionWithResume("false")));
    }

    /**
     * Verifies that a value other than "true" does not enable resume.
     */
    @Test
    public void testDisabledWhenAttributeOtherValue() {
        Assert.assertFalse(TunnelRequestService.isResumeEnabled(
                TunnelRequestType.CONNECTION, connectionWithResume("yes")));
    }

    /**
     * Verifies that resume is off by default when the attribute is absent.
     */
    @Test
    public void testDisabledWhenAttributeAbsent() {
        Assert.assertFalse(TunnelRequestService.isResumeEnabled(
                TunnelRequestType.CONNECTION, connectionWithResume(null)));
    }

    /**
     * Verifies that a missing connection is never resumable.
     */
    @Test
    public void testDisabledWhenConnectionNull() {
        Assert.assertFalse(TunnelRequestService.isResumeEnabled(
                TunnelRequestType.CONNECTION, null));
    }

    /**
     * Verifies that shared/active (anonymous) connections are never resumable,
     * even when the underlying connection is opted in.
     */
    @Test
    public void testDisabledForActiveConnection() {
        Assert.assertFalse(TunnelRequestService.isResumeEnabled(
                TunnelRequestType.ACTIVE_CONNECTION, connectionWithResume("true")));
    }

    /**
     * Verifies that balancing groups are never resumable, even when the
     * resolved connection is opted in.
     */
    @Test
    public void testDisabledForConnectionGroup() {
        Assert.assertFalse(TunnelRequestService.isResumeEnabled(
                TunnelRequestType.CONNECTION_GROUP, connectionWithResume("true")));
    }

    /**
     * Verifies that with no per-connection request, the configured maximum is
     * used as the effective grace.
     */
    @Test
    public void testGraceDefaultsToMaxWhenUnrequested() {
        Assert.assertEquals(60, TunnelRequestService.effectiveGraceSeconds(null, 60));
        Assert.assertEquals(300, TunnelRequestService.effectiveGraceSeconds(null, 300));
    }

    /**
     * Verifies that a per-connection request below the maximum is honored
     * unchanged.
     */
    @Test
    public void testGraceRequestBelowMaxHonored() {
        Assert.assertEquals(30, TunnelRequestService.effectiveGraceSeconds(30, 300));
    }

    /**
     * Verifies that a per-connection request above the maximum is capped at the
     * maximum.
     */
    @Test
    public void testGraceRequestAboveMaxCapped() {
        Assert.assertEquals(60, TunnelRequestService.effectiveGraceSeconds(600, 60));
    }

    /**
     * Verifies that negative inputs are floored at zero.
     */
    @Test
    public void testGraceNegativeInputsFlooredAtZero() {
        Assert.assertEquals(0, TunnelRequestService.effectiveGraceSeconds(-5, 60));
        Assert.assertEquals(0, TunnelRequestService.effectiveGraceSeconds(null, -5));
        Assert.assertEquals(0, TunnelRequestService.effectiveGraceSeconds(30, -1));
    }

}
