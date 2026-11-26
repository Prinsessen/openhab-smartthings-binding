/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.smartthingscloud.internal.servlet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.smartthingscloud.internal.handler.SmartThingsCloudAccountHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The authorization servlet's answers, with mocked requests: the overview page, Samsung's error, and the
 * SmartApp lifecycle calls (PING, CONFIGURATION, INSTALL delivering the tokens). Written for the move to
 * Jetty 12 / Jakarta EE 10 (openHAB 5.3), where the servlet moves from {@code javax.servlet} and the
 * HttpService to {@code jakarta.servlet} and the servlet whiteboard: the assertions are the same on both
 * lines; only the imports and {@link #newServlet()} differ.
 *
 * @author Nanna Agesen - Initial contribution
 */
@NonNullByDefault
class SmartThingsCloudServletTest {

    private SmartThingsCloudServlet servlet = newServlet();
    private HttpServletRequest request = mock(HttpServletRequest.class);
    private HttpServletResponse response = mock(HttpServletResponse.class);
    private StringWriter body = new StringWriter();

    /** openHAB 5.3: published to the servlet whiteboard by its component properties, no HttpService. */
    private static SmartThingsCloudServlet newServlet() {
        return new SmartThingsCloudServlet();
    }

    @BeforeEach
    void setUp() throws Exception {
        servlet = newServlet();
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body, true));
    }

    private void postJson(String json) throws Exception {
        when(request.getContentType()).thenReturn("application/json");
        when(request.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
        servlet.doPost(request, response);
    }

    @Test
    void getShowsTheOverviewPage() throws Exception {
        servlet.doGet(request, response);
        verify(response).setContentType(startsWith("text/html"));
        assertTrue(body.toString().contains("<h1>SmartThings Cloud Authorization</h1>"), body.toString());
    }

    @Test
    void getWithSamsungsErrorShowsIt() throws Exception {
        when(request.getParameter("error")).thenReturn("access_denied");
        when(request.getParameter("error_description")).thenReturn("The user said no");
        servlet.doGet(request, response);
        assertTrue(body.toString().contains("access_denied"), body.toString());
        assertTrue(body.toString().contains("The user said no"), body.toString());
    }

    @Test
    void pingAnswersTheChallenge() throws Exception {
        postJson("{\"lifecycle\":\"PING\",\"pingData\":{\"challenge\":\"c-123\"}}");
        verify(response).setStatus(200);
        verify(response).setContentType("application/json");
        assertEquals("{\"pingData\":{\"challenge\":\"c-123\"}}", body.toString());
    }

    @Test
    void configurationInitializeNamesTheFirstPage() throws Exception {
        postJson("{\"lifecycle\":\"CONFIGURATION\",\"configurationData\":{\"phase\":\"INITIALIZE\"}}");
        verify(response).setStatus(200);
        assertTrue(body.toString().contains("\"firstPageId\":\"1\""), body.toString());
    }

    @Test
    void installHandsTheTokensToTheBridge() throws Exception {
        SmartThingsCloudAccountHandler bridge = mock(SmartThingsCloudAccountHandler.class);
        servlet.addBridgeHandler(bridge);
        postJson("{\"lifecycle\":\"INSTALL\",\"installData\":{\"authToken\":\"a-1\",\"refreshToken\":\"r-1\"}}");
        verify(bridge).handleAuthorizationComplete("a-1", "r-1");
    }

    @Test
    void aFormPostWithoutActionIsABadRequest() throws Exception {
        when(request.getContentType()).thenReturn("application/x-www-form-urlencoded");
        servlet.doPost(request, response);
        verify(response).sendError(eq(400), anyString());
    }
}
