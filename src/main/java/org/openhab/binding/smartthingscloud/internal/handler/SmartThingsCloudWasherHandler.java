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
 * Thing handler for a Samsung SmartThings-connected washing machine.
 *
 * <p>
 * Polls the SmartThings REST API at a configurable interval and maps capability
 * attributes to openHAB channels. Supports commands for power, start and stop.
 *
 * <p>
 * Capability → channel mapping (Samsung samsungce namespace confirmed via live API):
 * <ul>
 * <li>{@code samsungce.washerOperatingState.operatingState} → {@code machineState} (read)</li>
 * <li>{@code washerOperatingState.setMachineState} → {@code machineState} (write — standard capability)</li>
 * <li>{@code samsungce.washerOperatingState.washerJobState} → {@code jobState}</li>
 * <li>{@code samsungce.washerOperatingState.remainingTime} (minutes int) → {@code remaining}</li>
 * <li>{@code samsungce.washerOperatingState.remainingTimeStr} ("HH:MM") → {@code remainingTimeStr}</li>
 * <li>{@code samsungce.washerOperatingState.operationTime} (total minutes) → {@code operationTime}</li>
 * <li>Derived from remainingTime → {@code completionTime}</li>
 * <li>{@code switch.switch} → {@code power}</li>
 * <li>{@code remoteControlStatus.remoteControlEnabled} → {@code remoteEnabled}</li>
 * <li>{@code powerMeter.power} / {@code powerConsumptionReport.powerConsumption.power} → {@code watt}</li>
 * <li>{@code energyMeter.energy} / {@code powerConsumptionReport.powerConsumption.energy} → {@code kwh}</li>
 * <li>{@code custom.supportedOptions.course} → {@code mode} (read); {@code samsungce.washerCycle.setWasherCycle}
 * (write)</li>
 * <li>{@code samsungce.washerRinseCycles.washerRinseCycles} → {@code rinseMode}</li>
 * <li>{@code custom.washerSpinLevel.washerSpinLevel} → {@code spinSpeed}</li>
 * <li>{@code custom.washerWaterTemperature.washerWaterTemperature} → {@code temperature}</li>
 * <li>{@code samsungce.washerBubbleSoak.status} → {@code bubbleSoak}</li>
 * <li>{@code samsungce.audioVolumeLevel.volumeLevel} → {@code volume}</li>
 * <li>{@code samsungce.clothingExtraCare.operationMode} → {@code extraCare}</li>
 * <li>{@code samsungce.clothingExtraCare.userLocation} → {@code extraCareLocation}</li>
 * <li>Derived from jobState (not none/finish/stopped) → {@code running}</li>
 * </ul>
 *
 * @author openHAB Samsung Cloud Binding - Initial contribution
 */
@NonNullByDefault
public class SmartThingsCloudWasherHandler extends BaseThingHandler {

