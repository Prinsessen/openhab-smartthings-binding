/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.smartthingscloud.internal.handler;

import static org.openhab.binding.smartthingscloud.SmartThingsCloudBindingConstants.*;

import java.time.ZonedDateTime;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Thing handler for a Samsung SmartThings-connected tumble dryer.
 *
 * <p>
 * Architecturally identical to {@link SmartThingsCloudWasherHandler}: it polls
 * the SmartThings REST API at a configurable interval and maps capability
 * attributes to openHAB channels, and shares the same JSON-parsing helpers.
 * Only the capability/attribute names differ (dryer* instead of washer*).
 *
 * <p>
 * Capability → channel mapping (Samsung samsungce namespace, confirmed against a
 * live DV5000T status dump):
 * <ul>
 * <li>{@code dryerOperatingState.machineState} → {@code machineState} (read: run/pause/stop)</li>
 * <li>{@code dryerOperatingState.setMachineState} → {@code machineState} (write — standard capability)</li>
 * <li>{@code dryerOperatingState.dryerJobState} → {@code jobState} (drying/cooling/finished/none)</li>
 * <li>{@code dryerOperatingState.completionTime} (ISO-8601) → {@code completionTime} (direct; falls back to
 * remainingTime-derived)</li>
 * <li>{@code samsungce.dryerOperatingState.operatingState} → {@code operatingState} (ready/running/paused)</li>
 * <li>{@code samsungce.dryerOperatingState.remainingTime} (minutes int) → {@code remaining}</li>
 * <li>{@code samsungce.dryerOperatingState.remainingTimeStr} ("HH:MM") → {@code remainingTimeStr}</li>
 * <li>{@code samsungce.dryerOperatingState.progress} → {@code progress}</li>
 * <li>{@code switch.switch} → {@code power}</li>
 * <li>{@code remoteControlStatus.remoteControlEnabled} → {@code remoteEnabled}</li>
 * <li>{@code powerConsumptionReport.powerConsumption.power} → {@code watt}</li>
 * <li>{@code powerConsumptionReport.powerConsumption.energy} (Wh) → {@code kwh}</li>
 * <li>{@code custom.supportedOptions.course} → {@code mode} (read); {@code samsungce.dryerCycle.setDryerCycle}
 * (write)</li>
 * <li>{@code samsungce.dryerCycle.dryerCycle} ("Table_03_Course_16" → "16") → {@code currentCycle}</li>
 * <li>{@code custom.dryerDryLevel.dryerDryLevel} → {@code dryLevel} (read/write)</li>
 * <li>{@code samsungce.dryerDryingTemperature.dryingTemperature} → {@code dryingTemperature} (read/write; may be
 * disabled on some models)</li>
 * <li>{@code samsungce.dryerDryingTime.dryingTime} (minutes) → {@code dryingTime} (read/write)</li>
 * <li>{@code custom.dryerWrinklePrevent.dryerWrinklePrevent} (on/off) → {@code wrinklePrevent} (read/write)</li>
 * <li>{@code samsungce.kidsLock.lockState} → {@code kidsLock}</li>
 * <li>{@code samsungce.dryerDelayEnd.remainingTime} → {@code delayEnd}</li>
 * <li>{@code custom.supportedOptions.supportedCourses} → {@code supportedCourses}</li>
 * <li>{@code samsungce.softwareUpdate.newVersionAvailable} → {@code updateAvailable}</li>
 * <li>Derived from jobState (not none/finish/stopped) → {@code running}</li>
 * </ul>
 *
 * @author openHAB Samsung Cloud Binding - Initial contribution
 */
@NonNullByDefault
public class SmartThingsCloudDryerHandler extends BaseThingHandler {

