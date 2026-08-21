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
import kotlinx.coroutines.withContext

class DoomGuardAccessibilityService :
    AccessibilityService() {

    private val scope =
        CoroutineScope(
            SupervisorJob() +
                    Dispatchers.IO
        )

    /*
     * Package that was explicitly unlocked by Continue.
     */
    @Volatile
    private var allowedPackage: String? = null

    /*
     * Last REAL application package observed.
     *
     * We intentionally don't update this for Android/system
     * windows.
     */
    @Volatile
    private var lastForegroundPackage: String? = null

    /*
     * When the temporarily unlocked application was exited.
     */
    @Volatile
    private var lastAllowedPackageExitTime = 0L

    /*
     * Prevent repeated launches for the same accessibility event.
     */
    @Volatile
    private var lastInterventionPackage: String? = null

    @Volatile
    private var lastInterventionTime = 0L


    override fun onServiceConnected() {

        super.onServiceConnected()

        allowedPackage =
            readAllowedPackage()

        lastForegroundPackage =
            null

        lastAllowedPackageExitTime =
            0L

        lastInterventionPackage =
            null

        lastInterventionTime =
            0L
    }


    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {

        if (event == null) {
            return
        }

        /*
         * Only react to actual window/package changes.
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
         * This prevents InterventionActivity itself from
         * triggering the service.
         */
        if (
            packageName ==
            applicationContext.packageName
        ) {
            return
        }


        /*
         * Ignore temporary Android/system windows.
         */
        if (
            isTransientSystemPackage(
                packageName
            )
        ) {
            return
        }


        /*
         * IMPORTANT:
         *
         * Accessibility can send many window-state events
         * while navigating inside the same application.
         *
         * Example:
         *
         * Instagram
         * Instagram comments
         * Instagram keyboard
         * Instagram dialog
         *
         * These are still the same package.
         *
         * Do not treat them as leaving/re-entering the app.
         */
        if (
            packageName ==
            lastForegroundPackage
        ) {
            return
        }


        handleForegroundPackage(
            packageName
        )
    }


    private fun handleForegroundPackage(
        packageName: String
    ) {

        val previousPackage =
            lastForegroundPackage


        /*
         * Read the latest persisted state.
         */
        allowedPackage =
            readAllowedPackage()

        val currentAllowed =
            allowedPackage


        /*
         * =====================================================
         * ACTIVE INTERVENTION
         * =====================================================
         *
         * This MUST happen before normal allowed-package logic.
         *
         * Example:
         *
         * InterventionActivity
         *       ↓
         * user swipes to Instagram
         *       ↓
         * Instagram becomes foreground
         *
         * If the intervention is still active for Instagram,
         * immediately bring the intervention back.
         */
        val interventionActive =
            isInterventionActive()

        val interventionPackage =
            readInterventionPackage()


        if (
            interventionActive &&
            interventionPackage != null &&
            packageName ==
            interventionPackage
        ) {

            /*
             * Do NOT update lastForegroundPackage.
             *
             * Instagram is not supposed to become the logical
             * foreground application while its intervention
             * is active.
             */
            bringInterventionToFront(
                packageName
            )

            return
        }


        /*
         * Record the newly observed REAL application.
         */
        lastForegroundPackage =
            packageName


        /*
         * =====================================================
         * RECENTLY UNLOCKED APP
         * =====================================================
         *
         * If Continue just opened the app, ignore the immediate
         * accessibility event.
         *
         * The allowedPackage check below also protects us, but
         * this makes the transition more robust.
         */
        val recentUnlockPackage =
            readRecentUnlockPackage()

        val recentUnlockTime =
            readRecentUnlockTime()

        if (
            packageName ==
            recentUnlockPackage
        ) {

            val elapsed =
                SystemClock.elapsedRealtime() -
                        recentUnlockTime

            if (
                elapsed <=
                RECENT_UNLOCK_GRACE
            ) {

                lastAllowedPackageExitTime =
                    0L

                return
            }
        }


        /*
         * =====================================================
         * CURRENTLY ALLOWED APPLICATION
         * =====================================================
         */
        if (
            packageName ==
            currentAllowed
        ) {

            /*
             * We are returning to the temporarily unlocked
             * application.
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

                    lastAllowedPackageExitTime =
                        0L

                    return
                }


                /*
                 * User stayed away too long.
                 *
                 * Temporary unlock has expired.
                 */
                clearTemporaryUnlock()

            } else {

                /*
                 * Already inside the allowed application.
                 */
                return
            }
        }


        /*
         * =====================================================
         * LEFT TEMPORARILY UNLOCKED APPLICATION
         * =====================================================
         */
        if (
            currentAllowed != null &&
            previousPackage ==
            currentAllowed &&
            packageName != currentAllowed
        ) {

            lastAllowedPackageExitTime =
                SystemClock.elapsedRealtime()
        }


        /*
         * Finally check whether the newly foreground package
         * is protected.
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
                 * Not a protected application.
                 */
                if (
                    blocked == null
                ) {
                    return@launch
                }


                /*
                 * UI/Activity work must happen on main.
                 */
                withContext(
                    Dispatchers.Main.immediate
                ) {

                    showInterventionIfNeeded(
                        blocked.packageName,
                        blocked.displayName,
                        blocked.unlockDelaySeconds
                    )
                }

            } catch (e: Exception) {

                e.printStackTrace()
            }
        }
    }


    private fun showInterventionIfNeeded(
        packageName: String,
        displayName: String,
        delaySeconds: Long
    ) {

        /*
         * Re-read state because the database lookup happened
         * asynchronously.
         */
        val currentAllowed =
            readAllowedPackage()

        allowedPackage =
            currentAllowed


        /*
         * If the user already unlocked this package,
         * don't show the intervention.
         */
        if (
            packageName ==
            currentAllowed
        ) {
            return
        }


        /*
         * If an intervention is already active for this
         * exact package, bring it forward rather than
         * creating another Activity.
         */
        if (
            isInterventionActive() &&
            readInterventionPackage() ==
            packageName
        ) {

            bringInterventionToFront(
                packageName
            )

            return
        }


        /*
         * If another intervention is active for a different
         * package, don't overwrite it from a stale accessibility
         * event.
         */
        if (
            isInterventionActive()
        ) {
            return
        }


        /*
         * Final persisted-state check.
         *
         * This protects against Continue being pressed while
         * the database coroutine was running.
         */
        val finalAllowed =
            readAllowedPackage()

        allowedPackage =
            finalAllowed


        if (
            packageName ==
            finalAllowed
        ) {
            return
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

            return
        }


        /*
         * Mark intervention ACTIVE BEFORE starting Activity.
         *
         * This is critical.
         *
         * If Android immediately reports the protected package
         * again, the service already knows that an intervention
         * exists.
         */
        val marked =
            markInterventionActive(
                packageName
            )


        if (!marked) {
            return
        }


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
                    packageName
                )

                putExtra(
                    InterventionActivity
                        .EXTRA_DISPLAY_NAME,
                    displayName
                )

                putExtra(
                    InterventionActivity
                        .EXTRA_DELAY_SECONDS,
                    delaySeconds
                )
            }


        try {

            startActivity(
                intent
            )

        } catch (e: Exception) {

            /*
             * If Android rejects the Activity launch,
             * remove the active state so another attempt
             * can happen.
             */
            clearInterventionState()

            lastInterventionPackage =
                null

            lastInterventionTime =
                0L

            e.printStackTrace()
        }
    }


    /*
     * Bring the intervention Activity to the foreground.
     *
     * This is used when the user tries to enter the protected
     * application while its intervention is still active.
     */
    private fun bringInterventionToFront(
        packageName: String
    ) {

        val intent =
            Intent(
                this,
                InterventionActivity::class.java
            ).apply {

                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                )

                putExtra(
                    InterventionActivity
                        .EXTRA_PACKAGE_NAME,
                    packageName
                )
            }


        try {

            startActivity(
                intent
            )

        } catch (e: Exception) {

            e.printStackTrace()
        }
    }


    /*
     * Mark an intervention as active.
     */
    private fun markInterventionActive(
        packageName: String
    ): Boolean {

        return getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
            .edit()
            .putBoolean(
                KEY_INTERVENTION_ACTIVE,
                true
            )
            .putString(
                KEY_INTERVENTION_PACKAGE,
                packageName
            )
            .commit()
    }


    /*
     * Clear intervention state.
     *
     * This is ONLY called when:
     *
     * - Continue was pressed, or
     * - Activity launch failed.
     */
    private fun clearInterventionState() {

        getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
            .edit()
            .putBoolean(
                KEY_INTERVENTION_ACTIVE,
                false
            )
            .remove(
                KEY_INTERVENTION_PACKAGE
            )
            .commit()
    }


    private fun isInterventionActive(): Boolean {

        return getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
            .getBoolean(
                KEY_INTERVENTION_ACTIVE,
                false
            )
    }


    private fun readInterventionPackage(): String? {

        return getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
            .getString(
                KEY_INTERVENTION_PACKAGE,
                null
            )
    }


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


    private fun clearTemporaryUnlock() {

        allowedPackage =
            null

        lastAllowedPackageExitTime =
            0L


        getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
            .edit()
            .remove(
                KEY_ALLOWED_PACKAGE
            )
            .remove(
                KEY_RECENT_UNLOCK_PACKAGE
            )
            .remove(
                KEY_RECENT_UNLOCK_TIME
            )
            .commit()
    }


    private fun readRecentUnlockPackage(): String? {

        return getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
            .getString(
                KEY_RECENT_UNLOCK_PACKAGE,
                null
            )
    }


    private fun readRecentUnlockTime(): Long {

        return getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
            .getLong(
                KEY_RECENT_UNLOCK_TIME,
                0L
            )
    }


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


        private const val KEY_INTERVENTION_ACTIVE =
            "intervention_active"


        private const val KEY_INTERVENTION_PACKAGE =
            "intervention_package"


        private const val KEY_RECENT_UNLOCK_PACKAGE =
            "recent_unlock_package"


        private const val KEY_RECENT_UNLOCK_TIME =
            "recent_unlock_time"


        /*
         * Prevent duplicate launches from accessibility events.
         */
        private const val INTERVENTION_COOLDOWN =
            1500L


        /*
         * Returning to the previously unlocked application
         * within this period is allowed.
         */
        private const val REOPEN_GRACE_PERIOD =
            3000L


        /*
         * Immediately after Continue, ignore the foreground
         * event generated by launching the target application.
         */
        private const val RECENT_UNLOCK_GRACE =
            1500L
    }
}