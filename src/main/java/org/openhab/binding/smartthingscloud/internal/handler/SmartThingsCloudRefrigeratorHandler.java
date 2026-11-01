/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.smartthingscloud.internal.handler;

import static org.openhab.binding.smartthingscloud.SmartThingsCloudBindingConstants.*;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.measure.Unit;
import javax.measure.quantity.Temperature;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.ImperialUnits;
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
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Thing handler for a Samsung SmartThings-connected refrigerator.
 *
 * <p>
 * The first multi-component device in this binding. A Samsung fridge advertises a template of up to fifteen
 * components (main, cooler, freezer, cvroom, onedoor, icemakers, pantries, scales, ...) and tells, in
 * {@code main.custom.disabledComponents}, which of them this model does not have; a second list,
 * {@code custom.disabledCapabilities} on each component, removes capabilities that are advertised but dead -
 * notably {@code temperatureMeasurement} and {@code thermostatCoolingSetpoint} on {@code main}, whose values
 * are null. The real temperatures and setpoints live on the compartment components (issue #3: three dumps,
 * RB53DG706BB1EF, RB34C605CB1/EF and a Family Hub, all with cooler and freezer).
 *
 * <p>
 * So this handler polls {@code /devices/{id}/status} (every component at once), reads {@code main} and then
 * only the compartments that are neither disabled nor missing, and maps them to channel groups:
 * <ul>
 * <li>{@code main#door} - {@code contactSensor.contact} (open/closed)</li>
 * <li>{@code main#powerCool} / {@code main#powerFreeze} - {@code samsungce.powerCool.activated} /
 * {@code samsungce.powerFreeze.activated}, fallback {@code refrigeration.rapidCooling} / {@code rapidFreezing};
 * written through the standard {@code refrigeration} capability</li>
 * <li>{@code main#vacationMode} - {@code samsungce.fridgeVacationMode.vacationMode} (on/off, read/write)</li>
 * <li>{@code main#watt} / {@code main#kwh} - {@code powerConsumptionReport.powerConsumption} (W, Wh -> kWh)</li>
 * <li>{@code main#waterFilterUsage} / {@code main#waterFilterStatus} - {@code custom.waterFilter}</li>
 * <li>{@code main#updateAvailable} - {@code samsungce.softwareUpdate.newVersionAvailable}</li>
 * <li>{@code main#components} - the compartments this fridge actually has, comma separated</li>
 * <li>{@code <compartment>#door}, {@code #temperature}, {@code #setpoint} (read/write), {@code #setpointMin},
 * {@code #setpointMax} - {@code contactSensor}, {@code temperatureMeasurement},
 * {@code thermostatCoolingSetpoint} (its {@code coolingSetpointRange}, else
 * {@code custom.thermostatSetpointControl}) on cooler, freezer, cvroom and onedoor</li>
 * </ul>
 * The temperature unit comes from the attribute's {@code unit} field (C or F); a setpoint command in the other
 * unit is converted before it is sent. Nothing here has been run against a live fridge by the author (who owns
 * none); the three dumps fix the read side, the write side follows SmartThings' capability reference.
 *
 * @author Nanna Agesen - Initial contribution
 */
@NonNullByDefault
public class SmartThingsCloudRefrigeratorHandler extends BaseThingHandler {

    /** The compartment groups the thing type declares. The fridge says which of them exist. */
    static final List<String> COMPARTMENTS = List.of(GROUP_COOLER, GROUP_FREEZER, GROUP_CVROOM, GROUP_ONEDOOR);

    private static final String CMD_SETPOINT_FMT = "{\"commands\":[{\"component\":\"%s\",\"capability\":\"thermostatCoolingSetpoint\",\"command\":\"setCoolingSetpoint\",\"arguments\":[%s]}]}";
    private static final String CMD_RAPID_COOLING_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"refrigeration\",\"command\":\"setRapidCooling\",\"arguments\":[\"%s\"]}]}";
    private static final String CMD_RAPID_FREEZING_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"refrigeration\",\"command\":\"setRapidFreezing\",\"arguments\":[\"%s\"]}]}";
    private static final String CMD_VACATION_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"samsungce.fridgeVacationMode\",\"command\":\"setVacationMode\",\"arguments\":[\"%s\"]}]}";

    private final Logger logger = LoggerFactory.getLogger(SmartThingsCloudRefrigeratorHandler.class);
    private static final Gson GSON = new GsonBuilder().create();

    private @Nullable ScheduledFuture<?> pollFuture;
    /** The unit each compartment reports its temperatures in, so a setpoint command can be converted. */
    private final Map<String, Unit<Temperature>> units = new LinkedHashMap<>();

    public SmartThingsCloudRefrigeratorHandler(Thing thing) {
        super(thing);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void initialize() {
        SmartThingsCloudRefrigeratorConfiguration config = getConfigAs(SmartThingsCloudRefrigeratorConfiguration.class);
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
            schedulePoll(getConfigAs(SmartThingsCloudRefrigeratorConfiguration.class).pollingIntervalSeconds);
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
        String deviceId = getConfigAs(SmartThingsCloudRefrigeratorConfiguration.class).deviceId;
        String body = commandBody(channelUID.getGroupId(), channelUID.getIdWithoutGroup(), command,
                units.get(String.valueOf(channelUID.getGroupId())));
        if (body == null) {
            logger.debug("No command handler for channel {}", channelUID.getId());
            return;
        }
        client.sendCommand(deviceId, body);
    }

    /**
     * The SmartThings command body for a channel command, or null when the channel is not writable.
     * Package-private and static so the mapping is testable without a thing.
     *
     * @param unit the unit the compartment reports in (a setpoint in another unit is converted), or null
     */
    static @Nullable String commandBody(@Nullable String group, String channel, Command command,
            @Nullable Unit<Temperature> unit) {
        if (group == null) {
            return null;
        }
        if (COMPARTMENTS.contains(group) && CHANNEL_SETPOINT.equals(channel)) {
            Number value = null;
            if (command instanceof QuantityType<?> q) {
                QuantityType<?> converted = unit != null ? q.toUnit(unit) : q;
                value = converted != null ? converted.toBigDecimal() : q.toBigDecimal();
            } else if (command instanceof DecimalType d) {
                value = d.toBigDecimal();
            } else {
                try {
                    value = Double.parseDouble(command.toString());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            // Samsung's ranges are whole degrees with step 1; send an integer when the value is one
            double v = value.doubleValue();
            String arg = v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
            return String.format(Locale.ROOT, CMD_SETPOINT_FMT, group, arg);
        }
        if (!GROUP_MAIN.equals(group)) {
            return null;
        }
        String onOff = OnOffType.ON.equals(command) ? "on" : OnOffType.OFF.equals(command) ? "off" : null;
        if (onOff == null) {
            return null;
        }
        switch (channel) {
            case CHANNEL_POWER_COOL:
                return String.format(Locale.ROOT, CMD_RAPID_COOLING_FMT, onOff);
            case CHANNEL_POWER_FREEZE:
                return String.format(Locale.ROOT, CMD_RAPID_FREEZING_FMT, onOff);
            case CHANNEL_VACATION_MODE:
                return String.format(Locale.ROOT, CMD_VACATION_FMT, onOff);
            default:
                return null;
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
        String deviceId = getConfigAs(SmartThingsCloudRefrigeratorConfiguration.class).deviceId;
        String json = client.getDeviceStatus(deviceId);
        if (json == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "No response from SmartThings API");
            return;
        }
        try {
            Snapshot snap = parse(json);
            units.putAll(snap.units);
            for (Map.Entry<String, State> e : snap.states.entrySet()) {
                updateState(e.getKey(), e.getValue());
            }
            updateStatus(ThingStatus.ONLINE);
        } catch (RuntimeException e) {
            logger.warn("Failed to parse SmartThings status for device {}: {}", deviceId, e.getMessage(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Parse error: " + e.getMessage());
        }
    }

    // ── JSON parsing ──────────────────────────────────────────────────────────

    /** What one poll found: channel states keyed {@code group#channel}, and each compartment's unit. */
    static final class Snapshot {
        final Map<String, State> states = new LinkedHashMap<>();
        final Map<String, Unit<Temperature>> units = new LinkedHashMap<>();
        /** The compartments present and enabled, in the thing type's order. */
        final List<String> compartments = new java.util.ArrayList<>();
    }

    /**
     * Parses a {@code /devices/{id}/status} response. Static and free of thing state so the three dumps of
     * issue #3 can be replayed in a unit test.
     */
    static Snapshot parse(String json) {
        Snapshot snap = new Snapshot();
        JsonObject root = GSON.fromJson(json, JsonObject.class);
        JsonObject components = root != null ? obj(root, "components") : null;
        if (components == null) {
            throw new IllegalArgumentException("no \"components\" in the status response");
        }
        JsonObject main = obj(components, GROUP_MAIN);
        Set<String> disabledComponents = new HashSet<>();
        if (main != null) {
            disabledComponents.addAll(stringList(attrValue(cap(main, "custom.disabledComponents"), "disabledComponents")));
            parseMain(main, snap);
        }
        for (String name : COMPARTMENTS) {
            JsonObject comp = obj(components, name);
            if (comp == null || disabledComponents.contains(name)) {
                continue;
            }
            snap.compartments.add(name);
            parseCompartment(name, comp, snap);
        }
        snap.states.put(GROUP_MAIN + "#" + CHANNEL_COMPONENTS, new StringType(String.join(", ", snap.compartments)));
        return snap;
    }

    private static void parseMain(JsonObject main, Snapshot snap) {
        Set<String> disabled = new HashSet<>(
                stringList(attrValue(cap(main, "custom.disabledCapabilities"), "disabledCapabilities")));
        String prefix = GROUP_MAIN + "#";

        String contact = strVal(enabled(main, "contactSensor", disabled), "contact");
        if (contact != null) {
            snap.states.put(prefix + CHANNEL_DOOR, "open".equalsIgnoreCase(contact) ? OpenClosedType.OPEN : OpenClosedType.CLOSED);
        }

        // Power Cool / Power Freeze: Samsung's own capability first, the standard refrigeration one as fallback
        JsonObject refrigeration = enabled(main, "refrigeration", disabled);
        Boolean cool = bool(attrValue(enabled(main, "samsungce.powerCool", disabled), "activated"));
        if (cool == null) {
            cool = onOff(strVal(refrigeration, "rapidCooling"));
        }
        if (cool != null) {
            snap.states.put(prefix + CHANNEL_POWER_COOL, OnOffType.from(cool));
        }
        Boolean freeze = bool(attrValue(enabled(main, "samsungce.powerFreeze", disabled), "activated"));
        if (freeze == null) {
            freeze = onOff(strVal(refrigeration, "rapidFreezing"));
        }
        if (freeze != null) {
            snap.states.put(prefix + CHANNEL_POWER_FREEZE, OnOffType.from(freeze));
        }

        Boolean vacation = onOff(strVal(enabled(main, "samsungce.fridgeVacationMode", disabled), "vacationMode"));
        if (vacation != null) {
            snap.states.put(prefix + CHANNEL_VACATION_MODE, OnOffType.from(vacation));
        }

        JsonElement pc = attrValue(enabled(main, "powerConsumptionReport", disabled), "powerConsumption");
        if (pc != null && pc.isJsonObject()) {
            JsonObject p = pc.getAsJsonObject();
            if (p.has("power") && !p.get("power").isJsonNull()) {
                snap.states.put(prefix + CHANNEL_WATT, new DecimalType(p.get("power").getAsDouble()));
            }
            if (p.has("energy") && !p.get("energy").isJsonNull()) {
                // reported in Wh, like the washer and the dryer
                snap.states.put(prefix + CHANNEL_KWH, new DecimalType(p.get("energy").getAsDouble() / 1000.0));
            }
        }

        JsonObject filter = enabled(main, "custom.waterFilter", disabled);
        JsonElement usage = attrValue(filter, "waterFilterUsage");
        if (usage != null && usage.isJsonPrimitive()) {
            snap.states.put(prefix + CHANNEL_WATER_FILTER_USAGE, new DecimalType(usage.getAsDouble()));
        }
        String filterStatus = strVal(filter, "waterFilterStatus");
        if (filterStatus != null) {
            snap.states.put(prefix + CHANNEL_WATER_FILTER_STATUS, new StringType(filterStatus));
        }

        Boolean update = bool(attrValue(enabled(main, "samsungce.softwareUpdate", disabled), "newVersionAvailable"));
        if (update != null) {
            snap.states.put(prefix + CHANNEL_UPDATE_AVAILABLE, OnOffType.from(update));
        }
    }

    private static void parseCompartment(String name, JsonObject comp, Snapshot snap) {
        Set<String> disabled = new HashSet<>(
                stringList(attrValue(cap(comp, "custom.disabledCapabilities"), "disabledCapabilities")));
        String prefix = name + "#";

        String contact = strVal(enabled(comp, "contactSensor", disabled), "contact");
        if (contact != null) {
            snap.states.put(prefix + CHANNEL_DOOR, "open".equalsIgnoreCase(contact) ? OpenClosedType.OPEN : OpenClosedType.CLOSED);
        }

        JsonObject tm = enabled(comp, "temperatureMeasurement", disabled);
        JsonObject tcs = enabled(comp, "thermostatCoolingSetpoint", disabled);
        Unit<Temperature> unit = unitOf(attrUnit(tm, "temperature"), attrUnit(tcs, "coolingSetpoint"));
        snap.units.put(name, unit);

        JsonElement temperature = attrValue(tm, "temperature");
        if (temperature != null && temperature.isJsonPrimitive()) {
            snap.states.put(prefix + CHANNEL_FRIDGE_TEMPERATURE, new QuantityType<>(temperature.getAsDouble(), unit));
        }
        JsonElement setpoint = attrValue(tcs, "coolingSetpoint");
        if (setpoint != null && setpoint.isJsonPrimitive()) {
            snap.states.put(prefix + CHANNEL_SETPOINT, new QuantityType<>(setpoint.getAsDouble(), unit));
        }

        // the allowed range: thermostatCoolingSetpoint.coolingSetpointRange {minimum, maximum, step}, else
        // custom.thermostatSetpointControl {minimumSetpoint, maximumSetpoint}
        JsonElement range = attrValue(tcs, "coolingSetpointRange");
        Double min = null;
        Double max = null;
        if (range != null && range.isJsonObject()) {
            min = num(range.getAsJsonObject(), "minimum");
            max = num(range.getAsJsonObject(), "maximum");
        }
        JsonObject control = enabled(comp, "custom.thermostatSetpointControl", disabled);
        if (min == null) {
            min = numVal(attrValue(control, "minimumSetpoint"));
        }
        if (max == null) {
            max = numVal(attrValue(control, "maximumSetpoint"));
        }
        if (min != null) {
            snap.states.put(prefix + CHANNEL_SETPOINT_MIN, new QuantityType<>(min, unit));
        }
        if (max != null) {
            snap.states.put(prefix + CHANNEL_SETPOINT_MAX, new QuantityType<>(max, unit));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static @Nullable JsonObject obj(JsonObject parent, String key) {
        JsonElement e = parent.get(key);
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
    }

    private static @Nullable JsonObject cap(@Nullable JsonObject component, String capabilityId) {
        return component == null ? null : obj(component, capabilityId);
    }

    /** The capability, unless the component lists it as disabled. */
    private static @Nullable JsonObject enabled(JsonObject component, String capabilityId, Set<String> disabled) {
        return disabled.contains(capabilityId) ? null : obj(component, capabilityId);
    }

    private static @Nullable JsonElement attrValue(@Nullable JsonObject capability, String attribute) {
        if (capability == null) {
            return null;
        }
        JsonElement attr = capability.get(attribute);
        if (attr == null || !attr.isJsonObject()) {
            return null;
        }
        JsonElement v = attr.getAsJsonObject().get("value");
        return (v == null || v.isJsonNull()) ? null : v;
    }

    private static @Nullable String attrUnit(@Nullable JsonObject capability, String attribute) {
        if (capability == null) {
            return null;
        }
        JsonElement attr = capability.get(attribute);
        if (attr == null || !attr.isJsonObject()) {
            return null;
        }
        JsonElement u = attr.getAsJsonObject().get("unit");
        return (u != null && u.isJsonPrimitive()) ? u.getAsString() : null;
    }

    private static @Nullable String strVal(@Nullable JsonObject capability, String attribute) {
        JsonElement v = attrValue(capability, attribute);
        return (v != null && v.isJsonPrimitive()) ? v.getAsString() : null;
    }

    private static @Nullable Boolean bool(@Nullable JsonElement v) {
        if (v == null || !v.isJsonPrimitive()) {
            return null;
        }
        if (v.getAsJsonPrimitive().isBoolean()) {
            return v.getAsBoolean();
        }
        return "true".equalsIgnoreCase(v.getAsString());
    }

    private static @Nullable Boolean onOff(@Nullable String s) {
        if (s == null) {
            return null;
        }
        return "on".equalsIgnoreCase(s) ? Boolean.TRUE : "off".equalsIgnoreCase(s) ? Boolean.FALSE : null;
    }

    private static @Nullable Double num(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e != null && e.isJsonPrimitive()) ? e.getAsDouble() : null;
    }

    private static @Nullable Double numVal(@Nullable JsonElement e) {
        return (e != null && e.isJsonPrimitive()) ? e.getAsDouble() : null;
    }

    private static List<String> stringList(@Nullable JsonElement e) {
        List<String> out = new java.util.ArrayList<>();
        if (e != null && e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                if (x.isJsonPrimitive()) {
                    out.add(x.getAsString());
                }
            }
        }
        return out;
    }

    /** "C" / "F" from the attribute's unit field; Celsius when the fridge does not say. */
    static Unit<Temperature> unitOf(@Nullable String... units) {
        for (String u : units) {
            if (u != null && u.toUpperCase(Locale.ROOT).startsWith("F")) {
                return ImperialUnits.FAHRENHEIT;
            }
            if (u != null && u.toUpperCase(Locale.ROOT).startsWith("C")) {
                return SIUnits.CELSIUS;
            }
        }
        return SIUnits.CELSIUS;
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
