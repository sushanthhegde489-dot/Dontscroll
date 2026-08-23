package com.sushanth.dontscroll.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock

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

import kotlinx.coroutines.delay

import java.util.Locale


class InterventionActivity : ComponentActivity() {

    companion object {

        const val EXTRA_PACKAGE_NAME =
            "package_name"

        const val EXTRA_DISPLAY_NAME =
            "display_name"

        const val EXTRA_DELAY_SECONDS =
            "delay_seconds"

        private const val UNLOCK_DURATION_MILLIS =
            5_000L
    }


    private var targetPackageName: String? by
    mutableStateOf(null)

    private var displayName: String by
    mutableStateOf("This app")

    private var delaySeconds: Long by
    mutableLongStateOf(900L)

    private var isUnlocking: Boolean by
    mutableStateOf(false)


    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        if (!readIntent(intent)) {
            finish()
            return
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {

                override fun handleOnBackPressed() {
                    /*
                     * Intentionally disabled.
                     */
                }
            }
        )

        setContent {

            DontscrollTheme {

                val currentPackage =
                    targetPackageName

                if (currentPackage != null) {

                    InterventionScreen(

                        packageName =
                            currentPackage,

                        displayName =
                            displayName,

                        delaySeconds =
                            delaySeconds,

                        isUnlocking =
                            isUnlocking,

                        onUnlocked = {

                            unlockAndOpenApp(
                                currentPackage
                            )
                        }
                    )
                }
            }
        }
    }


    override fun onNewIntent(
        intent: Intent?
    ) {

        super.onNewIntent(intent)

        if (intent == null) {
            return
        }

        setIntent(intent)

        if (isUnlocking) {
            return
        }

        readIntent(intent)
    }


    private fun readIntent(
        intent: Intent
    ): Boolean {

        val incomingPackage =
            intent.getStringExtra(
                EXTRA_PACKAGE_NAME
            )

        if (incomingPackage.isNullOrBlank()) {
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

        if (incomingDelay <= 0L) {
            return false
        }

        targetPackageName =
            incomingPackage

        displayName =
            incomingDisplayName

        delaySeconds =
            incomingDelay

        isUnlocking =
            false

        return true
    }


    private fun unlockAndOpenApp(
        targetPackage: String
    ) {

        if (isUnlocking) {
            return
        }

        /*
         * If blocking was turned off or a break was started
         * while this screen was open, do not create another
         * intervention state.
         */

        if (
            !DoomGuardAccessibilityService
                .shouldBlock(
                    this,
                    targetPackage
                )
        ) {

            val intent =
                packageManager
                    .getLaunchIntentForPackage(
                        targetPackage
                    )

            if (intent != null) {

                try {

                    intent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )

                    startActivity(intent)

                } catch (
                    exception: Exception
                ) {

                    exception.printStackTrace()
                }
            }

            finishAndRemoveTask()

            return
        }

        isUnlocking =
            true


        DoomGuardAccessibilityService
            .completeIntervention(
                context = this,
                packageName = targetPackage,
                durationMillis =
                    UNLOCK_DURATION_MILLIS
            )


        val launchIntent =
            packageManager
                .getLaunchIntentForPackage(
                    targetPackage
                )

        if (launchIntent == null) {

            isUnlocking =
                false

            return
        }


        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
        )


        try {

            DoomGuardAccessibilityService
                .prepareForTargetAppReturn(
                    targetPackage
                )

            startActivity(
                launchIntent
            )

            finishAndRemoveTask()

        } catch (
            exception: Exception
        ) {

            exception.printStackTrace()

            isUnlocking =
                false
        }
    }


    override fun onPause() {

        super.onPause()

        /*
         * Service owns intervention state.
         */
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

    isUnlocking: Boolean,

    onUnlocked: () -> Unit

) {

    val context =
        LocalContext.current

    var remaining by
    remember(
        packageName,
        delaySeconds
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


    /*
     * ========================================================
     * SCREEN TIME
     * ========================================================
     */

    LaunchedEffect(packageName) {

        while (true) {

            screenTime =
                ScreenTimeManager
                    .getAppTodayUsage(
                        context,
                        packageName
                    )

            delay(1_000L)
        }
    }


    /*
     * ========================================================
     * COUNTDOWN
     * ========================================================
     */

    LaunchedEffect(
        packageName,
        delaySeconds
    ) {

        val endTime =
            SystemClock.elapsedRealtime() +
                    delaySeconds * 1_000L

        while (true) {

            val millisRemaining =
                endTime -
                        SystemClock.elapsedRealtime()

            if (millisRemaining <= 0L) {

                remaining =
                    0L

                break
            }

            remaining =
                (
                        millisRemaining +
                                999L
                        ) / 1_000L

            delay(100L)
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
                    Modifier.height(18.dp)
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
                    Modifier.height(28.dp)
            )

            Text(

                text =
                    if (isReady) {
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
                    Modifier.height(10.dp)
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
                    Modifier.height(30.dp)
            )

            Card(

                modifier =
                    Modifier.fillMaxWidth(),

                shape =
                    RoundedCornerShape(20.dp),

                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            colors.surfaceContainer
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
                            colors.onSurfaceVariant
                    )

                    Spacer(
                        modifier =
                            Modifier.height(4.dp)
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
                        .height(60.dp),

                shape =
                    RoundedCornerShape(20.dp)
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

            Spacer(
                modifier =
                    Modifier.height(12.dp)
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