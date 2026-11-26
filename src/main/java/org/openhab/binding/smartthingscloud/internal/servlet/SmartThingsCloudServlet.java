/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.smartthingscloud.internal.servlet;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.openhab.binding.smartthingscloud.SmartThingsCloudBindingConstants.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.smartthingscloud.internal.dto.STTokenResponse;
import org.openhab.binding.smartthingscloud.internal.handler.SmartThingsCloudAccountHandler;
import org.openhab.core.thing.ThingStatus;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.ServiceScope;
import org.osgi.service.servlet.whiteboard.HttpWhiteboardConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * OAuth2 PKCE authorization servlet for SmartThings Cloud.
 *
 * <p>
 * Registered at {@code /smartthingscloud}. Provides:
 * <ul>
 * <li>GET /smartthingscloud — overview page with Authorize button per bridge</li>
 * <li>POST /smartthingscloud — action=authorize → generates PKCE and redirects to Samsung</li>
 * <li>GET /smartthingscloud?code=&state= — OAuth2 callback, exchanges code for tokens</li>
 * </ul>
 *
 * <p>
 * Uses PKCE (code_verifier / code_challenge) — no client_secret required.
 *
 * @author openHAB SmartThings Cloud Binding - Initial contribution
 */
@NonNullByDefault
@Component(service = { Servlet.class,
        SmartThingsCloudServlet.class }, scope = ServiceScope.SINGLETON, immediate = true, property = {
                HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN + "=" + SmartThingsCloudServlet.SERVLET_PATH,
                HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN + "=" + SmartThingsCloudServlet.SERVLET_PATH
                        + "/*",
                HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME + "=smartthingscloud-auth" })
