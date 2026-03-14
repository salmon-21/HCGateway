# HCGateway Development Guide

## Project Overview

Android app (Jetpack Compose + Kotlin) that syncs Health Connect data to a self-hosted API server.

## Architecture

- **Android app**: `app/` — Jetpack Compose, Kotlin, Hilt DI, MVVM
- **API server**: `api/` — Python Flask

### App Structure

```
app/app/src/main/java/dev/shuchir/hcgateway/
  HCGatewayApp.kt              # @HiltAndroidApp, Sentry init, theme init
  MainActivity.kt              # AppCompatActivity, single activity

  di/                           # Hilt modules (AppModule, HealthConnectModule)
  data/
    local/                      # DataStore preferences
    remote/                     # Retrofit API, auth interceptors
    repository/                 # Auth, Sync, HealthConnect, NetworkMonitor
  domain/model/                 # RecordTypes, SyncState
  ui/
    theme/                      # Material You + custom colors (success)
    navigation/                 # NavGraph with material-motion transitions
    home/                       # Sync screen (HomeScreen, HomeViewModel)
    login/                      # Login screen
    settings/                   # Settings, Licenses screens
    onboarding/                 # Permission onboarding
    components/                 # FilledCard, SyncWarningDialog
  worker/                       # SyncWorker, SyncScheduler, BootReceiver, PersistentSyncService
  fcm/                          # Firebase Cloud Messaging
```

### Key Dependencies

- Compose BOM 2026.02.00, Material 3 1.5.0-alpha15 (M3 Expressive)
- Hilt, Retrofit + OkHttp, DataStore, WorkManager
- Health Connect Client 1.1.0-alpha10
- Firebase Messaging, Sentry
- material-motion-compose-core (Shared Axis X transitions)
- aboutlibraries-core (license metadata)

## Development Setup

- **Build & install**: `./gradlew installDebug` (auto-launches app)
- **Device required**: Android device with Health Connect
- **Gradle**: 9.4.0, AGP 8.10.1, Kotlin 2.1.20

## Key Design Decisions

- **AppCompatDelegate** for theme switching (instant, no Compose delay)
- **OkHttp AuthInterceptor** handles 403 auto-refresh (API returns 403 not 401)
- **Changes API** for incremental sync (delta only)
- **Parallel sync** via coroutines async/awaitAll
- **PersistentSyncService** for always-on notification with next sync time
- **M3 Expressive**: wavy progress, loading indicator, button shapes, spring motion

## Conventions

- Communicate in Japanese
- Keep responses concise
