/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.device.session;

import com.aws.greengrass.device.exception.AuthenticationException;
import com.aws.greengrass.device.iot.Certificate;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import lombok.AccessLevel;
import lombok.Getter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton class for managing AuthN and AuthZ sessions.
 */
public class SessionManager {
    private static final Logger logger = LogManager.getLogger(SessionManager.class);
    private static final String SESSION_ID = "SessionId";

    // TODO: Add mechanism to prevent session leaks
    // This implementation currently relies on clients to properly close sessions. If they don't, then sessions
    // will be leaked. We can add this once sessions are created with the entire set of device credentials. However,
    // Moquette is currently creating sessions using just the certificate. Since multiple clients could use the same
    // certificate, we can't de-dup based on this alone.
    @Getter(AccessLevel.PACKAGE)
    private final Map<String, Session> sessionMap = new ConcurrentHashMap<>();

    /**
     * Looks up a session by id.
     *
     * @param sessionId session identifier
     * @return session or null
     */
    public Session findSession(String sessionId) {
        return sessionMap.get(sessionId);
    }

    /**
     * Creates session with certificate.
     *
     * @deprecated Sessions should be created using device credentials instead of certificates
     * @param certificate Client device certificate
     * @return session id
     */
    @Deprecated public String createSession(Certificate certificate) {
        Session session = new SessionImpl(certificate);
        return addSessionInternal(session);
    }

    /**
     * Creates a session with device credentials.
     *
     * @param credentialType Device credential type
     * @param credentialMap  Device credential map
     * @return session id
     * @throws AuthenticationException if device credentials were not able to be validated
     */
    public String createSession(String credentialType, Map<String, String> credentialMap)
            throws AuthenticationException {
        Session session = SessionCreator.createSession(credentialType, credentialMap);
        return addSessionInternal(session);
    }

    /**
     * Closes a session.
     *
     * @param sessionId session identifier
     */
    public void closeSession(String sessionId) {
        logger.atDebug().kv(SESSION_ID, sessionId).log("Closing session");
        closeSessionInternal(sessionId);
    }

    private synchronized void closeSessionInternal(String sessionId) {
        sessionMap.remove(sessionId);
    }

    // Returns a session ID which can be returned to the client
    private synchronized String addSessionInternal(Session session) {
        String sessionId = generateSessionId();
        logger.atDebug().kv(SESSION_ID, sessionId).log("Creating new session");
        sessionMap.put(sessionId, session);
        return sessionId;
    }

    private String generateSessionId() {
        String sessionId;
        do {
            sessionId = UUID.randomUUID().toString();
        } while (sessionMap.containsKey(sessionId));
        return sessionId;
    }
}