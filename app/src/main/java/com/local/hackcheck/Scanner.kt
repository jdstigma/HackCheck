package com.local.hackcheck

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings

enum class RiskLevel { HIGH, MEDIUM, INFO }

data class Finding(
    val level: RiskLevel,
    val title: String,
    val detail: String,
)

data class ScanResult(
    val findings: List<Finding>,
    val appsScanned: Int,
)

data class AppRow(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val hasLauncherIcon: Boolean,
    val versionName: String?,
    val firstInstallTimeMillis: Long,
    val lastUpdateTimeMillis: Long,
    val grantedDangerousPermissions: List<String>,
    val ignoringBatteryOptimizations: Boolean,
    val knownStalkerwareMatch: Boolean,
    val dualUseMonitoringMatch: Boolean,
)

private val DANGEROUS_PERMISSIONS = setOf(
    "android.permission.CAMERA",
    "android.permission.RECORD_AUDIO",
    "android.permission.READ_SMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.SEND_SMS",
    "android.permission.READ_CALL_LOG",
    "android.permission.WRITE_CALL_LOG",
    "android.permission.PROCESS_OUTGOING_CALLS",
    "android.permission.READ_CONTACTS",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_BACKGROUND_LOCATION",
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.READ_PHONE_STATE",
    "android.permission.PACKAGE_USAGE_STATS",
)

// Best-effort, publicly documented package identifiers associated with consumer
// stalkerware/spyware. NOT exhaustive -- absence of a match here does NOT mean
// a device is clean. Add to this list as you research further; several known
// stalkerware families deliberately rotate/obfuscate their package names.
private val KNOWN_STALKERWARE_PACKAGES = setOf(
    "com.flexispy.android",
    "com.hoverwatch.rem",
    "com.mobile.spy",
    "com.mspyagent",
    "com.google.services",          // documented alias used by TheTruthSpy-family apps
    "com.system.update.service",    // generic disguise name seen in stalkerware reporting
    "com.android.protect",
    "com.transfer.wsb",
)

// Legitimate apps that CAN be used for covert monitoring of another person.
// Not malware -- flagged separately so a genuine, disclosed use isn't confused
// with the stalkerware list above.
private val DUAL_USE_MONITORING_PACKAGES = setOf(
    "com.familysafe.production",
    "com.wondershare.famisafe",
    "com.life360.android.safetymapd",
    "com.google.android.apps.kids.familylink",
    "com.eset.parentalcontrol",
    "com.mmguardian.parentapp",
)

