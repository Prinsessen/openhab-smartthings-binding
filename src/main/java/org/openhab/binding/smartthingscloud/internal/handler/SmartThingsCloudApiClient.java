/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.smartthingscloud.internal.handler;

import static org.openhab.binding.smartthingscloud.SmartThingsCloudBindingConstants.*;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.smartthingscloud.internal.dto.STTokenResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * HTTP client for the SmartThings Cloud REST API.
 *
 * <p>
 * Handles:
 * <ul>
 * <li>Bearer token injection on every request</li>
 * <li>Automatic token refresh via refresh_token grant (PKCE — no client_secret)</li>
 * <li>Callback to bridge handler to persist refreshed tokens</li>
 * </ul>
 *
 * @author openHAB Samsung Cloud Binding - Initial contribution
 */
@NonNullByDefault
public class SmartThingsCloudApiClient {

    private final Logger logger = LoggerFactory.getLogger(SmartThingsCloudApiClient.class);
    private final Gson gson = new GsonBuilder().create();
    private final HttpClient httpClient;
    private final ReentrantLock tokenLock = new ReentrantLock();

    private final String clientId;
    private String clientSecret = "";
    private String accessToken;
    private String refreshToken;
    private Instant tokenExpiresAt = Instant.EPOCH; // treat as expired until first use

    private @Nullable TokenRefreshCallback tokenRefreshCallback;

    // ── Callback ──────────────────────────────────────────────────────────────

    /** Called when tokens are automatically refreshed so the bridge can persist them. */
    public interface TokenRefreshCallback {
        /**
         * @param newAccessToken the new access token
         * @param newRefreshToken the new refresh token
         * @param expiresAt when the new access token expires (for persistence)
         */
        void onTokenRefreshed(String newAccessToken, String newRefreshToken, Instant expiresAt);
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    public SmartThingsCloudApiClient(String clientId, String accessToken, String refreshToken) {
        this(clientId, accessToken, refreshToken, Instant.EPOCH);
    }

