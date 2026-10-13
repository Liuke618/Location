<p align="center">
<img src="./docs/images/LOGO.png" height="80"/>
</p>

<div align="center">

### YingSuo (GoGoGo)

A mock location APP without ROOT for Android 12+.

[简体中文](./README.md) | English

</div>

## Introduction

&emsp;&emsp;YingSuo is a fork of [ZCShou/GoGoGo](https://github.com/ZCShou/GoGoGo), a mock location tool for Android built on the debug API (TestProvider) plus the AMap LBS SDK: pick a spot on the map (via search, tap, long-press, or manual coordinates) and teleport the system location there with one tap — no root required. Static teleport only; no route simulation.

&emsp;&emsp;By default the app **never reads your real location** — targets come from search, map picking, or manual input only. A "locate myself" switch in Settings can turn on the real-location blue dot when needed.

## Features

1. **Map-based mock location**: tap / long-press the map or enter coordinates (GCJ02 / WGS84), one-tap GO; teleport to new spots while mocking
2. **POI search + reverse geocoding**: pick search suggestions with addresses; local search & location history
3. **Favorites**: bookmark frequent spots, mark one as default — auto-selected on cold start and as GO fallback
4. **Optional real location**: off by default (no real location requests at all); enable it for the blue dot and "back to my location"
5. **In-app updates**: detects new releases and installs them
6. **Agreement & privacy gate**: user agreement and privacy policy shown on first launch
7. **Self-serve AMap key setup**: packages without a configured key get an onboarding dialog at launch; the Settings page accepts a self-registered key with step-by-step guidance and the signing SHA1 for reference, effective after restart

## Requirements

- Android 12 (API 31) or later
- arm64-v8a devices

## Usage

1. Install the APK, accept the agreement & privacy policy, grant location permission
2. Select this app as the **mock location app** in Developer Options
3. Search for a place or pick a spot on the map, then tap GO; tap GO again to stop
4. (Optional) Settings → Map Location → enable "Locate myself" for the real-location blue dot

## Build

Toolchain: JDK 17 + Android SDK (AGP 9.x / targetSdk 36, single pure-Java `:app` module).

1. Configure `local.properties` (not committed):
   - `sdk.dir`: Android SDK path
   - `AMAP_API_KEY`: AMap Native key (bound to package `com.yybb.location` and the signing cert SHA1; optional — if omitted, the app guides you through configuring it under "Settings → AMap API Key" at launch)
   - `KEY_ALIAS` / `KEY_STORE_PASSWORD` / `KEY_PASSWORD`: signing credentials
2. Build & verify:

   ```bash
   sh gradlew assembleDebug test lint                              # daily
   sh gradlew clean assembleDebug assembleRelease test lint        # full gate
   ```

   > `gradlew` ships with the executable bit set — `./gradlew ...` (or `sh gradlew ...`) works as-is.

## Disclaimer

1. For learning Android location debugging only. Do not use for cheating in games, fitness apps, or anything that violates laws or platform rules — you are on your own.
2. Mocking relies on the system debug API; some apps (e.g. WeChat) may not be affected.

## Docs

- [Business overview (Chinese)](./docs/business-overview.md): what the code does today, per domain, kept in sync
- [AGENTS.md](./AGENTS.md): build, package boundaries and conventions

## Upstream & Credits

- Upstream: [ZCShou/GoGoGo](https://github.com/ZCShou/GoGoGo) — thanks to the original author
- References: [MockGPS](https://github.com/Hilaver/MockGPS), [together-go](https://github.com/bxxfighting/together-go), [Mocklation](https://github.com/P72B/Mocklation)

## License

[GPL-3.0-only](./LICENSE) © ZCShou. This fork remains GPL-3.0.