fun runScan(context: Context): ScanResult {
    val pm = context.packageManager
    val findings = mutableListOf<Finding>()

    @Suppress("DEPRECATION")
    val packages: List<PackageInfo> = try {
        pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
    } catch (e: Exception) {
        emptyList()
    }

    for (pkg in packages) {
        val appInfo = pkg.applicationInfo ?: continue
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val label = pm.getApplicationLabel(appInfo).toString()
        val pkgName = pkg.packageName

        val grantedDangerous = grantedDangerousPermissions(pkg)
        val hasLauncherIcon = pm.getLaunchIntentForPackage(pkgName) != null

        if (KNOWN_STALKERWARE_PACKAGES.contains(pkgName)) {
            findings += Finding(
                RiskLevel.HIGH,
                "Known-stalkerware package match: $label",
                "$pkgName matches a publicly documented spyware package name. " +
                    "This list is not exhaustive -- treat a non-match as inconclusive, not clean.",
            )
        }

        if (DUAL_USE_MONITORING_PACKAGES.contains(pkgName)) {
            findings += Finding(
                RiskLevel.MEDIUM,
                "Monitoring-capable app installed: $label",
                "$pkgName is a legitimate app that can also be used for covert monitoring. " +
                    "Confirm this was installed with your knowledge and consent.",
            )
        }

        if (!isSystem && !hasLauncherIcon) {
            val level = if (grantedDangerous.isNotEmpty()) RiskLevel.HIGH else RiskLevel.MEDIUM
            val permNote = if (grantedDangerous.isNotEmpty())
                ", and holds: ${grantedDangerous.joinToString()}."
            else "."
            findings += Finding(
                level,
                "Hidden app (no home-screen icon): $label",
                "$pkgName is installed but has no launcher icon$permNote",
            )
        } else if (!isSystem && grantedDangerous.size >= 3) {
            findings += Finding(
                RiskLevel.MEDIUM,
                "App with broad sensitive permissions: $label",
                "$pkgName holds: ${grantedDangerous.joinToString()}.",
            )
        }
    }

    // Active accessibility services -- powerful (can read screen content and
    // simulate input); a common vector for both legitimate accessibility tools
    // and stalkerware. Always surfaced for manual review, not auto-scored.
    val enabledAccessibility = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    )
    enabledAccessibility?.split(":")?.filter { it.isNotBlank() }?.forEach { component ->
        findings += Finding(
            RiskLevel.INFO,
            "Active accessibility service",
            "$component -- can read screen content and simulate taps/typing. " +
                "Confirm this is one you recognize and intentionally enabled.",
        )
    }

    // Device admins + device/profile owner status
    try {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.activeAdmins?.forEach { admin ->
            findings += Finding(
                RiskLevel.INFO,
                "Active device admin",
                "${admin.flattenToShortString()} -- device admins can enforce policies, " +
                    "lock, or wipe the device. A common legitimate entry is Find My Device.",
            )
        }

        for (pkg in packages) {
            val pkgName = pkg.packageName
            val isOwner = try { dpm.isDeviceOwnerApp(pkgName) } catch (e: Exception) { false }
            val isProfileOwner = try { dpm.isProfileOwnerApp(pkgName) } catch (e: Exception) { false }
            if (isOwner || isProfileOwner) {
                val label = pkg.applicationInfo?.let { pm.getApplicationLabel(it).toString() } ?: pkgName
                findings += Finding(
                    RiskLevel.HIGH,
                    "Device/profile owner app: $label",
                    "$pkgName has ${if (isOwner) "Device Owner" else "Profile Owner"} status -- " +
                        "this grants broad remote-management control over the device. Unusual outside " +
                        "a corporate-managed phone.",
                )
            }
        }
    } catch (e: Exception) {
        // Best-effort only -- some of this API surface can be restricted on some builds.
    }

    return ScanResult(findings.sortedBy { it.level.ordinal }, packages.size)
}

// Full per-app raw dump, richer than the curated findings list -- meant for pulling off
// the device and analyzing separately (adb pull the exported CSV to a computer).
fun rawInventory(context: Context): List<AppRow> {
    val pm = context.packageManager
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    @Suppress("DEPRECATION")
    val packages: List<PackageInfo> = try {
        pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
    } catch (e: Exception) {
        emptyList()
    }

    return packages.mapNotNull { pkg ->
        val appInfo = pkg.applicationInfo ?: return@mapNotNull null
        val pkgName = pkg.packageName
        val ignoringOptimizations = try {
            powerManager?.isIgnoringBatteryOptimizations(pkgName) ?: false
        } catch (e: Exception) {
            false
        }
        AppRow(
            packageName = pkgName,
            label = pm.getApplicationLabel(appInfo).toString(),
            isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            hasLauncherIcon = pm.getLaunchIntentForPackage(pkgName) != null,
            versionName = pkg.versionName,
            firstInstallTimeMillis = pkg.firstInstallTime,
            lastUpdateTimeMillis = pkg.lastUpdateTime,
            grantedDangerousPermissions = grantedDangerousPermissions(pkg),
            ignoringBatteryOptimizations = ignoringOptimizations,
            knownStalkerwareMatch = KNOWN_STALKERWARE_PACKAGES.contains(pkgName),
            dualUseMonitoringMatch = DUAL_USE_MONITORING_PACKAGES.contains(pkgName),
        )
    }
}

private fun grantedDangerousPermissions(pkg: PackageInfo): List<String> {
    val perms = pkg.requestedPermissions ?: return emptyList()
    val flags = pkg.requestedPermissionsFlags ?: return emptyList()
    val result = mutableListOf<String>()
    for (i in perms.indices) {
        if (i >= flags.size) break
        val granted = (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
        if (granted && DANGEROUS_PERMISSIONS.contains(perms[i])) {
            result += perms[i].removePrefix("android.permission.")
        }
    }
    return result
}
