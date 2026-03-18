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
- Health Connect Client 1.1.0 (stable), 41 record types
- Firebase Messaging, Sentry
- material-motion-compose-core (Shared Axis X transitions)
- aboutlibraries-core (license metadata)

## Development Setup

- **Build & install**: `./gradlew installDebug` (auto-launches app)
- **Clean build**: `./gradlew clean installDebug` (when APK changes not reflected)
- **Device required**: Android device with Health Connect
- **Gradle**: 9.4.0, AGP 8.10.1, Kotlin 2.1.20

## Key Design Decisions

- **AppCompatDelegate** for theme switching (instant, no Compose delay)
- **OkHttp AuthInterceptor** handles 403 auto-refresh (API returns 403 not 401)
- **Changes API** for incremental sync (delta only)
- **Streaming sync** via `readRecordsPaged` + Channel pipeline (read/upload overlap)
- **PersistentSyncService** for always-on notification with next sync time + cancel button
- **M3 Expressive**: wavy progress, loading indicator, button shapes, spring motion
- **PERMISSION_READ_HEALTH_DATA_HISTORY** for reading data older than 30 days

## Sync Architecture

- **fullSync**: All 41 types in parallel via `async(Dispatchers.IO)`, each type streams pages (1000 records) through a Channel — reader produces, consumer uploads. Memory bounded.
- **deltaSync**: Uses Changes API token to sync only modified records since last sync.
- **Cancel**: `@Volatile cancelled` flag prevents async coroutines from overwriting Cancelled state. `ensureActive()` checks in reader/consumer loops.
- **Force Sync**: Date range picker, consumes Changes API token on completion to clear New counts.
- **WorkManager**: Periodic sync with 75% interval guard to prevent duplicate sync on foreground resume.

## Gotchas

- **MindfulnessSession (record type 41)** is experimental and unsupported on Samsung devices. Including it in `ChangesTokenRequest` causes `SecurityException` that silently blocks token persistence. Filter unsupported types by checking `getGrantedPermissions()`.
- **Samsung Health** writes to Health Connect in ~1 hour batches, not real-time.
- **Process recreation**: Android may kill the app process in background. ViewModels reset, `_serverCounts` becomes null. Don't assume in-memory state persists.
- **`LinearWavyProgressIndicator` amplitude**: The M3 component applies its own internal spring animation to amplitude changes, making external dynamic values unresponsive. Use fixed values.

## Conventions

- Keep responses concise
