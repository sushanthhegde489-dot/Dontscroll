package com.sushanth.dontscroll.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.sushanth.dontscroll.data.AppDatabase
import com.sushanth.dontscroll.ui.InterventionActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch


class DoomGuardAccessibilityService :
    AccessibilityService() {

    private val scope =
        CoroutineScope(
            SupervisorJob() +
                    Dispatchers.IO
        )


    /*
     * The package that the user has explicitly unlocked
     * during the current foreground session.
     *
     * Example:
     *
     * Instagram -> Continue
     *
     * allowedPackage = Instagram
     *
     * Instagram remains usable.
     *
     * User opens YouTube.
     *
     * allowedPackage is cleared.
     *
     * User opens Instagram again.
     *
     * Intervention appears again.
     */
    private var allowedPackage: String? = null


    /*
     * Prevent extremely rapid duplicate launches.
     */
    private var lastInterventionPackage:
            String? = null

    private var lastInterventionTime =
        0L


    override fun onServiceConnected() {

        super.onServiceConnected()

        /*
         * Restore the current temporary unlock if the
         * service process was recreated.
         */
        allowedPackage =
            getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
                .getString(
                    KEY_ALLOWED_PACKAGE,
                    null
                )
    }


    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {

        if (event == null) {
            return
        }


        /*
         * We only care about foreground/window changes.
         */
        if (
            event.eventType !=
            AccessibilityEvent
                .TYPE_WINDOW_STATE_CHANGED
        ) {
            return
        }


        val packageName =
            event.packageName
                ?.toString()
                ?: return


        val prefs =
            getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )

        allowedPackage =
            prefs.getString(
                KEY_ALLOWED_PACKAGE,
                null
            )
        /*
         * Ignore our own application.
         */
        if (
            packageName ==
            applicationContext.packageName
        ) {
            return
        }


        /*
         * The user switched away from the app that
         * they previously unlocked.
         *
         * This ends the temporary unlock session.
         */
        if (
            allowedPackage != null &&
            packageName != allowedPackage
        ) {

            clearTemporaryUnlock()
        }


        /*
         * If this is the package that the user just
         * explicitly unlocked, allow it to remain open.
         */
        if (
            packageName ==
            allowedPackage
        ) {

            return
        }


        /*
         * Prevent duplicate events from immediately
         * launching multiple intervention activities.
         */
        val now =
            SystemClock.elapsedRealtime()

        if (
            packageName ==
            lastInterventionPackage &&
            now -
            lastInterventionTime <
            INTERVENTION_COOLDOWN
        ) {

            return
        }


        /*
         * Check the database asynchronously.
         */
        checkProtectedApp(
            packageName
        )
    }


    private fun checkProtectedApp(
        packageName: String
    ) {

        scope.launch {

            try {

                val database =
                    AppDatabase.getInstance(
                        applicationContext
                    )


                val blocked =
                    database
                        .blockedAppDao()
                        .getByPackage(
                            packageName
                        )


                /*
                 * App is not protected.
                 */
                if (blocked == null) {
                    return@launch
                }


                /*
                 * It may have become allowed between
                 * the event and this coroutine running.
                 */
                if (
                    packageName ==
                    allowedPackage
                ) {
                    return@launch
                }


                lastInterventionPackage =
                    packageName

                lastInterventionTime =
                    SystemClock.elapsedRealtime()


                val intent =
                    Intent(
                        this@DoomGuardAccessibilityService,
                        InterventionActivity::class.java
                    )


                intent.addFlags(

                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )


                intent.putExtra(

                    InterventionActivity
                        .EXTRA_PACKAGE_NAME,

                    blocked.packageName
                )


                intent.putExtra(

                    InterventionActivity
                        .EXTRA_DISPLAY_NAME,

                    blocked.displayName
                )


                intent.putExtra(

                    InterventionActivity
                        .EXTRA_DELAY_SECONDS,

                    blocked.unlockDelaySeconds
                )


                startActivity(intent)

            } catch (e: Exception) {

                e.printStackTrace()
            }
        }
    }


    /*
     * Clears the temporary permission to use the
     * previously unlocked application.
     */
    private fun clearTemporaryUnlock() {

        allowedPackage = null

        getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
            .edit()
            .remove(
                KEY_ALLOWED_PACKAGE
            )
            .apply()


        /*
         * Reset duplicate-intervention state so the
         * next protected app opening is evaluated
         * normally.
         */
        lastInterventionPackage = null

        lastInterventionTime = 0L
    }


    override fun onInterrupt() {
        // Nothing required.
    }


    override fun onDestroy() {

        scope.cancel()

        super.onDestroy()
    }


    companion object {

        private const val PREFS_NAME =
            "dontscroll_intervention"

        private const val KEY_ALLOWED_PACKAGE =
            "allowed_package"

        /*
         * Small safety window against duplicate
         * accessibility events.
         */
        private const val INTERVENTION_COOLDOWN =
            1500L
    }
}