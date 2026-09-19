# Changelog

All notable changes to the SmartThings Cloud binding are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- `dryer` thing type (Samsung tumble dryers; issue #1).
- Combo washer/dryer support on the `washer` thing: `cycleType`, `jobPhase`,
  `scheduledPhases`, `washingProgress`, `dryingProgress`, `dryLevel` and
  `dryingTime`, populated when the machine has the dryer capabilities (issue #6).

### Fixed
- Main UI listed the binding with "No thing types can be added": the add-on
  descriptor id was `binding-smartthingscloud` instead of `smartthingscloud`
  (issue #1).

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
