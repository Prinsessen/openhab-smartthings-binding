/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.smartthingscloud.internal.handler;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.smartthingscloud.internal.handler.SmartThingsCloudRefrigeratorHandler.Snapshot;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.ImperialUnits;
import org.openhab.core.library.unit.SIUnits;

/**
 * The refrigerator's status parsing, replayed from the shape of the three dumps in issue #3 (values as
 * reported there, every id removed). The author owns no fridge: this is the whole read-side proof until a
 * tester answers.
 *
 * @author Nanna Agesen - Initial contribution
 */
@NonNullByDefault
public class SmartThingsCloudRefrigeratorHandlerTest {

    /** A status like the Family Hub dump of 2026-10-31: the template with ten components disabled. */
    private static final String FAMILY_HUB = """
            {"components": {
              "main": {
                "contactSensor": {"contact": {"value": "closed"}},
                "custom.disabledComponents": {"disabledComponents": {"value":
                  ["icemaker-02","icemaker-03","pantry-01","pantry-02","curdmaker","camera-01","scale-10","scale-11","cvroom","onedoor"]}},
                "custom.disabledCapabilities": {"disabledCapabilities": {"value":
                  ["temperatureMeasurement","thermostatCoolingSetpoint","custom.fridgeMode","demandResponseLoadControl"]}},
                "temperatureMeasurement": {"temperature": {"value": null}},
                "thermostatCoolingSetpoint": {"coolingSetpoint": {"value": null}},
                "samsungce.powerCool": {"activated": {"value": false}},
                "samsungce.powerFreeze": {"activated": {"value": true}},
                "refrigeration": {"rapidCooling": {"value": "off"}, "rapidFreezing": {"value": "off"}, "defrost": {"value": null}},
                "samsungce.fridgeVacationMode": {"vacationMode": {"value": "off"}},
                "powerConsumptionReport": {"powerConsumption": {"value": {"energy": 1709767, "deltaEnergy": 0, "power": 1031}}},
                "custom.waterFilter": {"waterFilterUsage": {"value": 52}, "waterFilterStatus": {"value": "normal"}},
                "samsungce.softwareUpdate": {"newVersionAvailable": {"value": false}}
              },
              "cvroom": {
                "temperatureMeasurement": {"temperature": {"value": null}},
                "thermostatCoolingSetpoint": {"coolingSetpoint": {"value": null}},
                "contactSensor": {"contact": {"value": null}},
                "custom.disabledCapabilities": {"disabledCapabilities": {"value": []}}
              },
              "onedoor": {
                "temperatureMeasurement": {"temperature": {"value": 2, "unit": "C"}},
                "contactSensor": {"contact": {"value": "closed"}}
              },
              "icemaker": {"switch": {"switch": {"value": "off"}}},
              "cooler": {
                "temperatureMeasurement": {"temperature": {"value": 3, "unit": "C"}},
                "thermostatCoolingSetpoint": {"coolingSetpointRange": {"value": {"minimum": 1, "maximum": 7, "step": 1}, "unit": "C"},
                                              "coolingSetpoint": {"value": 3, "unit": "C"}},
                "contactSensor": {"contact": {"value": "open"}},
                "custom.thermostatSetpointControl": {"minimumSetpoint": {"value": 1}, "maximumSetpoint": {"value": 7}},
                "custom.disabledCapabilities": {"disabledCapabilities": {"value": ["custom.fridgeMode"]}}
              },
              "freezer": {
                "temperatureMeasurement": {"temperature": {"value": -18, "unit": "C"}},
                "thermostatCoolingSetpoint": {"coolingSetpointRange": {"value": {"minimum": -23, "maximum": -15, "step": 1}, "unit": "C"},
                                              "coolingSetpoint": {"value": -18, "unit": "C"}},
                "contactSensor": {"contact": {"value": "closed"}},
                "custom.disabledCapabilities": {"disabledCapabilities": {"value": []}}
              }
            }}
            """;

    @Test
    public void theRealCompartmentsAreReadAndTheDisabledOnesSkipped() {
        Snapshot s = SmartThingsCloudRefrigeratorHandler.parse(FAMILY_HUB);
        assertEquals(new StringType("cooler, freezer"), s.states.get("main#components"));
        assertEquals(new QuantityType<>(3, SIUnits.CELSIUS), s.states.get("cooler#temperature"));
        assertEquals(new QuantityType<>(3, SIUnits.CELSIUS), s.states.get("cooler#setpoint"));
        assertEquals(new QuantityType<>(1, SIUnits.CELSIUS), s.states.get("cooler#setpointMin"));
        assertEquals(new QuantityType<>(7, SIUnits.CELSIUS), s.states.get("cooler#setpointMax"));
        assertEquals(OpenClosedType.OPEN, s.states.get("cooler#door"));
        assertEquals(new QuantityType<>(-18, SIUnits.CELSIUS), s.states.get("freezer#temperature"));
        assertEquals(new QuantityType<>(-23, SIUnits.CELSIUS), s.states.get("freezer#setpointMin"));
        assertEquals(OpenClosedType.CLOSED, s.states.get("freezer#door"));
        // onedoor carries a value in the template, but main says the model does not have it
        assertFalse(s.states.keySet().stream().anyMatch(k -> k.startsWith("onedoor#")), "onedoor is disabled");
        assertFalse(s.states.keySet().stream().anyMatch(k -> k.startsWith("cvroom#")), "cvroom is disabled");
    }