    // machineState commands — standard washerOperatingState capability (samsungce namespace is read-only)
    private static final String CMD_RUN = "{\"commands\":[{\"component\":\"main\",\"capability\":\"washerOperatingState\",\"command\":\"setMachineState\",\"arguments\":[\"run\"]}]}";
    private static final String CMD_PAUSE = "{\"commands\":[{\"component\":\"main\",\"capability\":\"washerOperatingState\",\"command\":\"setMachineState\",\"arguments\":[\"pause\"]}]}";
    private static final String CMD_STOP = "{\"commands\":[{\"component\":\"main\",\"capability\":\"washerOperatingState\",\"command\":\"setMachineState\",\"arguments\":[\"stop\"]}]}";
    private static final String CMD_ON = "{\"commands\":[{\"component\":\"main\",\"capability\":\"switch\",\"command\":\"on\"}]}";
    private static final String CMD_OFF = "{\"commands\":[{\"component\":\"main\",\"capability\":\"switch\",\"command\":\"off\"}]}";
    // Program / settings commands
    private static final String CMD_CYCLE_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"samsungce.washerCycle\",\"command\":\"setWasherCycle\",\"arguments\":[\"%s\"]}]}";
    private static final String CMD_SPIN_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"custom.washerSpinLevel\",\"command\":\"setWasherSpinLevel\",\"arguments\":[\"%s\"]}]}";
    private static final String CMD_TEMP_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"custom.washerWaterTemperature\",\"command\":\"setWasherWaterTemperature\",\"arguments\":[\"%s\"]}]}";
    private static final String CMD_RINSE_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"samsungce.washerRinseCycles\",\"command\":\"setWasherRinseCycles\",\"arguments\":[\"%s\"]}]}";
    private static final String CMD_BUBBLE_ON = "{\"commands\":[{\"component\":\"main\",\"capability\":\"samsungce.washerBubbleSoak\",\"command\":\"on\",\"arguments\":[]}]}";
    private static final String CMD_BUBBLE_OFF = "{\"commands\":[{\"component\":\"main\",\"capability\":\"samsungce.washerBubbleSoak\",\"command\":\"off\",\"arguments\":[]}]}";
    private static final String CMD_VOLUME_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"samsungce.audioVolumeLevel\",\"command\":\"setVolumeLevel\",\"arguments\":[%d]}]}";
    private static final String CMD_EXTRA_CARE_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"samsungce.clothingExtraCare\",\"command\":\"setOperationMode\",\"arguments\":[\"%s\"]}]}";
    // Combo washer/dryer: drying settings live in the dryer capabilities on the same main component
    private static final String CMD_DRY_LEVEL_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"custom.dryerDryLevel\",\"command\":\"setDryerDryLevel\",\"arguments\":[\"%s\"]}]}";
    private static final String CMD_DRY_TIME_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"samsungce.dryerDryingTime\",\"command\":\"setDryerDryingTime\",\"arguments\":[\"%s\"]}]}";
    private static final String CMD_EXTRA_CARE_LOC_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"samsungce.clothingExtraCare\",\"command\":\"setUserLocation\",\"arguments\":[\"%s\"]}]}";

    private final Logger logger = LoggerFactory.getLogger(SmartThingsCloudWasherHandler.class);
    private final Gson gson = new GsonBuilder().create();

    private @Nullable ScheduledFuture<?> pollFuture;

