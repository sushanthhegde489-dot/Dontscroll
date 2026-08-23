package com.sushanth.dontscroll.util

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
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

    fun hasUsageAccess(
        context: Context
    ): Boolean {

        val appOps =
            context.getSystemService(
                Context.APP_OPS_SERVICE
            ) as AppOpsManager

        val mode =
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )

        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageSettings(
        context: Context
    ) {

        val intent =
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
        )

        context.startActivity(intent)
    }

    /**
     * Returns only apps that actually entered the foreground today.
     *
     * UsageStats.queryUsageStats() can contain packages that aren't
     * meaningful user-facing foreground apps, so this implementation
     * reconstructs usage from foreground/background events instead.
     */
    @SuppressLint("MissingPermission")
    fun getTodayUsage(
        context: Context
    ): List<AppUsage> {

        if (!hasUsageAccess(context)) {
            return emptyList()
        }

        val usageStatsManager =
            context.getSystemService(
                Context.USAGE_STATS_SERVICE
            ) as UsageStatsManager

        val calendar =
            Calendar.getInstance()

        calendar.set(
            Calendar.HOUR_OF_DAY,
            0
        )

        calendar.set(
            Calendar.MINUTE,
            0
        )

        calendar.set(
            Calendar.SECOND,
            0
        )

        calendar.set(
            Calendar.MILLISECOND,
            0
        )

        val startTime =
            calendar.timeInMillis

        val endTime =
            System.currentTimeMillis()

        val events =
            usageStatsManager.queryEvents(
                startTime,
                endTime
            )

        val event =
            UsageEvents.Event()

        /*
         * package -> timestamp when it entered foreground
         */
        val foregroundStarts =
            mutableMapOf<String, Long>()

        /*
         * package -> accumulated foreground duration
         */
        val usage =
            mutableMapOf<String, Long>()

        while (events.hasNextEvent()) {

            events.getNextEvent(event)

            val packageName =
                event.packageName

            if (packageName.isNullOrBlank()) {
                continue
            }

            when (event.eventType) {

                UsageEvents.Event.ACTIVITY_RESUMED -> {

                    /*
                     * If another RESUMED event arrives for the same
                     * package before it was paused, don't overwrite
                     * the original start time.
                     */
                    if (!foregroundStarts.containsKey(packageName)) {
                        foregroundStarts[packageName] =
                            event.timeStamp
                    }
                }

                UsageEvents.Event.ACTIVITY_PAUSED -> {

                    val start =
                        foregroundStarts.remove(packageName)

                    if (start != null) {

                        val duration =
                            (event.timeStamp - start)
                                .coerceAtLeast(0L)

                        usage[packageName] =
                            (usage[packageName] ?: 0L) +
                                    duration
                    }
                }

                /*
                 * Android can report these depending on the device /
                 * Android version. Treat them as foreground transitions.
                 */
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {

                    if (!foregroundStarts.containsKey(packageName)) {
                        foregroundStarts[packageName] =
                            event.timeStamp
                    }
                }

                UsageEvents.Event.MOVE_TO_BACKGROUND -> {

                    val start =
                        foregroundStarts.remove(packageName)

                    if (start != null) {

                        val duration =
                            (event.timeStamp - start)
                                .coerceAtLeast(0L)

                        usage[packageName] =
                            (usage[packageName] ?: 0L) +
                                    duration
                    }
                }
            }
        }

        /*
         * If an app is still in the foreground when the query ends,
         * close its session at "now".
         */
        foregroundStarts.forEach { (packageName, start) ->

            val duration =
                (endTime - start)
                    .coerceAtLeast(0L)

            usage[packageName] =
                (usage[packageName] ?: 0L) +
                        duration
        }

        return usage
            .asSequence()
            .filter {
                it.value > 0L
            }
            .sortedByDescending {
                it.value
            }
            .map {
                AppUsage(
                    packageName = it.key,
                    totalTimeMillis = it.value
                )
            }
            .toList()
    }

    fun getAppTodayUsage(
        context: Context,
        packageName: String
    ): Long {

        if (!hasUsageAccess(context)) {
            return 0L
        }

        return getTodayUsage(context)
            .firstOrNull {
                it.packageName == packageName
            }
            ?.totalTimeMillis
            ?: 0L
    }

    fun formatDuration(
        millis: Long
    ): String {

        val totalSeconds =
            millis / 1000L

        val hours =
            totalSeconds / 3600L

        val minutes =
            (totalSeconds % 3600L) / 60L

        val seconds =
            totalSeconds % 60L

        return when {

            hours > 0L ->
                String.format(
                    Locale.US,
                    "%dh %02dm",
                    hours,
                    minutes
                )

            minutes > 0L ->
                String.format(
                    Locale.US,
                    "%dm %02ds",
                    minutes,
                    seconds
                )

            else ->
                String.format(
                    Locale.US,
                    "%ds",
                    seconds
                )
        }
    }
}