    // machineState commands — standard dryerOperatingState capability (samsungce namespace is read-only)
    private static final String CMD_RUN = "{\"commands\":[{\"component\":\"main\",\"capability\":\"dryerOperatingState\",\"command\":\"setMachineState\",\"arguments\":[\"run\"]}]}";
    private static final String CMD_PAUSE = "{\"commands\":[{\"component\":\"main\",\"capability\":\"dryerOperatingState\",\"command\":\"setMachineState\",\"arguments\":[\"pause\"]}]}";
    private static final String CMD_STOP = "{\"commands\":[{\"component\":\"main\",\"capability\":\"dryerOperatingState\",\"command\":\"setMachineState\",\"arguments\":[\"stop\"]}]}";
    private static final String CMD_ON = "{\"commands\":[{\"component\":\"main\",\"capability\":\"switch\",\"command\":\"on\"}]}";
    private static final String CMD_OFF = "{\"commands\":[{\"component\":\"main\",\"capability\":\"switch\",\"command\":\"off\"}]}";
    // Program / settings commands
    private static final String CMD_CYCLE_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"samsungce.dryerCycle\",\"command\":\"setDryerCycle\",\"arguments\":[\"%s\"]}]}";
    private static final String CMD_DRY_LEVEL_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"custom.dryerDryLevel\",\"command\":\"setDryerDryLevel\",\"arguments\":[\"%s\"]}]}";
    private static final String CMD_DRY_TEMP_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"samsungce.dryerDryingTemperature\",\"command\":\"setDryerDryingTemperature\",\"arguments\":[\"%s\"]}]}";
    private static final String CMD_DRY_TIME_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"samsungce.dryerDryingTime\",\"command\":\"setDryerDryingTime\",\"arguments\":[\"%s\"]}]}";
    private static final String CMD_WRINKLE_ON = "{\"commands\":[{\"component\":\"main\",\"capability\":\"custom.dryerWrinklePrevent\",\"command\":\"setDryerWrinklePrevent\",\"arguments\":[\"on\"]}]}";
    private static final String CMD_WRINKLE_OFF = "{\"commands\":[{\"component\":\"main\",\"capability\":\"custom.dryerWrinklePrevent\",\"command\":\"setDryerWrinklePrevent\",\"arguments\":[\"off\"]}]}";

    private final Logger logger = LoggerFactory.getLogger(SmartThingsCloudDryerHandler.class);
    private final Gson gson = new GsonBuilder().create();

    private @Nullable ScheduledFuture<?> pollFuture;

    public SmartThingsCloudDryerHandler(Thing thing) {
        super(thing);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void initialize() {
        SmartThingsCloudDryerConfiguration config = getConfigAs(SmartThingsCloudDryerConfiguration.class);
        if (config.deviceId.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Device ID is not configured");
            return;
        }

        updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Waiting for first poll");
        schedulePoll(config.pollingIntervalSeconds);
    }

    @Override
    public void dispose() {
        cancelPoll();
        super.dispose();
    }

    @Override
    public void bridgeStatusChanged(org.openhab.core.thing.ThingStatusInfo bridgeStatusInfo) {
        if (bridgeStatusInfo.getStatus() == ThingStatus.OFFLINE) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
            cancelPoll();
        } else if (bridgeStatusInfo.getStatus() == ThingStatus.ONLINE) {
            SmartThingsCloudDryerConfiguration config = getConfigAs(SmartThingsCloudDryerConfiguration.class);
            schedulePoll(config.pollingIntervalSeconds);
        }
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            poll();
            return;
        }

        SmartThingsCloudApiClient client = getApiClient();
        if (client == null) {
            logger.warn("Cannot send command — bridge not ready");
            return;
        }

        String deviceId = getConfigAs(SmartThingsCloudDryerConfiguration.class).deviceId;
        String channelId = channelUID.getIdWithoutGroup();

