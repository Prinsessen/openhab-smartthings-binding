/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.smartthingscloud.internal.handler;

import static org.openhab.binding.smartthingscloud.SmartThingsCloudBindingConstants.*;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.SIUnits;
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
 * Thing handler for a Samsung SmartThings-connected air conditioner.
 *
 * <p>
 * Polls the SmartThings REST API at a configurable interval and maps capability
 * attributes to openHAB channels. Supports commands for power, mode, target
 * temperature and fan mode.
 *
 * <p>
 * Capability → channel mapping (standard SmartThings capabilities — Samsung ACs
 * do not use the samsungce namespace quirk that washers/TVs do):
 * <ul>
 * <li>{@code switch.switch} → {@code power} (read/write — switch.on / switch.off)</li>
 * <li>{@code airConditionerMode.airConditionerMode} → {@code mode} (read/write —
 * airConditionerMode.setAirConditionerMode)</li>
 * <li>{@code thermostatCoolingSetpoint.coolingSetpoint} → {@code targetTemperature}
 * (read/write — thermostatCoolingSetpoint.setCoolingSetpoint)</li>
 * <li>{@code temperatureMeasurement.temperature} → {@code currentTemperature} (read only)</li>
 * <li>{@code airConditionerFanMode.fanMode} → {@code fanMode} (read/write —
 * airConditionerFanMode.setFanMode)</li>
 * <li>{@code fanOscillationMode.fanOscillationMode} → {@code fanOscillationMode}
 * (read/write — fanOscillationMode.setFanOscillationMode)</li>
 * <li>{@code custom.airConditionerOptionalMode.acOptionalMode} → {@code optionalMode}
 * (read/write — custom.airConditionerOptionalMode.setAcOptionalMode)</li>
 * </ul>
 *
 * @author openHAB Samsung Cloud Binding - Initial contribution
 */
@NonNullByDefault
public class SmartThingsCloudAirConditionerHandler extends BaseThingHandler {

    // Power
    private static final String CMD_ON = "{\"commands\":[{\"component\":\"main\",\"capability\":\"switch\",\"command\":\"on\"}]}";
    private static final String CMD_OFF = "{\"commands\":[{\"component\":\"main\",\"capability\":\"switch\",\"command\":\"off\"}]}";

    // Mode (auto, cool, dry, fan, heat — exact supported list depends on device)
    private static final String CMD_MODE_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"airConditionerMode\",\"command\":\"setAirConditionerMode\",\"arguments\":[\"%s\"]}]}";

    // Target temperature — SmartThings expects a numeric argument (not a string)
    private static final String CMD_SETPOINT_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"thermostatCoolingSetpoint\",\"command\":\"setCoolingSetpoint\",\"arguments\":[%s]}]}";

    // Fan mode (auto, low, medium, high, turbo — exact supported list depends on device)
    private static final String CMD_FAN_MODE_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"airConditionerFanMode\",\"command\":\"setFanMode\",\"arguments\":[\"%s\"]}]}";

    // Fan oscillation mode (fixed, vertical, horizontal, all, etc. — device-dependent)
    private static final String CMD_FAN_OSCILLATION_MODE_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"fanOscillationMode\",\"command\":\"setFanOscillationMode\",\"arguments\":[\"%s\"]}]}";

    // Optional mode (windFree, sleep, speed, etc. — Samsung-specific, device-dependent)
    private static final String CMD_OPTIONAL_MODE_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"custom.airConditionerOptionalMode\",\"command\":\"setAcOptionalMode\",\"arguments\":[\"%s\"]}]}";

    private final Logger logger = LoggerFactory.getLogger(SmartThingsCloudAirConditionerHandler.class);
    private final Gson gson = new GsonBuilder().create();

    private @Nullable ScheduledFuture<?> pollFuture;

