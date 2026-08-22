package com.sushanth.dontscroll.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.background

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent

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

import androidx.core.graphics.drawable.toBitmap

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


        private const val KEY_INTERVENTION_ACTIVE =
            "intervention_active"


        private const val KEY_INTERVENTION_PACKAGE =
            "intervention_package"


        private const val KEY_RECENT_UNLOCK_PACKAGE =
            "recent_unlock_package"


        private const val KEY_RECENT_UNLOCK_TIME =
            "recent_unlock_time"
    }


    /*
     * =========================================================
     * COMPOSE-OBSERVABLE STATE
     * =========================================================
     *
     * FIX:
     *
     * These were previously plain `var`s. Because the activity
     * is launched with FLAG_ACTIVITY_SINGLE_TOP, opening a
     * SECOND blocked app while an intervention screen is
     * already showing reuses this SAME activity instance via
     * onNewIntent() instead of creating a new one.
     *
     * onNewIntent() updated the old plain vars correctly, but
     * setContent { ... } only runs once in onCreate(), so the
     * UI never recomposed - it kept showing the FIRST app's
     * timer forever.
     *
     * Making these mutableStateOf means Compose automatically
     * recomposes InterventionScreen (and restarts its
     * LaunchedEffect timers, since they're keyed on these
     * values) whenever onNewIntent() updates them.
     */

    private var currentPackageName by
    mutableStateOf<String?>(null)

    private var currentDisplayName by
    mutableStateOf("This app")

    private var currentDelaySeconds by
    mutableLongStateOf(15L * 60L)


    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )


        if (
            !readIntent(intent)
        ) {

            finish()

            return
        }


        markInterventionActive(
            currentPackageName!!
        )


        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {

                override fun handleOnBackPressed() {
                    // Back intentionally disabled.
                }
            }
        )


        setContent {

            DontscrollTheme {

                InterventionScreen(

                    packageName =
                        currentPackageName!!,

                    displayName =
                        currentDisplayName,

                    delaySeconds =
                        currentDelaySeconds,

                    onUnlocked = {

                        unlockAndOpenApp(
                            currentPackageName!!
                        )
                    }
                )
            }
        }
    }


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


        if (
            readIntent(intent)
        ) {

            currentPackageName?.let {

                markInterventionActive(
                    it
                )
            }
        }
    }


    /*
     * =========================================================
     * READ INTENT
     * =========================================================
     */

    private fun readIntent(
        intent: Intent
    ): Boolean {

        val packageName =
            intent.getStringExtra(
                EXTRA_PACKAGE_NAME
            )


        if (
            packageName.isNullOrBlank()
        ) {

            return false
        }


        val displayName =
            intent.getStringExtra(
                EXTRA_DISPLAY_NAME
            ) ?: "This app"


        val delaySeconds =
            if (
                intent.hasExtra(
                    EXTRA_DELAY_SECONDS
                )
            ) {

                intent.getLongExtra(
                    EXTRA_DELAY_SECONDS,
                    900L
                )

            } else {

                900L
            }


        if (
            delaySeconds <= 0L
        ) {

            return false
        }


        /*
         * Assigning to these mutableStateOf-backed properties
         * is what triggers recomposition, whether this is the
         * first call (from onCreate) or a later one (from
         * onNewIntent when a different app was opened while
         * this activity was still alive).
         */

        currentPackageName =
            packageName

        currentDisplayName =
            displayName

        currentDelaySeconds =
            delaySeconds


        return true
    }


    /*
     * =========================================================
     * INTERVENTION ACTIVE
     * =========================================================
     */

    private fun markInterventionActive(
        packageName: String
    ) {

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
    }


    /*
     * =========================================================
     * UNLOCK
     * =========================================================
     */

    private fun unlockAndOpenApp(
        packageName: String
    ) {

        val now =
            SystemClock.elapsedRealtime()


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

                .putBoolean(
                    KEY_INTERVENTION_ACTIVE,
                    false
                )

                .remove(
                    KEY_INTERVENTION_PACKAGE
                )

                .putString(
                    KEY_RECENT_UNLOCK_PACKAGE,
                    packageName
                )

                .putLong(
                    KEY_RECENT_UNLOCK_TIME,
                    now
                )

                .commit()


        if (!saved) {

            return
        }


        finishAndRemoveTask()


        Handler(
            Looper.getMainLooper()
        ).post {

            openBlockedApp(
                packageName
            )
        }
    }


    /*
     * =========================================================
     * OPEN APP
     * =========================================================
     */

    private fun openBlockedApp(
        packageName: String
    ) {

        val launchIntent =
            packageManager
                .getLaunchIntentForPackage(
                    packageName
                )


        if (
            launchIntent == null
        ) {

            return
        }


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
         * Intervention remains active
         * until Continue is pressed.
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

    var remaining by remember(
        packageName,
        delaySeconds
    ) {
        mutableLongStateOf(
            delaySeconds
        )
    }

    var screenTime by remember {
        mutableLongStateOf(0L)
    }


    /*
     * ========================================================
     * SCREEN TIME
     * ========================================================
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

            delay(1000L)
        }
    }


    /*
     * ========================================================
     * COUNTDOWN
     * ========================================================
     *
     * Keyed on (packageName, delaySeconds), so this timer
     * automatically restarts whenever the activity is reused
     * for a different blocked app.
     * ========================================================
     */

    LaunchedEffect(
        packageName,
        delaySeconds
    ) {

        val endTime =
            SystemClock.elapsedRealtime() +
                    delaySeconds * 1000L

        while (true) {

            val millisRemaining =
                endTime -
                        SystemClock.elapsedRealtime()

            if (
                millisRemaining <= 0L
            ) {

                remaining = 0L

                break
            }

            remaining =
                (
                        millisRemaining +
                                999L
                        ) / 1000L

            delay(100L)
        }
    }


    val isReady =
        remaining <= 0L


    val colors =
        MaterialTheme.colorScheme


    /*
     * ========================================================
     * SCREEN
     * ========================================================
     */

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


        /*
         * ====================================================
         * HEADER
         * ====================================================
         */

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


        /*
         * ====================================================
         * MAIN CONTENT
         * ====================================================
         */

        Column(

            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {


            /*
             * APP NAME
             */

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


            /*
             * TIMER
             */

            Text(

                text =
                    if (isReady)
                        "00:00:00"
                    else
                        formatCountdown(
                            remaining
                        ),

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
                    if (isReady)
                        "Your pause is over."
                    else
                        "Take a breath before you open it.",

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


            /*
             * =================================================
             * SCREEN TIME CARD
             * =================================================
             */

            Card(

                modifier =
                    Modifier.fillMaxWidth(),

                shape =
                    RoundedCornerShape(
                        20.dp
                    ),

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


        /*
         * ====================================================
         * BOTTOM
         * ====================================================
         */

        Column(

            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            Button(

                onClick =
                    onUnlocked,

                enabled =
                    isReady,

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(60.dp),

                shape =
                    RoundedCornerShape(
                        20.dp
                    )
            ) {

                Text(

                    text =
                        if (isReady)
                            "Continue to $displayName"
                        else
                            "Wait " +
                                    formatCountdown(
                                        remaining
                                    ),

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