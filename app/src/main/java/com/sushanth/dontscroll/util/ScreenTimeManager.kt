package com.sushanth.dontscroll.util

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.util.Calendar
import java.util.Locale

object ScreenTimeManager {

    data class AppUsage(
        val packageName: String,
        val totalTimeMillis: Long
    )

    /**
     * Checks whether this application has Usage Access permission.
     */
    fun hasUsageAccess(
        context: Context
    ): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Opens Android Usage Access settings safely.
     */
    fun openUsageSettings(
        context: Context
    ) {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    /**
     * Returns today's actual foreground/on-screen usage.
     * Reconstructs foreground sessions from UsageEvents using ACTIVITY_RESUMED and ACTIVITY_PAUSED.
     */
    fun getTodayUsage(
        context: Context
    ): List<AppUsage> {
        if (!hasUsageAccess(context)) {
            return emptyList()
        }

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()

        val startTime = getStartOfTodayMillis()
        val endTime = System.currentTimeMillis()

        val events = usageStatsManager.queryEvents(startTime, endTime) ?: return emptyList()

        if (!events.hasNextEvent()) {
            return emptyList()
        }

        val usageByPackage = calculateForegroundUsage(
            events = events,
            startTime = startTime,
            endTime = endTime
        )

        val packageManager = context.packageManager
        val launcherPackage = getDefaultLauncherPackage(context)

        return usageByPackage
            .asSequence()
            .filter { (packageName, time) ->
                packageName.isNotBlank() && time > 0L
            }
            .filter { (packageName, _) ->
                packageName != context.packageName
            }
            .filter { (packageName, _) ->
                packageName != launcherPackage
            }
            .filter { (packageName, _) ->
                isRealUserFacingApp(
                    context = context,
                    packageName = packageName
                )
            }
            .mapNotNull { (packageName, time) ->
                try {
                    packageManager.getApplicationInfo(packageName, 0)
                    AppUsage(
                        packageName = packageName,
                        totalTimeMillis = time
                    )
                } catch (_: Exception) {
                    null
                }
            }
            .sortedByDescending {
                it.totalTimeMillis
            }
            .toList()
    }

    /**
     * Returns today's actual foreground usage for one package.
     * Optimized to avoid querying all installed packages.
     */
    fun getAppTodayUsage(
        context: Context,
        packageName: String
    ): Long {
        if (packageName.isBlank() || !hasUsageAccess(context)) {
            return 0L
        }

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return 0L

        val startTime = getStartOfTodayMillis()
        val endTime = System.currentTimeMillis()

        val events = usageStatsManager.queryEvents(startTime, endTime) ?: return 0L

        if (!events.hasNextEvent()) {
            return 0L
        }

        val usageByPackage = calculateForegroundUsage(
            events = events,
            startTime = startTime,
            endTime = endTime
        )

        return usageByPackage[packageName] ?: 0L
    }

    /**
     * Reconstructs actual foreground sessions.
     * We count time only between ACTIVITY_RESUMED -> ACTIVITY_PAUSED.
     */
    private fun calculateForegroundUsage(
        events: UsageEvents,
        startTime: Long,
        endTime: Long
    ): Map<String, Long> {
        val usageByPackage = mutableMapOf<String, Long>()
        val activePackages = mutableMapOf<String, Long>()
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)

            val packageName = event.packageName
            if (packageName.isNullOrBlank()) {
                continue
            }

            val timestamp = event.timeStamp
            if (timestamp < startTime || timestamp > endTime) {
                continue
            }

            when (getEventType(event)) {
                EVENT_RESUMED -> {
                    if (!activePackages.containsKey(packageName)) {
                        activePackages[packageName] = timestamp
                    }
                }

                EVENT_PAUSED,
                EVENT_STOPPED -> {
                    val resumedAt = activePackages.remove(packageName)
                    if (resumedAt != null) {
                        val duration = (timestamp - resumedAt).coerceAtLeast(0L)
                        usageByPackage[packageName] = (usageByPackage[packageName] ?: 0L) + duration
                    }
                }
            }
        }

        for ((packageName, resumedAt) in activePackages) {
            val duration = (endTime - resumedAt).coerceAtLeast(0L)
            usageByPackage[packageName] = (usageByPackage[packageName] ?: 0L) + duration
        }

        return usageByPackage
    }

    @Suppress("DEPRECATION")
    private fun getEventType(
        event: UsageEvents.Event
    ): Int {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ->
                EVENT_RESUMED

            event.eventType == UsageEvents.Event.ACTIVITY_PAUSED ->
                EVENT_PAUSED

            event.eventType == UsageEvents.Event.ACTIVITY_STOPPED ->
                EVENT_STOPPED

            event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ->
                EVENT_RESUMED

            event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND ->
                EVENT_PAUSED

            else ->
                EVENT_NONE
        }
    }

    private fun isRealUserFacingApp(
        context: Context,
        packageName: String
    ): Boolean {
        val packageManager = context.packageManager

        val applicationInfo = try {
            packageManager.getApplicationInfo(packageName, 0)
        } catch (_: Exception) {
            return false
        }

        if (!hasLauncherActivity(context, packageName)) {
            return false
        }

        if (packageName == "android") {
            return false
        }

        val isSystemApp = (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val isUpdatedSystemApp = (applicationInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

        if (isSystemApp && !isUpdatedSystemApp) {
            if (!hasLauncherActivity(context, packageName)) {
                return false
            }
        }

        return true
    }

    private fun hasLauncherActivity(
        context: Context,
        packageName: String
    ): Boolean {
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
        }

        return try {
            val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, 0)
            }
            activities.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private fun getDefaultLauncherPackage(
        context: Context
    ): String? {
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }

        val resolveInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.resolveActivity(
                    intent,
                    PackageManager.ResolveInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.resolveActivity(intent, 0)
            }
        } catch (_: Exception) {
            null
        } ?: return null

        return resolveInfo.activityInfo?.packageName?.takeIf {
            it.isNotBlank()
        }
    }

    private fun getStartOfTodayMillis(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun formatDuration(
        millis: Long
    ): String {
        val totalSeconds = millis.coerceAtLeast(0L).div(1000L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L

        return when {
            hours > 0L -> String.format(Locale.US, "%dh %02dm", hours, minutes)
            minutes > 0L -> String.format(Locale.US, "%dm %02ds", minutes, seconds)
            else -> String.format(Locale.US, "%ds", seconds)
        }
    }

    private const val EVENT_NONE = 0
    private const val EVENT_RESUMED = 1
    private const val EVENT_PAUSED = 2
    private const val EVENT_STOPPED = 3
}