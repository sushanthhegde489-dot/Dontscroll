package com.sushanth.dontscroll.util

import android.content.Context

object UnlockSessionManager {

    private const val PREFS_NAME =
        "dontscroll_unlock_session"

    private const val KEY_PACKAGE =
        "unlocked_package"

    fun markUnlocked(
        context: Context,
        packageName: String
    ) {

        context
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            .edit()
            .putString(
                KEY_PACKAGE,
                packageName
            )
            .apply()
    }

    fun getUnlockedPackage(
        context: Context
    ): String? {

        return context
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            .getString(
                KEY_PACKAGE,
                null
            )
    }

    fun clear(
        context: Context
    ) {

        context
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            .edit()
            .remove(KEY_PACKAGE)
            .apply()
    }
}