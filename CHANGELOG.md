# Changelog

All notable changes to the SmartThings Cloud binding are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.0.0-beta.1] - 2026-11-26

For **openHAB 5.3** (snapshots and milestones from 2026-11-22 on). Same features as 1.6.0, which stays
the version for openHAB 5.0 - 5.2.

### Changed
- openHAB 5.3 moved to Jetty 12 / Jakarta EE 10 and removed the OSGi `HttpService`, so 1.6.0 does not start
  there. The authorization page (`/smartthingscloud`) is now a `jakarta.servlet` servlet published to the OSGi
  servlet whiteboard instead of being registered through the `HttpService`.

### Added
- `SmartThingsCloudServletTest`: the overview page, Samsung's error, the SmartApp lifecycle (PING, CONFIGURATION,
  INSTALL handing the tokens to the bridge) and a bad form POST - the same assertions pass on 1.6.0 and here.
  The authorization flow itself has not yet been run on a live openHAB 5.3.

## [1.6.0] - 2026-08-08

### Added
- Support for Samsung SmartThings air conditioners (`airConditioner` thing type),
  including power, mode, target temperature and fan mode channels
  (contributed by [@phiba2](https://github.com/phiba2), PR #5).

## [1.5.0] - 2026-05-02

### Added
- `scene` thing type — execute SmartThings scenes via a switch channel.

### Security
- Removed private redirect domain and hardcoded client secret from defaults.

### Documentation
- Use placeholder values in the scene example.

## [1.4.0] - 2026-05-01

### Added
- Initial release of the SmartThings Cloud binding.
- OAuth2 PKCE authorization using the built-in client ID (no developer app required).
- Thing types: `washer`, `television`, `presence`, `lightSensor`.

[1.6.0]: https://github.com/Prinsessen/openhab-smartthings-binding/releases/tag/v1.6.0
[1.5.0]: https://github.com/Prinsessen/openhab-smartthings-binding/releases/tag/v1.5.0
[1.4.0]: https://github.com/Prinsessen/openhab-smartthings-binding/releases/tag/v1.4.0