    /**
     * Constructor that restores a previously known expiry time so we don't refresh
     * an already-valid token on every startup.
     *
     * @param tokenExpiresAt the epoch-second timestamp when the access token expires
     */
    public SmartThingsCloudApiClient(String clientId, String accessToken, String refreshToken, Instant tokenExpiresAt) {
        this.clientId = clientId;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.tokenExpiresAt = tokenExpiresAt;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    public void setTokenRefreshCallback(@Nullable TokenRefreshCallback callback) {
        this.tokenRefreshCallback = callback;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fetches the full component/main status for a device.
     *
     * @param deviceId SmartThings device UUID
     * @return raw JSON body as String, or null on error
     */
    public @Nullable String getDeviceComponentStatus(String deviceId) {
        if (!ensureValidToken()) {
            logger.warn("Cannot fetch device status — token refresh failed");
            return null;
        }
        String url = ST_API_BASE + "/devices/" + deviceId + "/components/main/status";
        try {
            return getJson(url);
        } catch (Exception e) {
            logger.warn("Failed to fetch status for device {}: {}", deviceId, e.getMessage());
            return null;
        }
    }

    /**
     * Fetches the status of every component of a device at once.
     *
     * <p>
     * {@code GET /devices/{id}/status} answers {@code {"components": {"main": {...}, "cooler": {...}, ...}}}.
     * A multi-component appliance (the first was the refrigerator, issue #3) keeps its useful values outside
     * {@code main}, which {@link #getDeviceComponentStatus(String)} never sees.
     *
     * @param deviceId SmartThings device UUID
     * @return raw JSON body as String, or null on error
     */
    public @Nullable String getDeviceStatus(String deviceId) {
        if (!ensureValidToken()) {
            logger.warn("Cannot fetch device status — token refresh failed");
            return null;
        }
        String url = ST_API_BASE + "/devices/" + deviceId + "/status";
        try {
            return getJson(url);
        } catch (Exception e) {
            logger.warn("Failed to fetch full status for device {}: {}", deviceId, e.getMessage());
            return null;
        }
    }

    /**
     * Sends a command to a device capability attribute.
     *
     * <p>
     * Example body:
     * 
     * <pre>
     * {"commands":[{"component":"main","capability":"switch","command":"on"}]}
     * </pre>
     *
     * @param deviceId SmartThings device UUID
     * @param commandBody JSON command body
     * @return true on HTTP 200/204
     */
    public boolean sendCommand(String deviceId, String commandBody) {
        if (!ensureValidToken()) {
            logger.warn("Cannot send command — token refresh failed");
            return false;
        }
        String url = ST_API_BASE + "/devices/" + deviceId + "/commands";
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + accessToken).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(commandBody)).build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return true;
            }
            logger.warn("Command to {} returned HTTP {}: {}", deviceId, resp.statusCode(), resp.body());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            logger.warn("IO error sending command to {}: {}", deviceId, e.getMessage());
            return false;
        }
    }

    /**
     * Fetches all devices in the account (for discovery).
     *
     * @return raw JSON body as String, or null on error
     */
    public @Nullable String listDevices() {
        if (!ensureValidToken()) {
            return null;
        }
        try {
            return getJson(ST_API_BASE + "/devices");
        } catch (Exception e) {
            logger.warn("Failed to list devices: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Executes a SmartThings scene.
     *
     * @param sceneId the scene UUID
     * @param locationId the location UUID (may be empty — omitted from URL if blank)
     * @return {@code true} on HTTP 2xx
     */
    public boolean executeScene(String sceneId, String locationId) {
        if (!ensureValidToken()) {
            logger.warn("Cannot execute scene — token refresh failed");
            return false;
        }
        String url = ST_API_BASE + "/scenes/" + sceneId + "/execute";
        if (!locationId.isBlank()) {
            url = url + "?locationId=" + locationId;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + accessToken).POST(HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return true;
            }
            logger.warn("Execute scene {} returned HTTP {}: {}", sceneId, resp.statusCode(), resp.body());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            logger.warn("IO error executing scene {}: {}", sceneId, e.getMessage());
            return false;
        }
    }

    public void dispose() {
        // HttpClient has no explicit close in Java 11 — nothing to do
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private boolean ensureValidToken() {
        tokenLock.lock();
        try {
            // Refresh if less than 5 minutes remain
            if (Instant.now().isBefore(tokenExpiresAt.minusSeconds(300))) {
                return true;
            }
            return refreshAccessToken();
        } finally {
            tokenLock.unlock();
        }
    }

    /**
     * Refreshes the access token using the refresh token (PKCE — no client_secret required).
     */
    private boolean refreshAccessToken() {
        logger.debug("Refreshing Samsung SmartThings access token");

        // Standard OAuth2 refresh — include client_secret if configured
        Map<String, String> params;
        if (!clientSecret.isBlank()) {
            params = Map.of("grant_type", "refresh_token", "client_id", clientId, "client_secret", clientSecret,
                    "refresh_token", refreshToken);
        } else {
            params = Map.of("grant_type", "refresh_token", "client_id", clientId, "refresh_token", refreshToken);
        }

        String formBody = encodeForm(params);

        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(ST_TOKEN_URL)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody)).build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                logger.error("Token refresh returned HTTP {}: {}", resp.statusCode(), resp.body());
                return false;
            }

            STTokenResponse token = gson.fromJson(resp.body(), STTokenResponse.class);
            String newAccess = token != null ? token.access_token : null;
            String newRefresh = token != null ? token.refresh_token : null;
            if (newAccess == null || newRefresh == null) {
                logger.error("Token refresh response missing tokens: {}", resp.body());
                return false;
            }

            this.accessToken = newAccess;
            this.refreshToken = newRefresh;
            long expiresIn = token.expires_in > 0 ? token.expires_in : 86399;
            this.tokenExpiresAt = Instant.now().plusSeconds(expiresIn);

            logger.info("SmartThings token refreshed — expires in {}s", expiresIn);

            TokenRefreshCallback cb = this.tokenRefreshCallback;
            if (cb != null) {
                cb.onTokenRefreshed(newAccess, newRefresh, this.tokenExpiresAt);
            }
            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            logger.error("Failed to refresh Samsung token: {}", e.getMessage(), e);
            return false;
        }
    }

    private String getJson(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + accessToken).header("Accept", "application/json").GET().build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 401) {
            logger.warn("Got 401 from SmartThings — forcing token refresh");
            tokenExpiresAt = Instant.EPOCH;
            if (refreshAccessToken()) {
                // Retry once with new token
                HttpRequest retry = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(20))
                        .header("Authorization", "Bearer " + accessToken).header("Accept", "application/json").GET()
                        .build();
                HttpResponse<String> retryResp = httpClient.send(retry, HttpResponse.BodyHandlers.ofString());
                if (retryResp.statusCode() == 200) {
                    return retryResp.body();
                }
                throw new IOException("HTTP " + retryResp.statusCode() + " after token refresh");
            }
            throw new IOException("401 and token refresh failed");
        }
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    private static String encodeForm(Map<String, String> params) {
        return params.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }
}
