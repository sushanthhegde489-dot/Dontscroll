package com.sushanth.dontscroll.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sushanth.dontscroll.ui.theme.DontscrollTheme
import com.sushanth.dontscroll.util.ScreenTimeManager
import kotlinx.coroutines.delay
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

        private const val PREFS_NAME =
            "dontscroll_intervention"

        private const val KEY_ALLOWED_PACKAGE =
            "allowed_package"
    }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        val packageName =
            intent.getStringExtra(
                EXTRA_PACKAGE_NAME
            ) ?: run {
                finish()
                return
            }

        val displayName =
            intent.getStringExtra(
                EXTRA_DISPLAY_NAME
            ) ?: "This app"

        val delaySeconds =
            intent.getLongExtra(
                EXTRA_DELAY_SECONDS,
                30L
            )

        /*
         * Completely disable Back.
         */
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {

                override fun handleOnBackPressed() {
                    /*
                     * Intentionally empty.
                     */
                }
            }
        )

        setContent {

            DontscrollTheme {

                InterventionScreen(
                    packageName =
                        packageName,

                    displayName =
                        displayName,

                    delaySeconds =
                        delaySeconds,

                    onUnlocked = {

                        unlockAndOpenApp(
                            packageName
                        )
                    }
                )
            }
        }
    }

    /*
     * User pressed Continue.
     *
     * The important order is:
     *
     * 1. Persist unlock.
     * 2. Remove intervention task/window.
     * 3. Launch protected app after the intervention
     *    has been removed from the foreground.
     */
    private fun unlockAndOpenApp(
        packageName: String
    ) {

        /*
         * Persist synchronously.
         *
         * This guarantees the accessibility service can see
         * the unlock before the protected app becomes
         * foreground.
         */
        val saved =
            getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
                .edit()
                .putString(
                    KEY_ALLOWED_PACKAGE,
                    packageName
                )
                .commit()

        if (!saved) {
            /*
             * If the preference could not be persisted,
             * do not launch the protected app.
             */
            return
        }

        /*
         * Remove this Activity/task first.
         *
         * This is intentional: we don't want the intervention
         * window sitting underneath the Android clone-app
         * resolver.
         */
        finishAndRemoveTask()

        /*
         * Give Android one main-loop turn to destroy/remove
         * the intervention window.
         *
         * Then launch the protected application.
         */
        Handler(
            Looper.getMainLooper()
        ).post {

            openBlockedApp(
                packageName
            )
        }
    }

    private fun openBlockedApp(
        packageName: String
    ) {

        val launchIntent =
            packageManager
                .getLaunchIntentForPackage(
                    packageName
                )

        if (launchIntent == null) {
            return
        }

        /*
         * Explicitly launch the package's launcher activity.
         *
         * This avoids creating a generic implicit Intent.
         */
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
        )

        try {

            startActivity(
                launchIntent
            )

        } catch (e: Exception) {

            e.printStackTrace()
        }
    }

    override fun onPause() {

        super.onPause()

        /*
         * Do not modify the countdown or unlock state here.
         */
    }
}

/*
 * Intervention UI.
 */
@Composable
fun InterventionScreen(
    packageName: String,
    displayName: String,
    delaySeconds: Long,
    onUnlocked: () -> Unit
) {
    val context =
        LocalContext.current

    var remaining by remember {

        mutableLongStateOf(
            delaySeconds
        )
    }

    var screenTime by remember {

        mutableLongStateOf(0L)
    }

    /*
     * Update today's screen time.
     */
    LaunchedEffect(packageName) {

        while (true) {

            screenTime =
                ScreenTimeManager
                    .getAppTodayUsage(
                        context,
                        packageName
                    )

            delay(1000L)
        }
    }

    /*
     * Countdown.
     */
    LaunchedEffect(
        packageName,
        delaySeconds
    ) {

        remaining =
            delaySeconds

        while (remaining > 0L) {

            delay(1000L)

            remaining--
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(32.dp),

        horizontalAlignment =
            Alignment.CenterHorizontally,

        verticalArrangement =
            Arrangement.Center
    ) {

        Text(
            text =
                "DON'T SCROLL",

            style =
                MaterialTheme
                    .typography
                    .headlineLarge,

            color =
                MaterialTheme
                    .colorScheme
                    .primary
        )

        Spacer(
            Modifier.height(24.dp)
        )

        Text(
            text =
                displayName,

            style =
                MaterialTheme
                    .typography
                    .headlineMedium
        )

        Spacer(
            Modifier.height(32.dp)
        )

        Text(
            text =
                "Screen time today"
        )

        Spacer(
            Modifier.height(8.dp)
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
                    .headlineSmall
        )

        Spacer(
            Modifier.height(40.dp)
        )

        if (remaining > 0L) {

            Text(
                text =
                    "Wait before opening"
            )

            Spacer(
                Modifier.height(12.dp)
            )

            Text(
                text =
                    formatCountdown(
                        remaining
                    ),

                style =
                    MaterialTheme
                        .typography
                        .displayMedium
            )

            Spacer(
                Modifier.height(16.dp)
            )

            Text(
                text =
                    "You have to wait before " +
                            "continuing."
            )

        } else {

            Text(
                text =
                    "You waited.",

                style =
                    MaterialTheme
                        .typography
                        .headlineSmall
            )

            Spacer(
                Modifier.height(24.dp)
            )

            Button(
                onClick =
                    onUnlocked
            ) {

                Text(
                    text =
                        "Continue"
                )
            }
        }
    }
}

/*
 * Formats seconds as HH:MM:SS.
 */
fun formatCountdown(
    seconds: Long
): String {

    val hours =
        seconds / 3600L

    val minutes =
        (seconds % 3600L) / 60L

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