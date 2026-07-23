# 通勤闹钟 (weather-traffic-alarm)

A commuter alarm clock for Android that automatically adjusts wake-up time
based on weather conditions and traffic estimates.

**Package name:** `com.ljwzz.weathertrafficalarm`

## Architecture

- **Android** (Kotlin + Jetpack Compose) — API 29–36
- **Backend** (Spring Boot 3.5.x) — evaluation API, calendar, weather & route providers
- **Contract** (OpenAPI 3.1) — single source of truth for API interfaces

## Directory Structure

```
weather-traffic-alarm/
├── android/          # Android app (Gradle multi-module)
├── backend/          # Spring Boot backend
├── contract/         # OpenAPI spec & examples
├── calendar-data/    # Official holiday calendar data & tools
├── docs/             # Documentation, decisions, test results
├── infra/            # Docker Compose, deployment configs
└── scripts/          # Build & verification scripts
```

## Minimum Tool Versions

- JDK 21
- Android SDK Platform 36 + Build Tools 36.0.0
- Docker Engine (for local infra)
- Gradle 9.5.0 (via wrapper)

## Verification

```bash
./scripts/verify-all.sh
```
