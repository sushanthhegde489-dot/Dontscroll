package com.sushanth.dontscroll.util

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
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
     * Checks whether the user has enabled
     * Usage Access for Dontscroll.
     */
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

        return mode ==
                AppOpsManager.MODE_ALLOWED
    }

    /**
     * Opens Android Usage Access settings.
     */
    fun openUsageSettings(
        context: Context
    ) {

        val intent =
            Intent(
                Settings.ACTION_USAGE_ACCESS_SETTINGS
            )

        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
        )

        context.startActivity(intent)
    }

    /**
     * Returns today's application usage.
     *
     * This method first checks the special Usage Access
     * setting. If the user has not enabled it, an empty
     * list is returned.
     */
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

        val stats =
            queryUsageStats(
                usageStatsManager,
                startTime,
                endTime
            )

        return stats
            .asSequence()
            .filter {
                it.totalTimeInForeground > 0L
            }
            .map {
                AppUsage(
                    packageName =
                        it.packageName,

                    totalTimeMillis =
                        it.totalTimeInForeground
                )
            }
            .toList()
    }

    /**
     * Android's UsageStats API is protected by the
     * special Usage Access setting.
     *
     * hasUsageAccess() is checked before this function
     * is called, so suppress the normal runtime-permission
     * Lint warning here.
     */
    @SuppressLint("MissingPermission")
    private fun queryUsageStats(
        usageStatsManager: UsageStatsManager,
        startTime: Long,
        endTime: Long
    ) =
        usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        ) ?: emptyList()

    /**
     * Returns today's usage for one package.
     */
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

    /**
     * Converts milliseconds to readable screen time.
     */
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