    @Test
    public void mainIsReadButItsDisabledTemperatureIsNot() {
        Snapshot s = SmartThingsCloudRefrigeratorHandler.parse(FAMILY_HUB);
        assertEquals(OpenClosedType.CLOSED, s.states.get("main#door"));
        assertEquals(OnOffType.OFF, s.states.get("main#powerCool"));
        assertEquals(OnOffType.ON, s.states.get("main#powerFreeze"), "samsungce.powerFreeze wins over refrigeration");
        assertEquals(OnOffType.OFF, s.states.get("main#vacationMode"));
        assertEquals(new DecimalType(1031), s.states.get("main#watt"));
        assertEquals(new DecimalType(1709.767), s.states.get("main#kwh"));
        assertEquals(new DecimalType(52), s.states.get("main#waterFilterUsage"));
        assertEquals(new StringType("normal"), s.states.get("main#waterFilterStatus"));
        assertEquals(OnOffType.OFF, s.states.get("main#updateAvailable"));
        assertNull(s.states.get("main#temperature"), "main has no temperature channel");
    }

    @Test
    public void aFahrenheitFridgeKeepsItsUnit() {
        String json = FAMILY_HUB.replace("\"unit\": \"C\"", "\"unit\": \"F\"");
        Snapshot s = SmartThingsCloudRefrigeratorHandler.parse(json);
        assertEquals(new QuantityType<>(3, ImperialUnits.FAHRENHEIT), s.states.get("cooler#temperature"));
        assertEquals(ImperialUnits.FAHRENHEIT, s.units.get("cooler"));
    }

    @Test
    public void theRefrigerationCapabilityIsTheFallbackForPowerCool() {
        String json = FAMILY_HUB.replace("\"samsungce.powerCool\": {\"activated\": {\"value\": false}},", "")
                .replace("\"rapidCooling\": {\"value\": \"off\"}", "\"rapidCooling\": {\"value\": \"on\"}");
        Snapshot s = SmartThingsCloudRefrigeratorHandler.parse(json);
        assertEquals(OnOffType.ON, s.states.get("main#powerCool"));
    }

    @Test
    public void aStatusWithoutComponentsIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> SmartThingsCloudRefrigeratorHandler.parse("{\"switch\": {}}"));
    }

    @Test
    public void aSetpointCommandTargetsItsCompartmentInTheFridgesUnit() {
        String body = SmartThingsCloudRefrigeratorHandler.commandBody("freezer", "setpoint",
                new QuantityType<>(-20, SIUnits.CELSIUS), SIUnits.CELSIUS);
        assertEquals(
                "{\"commands\":[{\"component\":\"freezer\",\"capability\":\"thermostatCoolingSetpoint\",\"command\":\"setCoolingSetpoint\",\"arguments\":[-20]}]}",
                body);
        // a Fahrenheit command to a Celsius fridge is converted, and a plain number is passed as it is
        String converted = SmartThingsCloudRefrigeratorHandler.commandBody("cooler", "setpoint",
                new QuantityType<>(41, ImperialUnits.FAHRENHEIT), SIUnits.CELSIUS);
        assertNotNull(converted);
        assertTrue(converted.contains("\"arguments\":[5]"), converted);
        String plain = SmartThingsCloudRefrigeratorHandler.commandBody("cooler", "setpoint", new DecimalType(4), null);
        assertNotNull(plain);
        assertTrue(plain.contains("\"component\":\"cooler\"") && plain.contains("\"arguments\":[4]"), plain);
    }

    @Test
    public void mainSwitchesAndNothingElseAreWritable() {
        assertEquals(
                "{\"commands\":[{\"component\":\"main\",\"capability\":\"refrigeration\",\"command\":\"setRapidCooling\",\"arguments\":[\"on\"]}]}",
                SmartThingsCloudRefrigeratorHandler.commandBody("main", "powerCool", OnOffType.ON, null));
        assertEquals(
                "{\"commands\":[{\"component\":\"main\",\"capability\":\"refrigeration\",\"command\":\"setRapidFreezing\",\"arguments\":[\"off\"]}]}",
                SmartThingsCloudRefrigeratorHandler.commandBody("main", "powerFreeze", OnOffType.OFF, null));
        assertEquals(
                "{\"commands\":[{\"component\":\"main\",\"capability\":\"samsungce.fridgeVacationMode\",\"command\":\"setVacationMode\",\"arguments\":[\"on\"]}]}",
                SmartThingsCloudRefrigeratorHandler.commandBody("main", "vacationMode", OnOffType.ON, null));
        assertNull(SmartThingsCloudRefrigeratorHandler.commandBody("main", "door", OnOffType.ON, null));
        assertNull(SmartThingsCloudRefrigeratorHandler.commandBody("main", "watt", new DecimalType(1), null));
        assertNull(SmartThingsCloudRefrigeratorHandler.commandBody("cooler", "temperature", new DecimalType(1), null));
        assertNull(SmartThingsCloudRefrigeratorHandler.commandBody(null, "setpoint", new DecimalType(1), null));
    }
}
