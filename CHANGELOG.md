# Changelog

All notable changes to the SmartThings Cloud binding are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- `dishwasher` thing type (beta): cycle state, program and options, remaining time,
  energy and water use, remote control. Mapped from a DW8700B status dump
  (issue #7); run-time values and writes await a tester.

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
