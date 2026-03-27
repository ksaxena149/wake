# ADR-001: Nearby over BLE

Status: Accepted
Date: 2026-03-28

## Context

WAKE needs a phone-to-phone transport layer that works across varied Android devices. A low-level stack based on raw BLE advertising, BLE scanning, GATT server/client, and WifiP2pManager would require significant device-specific handling and long stabilization time.

## Decision

Use Google Nearby Connections API instead of raw BLE advertising, BLE scanning, GATT server/client, and WifiP2pManager.

## Consequences

This removes approximately 8 weeks of device-inconsistent low-level transport implementation work and allows faster delivery of core protocol features.

The tradeoff is that WAKE now requires Google Play Services on all Android devices.
