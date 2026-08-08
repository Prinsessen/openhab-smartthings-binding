# SmartThings Cloud Binding

OpenHAB binding for Samsung SmartThings devices via the **SmartThings Cloud API** —
no SmartThings Hub, no developer registration, and no Personal Access Token required.

Authorization uses the same **OAuth2 PKCE flow** as the official SmartThings CLI.
Any Samsung account holder can authorize using the built-in client ID without registering a developer app.

## Supported Things

| Thing type    | Description |
|---------------|-------------|
| `washer`         | Samsung washing machine |
| `television`     | Samsung Smart TV |
| `presence`       | Samsung mobile phone / SmartThings presence sensor |
| `lightSensor`    | Samsung SmartThings illuminance / brightness sensor |
| `scene`          | Samsung SmartThings scene — execute with a switch channel |
| `airConditioner` | Samsung SmartThings airconditioner |

> Additional device types (dryer, dishwasher, etc.) can be added by contributing channel mappings.

---

## Authorization

The binding uses **OAuth2 PKCE** (Proof Key for Code Exchange).
No client secret or developer account is needed.

### Steps

1. Add the bridge thing (via UI or `.things` file).
2. Open the authorization page in your browser:
   ```
   http://YOUR_OPENHAB_IP:8080/smartthingscloud
   ```
3. Click **Authorize with Samsung** and log in with your Samsung account.
4. After approval, the bridge goes **ONLINE** automatically.

> **Important:** The OAuth callback goes to `http://localhost:61973/finish`.
> The binding temporarily opens **TCP port 61973** on the openHAB host — identical to what the SmartThings CLI does.
> Your browser must run on the **same machine** as openHAB, or port 61973 must be forwarded from your local machine to the openHAB host.
> VS Code Remote / SSH tunneling setups work out of the box (VS Code auto-tunnels the port).

### Token Persistence

Tokens are stored in openHAB's `StorageService` (JSON database) and survive restarts.
The access token is automatically refreshed before it expires.
Re-authorization is only needed if the refresh token is revoked (e.g., Samsung account password change).

---

## SmartThings CLI

