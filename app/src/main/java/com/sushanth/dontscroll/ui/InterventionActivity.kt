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
import android.os.SystemClock

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


        private const val KEY_INTERVENTION_ACTIVE =
            "intervention_active"


        private const val KEY_INTERVENTION_PACKAGE =
            "intervention_package"


        private const val KEY_RECENT_UNLOCK_PACKAGE =
            "recent_unlock_package"


        private const val KEY_RECENT_UNLOCK_TIME =
            "recent_unlock_time"
    }


    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )


        /*
         * Get protected package.
         */
        val packageName =
            intent.getStringExtra(
                EXTRA_PACKAGE_NAME
            ) ?: run {

                finish()

                return
            }


        /*
         * Get display name.
         */
        val displayName =
            intent.getStringExtra(
                EXTRA_DISPLAY_NAME
            ) ?: "This app"


        /*
         * Get configured delay.
         */
        val delaySeconds =
            intent.getLongExtra(
                EXTRA_DELAY_SECONDS,
                30L
            )


        /*
         * =====================================================
         * MARK INTERVENTION ACTIVE
         * =====================================================
         *
         * Do this every time the intervention Activity is
         * created/reused for a protected package.
         */
        getSharedPreferences(
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


        /*
         * Completely disable Back.
         */
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {

                override fun handleOnBackPressed() {

                    /*
                     * Intentionally empty.
                     *
                     * Back cannot bypass the intervention.
                     */
                }
            }
        )


        /*
         * Compose UI.
         */
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
     * =========================================================
     * CONTINUE
     * =========================================================
     */
    private fun unlockAndOpenApp(
        packageName: String
    ) {

        /*
         * IMPORTANT:
         *
         * Everything is committed BEFORE Instagram/YouTube/etc.
         * is launched.
         *
         * This prevents the AccessibilityService from seeing
         * the target app while intervention_active is still true.
         */
        val now =
            SystemClock.elapsedRealtime()


        val saved =
            getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
                .edit()

                /*
                 * Explicitly unlock this package.
                 */
                .putString(
                    KEY_ALLOWED_PACKAGE,
                    packageName
                )

                /*
                 * Intervention is now finished.
                 */
                .putBoolean(
                    KEY_INTERVENTION_ACTIVE,
                    false
                )

                /*
                 * No active intervention package anymore.
                 */
                .remove(
                    KEY_INTERVENTION_PACKAGE
                )

                /*
                 * Remember intentional unlock.
                 */
                .putString(
                    KEY_RECENT_UNLOCK_PACKAGE,
                    packageName
                )

                .putLong(
                    KEY_RECENT_UNLOCK_TIME,
                    now
                )

                .commit()


        /*
         * Never launch the app if state couldn't be persisted.
         */
        if (!saved) {
            return
        }


        /*
         * Remove this Activity/task.
         */
        finishAndRemoveTask()


        /*
         * Give Android one main-loop turn to process the
         * Activity removal.
         */
        Handler(
            Looper.getMainLooper()
        ).post {

            openBlockedApp(
                packageName
            )
        }
    }


    /*
     * Launch the protected application.
     */
    private fun openBlockedApp(
        packageName: String
    ) {

        val launchIntent =
            packageManager
                .getLaunchIntentForPackage(
                    packageName
                )


        /*
         * No launcher Activity.
         */
        if (
            launchIntent == null
        ) {
            return
        }


        /*
         * Explicitly launch the package.
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
         * DO NOT change intervention state here.
         *
         * onPause() can happen because of:
         *
         * - Recents
         * - system dialogs
         * - resolver
         * - cloned-app UI
         * - Activity transitions
         *
         * The intervention remains active until Continue.
         */
    }
}


/*
 * ============================================================
 * INTERVENTION UI
 * ============================================================
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


    /*
     * Countdown.
     */
    var remaining by remember {

        mutableLongStateOf(
            delaySeconds
        )
    }


    /*
     * Today's screen time.
     */
    var screenTime by remember {

        mutableLongStateOf(
            0L
        )
    }


    /*
     * Update screen time every second.
     */
    LaunchedEffect(
        packageName
    ) {

        while (true) {

            screenTime =
                ScreenTimeManager
                    .getAppTodayUsage(
                        context,
                        packageName
                    )

            delay(
                1000L
            )
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


        while (
            remaining > 0L
        ) {

            delay(
                1000L
            )

            remaining--
        }
    }


    Column(

        modifier =
            Modifier
                .fillMaxSize()
                .padding(
                    32.dp
                ),

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
            Modifier.height(
                24.dp
            )
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
            Modifier.height(
                32.dp
            )
        )


        Text(
            text =
                "Screen time today"
        )


        Spacer(
            Modifier.height(
                8.dp
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
                    .headlineSmall
        )


        Spacer(
            Modifier.height(
                40.dp
            )
        )


        if (
            remaining > 0L
        ) {

            Text(
                text =
                    "Wait before opening"
            )


            Spacer(
                Modifier.height(
                    12.dp
                )
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
                Modifier.height(
                    16.dp
                )
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
                Modifier.height(
                    24.dp
                )
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
 * ============================================================
 * COUNTDOWN FORMATTER
 * ============================================================
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