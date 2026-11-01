# Changelog

All notable changes to the SmartThings Cloud binding are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- `refrigerator` thing type (beta): the binding's first multi-component device.
  Doors, temperature and setpoint (read/write, with the allowed range) per
  compartment as channel groups (`cooler`, `freezer`, `cvroom`, `onedoor`),
  Power Cool / Power Freeze, vacation mode, power and energy, water filter.
  Reads `/devices/{id}/status` (every component) and skips the compartments
  the fridge lists in `custom.disabledComponents` and the capabilities in
  `custom.disabledCapabilities`. Built from three status dumps (issue #3);
  writes await a tester. JUnit test replays the dumps' shape.

### Fixed
- Main UI listed the binding with "No thing types can be added": the add-on
  descriptor id was `binding-smartthingscloud` instead of `smartthingscloud`,
  so the UI never matched the thing types to the add-on (issue #1). Things could
  only be created from `.things` files; now the UI works too.

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
