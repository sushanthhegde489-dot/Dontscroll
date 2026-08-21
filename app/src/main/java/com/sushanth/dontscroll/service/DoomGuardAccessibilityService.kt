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
     * The package that the user explicitly unlocked.
     *
     * Example:
     *
     * Instagram -> Continue
     *
     * allowedPackage = Instagram
     */
    @Volatile
    private var allowedPackage: String? = null

    /*
     * Last real application package observed in the foreground.
     *
     * System/resolver packages are deliberately ignored,
     * so they don't interfere with this state.
     */
    @Volatile
    private var lastForegroundPackage: String? = null

    /*
     * When the temporarily unlocked app was last exited.
     *
     * This is used for the short grace period.
     */
    @Volatile
    private var lastAllowedPackageExitTime = 0L

    /*
     * Prevent duplicate intervention launches caused by
     * multiple accessibility events.
     */
    private var lastInterventionPackage: String? = null

    private var lastInterventionTime = 0L


    override fun onServiceConnected() {

        super.onServiceConnected()

        /*
         * Restore the temporary unlock if the service
         * process was recreated.
         */
        allowedPackage =
            readAllowedPackage()

        /*
         * We don't know the actual current foreground app
         * when the service connects.
         */
        lastForegroundPackage = null

        lastAllowedPackageExitTime = 0L
    }


    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {

        if (event == null) {
            return
        }

        /*
         * Only react to foreground/window changes.
         */
        if (
            event.eventType !=
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            return
        }

        val packageName =
            event.packageName
                ?.toString()
                ?: return


        /*
         * Ignore our own application.
         *
         * This includes InterventionActivity.
         */
        if (
            packageName ==
            applicationContext.packageName
        ) {
            return
        }


        /*
         * Ignore temporary Android/system windows.
         *
         * This is particularly important for cloned apps
         * and Android's application resolver.
         */
        if (
            isTransientSystemPackage(
                packageName
            )
        ) {
            return
        }


        /*
         * Synchronize with persisted state.
         */
        allowedPackage =
            readAllowedPackage()

        val currentAllowed =
            allowedPackage


        /*
         * =====================================================
         * CASE 1
         *
         * The user has a temporary unlock and we are still
         * inside that exact application.
         * =====================================================
         */
        if (
            packageName ==
            currentAllowed
        ) {

            /*
             * We have returned to the allowed app.
             *
             * If we were previously outside it, check whether
             * the return happened within the grace period.
             */
            if (
                lastAllowedPackageExitTime > 0L
            ) {

                val elapsed =
                    SystemClock.elapsedRealtime() -
                            lastAllowedPackageExitTime

                if (
                    elapsed <=
                    REOPEN_GRACE_PERIOD
                ) {

                    /*
                     * Returned quickly enough.
                     *
                     * Keep the unlock alive.
                     */
                    lastAllowedPackageExitTime = 0L

                    lastForegroundPackage =
                        packageName

                    return
                }

                /*
                 * Grace period expired.
                 *
                 * The user stayed away too long, so this
                 * return should require the intervention again.
                 */
                clearTemporaryUnlock()

                lastForegroundPackage =
                    packageName

                checkProtectedApp(
                    packageName
                )

                return
            }

            /*
             * Normal case:
             *
             * User is already inside the allowed application.
             */
            lastForegroundPackage =
                packageName

            return
        }


        /*
         * =====================================================
         * CASE 2
         *
         * The user just left the temporarily unlocked app.
         * =====================================================
         *
         * Example:
         *
         * Instagram
         *     ↓
         * YouTube
         *
         * We DO NOT immediately clear the Instagram unlock.
         *
         * Instead we start the grace period.
         */
        if (
            currentAllowed != null &&
            lastForegroundPackage ==
            currentAllowed &&
            packageName != currentAllowed
        ) {

            lastAllowedPackageExitTime =
                SystemClock.elapsedRealtime()
        }


        /*
         * Record the new real foreground package.
         */
        lastForegroundPackage =
            packageName


        /*
         * Check whether the newly foreground package
         * is protected.
         *
         * We deliberately do this even if another app was
         * temporarily unlocked.
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
                 * This app is not protected.
                 *
                 * Do not clear the temporary unlock.
                 *
                 * It could simply be:
                 *
                 * - Launcher
                 * - Settings
                 * - Chrome
                 * - Android resolver
                 * - another unprotected app
                 */
                if (
                    blocked == null
                ) {
                    return@launch
                }


                /*
                 * Get the latest persisted unlock.
                 */
                val currentAllowed =
                    readAllowedPackage()

                allowedPackage =
                    currentAllowed


                /*
                 * =================================================
                 * CASE:
                 *
                 * User returned to the same temporarily unlocked
                 * protected application.
                 * =================================================
                 */
                if (
                    packageName ==
                    currentAllowed
                ) {

                    val exitTime =
                        lastAllowedPackageExitTime

                    if (
                        exitTime == 0L
                    ) {
                        return@launch
                    }


                    val elapsed =
                        SystemClock.elapsedRealtime() -
                                exitTime


                    /*
                     * Returned within the grace period.
                     */
                    if (
                        elapsed <=
                        REOPEN_GRACE_PERIOD
                    ) {

                        lastAllowedPackageExitTime =
                            0L

                        lastForegroundPackage =
                            packageName

                        return@launch
                    }


                    /*
                     * Grace period expired.
                     *
                     * Clear the old unlock and continue so that
                     * a new intervention is displayed.
                     */
                    clearTemporaryUnlock()
                }


                /*
                 * =================================================
                 * CASE:
                 *
                 * User opened a DIFFERENT protected application.
                 * =================================================
                 *
                 * Example:
                 *
                 * Instagram was unlocked.
                 *
                 * User opens YouTube.
                 *
                 * YouTube is protected.
                 *
                 * Instagram's temporary unlock is no longer
                 * relevant.
                 */
                if (
                    currentAllowed != null &&
                    packageName != currentAllowed
                ) {

                    clearTemporaryUnlock()
                }


                /*
                 * Duplicate accessibility-event protection.
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

                    return@launch
                }


                /*
                 * Final persisted-state check.
                 *
                 * This protects against races where Continue
                 * was pressed while this coroutine was running.
                 */
                val finalAllowed =
                    readAllowedPackage()


                allowedPackage =
                    finalAllowed


                if (
                    packageName ==
                    finalAllowed
                ) {

                    return@launch
                }


                /*
                 * Record intervention launch.
                 */
                lastInterventionPackage =
                    packageName

                lastInterventionTime =
                    now


                val intent =
                    Intent(
                        this@DoomGuardAccessibilityService,
                        InterventionActivity::class.java
                    ).apply {

                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )

                        putExtra(
                            InterventionActivity
                                .EXTRA_PACKAGE_NAME,

                            blocked.packageName
                        )

                        putExtra(
                            InterventionActivity
                                .EXTRA_DISPLAY_NAME,

                            blocked.displayName
                        )

                        putExtra(
                            InterventionActivity
                                .EXTRA_DELAY_SECONDS,

                            blocked.unlockDelaySeconds
                        )
                    }


                startActivity(
                    intent
                )

            } catch (e: Exception) {

                e.printStackTrace()
            }
        }
    }


    /*
     * Determines whether a package represents a temporary
     * system/resolver window rather than a real application.
     *
     * These packages must NOT count as the user leaving the
     * protected application.
     */
    private fun isTransientSystemPackage(
        packageName: String
    ): Boolean {

        return when {

            packageName ==
                    "android" -> true

            packageName ==
                    "com.android.systemui" -> true

            packageName ==
                    "com.google.android.permissioncontroller" -> true

            packageName ==
                    "com.android.permissioncontroller" -> true

            packageName ==
                    "com.google.android.packageinstaller" -> true

            packageName ==
                    "com.android.packageinstaller" -> true

            packageName ==
                    "com.android.intentresolver" -> true

            packageName ==
                    "android.ext.services" -> true

            else -> false
        }
    }


    /*
     * Read the current temporary unlock.
     */
    private fun readAllowedPackage(): String? {

        return getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
            .getString(
                KEY_ALLOWED_PACKAGE,
                null
            )
    }


    /*
     * Clear the temporary unlock.
     */
    private fun clearTemporaryUnlock() {

        allowedPackage = null

        lastAllowedPackageExitTime = 0L

        getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
            .edit()
            .remove(
                KEY_ALLOWED_PACKAGE
            )
            .commit()


        /*
         * Reset duplicate-intervention state.
         */
        lastInterventionPackage =
            null

        lastInterventionTime =
            0L
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
         * Duplicate accessibility-event protection.
         *
         * 1500 ms = 1.5 seconds.
         *
         * This is NOT the reopen grace period.
         */
        private const val INTERVENTION_COOLDOWN =
            1500L


        /*
         * How long the user can leave an unlocked app
         * and return without seeing the intervention again.
         *
         * 5000 ms = 5 seconds.
         */
        private const val REOPEN_GRACE_PERIOD =
            3000L
    }
}