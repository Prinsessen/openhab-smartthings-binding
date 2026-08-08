/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.smartthingscloud.internal.handler;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Configuration for a SmartThings Cloud air conditioner thing.
 *
 * @author openHAB Samsung Cloud Binding - Initial contribution
 */
@NonNullByDefault
public class SmartThingsCloudAirConditionerConfiguration {

    /**
     * SmartThings device ID (UUID).
     * Find it at https://developer.smartthings.com/docs/api/public or via
     * GET https://api.smartthings.com/v1/devices with your access token.
     */
    public String deviceId = "";

    /**
     * Polling interval in seconds for fetching air conditioner status from SmartThings API.
     */
    public int pollingIntervalSeconds = 30;
}
