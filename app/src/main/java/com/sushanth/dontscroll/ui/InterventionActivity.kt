package com.sushanth.dontscroll.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

import com.sushanth.dontscroll.service.DoomGuardAccessibilityService
import com.sushanth.dontscroll.ui.theme.DontscrollTheme
import com.sushanth.dontscroll.util.ScreenTimeManager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

import java.util.Locale


class InterventionActivity :
    ComponentActivity() {

    companion object {

        const val EXTRA_PACKAGE_NAME =
            "package_name"

        const val EXTRA_DISPLAY_NAME =
            "display_name"

        const val EXTRA_DELAY_SECONDS =
            "delay_seconds"

        const val EXTRA_SESSION_ID =
            "session_id"

        private const val UNLOCK_DURATION_MILLIS =
            5_000L

        @Volatile
        private var currentInstance:
                InterventionActivity? =
            null

        fun closeFromService() {

            currentInstance?.let { activity ->

                activity.runOnUiThread {

                    if (
                        !activity.isFinishing
                    ) {

                        activity.finishAndRemoveTask()
                    }
                }
            }
        }
    }

    private var targetPackageName:
            String? by mutableStateOf(null)

    private var displayName:
            String by mutableStateOf("This app")

    private var delaySeconds:
            Long by mutableLongStateOf(900L)

    private var isUnlocking:
            Boolean by mutableStateOf(false)

    private var sessionId:
            Long by mutableLongStateOf(0L)

    private var interventionScreenActive:
            Boolean by mutableStateOf(false)

    // =========================================================
    // CREATE
    // =========================================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        currentInstance =
            this

        if (
            !readIntent(intent)
        ) {

            finishAndRemoveTask()

            return
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {

                override fun handleOnBackPressed() {
                    navigateHomeAndFinish()
                }
            }
        )

        setContent {

            DontscrollTheme {

                val currentPackage =
                    targetPackageName

                if (
                    currentPackage != null
                ) {

                    InterventionScreen(

                        packageName =
                            currentPackage,

                        displayName =
                            displayName,

                        delaySeconds =
                            delaySeconds,

                        sessionId =
                            sessionId,

                        isScreenActive =
                            interventionScreenActive,

                        isUnlocking =
                            isUnlocking,

                        onUnlocked = {

                            unlockAndOpenApp(
                                currentPackage
                            )
                        },

                        onDismiss = {

                            navigateHomeAndFinish()
                        }
                    )
                }
            }
        }
    }

    private fun navigateHomeAndFinish() {

        try {

            val homeIntent =
                Intent(
                    Intent.ACTION_MAIN
                ).apply {

                    addCategory(
                        Intent.CATEGORY_HOME
                    )

                    flags =
                        Intent.FLAG_ACTIVITY_NEW_TASK
                }

            startActivity(
                homeIntent
            )

        } catch (exception: Exception) {

            Log.e(
                "DoomGuard",
                "Failed to navigate to Home",
                exception
            )

        } finally {

            finishAndRemoveTask()
        }
    }

    // =========================================================
    // RESUME
    // =========================================================

    override fun onResume() {

        super.onResume()

        currentInstance =
            this

        interventionScreenActive =
            true

        DoomGuardAccessibilityService
            .notifyInterventionScreenVisible(
                targetPackageName
            )
    }

    // =========================================================
    // PAUSE
    // =========================================================

    override fun onPause() {

        /*
         * Stop counting immediately when the intervention is no
         * longer in the foreground. onStop below handles the
         * service-side "user left intervention" state.
         */
        interventionScreenActive =
            false

        super.onPause()
    }

    // =========================================================
    // STOP
    // =========================================================

    override fun onStop() {

        super.onStop()

        interventionScreenActive =
            false

        DoomGuardAccessibilityService
            .notifyInterventionScreenHidden(
                targetPackageName
            )
    }

    // =========================================================
    // NEW INTENT
    // =========================================================

    override fun onNewIntent(
        intent: Intent?
    ) {

        super.onNewIntent(
            intent
        )

        if (
            intent == null
        ) {
            return
        }

        setIntent(
            intent
        )

        if (
            isUnlocking
        ) {
            return
        }

        readIntent(
            intent
        )
    }

    // =========================================================
    // READ INTENT
    // =========================================================

    private fun readIntent(
        intent: Intent
    ): Boolean {

        val incomingPackage =
            intent.getStringExtra(
                EXTRA_PACKAGE_NAME
            )

        if (
            incomingPackage.isNullOrBlank()
        ) {

            return false
        }

        val incomingDisplayName =
            intent
                .getStringExtra(
                    EXTRA_DISPLAY_NAME
                )
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: "This app"

        val incomingDelay =
            intent.getLongExtra(
                EXTRA_DELAY_SECONDS,
                900L
            )

        val incomingSessionId =
            intent.getLongExtra(
                EXTRA_SESSION_ID,
                0L
            )

        if (
            incomingDelay <= 0L
        ) {

            return false
        }

        targetPackageName =
            incomingPackage

        displayName =
            incomingDisplayName

        delaySeconds =
            incomingDelay

        sessionId =
            incomingSessionId

        isUnlocking =
            false

        return true
    }

    // =========================================================
    // UNLOCK
    // =========================================================

    private fun unlockAndOpenApp(
        targetPackage: String
    ) {

        if (
            isUnlocking
        ) {
            return
        }

        /*
         * Blocking may have been disabled while this screen
         * was visible.
         */
        if (
            !DoomGuardAccessibilityService
                .shouldBlock(
                    this,
                    targetPackage
                )
        ) {

            openTargetApplication(
                targetPackage
            )

            finishAndRemoveTask()

            return
        }

        isUnlocking =
            true

        /*
         * CRITICAL:
         *
         * This clears interventionTargetPackage inside the
         * service BEFORE the target app is opened.
         *
         * Therefore the target app's foreground event is treated
         * as a legitimate unlock rather than an intervention
         * restart.
         */
        DoomGuardAccessibilityService
            .completeIntervention(
                context =
                    this,

                packageName =
                    targetPackage,

                durationMillis =
                    UNLOCK_DURATION_MILLIS
            )

        val launchIntent =
            packageManager
                .getLaunchIntentForPackage(
                    targetPackage
                )

        if (launchIntent == null) {
            isUnlocking = false
            navigateHomeAndFinish()
            return
        }

        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
        )

        try {
            startActivity(launchIntent)
            finishAndRemoveTask()
        } catch (exception: Exception) {
            Log.e("DoomGuard", "Unable to launch target app", exception)
            isUnlocking = false
            navigateHomeAndFinish()
        }
    }

    // =========================================================
    // OPEN TARGET
    // =========================================================

    private fun openTargetApplication(
        packageName: String
    ) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            navigateHomeAndFinish()
            return
        }

        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
        )

        try {
            startActivity(launchIntent)
            finishAndRemoveTask()
        } catch (exception: Exception) {
            Log.e("DoomGuard", "Unable to launch target app", exception)
            navigateHomeAndFinish()
        }
    }

    // =========================================================
    // DESTROY
    // =========================================================

    override fun onDestroy() {

        interventionScreenActive =
            false

        if (
            currentInstance === this
        ) {

            currentInstance =
                null
        }

        super.onDestroy()
    }
}


