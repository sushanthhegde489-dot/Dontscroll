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
import com.sushanth.dontscroll.util.ScreenTimeManager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds


class DoomGuardAccessibilityService :
    AccessibilityService() {

    // =========================================================
    // COROUTINE
    // =========================================================

    private val serviceJob =
        SupervisorJob()

    private val scope =
        CoroutineScope(
            Dispatchers.IO + serviceJob
        )

    // =========================================================
    // DATABASE
    // =========================================================

    private lateinit var database: AppDatabase

    private var blockedAppsJob: Job? =
        null

    private var breakExpirationJob: Job? =
        null

    // =========================================================
    // BLOCKED PACKAGES
    // =========================================================

    @Volatile
    private var blockedPackages: Set<String> =
        emptySet()

    // =========================================================
    // FOREGROUND STATE MACHINE
    // =========================================================

    /**
     * The last package that the service considers to be the
     * actual external foreground application.
     *
     * IMPORTANT:
     *
     * This represents APP-LEVEL foreground state.
     *
     * It does NOT change when Instagram opens:
     * - comments
     * - reels
     * - profiles
     * - share dialogs
     * - keyboards
     * - internal activities
     *
     * because all of those can still belong to the same package.
     */
    @Volatile
    private var foregroundPackage: String? =
        null

    /**
     * Activity class associated with the last accepted external
     * foreground event. Same-package Activity/window changes are
     * intentionally never treated as new app entries.
     */
    @Volatile
    private var foregroundActivityClass: String? =
        null

    /**
     * Last package received from an external accessibility
     * window-state event.
     *
     * This is intentionally separate from foregroundPackage.
     */
    @Volatile
    private var lastObservedPackage: String? =
        null

    /**
     * Monotonic timestamp of the most recent actual package
     * transition.
     */
    @Volatile
    private var lastForegroundTransitionTime: Long =
        0L

    /**
     * When true, InterventionActivity has disappeared and we
     * are waiting for Android to tell us what application
     * actually became foreground.
     *
     * This is the ONLY situation where seeing the target
     * package again immediately after an intervention means
     * "restart the intervention".
     *
     * This prevents normal same-app navigation from triggering
     * an intervention.
     */
    @Volatile
    private var awaitingInterventionReturn =
        false

    /**
     * Package that owns the intervention currently being
     * enforced.
     */
    @Volatile
    private var interventionTargetPackage: String? =
        null

    // =========================================================
    // ACTIVE INTERVENTION
    // =========================================================

    /**
     * True when InterventionActivity is currently visible.
     */
    @Volatile
    private var interventionScreenVisible =
        false

    /**
     * Prevents the service from interpreting an immediate
     * lifecycle transition as a real user action before the
     * intervention has actually appeared.
     */
    @Volatile
    private var interventionScreenHasShown =
        false

    // =========================================================
    // CONTINUOUS USAGE
    // =========================================================

    @Volatile
    private var trackedPackage: String? =
        null

    @Volatile
    private var trackedStartTime: Long =
        0L

    private var usageMonitorJob: Job? =
        null

    // =========================================================
    // INTERVENTION LAUNCH COOLDOWN
    // =========================================================

    /**
     * Protects against duplicate launch attempts caused by
     * multiple asynchronous paths.
     */
    private val lastInterventionLaunch =
        mutableMapOf<String, Long>()

    // =========================================================
    // SERVICE LIFECYCLE
    // =========================================================

    override fun onCreate() {

        super.onCreate()

        instance =
            this

        Log.d(
            TAG,
            "DoomGuardAccessibilityService created"
        )
    }

    override fun onServiceConnected() {

        super.onServiceConnected()

        database =
            AppDatabase.getInstance(
                applicationContext
            )

        foregroundPackage =
            null

        foregroundActivityClass =
            null

        lastObservedPackage =
            null

        lastForegroundTransitionTime =
            0L

        awaitingInterventionReturn =
            false

        interventionTargetPackage =
            null

        interventionScreenVisible =
            false

        interventionScreenHasShown =
            false

        stopUsageTimer()

        observeBlockedApps()

        if (
            isBreakActive(
                applicationContext
            )
        ) {
            scheduleBreakExpiration()
        }

        instance =
            this

        Log.d(
            TAG,
            "Accessibility service connected"
        )
    }

    // =========================================================
    // BLOCKED APP OBSERVER
    // =========================================================

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

                        val interventionTarget =
                            interventionTargetPackage

                        if (
                            interventionTarget != null &&
                            interventionTarget !in blockedPackages
                        ) {

                            cancelCurrentIntervention()
                        }
                    }
            }
    }

    // =========================================================
    // ACCESSIBILITY EVENT
    // =========================================================

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {

        if (event == null) {
            return
        }

        /*
         * We only care about package-level foreground
         * transitions.
         *
         * AccessibilityService can produce many other event
         * types for internal UI changes.
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

        val activityClassName =
            event.className
                ?.toString()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        val ourPackage =
            applicationContext.packageName

        // =====================================================
        // OUR OWN APP
        // =====================================================

        /*
         * InterventionActivity belongs to our application.
         *
         * Its lifecycle callbacks are authoritative for the
         * intervention itself, so do not treat our package as
         * an external foreground application.
         */
        if (
            packageName ==
            ourPackage
        ) {

            return
        }

        // =====================================================
        // TRANSIENT SYSTEM UI
        // =====================================================

        /*
         * Android can temporarily report SystemUI,
         * permission controllers, keyboards, intent resolvers,
         * etc.
         *
         * These must not become foreground applications in our
         * state machine.
         */
        if (
            isTransientSystemPackage(
                packageName
            )
        ) {

            return
        }

        // =====================================================
        // REAL PACKAGE TRANSITION
        // =====================================================

        handleExternalForegroundPackage(
            packageName,
            activityClassName
        )
    }

    // =========================================================
    // FOREGROUND PACKAGE STATE MACHINE
    // =========================================================

    private fun handleExternalForegroundPackage(
        packageName: String,
        activityClassName: String?
    ) {

        val previousPackage =
            foregroundPackage

        val previousActivityClass =
            foregroundActivityClass

        lastObservedPackage =
            packageName

        // =====================================================
        // SAME APP = IGNORE WINDOW/ACTIVITY CHANGES
        // =====================================================

        /*
         * TYPE_WINDOW_STATE_CHANGED is noisy. An app can emit this
         * event when it switches activities, opens a dialog, shows
         * a share sheet, changes its internal window, etc.
         *
         * Blocking is package based, so the package is the authority
         * for deciding whether the user actually left one app and
         * entered another app.
         *
         * IMPORTANT:
         *
         * If previousPackage == packageName, this is NOT a new app
         * entry. Never start or restart an intervention from this
         * event, even when the Activity class changed.
         */
        if (
            previousPackage ==
            packageName
        ) {

            // Record the newest activity only for diagnostics/state.
            foregroundActivityClass =
                activityClassName

            // If blocking is active and user is in a protected package without a valid unlock,
            // intervene immediately (e.g. break expired while user was scrolling inside the app).
            if (
                isBlockingCurrentlyActive(
                    applicationContext
                ) &&
                packageName in blockedPackages
            ) {
                val state =
                    readState(packageName)

                if (
                    !state.interventionActive &&
                    !interventionScreenVisible &&
                    state.unlockUntil <= System.currentTimeMillis()
                ) {
                    Log.d(
                        TAG,
                        "Same-app event for blocked app without active unlock/intervention: $packageName"
                    )

                    showInitialIntervention(
                        packageName
                    )

                    return
                }
            }

            Log.d(
                TAG,
                "Ignoring same-app window/activity change: " +
                        "$previousPackage/$previousActivityClass -> " +
                        "$packageName/$activityClassName"
            )

            return
        }

        // =====================================================
        // REAL APP TRANSITION
        // =====================================================

        foregroundPackage =
            packageName

        foregroundActivityClass =
            activityClassName

        lastForegroundTransitionTime =
            SystemClock.elapsedRealtime()

        Log.d(
            TAG,
            "Foreground app transition: " +
                    "$previousPackage/$previousActivityClass -> " +
                    "$packageName/$activityClassName"
        )

        // =====================================================
        // RETURN TO AN UNFINISHED INTERVENTION
        // =====================================================

        if (
            awaitingInterventionReturn &&
            interventionTargetPackage ==
            packageName
        ) {

            awaitingInterventionReturn =
                false

            showExistingIntervention(
                packageName
            )

            return
        }

        /*
         * Leaving the blocked app does NOT cancel its unfinished
         * intervention. The state remains active until the user
         * returns to the same package and gets a fresh session.
         */
        if (
            awaitingInterventionReturn
        ) {

            Log.d(
                TAG,
                "Intervention pending while outside target: " +
                        "${interventionTargetPackage} -> $packageName"
            )
        }

        // =====================================================
        // GLOBAL BLOCKING
        // =====================================================

        if (
            !isBlockingCurrentlyActive(
                applicationContext
            )
        ) {

            if (
                isBreakActive(
                    applicationContext
                )
            ) {
                if (
                    breakExpirationJob == null ||
                    breakExpirationJob?.isActive != true
                ) {
                    scheduleBreakExpiration()
                }
            }

            stopUsageTimer()
            return
        }

        stopUsageTimer()

        if (
            packageName !in blockedPackages
        ) {
            return
        }

        val state =
            readState(packageName)

        if (
            state.interventionActive
        ) {

            interventionTargetPackage =
                packageName

            interventionScreenVisible =
                false

            interventionScreenHasShown =
                false

            showExistingIntervention(
                packageName
            )

            return
        }

        if (
            state.unlockUntil >
            System.currentTimeMillis()
        ) {

            startUsageTimer(packageName)
            return
        }

        showInitialIntervention(packageName)
    }

    // =========================================================
    // INITIAL INTERVENTION
    // =========================================================

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

        // =====================================================
        // EXISTING INTERVENTION
        // =====================================================

        if (
            state.interventionActive
        ) {

            interventionTargetPackage =
                packageName

            showExistingIntervention(
                packageName
            )

            return
        }

        // =====================================================
        // STILL UNLOCKED
        // =====================================================

        if (
            state.unlockUntil >
            System.currentTimeMillis()
        ) {

            startUsageTimer(
                packageName
            )

            return
        }

        // =====================================================
        // LOAD DATABASE ENTRY
        // =====================================================

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

                val effectiveDelaySeconds =
                    getEffectiveUnlockDelaySeconds(
                        blocked
                    )

                withContext(
                    Dispatchers.Main.immediate
                ) {

                    // -------------------------------------------------
                    // REVALIDATE EVERYTHING ON MAIN
                    // -------------------------------------------------

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

                    if (
                        packageName !in blockedPackages
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

                        interventionTargetPackage =
                            packageName

                        showExistingIntervention(
                            packageName
                        )

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

                    // -------------------------------------------------
                    // CLAIM INTERVENTION BEFORE LAUNCH
                    // -------------------------------------------------

                    setInterventionActive(
                        packageName
                    )

                    interventionTargetPackage =
                        packageName

                    interventionScreenVisible =
                        false

                    interventionScreenHasShown =
                        false

                    awaitingInterventionReturn =
                        false

                    stopUsageTimer()

                    launchInterventionActivity(
                        packageName,
                        blocked.displayName,
                        effectiveDelaySeconds
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

    // =========================================================
    // EXISTING INTERVENTION
    // =========================================================

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

        if (
            packageName !in blockedPackages
        ) {
            return
        }

        interventionTargetPackage =
            packageName

        /*
         * We are actively enforcing this intervention again.
         */
        awaitingInterventionReturn =
            false

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

                val effectiveDelaySeconds =
                    getEffectiveUnlockDelaySeconds(
                        blocked
                    )

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

                    // -------------------------------------------------
                    // STATE WAS ALREADY CLEARED
                    // -------------------------------------------------

                    if (
                        !state.interventionActive
                    ) {

                        interventionTargetPackage =
                            null

                        interventionScreenVisible =
                            false

                        interventionScreenHasShown =
                            false

                        awaitingInterventionReturn =
                            false

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

                    // -------------------------------------------------
                    // ALREADY VISIBLE
                    // -------------------------------------------------

                    if (
                        interventionScreenVisible
                    ) {
                        return@withContext
                    }

                    // -------------------------------------------------
                    // RESTART
                    // -------------------------------------------------

                    launchInterventionActivity(
                        packageName,
                        blocked.displayName,
                        effectiveDelaySeconds,
                        ignoreCooldown = true
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

    // =========================================================
    // LAUNCH INTERVENTION
    // =========================================================

    private fun launchInterventionActivity(
        packageName: String,
        displayName: String,
        delaySeconds: Long,
        ignoreCooldown: Boolean = false
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
            interventionScreenVisible
        ) {
            return
        }

        if (
            !ignoreCooldown &&
            isLaunchOnCooldown(
                packageName
            )
        ) {

            return
        }

        val now =
            SystemClock.elapsedRealtime()

        synchronized(
            lastInterventionLaunch
        ) {

            lastInterventionLaunch[
                packageName
            ] = now
        }

        interventionTargetPackage =
            packageName

        interventionScreenVisible =
            false

        interventionScreenHasShown =
            false

        awaitingInterventionReturn =
            false

        val sessionId =
            SystemClock.elapsedRealtimeNanos()

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

                putExtra(
                    InterventionActivity.EXTRA_SESSION_ID,
                    sessionId
                )
            }

        try {

            Log.d(
                TAG,
                "Launching intervention: $packageName"
            )

            startActivity(
                intent
            )

        } catch (exception: Exception) {

            Log.e(
                TAG,
                "Could not launch InterventionActivity",
                exception
            )

            interventionTargetPackage =
                null

            interventionScreenVisible =
                false

            interventionScreenHasShown =
                false

            awaitingInterventionReturn =
                false

            clearIntervention(
                applicationContext,
                packageName
            )
        }
    }

    // =========================================================
    // ACTIVITY VISIBLE
    // =========================================================

    private fun markInterventionScreenVisible(
        packageName: String
    ) {

        val target =
            interventionTargetPackage

        if (
            target != packageName
        ) {
            return
        }

        interventionScreenVisible =
            true

        interventionScreenHasShown =
            true

        awaitingInterventionReturn =
            false


        Log.d(
            TAG,
            "Intervention screen VISIBLE for $packageName"
        )
    }

    // =========================================================
    // ACTIVITY HIDDEN
    // =========================================================

    private fun markInterventionScreenHidden(
        packageName: String
    ) {

        if (
            interventionTargetPackage !=
            packageName
        ) {
            return
        }

        val state =
            readState(packageName)

        if (
            !state.interventionActive
        ) {

            awaitingInterventionReturn =
                false

            interventionScreenVisible =
                false

            interventionScreenHasShown =
                false

            return
        }

        /*
         * Do not treat an onStop that happens before the Activity
         * ever reached onResume as a real user departure.
         *
         * This prevents launch/lifecycle races from putting the
         * service into "awaiting return" before the intervention
         * was actually shown.
         */
        if (!interventionScreenHasShown) {

            interventionScreenVisible =
                false

            return
        }

        /*
         * The intervention was genuinely visible and the user has
         * now left it. This is NOT completion.
         *
         * Keep interventionActive=true and wait for a REAL external
         * package transition. Only when the foreground package first
         * changes away and later changes back to this target do we
         * restart the intervention.
         */
        interventionScreenVisible =
            false

        interventionScreenHasShown =
            false

        awaitingInterventionReturn =
            true

        Log.d(
            TAG,
            "Intervention hidden; waiting for real app return: $packageName"
        )
    }

    // =========================================================
    // CANCEL INTERVENTION
    // =========================================================

    private fun cancelCurrentIntervention() {

        val target =
            interventionTargetPackage

        awaitingInterventionReturn =
            false

        interventionTargetPackage =
            null

        interventionScreenVisible =
            false

        interventionScreenHasShown =
            false

        stopUsageTimer()

        InterventionActivity.closeFromService()

        if (target != null) {
            clearIntervention(
                applicationContext,
                target
            )
        }

        Log.d(
            TAG,
            "Intervention cancelled: $target"
        )
    }

    // =========================================================
    // CONTINUOUS USAGE TIMER
    // =========================================================

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
            "Started continuous-use timer: $packageName"
        )

        usageMonitorJob =
            scope.launch {

                monitorUsage(
                    packageName
                )
            }
    }

    // =========================================================
    // CONTINUOUS USAGE MONITOR
    // =========================================================

    private suspend fun monitorUsage(
        packageName: String
    ) {

        while (true) {

            delay(
                USAGE_CHECK_INTERVAL
            )

            // -------------------------------------------------
            // BLOCKING
            // -------------------------------------------------

            if (
                !isBlockingCurrentlyActive(
                    applicationContext
                )
            ) {
                return
            }

            // -------------------------------------------------
            // REAL APP FOREGROUND
            // -------------------------------------------------

            if (
                foregroundPackage !=
                packageName
            ) {
                return
            }

            // -------------------------------------------------
            // BLOCKED APP
            // -------------------------------------------------

            if (
                packageName !in blockedPackages
            ) {
                return
            }

            // -------------------------------------------------
            // INTERVENTION
            // -------------------------------------------------

            val state =
                readState(
                    packageName
                )

            if (
                state.interventionActive
            ) {
                return
            }

            // -------------------------------------------------
            // BREAK
            // -------------------------------------------------

            if (
                isBreakActive(
                    applicationContext
                )
            ) {
                return
            }

            // -------------------------------------------------
            // ELAPSED TIME
            // -------------------------------------------------

            val elapsed =
                SystemClock.elapsedRealtime() -
                        trackedStartTime

            val limit =
                getContinuousUsageLimitMillis(
                    applicationContext,
                    packageName
                )

            if (
                state.unlockUntil <=
                System.currentTimeMillis() ||
                elapsed >=
                limit
            ) {

                Log.d(
                    TAG,
                    "Continuous-use limit reached or unlock expired: " +
                            "$packageName " +
                            "elapsed=$elapsed " +
                            "limit=$limit " +
                            "unlockUntil=${state.unlockUntil}"
                )

                triggerConsecutiveIntervention(
                    packageName
                )

                return
            }
        }
    }

    // =========================================================
    // CONTINUOUS INTERVENTION
    // =========================================================

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

        val effectiveDelaySeconds =
            getEffectiveUnlockDelaySeconds(
                blocked
            )

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

            /*
             * Claim the intervention BEFORE launching the
             * Activity.
             */
            setInterventionActive(
                packageName
            )

            interventionTargetPackage =
                packageName

            interventionScreenVisible =
                false

            interventionScreenHasShown =
                false

            awaitingInterventionReturn =
                false

            stopUsageTimer()

            launchInterventionActivity(
                packageName,
                blocked.displayName,
                effectiveDelaySeconds
            )
        }
    }

    // =========================================================
    // EFFECTIVE UNLOCK DELAY
    // =========================================================

    /**
     * Automatic delay is calculated when the intervention
     * starts, using today's usage.
     *
     * No previous day's automatic value is persisted.
     */
    private fun getEffectiveUnlockDelaySeconds(
        blocked: com.sushanth.dontscroll.data.BlockedApp
    ): Long {

        if (
            !blocked.automaticDelay
        ) {
            return blocked.unlockDelaySeconds
        }

        val usageMillis =
            ScreenTimeManager
                .getAppTodayUsage(
                    applicationContext,
                    blocked.packageName
                )

        return calculateAutomaticUnlockDelaySeconds(
            usageMillis
        )
    }

    private fun calculateAutomaticUnlockDelaySeconds(
        screenTimeMillis: Long
    ): Long {

        val minutes =
            screenTimeMillis /
                    60_000L

        return when {

            minutes < 30L ->
                15L

            minutes < 60L ->
                30L

            minutes < 120L ->
                60L

            minutes < 180L ->
                120L

            minutes < 240L ->
                180L

            minutes < 300L ->
                300L

            else ->
                600L
        }
    }

    // =========================================================
    // STATE
    // =========================================================

    private fun readState(
        packageName: String
    ): PackageState {

        val preferences =
            prefs()

        val unlockUntil =
            preferences.getLong(
                unlockKey(
                    packageName
                ),
                0L
            )

        val interventionActive =
            preferences.getBoolean(
                interventionKey(
                    packageName
                ),
                false
            )

        if (
            unlockUntil > 0L &&
            unlockUntil <=
            System.currentTimeMillis()
        ) {

            preferences.edit {

                putLong(
                    unlockKey(
                        packageName
                    ),
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

    private fun setInterventionActive(
        packageName: String
    ) {

        prefs()
            .edit()
            .putBoolean(
                interventionKey(
                    packageName
                ),
                true
            )
            .putLong(
                unlockKey(
                    packageName
                ),
                0L
            )
            .apply()

        interventionTargetPackage =
            packageName

        interventionScreenVisible =
            false

        interventionScreenHasShown =
            false

        awaitingInterventionReturn =
            false

        Log.d(
            TAG,
            "Intervention ACTIVE: $packageName"
        )
    }

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
                interventionKey(
                    packageName
                ),
                false
            )
            .putLong(
                unlockKey(
                    packageName
                ),
                0L
            )
            .apply()

        Log.d(
            TAG,
            "Intervention CLEARED: $packageName"
        )
    }

    // =========================================================
    // TIMER HELPERS
    // =========================================================

    private fun stopUsageTimer() {

        usageMonitorJob?.cancel()

        usageMonitorJob =
            null

        trackedPackage =
            null

        trackedStartTime =
            0L
    }

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

    // =========================================================
    // MANUAL CONTINUOUS TIMER
    // =========================================================

    private fun manuallyStartContinuousTimer(
        packageName: String
    ) {

        if (
            packageName !in blockedPackages
        ) {
            return
        }

        foregroundPackage =
            packageName

        lastObservedPackage =
            packageName

        startUsageTimer(
            packageName
        )
    }

    // =========================================================
    // PREFS
    // =========================================================

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

    // =========================================================
    // SYSTEM PACKAGES
    // =========================================================

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

    // =========================================================
    // BREAK EXPIRATION
    // =========================================================

    private fun scheduleBreakExpiration() {

        breakExpirationJob?.cancel()

        val breakUntil =
            getBreakUntil(
                applicationContext
            )

        val remainingMillis =
            breakUntil -
                    System.currentTimeMillis()

        if (
            remainingMillis <= 0L
        ) {
            return
        }

        Log.d(
            TAG,
            "Scheduling break expiration in ${remainingMillis}ms"
        )

        breakExpirationJob =
            scope.launch {

                delay(
                    remainingMillis
                )

                withContext(
                    Dispatchers.Main.immediate
                ) {
                    handleBreakExpired()
                }
            }
    }

    private fun cancelBreakExpiration() {

        breakExpirationJob?.cancel()

        breakExpirationJob =
            null
    }

    private fun handleBreakExpired() {

        Log.d(
            TAG,
            "Handling break expiration"
        )

        if (
            !isBlockingCurrentlyActive(
                applicationContext
            )
        ) {
            return
        }

        val activePkg =
            try {
                rootInActiveWindow
                    ?.packageName
                    ?.toString()
            } catch (_: Exception) {
                null
            }

        val current =
            (if (
                activePkg != null &&
                !isTransientSystemPackage(activePkg) &&
                activePkg != applicationContext.packageName
            ) {
                foregroundPackage =
                    activePkg
                activePkg
            } else {
                foregroundPackage
            }) ?: return

        if (
            current !in blockedPackages
        ) {
            return
        }

        val state =
            readState(current)

        if (
            state.interventionActive
        ) {
            showExistingIntervention(
                current
            )

            return
        }

        if (
            state.unlockUntil >
            System.currentTimeMillis()
        ) {
            startUsageTimer(
                current
            )

            return
        }

        showInitialIntervention(
            current
        )
    }

    // =========================================================
    // INTERRUPT
    // =========================================================

    override fun onInterrupt() {
        // Nothing required.
    }

    // =========================================================
    // DESTROY
    // =========================================================

    override fun onDestroy() {

        cancelBreakExpiration()
        blockedAppsJob?.cancel()
        usageMonitorJob?.cancel()

        synchronized(
            lastInterventionLaunch
        ) {

            lastInterventionLaunch.clear()
        }

        foregroundPackage =
            null

        foregroundActivityClass =
            null

        lastObservedPackage =
            null

        lastForegroundTransitionTime =
            0L

        awaitingInterventionReturn =
            false

        interventionTargetPackage =
            null

        interventionScreenVisible =
            false

        interventionScreenHasShown =
            false

        if (
            instance === this
        ) {

            instance =
                null
        }

        scope.cancel()

        super.onDestroy()
    }

    // =========================================================
    // DATA
    // =========================================================

    private data class PackageState(
        val interventionActive: Boolean,
        val unlockUntil: Long
    )

    // =========================================================
    // COMPANION
    // =========================================================

    companion object {

        private const val TAG =
            "DoomGuard"

        private const val PREFS_NAME =
            "dontscroll_intervention"

        // =====================================================
        // PER-APP STATE
        // =====================================================

        private const val KEY_INTERVENTION_ACTIVE_PREFIX =
            "intervention_active_"

        private const val KEY_UNLOCK_UNTIL_PREFIX =
            "unlock_until_"

        private const val KEY_CONTINUOUS_USAGE_APP_PREFIX =
            "continuous_usage_app_"

        // =====================================================
        // BLOCKING
        // =====================================================

        private const val KEY_BLOCKING_ENABLED =
            "blocking_enabled"

        // =====================================================
        // BREAK
        // =====================================================

        private const val KEY_BREAK_UNTIL =
            "break_until"

        // =====================================================
        // CONTINUOUS USAGE
        // =====================================================

        private const val KEY_CONTINUOUS_USAGE_LIMIT_MILLIS =
            "continuous_usage_limit_millis"

        /**
         * Default = 5 minutes.
         */
        private const val DEFAULT_CONTINUOUS_USAGE_LIMIT_MILLIS =
            5L * 60L * 1_000L

        private fun continuousUsageAppKey(
            packageName: String
        ): String {

            return KEY_CONTINUOUS_USAGE_APP_PREFIX +
                    packageName
        }

        private const val USAGE_CHECK_INTERVAL =
            1_000L

        // =====================================================
        // INTERVENTION
        // =====================================================

        /**
         * Prevents duplicate normal intervention launches.
         */
        private const val INTERVENTION_LAUNCH_COOLDOWN =
            1_500L

        /**
         * Guard against duplicate normal launches triggered by
         * multiple accessibility/lifecycle callbacks.
         */
        // =====================================================
        // INSTANCE
        // =====================================================

        @Volatile
        private var instance:
                DoomGuardAccessibilityService? =
            null

        // =====================================================
        // INTERVENTION SCREEN CALLBACKS
        // =====================================================

        fun notifyInterventionScreenVisible(
            packageName: String?
        ) {

            val service =
                instance
                    ?: return

            if (
                packageName.isNullOrBlank()
            ) {
                return
            }

            service.markInterventionScreenVisible(
                packageName
            )
        }

        fun notifyInterventionScreenHidden(
            packageName: String?
        ) {

            val service =
                instance
                    ?: return

            if (
                packageName.isNullOrBlank()
            ) {
                return
            }

            service.markInterventionScreenHidden(
                packageName
            )
        }

        // =====================================================
        // BLOCKING
        // =====================================================

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

            instance?.stopUsageTimer()

            instance?.awaitingInterventionReturn =
                false

            instance?.interventionTargetPackage =
                null

            instance?.interventionScreenVisible =
                false

            instance?.interventionScreenHasShown =
                false

            InterventionActivity.closeFromService()
        }

        // =====================================================
        // BREAK
        // =====================================================

        fun getBreakUntil(
            context: Context
        ): Long {

            val preferences =
                context
                    .getSharedPreferences(
                        PREFS_NAME,
                        Context.MODE_PRIVATE
                    )

            val breakUntil =
                preferences.getLong(
                    KEY_BREAK_UNTIL,
                    0L
                )

            if (
                breakUntil > 0L &&
                breakUntil <=
                System.currentTimeMillis()
            ) {

                preferences.edit {

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
            ) >
                    System.currentTimeMillis()
        }

        fun startBreak(
            context: Context,
            durationMillis: Long
        ) {

            if (
                durationMillis <= 0L
            ) {
                return
            }

            context
                .getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                .edit()
                .putLong(
                    KEY_BREAK_UNTIL,
                    System.currentTimeMillis() +
                            durationMillis
                )
                .apply()

            instance?.stopUsageTimer()

            instance?.awaitingInterventionReturn =
                false

            instance?.scheduleBreakExpiration()
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

            instance?.cancelBreakExpiration()

            instance?.stopUsageTimer()

            instance?.awaitingInterventionReturn =
                false

            /*
             * Clear diagnostic transition state so the next
             * genuine package transition is treated normally.
             */
            instance?.lastObservedPackage =
                null

            instance?.lastForegroundTransitionTime =
                0L

            instance?.handleBreakExpired()
        }

        // =====================================================
        // SHOULD BLOCK
        // =====================================================

        fun shouldBlock(
            context: Context,
            packageName: String? = null
        ): Boolean {

            if (
                !isBlockingEnabled(
                    context
                )
            ) {
                return false
            }

            if (
                isBreakActive(
                    context
                )
            ) {
                return false
            }

            if (
                packageName != null
            ) {

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

        private fun isBlockingCurrentlyActive(
            context: Context
        ): Boolean {

            return isBlockingEnabled(
                context
            ) &&
                    !isBreakActive(
                        context
                    )
        }

        // =====================================================
        // COMPLETE INTERVENTION
        // =====================================================

        fun completeIntervention(
            context: Context,
            packageName: String,
            durationMillis: Long
        ) {

            if (
                durationMillis <= 0L
            ) {
                return
            }

            val unlockUntil =
                System.currentTimeMillis() +
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
                .apply()

            /*
             * CRITICAL:
             *
             * The intervention is completed BEFORE the target
             * app is returned to.
             */
            instance?.interventionTargetPackage =
                null

            instance?.interventionScreenVisible =
                false

            instance?.interventionScreenHasShown =
                false

            instance?.awaitingInterventionReturn =
                false


            /*
             * The protected application is expected to remain
             * foreground after Continue.
             */
            instance?.foregroundPackage =
                packageName

            instance?.lastObservedPackage =
                packageName

            instance?.startUsageTimer(
                packageName
            )

            Log.d(
                TAG,
                "Intervention completed: " +
                        "$packageName " +
                        "unlockUntil=$unlockUntil"
            )
        }

        // =====================================================
        // PACKAGE UNLOCK CHECK
        // =====================================================

        fun isPackageUnlocked(
            context: Context,
            packageName: String
        ): Boolean {

            return context
                .getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                .getLong(
                    KEY_UNLOCK_UNTIL_PREFIX +
                            packageName,
                    0L
                ) >
                    System.currentTimeMillis()
        }

        // =====================================================
        // CONTINUOUS TIMER CONFIGURATION
        // =====================================================

        fun getContinuousUsageLimitMillis(
            context: Context,
            packageName: String? = null
        ): Long {

            val preferences =
                context.getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
                )

            val globalLimit =
                preferences.getLong(
                    KEY_CONTINUOUS_USAGE_LIMIT_MILLIS,
                    DEFAULT_CONTINUOUS_USAGE_LIMIT_MILLIS
                )

            val appOverride =
                packageName
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?.let {
                        preferences.getLong(
                            continuousUsageAppKey(
                                it
                            ),
                            0L
                        )
                    }
                    ?: 0L

            return if (
                appOverride > 0L
            ) {

                appOverride.coerceAtLeast(
                    1_000L
                )

            } else {

                globalLimit.coerceAtLeast(
                    1_000L
                )
            }
        }

        fun setContinuousUsageLimitMinutes(
            context: Context,
            minutes: Long
        ) {

            if (
                minutes <= 0L
            ) {
                return
            }

            context
                .getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                .edit()
                .putLong(
                    KEY_CONTINUOUS_USAGE_LIMIT_MILLIS,
                    minutes *
                            60L *
                            1_000L
                )
                .apply()

            Log.d(
                TAG,
                "Global continuous-use limit = " +
                        "$minutes minutes"
            )
        }

        fun getContinuousUsageLimitMinutes(
            context: Context
        ): Long {

            return getContinuousUsageLimitMillis(
                context
            ) /
                    60_000L
        }

        fun setAppContinuousUsageLimitMinutes(
            context: Context,
            packageName: String,
            minutes: Long
        ) {

            if (
                packageName.isBlank()
            ) {
                return
            }

            context
                .getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                .edit()
                .putLong(
                    continuousUsageAppKey(
                        packageName
                    ),
                    minutes.coerceAtLeast(
                        1L
                    ) *
                            60L *
                            1_000L
                )
                .apply()

            Log.d(
                TAG,
                "App continuous-use limit for " +
                        "$packageName = $minutes minutes"
            )
        }

        fun clearAppContinuousUsageLimit(
            context: Context,
            packageName: String
        ) {

            context
                .getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                .edit()
                .remove(
                    continuousUsageAppKey(
                        packageName
                    )
                )
                .apply()
        }

        fun getAppContinuousUsageLimitMinutes(
            context: Context,
            packageName: String
        ): Long? {

            val millis =
                context
                    .getSharedPreferences(
                        PREFS_NAME,
                        Context.MODE_PRIVATE
                    )
                    .getLong(
                        continuousUsageAppKey(
                            packageName
                        ),
                        0L
                    )

            return millis
                .takeIf {
                    it > 0L
                }
                ?.div(
                    60_000L
                )
        }

        fun resetContinuousUsageLimit(
            context: Context
        ) {

            context
                .getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                .edit()
                .remove(
                    KEY_CONTINUOUS_USAGE_LIMIT_MILLIS
                )
                .apply()
        }

        // =====================================================
        // MANUAL TIMER CONTROL
        // =====================================================

        @Suppress("UNUSED_PARAMETER")
        fun startContinuousTimer(
            context: Context,
            packageName: String
        ) {

            instance?.manuallyStartContinuousTimer(
                packageName
            )
        }

        fun stopContinuousTimer() {

            instance?.stopUsageTimer()
        }
    }
}
