package com.sushanth.dontscroll.util

import android.content.Context

data class ActiveTimer(
    val packageName: String,
    val displayName: String,
    val startedAt: Long,
    val unlockAt: Long
)

object TimerStateStore {

    private const val PREFS = "dontscroll_timer_state"
    private const val KEY_PACKAGE = "package_name"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_STARTED_AT = "started_at"
    private const val KEY_UNLOCK_AT = "unlock_at"

    private fun prefs(context: Context) =
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

    fun start(
        context: Context,
        packageName: String,
        displayName: String,
        delaySeconds: Long
    ) {

        val now = System.currentTimeMillis()

        prefs(context)
            .edit()
            .putString(KEY_PACKAGE, packageName)
            .putString(KEY_DISPLAY_NAME, displayName)
            .putLong(KEY_STARTED_AT, now)
            .putLong(
                KEY_UNLOCK_AT,
                now + (delaySeconds * 1000L)
            )
            .apply()
    }

    fun get(
        context: Context
    ): ActiveTimer? {

        val p = prefs(context)

        val packageName =
            p.getString(KEY_PACKAGE, null)
                ?: return null

        val displayName =
            p.getString(
                KEY_DISPLAY_NAME,
                packageName
            ) ?: packageName

        val startedAt =
            p.getLong(KEY_STARTED_AT, 0L)

        val unlockAt =
            p.getLong(KEY_UNLOCK_AT, 0L)

        if (startedAt == 0L || unlockAt == 0L) {
            return null
        }

        return ActiveTimer(
            packageName = packageName,
            displayName = displayName,
            startedAt = startedAt,
            unlockAt = unlockAt
        )
    }

    fun remainingSeconds(
        context: Context
    ): Long {

        val timer = get(context)
            ?: return 0L

        val remaining =
            timer.unlockAt -
                System.currentTimeMillis()

        return maxOf(
            0L,
            remaining / 1000L
        )
    }

    fun isActive(
        context: Context
    ): Boolean {
        return get(context) != null
    }

    fun isFinished(
        context: Context
    ): Boolean {

        val timer = get(context)
            ?: return false

        return System.currentTimeMillis() >=
                timer.unlockAt
    }

    fun clear(
        context: Context
    ) {

        prefs(context)
            .edit()
            .clear()
            .apply()
    }

    fun isForPackage(
        context: Context,
        packageName: String
    ): Boolean {

        return get(context)
            ?.packageName == packageName
    }
}