    public SmartThingsCloudWasherHandler(Thing thing) {
        super(thing);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void initialize() {
        SmartThingsCloudWasherConfiguration config = getConfigAs(SmartThingsCloudWasherConfiguration.class);
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
            SmartThingsCloudWasherConfiguration config = getConfigAs(SmartThingsCloudWasherConfiguration.class);
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

        String deviceId = getConfigAs(SmartThingsCloudWasherConfiguration.class).deviceId;
        String channelId = channelUID.getIdWithoutGroup();

        if (CHANNEL_POWER.equals(channelId)) {
            client.sendCommand(deviceId, OnOffType.ON.equals(command) ? CMD_ON : CMD_OFF);

        } else if (CHANNEL_MACHINE_STATE.equals(channelId)) {
            String val = command.toString().toLowerCase();
            String body = "run".equals(val) ? CMD_RUN : "pause".equals(val) ? CMD_PAUSE : CMD_STOP;
            client.sendCommand(deviceId, body);

        } else if (CHANNEL_MODE.equals(channelId)) {
            // Write: samsungce.washerCycle.setWasherCycle
            client.sendCommand(deviceId, String.format(CMD_CYCLE_FMT, command.toString()));

        } else if (CHANNEL_SPIN_SPEED.equals(channelId)) {
            client.sendCommand(deviceId, String.format(CMD_SPIN_FMT, command.toString()));

        } else if (CHANNEL_TEMPERATURE.equals(channelId)) {
            client.sendCommand(deviceId, String.format(CMD_TEMP_FMT, command.toString()));

        } else if (CHANNEL_RINSE_MODE.equals(channelId)) {
            client.sendCommand(deviceId, String.format(CMD_RINSE_FMT, command.toString()));

        } else if (CHANNEL_BUBBLE_SOAK.equals(channelId)) {
            client.sendCommand(deviceId, OnOffType.ON.equals(command) ? CMD_BUBBLE_ON : CMD_BUBBLE_OFF);

        } else if (CHANNEL_VOLUME.equals(channelId)) {
            try {
                int vol = Integer.parseInt(command.toString());
                client.sendCommand(deviceId, String.format(CMD_VOLUME_FMT, vol));
            } catch (NumberFormatException e) {
                logger.warn("Invalid volume command '{}' — expected integer 0-3", command);
            }

        } else if (CHANNEL_EXTRA_CARE.equals(channelId)) {
            client.sendCommand(deviceId, String.format(CMD_EXTRA_CARE_FMT, command.toString()));

        } else if (CHANNEL_EXTRA_CARE_LOCATION.equals(channelId)) {
            client.sendCommand(deviceId, String.format(CMD_EXTRA_CARE_LOC_FMT, command.toString()));

        } else if (CHANNEL_DRY_LEVEL.equals(channelId)) {
            client.sendCommand(deviceId, String.format(CMD_DRY_LEVEL_FMT, command.toString()));

        } else if (CHANNEL_DRYING_TIME.equals(channelId)) {
            client.sendCommand(deviceId, String.format(CMD_DRY_TIME_FMT, command.toString()));

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

        String deviceId = getConfigAs(SmartThingsCloudWasherConfiguration.class).deviceId;
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
     * Capability names confirmed against live Samsung washer API response.
     */
    private void parseAndUpdate(String json) {
        JsonObject root = gson.fromJson(json, JsonObject.class);
        if (root == null) {
            logger.warn("Null root in SmartThings status response");
            return;
        }

        // ── samsungce.washerOperatingState ────────────────────────────────────
        // Samsung washers use the samsungce namespace, not the standard washerOperatingState capability.
        JsonObject wos = getCapability(root, "samsungce.washerOperatingState");
        if (wos != null) {
            String machineState = strVal(wos, "operatingState");
            if (machineState != null) {
                updateState(CHANNEL_MACHINE_STATE, new StringType(machineState));
            }
            String jobState = strVal(wos, "washerJobState");
            if (jobState != null) {
                updateState(CHANNEL_JOB_STATE, new StringType(jobState));
                boolean running = !isTerminalJobState(jobState);
                updateState(CHANNEL_RUNNING, OnOffType.from(running));
            }
            // remainingTime is reported in minutes as a plain integer
            JsonElement remainElem = attrValue(wos, "remainingTime");
            if (remainElem != null && !remainElem.isJsonNull()) {
                try {
                    long remainMin = remainElem.getAsLong();
                    updateState(CHANNEL_REMAINING, new DecimalType(Math.max(0, remainMin)));
                    if (remainMin > 0) {
                        ZonedDateTime completionTime = ZonedDateTime.now().plusMinutes(remainMin);
                        updateState(CHANNEL_COMPLETION_TIME, new DateTimeType(completionTime));
                    }
                } catch (Exception e) {
                    logger.debug("Could not parse remainingTime: {}", remainElem);
                }
            }
            // remainingTimeStr — formatted as "HH:MM"
            String remainStr = strVal(wos, "remainingTimeStr");
            if (remainStr != null) {
                updateState(CHANNEL_REMAINING_TIME_STR, new StringType(remainStr));
            }
            // operationTime — total cycle duration in minutes
            JsonElement opTimeElem = attrValue(wos, "operationTime");
            if (opTimeElem != null && !opTimeElem.isJsonNull()) {
                try {
                    updateState(CHANNEL_OPERATION_TIME, new DecimalType(opTimeElem.getAsLong()));
                } catch (Exception e) {
                    logger.debug("Could not parse operationTime: {}", opTimeElem);
                }
            }
            // Combo washer/dryer extras (issue #6). Absent or null on a plain washer; only updated when present.
            String phase = strVal(wos, "washerJobPhase");
            if (phase != null) {
                updateState(CHANNEL_JOB_PHASE, new StringType(phase));
            }
            updateNumberIfPresent(wos, "washingProgress", CHANNEL_WASHING_PROGRESS);
            updateNumberIfPresent(wos, "dryingProgress", CHANNEL_DRYING_PROGRESS);
            JsonElement phases = attrValue(wos, "scheduledPhases");
            if (phases == null || !phases.isJsonArray()) {
                phases = attrValue(wos, "scheduledJobs");
            }
            if (phases != null && phases.isJsonArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonElement p : phases.getAsJsonArray()) {
                    if (!p.isJsonObject()) {
                        continue;
                    }
                    JsonObject po = p.getAsJsonObject();
                    String name = po.has("phaseName") ? po.get("phaseName").getAsString()
                            : po.has("jobName") ? po.get("jobName").getAsString() : "?";
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(name);
                    if (po.has("timeInMin") && !po.get("timeInMin").isJsonNull()) {
                        sb.append(' ').append(po.get("timeInMin").getAsLong()).append('m');
                    }
                }
                updateState(CHANNEL_SCHEDULED_PHASES, new StringType(sb.toString()));
            }
        }

        // ── combo washer/dryer: cycle type and drying settings (issue #6) ─────
        // On an all-in-one machine samsungce.washerCycle carries cycleType
        // (allInOne / washingOnly / dryingOnly) and the dryer capabilities sit on
        // the same component. custom.dryerDryLevel holds either a level word
        // (none, cupboard) or the drying time in minutes as a string.
        JsonObject cycleCap = getCapability(root, "samsungce.washerCycle");
        if (cycleCap != null) {
            String cycleType = strVal(cycleCap, "cycleType");
            if (cycleType != null) {
                updateState(CHANNEL_CYCLE_TYPE, new StringType(cycleType));
            }
        }
        JsonObject dryLevel = getCapability(root, "custom.dryerDryLevel");
        if (dryLevel != null) {
            String val = strVal(dryLevel, "dryerDryLevel");
            if (val != null) {
                updateState(CHANNEL_DRY_LEVEL, new StringType(val));
            }
        }
        JsonObject dryTime = getCapability(root, "samsungce.dryerDryingTime");
        if (dryTime != null) {
            String val = strVal(dryTime, "dryingTime");
            if (val != null) {
                updateState(CHANNEL_DRYING_TIME, new StringType(val));
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

        // ── wash program — custom.supportedOptions.course ─────────────────────
        JsonObject opts = getCapability(root, "custom.supportedOptions");
        if (opts != null) {
            String val = strVal(opts, "course");
            if (val != null)
                updateState(CHANNEL_MODE, new StringType(val));
        }

        // ── rinse mode ────────────────────────────────────────────────────────
        JsonObject rinse = getCapability(root, "samsungce.washerRinseCycles");
        if (rinse != null) {
            String val = strVal(rinse, "washerRinseCycles");
            if (val != null)
                updateState(CHANNEL_RINSE_MODE, new StringType(val));
        }

        // ── spin speed — custom.washerSpinLevel ───────────────────────────────
        JsonObject spin = getCapability(root, "custom.washerSpinLevel");
        if (spin != null) {
            String val = strVal(spin, "washerSpinLevel");
            if (val != null)
                updateState(CHANNEL_SPIN_SPEED, new StringType(val));
        }

        // ── water temperature — custom.washerWaterTemperature ─────────────────
        JsonObject temp = getCapability(root, "custom.washerWaterTemperature");
        if (temp != null) {
            String val = strVal(temp, "washerWaterTemperature");
            if (val != null)
                updateState(CHANNEL_TEMPERATURE, new StringType(val));
        }

        // ── bubble soak — samsungce.washerBubbleSoak ──────────────────────────
        JsonObject bubble = getCapability(root, "samsungce.washerBubbleSoak");
        if (bubble != null) {
            String val = strVal(bubble, "status");
            if (val != null)
                updateState(CHANNEL_BUBBLE_SOAK, OnOffType.from("on".equalsIgnoreCase(val)));
        }

        // ── volume — samsungce.audioVolumeLevel ───────────────────────────────
        JsonObject audio = getCapability(root, "samsungce.audioVolumeLevel");
        if (audio != null) {
            String val = strVal(audio, "volumeLevel");
            if (val != null) {
                try {
                    updateState(CHANNEL_VOLUME, new DecimalType(Integer.parseInt(val)));
                } catch (NumberFormatException e) {
                    logger.debug("Unexpected volumeLevel value: {}", val);
                }
            }
        }

        // ── extra care ────────────────────────────────────────────────────────
        JsonObject extraCare = getCapability(root, "samsungce.clothingExtraCare");
        if (extraCare != null) {
            String mode = strVal(extraCare, "operationMode");
            if (mode != null)
                updateState(CHANNEL_EXTRA_CARE, new StringType(mode));
            String loc = strVal(extraCare, "userLocation");
            if (loc != null)
                updateState(CHANNEL_EXTRA_CARE_LOCATION, new StringType(loc));
        }

        // ── water consumption (liters) — samsungce.waterConsumptionReport ────────
        JsonObject waterCap = getCapability(root, "samsungce.waterConsumptionReport");
        if (waterCap != null) {
            JsonElement waterVal = attrValue(waterCap, "waterConsumption");
            if (waterVal != null && waterVal.isJsonObject()) {
                JsonElement cumEl = waterVal.getAsJsonObject().get("cumulativeAmount");
                if (cumEl != null && !cumEl.isJsonNull()) {
                    updateState(CHANNEL_WATER_LITERS, new DecimalType(cumEl.getAsDouble() / 1000.0));
                }
            }
        }

        // ── kids lock — samsungce.kidsLock ────────────────────────────────────
        JsonObject kidsLockCap = getCapability(root, "samsungce.kidsLock");
        if (kidsLockCap != null) {
            String lockVal = strVal(kidsLockCap, "lockState");
            if (lockVal != null)
                updateState(CHANNEL_KIDS_LOCK, OnOffType.from("locked".equalsIgnoreCase(lockVal)));
        }

        // ── current cycle (read) — samsungce.washerCycle ─────────────────────
        JsonObject washerCycleCap = getCapability(root, "samsungce.washerCycle");
        if (washerCycleCap != null) {
            String cycleVal = strVal(washerCycleCap, "washerCycle");
            if (cycleVal != null) {
                // "Table_02_Course_B0" → "B0"
                int idx = cycleVal.lastIndexOf('_');
                String cycleCode = idx >= 0 ? cycleVal.substring(idx + 1) : cycleVal;
                updateState(CHANNEL_CURRENT_CYCLE, new StringType(cycleCode));
            }
        }

        // ── operating state + progress — samsungce.washerOperatingState ──────
        JsonObject samsungWos = getCapability(root, "samsungce.washerOperatingState");
        if (samsungWos != null) {
            String opState = strVal(samsungWos, "operatingState");
            if (opState != null)
                updateState(CHANNEL_OPERATING_STATE, new StringType(opState));
            JsonElement progressEl = attrValue(samsungWos, "progress");
            if (progressEl != null && !progressEl.isJsonNull()) {
                try {
                    updateState(CHANNEL_PROGRESS, new DecimalType(progressEl.getAsInt()));
                } catch (NumberFormatException e) {
                    logger.debug("Unexpected progress value: {}", progressEl);
                }
            }
        }

        // ── detergent remaining — samsungce.detergentState ─────────────────────
        JsonObject detergent = getCapability(root, "samsungce.detergentState");
        if (detergent != null) {
            JsonElement el = attrValue(detergent, "remainingAmount");
            if (el != null && !el.isJsonNull()) {
                updateState(CHANNEL_DETERGENT_REMAINING, new DecimalType(el.getAsDouble()));
            }
        }

        // ── softener remaining — samsungce.softenerState ──────────────────────
        JsonObject softener = getCapability(root, "samsungce.softenerState");
        if (softener != null) {
            JsonElement el = attrValue(softener, "remainingAmount");
            if (el != null && !el.isJsonNull()) {
                updateState(CHANNEL_SOFTENER_REMAINING, new DecimalType(el.getAsDouble()));
            }
        }

        // ── delay end remaining minutes — samsungce.washerDelayEnd ─────────────
        JsonObject delayEnd = getCapability(root, "samsungce.washerDelayEnd");
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
                boolean available = el.getAsBoolean();
                updateState(CHANNEL_UPDATE_AVAILABLE, OnOffType.from(available));
            }
        }

        // ── supported courses — custom.supportedOptions.supportedCourses ──────
        JsonObject suppOpts = getCapability(root, "custom.supportedOptions");
        if (suppOpts != null) {
            JsonElement coursesEl = attrValue(suppOpts, "supportedCourses");
            if (coursesEl != null && coursesEl.isJsonArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonElement c : coursesEl.getAsJsonArray()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(c.getAsString());
                }
                if (sb.length() > 0) updateState(CHANNEL_SUPPORTED_COURSES, new StringType(sb.toString()));
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Updates a Number channel from an attribute when it is present and numeric; leaves it alone otherwise. */
    private void updateNumberIfPresent(JsonObject capability, String attribute, String channel) {
        JsonElement el = attrValue(capability, attribute);
        if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            updateState(channel, new DecimalType(el.getAsDouble()));
        }
    }

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
