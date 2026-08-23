package com.sushanth.dontscroll.util

import android.content.Context

object BlockingManager {

    private const val PREFS_NAME =
        "dontscroll_blocking"

    private const val KEY_BLOCKING_ENABLED =
        "blocking_enabled"

    private const val KEY_BREAK_UNTIL =
        "break_until"


    private fun prefs(
        context: Context
    ) =
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )


    /*
     * =========================================================
     * GLOBAL BLOCKING
     * =========================================================
     *
     * Default is ON.
     */

    fun isBlockingEnabled(
        context: Context
    ): Boolean {

        return prefs(context)
            .getBoolean(
                KEY_BLOCKING_ENABLED,
                true
            )
    }


    fun setBlockingEnabled(
        context: Context,
        enabled: Boolean
    ) {

        prefs(context)
            .edit()
            .putBoolean(
                KEY_BLOCKING_ENABLED,
                enabled
            )
            .apply()
    }


    /*
     * =========================================================
     * BREAK
     * =========================================================
     */

    fun startBreak(
        context: Context,
        durationMillis: Long
    ) {

        if (durationMillis <= 0L) {
            return
        }

        val breakUntil =
            System.currentTimeMillis() +
                    durationMillis

        prefs(context)
            .edit()
            .putLong(
                KEY_BREAK_UNTIL,
                breakUntil
            )
            .apply()
    }


    fun endBreak(
        context: Context
    ) {

        prefs(context)
            .edit()
            .remove(
                KEY_BREAK_UNTIL
            )
            .apply()
    }


    fun getBreakUntil(
        context: Context
    ): Long {

        return prefs(context)
            .getLong(
                KEY_BREAK_UNTIL,
                0L
            )
    }


    fun isOnBreak(
        context: Context
    ): Boolean {

        val breakUntil =
            getBreakUntil(context)

        if (breakUntil <= 0L) {
            return false
        }

        if (
            System.currentTimeMillis() >=
            breakUntil
        ) {

            endBreak(context)

            return false
        }

        return true
    }


    /*
     * =========================================================
     * REMAINING BREAK
     * =========================================================
     */

    fun getRemainingBreakMillis(
        context: Context
    ): Long {

        val breakUntil =
            getBreakUntil(context)

        if (breakUntil <= 0L) {
            return 0L
        }

        val remaining =
            breakUntil -
                    System.currentTimeMillis()

        if (remaining <= 0L) {

            endBreak(context)

            return 0L
        }

        return remaining
    }


    /*
     * =========================================================
     * SINGLE BLOCKING GATE
     * =========================================================
     *
     * DoomGuardAccessibilityService should call this
     * immediately before launching an intervention.
     */

    fun shouldBlock(
        context: Context
    ): Boolean {

        if (
            !isBlockingEnabled(context)
        ) {

            return false
        }

        if (
            isOnBreak(context)
        ) {

            return false
        }

        return true
    }
}