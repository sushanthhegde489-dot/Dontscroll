package com.sushanth.dontscroll.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

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
     * COROUTINE SCOPE
     * =========================================================
     */

    private val scope =
        CoroutineScope(
            SupervisorJob() +
                    Dispatchers.IO
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
     * CURRENT FOREGROUND PACKAGE
     * =========================================================
     */

    @Volatile
    private var foregroundPackage: String? =
        null


    /*
     * =========================================================
     * PER-APP PROTECTION STATE
     * =========================================================
     *
     * IMPORTANT:
     *
     * There is deliberately NO global:
     *
     *     interventionActive
     *     interventionPackage
     *     unlockedPackage
     *
     * Every package owns its own state.
     */

    private data class AppState(
        var interventionActive: Boolean = false,
        var unlockUntil: Long = 0L
    )


    private val appStates =
        mutableMapOf<String, AppState>()


    /*
     * =========================================================
     * PER-APP 15-MINUTE TRACKING
     * =========================================================
     *
     * Only the foreground package needs an active monitor.
     *
     * Its state belongs to that package and is NOT shared with
     * other applications.
     */

    @Volatile
    private var trackedPackage: String? =
        null

    @Volatile
    private var trackedStartTime: Long =
        0L

    @Volatile
    private var consecutiveInterventionTriggered =
        false

    private var consecutiveMonitorJob: Job? =
        null


    /*
     * =========================================================
     * DUPLICATE INTERVENTION PROTECTION
     * =========================================================
     *
     * This is also per package.
     */

    private val lastInterventionTimes =
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

        syncPersistedState()

        observeBlockedApps()

        foregroundPackage =
            null

        resetConsecutiveUsage()

        Log.d(
            TAG,
            "DoomGuard accessibility service connected"
        )
    }


    /*
     * =========================================================
     * OBSERVE BLOCKED APPS
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

                        val newBlockedPackages =
                            apps
                                .map { app ->
                                    app.packageName
                                }
                                .toSet()

                        blockedPackages =
                            newBlockedPackages

                        Log.d(
                            TAG,
                            "Blocked packages updated: " +
                                    "$blockedPackages"
                        )


                        /*
                         * If the currently tracked app is no
                         * longer blocked, stop its timer.
                         */

                        val tracked =
                            trackedPackage

                        if (
                            tracked != null &&
                            tracked !in blockedPackages
                        ) {

                            Log.d(
                                TAG,
                                "Tracked package removed from block list: $tracked"
                            )

                            resetConsecutiveUsage()
                        }


                        /*
                         * Remove persisted state for packages
                         * that are no longer blocked.
                         *
                         * IMPORTANT:
                         *
                         * This only affects the package that was
                         * actually removed.
                         */

                        val statesToRemove =
                            synchronized(appStates) {
                                appStates.keys
                                    .filter {
                                        it !in blockedPackages
                                    }
                            }

                        statesToRemove.forEach { packageName ->

                            Log.d(
                                TAG,
                                "Removing protection state for unblocked package: $packageName"
                            )

                            clearAppState(
                                packageName
                            )
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
         * We primarily care about foreground/window changes.
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


        if (packageName.isEmpty()) {
            return
        }


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
         * Ignore Android/system transient windows.
         */

        if (
            isTransientSystemPackage(
                packageName
            )
        ) {

            Log.d(
                TAG,
                "Ignoring transient system package: $packageName"
            )

            return
        }


        /*
         * Make sure the latest persisted state is loaded.
         */

        syncPersistedState()


        /*
         * Every valid package event is processed.
         *
         * We intentionally do NOT inspect className.
         */

        handleForegroundPackage(
            packageName
        )
    }


    /*
     * =========================================================
     * SYNCHRONIZE PERSISTED STATE
     * =========================================================
     *
     * State is stored under package-specific keys:
     *
     *     intervention_active_<package>
     *     unlock_until_<package>
     *
     * This allows:
     *
     *     Instagram = intervention active
     *     WhatsApp  = temporarily unlocked
     *
     * at the same time.
     */

    private fun syncPersistedState() {

        /*
         * We cannot enumerate SharedPreferences safely without
         * knowing which packages exist.
         *
         * Therefore state is loaded lazily per package through
         * getAppState().
         *
         * This method intentionally does not overwrite the
         * in-memory map with a single global state.
         */

        val packageName =
            foregroundPackage

        if (packageName != null) {
            getAppState(
                packageName
            )
        }
    }


    /*
     * =========================================================
     * GET PER-APP STATE
     * =========================================================
     */

    private fun getAppState(
        packageName: String
    ): AppState {

        synchronized(appStates) {

            val existing =
                appStates[packageName]

            if (existing != null) {

                /*
                 * Automatically expire unlocks.
                 */

                if (
                    existing.unlockUntil > 0L &&
                    System.currentTimeMillis() >=
                    existing.unlockUntil
                ) {

                    existing.unlockUntil =
                        0L

                    persistAppState(
                        packageName,
                        existing
                    )
                }

                return existing
            }


            val preferences =
                prefs()


            val state =
                AppState(
                    interventionActive =
                        preferences.getBoolean(
                            interventionKey(
                                packageName
                            ),
                            false
                        ),

                    unlockUntil =
                        preferences.getLong(
                            unlockKey(
                                packageName
                            ),
                            0L
                        )
                )


            /*
             * Expired unlock.
             */

            if (
                state.unlockUntil > 0L &&
                System.currentTimeMillis() >=
                state.unlockUntil
            ) {

                state.unlockUntil =
                    0L
            }


            appStates[
                packageName
            ] = state


            return state
        }
    }


    /*
     * =========================================================
     * FOREGROUND PACKAGE HANDLER
     * =========================================================
     */

    private fun handleForegroundPackage(
        packageName: String
    ) {

        val previousPackage =
            foregroundPackage


        val samePackage =
            previousPackage == packageName


        foregroundPackage =
            packageName


        Log.d(
            TAG,
            "Foreground changed: " +
                    "$previousPackage -> $packageName | " +
                    "blocked=$blockedPackages"
        )


        /*
         * Package changed.
         *
         * Stop ONLY the consecutive-use monitor.
         *
         * DO NOT clear the previous app's intervention.
         *
         * DO NOT clear the previous app's unlock state.
         */

        if (
            previousPackage != null &&
            previousPackage != packageName
        ) {

            resetConsecutiveUsage()
        }


        /*
         * Non-blocked package.
         */

        if (
            packageName !in blockedPackages
        ) {

            Log.d(
                TAG,
                "$packageName is not blocked"
            )

            /*
             * IMPORTANT:
             *
             * Do not clear another package's intervention.
             */

            return
        }


        /*
         * Get THIS PACKAGE'S state.
         */

        val state =
            getAppState(
                packageName
            )


        /*
         * =====================================================
         * CASE 1
         * =====================================================
         *
         * This package is temporarily unlocked.
         *
         * Its own intervention state should already be false.
         *
         * Nothing belonging to another package is touched.
         */

        if (
            isTemporarilyUnlocked(
                packageName
            )
        ) {

            Log.d(
                TAG,
                "$packageName is temporarily unlocked"
            )

            /*
             * If somehow an old intervention remains active,
             * do NOT allow the unlocked state to clear another
             * package.
             *
             * Only this package can be cleared.
             */

            if (
                state.interventionActive
            ) {

                Log.d(
                    TAG,
                    "Clearing stale intervention for unlocked package: $packageName"
                )

                state.interventionActive =
                    false

                persistAppState(
                    packageName,
                    state
                )
            }


            startConsecutiveUsage(
                packageName
            )

            return
        }


        /*
         * =====================================================
         * CASE 2
         * =====================================================
         *
         * THIS package already has an intervention.
         *
         * Restore it when the user returns.
         */

        if (
            state.interventionActive
        ) {

            Log.d(
                TAG,
                "Intervention already active for $packageName"
            )


            if (!samePackage) {

                bringInterventionToFront(
                    packageName
                )
            }


            return
        }


        /*
         * =====================================================
         * CASE 3
         * =====================================================
         *
         * Normal blocked package with no intervention.
         */

        Log.d(
            TAG,
            "$packageName is blocked; checking protection"
        )


        if (!samePackage) {

            checkProtectedApp(
                packageName
            )
        }
    }


    /*
     * =========================================================
     * CHECK PROTECTED APP
     * =========================================================
     */

    private fun checkProtectedApp(
        packageName: String
    ) {

        scope.launch {

            try {

                if (
                    packageName !in blockedPackages
                ) {
                    return@launch
                }


                val blocked =
                    database
                        .blockedAppDao()
                        .getByPackage(
                            packageName
                        )
                        ?: return@launch


                if (
                    packageName !in blockedPackages
                ) {
                    return@launch
                }


                if (
                    blocked.unlockDelaySeconds <= 0L
                ) {
                    return@launch
                }


                withContext(
                    Dispatchers.Main.immediate
                ) {

                    if (
                        foregroundPackage !=
                        packageName
                    ) {
                        return@withContext
                    }


                    showInitialIntervention(
                        blocked.packageName,
                        blocked.displayName,
                        blocked.unlockDelaySeconds
                    )
                }

            } catch (
                exception: Exception
            ) {

                Log.e(
                    TAG,
                    "checkProtectedApp failed",
                    exception
                )
            }
        }
    }


    /*
     * =========================================================
     * SHOW INITIAL INTERVENTION
     * =========================================================
     */

    private fun showInitialIntervention(
        packageName: String,
        displayName: String,
        delaySeconds: Long
    ) {

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


        /*
         * Check THIS package only.
         */

        if (
            isTemporarilyUnlocked(
                packageName
            )
        ) {

            startConsecutiveUsage(
                packageName
            )

            return
        }


        val state =
            getAppState(
                packageName
            )


        /*
         * Already active for THIS package.
         */

        if (
            state.interventionActive
        ) {

            Log.d(
                TAG,
                "Intervention already active for $packageName"
            )

            return
        }


        /*
         * Duplicate event protection.
         */

        val now =
            SystemClock.elapsedRealtime()


        val previousLaunchTime =
            synchronized(
                lastInterventionTimes
            ) {
                lastInterventionTimes[
                    packageName
                ] ?: 0L
            }


        if (
            now -
            previousLaunchTime <
            INTERVENTION_COOLDOWN
        ) {

            Log.d(
                TAG,
                "Ignoring duplicate intervention event for $packageName"
            )

            return
        }


        /*
         * Mark ONLY THIS PACKAGE as active.
         */

        state.interventionActive =
            true


        persistAppState(
            packageName,
            state
        )


        synchronized(
            lastInterventionTimes
        ) {

            lastInterventionTimes[
                packageName
            ] = now
        }


        /*
         * Stop the current package's consecutive timer.
         */

        resetConsecutiveUsage()


        Log.d(
            TAG,
            "Launching initial intervention for $packageName"
        )


        launchInterventionActivity(
            packageName,
            displayName,
            delaySeconds
        )
    }


    /*
     * =========================================================
     * START CONSECUTIVE USAGE
     * =========================================================
     */

    private fun startConsecutiveUsage(
        packageName: String
    ) {

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


        /*
         * A package with an active intervention cannot have a
         * normal unlocked timer.
         */

        val state =
            getAppState(
                packageName
            )


        if (
            state.interventionActive
        ) {
            return
        }


        /*
         * Must actually be unlocked.
         */

        if (
            !isTemporarilyUnlocked(
                packageName
            )
        ) {
            return
        }


        /*
         * Already tracking THIS package.
         */

        if (
            trackedPackage ==
            packageName &&
            trackedStartTime > 0L
        ) {
            return
        }


        consecutiveMonitorJob?.cancel()


        trackedPackage =
            packageName


        trackedStartTime =
            SystemClock.elapsedRealtime()


        consecutiveInterventionTriggered =
            false


        Log.d(
            TAG,
            "Started 15-minute session for $packageName"
        )


        startConsecutiveMonitor()
    }


    /*
     * =========================================================
     * 15-MINUTE MONITOR
     * =========================================================
     */

    private fun startConsecutiveMonitor() {

        consecutiveMonitorJob?.cancel()


        consecutiveMonitorJob =
            scope.launch {

                while (true) {

                    delay(
                        CONSECUTIVE_CHECK_INTERVAL
                    )


                    val packageName =
                        trackedPackage
                            ?: return@launch


                    /*
                     * User left the tracked package.
                     */

                    if (
                        foregroundPackage !=
                        packageName
                    ) {

                        Log.d(
                            TAG,
                            "15-minute timer stopped; left $packageName"
                        )

                        resetConsecutiveUsage()

                        return@launch
                    }


                    /*
                     * Package no longer blocked.
                     */

                    if (
                        packageName !in blockedPackages
                    ) {

                        resetConsecutiveUsage()

                        return@launch
                    }


                    /*
                     * The package's own state is what matters.
                     */

                    val state =
                        getAppState(
                            packageName
                        )


                    /*
                     * Intervention for THIS package took over.
                     */

                    if (
                        state.interventionActive
                    ) {

                        resetConsecutiveUsage()

                        return@launch
                    }


                    /*
                     * Unlock expired.
                     */

                    if (
                        !isTemporarilyUnlocked(
                            packageName
                        )
                    ) {

                        Log.d(
                            TAG,
                            "Unlock expired for $packageName"
                        )

                        resetConsecutiveUsage()

                        return@launch
                    }


                    val elapsed =
                        SystemClock.elapsedRealtime() -
                                trackedStartTime


                    if (
                        elapsed >=
                        CONSECUTIVE_USAGE_LIMIT
                    ) {

                        Log.d(
                            TAG,
                            "15-minute limit reached: $packageName"
                        )


                        triggerConsecutiveIntervention(
                            packageName
                        )


                        return@launch
                    }
                }
            }
    }


    /*
     * =========================================================
     * TRIGGER 15-MINUTE INTERVENTION
     * =========================================================
     */

    private suspend fun triggerConsecutiveIntervention(
        packageName: String
    ) {

        if (
            foregroundPackage !=
            packageName
        ) {

            resetConsecutiveUsage()

            return
        }


        if (
            packageName !in blockedPackages
        ) {

            resetConsecutiveUsage()

            return
        }


        val state =
            getAppState(
                packageName
            )


        /*
         * Only THIS package's intervention matters.
         */

        if (
            state.interventionActive
        ) {
            return
        }


        if (
            consecutiveInterventionTriggered
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


        consecutiveInterventionTriggered =
            true


        withContext(
            Dispatchers.Main.immediate
        ) {

            showConsecutiveIntervention(
                blocked.packageName,
                blocked.displayName,
                blocked.unlockDelaySeconds
            )
        }
    }


    /*
     * =========================================================
     * SHOW 15-MINUTE INTERVENTION
     * =========================================================
     */

    private fun showConsecutiveIntervention(
        packageName: String,
        displayName: String,
        delaySeconds: Long
    ) {

        if (
            foregroundPackage !=
            packageName
        ) {

            resetConsecutiveUsage()

            return
        }


        if (
            packageName !in blockedPackages
        ) {

            resetConsecutiveUsage()

            return
        }


        val state =
            getAppState(
                packageName
            )


        /*
         * Another intervention for THIS package already exists.
         */

        if (
            state.interventionActive
        ) {
            resetConsecutiveUsage()
            return
        }


        /*
         * This package becomes protected.
         */

        state.interventionActive =
            true


        persistAppState(
            packageName,
            state
        )


        synchronized(
            lastInterventionTimes
        ) {

            lastInterventionTimes[
                packageName
            ] =
                SystemClock.elapsedRealtime()
        }


        /*
         * Stop ONLY this package's consecutive timer.
         */

        consecutiveMonitorJob?.cancel()

        consecutiveMonitorJob =
            null

        trackedPackage =
            null

        trackedStartTime =
            0L


        Log.d(
            TAG,
            "Launching 15-minute intervention for $packageName"
        )


        launchInterventionActivity(
            packageName,
            displayName,
            delaySeconds
        )
    }


    /*
     * =========================================================
     * LAUNCH INTERVENTION ACTIVITY
     * =========================================================
     */

    private fun launchInterventionActivity(
        packageName: String,
        displayName: String,
        delaySeconds: Long
    ) {

        if (
            foregroundPackage !=
            packageName
        ) {
            return
        }


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
                "Starting InterventionActivity for $packageName"
            )


            startActivity(
                intent
            )

        } catch (
            exception: Exception
        ) {

            Log.e(
                TAG,
                "Failed to start InterventionActivity",
                exception
            )


            /*
             * IMPORTANT:
             *
             * Only clear THIS package.
             */

            clearIntervention(
                packageName
            )


            synchronized(
                lastInterventionTimes
            ) {

                lastInterventionTimes.remove(
                    packageName
                )
            }


            consecutiveInterventionTriggered =
                false
        }
    }


    /*
     * =========================================================
     * BRING THIS PACKAGE'S INTERVENTION TO FRONT
     * =========================================================
     */

    private fun bringInterventionToFront(
        packageName: String
    ) {

        val state =
            getAppState(
                packageName
            )


        if (
            !state.interventionActive
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

                    val currentState =
                        getAppState(
                            packageName
                        )


                    /*
                     * Re-check everything.
                     */

                    if (
                        !currentState.interventionActive
                    ) {
                        return@withContext
                    }


                    if (
                        foregroundPackage !=
                        packageName
                    ) {
                        return@withContext
                    }


                    launchInterventionActivity(
                        blocked.packageName,
                        blocked.displayName,
                        blocked.unlockDelaySeconds
                    )
                }

            } catch (
                exception: Exception
            ) {

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
     * IS TEMPORARILY UNLOCKED
     * =========================================================
     */

    private fun isTemporarilyUnlocked(
        packageName: String
    ): Boolean {

        val state =
            getAppState(
                packageName
            )


        val unlockUntil =
            state.unlockUntil


        if (
            unlockUntil <= 0L
        ) {
            return false
        }


        if (
            System.currentTimeMillis() >=
            unlockUntil
        ) {

            state.unlockUntil =
                0L


            persistAppState(
                packageName,
                state
            )


            return false
        }


        return true
    }


    /*
     * =========================================================
     * SET TEMPORARY UNLOCK
     * =========================================================
     *
     * Call this when InterventionActivity successfully completes
     * its unlock flow.
     *
     * Example:
     *
     *     unlockPackage(packageName, 15 * 60 * 1000L)
     *
     */

    private fun unlockPackage(
        packageName: String,
        durationMillis: Long
    ) {

        val state =
            getAppState(
                packageName
            )


        /*
         * Only THIS package is changed.
         */

        state.interventionActive =
            false


        state.unlockUntil =
            System.currentTimeMillis() +
                    durationMillis


        persistAppState(
            packageName,
            state
        )


        Log.d(
            TAG,
            "Package unlocked: $packageName until ${state.unlockUntil}"
        )
    }


    /*
     * =========================================================
     * CLEAR INTERVENTION FOR ONE PACKAGE
     * =========================================================
     *
     * NEVER clears another package.
     */

    private fun clearIntervention(
        packageName: String
    ) {

        val state =
            getAppState(
                packageName
            )


        state.interventionActive =
            false


        persistAppState(
            packageName,
            state
        )


        Log.d(
            TAG,
            "Intervention cleared for $packageName"
        )
    }


    /*
     * =========================================================
     * CLEAR COMPLETE APP STATE
     * =========================================================
     */

    private fun clearAppState(
        packageName: String
    ) {

        synchronized(appStates) {

            appStates.remove(
                packageName
            )
        }


        prefs()
            .edit()
            .remove(
                interventionKey(
                    packageName
                )
            )
            .remove(
                unlockKey(
                    packageName
                )
            )
            .apply()


        synchronized(
            lastInterventionTimes
        ) {

            lastInterventionTimes.remove(
                packageName
            )
        }
    }


    /*
     * =========================================================
     * PERSIST ONE APP'S STATE
     * =========================================================
     */

    private fun persistAppState(
        packageName: String,
        state: AppState
    ) {

        prefs()
            .edit()
            .putBoolean(
                interventionKey(
                    packageName
                ),
                state.interventionActive
            )
            .putLong(
                unlockKey(
                    packageName
                ),
                state.unlockUntil
            )
            .apply()


        synchronized(appStates) {

            appStates[
                packageName
            ] = state
        }
    }


    /*
     * =========================================================
     * RESET CONSECUTIVE USAGE
     * =========================================================
     *
     * This resets only the foreground usage monitor.
     *
     * It DOES NOT modify intervention or unlock state.
     */

    private fun resetConsecutiveUsage() {

        consecutiveMonitorJob?.cancel()

        consecutiveMonitorJob =
            null

        trackedPackage =
            null

        trackedStartTime =
            0L

        consecutiveInterventionTriggered =
            false
    }


    /*
     * =========================================================
     * SHARED PREFERENCES
     * =========================================================
     */

    private fun prefs() =
        getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )


    /*
     * =========================================================
     * PER-PACKAGE PREFERENCE KEYS
     * =========================================================
     */

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
     * SYSTEM / TRANSIENT PACKAGES
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

            else ->
                false
        }
    }


    /*
     * =========================================================
     * INTERRUPT
     * =========================================================
     */

    override fun onInterrupt() {
        // Nothing required.
    }


    /*
     * =========================================================
     * DESTROY
     * =========================================================
     */

    override fun onDestroy() {

        blockedAppsJob?.cancel()

        consecutiveMonitorJob?.cancel()

        scope.cancel()

        synchronized(appStates) {
            appStates.clear()
        }

        synchronized(lastInterventionTimes) {
            lastInterventionTimes.clear()
        }

        super.onDestroy()
    }


    /*
     * =========================================================
     * CONSTANTS
     * =========================================================
     */

    companion object {

        private const val TAG =
            "DoomGuard"


        private const val PREFS_NAME =
            "dontscroll_intervention"


        /*
         * Per-package intervention state.
         */

        private const val KEY_INTERVENTION_ACTIVE_PREFIX =
            "intervention_active_"


        /*
         * Per-package unlock expiration.
         */

        private const val KEY_UNLOCK_UNTIL_PREFIX =
            "unlock_until_"


        /*
         * Prevent duplicate intervention launches caused by
         * multiple accessibility events.
         */

        private const val INTERVENTION_COOLDOWN =
            1500L


        /*
         * 15 continuous minutes.
         */

        private const val CONSECUTIVE_USAGE_LIMIT =
            15L * 60L * 1000L


        /*
         * Check the continuous-use timer every second.
         */

        private const val CONSECUTIVE_CHECK_INTERVAL =
            1000L
    }
}