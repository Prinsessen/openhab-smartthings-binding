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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Thing handler for a Samsung SmartThings-connected dishwasher.
 *
 * <p>
 * Same shape as {@link SmartThingsCloudDryerHandler}: everything lives in the
 * {@code main} component, polled at a configurable interval, capability
 * attributes mapped to channels. Written against a verbatim status dump of a
 * DW8700B (issue #7); the machine was idle, so run-time values are mapped by
 * name and still need a live cycle to confirm.
 *
 * <p>
 * Capability → channel mapping:
 * <ul>
 * <li>{@code dishwasherOperatingState.machineState} → {@code machineState} (run/pause/stop; write via
 * {@code setMachineState})</li>
 * <li>{@code dishwasherOperatingState.dishwasherJobState} → {@code jobState}; falls back to
 * {@code samsungce.dishwasherJobState.dishwasherJobState} when the standard one is "unknown"</li>
 * <li>{@code dishwasherOperatingState.completionTime} → {@code completionTime}</li>
 * <li>{@code samsungce.dishwasherOperation.operatingState} (ready/running/paused) → {@code operatingState}</li>
 * <li>{@code samsungce.dishwasherOperation.remainingTime} (min) → {@code remaining}; {@code remainingTimeStr} →
 * {@code remainingTimeStr}; {@code progressPercentage} → {@code progress}; {@code timeLeftToStart} →
 * {@code timeLeftToStart}</li>
 * <li>{@code samsungce.dishwasherJobState.scheduledJobs} → {@code scheduledJobs} ("washing 121m, rinsing 28m,
 * drying 165m")</li>
 * <li>{@code custom.dishwasherOperatingProgress.dishwasherOperatingProgress} → {@code operatingProgress}</li>
 * <li>{@code samsungce.dishwasherWashingCourse.washingCourse} → {@code washingCourse} (read/write via
 * {@code setWashingCourse}); {@code supportedCourses} → {@code supportedCourses}</li>
 * <li>{@code samsungce.dishwasherWashingOptions.*} → {@code selectedZone}, {@code speedBooster}, {@code dryPlus},
 * {@code stormWash}, {@code hotAirDry}, {@code highTempWash}, {@code sanitizingWash} (read/write; writes use the
 * {@code set<Option>} commands and are unverified until a tester confirms)</li>
 * <li>{@code custom.dishwasherDelayStartTime.dishwasherDelayStartTime} ("HH:MM:SS") → {@code delayStartTime}</li>
 * <li>{@code switch.switch} → {@code power}; {@code remoteControlStatus.remoteControlEnabled} →
 * {@code remoteEnabled}</li>
 * <li>{@code powerConsumptionReport.powerConsumption.power/energy} → {@code watt} / {@code kwh}</li>
 * <li>{@code samsungce.waterConsumptionReport.waterConsumption.cumulativeAmount} → {@code waterLiters}</li>
 * <li>{@code samsungce.kidsLock.lockState} → {@code kidsLock}; {@code samsungce.audioVolumeLevel.volumeLevel} →
 * {@code volume}; {@code samsungce.softwareUpdate.newVersionAvailable} → {@code updateAvailable}</li>
 * <li>Derived: {@code running} = operatingState is "running" (or jobState not terminal)</li>
 * </ul>
 *
 * @author Nanna Agesen - Initial contribution
 */
@NonNullByDefault
public class SmartThingsCloudDishwasherHandler extends BaseThingHandler {

    private static final String CMD_MACHINE_STATE_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"dishwasherOperatingState\",\"command\":\"setMachineState\",\"arguments\":[\"%s\"]}]}";
    private static final String CMD_ON = "{\"commands\":[{\"component\":\"main\",\"capability\":\"switch\",\"command\":\"on\"}]}";
    private static final String CMD_OFF = "{\"commands\":[{\"component\":\"main\",\"capability\":\"switch\",\"command\":\"off\"}]}";
    private static final String CMD_COURSE_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"samsungce.dishwasherWashingCourse\",\"command\":\"setWashingCourse\",\"arguments\":[\"%s\"]}]}";
    private static final String CMD_ZONE_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"samsungce.dishwasherWashingOptions\",\"command\":\"setSelectedZone\",\"arguments\":[\"%s\"]}]}";
    /** Boolean options share one command shape: set<Option>(true|false). */
    private static final String CMD_BOOL_OPTION_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"samsungce.dishwasherWashingOptions\",\"command\":\"%s\",\"arguments\":[%s]}]}";

    private final Logger logger = LoggerFactory.getLogger(SmartThingsCloudDishwasherHandler.class);
    private final Gson gson = new GsonBuilder().create();

    private @Nullable ScheduledFuture<?> pollFuture;

    public SmartThingsCloudDishwasherHandler(Thing thing) {
        super(thing);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void initialize() {
        SmartThingsCloudDishwasherConfiguration config = getConfigAs(SmartThingsCloudDishwasherConfiguration.class);
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
            SmartThingsCloudDishwasherConfiguration config = getConfigAs(
                    SmartThingsCloudDishwasherConfiguration.class);
            schedulePoll(config.pollingIntervalSeconds);
        }
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            scheduler.execute(this::poll);
            return;
        }
        SmartThingsCloudApiClient client = getApiClient();
        if (client == null) {
            logger.debug("No API client (bridge offline?) — dropping command {} for {}", command, channelUID);
            return;
        }
        String deviceId = getConfigAs(SmartThingsCloudDishwasherConfiguration.class).deviceId;
        String channelId = channelUID.getId();

        if (CHANNEL_POWER.equals(channelId)) {
            client.sendCommand(deviceId, OnOffType.ON.equals(command) ? CMD_ON : CMD_OFF);
        } else if (CHANNEL_MACHINE_STATE.equals(channelId)) {
            String state = command.toString().toLowerCase();
            if ("run".equals(state) || "pause".equals(state) || "stop".equals(state)) {
                client.sendCommand(deviceId, String.format(CMD_MACHINE_STATE_FMT, state));
            } else {
                logger.debug("Ignoring machineState command {} (expected run/pause/stop)", command);
            }
        } else if (CHANNEL_WASHING_COURSE.equals(channelId)) {
            client.sendCommand(deviceId, String.format(CMD_COURSE_FMT, command.toString()));
        } else if (CHANNEL_SELECTED_ZONE.equals(channelId)) {
            client.sendCommand(deviceId, String.format(CMD_ZONE_FMT, command.toString()));
        } else if (CHANNEL_SPEED_BOOSTER.equals(channelId)) {
            client.sendCommand(deviceId, boolOption("setSpeedBooster", command));
        } else if (CHANNEL_DRY_PLUS.equals(channelId)) {
            client.sendCommand(deviceId, boolOption("setDryPlus", command));
        } else if (CHANNEL_STORM_WASH.equals(channelId)) {
            client.sendCommand(deviceId, boolOption("setStormWash", command));
        } else if (CHANNEL_HOT_AIR_DRY.equals(channelId)) {
            client.sendCommand(deviceId, boolOption("setHotAirDry", command));
        } else if (CHANNEL_HIGH_TEMP_WASH.equals(channelId)) {
            client.sendCommand(deviceId, boolOption("setHighTempWash", command));
        } else if (CHANNEL_SANITIZING_WASH.equals(channelId)) {
            client.sendCommand(deviceId, boolOption("setSanitizingWash", command));
        } else {
            logger.debug("Channel {} is read-only or unknown — ignoring command {}", channelId, command);
            return;
        }
        // Reflect the new state promptly rather than waiting a full poll interval
        scheduler.schedule(this::poll, 3, TimeUnit.SECONDS);
    }

    private static String boolOption(String commandName, Command command) {
        return String.format(CMD_BOOL_OPTION_FMT, commandName, OnOffType.ON.equals(command) ? "true" : "false");
    }

    // ── Polling ───────────────────────────────────────────────────────────────

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
        String deviceId = getConfigAs(SmartThingsCloudDishwasherConfiguration.class).deviceId;
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

    private void parseAndUpdate(String json) {
        JsonObject root = gson.fromJson(json, JsonObject.class);
        if (root == null) {
            logger.warn("Null root in SmartThings status response");
            return;
        }

        // ── dishwasherOperatingState (standard) ─────────────────────────────
        String jobState = null;
        JsonObject dos = getCapability(root, "dishwasherOperatingState");
        if (dos != null) {
            String machineState = strVal(dos, "machineState");
            if (machineState != null) {
                updateState(CHANNEL_MACHINE_STATE, new StringType(machineState));
            }
            String js = strVal(dos, "dishwasherJobState");
            if (js != null && !"unknown".equalsIgnoreCase(js)) {
                jobState = js;
            }
            String completion = strVal(dos, "completionTime");
            if (completion != null) {
                try {
                    updateState(CHANNEL_COMPLETION_TIME, new DateTimeType(ZonedDateTime.parse(completion)));
                } catch (Exception e) {
                    logger.debug("Could not parse completionTime: {}", completion);
                }
            }
        }

        // ── samsungce.dishwasherJobState — the Samsung job state and the plan ──
        JsonObject sjs = getCapability(root, "samsungce.dishwasherJobState");
        if (sjs != null) {
            if (jobState == null) {
                jobState = strVal(sjs, "dishwasherJobState");
            }
            JsonElement jobs = attrValue(sjs, "scheduledJobs");
            if (jobs != null && jobs.isJsonArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonElement j : jobs.getAsJsonArray()) {
                    if (!j.isJsonObject()) {
                        continue;
                    }
                    JsonObject jo = j.getAsJsonObject();
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(jo.has("jobName") ? jo.get("jobName").getAsString() : "?");
                    if (jo.has("timeInSec")) {
                        sb.append(' ').append(Math.round(jo.get("timeInSec").getAsDouble() / 60.0)).append('m');
                    }
                }
                updateState(CHANNEL_SCHEDULED_JOBS, new StringType(sb.toString()));
            }
        }
        if (jobState != null) {
            updateState(CHANNEL_JOB_STATE, new StringType(jobState));
        }

        // ── samsungce.dishwasherOperation — the live figures ────────────────
        boolean running = jobState != null && !isTerminalJobState(jobState);
        JsonObject op = getCapability(root, "samsungce.dishwasherOperation");
        if (op != null) {
            String opState = strVal(op, "operatingState");
            if (opState != null) {
                updateState(CHANNEL_OPERATING_STATE, new StringType(opState));
                running = "running".equalsIgnoreCase(opState) || running;
            }
            JsonElement remain = attrValue(op, "remainingTime");
            if (remain != null && remain.isJsonPrimitive() && remain.getAsJsonPrimitive().isNumber()) {
                int remainMin = (int) Math.round(remain.getAsDouble());
                updateState(CHANNEL_REMAINING, new DecimalType(Math.max(0, remainMin)));
                if (dos == null || strVal(dos, "completionTime") == null) {
                    if (remainMin > 0) {
                        updateState(CHANNEL_COMPLETION_TIME, new DateTimeType(ZonedDateTime.now().plusMinutes(remainMin)));
                    }
                }
            }
            String remainStr = strVal(op, "remainingTimeStr");
            if (remainStr != null) {
                updateState(CHANNEL_REMAINING_TIME_STR, new StringType(remainStr));
            }
            JsonElement pct = attrValue(op, "progressPercentage");
            if (pct != null && pct.isJsonPrimitive() && pct.getAsJsonPrimitive().isNumber()) {
                updateState(CHANNEL_PROGRESS, new DecimalType(pct.getAsInt()));
            }
            JsonElement tlts = attrValue(op, "timeLeftToStart");
            if (tlts != null && tlts.isJsonPrimitive() && tlts.getAsJsonPrimitive().isNumber()) {
                updateState(CHANNEL_TIME_LEFT_TO_START, new DecimalType((int) Math.round(tlts.getAsDouble())));
            }
        }
        updateState(CHANNEL_RUNNING, OnOffType.from(running));

        // ── custom.dishwasherOperatingProgress ──────────────────────────────
        JsonObject prog = getCapability(root, "custom.dishwasherOperatingProgress");
        if (prog != null) {
            String p = strVal(prog, "dishwasherOperatingProgress");
            if (p != null) {
                updateState(CHANNEL_OPERATING_PROGRESS, new StringType(p));
            }
        }

        // ── switch / remote control ─────────────────────────────────────────
        JsonObject sw = getCapability(root, "switch");
        if (sw != null) {
            String val = strVal(sw, "switch");
            if (val != null) {
                updateState(CHANNEL_POWER, OnOffType.from("on".equalsIgnoreCase(val)));
            }
        }
        JsonObject rcs = getCapability(root, "remoteControlStatus");
        if (rcs != null) {
            String val = strVal(rcs, "remoteControlEnabled");
            if (val != null) {
                updateState(CHANNEL_REMOTE_ENABLED, OnOffType.from("true".equalsIgnoreCase(val)));
            }
        }

        // ── energy and water ────────────────────────────────────────────────
        JsonObject pcr = getCapability(root, "powerConsumptionReport");
        if (pcr != null) {
            JsonElement pcElem = attrValue(pcr, "powerConsumption");
            if (pcElem != null && pcElem.isJsonObject()) {
                JsonObject pc = pcElem.getAsJsonObject();
                if (pc.has("power") && !pc.get("power").isJsonNull()) {
                    updateState(CHANNEL_WATT, new DecimalType(pc.get("power").getAsDouble()));
                }
                if (pc.has("energy") && !pc.get("energy").isJsonNull()) {
                    updateState(CHANNEL_KWH, new DecimalType(pc.get("energy").getAsDouble() / 1000.0));
                }
            }
        }
        JsonObject wcr = getCapability(root, "samsungce.waterConsumptionReport");
        if (wcr != null) {
            JsonElement wcElem = attrValue(wcr, "waterConsumption");
            if (wcElem != null && wcElem.isJsonObject()) {
                JsonObject wc = wcElem.getAsJsonObject();
                if (wc.has("cumulativeAmount") && !wc.get("cumulativeAmount").isJsonNull()) {
                    updateState(CHANNEL_WATER_LITERS, new DecimalType(wc.get("cumulativeAmount").getAsDouble()));
                }
            }
        }

        // ── course and options ──────────────────────────────────────────────
        JsonObject course = getCapability(root, "samsungce.dishwasherWashingCourse");
        if (course != null) {
            String c = strVal(course, "washingCourse");
            if (c != null) {
                updateState(CHANNEL_WASHING_COURSE, new StringType(c));
            }
            JsonElement supported = attrValue(course, "supportedCourses");
            if (supported != null && supported.isJsonArray()) {
                updateState(CHANNEL_SUPPORTED_COURSES, new StringType(joinArray(supported.getAsJsonArray())));
            }
        }
        JsonObject opts = getCapability(root, "samsungce.dishwasherWashingOptions");
        if (opts != null) {
            String zone = optionValue(opts, "selectedZone");
            if (zone != null) {
                updateState(CHANNEL_SELECTED_ZONE, new StringType(zone));
            }
            updateBoolOption(opts, "speedBooster", CHANNEL_SPEED_BOOSTER);
            updateBoolOption(opts, "dryPlus", CHANNEL_DRY_PLUS);
            updateBoolOption(opts, "stormWash", CHANNEL_STORM_WASH);
            updateBoolOption(opts, "hotAirDry", CHANNEL_HOT_AIR_DRY);
            updateBoolOption(opts, "highTempWash", CHANNEL_HIGH_TEMP_WASH);
            updateBoolOption(opts, "sanitizingWash", CHANNEL_SANITIZING_WASH);
        }
        JsonObject delay = getCapability(root, "custom.dishwasherDelayStartTime");
        if (delay != null) {
            String d = strVal(delay, "dishwasherDelayStartTime");
            if (d != null) {
                updateState(CHANNEL_DELAY_START_TIME, new StringType(d));
            }
        }

        // ── the small stuff ─────────────────────────────────────────────────
        JsonObject kidsLockCap = getCapability(root, "samsungce.kidsLock");
        if (kidsLockCap != null) {
            String lockVal = strVal(kidsLockCap, "lockState");
            if (lockVal != null) {
                updateState(CHANNEL_KIDS_LOCK, OnOffType.from("locked".equalsIgnoreCase(lockVal)));
            }
        }
        JsonObject vol = getCapability(root, "samsungce.audioVolumeLevel");
        if (vol != null) {
            JsonElement v = attrValue(vol, "volumeLevel");
            if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                updateState(CHANNEL_VOLUME, new DecimalType(v.getAsInt()));
            }
        }
        JsonObject swUpdate = getCapability(root, "samsungce.softwareUpdate");
        if (swUpdate != null) {
            JsonElement el = attrValue(swUpdate, "newVersionAvailable");
            if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()) {
                updateState(CHANNEL_UPDATE_AVAILABLE, OnOffType.from(el.getAsBoolean()));
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Washing options come in two shapes on the DW8700B: a plain value
     * ({@code "value": false}) or an object with the value and what it may be set to
     * ({@code "value": {"value": "all", "settable": ["lower", "all"]}}). Both are read here.
     */
    private @Nullable JsonElement optionElement(JsonObject opts, String option) {
        JsonElement v = attrValue(opts, option);
        if (v == null || v.isJsonNull()) {
            return null;
        }
        if (v.isJsonObject() && v.getAsJsonObject().has("value")) {
            JsonElement inner = v.getAsJsonObject().get("value");
            return (inner == null || inner.isJsonNull()) ? null : inner;
        }
        return v;
    }

    private @Nullable String optionValue(JsonObject opts, String option) {
        JsonElement v = optionElement(opts, option);
        return (v != null && v.isJsonPrimitive()) ? v.getAsString() : null;
    }

    private void updateBoolOption(JsonObject opts, String option, String channel) {
        JsonElement v = optionElement(opts, option);
        if (v == null || !v.isJsonPrimitive()) {
            return; // not offered by this model (null in the dump) — leave the channel alone
        }
        boolean on = v.getAsJsonPrimitive().isBoolean() ? v.getAsBoolean()
                : "true".equalsIgnoreCase(v.getAsString()) || "on".equalsIgnoreCase(v.getAsString());
        updateState(channel, OnOffType.from(on));
    }

    private static String joinArray(JsonArray arr) {
        StringBuilder sb = new StringBuilder();
        for (JsonElement e : arr) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.isJsonPrimitive() ? e.getAsString() : e.toString());
        }
        return sb.toString();
    }

    private @Nullable JsonObject getCapability(JsonObject root, String capabilityId) {
        JsonElement e = root.get(capabilityId);
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
    }

    private @Nullable String strVal(JsonObject capability, String attribute) {
        JsonElement val = attrValue(capability, attribute);
        return (val != null && !val.isJsonNull() && val.isJsonPrimitive()) ? val.getAsString() : null;
    }

    private @Nullable JsonElement attrValue(JsonObject capability, String attribute) {
        JsonElement attr = capability.get(attribute);
        if (attr == null || !attr.isJsonObject()) {
            return null;
        }
        return attr.getAsJsonObject().get("value");
    }

    private static boolean isTerminalJobState(String jobState) {
        return "none".equalsIgnoreCase(jobState) || "finish".equalsIgnoreCase(jobState)
                || "finished".equalsIgnoreCase(jobState) || "stopped".equalsIgnoreCase(jobState)
                || "unknown".equalsIgnoreCase(jobState);
    }

    private @Nullable SmartThingsCloudApiClient getApiClient() {
        Bridge bridge = getBridge();
        if (bridge == null) {
            return null;
        }
        ThingHandler handler = bridge.getHandler();
        if (handler instanceof SmartThingsCloudAccountHandler accountHandler) {
            return accountHandler.getApiClient();
        }
        return null;
    }
}
