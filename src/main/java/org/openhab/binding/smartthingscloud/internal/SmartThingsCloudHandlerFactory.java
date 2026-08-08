/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.smartthingscloud.internal;

import static org.openhab.binding.smartthingscloud.SmartThingsCloudBindingConstants.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.smartthingscloud.internal.handler.SmartThingsCloudAccountHandler;
import org.openhab.binding.smartthingscloud.internal.handler.SmartThingsCloudAirConditionerHandler;
import org.openhab.binding.smartthingscloud.internal.handler.SmartThingsCloudLightSensorHandler;
import org.openhab.binding.smartthingscloud.internal.handler.SmartThingsCloudPresenceHandler;
import org.openhab.binding.smartthingscloud.internal.handler.SmartThingsCloudSceneHandler;
import org.openhab.binding.smartthingscloud.internal.handler.SmartThingsCloudTelevisionHandler;
import org.openhab.binding.smartthingscloud.internal.handler.SmartThingsCloudWasherHandler;
import org.openhab.binding.smartthingscloud.internal.servlet.SmartThingsCloudServlet;
import org.openhab.core.storage.StorageService;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Creates handler instances for SmartThings Cloud things.
 *
 * @author openHAB Samsung Cloud Binding - Initial contribution
 */
@NonNullByDefault
@Component(service = ThingHandlerFactory.class, immediate = true)
public class SmartThingsCloudHandlerFactory extends BaseThingHandlerFactory {

    private final SmartThingsCloudServlet servlet;
    private final StorageService storageService;

    @Activate
    public SmartThingsCloudHandlerFactory(@Reference SmartThingsCloudServlet servlet,
            @Reference StorageService storageService) {
        this.servlet = servlet;
        this.storageService = storageService;
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID uid = thing.getThingTypeUID();

        if (THING_TYPE_ACCOUNT.equals(uid)) {
            return new SmartThingsCloudAccountHandler((Bridge) thing, servlet, storageService);
        }
        if (THING_TYPE_WASHER.equals(uid)) {
            return new SmartThingsCloudWasherHandler(thing);
        }
        if (THING_TYPE_TELEVISION.equals(uid)) {
            return new SmartThingsCloudTelevisionHandler(thing);
        }
        if (THING_TYPE_PRESENCE.equals(uid)) {
            return new SmartThingsCloudPresenceHandler(thing);
        }
        if (THING_TYPE_LIGHT_SENSOR.equals(uid)) {
            return new SmartThingsCloudLightSensorHandler(thing);
        }
        if (THING_TYPE_SCENE.equals(uid)) {
            return new SmartThingsCloudSceneHandler(thing);
        }
        if (THING_TYPE_AIR_CONDITIONER.equals(uid)) {
            return new SmartThingsCloudAirConditionerHandler(thing);
        }
        return null;
    }
}