The [SmartThings CLI](https://github.com/SmartThingsCommunity/smartthings-cli) is an official Samsung command-line tool that uses the same OAuth2 PKCE flow as this binding.
It is the easiest way to look up device IDs and inspect capability state without writing any code.

### Installation

**Requirements:** Node.js 18+ and npm.

> **openHAB on Debian/Ubuntu/Raspberry Pi OS:**
> The default `apt install nodejs` package is usually too old (v12/v16).
> Install a current version via the [NodeSource](https://github.com/nodesource/distributions) repository:
> ```bash
> curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
> sudo apt-get install -y nodejs
> node --version   # should print v20.x.x
> ```

Once Node.js 18+ is available, install the CLI globally:

```bash
npm install -g @smartthings/cli
```

Verify:

```bash
smartthings --version
# e.g. 2.1.2
```

### First-time login

The CLI authenticates on first use. Run any command (e.g. `smartthings devices`) and it will:

1. Print a URL — open it in your browser
2. Log in with your Samsung account and authorize
3. Be redirected to `http://localhost:61973/finish` — the CLI briefly opens this port to catch the callback

> **Headless / remote servers (e.g. openHAB on a Raspberry Pi):**
> The callback lands on `localhost:61973` on the **server**, not your desktop browser.
> Use SSH port forwarding so Chrome/Firefox on your laptop can complete the flow:
> ```bash
> ssh -L 61973:localhost:61973 user@your-openhab-host
> ```
> Then open the authorization URL in your local browser.
>
> **VS Code Remote SSH** handles this automatically — VS Code auto-forwards port 61973.

### Credentials storage

After successful login, tokens are stored in `~/.config/@smartthings/cli/credentials.json`.
They are reused automatically on subsequent commands — no re-login needed.

---

## Finding Your Device ID

### Option 1: SmartThings CLI (easiest)

```bash
smartthings devices
```

Lists all your devices with name and `deviceId` — no token needed (triggers login on first use, see above).

### Option 2: SmartThings REST API

If you have an access token (e.g. from the SmartThings CLI via `smartthings tokens:create`):

```bash
curl -H "Authorization: Bearer YOUR_ACCESS_TOKEN" \
     https://api.smartthings.com/v1/devices | python3 -m json.tool | grep -E "name|deviceId"
```

### Option 3: SmartThings App (older app versions only)

> ⚠️ Samsung removed the device ID from newer app versions — this may not work.

1. Open the SmartThings app → tap your device.
2. Tap ⋮ (three-dot menu) → **Information**.
3. The device ID is shown as a UUID at the bottom.

---

## Finding Your Scene ID

```bash
smartthings scenes
```

Lists all scenes with name and `sceneId`:

```
 #  Scene Name  Scene Id
 1  Movie Time  xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
```

To also find the `locationId` (needed if your account has multiple locations):

```bash
smartthings locations
```

---

## Thing Configuration

### `.things` file

```java
Bridge smartthingscloud:account:myaccount "Samsung SmartThings" {
    Thing washer mywasher "My Washing Machine" [
        deviceId = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
        pollingIntervalSeconds = 30
    ]
    Thing television myTV "Living Room TV" [
        deviceId = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
        pollingIntervalSeconds = 30
    ]
    Thing presence myphone "My Phone" [
        deviceId = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
        pollingIntervalSeconds = 30
    ]
    Thing lightSensor mysensor "Bedroom Light Sensor" [
        deviceId = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
        pollingIntervalSeconds = 60
    ]
   Thing airConditioner myairconditioner "Airconditioner" [
        deviceId = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
        pollingIntervalSeconds = 60
    ]
}
```

### UI Configuration

Add a new thing of type **SmartThings Cloud Account** (bridge), then add child things of the desired type.

---

## Bridge Configuration Parameters

| Parameter      | Type | Required | Default                                  | Description |
|----------------|------|----------|------------------------------------------|-------------|
| `clientId`     | text | No       | `d18cf96e-c626-4433-bf51-ddbb10c5d1ed`   | SmartThings OAuth2 client ID. The default is the open-source SmartThings CLI client ID — no developer app needed. |
| `accessToken`  | text | No       | *(empty)*                                | Pre-configured access token. Leave empty and use the web authorization page instead. |
| `refreshToken` | text | No       | *(empty)*                                | Pre-configured refresh token. Leave empty and use the web authorization page instead. |

---

## Washer Configuration Parameters

| Parameter                | Type    | Required | Default | Description |
|--------------------------|---------|----------|---------|-------------|
| `deviceId`               | text    | **Yes**  | —       | SmartThings device UUID. See [Finding Your Device ID](#finding-your-device-id). |
| `pollingIntervalSeconds` | integer | No       | `30`    | Poll interval in seconds. Min: 10, Max: 300. |

---

## Television Configuration Parameters

| Parameter                | Type    | Required | Default | Description |
|--------------------------|---------|----------|---------|-------------|
| `deviceId`               | text    | **Yes**  | —       | SmartThings device UUID. |
| `pollingIntervalSeconds` | integer | No       | `30`    | Poll interval in seconds. Min: 10, Max: 300. |

---

## Presence Configuration Parameters

| Parameter                | Type    | Required | Default | Description |
|--------------------------|---------|----------|---------|-------------|
| `deviceId`               | text    | **Yes**  | —       | SmartThings device UUID. (Phone or SmartThings presence sensor) |
| `pollingIntervalSeconds` | integer | No       | `30`    | Poll interval in seconds. Min: 10, Max: 300. |

---

## Light Sensor Configuration Parameters

| Parameter                | Type    | Required | Default | Description |
|--------------------------|---------|----------|---------|-------------|
| `deviceId`               | text    | **Yes**  | —       | SmartThings device UUID. |
| `pollingIntervalSeconds` | integer | No       | `60`    | Poll interval in seconds. Min: 10, Max: 300. |

---

## Scene Configuration Parameters

| Parameter    | Type | Required | Default  | Description |
|--------------|------|----------|----------|-------------|
| `sceneId`    | text | **Yes**  | —        | SmartThings scene UUID. See [Finding Your Scene ID](#finding-your-scene-id). |
| `locationId` | text | No       | *(empty)*| SmartThings location UUID. Required only if your account has multiple locations. |

---

## Airconditioner Configuration Parameters

| Parameter    | Type | Required | Default  | Description |
|--------------|------|----------|----------|-------------|
| `deviceId`   | text | **Yes**  | —        | SmartThings device UUID.  |
| `locationId` | text | No       | `30`     | Poll interval in seconds. |

---

## Washer Channels

### State channels (read-only)

| Channel ID       | Type          | Source capability (read)                                      | Description |
|------------------|---------------|---------------------------------------------------------------|-------------|
| `machineState`   | String        | `samsungce.washerOperatingState.washerJobState`               | Machine state: `run`, `pause`, `stop` |
| `jobState`       | String        | `samsungce.washerOperatingState.washerJobState`               | Cycle phase: `washing`, `rinsing`, `spin`, `finish`, `none`, `airWash`, `cooling`, `preWash`, `wrinklePrevent` |
| `completionTime` | DateTime      | `samsungce.washerOperatingState.completionTime`               | Expected finish time |
| `running`        | Switch        | derived from `jobState`                                       | `ON` when actively running (jobState not `none`/`finish`) |
| `remaining`      | Number        | `samsungce.washerOperatingState.remainingTime`                | Minutes remaining |
| `remoteEnabled`  | Switch        | `samsungce.remoteControlStatus.remoteControlEnabled`          | `ON` when remote control is allowed |
| `mode`           | String        | `samsungce.washerWashingCourse.washingCourse`                 | Active wash program code |
| `rinseMode`      | String        | `samsungce.washerRinse.rinseCount`                            | Rinse count/mode |
| `spinSpeed`      | String        | `samsungce.washerSpinLevel.washingSpinLevel`                  | Spin speed (rpm) |
| `temperature`    | String        | `samsungce.washerWaterTemperature.washerWaterTemperature`     | Wash temperature (°C) |
| `watt`           | Number:Power  | `powerConsumptionReport.power`                                | Current power consumption (W) |
| `kwh`            | Number:Energy | `powerConsumptionReport.energy`                               | Accumulated energy usage (kWh) |
| `waterLiters`    | Number        | `samsungce.waterConsumptionReport.cumulativeAmount`           | Cumulative water consumed (liters, lifetime total) |
| `operatingState` | String        | `samsungce.washerOperatingState.operatingState`               | Detailed operating state: `ready`, `running`, `paused`, `finished` |
| `currentCycle`   | String        | `samsungce.washerCycle.washerCycle`                           | Active wash program code (e.g. `B0`, `1B`, `65`) — use MAP transform for display |
| `kidsLock`       | Switch        | `samsungce.kidsLock.lockState`                                | `ON` when child lock is active (locked) |
| `progress`            | Number        | `samsungce.washerOperatingState.progress`          | Wash cycle progress (0–100 %) — only populated while running |
| `detergentRemaining`  | Number        | `samsungce.detergentState.remainingAmount`          | Detergent level remaining (%) |
| `softenerRemaining`   | Number        | `samsungce.softenerState.remainingAmount`           | Softener level remaining (%) |
| `delayEnd`            | Number        | `samsungce.washerDelayEnd.remainingTime`            | Delay-end countdown remaining (minutes) |
| `supportedCourses`    | String        | `custom.supportedOptions.supportedCourses`         | Comma-separated list of supported wash programs |
| `remainingTimeStr`    | String        | `samsungce.washerOperatingState.remainingTimeStr`  | Remaining time formatted as `HH:MM` |
| `operationTime`       | Number        | `samsungce.washerOperatingState.operationTime`     | Total selected cycle duration (minutes) |
| `updateAvailable`     | Switch        | `samsungce.softwareUpdate.newVersionAvailable`     | `ON` when a firmware update is available |

### Control channels (read/write)

| Channel ID          | Type   | Write capability                                   | Description |
|---------------------|--------|----------------------------------------------------|-------------|
| `machineState`      | String | `washerOperatingState` → `setMachineState`         | Send `run`, `pause`, or `stop` |
| `power`             | Switch | `switch` → `on` / `off`                            | Remote power on/off |
| `bubbleSoak`        | Switch | `samsungce.washerBubbleSoak` → `on` / `off`        | Enable/disable bubble soak pre-wash |
| `volume`            | Number | `samsungce.audioVolumeLevel` → `setVolumeLevel`    | Buzzer volume: `0`=off, `1`=low, `2`=medium, `3`=high |
| `extraCare`         | String | `samsungce.clothingExtraCare` → `setOperationMode` | Extra care mode string |
| `extraCareLocation` | String | `samsungce.clothingExtraCare` → `setUserLocation`  | Extra care location string |

> **Note on capability namespaces:** Samsung reports state through `samsungce.*` capabilities (read-only).
> Write commands must target the standard `washerOperatingState` capability — not `samsungce.washerOperatingState`.
> This is a quirk of the Samsung SmartThings API.

---

## Television Channels

### State channels (read-only)

| Channel ID      | Type   | Source capability                        | Description |
|-----------------|--------|------------------------------------------|-------------|
| `tvVolume`               | Number        | `audioVolume.volume`                                                    | Current volume level (0–100) |
| `tvMute`                 | Switch        | `audioMute.mute`                                                        | `ON` when muted |
| `inputSource`            | String        | `samsungvd.mediaInputSource.inputSource`                                | Active input source (e.g. `HDMI1`, `digitalTv`) |
| `tvChannel`              | String        | `tvChannel.tvChannel`                                                   | Active channel number |
| `tvChannelName`          | String        | `tvChannel.tvChannelName`                                               | Active channel name |
| `pictureMode`            | String        | `custom.picturemode.pictureMode`                                        | Picture mode (e.g. `Standard`, `Movie`, `Game`) |
| `soundMode`              | String        | `custom.soundmode.soundMode`                                            | Sound mode (e.g. `Standard`, `Movie`) |
| `playbackStatus`         | String        | `mediaPlayback.supportedPlaybackCommands`                               | Current playback state |
| `watt`                   | Number:Power  | `powerConsumptionReport.powerConsumption.power`                         | Current power draw (W) |
| `kwh`                    | Number:Energy | `powerConsumptionReport.powerConsumption.energy`                        | Accumulated energy (kWh, lifetime) |
| `firmwareVersion`        | String        | `samsungvd.firmwareVersion.firmwareVersion`                             | TV firmware version |
| `onlineStatus`           | String        | `samsungvd.thingStatus.status`                                          | Online status (`Idle`, `Active`) |
| `supportedInputSources`  | String        | `samsungvd.mediaInputSource.supportedInputSourcesMap`                   | Comma-separated list of available input sources |
| `supportedPictureModes`  | String        | `custom.picturemode.supportedPictureModes`                              | Comma-separated list of available picture modes |
| `supportedSoundModes`    | String        | `custom.soundmode.supportedSoundModes`                                  | Comma-separated list of available sound modes |

### Control channels (read/write)

| Channel ID      | Type   | Write capability                         | Description |
|-----------------|--------|------------------------------------------|-------------|
| `power`         | Switch | `switch` → `on` / `off`                  | TV power on/off |
| `tvVolume`      | Number | `audioVolume` → `setVolume`              | Set volume (0–100). Decimal values (e.g. `55.0`) are automatically rounded. |
| `tvMute`        | Switch | `audioMute` → `mute` / `unmute`          | Mute/unmute |
| `inputSource`   | String | `mediaInputSource` → `setInputSource`    | Set input source |
| `tvChannel`     | String | `tvChannel` → `setTvChannel`             | Set channel number |
| `pictureMode`   | String | `custom.picturemode` → `setPictureMode`  | Set picture mode |
| `soundMode`     | String | `custom.soundmode` → `setSoundMode`      | Set sound mode |
| `channelUp`     | Switch | `tvChannel` → `channelUp`                | Zap one channel up (momentary — resets to OFF automatically) |
| `channelDown`   | Switch | `tvChannel` → `channelDown`              | Zap one channel down (momentary — resets to OFF automatically) |

> **Note on input source:** The binding prefers `samsungvd.mediaInputSource.inputSource` for reads as it is more up-to-date than the standard `mediaInputSource` attribute, which can be stale after channel or source switches.

> **Note on volume:** The SmartThings API returns volume as a decimal string (e.g. `55.0`). The binding parses this correctly via `Double.parseDouble` + `Math.round`. Slider items work out of the box.

---

## Presence Channels

### State channels (read-only)

| Channel ID | Type   | Source capability         | Description |
|------------|--------|---------------------------|-------------|
| `presence` | Switch | `presenceSensor.presence` | `ON` when the device is present (home), `OFF` when away |

> Designed for Samsung mobile phones registered in SmartThings and for dedicated SmartThings presence sensors (e.g. SmartThings Arrival Sensor).
> Combine with a grace-period timer rule (5 minutes recommended) to avoid false-away triggers on brief signal loss.

---

## Light Sensor Channels

### State channels (read-only)

| Channel ID        | Type   | Source capability                        | Description |
|-------------------|--------|------------------------------------------|-------------|
| `illuminance`     | Number | `illuminanceMeasurement.illuminance`     | Illuminance in lux |
| `brightnessLevel` | Number | `illuminanceMeasurement.brightnessLevel` | Relative brightness intensity (0–100) |

> Longer polling intervals (60–120 s) are appropriate for light sensors as illuminance changes gradually.
> The `brightnessLevel` value is a Samsung-specific relative index and does not correspond to standard photometric lux categories.

---

## Scene Channels

| Channel ID | Type   | Description |
|------------|--------|-------------|
| `trigger`  | Switch | Send `ON` to execute the scene. Automatically resets to `OFF` after triggering. |

> **Note:** Because the channel auto-resets, you can link it to a `Switch` item and fire the scene any number of times — each `ON` command is a fresh execution.

---

## Airconditioner Channels

### State channels (read-only)

| Channel ID      | Type   | Source capability                        | Description |
|-----------------|--------|------------------------------------------|-------------|
| `power`                  | Switch                | `switch` → `on` / `off`             | Status of the air conditioner on or off |
| `mode`                   | Switch                | `airConditionerMode`                | Operating mode: auto, cool, dry, fan, heat |
| `targetTemperature`      | Number:Temperature    | `thermostatCoolingSetpoint`         | Set cooling setpoint temperature |
| `currentTemperature`     | Number:Temperature    | `temperatureMeasurement`            | Measured room temperature |
| `fanMode`                | String                | `airConditionerFanMode`             | Fan speed: auto, low, medium, high, turbo |
| `fanOscillationMode`     | String                | `fanOscillationMode`                | Fan swing/oscillation direction: fixed, vertical, horizontal, all, fixedCenter, fixedLeft, fixedRight |
| `optionalMode`           | String                | `custom.airConditionerOptionalMode` | Samsung-specific optional mode, e.g. windFree, sleep, speed |

### Control channels (read/write)

| Channel ID      | Type   | Write capability                        | Description |
|-----------------|--------|------------------------------------------|-------------|
| `power`                  | Switch                | `switch` → `on` / `off`             | Turn the air conditioner on or off |
| `mode`                   | Switch                | `airConditionerMode`                | Set operating mode: auto, cool, dry, fan, heat |
| `targetTemperature`      | Number:Temperature    | `thermostatCoolingSetpoint`         | Set cooling setpoint temperature |
| `fanMode`                | String                | `airConditionerFanMode`             | Set fan speed: auto, low, medium, high, turbo |
| `fanOscillationMode`     | String                | `fanOscillationMode`                | Set fan swing/oscillation direction: fixed, vertical, horizontal, all, fixedCenter, fixedLeft, fixedRight |
| `optionalMode`           | String                | `custom.airConditionerOptionalMode` | Set Samsung-specific optional mode, e.g. windFree, sleep, speed |


---

## Full Example

### `smartthings.things`

```java
Bridge smartthingscloud:account:myaccount "Samsung SmartThings" {
    Thing washer mywasher "My Washing Machine" [
        deviceId = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
        pollingIntervalSeconds = 30
    ]
    Thing television myTV "Living Room TV" [
        deviceId = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
        pollingIntervalSeconds = 30
    ]
    Thing presence myphone "My Phone" [
        deviceId = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
        pollingIntervalSeconds = 30
    ]
    Thing lightSensor mysensor "Bedroom Light Sensor" [
        deviceId = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
        pollingIntervalSeconds = 60
    ]
    Thing airConditioner myairconditioner "Airconditioner" [
        deviceId = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
        pollingIntervalSeconds = 30
    ]
}
```

### `smartthings.items`

```java
// ── Washer ─────────────────────────────────────────────────────────────────
Group           gWasher                  "Washing Machine"
String          Washer_MachineState      "State [%s]"                  (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:machineState" }
String          Washer_JobState          "Phase [%s]"                  (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:jobState" }
DateTime        Washer_CompletionTime    "Done at [%1$tH:%1$tM]"       (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:completionTime" }
Switch          Washer_Running           "Running"                     (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:running" }
Number          Washer_Remaining         "Remaining [%.0f min]"        (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:remaining" }
Switch          Washer_RemoteEnabled     "Remote Enabled"              (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:remoteEnabled" }
String          Washer_Mode              "Program [%s]"                (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:mode" }
String          Washer_RinseMode         "Rinse [%s]"                  (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:rinseMode" }
String          Washer_SpinSpeed         "Spin [%s rpm]"               (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:spinSpeed" }
String          Washer_Temperature       "Temperature [%s°C]"          (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:temperature" }
Number:Power    Washer_Watt              "Power [%.0f W]"              (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:watt" }
Number:Energy   Washer_kWh               "Energy [%.2f kWh]"           (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:kwh" }
Number          Washer_WaterLiters       "Water [%.1f L]"              (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:waterLiters" }
String          Washer_OperatingState    "Operating State [%s]"        (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:operatingState" }
String          Washer_CurrentCycle      "Active Program [%s]"         (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:currentCycle" }
Switch          Washer_KidsLock          "Child Lock"                  (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:kidsLock" }
Number          Washer_Progress          "Progress [%.0f %%]"          (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:progress" }
Number          Washer_DetergentRemaining "Detergent [%.0f %%]"         (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:detergentRemaining" }
Number          Washer_SoftenerRemaining  "Softener [%.0f %%]"          (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:softenerRemaining" }
Number          Washer_DelayEnd           "Delay End [%d min]"          (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:delayEnd" }
String          Washer_SupportedCourses   "Programs [%s]"               (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:supportedCourses" }
String          Washer_RemainingTimeStr  "Remaining [%s]"              (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:remainingTimeStr" }
Number          Washer_OperationTime     "Cycle Time [%d min]"         (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:operationTime" }
Switch          Washer_UpdateAvailable   "Update Available"            (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:updateAvailable" }
Switch          Washer_Power             "Power"                       (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:power" }
Switch          Washer_BubbleSoak        "Bubble Soak"                 (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:bubbleSoak" }
Number          Washer_Volume            "Volume [%.0f]"               (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:volume" }
String          Washer_ExtraCare         "Extra Care [%s]"             (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:extraCare" }
String          Washer_ExtraCareLocation "Extra Care Location [%s]"    (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:extraCareLocation" }

// ── Television ──────────────────────────────────────────────────────────────
Group           gTV                      "Living Room TV"
Switch          TV_Power                 "Power"                       (gTV) { channel="smartthingscloud:television:myaccount:myTV:power" }
Number          TV_Volume                "Volume [%.0f]"               (gTV) { channel="smartthingscloud:television:myaccount:myTV:tvVolume" }
Switch          TV_Mute                  "Mute"                        (gTV) { channel="smartthingscloud:television:myaccount:myTV:tvMute" }
String          TV_InputSource           "Input [%s]"                   (gTV) { channel="smartthingscloud:television:myaccount:myTV:inputSource" }
String          TV_Channel               "Channel [%s]"                (gTV) { channel="smartthingscloud:television:myaccount:myTV:tvChannel" }
String          TV_ChannelName           "Channel Name [%s]"           (gTV) { channel="smartthingscloud:television:myaccount:myTV:tvChannelName" }
String          TV_PictureMode           "Picture Mode [%s]"           (gTV) { channel="smartthingscloud:television:myaccount:myTV:pictureMode" }
String          TV_SoundMode             "Sound Mode [%s]"             (gTV) { channel="smartthingscloud:television:myaccount:myTV:soundMode" }
String          TV_Playback              "Playback [%s]"               (gTV) { channel="smartthingscloud:television:myaccount:myTV:playbackStatus" }
Number:Power    TV_Watt                  "Power [%.0f W]"              (gTV) { channel="smartthingscloud:television:myaccount:myTV:watt" }
Number:Energy   TV_kWh                   "Energy [%.3f kWh]"           (gTV) { channel="smartthingscloud:television:myaccount:myTV:kwh" }
String          TV_Firmware              "Firmware [%s]"               (gTV) { channel="smartthingscloud:television:myaccount:myTV:firmwareVersion" }
String          TV_OnlineStatus          "Online [%s]"                 (gTV) { channel="smartthingscloud:television:myaccount:myTV:onlineStatus" }
Switch          TV_ChannelUp             "Channel ▲"                   (gTV) { channel="smartthingscloud:television:myaccount:myTV:channelUp" }
Switch          TV_ChannelDown           "Channel ▼"                   (gTV) { channel="smartthingscloud:television:myaccount:myTV:channelDown" }
String          TV_SupportedInputs       "Available Inputs [%s]"       (gTV) { channel="smartthingscloud:television:myaccount:myTV:supportedInputSources" }
String          TV_SupportedPictures     "Available Picture Modes [%s]" (gTV) { channel="smartthingscloud:television:myaccount:myTV:supportedPictureModes" }
String          TV_SupportedSound        "Available Sound Modes [%s]"  (gTV) { channel="smartthingscloud:television:myaccount:myTV:supportedSoundModes" }

// ── Presence ────────────────────────────────────────────────────────────────
Group           gPresence                "Presence"
Switch          Phone_Presence           "Home [%s]"                   (gPresence) { channel="smartthingscloud:presence:myaccount:myphone:presence" }

// ── Light Sensor ────────────────────────────────────────────────────────────
Group           gLightSensor             "Bedroom Light Sensor"
Number          LS_Illuminance           "Illuminance [%.0f lx]"       (gLightSensor) { channel="smartthingscloud:lightSensor:myaccount:mysensor:illuminance" }
Number          LS_BrightnessLevel       "Brightness Level [%.0f]"     (gLightSensor) { channel="smartthingscloud:lightSensor:myaccount:mysensor:brightnessLevel" }

// ── Scenes ──────────────────────────────────────────────────────────────────
Switch          Scene_Filmscene          "Filmscene"                   { channel="smartthingscloud:scene:myaccount:filmscene:trigger" }
```

### `smartthings.sitemap`

```java
sitemap smartthings label="SmartThings" {

    Frame label="Washing Machine" {
        Text   item=Washer_MachineState   label="State [MAP(WasherState.map):%s]"
        Text   item=Washer_JobState       label="Phase [MAP(WasherJob.map):%s]"
        Text   item=Washer_Remaining      label="Remaining [%.0f min]"
        Text   item=Washer_CompletionTime label="Done at [%1$tH:%1$tM]"
        Text   item=Washer_Progress       label="Progress [%.0f %%]"
        Switch item=Washer_Power
        Switch item=Washer_BubbleSoak     mappings=[ON="On", OFF="Off"]
        Slider item=Washer_Volume         minValue=0 maxValue=3 step=1
        Text   item=Washer_Watt           label="Power [%.0f W]"
        Text   item=Washer_kWh            label="Energy [%.2f kWh]"
        Text   item=Washer_WaterLiters    label="Water [%.1f L]"
    }

    Frame label="Living Room TV" {
        Switch item=TV_Power
        Slider item=TV_Volume             minValue=0 maxValue=100 step=1
        Switch item=TV_Mute               mappings=[ON="Muted", OFF="Sound"]
        Text   item=TV_Input              label="Input [%s]"
        Text   item=TV_PictureMode        label="Picture [%s]"
        Text   item=TV_SoundMode          label="Sound [%s]"
        Text   item=TV_AppId              label="App [%s]"
    }

    Frame label="Presence" {
        Text item=Phone_Presence label="Home [MAP(da.map):%s]"
    }

    Frame label="Bedroom Light Sensor" {
        Text item=LS_Illuminance    label="Illuminance [%.0f lx]"
        Text item=LS_BrightnessLevel label="Brightness Level [%.0f]"
    }

    Frame label="Scenes" {
        Switch item=Scene_Filmscene label="Filmscene"
    }

}
```

> **Note for Switch items with `mappings`:** Always use `ON`/`OFF` (uppercase) as mapping keys.
> Lowercase `on`/`off` keys send `StringType` commands instead of `OnOffType` and will not be handled correctly by the binding.

### `smartthings.rules` (example — washer notification + presence grace timer)

```java
rule "Washing machine finished"
when
    Item Washer_JobState changed to "finish"
then
    sendNotification("your@email.com", "Washing machine done!")
end

var Timer awayTimer = null

rule "Phone left home"
when
    Item Phone_Presence changed to OFF
then
    if (awayTimer != null && !awayTimer.hasTerminated) awayTimer.cancel
    awayTimer = createTimer(now.plusMinutes(5), [|
        if (Phone_Presence.state == OFF) {
            // trigger away logic here
            logInfo("presence", "Phone confirmed away after grace period")
        }
    ])
end

rule "Phone arrived home"
when
    Item Phone_Presence changed to ON
then
    if (awayTimer != null && !awayTimer.hasTerminated) awayTimer.cancel
end
```

### `smartthings.js` (same examples in JS scripting / ECMAScript)

> Requires the **JS Scripting** add-on (`org.openhab.automation.jsscripting`).
> Place the file in `$OPENHAB_CONF/automation/js/`.

```javascript
const { rules, items, actions, time } = require("openhab");

// Washer notification
rules.when().item("Washer_JobState").changed().toValue("finish").then(() => {
  actions.NotificationAction.sendNotification("your@email.com", "Washing machine done!");
}).build("Washing machine finished");

// Presence grace timer
let awayTimer = null;

rules.when().item("Phone_Presence").changed().toValue("OFF").then(() => {
  if (awayTimer !== null) {
    awayTimer.cancel();
    awayTimer = null;
  }
  awayTimer = actions.ScriptExecution.createTimer(time.ZonedDateTime.now().plusMinutes(5), () => {
    if (items.getItem("Phone_Presence").state === "OFF") {
      console.log("Phone confirmed away after grace period");
      // trigger away logic here
    }
    awayTimer = null;
  });
}).build("Phone left home");

rules.when().item("Phone_Presence").changed().toValue("ON").then(() => {
  if (awayTimer !== null) {
    awayTimer.cancel();
    awayTimer = null;
  }
}).build("Phone arrived home");
```

---

## Technical Notes

### OAuth2 PKCE

The binding uses the same `clientId` as the open-source SmartThings CLI (`d18cf96e-c626-4433-bf51-ddbb10c5d1ed`).
This client ID only whitelists `http://localhost:61973/finish` as redirect URI.
The binding opens port **61973** temporarily to receive the callback, then closes it immediately.

### API Polling

Polls `GET /v1/devices/{deviceId}/components/main/status` every `pollingIntervalSeconds` seconds (default 30).
This returns all capability state in a single request, keeping API usage minimal.

### Token Refresh

Tokens are refreshed automatically before expiry.
The bridge goes `OFFLINE (CONFIGURATION_ERROR)` only if the refresh token is revoked.
Re-authorize via `http://YOUR_OPENHAB:8080/smartthingscloud` in that case.

---

## Building from Source

```bash
git clone https://github.com/Prinsessen/openhab-binding-smartthingscloud.git
cd openhab-binding-smartthingscloud
mvn clean package -DskipTests
```

Deploy:
```bash
cp target/org.openhab.binding.smartthingscloud-*.jar /usr/share/openhab/addons/
```

---

## Known Limitations & Planned Features

### Completed

- [x] `television` thing — Samsung Smart TV support (power, volume, mute, input, channel, picture mode, sound mode, app ID, channel up/down, supported inputs/picture modes/sound modes)
- [x] `presence` thing — SmartThings phone/arrival sensor presence detection
- [x] `lightSensor` thing — illuminance + brightness level channels
- [x] `airConditioner` thing — Samsung Smartthings Airconditioner support

### Planned

- [ ] Auto-discovery — list SmartThings devices and auto-create things
- [ ] Dryer support
- [ ] Dishwasher support
- [ ] SSE/webhook push events instead of polling
- [ ] TV: channel list / app list enumeration
- [ ] Presence: geofence zone support (multiple zones)

Contributions via pull request are welcome.

---

## Changelog

### v1.4.0 (2026-05-01)

**TV:** Added `kwh` — accumulated energy consumption from `powerConsumptionReport` (Wh → kWh).
**TV corrections:** Channel IDs `inputSource`, `pictureMode`, `soundMode` corrected (were previously documented as `tvInput`, `tvPictureMode`, `tvSoundMode`); `tvAppId` removed (not implemented); `tvChannelName`, `playbackStatus`, `watt`, `firmwareVersion`, `onlineStatus` added to documentation (pre-existing channels now documented).
**Washer:** Added `remainingTimeStr` (`HH:MM` formatted remaining time), `operationTime` (total cycle duration in minutes), `updateAvailable` (firmware update flag from `samsungce.softwareUpdate`).
TV channel count: 12 → 18. Washer channel count: 26 → 29.

---

### v1.3.0 (2026-05-01)

**New television channels**

- `channelUp` / `channelDown` (Switch, control) — momentary channel zap buttons; the binding resets the switch to OFF after each command so repeated presses work correctly
- `supportedInputSources` (String, read-only) — comma-separated list of all input sources the TV reports as available
- `supportedPictureModes` (String, read-only) — comma-separated list of available picture modes
- `supportedSoundModes` (String, read-only) — comma-separated list of available sound modes

**New washer channels**

- `detergentRemaining` (Number, read-only) — detergent level remaining (%)
- `softenerRemaining` (Number, read-only) — softener level remaining (%)
- `delayEnd` (Number, read-only) — delay-end countdown remaining (minutes)
- `supportedCourses` (String, read-only) — comma-separated list of wash programs supported by the machine

**Bug fix — volume/mute no longer bounce back**

Samsung SmartThings cloud never updates the `audioVolume` or `audioMute` timestamps after a command is sent (a confirmed Samsung-side limitation). The binding now tracks the last-seen API timestamp for these attributes. A poll update is only applied when the timestamp has actually changed — so the value you set via the UI stays until the TV itself reports a different state.

TV channel count: 7 → 12. Washer channel count: 22 → 26.

---

### v1.2.0 (2026-04-29)

**New thing types**

- `television` thing: Samsung Smart TV support — 7 read channels (`tvVolume`, `tvMute`, `tvInput`, `tvChannel`, `tvPictureMode`, `tvSoundMode`, `tvAppId`) + 7 write channels (power, volume, mute, input, channel, picture mode, sound mode)
- `presence` thing: SmartThings presence sensor / Samsung phone presence — 1 channel (`presence` Switch: `ON`=home, `OFF`=away)
- `lightSensor` thing: SmartThings illuminance sensor — 2 channels (`illuminance` in lux, `brightnessLevel` relative 0–100)

**Bug fixes**

- TV volume: fixed `NumberFormatException` when SmartThings API returns volume as decimal string (e.g. `55.0`). The binding now uses `Double.parseDouble` + `Math.round` so Slider items work correctly without errors.
- TV input source: binding now prefers `samsungvd.mediaInputSource.inputSource` (always current) over the standard `mediaInputSource` attribute which can be stale.

---

### v1.1.0

**New washer channels**

- `waterLiters` — cumulative water consumption (liters)
- `operatingState` — detailed operating state (`ready`, `running`, `paused`, `finished`)
- `currentCycle` — active wash program code (e.g. `B0`, `1B`) — use MAP transform for display
- `kidsLock` — child lock active state (Switch)
- `progress` — wash cycle progress 0–100 % (only populated while running)

Washer channel count: 17 → 22.

---

### v1.0.0

**Initial release**

- `account` bridge: OAuth2 PKCE authorization — no PAT or developer registration needed. Uses the same open-source `clientId` as the SmartThings CLI.
- `washer` thing: 17 channels — machine state, job state, completion time, running, remaining, remote enabled, mode, rinse mode, spin speed, temperature, watt, kWh, bubble soak, volume, extra care, extra care location, power
- Automatic token refresh and persistent token storage via openHAB `StorageService`
- Documented Samsung capability namespace quirk: read via `samsungce.*`, write via standard capabilities

---

## Credits

Binding developed by **Nanna** ([@Prinsessen](https://github.com/Prinsessen)).

OAuth2 PKCE approach inspired by the [SmartThings CLI](https://github.com/SmartThingsCommunity/smartthings-cli).
Binding structure modeled on the openHAB Withings binding.
