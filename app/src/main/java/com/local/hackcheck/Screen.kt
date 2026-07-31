package com.local.hackcheck

enum class Screen(val title: String) {
    Home("HackCheck"),
    Scan("App Scan"),
    Network("Network & Devices"),
    Monitor("Background Monitoring"),
    Capture("Traffic Capture"),
    Tools("Network Tools"),
}

/**
 * Traffic Capture is fully built (relay logic, screen, export, analysis tooling) but hidden from
 * the UI: VpnService.protect() is confirmed broken on the test device by a currently-unpatched
 * Android 16 platform bug (same bug hitting Proton VPN/Mullvad/WireGuard/TunnelBear -- Google
 * issue tracker, filed since 2025-08, no fix as of 2026-07-30). Every socket our relay opens gets
 * routed back into our own tunnel instead of the real network. Not fixable in app code -- flip
 * this back to true once protect() is confirmed working again (device update, or validated on a
 * different, unaffected device).
 */
const val CAPTURE_FEATURE_ENABLED = false