        if (CHANNEL_POWER.equals(channelId)) {
            client.sendCommand(deviceId, OnOffType.ON.equals(command) ? CMD_ON : CMD_OFF);

        } else if (CHANNEL_MACHINE_STATE.equals(channelId)) {
            String val = command.toString().toLowerCase();
            String body = "run".equals(val) ? CMD_RUN : "pause".equals(val) ? CMD_PAUSE : CMD_STOP;
            client.sendCommand(deviceId, body);

        } else if (CHANNEL_MODE.equals(channelId)) {
            // Write: samsungce.dryerCycle.setDryerCycle
            client.sendCommand(deviceId, String.format(CMD_CYCLE_FMT, command.toString()));

        } else if (CHANNEL_DRY_LEVEL.equals(channelId)) {
            client.sendCommand(deviceId, String.format(CMD_DRY_LEVEL_FMT, command.toString()));

        } else if (CHANNEL_DRYING_TEMPERATURE.equals(channelId)) {
            client.sendCommand(deviceId, String.format(CMD_DRY_TEMP_FMT, command.toString()));

        } else if (CHANNEL_DRYING_TIME.equals(channelId)) {
            client.sendCommand(deviceId, String.format(CMD_DRY_TIME_FMT, command.toString()));

        } else if (CHANNEL_WRINKLE_PREVENT.equals(channelId)) {
            client.sendCommand(deviceId, OnOffType.ON.equals(command) ? CMD_WRINKLE_ON : CMD_WRINKLE_OFF);

        } else {
            logger.debug("No command handler for channel {}", channelId);
        }
    }

    // ── Polling ────────────────────────────────────────────────────────────────

    private void schedulePoll(int intervalSeconds) {
        cancelPoll();
        pollFuture = scheduler.scheduleWithFixedDelay(this::poll, 0, intervalSeconds, TimeUnit.SECONDS);
    }

    private void cancelPoll() {
        ScheduledFuture<?> f = pollFuture;
        if (f != null) {
            f.cancel(true);
            pollFuture = null;
        }
    }

    private void poll() {
        SmartThingsCloudApiClient client = getApiClient();
        if (client == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, "Bridge not available");
            return;
        }

        String deviceId = getConfigAs(SmartThingsCloudDryerConfiguration.class).deviceId;
        String json = client.getDeviceComponentStatus(deviceId);

        if (json == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "No response from SmartThings API");
            return;
        }

        try {
            parseAndUpdate(json);
            updateStatus(ThingStatus.ONLINE);
        } catch (Exception e) {
            logger.warn("Failed to parse SmartThings status for device {}: {}", deviceId, e.getMessage(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Parse error: " + e.getMessage());
        }
    }

    // ── JSON parsing ──────────────────────────────────────────────────────────

    /**
     * Parses the /components/main/status response and updates channels.
     *
     * <p>
     * Top level is a map of capabilityId → { attributeName → {value, timestamp} }.
     * Capability names confirmed against a live Samsung dryer API response.
     */
    private void parseAndUpdate(String json) {
        JsonObject root = gson.fromJson(json, JsonObject.class);
        if (root == null) {
            logger.warn("Null root in SmartThings status response");
            return;
        }

        // ── dryerOperatingState (standard) — machineState, jobState, completionTime
        JsonObject dos = getCapability(root, "dryerOperatingState");
        if (dos != null) {
            String machineState = strVal(dos, "machineState");
            if (machineState != null) {
                updateState(CHANNEL_MACHINE_STATE, new StringType(machineState));
            }
            String jobState = strVal(dos, "dryerJobState");
            if (jobState != null) {
                updateState(CHANNEL_JOB_STATE, new StringType(jobState));
                boolean running = !isTerminalJobState(jobState);
                updateState(CHANNEL_RUNNING, OnOffType.from(running));
            }
            // completionTime is reported directly as an ISO-8601 timestamp
            String completion = strVal(dos, "completionTime");
            if (completion != null) {
                try {
                    updateState(CHANNEL_COMPLETION_TIME, new DateTimeType(ZonedDateTime.parse(completion)));
                } catch (Exception e) {
                    logger.debug("Could not parse completionTime: {}", completion);
                }
            }
        }

        // ── samsungce.dryerOperatingState — operatingState, remaining, progress ─
        JsonObject sdos = getCapability(root, "samsungce.dryerOperatingState");
        if (sdos != null) {
            String opState = strVal(sdos, "operatingState");
            if (opState != null) {
                updateState(CHANNEL_OPERATING_STATE, new StringType(opState));
            }
            // remainingTime is reported in minutes as a plain integer
            JsonElement remainElem = attrValue(sdos, "remainingTime");
            if (remainElem != null && !remainElem.isJsonNull()) {
                try {
                    long remainMin = remainElem.getAsLong();
                    updateState(CHANNEL_REMAINING, new DecimalType(Math.max(0, remainMin)));
                    // Fallback completionTime derivation if the standard capability did not provide one
                    if (remainMin > 0 && dos != null && strVal(dos, "completionTime") == null) {
                        updateState(CHANNEL_COMPLETION_TIME, new DateTimeType(ZonedDateTime.now().plusMinutes(remainMin)));
                    }
                } catch (Exception e) {
                    logger.debug("Could not parse remainingTime: {}", remainElem);
                }
            }
            // remainingTimeStr — formatted as "HH:MM"
            String remainStr = strVal(sdos, "remainingTimeStr");
            if (remainStr != null) {
                updateState(CHANNEL_REMAINING_TIME_STR, new StringType(remainStr));
            }
            JsonElement progressEl = attrValue(sdos, "progress");
            if (progressEl != null && !progressEl.isJsonNull()) {
                try {
                    updateState(CHANNEL_PROGRESS, new DecimalType(progressEl.getAsInt()));
                } catch (NumberFormatException e) {
                    logger.debug("Unexpected progress value: {}", progressEl);
                }
            }
            // Fallback jobState if the standard capability was absent
            if (dos == null) {
                String jobState = strVal(sdos, "dryerJobState");
                if (jobState != null) {
                    updateState(CHANNEL_JOB_STATE, new StringType(jobState));
                    updateState(CHANNEL_RUNNING, OnOffType.from(!isTerminalJobState(jobState)));
                }
            }
        }

        // ── switch ────────────────────────────────────────────────────────────
        JsonObject sw = getCapability(root, "switch");
        if (sw != null) {
            String val = strVal(sw, "switch");
            if (val != null) {
                updateState(CHANNEL_POWER, OnOffType.from("on".equalsIgnoreCase(val)));
            }
        }

        // ── remoteControlStatus ───────────────────────────────────────────────
        JsonObject rcs = getCapability(root, "remoteControlStatus");
        if (rcs != null) {
            String val = strVal(rcs, "remoteControlEnabled");
            if (val != null) {
                updateState(CHANNEL_REMOTE_ENABLED, OnOffType.from("true".equalsIgnoreCase(val)));
            }
        }

        // ── power — try powerMeter first, fall back to powerConsumptionReport ─
        JsonObject powerMeter = getCapability(root, "powerMeter");
        String watt = powerMeter != null ? strVal(powerMeter, "power") : null;
        if (watt != null) {
            updateState(CHANNEL_WATT, new DecimalType(Double.parseDouble(watt)));
        } else {
            JsonObject pcr = getCapability(root, "powerConsumptionReport");
            if (pcr != null) {
                JsonElement pcElem = attrValue(pcr, "powerConsumption");
                if (pcElem != null && pcElem.isJsonObject()) {
                    JsonObject pc = pcElem.getAsJsonObject();
                    if (pc.has("power")) {
                        updateState(CHANNEL_WATT, new DecimalType(pc.get("power").getAsDouble()));
                    }
                }
            }
        }

        // ── energy — try energyMeter first, fall back to powerConsumptionReport
        JsonObject energyMeter = getCapability(root, "energyMeter");
        String kwhStr = energyMeter != null ? strVal(energyMeter, "energy") : null;
        if (kwhStr != null) {
            updateState(CHANNEL_KWH, new DecimalType(Double.parseDouble(kwhStr)));
        } else {
            JsonObject pcr2 = getCapability(root, "powerConsumptionReport");
            if (pcr2 != null) {
                JsonElement pcElem = attrValue(pcr2, "powerConsumption");
                if (pcElem != null && pcElem.isJsonObject()) {
                    JsonObject pc = pcElem.getAsJsonObject();
                    if (pc.has("energy")) {
                        // powerConsumptionReport reports energy in Wh — convert to kWh
                        updateState(CHANNEL_KWH, new DecimalType(pc.get("energy").getAsDouble() / 1000.0));
                    }
                }
            }
        }

        // ── dry program — custom.supportedOptions.course ──────────────────────
        JsonObject opts = getCapability(root, "custom.supportedOptions");
        if (opts != null) {
            String val = strVal(opts, "course");
            if (val != null)
                updateState(CHANNEL_MODE, new StringType(val));
            // supported courses list
            JsonElement coursesEl = attrValue(opts, "supportedCourses");
            if (coursesEl != null && coursesEl.isJsonArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonElement c : coursesEl.getAsJsonArray()) {
                    if (sb.length() > 0)
                        sb.append(", ");
                    sb.append(c.getAsString());
                }
                if (sb.length() > 0)
                    updateState(CHANNEL_SUPPORTED_COURSES, new StringType(sb.toString()));
            }
        }

        // ── current cycle (read) — samsungce.dryerCycle ───────────────────────
        JsonObject dryerCycleCap = getCapability(root, "samsungce.dryerCycle");
        if (dryerCycleCap != null) {
            String cycleVal = strVal(dryerCycleCap, "dryerCycle");
            if (cycleVal != null) {
                // "Table_03_Course_16" → "16"
                int idx = cycleVal.lastIndexOf('_');
                String cycleCode = idx >= 0 ? cycleVal.substring(idx + 1) : cycleVal;
                updateState(CHANNEL_CURRENT_CYCLE, new StringType(cycleCode));
            }
        }

        // ── dry level — custom.dryerDryLevel ──────────────────────────────────
        JsonObject dryLevel = getCapability(root, "custom.dryerDryLevel");
        if (dryLevel != null) {
            String val = strVal(dryLevel, "dryerDryLevel");
            if (val != null)
                updateState(CHANNEL_DRY_LEVEL, new StringType(val));
        }

        // ── drying temperature — samsungce.dryerDryingTemperature (may be null) ─
        JsonObject dryTemp = getCapability(root, "samsungce.dryerDryingTemperature");
        if (dryTemp != null) {
            String val = strVal(dryTemp, "dryingTemperature");
            if (val != null)
                updateState(CHANNEL_DRYING_TEMPERATURE, new StringType(val));
        }

        // ── drying time — samsungce.dryerDryingTime (minutes) ─────────────────
        JsonObject dryTime = getCapability(root, "samsungce.dryerDryingTime");
        if (dryTime != null) {
            String val = strVal(dryTime, "dryingTime");
            if (val != null)
                updateState(CHANNEL_DRYING_TIME, new StringType(val));
        }

        // ── wrinkle prevent — custom.dryerWrinklePrevent ──────────────────────
        JsonObject wrinkle = getCapability(root, "custom.dryerWrinklePrevent");
        if (wrinkle != null) {
            String val = strVal(wrinkle, "dryerWrinklePrevent");
            if (val != null)
                updateState(CHANNEL_WRINKLE_PREVENT, OnOffType.from("on".equalsIgnoreCase(val)));
        }

        // ── kids lock — samsungce.kidsLock ────────────────────────────────────
        JsonObject kidsLockCap = getCapability(root, "samsungce.kidsLock");
        if (kidsLockCap != null) {
            String lockVal = strVal(kidsLockCap, "lockState");
            if (lockVal != null)
                updateState(CHANNEL_KIDS_LOCK, OnOffType.from("locked".equalsIgnoreCase(lockVal)));
        }

        // ── delay end remaining minutes — samsungce.dryerDelayEnd ─────────────
        JsonObject delayEnd = getCapability(root, "samsungce.dryerDelayEnd");
        if (delayEnd != null) {
            JsonElement el = attrValue(delayEnd, "remainingTime");
            if (el != null && !el.isJsonNull()) {
                updateState(CHANNEL_DELAY_END, new DecimalType(el.getAsInt()));
            }
        }

        // ── software update available — samsungce.softwareUpdate ──────────────
        JsonObject swUpdate = getCapability(root, "samsungce.softwareUpdate");
        if (swUpdate != null) {
            JsonElement el = attrValue(swUpdate, "newVersionAvailable");
            if (el != null && !el.isJsonNull()) {
                updateState(CHANNEL_UPDATE_AVAILABLE, OnOffType.from(el.getAsBoolean()));
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private @Nullable JsonObject getCapability(JsonObject root, String capabilityId) {
        JsonElement e = root.get(capabilityId);
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
    }

    /** Returns the "value" field of an attribute object, as a String. */
    private @Nullable String strVal(JsonObject capability, String attribute) {
        JsonElement val = attrValue(capability, attribute);
        return (val != null && !val.isJsonNull()) ? val.getAsString() : null;
    }

    /** Returns the raw "value" JsonElement of an attribute object. */
    private @Nullable JsonElement attrValue(JsonObject capability, String attribute) {
        JsonElement attr = capability.get(attribute);
        if (attr == null || !attr.isJsonObject())
            return null;
        return attr.getAsJsonObject().get("value");
    }

    private static boolean isTerminalJobState(String jobState) {
        return "none".equalsIgnoreCase(jobState) || "finish".equalsIgnoreCase(jobState)
                || "stopped".equalsIgnoreCase(jobState) || "finished".equalsIgnoreCase(jobState);
    }

    private @Nullable SmartThingsCloudApiClient getApiClient() {
        Bridge bridge = getBridge();
        if (bridge == null)
            return null;
        ThingHandler handler = bridge.getHandler();
        if (handler instanceof SmartThingsCloudAccountHandler accountHandler) {
            return accountHandler.getApiClient();
        }
        return null;
    }
}
