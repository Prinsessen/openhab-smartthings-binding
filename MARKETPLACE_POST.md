| Key | Value |
|----|----|
| uid | smartthingscloud |
| type | binding |
| author | Nanna Agesen |
| version range | \[5.0.0;6.0.0) |
| download | [org.openhab.binding.smartthingscloud-1.6.0.jar](https://github.com/Prinsessen/openhab-smartthings-binding/releases/download/v1.6.0/org.openhab.binding.smartthingscloud-1.6.0.jar) |

# Samsung SmartThings Cloud Binding

Connect your Samsung SmartThings cloud-connected appliances to openHAB — **no hub, no Personal Access Token, no developer registration required.**

Authorization uses **OAuth2 PKCE** (the same flow as the official SmartThings CLI). You click Authorize in the browser on the openHAB machine, log in with your Samsung account, and the bridge goes ONLINE. Tokens are persisted and auto-refreshed — no re-authorization needed after restart.

> **Note:** This is *not* the legacy SmartThings Hub binding. That binding required a physical SmartThings Hub and Groovy SmartApps, which Samsung deprecated in 2022. This binding communicates directly with the Samsung SmartThings Cloud API and works with any Wi-Fi connected Samsung appliance.

---

## Supported Things

| Thing Type | Description |
|----|-----|
| `account` (Bridge) | Samsung SmartThings account — manages OAuth2 tokens |
| `washer` | Samsung washing machine |
| `television` | Samsung Smart TV |
| `presence` | SmartThings phone / presence sensor |
| `lightSensor` | SmartThings illuminance sensor |
| `scene` | SmartThings scene — trigger a scene with a Switch |
| `airConditioner` | Samsung air conditioner — power, mode, temperature, fan |
---

## Channels

### Account Bridge — Status (read-only)

| Channel | Type | Description |
|----|----|----|---|
| `authStatus` | String | Authorization state: `Authorized ✓` / `Connection failed — check logs` / `Not authorized — open .../smartthingscloud` |

---

### Washer — State (read-only)

| Channel | Type | Description |
|----|----|----|
| `machineState` | String | Machine state: `run`, `pause`, `stop` |
| `jobState` | String | Cycle phase: `washing`, `rinsing`, `spin`, `finish`, `none`, etc. |
| `completionTime` | DateTime | Estimated finish time |
| `running` | Switch | ON while a cycle is active |
| `remaining` | Number | Minutes remaining |
| `remoteEnabled` | Switch | ON when remote control is permitted by the machine |
| `mode` | String | Active wash program code |
| `rinseMode` | String | Rinse count/mode |
| `spinSpeed` | String | Spin speed (rpm) |
| `temperature` | String | Wash temperature (°C) |
| `watt` | Number:Power | Current power consumption (W) |
| `kwh` | Number:Energy | Accumulated energy usage (kWh) |
| `waterLiters` | Number | Cumulative water consumed (lifetime total, liters) |
| `operatingState` | String | Detailed operating state: `ready`, `running`, `paused`, `finished` |
| `currentCycle` | String | Active wash program code from Samsung (e.g. `B0`, `1B`, `65`) |
| `kidsLock` | Switch | ON when child lock is active |
| `progress` | Number | Wash cycle progress 0–100 % (only populated while running) |
| `detergentRemaining` | Number | Detergent level remaining (%) |
| `softenerRemaining` | Number | Softener level remaining (%) |
| `delayEnd` | Number | Delay-end countdown remaining (minutes) |
| `supportedCourses` | String | Comma-separated list of supported wash programs |

### Washer — Control (read/write)

| Channel | Type | Description |
|----|----|----|
| `power` | Switch | Power on/off |
| `bubbleSoak` | Switch | Bubble Soak pre-soak on/off |
| `volume` | Number | End-of-cycle buzzer volume: 0=off, 1–3 |
| `extraCare` | String | Extra Care mode (`off`, `manual`, `userLocationBased`) |
| `extraCareLocation` | String | Extra Care location (`indoor`, `outdoor`) |

### Washer — Energy totals (read-only)

| Channel | Type | Description |
|----|----|----|
| `watt` | Number:Power | Live power consumption (W) |
| `kwh` | Number:Energy | Accumulated energy used (kWh) |

---

### Television — State (read-only)

| Channel | Type | Description |
|----|----|----|---|
| `tvVolume` | Number | Current volume level (0–100) |
| `tvMute` | Switch | ON when muted |
| `inputSource` | String | Active input source (e.g. `HDMI1`, `digitalTv`) |
| `tvChannel` | String | Active channel number |
| `tvChannelName` | String | Active channel name |
| `pictureMode` | String | Picture mode: `Standard`, `Movie`, `Game`, etc. |
| `soundMode` | String | Sound mode: `Standard`, `Movie`, `Music`, etc. |
| `playbackStatus` | String | Current playback state |
| `watt` | Number:Power | Current power draw (W) |
| `kwh` | Number:Energy | Accumulated energy (kWh, lifetime) |
| `firmwareVersion` | String | TV firmware version |
| `onlineStatus` | String | Online status (`Idle`, `Active`) |
| `supportedInputSources` | String | Comma-separated list of available input sources |
| `supportedPictureModes` | String | Comma-separated list of available picture modes |
| `supportedSoundModes` | String | Comma-separated list of available sound modes |

### Television — Control (read/write)

| Channel | Type | Description |
|----|----|----|---|
| `power` | Switch | Power on/off |
| `tvVolume` | Number | Set volume (0–100) |
| `tvMute` | Switch | Mute/unmute |
| `inputSource` | String | Set input source |
| `tvChannel` | String | Set channel number |
| `pictureMode` | String | Set picture mode |
| `soundMode` | String | Set sound mode |
| `channelUp` | Switch | Zap one channel up (momentary — resets to OFF automatically) |
| `channelDown` | Switch | Zap one channel down (momentary — resets to OFF automatically) |

---

### Presence — State (read-only)

| Channel | Type | Description |
|----|----|----|
| `presence` | Switch | ON = home, OFF = away |

---

### Light Sensor — State (read-only)

| Channel | Type | Description |
|----|----|----|
| `illuminance` | Number:Illuminance | Light level in lux |
| `brightnessLevel` | Dimmer | Brightness 0–100 % (maps lux to %) |

---

### Air Conditioner — Control (read/write)

| Channel | Type | Description |
|----|----|----|
| `power` | Switch | Turn the air conditioner on/off |
| `mode` | String | Operating mode: `auto`, `cool`, `dry`, `fan`, `heat` (device-dependent) |
| `targetTemperature` | Number:Temperature | Cooling setpoint (16–30 °C) |
| `fanMode` | String | Fan speed: `auto`, `low`, `medium`, `high`, `turbo` (device-dependent) |
| `fanOscillationMode` | String | Swing direction: `fixed`, `vertical`, `horizontal`, `all` (device-dependent) |
| `optionalMode` | String | Samsung-specific mode, e.g. `windFree`, `sleep`, `speed` (device-dependent) |

### Air Conditioner — State (read-only)

| Channel | Type | Description |
|----|----|----|
| `currentTemperature` | Number:Temperature | Measured room temperature |

---
### Scene — Control (write-only)

| Channel | Type | Description |
|----|----|----||
| `trigger` | Switch | Send `ON` to execute the scene — auto-resets to `OFF` |

**Configuration parameters:**

| Parameter | Required | Description |
|----|----|----|
| `sceneId` | ✅ | SmartThings scene ID (UUID) |
| `locationId` | ❌ | Location ID — only needed for multi-location accounts |

Find your scene ID with the SmartThings CLI: `smartthings scenes`

---
## Quick Start

### smartthings.things

```java
Bridge smartthingscloud:account:myaccount "Samsung SmartThings" [
    pollingIntervalSeconds=30
] {
    Thing washer mywasher "Samsung Washing Machine" [
        deviceId="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
    ]
}
```

> **Tip:** Find your `deviceId` in the Samsung SmartThings app under the device's detail page, or it is shown on the authorization success page after the OAuth2 flow.

### smartthings.items

```java
Group gWasher "Washing Machine" <washingmachine>

// State
String          Washer_MachineState     "State [%s]"              (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:machineState" }
String          Washer_JobState         "Phase [%s]"              (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:jobState" }
Switch          Washer_Running          "Running"                 (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:running" }
Number          Washer_Remaining        "Remaining [%.0f min]"    (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:remaining" }
DateTime        Washer_CompletionTime   "Done at [%1$tH:%1$tM]"   (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:completionTime" }
Switch          Washer_RemoteEnabled    "Remote Control [%s]"     (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:remoteEnabled" }

// Program
String          Washer_Mode             "Program [%s]"            (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:mode" }
String          Washer_RinseMode        "Rinse [%s]"              (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:rinseMode" }
String          Washer_SpinSpeed        "Spin [%s rpm]"           (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:spinSpeed" }
String          Washer_Temperature      "Temperature [%s °C]"     (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:temperature" }

// Energy & water
Number:Power    Washer_Watt             "Power [%.0f W]"          (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:watt" }
Number:Energy   Washer_kWh              "Energy [%.2f kWh]"       (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:kwh" }
Number          Washer_WaterLiters      "Water [%.1f L]"          (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:waterLiters" }

// Extended state (read-only)
String          Washer_OperatingState   "Operating State [%s]"    (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:operatingState" }
String          Washer_CurrentCycle     "Active Program [%s]"     (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:currentCycle" }
Switch          Washer_KidsLock         "Child Lock [%s]"         (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:kidsLock" }
Number          Washer_Progress         "Progress [%.0f %%]"      (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:progress" }

// Control
Switch          Washer_Power            "Power"                   (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:power" }
Switch          Washer_BubbleSoak       "Bubble Soak"             (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:bubbleSoak" }
Number          Washer_Volume           "Volume [%.0f]"           (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:volume" }
String          Washer_ExtraCare        "Extra Care [%s]"         (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:extraCare" }
String          Washer_ExtraCareLocation "Location [%s]"          (gWasher) { channel="smartthingscloud:washer:myaccount:mywasher:extraCareLocation" }
```

> ⚠️ **Sitemap tip:** Use `mappings=[ON="On", OFF="Off"]` (uppercase) for Switch channels in sitemaps. Lowercase keys send `StringType` instead of `OnOffType` and the command will be ignored.

### OAuth2 Authorization

1. Add the bridge and washer things (`.things` file or UI)
2. Open **`http://localhost:8080/smartthingscloud`** in a browser on the **openHAB machine itself**
3. Click **"Authorize with SmartThings"** and log in with your Samsung account
4. Bridge goes **ONLINE** — done

> ⚠️ **Important:** The browser must run on the **same machine** as openHAB. Samsung's OAuth2 callback is hardcoded to `http://localhost:61973/finish` (a Samsung-side constraint from the SmartThings CLI client ID — it cannot be changed). Use SSH port forwarding (`ssh -L 8080:localhost:8080 user@openhab-host`) if you only have remote access.

---

## Why no PAT token?

The binding reuses the `clientId` from the open-source SmartThings CLI — a client ID registered by Samsung for personal use that supports the standard OAuth2 PKCE flow. No developer account, no app registration, no secrets to manage.

---

## Beta — contributions welcome!

This is a **beta release**. The following thing types are currently implemented:

- ✅ `washer` — Samsung washing machine (26 channels)
- ✅ `television` — Samsung Smart TV (12 channels — power, volume, mute, input, channel, picture/sound mode, app ID, channel up/down, supported inputs/modes)
- ✅ `presence` — SmartThings phone / arrival sensor
- ✅ `lightSensor` — illuminance (lux) + brightness level
- ✅ `scene` — execute any SmartThings scene via a Switch channel
- ✅ `airConditioner` — power, mode, target/current temperature, fan mode/oscillation (contributed by [@phiba2](https://github.com/phiba2))

Still planned:
- Dryer
- Dishwasher
- Air purifier
- Fridge / freezer

**If you own one of these and want openHAB support, I'd love to hear from you.** Open an issue on GitHub describing your device and I'm happy to work with you to implement it — you don't need Java experience, just the ability to test on your own device.

---

## Resources

* **Download JAR:** [org.openhab.binding.smartthingscloud-1.6.0.jar](https://github.com/Prinsessen/openhab-smartthings-binding/releases/download/v1.6.0/org.openhab.binding.smartthingscloud-1.6.0.jar)
* **Source Code:** [github.com/Prinsessen/openhab-smartthings-binding](https://github.com/Prinsessen/openhab-smartthings-binding)
* **Full Documentation:** [README.md](https://github.com/Prinsessen/openhab-smartthings-binding/blob/main/README.md)
* **Release:** [v1.6.0 — Air Conditioner Support](https://github.com/Prinsessen/openhab-smartthings-binding/releases/tag/v1.6.0)
* **License:** EPL-2.0

---

*Tested on openHAB 5.2. Feedback on openHAB 4.x compatibility welcome.*
