# HCGateway

Android app (Jetpack Compose + Kotlin, Hilt, MVVM) under `app/` that syncs Health Connect data to a self-hosted Flask API under `api/`.

See @README.md for the project overview. Dependencies live in `app/gradle/libs.versions.toml`.

## Build

- **Build & install**: `./gradlew installDebug` (from `app/`, auto-launches the app)
- **Clean build**: `./gradlew clean installDebug` — needed when code changes aren't reflected (`installDebug` may use cached artifacts)
- Requires a connected Android device with Health Connect installed.

## Design decisions (non-obvious)

- **Theme switching** uses `AppCompatDelegate`, not Compose state — instant, no recomposition delay.
- **OkHttp AuthInterceptor** auto-refreshes on **403** (this API returns 403, not 401).
- **Streaming sync**: `readRecordsPaged` feeds a Channel pipeline so reads and uploads overlap and memory stays bounded.
- **Idle notification** uses `NotificationManager.notify()` directly, *not* a foreground service — avoids Android 15's 6-hour `dataSync` foreground limit.
- **Reading data older than 30 days** requires `PERMISSION_READ_HEALTH_DATA_HISTORY`.
- **UI** targets M3 Expressive (wavy progress, spring motion, expressive shapes).
- **Sentry is opt-in**: `io.sentry.auto-init=false` in the manifest, so `HCGatewayApp.initSentry` starts the SDK only when the in-app toggle (`sentryEnabled`) is on — gating both errors and Release Health sessions. DSN and `io.sentry.environment` are manifest meta-data (the latter from the `sentryEnvironment` manifestPlaceholder per buildType) so they're set before auto session tracking begins — setting `environment` only in `options` is too late and sessions get tagged `production`. The DSN is a public client key, safe to commit. The Sentry Gradle plugin uploads mappings/source on **release only** (`ignoredBuildTypes = ["debug"]`); its auth token lives in the gitignored `app/sentry.properties`.

## Sync

- **fullSync**: all record types in parallel via `async(Dispatchers.IO)`; each streams pages of 1000 records through a Channel.
- **deltaSync**: uses a Changes API token to sync only records modified since last sync.
- **Cancel**: a `@Volatile cancelled` flag stops async coroutines from overwriting the Cancelled state; reader/consumer loops call `ensureActive()`.
- **Force Sync**: date-range picker; consumes the Changes API token on completion to clear New counts.
- **WorkManager**: periodic sync with a 75% interval guard against duplicate runs on foreground resume. Minimum interval is 15 min (WorkManager constraint).

## Gotchas

- **MindfulnessSession** is experimental and unsupported on Samsung. Including it in a `ChangesTokenRequest` throws a `SecurityException` that silently blocks token persistence — filter unsupported types via `getGrantedPermissions()`.
- **Samsung Health** writes to Health Connect in ~1-hour batches, not real-time.
- **Process recreation**: Android may kill the background process; ViewModels reset and in-memory state (e.g. `_serverCounts`) becomes null. Don't assume it persists.
- **`LinearWavyProgressIndicator` amplitude**: the M3 component runs its own internal spring on amplitude, so external dynamic values don't take effect — use fixed values.
- **logcat PID changes** after reinstall or process recreation — confirm the PID matches the current process.
- **Adding a record type** touches 4 files: `RecordTypes.kt`, `RecordSerializer.kt`, `AndroidManifest.xml` (READ/WRITE permissions), and `HealthConnectRepository.kt` (getChangesToken filter if experimental).

## Conventions

- Keep responses concise.
