package com.sushanth.dontscroll.service

import android.accessibilityservice.AccessibilityService
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
     * The package that was previously foreground.
     *
     * Example:
     *
     * Instagram -> launcher
     * launcher -> Instagram
     * Instagram -> Recents
     * Recents -> Instagram
     *
     * Every transition is detected independently.
     */
    private var lastForegroundPackage:
            String? = null

    /*
     * Small debounce for duplicate accessibility events.
     */
    private var lastHandledPackage:
            String? = null

    private var lastHandledTime =
        0L

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {

        if (event == null) {
            return
        }

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

        /*
         * Ignore our own application.
         */
        if (
            packageName ==
            applicationContext.packageName
        ) {
            lastForegroundPackage =
                packageName

            return
        }

        /*
         * Detect an actual foreground-package change.
         */
        val changed =
            packageName !=
                    lastForegroundPackage

        if (!changed) {
            /*
             * Same foreground application generating
             * another accessibility event.
             */
            return
        }

        /*
         * IMPORTANT:
         *
         * Update this immediately.
         *
         * So:
         *
         * Recents -> Instagram
         *
         * is detected even if there was no Home-screen
         * transition.
         */
        lastForegroundPackage =
            packageName

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
                 * Normal application.
                 */
                if (blocked == null) {
                    return@launch
                }

                /*
                 * Prevent several accessibility events
                 * from launching multiple intervention
                 * activities in rapid succession.
                 */
                val now =
                    SystemClock.elapsedRealtime()

                if (
                    packageName ==
                    lastHandledPackage &&
                    now -
                    lastHandledTime <
                    1000L
                ) {
                    return@launch
                }

                lastHandledPackage =
                    packageName

                lastHandledTime =
                    now

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

                startActivity(
                    intent
                )

            } catch (
                exception: Exception
            ) {

                exception.printStackTrace()
            }
        }
    }

    override fun onInterrupt() {
        // Nothing to do.
    }

    override fun onDestroy() {

        scope.cancel()

        super.onDestroy()
    }
}