---
description: Kotlin and Android conventions for the WAKE Android app
paths:
  - "android/**/*.kt"
  - "android/**/*.kts"
---

# Android Rules

## Stack

Kotlin, Jetpack Compose (UI), Room v3 (local DB), OkHttp (HTTP), Google Nearby Connections API (Phase 3+, `play-services-nearby:19.1.0`), Google Tink (Ed25519 signature verification). Foreground Service for always-on relay.

## Code Style

- `val` over `var`. Data classes for models. `when` over `if-else` chains.
- Coroutines for all async work: `Dispatchers.IO` for disk/network, `Dispatchers.Main` for UI.
- ViewModels: `<Screen>ViewModel`. Composables: `<Screen>Screen`. Keep composables small.
- Business logic belongs in ViewModel or Repository, never in Composables.

## Architecture

MVVM + Foreground Service:
- `data/` — Room entities, DAOs, `BundleStoreManager` (payload files in `context.filesDir`, LRU eviction at 500 MB cap)
- `service/` — `WakeService` (owns coroutine scope), `WakeHttpClient`, `ServerSyncManager`
- `ui/` — Compose screens and ViewModels

## Nearby Connections

- Service ID: `"com.wake.dtn"` — must match exactly on all peers.
- `Strategy.P2P_CLUSTER` for multi-peer mesh topology.
- Connection callbacks go in a dedicated `NearbyConnectionManager` class, not scattered in Activities.
- Always disconnect after bundle exchange completes.

## Bundle Storage

- Payload bytes: files in `context.filesDir`, named `{query_id}_{chunk_index}.bundle`.
- Metadata: Room `bundles` table. `seen_ids` table for deduplication.
- Eviction: TTL-expired first, then LRU when total storage exceeds 500 MB.

## Required Permissions

`ACCESS_FINE_LOCATION`, `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `NEARBY_WIFI_DEVICES` (API 33+), `POST_NOTIFICATIONS` (API 33+).

## Testing

- Always write `androidTest/` tests alongside implementation, mirroring the feature's package structure.
- Use `@RunWith(AndroidJUnit4::class)`. Use `ServiceTestRule` for `WakeService` tests.
- Physical device required — emulators cannot run Nearby or BLE.
- Source directory path must exactly match the Kotlin `package` declaration or UTP finds 0 tests.
