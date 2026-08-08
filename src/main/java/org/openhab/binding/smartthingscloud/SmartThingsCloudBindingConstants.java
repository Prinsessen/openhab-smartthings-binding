/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.smartthingscloud;

import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * Constants for the SmartThings Cloud binding.
 *
 * @author openHAB Samsung Cloud Binding - Initial contribution
 */
@NonNullByDefault
public class SmartThingsCloudBindingConstants {

    public static final String BINDING_ID = "smartthingscloud";

    // ── Thing Types ───────────────────────────────────────────────────────────
    public static final ThingTypeUID THING_TYPE_ACCOUNT = new ThingTypeUID(BINDING_ID, "account");
    public static final ThingTypeUID THING_TYPE_WASHER = new ThingTypeUID(BINDING_ID, "washer");
    public static final ThingTypeUID THING_TYPE_TELEVISION = new ThingTypeUID(BINDING_ID, "television");
    public static final ThingTypeUID THING_TYPE_PRESENCE = new ThingTypeUID(BINDING_ID, "presence");
    public static final ThingTypeUID THING_TYPE_LIGHT_SENSOR = new ThingTypeUID(BINDING_ID, "lightSensor");
    public static final ThingTypeUID THING_TYPE_SCENE = new ThingTypeUID(BINDING_ID, "scene");
    public static final ThingTypeUID THING_TYPE_AIR_CONDITIONER = new ThingTypeUID(BINDING_ID, "airConditioner");

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Set.of(THING_TYPE_ACCOUNT, THING_TYPE_WASHER,
            THING_TYPE_TELEVISION, THING_TYPE_PRESENCE, THING_TYPE_LIGHT_SENSOR, THING_TYPE_SCENE,
            THING_TYPE_AIR_CONDITIONER);

    // ── SmartThings API ───────────────────────────────────────────────────────
    /** Default public client_id from the open-source SmartThings CLI. */
    public static final String DEFAULT_CLIENT_ID = "b3c72013-eddd-4f65-a33b-0d90cf271e24";
    public static final String DEFAULT_REDIRECT_URI = "";

    public static final String ST_AUTH_URL = "https://oauthin-regional.api.smartthings.com/oauth/authorize";
    public static final String ST_TOKEN_URL = "https://auth-global.api.smartthings.com/oauth/token";
    public static final String ST_API_BASE = "https://api.smartthings.com/v1";
    public static final String OAUTH_SCOPES = "controller:stCli";

    // ── Washer Channels ───────────────────────────────────────────────────────
    public static final String CHANNEL_MACHINE_STATE = "machineState";
    public static final String CHANNEL_JOB_STATE = "jobState";
    public static final String CHANNEL_COMPLETION_TIME = "completionTime";
    public static final String CHANNEL_REMOTE_ENABLED = "remoteEnabled";
    public static final String CHANNEL_POWER = "power";
    public static final String CHANNEL_WATT = "watt";
    public static final String CHANNEL_KWH = "kwh";
    public static final String CHANNEL_MODE = "mode";
    public static final String CHANNEL_RINSE_MODE = "rinseMode";
    public static final String CHANNEL_SPIN_SPEED = "spinSpeed";
    public static final String CHANNEL_TEMPERATURE = "temperature";
    public static final String CHANNEL_RUNNING = "running";
    public static final String CHANNEL_REMAINING = "remaining";
    public static final String CHANNEL_BUBBLE_SOAK = "bubbleSoak";
    public static final String CHANNEL_VOLUME = "volume";
    public static final String CHANNEL_EXTRA_CARE = "extraCare";
    public static final String CHANNEL_EXTRA_CARE_LOCATION = "extraCareLocation";
    public static final String CHANNEL_WATER_LITERS = "waterLiters";
    public static final String CHANNEL_DETERGENT_REMAINING = "detergentRemaining";
    public static final String CHANNEL_SOFTENER_REMAINING = "softenerRemaining";
    public static final String CHANNEL_DELAY_END = "delayEnd";
    public static final String CHANNEL_SUPPORTED_COURSES = "supportedCourses";
    public static final String CHANNEL_KIDS_LOCK = "kidsLock";
    public static final String CHANNEL_CURRENT_CYCLE = "currentCycle";
    public static final String CHANNEL_OPERATING_STATE = "operatingState";
    public static final String CHANNEL_PROGRESS = "progress";
    public static final String CHANNEL_REMAINING_TIME_STR = "remainingTimeStr";
    public static final String CHANNEL_OPERATION_TIME = "operationTime";
    public static final String CHANNEL_UPDATE_AVAILABLE = "updateAvailable";

    // ── Television Channels ────────────────────────────────────────────────────
    public static final String CHANNEL_TV_VOLUME = "tvVolume";
    public static final String CHANNEL_TV_MUTE = "tvMute";
    public static final String CHANNEL_TV_INPUT = "inputSource";
    public static final String CHANNEL_TV_SUPPORTED_INPUTS = "supportedInputSources";
    public static final String CHANNEL_TV_CHANNEL = "tvChannel";
    public static final String CHANNEL_TV_CHANNEL_NAME = "tvChannelName";
    public static final String CHANNEL_TV_CHANNEL_UP = "channelUp";
    public static final String CHANNEL_TV_CHANNEL_DOWN = "channelDown";
    public static final String CHANNEL_TV_PICTURE_MODE = "pictureMode";
    public static final String CHANNEL_TV_SUPPORTED_PICTURE_MODES = "supportedPictureModes";
    public static final String CHANNEL_TV_SOUND_MODE = "soundMode";
    public static final String CHANNEL_TV_SUPPORTED_SOUND_MODES = "supportedSoundModes";
    public static final String CHANNEL_TV_PLAYBACK = "playbackStatus";
    public static final String CHANNEL_TV_FIRMWARE = "firmwareVersion";
    public static final String CHANNEL_TV_ONLINE = "onlineStatus";

    // ── Presence Channels ─────────────────────────────────────────────────────
    public static final String CHANNEL_PRESENCE = "presence";

    // ── Light Sensor Channels ─────────────────────────────────────────────────
    public static final String CHANNEL_ILLUMINANCE = "illuminance";
    public static final String CHANNEL_BRIGHTNESS_LEVEL = "brightnessLevel";

    // ── Scene Channels ────────────────────────────────────────────────────────
    /** Switch channel — send ON to execute the scene; auto-resets to OFF. */
    public static final String CHANNEL_SCENE_TRIGGER = "trigger";

    // ── Air Conditioner Channels ──────────────────────────────────────────────
    public static final String CHANNEL_AC_POWER = "power";
    public static final String CHANNEL_AC_MODE = "mode";
    public static final String CHANNEL_AC_TARGET_TEMPERATURE = "targetTemperature";
    public static final String CHANNEL_AC_CURRENT_TEMPERATURE = "currentTemperature";
    public static final String CHANNEL_AC_FAN_MODE = "fanMode";
    public static final String CHANNEL_AC_FAN_OSCILLATION_MODE = "fanOscillationMode";
    public static final String CHANNEL_AC_OPTIONAL_MODE = "optionalMode";

    // ── Storage keys ─────────────────────────────────────────────────────────
    public static final String STORAGE_ACCESS_TOKEN = "accessToken";
    public static final String STORAGE_REFRESH_TOKEN = "refreshToken";
    /** Unix epoch seconds when the access token expires. Persisted so we avoid needless refreshes on restart. */
    public static final String STORAGE_EXPIRES_AT = "expiresAt";
}
