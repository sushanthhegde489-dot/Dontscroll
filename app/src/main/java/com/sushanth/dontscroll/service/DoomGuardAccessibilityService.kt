package com.sushanth.dontscroll.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

import androidx.core.content.edit

import com.sushanth.dontscroll.data.AppDatabase
import com.sushanth.dontscroll.ui.InterventionActivity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class DoomGuardAccessibilityService :
    AccessibilityService() {


    /*
     * =========================================================
     * COROUTINE
     * =========================================================
     */

    private val serviceJob =
        SupervisorJob()

    private val scope =
        CoroutineScope(
            Dispatchers.IO + serviceJob
        )


    /*
     * =========================================================
     * DATABASE
     * =========================================================
     */

    private lateinit var database: AppDatabase

    private var blockedAppsJob: Job? =
        null


    /*
     * =========================================================
     * BLOCKED PACKAGES
     * =========================================================
     */

    @Volatile
    private var blockedPackages: Set<String> =
        emptySet()


    /*
     * =========================================================
     * FOREGROUND PACKAGE
     * =========================================================
     */

    @Volatile
    private var foregroundPackage: String? =
        null


    /*
     * =========================================================
     * EXPECTED RETURN
     * =========================================================
     */

    @Volatile
    private var waitingForPackageReturn: String? =
        null


    /*
     * =========================================================
     * USAGE TIMER
     * =========================================================
     */

    @Volatile
    private var trackedPackage: String? =
        null

    @Volatile
    private var trackedStartTime: Long =
        0L

    private var usageMonitorJob: Job? =
        null


    /*
     * =========================================================
     * DUPLICATE INTERVENTION PROTECTION
     * =========================================================
     */

    private val lastInterventionLaunch =
        mutableMapOf<String, Long>()


    /*
     * =========================================================
     * SERVICE CONNECTED
     * =========================================================
     */

    override fun onServiceConnected() {

        super.onServiceConnected()

        database =
            AppDatabase.getInstance(
                applicationContext
            )

        foregroundPackage =
            null

        waitingForPackageReturn =
            null

        stopUsageTimer()

        observeBlockedApps()

        instance =
            this

        Log.d(
            TAG,
            "DoomGuard accessibility service connected"
        )
    }


    /*
     * =========================================================
     * BLOCKED APP OBSERVER
     * =========================================================
     */

    private fun observeBlockedApps() {

        blockedAppsJob?.cancel()

        blockedAppsJob =
            scope.launch {

                database
                    .blockedAppDao()
                    .getAll()
                    .collectLatest { apps ->

                        blockedPackages =
                            apps
                                .map {
                                    it.packageName
                                }
                                .toSet()

                        Log.d(
                            TAG,
                            "Blocked packages: $blockedPackages"
                        )

                        val tracked =
                            trackedPackage

                        if (
                            tracked != null &&
                            tracked !in blockedPackages
                        ) {

                            stopUsageTimer()
                        }
                    }
            }
    }


    /*
     * =========================================================
     * ACCESSIBILITY EVENT
     * =========================================================
     */

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {

        if (event == null) {
            return
        }

        /*
         * ONLY react to real window changes.
         *
         * This prevents:
         *
         * Instagram comments
         * scrolling
         * popups
         * focus changes
         * buttons
         *
         * from being treated as app launches.
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
                ?.trim()
                ?: return

        if (packageName.isBlank()) {
            return
        }


        /*
         * =====================================================
         * GLOBAL BLOCKING CHECK
         * =====================================================
         *
         * If blocking is OFF or a break is active, the service
         * still receives accessibility events but DOES NOTHING.
         */

        if (
            !isBlockingCurrentlyActive(
                applicationContext
            )
        ) {

            stopUsageTimer()

            /*
             * Keep tracking which real package is foreground.
             * This prevents a stale transition from being treated
             * as a new app entry when blocking is later enabled.
             */

            if (
                !isTransientSystemPackage(
                    packageName
                ) &&
                packageName !=
                applicationContext.packageName
            ) {

                foregroundPackage =
                    packageName
            }

            return
        }


        /*
         * =====================================================
         * OUR OWN APP
         * =====================================================
         */

        if (
            packageName ==
            applicationContext.packageName
        ) {

            return
        }


        /*
         * =====================================================
         * TRANSIENT SYSTEM WINDOW
         * =====================================================
         */

        if (
            isTransientSystemPackage(
                packageName
            )
        ) {

            return
        }


        handleForegroundPackage(
            packageName
        )
    }


    /*
     * =========================================================
     * FOREGROUND HANDLER
     * =========================================================
     */

    private fun handleForegroundPackage(
        packageName: String
    ) {

        /*
         * Global blocking could have been disabled between
         * accessibility callbacks.
         */

        if (
            !isBlockingCurrentlyActive(
                applicationContext
            )
        ) {

            stopUsageTimer()

            foregroundPackage =
                packageName

            return
        }


        val previousPackage =
            foregroundPackage


        /*
         * =====================================================
         * SAME PACKAGE
         * =====================================================
         *
         * This is what prevents Instagram comments and other
         * internal navigation from retriggering the intervention.
         */

        if (
            previousPackage ==
            packageName
        ) {

            return
        }


        /*
         * =====================================================
         * EXPECTED RETURN
         * =====================================================
         */

        val expectedReturn =
            waitingForPackageReturn

        if (
            expectedReturn ==
            packageName
        ) {

            waitingForPackageReturn =
                null

            foregroundPackage =
                packageName

            Log.d(
                TAG,
                "Returned to app: $packageName"
            )


            /*
             * Check global state again.
             */

            if (
                !isBlockingCurrentlyActive(
                    applicationContext
                )
            ) {

                stopUsageTimer()
                return
            }


            val state =
                readState(
                    packageName
                )


            if (
                state.interventionActive
            ) {

                showExistingIntervention(
                    packageName
                )

                return
            }


            if (
                state.unlockUntil >
                System.currentTimeMillis()
            ) {

                startUsageTimer(
                    packageName
                )

                return
            }


            /*
             * Unlock expired.
             *
             * Do NOT instantly intervene here.
             *
             * The next genuine foreground transition will
             * trigger the next intervention.
             */

            return
        }


        /*
         * =====================================================
         * NORMAL APP TRANSITION
         * =====================================================
         */

        foregroundPackage =
            packageName

        Log.d(
            TAG,
            "Foreground: " +
                    "$previousPackage -> $packageName"
        )

        stopUsageTimer()


        /*
         * Not protected.
         */

        if (
            packageName !in blockedPackages
        ) {

            return
        }


        val state =
            readState(
                packageName
            )


        /*
         * =====================================================
         * ACTIVE INTERVENTION
         * =====================================================
         */

        if (
            state.interventionActive
        ) {

            showExistingIntervention(
                packageName
            )

            return
        }


        /*
         * =====================================================
         * CURRENTLY UNLOCKED
         * =====================================================
         */

        if (
            state.unlockUntil >
            System.currentTimeMillis()
        ) {

            startUsageTimer(
                packageName
            )

            return
        }


        /*
         * =====================================================
         * NEED INTERVENTION
         * =====================================================
         */

        showInitialIntervention(
            packageName
        )
    }


    /*
     * =========================================================
     * INITIAL INTERVENTION
     * =========================================================
     */

    private fun showInitialIntervention(
        packageName: String
    ) {

        if (
            !isBlockingCurrentlyActive(
                applicationContext
            )
        ) {

            return
        }

        if (
            foregroundPackage !=
            packageName
        ) {

            return
        }

        if (
            packageName !in blockedPackages
        ) {

            return
        }


        val state =
            readState(
                packageName
            )


        if (
            state.interventionActive
        ) {

            showExistingIntervention(
                packageName
            )

            return
        }


        if (
            state.unlockUntil >
            System.currentTimeMillis()
        ) {

            startUsageTimer(
                packageName
            )

            return
        }


        scope.launch {

            try {

                val blocked =
                    database
                        .blockedAppDao()
                        .getByPackage(
                            packageName
                        )
                        ?: return@launch


                if (
                    blocked.unlockDelaySeconds <= 0L
                ) {

                    return@launch
                }


                withContext(
                    Dispatchers.Main.immediate
                ) {

                    if (
                        !isBlockingCurrentlyActive(
                            applicationContext
                        )
                    ) {

                        return@withContext
                    }


                    if (
                        foregroundPackage !=
                        packageName
                    ) {

                        return@withContext
                    }


                    val currentState =
                        readState(
                            packageName
                        )


                    if (
                        currentState.interventionActive
                    ) {

                        return@withContext
                    }


                    if (
                        currentState.unlockUntil >
                        System.currentTimeMillis()
                    ) {

                        startUsageTimer(
                            packageName
                        )

                        return@withContext
                    }


                    /*
                     * CLAIM intervention BEFORE launching Activity.
                     */

                    setInterventionActive(
                        packageName
                    )

                    stopUsageTimer()

                    launchInterventionActivity(
                        packageName,
                        blocked.displayName,
                        blocked.unlockDelaySeconds
                    )
                }

            } catch (exception: Exception) {

                Log.e(
                    TAG,
                    "Failed to show initial intervention",
                    exception
                )
            }
        }
    }


    /*
     * =========================================================
     * EXISTING INTERVENTION
     * =========================================================
     */

    private fun showExistingIntervention(
        packageName: String
    ) {

        if (
            !isBlockingCurrentlyActive(
                applicationContext
            )
        ) {

            return
        }

        if (
            foregroundPackage !=
            packageName
        ) {

            return
        }


        scope.launch {

            try {

                val blocked =
                    database
                        .blockedAppDao()
                        .getByPackage(
                            packageName
                        )
                        ?: return@launch


                if (
                    blocked.unlockDelaySeconds <= 0L
                ) {

                    return@launch
                }


                withContext(
                    Dispatchers.Main.immediate
                ) {

                    if (
                        !isBlockingCurrentlyActive(
                            applicationContext
                        )
                    ) {

                        return@withContext
                    }


                    if (
                        foregroundPackage !=
                        packageName
                    ) {

                        return@withContext
                    }


                    val state =
                        readState(
                            packageName
                        )


                    if (
                        !state.interventionActive
                    ) {

                        if (
                            state.unlockUntil >
                            System.currentTimeMillis()
                        ) {

                            startUsageTimer(
                                packageName
                            )
                        }

                        return@withContext
                    }


                    if (
                        isLaunchOnCooldown(
                            packageName
                        )
                    ) {

                        return@withContext
                    }


                    launchInterventionActivity(
                        packageName,
                        blocked.displayName,
                        blocked.unlockDelaySeconds
                    )
                }

            } catch (exception: Exception) {

                Log.e(
                    TAG,
                    "Failed to restore intervention",
                    exception
                )
            }
        }
    }


    /*
     * =========================================================
     * USAGE TIMER
     * =========================================================
     */

    private fun startUsageTimer(
        packageName: String
    ) {

        if (
            !isBlockingCurrentlyActive(
                applicationContext
            )
        ) {

            return
        }

        if (
            foregroundPackage !=
            packageName
        ) {

            return
        }

        if (
            packageName !in blockedPackages
        ) {

            return
        }


        val state =
            readState(
                packageName
            )


        if (
            state.interventionActive
        ) {

            return
        }


        if (
            state.unlockUntil <=
            System.currentTimeMillis()
        ) {

            return
        }


        if (
            trackedPackage ==
            packageName &&
            trackedStartTime > 0L
        ) {

            return
        }


        usageMonitorJob?.cancel()


        trackedPackage =
            packageName

        trackedStartTime =
            SystemClock.elapsedRealtime()


        Log.d(
            TAG,
            "Started continuous 15-minute timer: $packageName"
        )


        usageMonitorJob =
            scope.launch {

                monitorUsage(
                    packageName
                )
            }
    }


    /*
     * =========================================================
     * MONITOR USAGE
     * =========================================================
     */

    private suspend fun monitorUsage(
        packageName: String
    ) {

        while (true) {

            delay(
                USAGE_CHECK_INTERVAL
            )


            /*
             * Blocking disabled or break started.
             */

            if (
                !isBlockingCurrentlyActive(
                    applicationContext
                )
            ) {

                Log.d(
                    TAG,
                    "Blocking disabled/break active"
                )

                return
            }


            /*
             * User left app.
             */

            if (
                foregroundPackage !=
                packageName
            ) {

                return
            }


            if (
                packageName !in blockedPackages
            ) {

                return
            }


            val state =
                readState(
                    packageName
                )


            if (
                state.interventionActive
            ) {

                return
            }


            if (
                state.unlockUntil <=
                System.currentTimeMillis()
            ) {

                return
            }


            val elapsed =
                SystemClock.elapsedRealtime() -
                        trackedStartTime


            /*
             * 15 MINUTES CONTINUOUS USE
             */

            if (
                elapsed >=
                CONSECUTIVE_USAGE_LIMIT
            ) {

                Log.d(
                    TAG,
                    "15-minute continuous limit reached: $packageName"
                )


                triggerConsecutiveIntervention(
                    packageName
                )

                return
            }
        }
    }


    /*
     * =========================================================
     * SECONDARY INTERVENTION
     * =========================================================
     */

    private suspend fun triggerConsecutiveIntervention(
        packageName: String
    ) {

        if (
            !isBlockingCurrentlyActive(
                applicationContext
            )
        ) {

            return
        }

        if (
            foregroundPackage !=
            packageName
        ) {

            return
        }

        if (
            packageName !in blockedPackages
        ) {

            return
        }


        val state =
            readState(
                packageName
            )


        if (
            state.interventionActive
        ) {

            return
        }


        if (
            state.unlockUntil <=
            System.currentTimeMillis()
        ) {

            return
        }


        val blocked =
            database
                .blockedAppDao()
                .getByPackage(
                    packageName
                )
                ?: return


        if (
            blocked.unlockDelaySeconds <= 0L
        ) {

            return
        }


        withContext(
            Dispatchers.Main.immediate
        ) {

            if (
                !isBlockingCurrentlyActive(
                    applicationContext
                )
            ) {

                return@withContext
            }

            if (
                foregroundPackage !=
                packageName
            ) {

                return@withContext
            }


            val currentState =
                readState(
                    packageName
                )


            if (
                currentState.interventionActive
            ) {

                return@withContext
            }


            if (
                currentState.unlockUntil <=
                System.currentTimeMillis()
            ) {

                return@withContext
            }


            setInterventionActive(
                packageName
            )

            stopUsageTimer()


            launchInterventionActivity(
                packageName,
                blocked.displayName,
                blocked.unlockDelaySeconds
            )
        }
    }


    /*
     * =========================================================
     * LAUNCH INTERVENTION
     * =========================================================
     */

    private fun launchInterventionActivity(
        packageName: String,
        displayName: String,
        delaySeconds: Long
    ) {

        if (
            !isBlockingCurrentlyActive(
                applicationContext
            )
        ) {

            return
        }

        if (
            foregroundPackage !=
            packageName
        ) {

            return
        }


        val now =
            SystemClock.elapsedRealtime()


        val previousLaunch =
            synchronized(
                lastInterventionLaunch
            ) {

                lastInterventionLaunch[
                    packageName
                ] ?: 0L
            }


        if (
            now - previousLaunch <
            INTERVENTION_LAUNCH_COOLDOWN
        ) {

            return
        }


        synchronized(
            lastInterventionLaunch
        ) {

            lastInterventionLaunch[
                packageName
            ] = now
        }


        val intent =
            Intent(
                this,
                InterventionActivity::class.java
            ).apply {

                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )

                putExtra(
                    InterventionActivity.EXTRA_PACKAGE_NAME,
                    packageName
                )

                putExtra(
                    InterventionActivity.EXTRA_DISPLAY_NAME,
                    displayName
                )

                putExtra(
                    InterventionActivity.EXTRA_DELAY_SECONDS,
                    delaySeconds
                )
            }


        try {

            Log.d(
                TAG,
                "Launching intervention: $packageName"
            )

            startActivity(intent)

        } catch (exception: Exception) {

            Log.e(
                TAG,
                "Could not launch InterventionActivity",
                exception
            )

            clearIntervention(
                applicationContext,
                packageName
            )
        }
    }


    /*
     * =========================================================
     * PREPARE TARGET RETURN
     * =========================================================
     */

    private fun prepareForTargetAppReturn(
        packageName: String
    ) {

        waitingForPackageReturn =
            packageName

        stopUsageTimer()

        Log.d(
            TAG,
            "Waiting for return to $packageName"
        )
    }


    /*
     * =========================================================
     * READ PER-APP STATE
     * =========================================================
     */

    private fun readState(
        packageName: String
    ): PackageState {

        val preferences =
            prefs()

        val unlockUntil =
            preferences.getLong(
                unlockKey(packageName),
                0L
            )

        val interventionActive =
            preferences.getBoolean(
                interventionKey(packageName),
                false
            )


        if (
            unlockUntil > 0L &&
            unlockUntil <=
            System.currentTimeMillis()
        ) {

            preferences.edit {

                putLong(
                    unlockKey(packageName),
                    0L
                )
            }


            return PackageState(
                interventionActive =
                    interventionActive,

                unlockUntil =
                    0L
            )
        }


        return PackageState(
            interventionActive =
                interventionActive,

            unlockUntil =
                unlockUntil
        )
    }


    /*
     * =========================================================
     * SET INTERVENTION ACTIVE
     * =========================================================
     */

    private fun setInterventionActive(
        packageName: String
    ) {

        prefs()
            .edit()
            .putBoolean(
                interventionKey(packageName),
                true
            )
            .putLong(
                unlockKey(packageName),
                0L
            )
            .commit()


        Log.d(
            TAG,
            "Intervention ACTIVE: $packageName"
        )
    }


    /*
     * =========================================================
     * CLEAR INTERVENTION
     * =========================================================
     */

    private fun clearIntervention(
        context: Context,
        packageName: String
    ) {

        context
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            .edit()
            .putBoolean(
                interventionKey(packageName),
                false
            )
            .putLong(
                unlockKey(packageName),
                0L
            )
            .apply()
    }


    /*
     * =========================================================
     * TIMER
     * =========================================================
     */

    private fun isLaunchOnCooldown(
        packageName: String
    ): Boolean {

        val now =
            SystemClock.elapsedRealtime()

        val previous =
            synchronized(
                lastInterventionLaunch
            ) {

                lastInterventionLaunch[
                    packageName
                ] ?: 0L
            }

        return (
                now - previous
                ) < INTERVENTION_LAUNCH_COOLDOWN
    }


    private fun stopUsageTimer() {

        usageMonitorJob?.cancel()

        usageMonitorJob =
            null

        trackedPackage =
            null

        trackedStartTime =
            0L
    }


    /*
     * =========================================================
     * PREFS
     * =========================================================
     */

    private fun prefs() =
        getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )


    private fun interventionKey(
        packageName: String
    ): String {

        return KEY_INTERVENTION_ACTIVE_PREFIX +
                packageName
    }


    private fun unlockKey(
        packageName: String
    ): String {

        return KEY_UNLOCK_UNTIL_PREFIX +
                packageName
    }


    /*
     * =========================================================
     * TRANSIENT SYSTEM PACKAGES
     * =========================================================
     */

    private fun isTransientSystemPackage(
        packageName: String
    ): Boolean {

        return when (packageName) {

            "android" ->
                true

            "com.android.systemui" ->
                true

            "com.google.android.permissioncontroller" ->
                true

            "com.android.permissioncontroller" ->
                true

            "com.google.android.packageinstaller" ->
                true

            "com.android.packageinstaller" ->
                true

            "com.android.intentresolver" ->
                true

            "android.ext.services" ->
                true

            "com.google.android.inputmethod.latin" ->
                true

            "com.android.inputmethod.latin" ->
                true

            else ->
                false
        }
    }


    override fun onInterrupt() {
        // Nothing required.
    }


    override fun onDestroy() {

        blockedAppsJob?.cancel()

        usageMonitorJob?.cancel()

        synchronized(
            lastInterventionLaunch
        ) {

            lastInterventionLaunch.clear()
        }

        if (
            instance === this
        ) {

            instance =
                null
        }

        scope.cancel()

        super.onDestroy()
    }


    override fun onCreate() {

        super.onCreate()

        instance =
            this
    }


    /*
     * =========================================================
     * DATA
     * =========================================================
     */

    private data class PackageState(

        val interventionActive: Boolean,

        val unlockUntil: Long
    )


    /*
     * =========================================================
     * COMPANION
     * =========================================================
     */

    companion object {

        private const val TAG =
            "DoomGuard"


        private const val PREFS_NAME =
            "dontscroll_intervention"


        /*
         * Per-app state.
         */

        private const val KEY_INTERVENTION_ACTIVE_PREFIX =
            "intervention_active_"

        private const val KEY_UNLOCK_UNTIL_PREFIX =
            "unlock_until_"


        /*
         * =====================================================
         * GLOBAL BLOCKING
         * =====================================================
         */

        private const val KEY_BLOCKING_ENABLED =
            "blocking_enabled"


        /*
         * =====================================================
         * BREAK
         * =====================================================
         */

        private const val KEY_BREAK_UNTIL =
            "break_until"


        /*
         * =====================================================
         * COOLDOWN
         * =====================================================
         */

        private const val INTERVENTION_LAUNCH_COOLDOWN =
            1_500L


        /*
         * =====================================================
         * CONTINUOUS USAGE
         * =====================================================
         */

        private const val CONSECUTIVE_USAGE_LIMIT =
            15L * 60L * 1_000L


        private const val USAGE_CHECK_INTERVAL =
            1_000L


        /*
         * =====================================================
         * SERVICE INSTANCE
         * =====================================================
         */

        @Volatile
        private var instance:
                DoomGuardAccessibilityService? =
            null


        /*
         * =====================================================
         * GLOBAL BLOCKING STATE
         * =====================================================
         */

        fun isBlockingEnabled(
            context: Context
        ): Boolean {

            return context
                .getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                .getBoolean(
                    KEY_BLOCKING_ENABLED,
                    true
                )
        }


        fun enableBlocking(
            context: Context
        ) {

            context
                .getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                .edit()
                .putBoolean(
                    KEY_BLOCKING_ENABLED,
                    true
                )
                .apply()


            Log.d(
                TAG,
                "GLOBAL BLOCKING ENABLED"
            )
        }


        fun disableBlocking(
            context: Context
        ) {

            context
                .getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                .edit()
                .putBoolean(
                    KEY_BLOCKING_ENABLED,
                    false
                )
                .apply()


            /*
             * Immediately stop the service's continuous timer.
             */

            instance?.stopUsageTimer()

            Log.d(
                TAG,
                "GLOBAL BLOCKING DISABLED"
            )
        }


        /*
         * =====================================================
         * BREAK STATE
         * =====================================================
         */

        fun getBreakUntil(
            context: Context
        ): Long {

            val prefs =
                context
                    .getSharedPreferences(
                        PREFS_NAME,
                        Context.MODE_PRIVATE
                    )

            val breakUntil =
                prefs.getLong(
                    KEY_BREAK_UNTIL,
                    0L
                )


            /*
             * Automatically clean expired breaks.
             */

            if (
                breakUntil > 0L &&
                breakUntil <=
                System.currentTimeMillis()
            ) {

                prefs.edit {

                    putLong(
                        KEY_BREAK_UNTIL,
                        0L
                    )
                }

                return 0L
            }


            return breakUntil
        }


        fun isBreakActive(
            context: Context
        ): Boolean {

            return getBreakUntil(
                context
            ) > System.currentTimeMillis()
        }


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


            context
                .getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                .edit()
                .putLong(
                    KEY_BREAK_UNTIL,
                    breakUntil
                )
                .apply()


            /*
             * Stop any continuous-use timer immediately.
             */

            instance?.stopUsageTimer()


            Log.d(
                TAG,
                "BREAK STARTED until $breakUntil"
            )
        }


        fun endBreak(
            context: Context
        ) {

            context
                .getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                .edit()
                .putLong(
                    KEY_BREAK_UNTIL,
                    0L
                )
                .apply()


            instance?.stopUsageTimer()


            Log.d(
                TAG,
                "BREAK ENDED"
            )
        }


        /*
         * =====================================================
         * CENTRAL BLOCKING CHECK
         * =====================================================
         *
         * Every place that can launch an intervention calls
         * this.
         */

        fun shouldBlock(
            context: Context,
            packageName: String? = null
        ): Boolean {

            /*
             * Global switch.
             */

            if (
                !isBlockingEnabled(context)
            ) {

                return false
            }


            /*
             * Temporary break.
             */

            if (
                isBreakActive(context)
            ) {

                return false
            }


            /*
             * If a package was supplied, make sure it is
             * actually protected.
             */

            if (
                packageName != null
            ) {

                /*
                 * We intentionally do not query Room here.
                 * This method is also called from the UI.
                 *
                 * The service's blockedPackages set is the
                 * authoritative runtime list.
                 */

                val service =
                    instance

                if (
                    service != null &&
                    packageName !in
                    service.blockedPackages
                ) {

                    return false
                }
            }


            return true
        }


        /*
         * Internal service check.
         */

        private fun isBlockingCurrentlyActive(
            context: Context
        ): Boolean {

            return isBlockingEnabled(context) &&
                    !isBreakActive(context)
        }


        /*
         * =====================================================
         * COMPLETE INTERVENTION
         * =====================================================
         */

        fun completeIntervention(
            context: Context,
            packageName: String,
            durationMillis: Long
        ) {

            val now =
                System.currentTimeMillis()

            val unlockUntil =
                now +
                        durationMillis


            context
                .getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                .edit()
                .putBoolean(
                    KEY_INTERVENTION_ACTIVE_PREFIX +
                            packageName,
                    false
                )
                .putLong(
                    KEY_UNLOCK_UNTIL_PREFIX +
                            packageName,
                    unlockUntil
                )
                .commit()


            Log.d(
                TAG,
                "Intervention completed: " +
                        "$packageName unlockUntil=$unlockUntil"
            )
        }


        /*
         * =====================================================
         * RETURN TO TARGET APP
         * =====================================================
         */

        fun prepareForTargetAppReturn(
            packageName: String
        ) {

            instance?.prepareForTargetAppReturn(
                packageName
            )
        }


        /*
         * =====================================================
         * PUBLIC UNLOCK CHECK
         * =====================================================
         */

        fun isPackageUnlocked(
            context: Context,
            packageName: String
        ): Boolean {

            val unlockUntil =
                context
                    .getSharedPreferences(
                        PREFS_NAME,
                        Context.MODE_PRIVATE
                    )
                    .getLong(
                        KEY_UNLOCK_UNTIL_PREFIX +
                                packageName,
                        0L
                    )

            return unlockUntil >
                    System.currentTimeMillis()
        }
    }
}