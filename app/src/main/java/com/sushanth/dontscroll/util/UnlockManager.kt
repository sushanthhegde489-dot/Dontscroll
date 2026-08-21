package com.sushanth.dontscroll.util

import android.content.Context
import com.sushanth.dontscroll.data.AppDatabase
import com.sushanth.dontscroll.data.BlockedApp

object UnlockManager {

    private const val PREFS_NAME = "dontscroll_unlocks"

    private fun prefs(context: Context) =
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    /**
     * Starts a fresh unlock timer for an app.
     *
     * Every time the intervention screen is triggered,
     * a new timer is created.
     */
    fun startTimer(
        context: Context,
        packageName: String,
        delaySeconds: Long
    ) {

        val unlockUntil =
            System.currentTimeMillis() +
                    delaySeconds * 1000L

        prefs(context)
            .edit()
            .putLong(
                packageName,
                unlockUntil
            )
            .apply()
    }

    /**
     * Returns the timestamp at which the app becomes
     * available.
     */
    fun getUnlockUntil(
        context: Context,
        packageName: String
    ): Long {

        return prefs(context)
            .getLong(packageName, 0L)
    }

    /**
     * Returns remaining seconds.
     */
    fun getRemainingSeconds(
        context: Context,
        packageName: String
    ): Long {

        val unlockUntil =
            getUnlockUntil(
                context,
                packageName
            )

        if (unlockUntil <= 0L) {
            return 0L
        }

        val remainingMillis =
            unlockUntil -
                    System.currentTimeMillis()

        if (remainingMillis <= 0L) {
            return 0L
        }

        return (remainingMillis + 999L) / 1000L
    }

    /**
     * Whether the app currently has an active unlock.
     */
    fun isUnlocked(
        context: Context,
        packageName: String
    ): Boolean {

        val unlockUntil =
            getUnlockUntil(
                context,
                packageName
            )

        return unlockUntil > System.currentTimeMillis()
    }

    /**
     * Clears an existing timer.
     */
    fun clear(
        context: Context,
        packageName: String
    ) {

        prefs(context)
            .edit()
            .remove(packageName)
            .apply()
    }

    /**
     * Returns the protected app from Room.
     *
     * This is intentionally suspend because Room database
     * queries must not run on the main thread.
     */
    suspend fun getBlockedApp(
        context: Context,
        packageName: String
    ): BlockedApp? {

        return AppDatabase
            .getInstance(context)
            .blockedAppDao()
            .getByPackage(packageName)
    }
}