/*
 * ============================================================
 * INTERVENTION SCREEN
 * ============================================================
 */

@Composable
fun InterventionScreen(

    packageName: String,

    displayName: String,

    delaySeconds: Long,

    sessionId: Long,

    isScreenActive: Boolean,

    isUnlocking: Boolean,

    onUnlocked: () -> Unit,

    onDismiss: () -> Unit

) {

    val context =
        LocalContext.current

    var remaining by
    remember(
        packageName,
        delaySeconds,
        sessionId
    ) {

        mutableLongStateOf(
            delaySeconds
        )
    }

    var screenTime by
    remember(
        packageName
    ) {

        mutableLongStateOf(
            0L
        )
    }

    // =========================================================
    // SCREEN TIME (Calculated asynchronously once upon display)
    // =========================================================

    LaunchedEffect(
        packageName,
        isScreenActive
    ) {

        if (!isScreenActive) {
            return@LaunchedEffect
        }

        withContext(Dispatchers.IO) {
            screenTime =
                ScreenTimeManager
                    .getAppTodayUsage(
                        context,
                        packageName
                    )
        }
    }

    // =========================================================
    // COUNTDOWN
    // =========================================================

    LaunchedEffect(
        packageName,
        delaySeconds,
        sessionId,
        isScreenActive
    ) {

        /*
         * Count only while the intervention Activity is actually
         * active. Leaving the screen cancels this effect and resets
         * remaining to the full configured delay.
         */
        if (!isScreenActive) {

            remaining =
                delaySeconds

            return@LaunchedEffect
        }

        remaining =
            delaySeconds

        while (remaining > 0L) {

            delay(1.seconds)

            if (!isScreenActive) {
                return@LaunchedEffect
            }

            remaining =
                (remaining - 1L).coerceAtLeast(0L)
        }
    }

    val isReady =
        remaining <= 0L

    val colors =
        MaterialTheme.colorScheme

    Column(

        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    colors.background
                )
                .padding(
                    horizontal = 28.dp,
                    vertical = 48.dp
                ),

        horizontalAlignment =
            Alignment.CenterHorizontally,

        verticalArrangement =
            Arrangement.SpaceBetween
    ) {

        Column(
            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            Spacer(
                modifier =
                    Modifier.height(
                        18.dp
                    )
            )

            Text(

                text =
                    "DONTSCROLL",

                style =
                    MaterialTheme
                        .typography
                        .headlineMedium,

                color =
                    colors.onBackground,

                textAlign =
                    TextAlign.Center
            )
        }

        Column(
            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            Text(

                text =
                    displayName,

                style =
                    MaterialTheme
                        .typography
                        .titleLarge,

                color =
                    colors.onBackground,

                textAlign =
                    TextAlign.Center
            )

            Spacer(
                modifier =
                    Modifier.height(
                        28.dp
                    )
            )

            Text(

                text =
                    if (
                        isReady
                    ) {

                        "00:00:00"

                    } else {

                        formatCountdown(
                            remaining
                        )
                    },

                style =
                    MaterialTheme
                        .typography
                        .displayLarge,

                color =
                    colors.primary,

                textAlign =
                    TextAlign.Center
            )

            Spacer(
                modifier =
                    Modifier.height(
                        10.dp
                    )
            )

            Text(

                text =
                    when {

                        isUnlocking ->
                            "Opening $displayName…"

                        isReady ->
                            "Your pause is over."

                        else ->
                            "Take a breath before you open it."
                    },

                style =
                    MaterialTheme
                        .typography
                        .bodyLarge,

                color =
                    colors.onSurfaceVariant,

                textAlign =
                    TextAlign.Center
            )

            Spacer(
                modifier =
                    Modifier.height(
                        30.dp
                    )
            )

            Card(

                modifier =
                    Modifier
                        .fillMaxWidth(),

                shape =
                    RoundedCornerShape(
                        20.dp
                    ),

                colors =
                    CardDefaults
                        .cardColors(
                            containerColor =
                                colors
                                    .surfaceContainer
                        )
            ) {

                Column(

                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = 28.dp,
                                vertical = 18.dp
                            ),

                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {

                    Text(

                        text =
                            "TODAY",

                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,

                        color =
                            colors
                                .onSurfaceVariant
                    )

                    Spacer(
                        modifier =
                            Modifier.height(
                                4.dp
                            )
                    )

                    Text(

                        text =
                            ScreenTimeManager
                                .formatDuration(
                                    screenTime
                                ),

                        style =
                            MaterialTheme
                                .typography
                                .titleLarge,

                        color =
                            colors.secondary
                    )

                    Text(

                        text =
                            "screen time",

                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,

                        color =
                            colors.onSurfaceVariant
                    )
                }
            }
        }

        Column(
            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            Button(

                onClick =
                    onUnlocked,

                enabled =
                    isReady &&
                            !isUnlocking,

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            60.dp
                        ),

                shape =
                    RoundedCornerShape(
                        20.dp
                    )
            ) {

                Text(

                    text =
                        when {

                            isUnlocking ->
                                "Opening…"

                            isReady ->
                                "Continue to $displayName"

                            else ->
                                "Wait " +
                                        formatCountdown(
                                            remaining
                                        )
                        },

                    style =
                        MaterialTheme
                            .typography
                            .titleMedium
                )
            }

            if (!isReady && !isUnlocking) {

                Spacer(
                    modifier =
                        Modifier.height(
                            8.dp
                        )
                )

                TextButton(
                    onClick = onDismiss,
                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    Text(
                        text = "I won't scroll · Go Home",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                }
            }

            Spacer(
                modifier =
                    Modifier.height(
                        12.dp
                    )
            )
        }
    }
}


/*
 * ============================================================
 * COUNTDOWN FORMAT
 * ============================================================
 */

fun formatCountdown(
    seconds: Long
): String {

    val hours =
        seconds / 3_600L

    val minutes =
        (
                seconds % 3_600L
                ) / 60L

    val secs =
        seconds % 60L

    return String.format(
        Locale.US,
        "%02d:%02d:%02d",
        hours,
        minutes,
        secs
    )
}