public class SmartThingsCloudServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    static final String SERVLET_PATH = "/smartthingscloud";
    private static final int CALLBACK_PORT = 61973;
    private static final String REDIRECT_URI = "http://localhost:" + CALLBACK_PORT + "/finish";

    private final Logger logger = LoggerFactory.getLogger(SmartThingsCloudServlet.class);
    private final Gson gson = new GsonBuilder().create();

    /** Registered bridge handlers — keyed by thing UID string. */
    private final Set<SmartThingsCloudAccountHandler> bridgeHandlers = new CopyOnWriteArraySet<>();
    /** Pending PKCE code verifiers — keyed by bridge UID, valid until callback completes. */
    private final Map<String, String> pendingCodeVerifiers = new ConcurrentHashMap<>();

    // openHAB 5.3: published to the servlet whiteboard at SERVLET_PATH (the component's properties) - the
    // HttpService of 5.2 and its registerServlet()/unregister() no longer exist.

    @Deactivate
    protected void dispose() {
        bridgeHandlers.clear();
        pendingCodeVerifiers.clear();
    }

    // ── Handler registration ──────────────────────────────────────────────────

    public void addBridgeHandler(SmartThingsCloudAccountHandler handler) {
        bridgeHandlers.add(handler);
    }

    public void removeBridgeHandler(SmartThingsCloudAccountHandler handler) {
        bridgeHandlers.remove(handler);
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    @Override
    protected void doGet(@Nullable HttpServletRequest request, @Nullable HttpServletResponse response)
            throws IOException {
        if (request == null || response == null)
            return;
        response.setContentType("text/html; charset=UTF-8");

        // Callback now handled via ServerSocket on port 61973 — just show overview
        String error = request.getParameter("error");
        String errorDesc = request.getParameter("error_description");
        if (error != null) {
            logger.warn("SmartThings GET error: {} — {}", error, errorDesc);
            sendError(response, "Samsung returned error: " + error + " — " + errorDesc);
            return;
        }
        showOverview(response);
    }

    @Override
    protected void doPost(@Nullable HttpServletRequest request, @Nullable HttpServletResponse response)
            throws IOException {
        if (request == null || response == null)
            return;

        // SmartThings lifecycle PING verification (webhook registration)
        String contentType = request.getContentType();
        if (contentType != null && contentType.contains("application/json")) {
            try {
                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader reader = request.getReader()) {
                    String line;
                    while ((line = reader.readLine()) != null)
                        sb.append(line);
                }
                String body = sb.toString();
                com.google.gson.JsonObject json = gson.fromJson(body, com.google.gson.JsonObject.class);
                if (json == null) {
                    return;
                }
                String lifecycle = getJsonString(json, "lifecycle");
                if ("PING".equals(lifecycle)) {
                    com.google.gson.JsonObject pingData = json.has("pingData") ? json.getAsJsonObject("pingData")
                            : null;
                    String challenge = pingData != null && pingData.has("challenge")
                            ? pingData.get("challenge").getAsString()
                            : "";
                    logger.info("SmartThings PING verification received, challenge={}", challenge);
                    response.setContentType("application/json");
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.getWriter().write("{\"pingData\":{\"challenge\":\"" + challenge + "\"}}");
                    return;
                }
                if ("CONFIRMATION".equals(lifecycle)) {
                    com.google.gson.JsonObject confirmationData = json.has("confirmationData")
                            ? json.getAsJsonObject("confirmationData")
                            : null;
                    String confirmationUrl = confirmationData != null && confirmationData.has("confirmationUrl")
                            ? confirmationData.get("confirmationUrl").getAsString()
                            : "";
                    String appId = confirmationData != null && confirmationData.has("appId")
                            ? confirmationData.get("appId").getAsString()
                            : "";
                    logger.info("SmartThings CONFIRMATION lifecycle received, appId={}, confirmationUrl={}", appId,
                            confirmationUrl);
                    if (!confirmationUrl.isBlank()) {
                        try {
                            HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                                    .build();
                            HttpRequest confirmReq = HttpRequest.newBuilder().uri(URI.create(confirmationUrl)).GET()
                                    .timeout(Duration.ofSeconds(10)).build();
                            HttpResponse<String> confirmResp = httpClient.send(confirmReq,
                                    HttpResponse.BodyHandlers.ofString());
                            logger.info("SmartThings CONFIRMATION GET response: status={}", confirmResp.statusCode());
                        } catch (Exception ce) {
                            logger.warn("SmartThings CONFIRMATION GET failed: {}", ce.getMessage());
                        }
                    }
                    response.setContentType("application/json");
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.getWriter().write("{\"targetUrl\":\"" + SERVLET_PATH + "\"}");
                    return;
                }
                if ("EVENT".equals(lifecycle)) {
                    logger.info("SmartThings EVENT lifecycle received");
                    response.setContentType("application/json");
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.getWriter().write("{\"eventData\":{}}");
                    return;
                }
                if ("OAUTH_CALLBACK".equals(lifecycle)) {
                    logger.info("SmartThings OAUTH_CALLBACK lifecycle received");
                    response.setContentType("application/json");
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.getWriter().write("{\"oAuthCallbackData\":{}}");
                    return;
                }
                if ("CONFIGURATION".equals(lifecycle)) {
                    com.google.gson.JsonObject configData = json.has("configurationData")
                            ? json.getAsJsonObject("configurationData")
                            : null;
                    String phase = configData != null && configData.has("phase") ? configData.get("phase").getAsString()
                            : "";
                    logger.info("SmartThings CONFIGURATION lifecycle received, phase={}", phase);
                    response.setContentType("application/json");
                    response.setStatus(HttpServletResponse.SC_OK);
                    if ("INITIALIZE".equals(phase)) {
                        response.getWriter().write("{\"configurationData\":{\"initialize\":{"
                                + "\"name\":\"openHAB SmartThings Binding\","
                                + "\"description\":\"Connects SmartThings devices to openHAB\"," + "\"id\":\"app\","
                                + "\"permissions\":[\"r:devices:*\",\"w:devices:*\",\"x:devices:*\","
                                + "\"r:locations:*\",\"r:scenes:*\",\"x:scenes:*\","
                                + "\"r:rules:*\",\"w:rules:*\",\"r:hubs:*\","
                                + "\"r:customcapability\",\"i:deviceprofiles:*\"]," + "\"firstPageId\":\"1\"}}}");
                    } else if ("PAGE".equals(phase)) {
                        response.getWriter().write("{\"configurationData\":{\"page\":{" + "\"pageId\":\"1\","
                                + "\"name\":\"openHAB SmartThings Binding\"," + "\"nextPageId\":null,"
                                + "\"previousPageId\":null," + "\"complete\":true,"
                                + "\"sections\":[{\"name\":\"openHAB Integration\"," + "\"settings\":[{\"id\":\"info\","
                                + "\"name\":\"openHAB SmartThings Binding\","
                                + "\"description\":\"This SmartApp connects your SmartThings devices to openHAB.\","
                                + "\"type\":\"PARAGRAPH\","
                                + "\"defaultValue\":\"Devices will be available in openHAB automatically.\"}]}]}}}");
                    } else {
                        response.getWriter().write("{}");
                    }
                    return;
                }
                if ("INSTALL".equals(lifecycle) || "UPDATE".equals(lifecycle)) {
                    String dataKey = lifecycle.equals("INSTALL") ? "installData" : "updateData";
                    com.google.gson.JsonObject installData = json.has(dataKey) ? json.getAsJsonObject(dataKey) : null;
                    String authToken = installData != null && installData.has("authToken")
                            ? installData.get("authToken").getAsString()
                            : null;
                    String refreshToken = installData != null && installData.has("refreshToken")
                            ? installData.get("refreshToken").getAsString()
                            : null;
                    logger.info("SmartThings {} lifecycle received, hasAuthToken={}", lifecycle, authToken != null);
                    if (authToken != null && refreshToken != null) {
                        // Deliver tokens to any registered bridge handler
                        for (SmartThingsCloudAccountHandler h : bridgeHandlers) {
                            h.handleAuthorizationComplete(authToken, refreshToken);
                            logger.info("SmartThings {} tokens delivered to bridge {}", lifecycle,
                                    h.getThing().getUID());
                            break; // only first bridge
                        }
                    }
                    response.setContentType("application/json");
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.getWriter().write("{\"" + dataKey + "\":{}}");
                    return;
                }
                if ("UNINSTALL".equals(lifecycle)) {
                    logger.info("SmartThings UNINSTALL lifecycle received");
                    response.setContentType("application/json");
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.getWriter().write("{\"uninstallData\":{}}");
                    return;
                }
            } catch (Exception e) {
                logger.debug("Could not parse POST body as JSON lifecycle event: {}", e.getMessage());
            }
        }

        String action = request.getParameter("action");
        String bridgeId = request.getParameter("bridgeId");

        if ("authorize".equals(action) && bridgeId != null && !bridgeId.isBlank()) {
            startAuthorization(request, response, bridgeId);
        } else {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing action or bridgeId");
        }
    }

    // ── Pages ─────────────────────────────────────────────────────────────────

    private void showOverview(HttpServletResponse response) throws IOException {
        StringBuilder html = new StringBuilder(pageHeader("SmartThings Cloud Authorization"));
        html.append("<h1>SmartThings Cloud Authorization</h1>");

        // SSH tunnel instructions banner
        html.append("<div class='card' style='background:#fff8e1;border-left:4px solid #f59e0b;'>")
                .append("<h3 style='margin-top:0'>&#128679; Remote Access: SSH Tunnel Required</h3>")
                .append("<p>Samsung SmartThings only allows authorization via <code>localhost:").append(CALLBACK_PORT)
                .append("</code>. Before clicking Authorize, open an SSH tunnel:</p>")
                .append("<pre style='background:#1e293b;color:#e2e8f0;padding:12px;border-radius:6px;overflow-x:auto'>")
                .append("ssh -L ").append(CALLBACK_PORT).append(":localhost:").append(CALLBACK_PORT)
                .append(" admin@your-openhab-host</pre>")
                .append("<p>Keep this terminal open, then click Authorize. After Samsung login, your browser will open <code>localhost:")
                .append(CALLBACK_PORT).append("</code> — this is normal and completes the auth.</p></div>");

        if (bridgeHandlers.isEmpty()) {
            html.append("<div class='card'><p>No SmartThings Cloud bridges configured.</p></div>");
        } else {
            for (SmartThingsCloudAccountHandler h : bridgeHandlers) {
                String uid = h.getThing().getUID().getAsString();
                String label = h.getThing().getLabel();
                ThingStatus st = h.getThing().getStatus();

                html.append("<div class='card'>").append("<h2>").append(esc(label != null ? label : uid))
                        .append("</h2>").append("<table>").append("<tr><td><b>Bridge UID:</b></td><td>")
                        .append(esc(uid)).append("</td></tr>")
                        .append("<tr><td><b>Status:</b></td><td><span class='status-")
                        .append(ThingStatus.ONLINE.equals(st) ? "online" : "offline").append("'>")
                        .append(esc(st.toString())).append("</span></td></tr>").append("</table>")
                        .append("<form method='POST'>").append("<input type='hidden' name='action' value='authorize'/>")
                        .append("<input type='hidden' name='bridgeId' value='").append(esc(uid)).append("'/>")
                        .append("<button type='submit'>Authorize with SmartThings</button>").append("</form></div>");
            }
        }

        html.append(pageFooter());
        response.getWriter().write(html.toString());
    }

    // ── Authorization flow ────────────────────────────────────────────────────

    private void startAuthorization(HttpServletRequest request, HttpServletResponse response, String bridgeId)
            throws IOException {
        SmartThingsCloudAccountHandler bridge = getBridgeHandler(bridgeId);
        if (bridge == null) {
            sendError(response, "Unknown bridge: " + bridgeId);
            return;
        }

        String clientId = bridge.getClientId();

        // PKCE — no client_secret required
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);
        pendingCodeVerifiers.put(bridgeId, codeVerifier);

        String authUrl = ST_AUTH_URL + "?response_type=code" + "&client_id=" + URLEncoder.encode(clientId, UTF_8)
                + "&scope=" + URLEncoder.encode(OAUTH_SCOPES, UTF_8) + "&redirect_uri="
                + URLEncoder.encode(REDIRECT_URI, UTF_8) + "&state=" + URLEncoder.encode(bridgeId, UTF_8)
                + "&code_challenge=" + codeChallenge + "&code_challenge_method=S256" + "&client_type=USER_LEVEL";

        logger.info("Starting local callback server on port {} for bridge {}", CALLBACK_PORT, bridgeId);

        // Background thread: accept one connection on port 61973, exchange code for tokens
        Thread callbackThread = new Thread(() -> {
            try (ServerSocket server = new ServerSocket(CALLBACK_PORT)) {
                server.setSoTimeout(120_000); // 2-minute timeout
                try (Socket client = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), UTF_8));
                    PrintWriter out = new PrintWriter(client.getOutputStream(), true);

                    String requestLine = in.readLine();
                    if (requestLine == null || !requestLine.startsWith("GET")) {
                        writeHttpResponse(out, 400, "<h1>Bad Request</h1>");
                        return;
                    }

                    String path = requestLine.split(" ")[1];
                    Map<String, String> params = parseQuery(path.contains("?") ? path.split("\\?", 2)[1] : "");
                    String code = params.get("code");
                    String state = params.get("state");
                    String callbackError = params.get("error");

                    if (callbackError != null) {
                        logger.error("Samsung OAuth error on callback: {}", callbackError);
                        writeHttpResponse(out, 400,
                                "<h1>Authorization Error</h1><p>Samsung returned: " + esc(callbackError) + "</p>");
                        return;
                    }

                    if (code == null || state == null) {
                        writeHttpResponse(out, 400, "<h1>Missing code or state</h1>");
                        return;
                    }

                    logger.info("Callback received for bridge {}, exchanging code for tokens", state);

                    String pkceVerifier = pendingCodeVerifiers.remove(state);
                    Map<String, String> tokenParams = new LinkedHashMap<>();
                    tokenParams.put("grant_type", "authorization_code");
                    tokenParams.put("client_id", clientId);
                    if (pkceVerifier != null) {
                        tokenParams.put("code_verifier", pkceVerifier);
                    }
                    tokenParams.put("code", code);
                    tokenParams.put("redirect_uri", REDIRECT_URI);

                    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
                    HttpRequest tokenReq = HttpRequest.newBuilder().uri(URI.create(ST_TOKEN_URL))
                            .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(encodeForm(tokenParams))).build();

                    HttpResponse<String> tokenResp = httpClient.send(tokenReq,
                            HttpResponse.BodyHandlers.ofString(UTF_8));

                    if (tokenResp.statusCode() != 200) {
                        logger.error("Token exchange HTTP {}: {}", tokenResp.statusCode(), tokenResp.body());
                        writeHttpResponse(out, 500, "<h1>Token Exchange Failed</h1><p>HTTP " + tokenResp.statusCode()
                                + ": " + esc(tokenResp.body()) + "</p>");
                        return;
                    }

                    STTokenResponse token = gson.fromJson(tokenResp.body(), STTokenResponse.class);
                    String accessToken = token != null ? token.access_token : null;
                    String refreshToken = token != null ? token.refresh_token : null;

                    if (accessToken == null || refreshToken == null) {
                        writeHttpResponse(out, 500,
                                "<h1>Incomplete Token Response</h1><p>" + esc(tokenResp.body()) + "</p>");
                        return;
                    }

                    SmartThingsCloudAccountHandler targetBridge = getBridgeHandler(state);
                    if (targetBridge != null) {
                        targetBridge.handleAuthorizationComplete(accessToken, refreshToken);
                        logger.info("SmartThings authorization successful for bridge {}", state);
                        writeHttpResponse(out, 200,
                                "<h1 style='color:green'>&#10003; Authorization Successful</h1>"
                                        + "<p>SmartThings account authorized. You can close this tab.</p>"
                                        + "<p><a href='" + SERVLET_PATH + "'>Back to openHAB SmartThings</a></p>");
                    } else {
                        writeHttpResponse(out, 500, "<h1>Bridge Not Found</h1><p>state=" + esc(state) + "</p>");
                    }
                }
            } catch (Exception e) {
                logger.error("Callback server error: {}", e.getMessage(), e);
            }
        }, "smartthings-oauth-callback");
        callbackThread.setDaemon(true);
        callbackThread.start();

        logger.info("Redirecting to SmartThings authorization for bridge {}", bridgeId);
        response.sendRedirect(authUrl);
    }

    // ── Success / error pages ─────────────────────────────────────────────────

    private void sendSuccess(HttpServletResponse response, SmartThingsCloudAccountHandler bridge) throws IOException {
        String label = bridge.getThing().getLabel();
        StringBuilder html = new StringBuilder(pageHeader("Authorization Successful"));
        html.append("<div class='card success'>").append("<h1>&#10003; Authorization Successful</h1>")
                .append("<p>SmartThings account authorized for bridge <strong>")
                .append(esc(label != null ? label : "—")).append("</strong>.</p>")
                .append("<p>Tokens saved — bridge reinitializing now.</p>").append("<a href='").append(SERVLET_PATH)
                .append("' class='button'>Back to Overview</a>").append("</div>").append(pageFooter());
        response.getWriter().write(html.toString());
    }

    private void sendError(HttpServletResponse response, String message) throws IOException {
        StringBuilder html = new StringBuilder(pageHeader("Authorization Error"));
        html.append("<div class='card error'>").append("<h1>&#10007; Authorization Error</h1>").append("<p>")
                .append(esc(message)).append("</p>").append("<a href='").append(SERVLET_PATH)
                .append("' class='button'>Back to Overview</a>").append("</div>").append(pageFooter());
        response.getWriter().write(html.toString());
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static @Nullable String getJsonString(com.google.gson.JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull())
            return null;
        return obj.get(key).getAsString();
    }

    private @Nullable SmartThingsCloudAccountHandler getBridgeHandler(String bridgeUid) {
        for (SmartThingsCloudAccountHandler h : bridgeHandlers) {
            if (h.getThing().getUID().getAsString().equals(bridgeUid))
                return h;
        }
        return null;
    }

    private static void writeHttpResponse(PrintWriter out, int status, String body) {
        String statusText = status == 200 ? "OK" : status == 400 ? "Bad Request" : "Internal Server Error";
        String html = "<html><head><meta charset='UTF-8'><style>body{font-family:sans-serif;padding:40px}</style></head><body>"
                + body + "</body></html>";
        out.print("HTTP/1.1 " + status + " " + statusText + "\r\n");
        out.print("Content-Type: text/html; charset=UTF-8\r\n");
        out.print("Content-Length: " + html.getBytes(UTF_8).length + "\r\n");
        out.print("Connection: close\r\n");
        out.print("\r\n");
        out.print(html);
        out.flush();
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isBlank())
            return params;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            try {
                String key = URLDecoder.decode(kv[0], UTF_8);
                String val = kv.length > 1 ? URLDecoder.decode(kv[1], UTF_8) : "";
                params.put(key, val);
            } catch (Exception ignored) {
            }
        }
        return params;
    }

    private static String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String generateCodeChallenge(String codeVerifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String encodeForm(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), UTF_8) + "=" + URLEncoder.encode(e.getValue(), UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String pageHeader(String title) {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>" + "<title>" + title
                + "</title><style>" + "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;"
                + "max-width:700px;margin:40px auto;padding:0 20px;background:#f5f5f5;color:#333}" + "h1{color:#1a73e8}"
                + ".card{background:#fff;border-radius:8px;padding:24px;margin:20px 0;"
                + "box-shadow:0 1px 3px rgba(0,0,0,.12)}" + ".card h2{margin-top:0}"
                + "table{border-collapse:collapse;margin:12px 0}" + "td{padding:6px 16px 6px 0;vertical-align:top}"
                + ".status-online{color:#0d904f;font-weight:bold}" + ".status-offline{color:#d93025;font-weight:bold}"
                + "button,.button{display:inline-block;background:#1a73e8;color:#fff;border:none;"
                + "padding:10px 24px;border-radius:4px;font-size:14px;cursor:pointer;text-decoration:none}"
                + "button:hover,.button:hover{background:#1557b0}"
                + ".success{border-left:4px solid #0d904f}.success h1{color:#0d904f}"
                + ".error{border-left:4px solid #d93025}.error h1{color:#d93025}" + "</style></head><body>";
    }

    private static String pageFooter() {
        return "<p style='color:#999;font-size:12px;margin-top:40px'>openHAB SmartThings Cloud Binding</p>"
                + "</body></html>";
    }
}