    public SmartThingsCloudAirConditionerHandler(Thing thing) {
        super(thing);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void initialize() {
        SmartThingsCloudAirConditionerConfiguration config = getConfigAs(
                SmartThingsCloudAirConditionerConfiguration.class);
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
            SmartThingsCloudAirConditionerConfiguration config = getConfigAs(
                    SmartThingsCloudAirConditionerConfiguration.class);
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

        String deviceId = getConfigAs(SmartThingsCloudAirConditionerConfiguration.class).deviceId;
        String channelId = channelUID.getIdWithoutGroup();

        if (CHANNEL_AC_POWER.equals(channelId)) {
            client.sendCommand(deviceId, OnOffType.ON.equals(command) ? CMD_ON : CMD_OFF);

        } else if (CHANNEL_AC_MODE.equals(channelId)) {
            client.sendCommand(deviceId, String.format(CMD_MODE_FMT, command.toString()));

        } else if (CHANNEL_AC_TARGET_TEMPERATURE.equals(channelId)) {
            double celsius = toCelsius(command);
            // SmartThings expects a bare number, e.g. 23 or 23.5 — never quoted
            String numericArg = (celsius == Math.floor(celsius)) ? String.valueOf((int) celsius)
                    : String.valueOf(celsius);
            client.sendCommand(deviceId, String.format(CMD_SETPOINT_FMT, numericArg));

        } else if (CHANNEL_AC_FAN_MODE.equals(channelId)) {
            client.sendCommand(deviceId, String.format(CMD_FAN_MODE_FMT, command.toString()));

        } else if (CHANNEL_AC_FAN_OSCILLATION_MODE.equals(channelId)) {
            client.sendCommand(deviceId, String.format(CMD_FAN_OSCILLATION_MODE_FMT, command.toString()));

        } else if (CHANNEL_AC_OPTIONAL_MODE.equals(channelId)) {
            client.sendCommand(deviceId, String.format(CMD_OPTIONAL_MODE_FMT, command.toString()));

        } else {
            logger.debug("No command handler for channel {}", channelId);
        }
    }

    /** Accepts QuantityType (Number:Temperature) or plain DecimalType/String commands. */
    private double toCelsius(Command command) {
        if (command instanceof QuantityType<?> qt) {
            QuantityType<?> celsiusQt = qt.toUnit(SIUnits.CELSIUS);
            if (celsiusQt != null) {
                return celsiusQt.doubleValue();
            }
        }
        if (command instanceof DecimalType dt) {
            return dt.doubleValue();
        }
        try {
            return Double.parseDouble(command.toString());
        } catch (NumberFormatException e) {
            logger.warn("Could not parse target temperature command '{}' — defaulting to 0", command);
            return 0;
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

        String deviceId = getConfigAs(SmartThingsCloudAirConditionerConfiguration.class).deviceId;
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
     */
    private void parseAndUpdate(String json) {
        JsonObject root = gson.fromJson(json, JsonObject.class);
        if (root == null) {
            logger.warn("Null root in SmartThings status response");
            return;
        }

        // ── power — switch.switch ─────────────────────────────────────────────
        JsonObject sw = getCapability(root, "switch");
        if (sw != null) {
            String swVal = strVal(sw, "switch");
            if (swVal != null) {
                updateState(CHANNEL_AC_POWER, OnOffType.from("on".equalsIgnoreCase(swVal)));
            }
        }

        // ── mode — airConditionerMode.airConditionerMode ─────────────────────
        JsonObject acMode = getCapability(root, "airConditionerMode");
        if (acMode != null) {
            String modeVal = strVal(acMode, "airConditionerMode");
            if (modeVal != null) {
                updateState(CHANNEL_AC_MODE, new StringType(modeVal));
            }
        }

        // ── target temperature — thermostatCoolingSetpoint.coolingSetpoint ───
        JsonObject setpoint = getCapability(root, "thermostatCoolingSetpoint");
        if (setpoint != null) {
            JsonElement setpointVal = attrValue(setpoint, "coolingSetpoint");
            if (setpointVal != null && !setpointVal.isJsonNull()) {
                try {
                    updateState(CHANNEL_AC_TARGET_TEMPERATURE,
                            new QuantityType<>(setpointVal.getAsDouble(), SIUnits.CELSIUS));
                } catch (NumberFormatException e) {
                    logger.debug("Unexpected coolingSetpoint value: {}", setpointVal);
                }
            }
        }

        // ── current temperature — temperatureMeasurement.temperature ─────────
        JsonObject tempMeas = getCapability(root, "temperatureMeasurement");
        if (tempMeas != null) {
            JsonElement tempVal = attrValue(tempMeas, "temperature");
            if (tempVal != null && !tempVal.isJsonNull()) {
                try {
                    updateState(CHANNEL_AC_CURRENT_TEMPERATURE,
                            new QuantityType<>(tempVal.getAsDouble(), SIUnits.CELSIUS));
                } catch (NumberFormatException e) {
                    logger.debug("Unexpected temperature value: {}", tempVal);
                }
            }
        }

        // ── fan mode — airConditionerFanMode.fanMode ──────────────────────────
        JsonObject fanMode = getCapability(root, "airConditionerFanMode");
        if (fanMode != null) {
            String fanVal = strVal(fanMode, "fanMode");
            if (fanVal != null) {
                updateState(CHANNEL_AC_FAN_MODE, new StringType(fanVal));
            }
        }

        // ── fan oscillation mode — fanOscillationMode.fanOscillationMode ─────
        JsonObject fanOscMode = getCapability(root, "fanOscillationMode");
        if (fanOscMode != null) {
            String fanOscVal = strVal(fanOscMode, "fanOscillationMode");
            if (fanOscVal != null) {
                updateState(CHANNEL_AC_FAN_OSCILLATION_MODE, new StringType(fanOscVal));
            }
        }

        // ── optional mode — custom.airConditionerOptionalMode.acOptionalMode ─
        JsonObject optMode = getCapability(root, "custom.airConditionerOptionalMode");
        if (optMode != null) {
            String optVal = strVal(optMode, "acOptionalMode");
            if (optVal != null) {
                updateState(CHANNEL_AC_OPTIONAL_MODE, new StringType(optVal));
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
