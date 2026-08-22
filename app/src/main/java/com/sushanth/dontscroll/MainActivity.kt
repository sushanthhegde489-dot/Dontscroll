package com.sushanth.dontscroll

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

import androidx.core.graphics.drawable.toBitmap

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import com.sushanth.dontscroll.data.AppDatabase
import com.sushanth.dontscroll.data.BlockedApp
import com.sushanth.dontscroll.data.InstalledApp
import com.sushanth.dontscroll.data.getInstalledApps
import com.sushanth.dontscroll.ui.theme.DontscrollTheme
import com.sushanth.dontscroll.util.ScreenTimeManager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import kotlin.time.Duration.Companion.seconds


class MainActivity : ComponentActivity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContent {
            DontscrollTheme {
                DontscrollApp()
            }
        }
    }
}


/*
 * ============================================================
 * ACCESSIBILITY SERVICE CHECK
 * ============================================================
 */

fun isAccessibilityServiceEnabled(
    context: Context
): Boolean {

    val accessibilityManager =
        context.getSystemService(
            Context.ACCESSIBILITY_SERVICE
        ) as AccessibilityManager

    val enabledServices =
        accessibilityManager
            .getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )

    return enabledServices.any { serviceInfo ->

        val service =
            serviceInfo.resolveInfo?.serviceInfo
                ?: return@any false

        service.packageName ==
                context.packageName &&
                service.name ==
                "com.sushanth.dontscroll.service." +
                "DoomGuardAccessibilityService"
    }
}


/*
 * ============================================================
 * ROOT APP
 * ============================================================
 */

@Composable
fun DontscrollApp() {

    val context =
        LocalContext.current

    var permissionRefresh by remember {
        mutableLongStateOf(
            System.currentTimeMillis()
        )
    }

    val settingsLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            permissionRefresh =
                System.currentTimeMillis()
        }

    val accessibilityEnabled =
        remember(permissionRefresh) {

            isAccessibilityServiceEnabled(
                context
            )
        }

    val usageAccessEnabled =
        remember(permissionRefresh) {

            ScreenTimeManager
                .hasUsageAccess(
                    context
                )
        }

    if (
        !accessibilityEnabled ||
        !usageAccessEnabled
    ) {

        RequiredPermissionsScreen(

            accessibilityEnabled =
                accessibilityEnabled,

            usageAccessEnabled =
                usageAccessEnabled,

            onAccessibilityClick = {

                settingsLauncher.launch(
                    Intent(
                        Settings.ACTION_ACCESSIBILITY_SETTINGS
                    )
                )
            },

            onUsageAccessClick = {

                settingsLauncher.launch(
                    Intent(
                        Settings.ACTION_USAGE_ACCESS_SETTINGS
                    )
                )
            }
        )

        return
    }

    DontscrollMainScreen(
        context = context
    )
}


/*
 * ============================================================
 * PERMISSIONS SCREEN
 * ============================================================
 */

@Composable
fun RequiredPermissionsScreen(

    accessibilityEnabled: Boolean,

    usageAccessEnabled: Boolean,

    onAccessibilityClick: () -> Unit,

    onUsageAccessClick: () -> Unit

) {

    Surface(
        modifier =
            Modifier.fillMaxSize()
    ) {

        Column(

            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),

            horizontalAlignment =
                Alignment.Start,

            verticalArrangement =
                Arrangement.Center
        ) {

            Box(

                modifier =
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme
                                .colorScheme
                                .primaryContainer
                        ),

                contentAlignment =
                    Alignment.Center
            ) {

                Text(
                    text = "DS",
                    fontWeight =
                        FontWeight.ExtraBold,
                    color =
                        MaterialTheme
                            .colorScheme
                            .primary
                )
            }

            Spacer(
                Modifier.height(20.dp)
            )

            Text(

                text =
                    "Let's get Dontscroll ready.",

                style =
                    MaterialTheme
                        .typography
                        .headlineLarge,

                fontWeight =
                    FontWeight.ExtraBold
            )

            Spacer(
                Modifier.height(8.dp)
            )

            Text(

                text =
                    "Two permissions are required to get the app working",

                style =
                    MaterialTheme
                        .typography
                        .bodyLarge,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )

            Spacer(
                Modifier.height(28.dp)
            )

            PermissionCard(

                number = "01",

                title =
                    "Screen Time Access",

                description =
                    "Measures how much time you spend in each app.",

                enabled =
                    usageAccessEnabled,

                onClick =
                    onUsageAccessClick
            )

            Spacer(
                Modifier.height(12.dp)
            )

            PermissionCard(

                number = "02",

                title =
                    "Accessibility Service",

                description =
                    "Detects when you open an app you've protected.",

                enabled =
                    accessibilityEnabled,

                onClick =
                    onAccessibilityClick
            )

            Spacer(
                Modifier.height(24.dp)
            )

            Text(

                text =
                    "You can change these permissions later in Settings.",

                style =
                    MaterialTheme
                        .typography
                        .bodySmall,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }
    }
}


/*
 * ============================================================
 * PERMISSION CARD
 * ============================================================
 */

@Composable
private fun PermissionCard(

    number: String,

    title: String,

    description: String,

    enabled: Boolean,

    onClick: () -> Unit

) {

    Card(

        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(20.dp),

        colors =
            CardDefaults.cardColors(

                containerColor =
                    if (enabled) {

                        MaterialTheme
                            .colorScheme
                            .primaryContainer

                    } else {

                        MaterialTheme
                            .colorScheme
                            .surfaceVariant
                    }
            )
    ) {

        Column(
            modifier =
                Modifier.padding(18.dp)
        ) {

            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Box(

                    modifier =
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (enabled) {
                                    MaterialTheme
                                        .colorScheme
                                        .primary
                                } else {
                                    MaterialTheme
                                        .colorScheme
                                        .surface
                                }
                            ),

                    contentAlignment =
                        Alignment.Center
                ) {

                    Text(

                        text =
                            if (enabled) "✓" else number,

                        fontWeight =
                            FontWeight.Bold,

                        color =
                            if (enabled) {
                                MaterialTheme
                                    .colorScheme
                                    .onPrimary
                            } else {
                                MaterialTheme
                                    .colorScheme
                                    .onSurface
                            }
                    )
                }

                Spacer(
                    Modifier.width(14.dp)
                )

                Column(
                    modifier =
                        Modifier.weight(1f)
                ) {

                    Text(

                        text =
                            title,

                        style =
                            MaterialTheme
                                .typography
                                .titleMedium,

                        fontWeight =
                            FontWeight.Bold
                    )

                    Spacer(
                        Modifier.height(3.dp)
                    )

                    Text(

                        text =
                            description,

                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,

                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )
                }
            }

            if (!enabled) {

                Spacer(
                    Modifier.height(14.dp)
                )

                Button(

                    onClick =
                        onClick,

                    modifier =
                        Modifier.fillMaxWidth(),

                    shape =
                        RoundedCornerShape(14.dp)
                ) {

                    Text(
                        "Enable"
                    )
                }
            }
        }
    }
}


/*
 * ============================================================
 * MAIN SCREEN
 *
 * PAGE ORDER:
 *
 * 0 = Protect
 * 1 = Home
 * 2 = Settings
 *
 * Home is therefore physically in the middle.
 * ============================================================
 */

@Composable
fun DontscrollMainScreen(
    context: Context
) {

    val database =
        remember {

            AppDatabase.getInstance(
                context
            )
        }

    val scope =
        rememberCoroutineScope()

    var apps by remember {

        mutableStateOf<List<InstalledApp>>(
            emptyList()
        )
    }

    var appsLoading by remember {

        mutableStateOf(true)
    }

    var refresh by remember {

        mutableLongStateOf(
            System.currentTimeMillis()
        )
    }

    var selectedApp by remember {

        mutableStateOf<InstalledApp?>(null)
    }

    /*
     * Load installed apps.
     */

    LaunchedEffect(Unit) {

        appsLoading = true

        apps =
            withContext(
                Dispatchers.IO
            ) {

                getInstalledApps(
                    context
                )
            }

        appsLoading = false
    }

    /*
     * Refresh usage every 30 seconds.
     */

    LaunchedEffect(Unit) {

        while (true) {

            delay(30.seconds)

            refresh =
                System.currentTimeMillis()
        }
    }

    val blockedApps by
    database
        .blockedAppDao()
        .getAll()
        .collectAsStateWithLifecycle(
            initialValue =
                emptyList()
        )

    /*
     * Get today's actual app usage.
     */

    val usageList =
        remember(refresh) {

            ScreenTimeManager
                .getTodayUsage(
                    context
                )
        }

    /*
     * Map:
     *
     * packageName -> milliseconds
     */

    val usageMap =
        usageList.associate {

            it.packageName to
                    it.totalTimeMillis
        }

    /*
     * IMPORTANT:
     *
     * This is TOTAL ACTUAL SCREEN TIME.
     *
     * It is NOT 24 hours.
     */

    val totalScreenTime =
        usageList.sumOf {

            it.totalTimeMillis
        }.coerceAtLeast(0L)

    if (appsLoading) {

        DontscrollAppsLoadingScreen()

        return
    }

    /*
     * ========================================================
     * PAGER
     * ========================================================
     */

    val pagerState =
        rememberPagerState(
            initialPage = 1,
            pageCount = {
                3
            }
        )

    /*
     * Keep pager and bottom navigation synchronized.
     */

    var currentPage by remember {

        mutableLongStateOf(1L)
    }

    LaunchedEffect(pagerState) {

        snapshotFlow {

            pagerState.currentPage

        }.collect { page ->

            currentPage =
                page.toLong()
        }
    }

    /*
     * ========================================================
     * UI
     * ========================================================
     */

    Scaffold(

        containerColor =
            MaterialTheme
                .colorScheme
                .background,

        bottomBar = {

            NavigationBar {

                /*
                 * PROTECT
                 */

                NavigationBarItem(

                    selected =
                        currentPage == 0L,

                    onClick = {

                        scope.launch {

                            pagerState
                                .animateScrollToPage(
                                    0
                                )
                        }
                    },

                    icon = {

                        Text(
                            text = "◈",
                            fontWeight =
                                FontWeight.Bold
                        )
                    },

                    label = {
                        Text("Protect")
                    }
                )

                /*
                 * HOME
                 *
                 * Center item.
                 */

                NavigationBarItem(

                    selected =
                        currentPage == 1L,

                    onClick = {

                        scope.launch {

                            pagerState
                                .animateScrollToPage(
                                    1
                                )
                        }
                    },

                    icon = {

                        Text(
                            text = "⌂",
                            fontWeight =
                                FontWeight.Bold
                        )
                    },

                    label = {
                        Text("Home")
                    }
                )

                /*
                 * SETTINGS
                 */

                NavigationBarItem(

                    selected =
                        currentPage == 2L,

                    onClick = {

                        scope.launch {

                            pagerState
                                .animateScrollToPage(
                                    2
                                )
                        }
                    },

                    icon = {

                        Text(
                            text = "⚙",
                            fontWeight =
                                FontWeight.Bold
                        )
                    },

                    label = {
                        Text("Settings")
                    }
                )
            }
        }

    ) { paddingValues ->

        /*
         * ====================================================
         * SWIPEABLE PAGES
         * ====================================================
         */

        HorizontalPager(

            state =
                pagerState,

            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        paddingValues
                    )

        ) { page ->

            when (page) {

                /*
                 * ============================================
                 * PAGE 0 — PROTECT
                 * ============================================
                 */

                0 -> {

                    ProtectedAppsScreen(

                        modifier =
                            Modifier.fillMaxSize(),

                        apps =
                            apps,

                        blockedApps =
                            blockedApps,

                        usageMap =
                            usageMap,

                        onProtectApp = {

                            selectedApp =
                                it
                        },

                        onUnprotect = { blocked ->

                            scope.launch {

                                database
                                    .blockedAppDao()
                                    .delete(
                                        blocked
                                    )
                            }
                        }
                    )
                }

                /*
                 * ============================================
                 * PAGE 1 — HOME
                 * ============================================
                 */

                1 -> {

                    HomeBreakdownScreen(

                        modifier =
                            Modifier.fillMaxSize(),

                        apps =
                            apps,

                        usageMap =
                            usageMap,

                        totalScreenTime =
                            totalScreenTime
                    )
                }

                /*
                 * ============================================
                 * PAGE 2 — SETTINGS
                 * ============================================
                 */

                2 -> {

                    SettingsScreen(

                        modifier =
                            Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    /*
     * ========================================================
     * PROTECT DIALOG
     * ========================================================
     */

    selectedApp?.let { app ->

        DelayDialog(

            appName =
                app.displayName,

            screenTimeMillis =
                usageMap[
                    app.packageName
                ] ?: 0L,

            onDismiss = {

                selectedApp =
                    null
            },

            onSave = {
                    delaySeconds,
                    automatic ->

                if (
                    delaySeconds > 0L
                ) {

                    val blocked =
                        BlockedApp(

                            packageName =
                                app.packageName,

                            displayName =
                                app.displayName,

                            unlockDelaySeconds =
                                delaySeconds,

                            automaticDelay =
                                automatic
                        )

                    scope.launch {

                        database
                            .blockedAppDao()
                            .insert(
                                blocked
                            )
                    }
                }

                selectedApp =
                    null
            }
        )
    }
}


/*
 * ============================================================
 * HOME — BREAKDOWN ONLY
 * ============================================================
 */

@Composable
fun HomeBreakdownScreen(

    modifier: Modifier,

    apps: List<InstalledApp>,

    usageMap: Map<String, Long>,

    totalScreenTime: Long

) {

    /*
     * Sort apps by actual usage.
     */

    val sortedApps =
        apps
            .mapNotNull { app ->

                val time =
                    usageMap[
                        app.packageName
                    ]

                if (
                    time == null ||
                    time <= 0L
                ) {

                    null

                } else {

                    app to time
                }
            }
            .sortedByDescending {
                it.second
            }

    /*
     * Top 6 apps are shown individually.
     */

    val topApps =
        sortedApps.take(6)

    /*
     * Everything else becomes "Other apps".
     */

    val topSixTime =
        topApps.sumOf {
            it.second
        }

    val otherTime =
        (
                totalScreenTime -
                        topSixTime
                ).coerceAtLeast(0L)

    LazyColumn(

        modifier =
            modifier
                .fillMaxSize()
                .padding(
                    horizontal = 16.dp
                ),

        verticalArrangement =
            Arrangement.spacedBy(12.dp)
    ) {

        item {

            Spacer(
                Modifier.height(18.dp)
            )

            Text(

                text =
                    "Dear Doomscroller",

                style =
                    MaterialTheme
                        .typography
                        .headlineMedium,

                fontWeight =
                    FontWeight.ExtraBold
            )

            Spacer(
                Modifier.height(4.dp)
            )

            Text(
                text = when {

                    totalScreenTime < 30 * 60 * 1000L ->
                        "You've had a relatively light day. " +
                                "Great job"

                    totalScreenTime < 60 * 60 * 1000L ->
                        "You've spent a little time on your phone today. " +
                                "Good going"

                    totalScreenTime < 2 * 60 * 60 * 1000L ->
                        "You've spent over an hour on your phone today. " +
                                "Its better to touch grass now."

                    totalScreenTime < 3 * 60 * 60 * 1000L ->
                        "You've been on your phone for a while today. " +
                                "Please get off your phone"

                    totalScreenTime < 4 * 60 * 60 * 1000L ->
                        "You've spent quite a lot of time on your phone today. " +
                                "A stronger pause could be useful."

                    totalScreenTime < 5 * 60 * 60 * 1000L ->
                        "You've had a heavy screen-time day. " +
                                "Consider taking a longer break before using your phone."

                    else ->
                        "You've spent a lot of time on your phone today. " +
                                "PLEASE go out and do something"
                },

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }

        /*
         * TOTAL SCREEN TIME
         */

        item {

            TodayHeroCard(

                totalScreenTime =
                    totalScreenTime,

                usedAppCount =
                    sortedApps.size
            )
        }

        /*
         * DONUT
         */

        item {

            CircularUsageCard(

                apps =
                    topApps,

                otherTime =
                    otherTime,

                totalScreenTime =
                    totalScreenTime,

                totalUsageAppCount =
                    sortedApps.size
            )
        }

        item {

            Spacer(
                Modifier.height(20.dp)
            )
        }
    }
}


/*
 * ============================================================
 * TODAY HERO CARD
 * ============================================================
 */

@Composable
fun TodayHeroCard(

    totalScreenTime: Long,

    usedAppCount: Int

) {

    Card(

        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(24.dp),

        colors =
            CardDefaults.cardColors(

                containerColor =
                    MaterialTheme
                        .colorScheme
                        .primaryContainer
            )
    ) {

        Column(
            modifier =
                Modifier.padding(20.dp)
        ) {

            Text(

                text =
                    "TODAY",

                style =
                    MaterialTheme
                        .typography
                        .labelMedium,

                fontWeight =
                    FontWeight.Bold,

                color =
                    MaterialTheme
                        .colorScheme
                        .primary
            )

            Spacer(
                Modifier.height(4.dp)
            )

            Text(

                text =
                    ScreenTimeManager
                        .formatDuration(
                            totalScreenTime
                        ),

                style =
                    MaterialTheme
                        .typography
                        .displaySmall,

                fontWeight =
                    FontWeight.ExtraBold
            )

            Text(

                text =
                    if (usedAppCount == 1) {
                        "across 1 app"
                    } else {
                        "across $usedAppCount apps"
                    },

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }
    }
}


/*
 * ============================================================
 * CIRCULAR USAGE CARD
 *
 * EVERY SEGMENT IS A PERCENTAGE OF TOTAL ACTUAL
 * SCREEN TIME.
 *
 * Example:
 *
 * Total screen time = 4 hours
 *
 * YouTube = 2 hours
 * Instagram = 1 hour
 * WhatsApp = 1 hour
 *
 * Result:
 *
 * YouTube = 50%
 * Instagram = 25%
 * WhatsApp = 25%
 *
 * NOT percentages of 24 hours.
 * ============================================================
 */

@Composable
fun CircularUsageCard(

    apps: List<Pair<InstalledApp, Long>>,

    otherTime: Long,

    totalScreenTime: Long,

    totalUsageAppCount: Int

) {

    val materialColors =
        MaterialTheme.colorScheme

    val chartColors = listOf(

        materialColors.primary,

        materialColors.secondary,

        materialColors.tertiary,

        materialColors.error,

        materialColors.primaryContainer,

        materialColors.secondaryContainer,

        materialColors.tertiaryContainer
    )

    val hasOther =
        otherTime > 0L

    val chartItemCount =
        apps.size +
                if (hasOther) 1 else 0

    Card(

        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(24.dp),

        colors =
            CardDefaults.cardColors(

                containerColor =
                    materialColors.surface
            )
    ) {

        Column(

            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
        ) {

            /*
             * HEADER
             */

            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Column(
                    modifier =
                        Modifier.weight(1f)
                ) {

                    Text(

                        text =
                            "Today's breakdown",

                        style =
                            MaterialTheme
                                .typography
                                .titleLarge,

                        fontWeight =
                            FontWeight.Bold
                    )

                    Spacer(
                        Modifier.height(3.dp)
                    )

                    Text(

                        text =
                            if (
                                totalUsageAppCount == 1
                            ) {

                                "1 app used today"

                            } else {

                                "$totalUsageAppCount apps used today"
                            },

                        color =
                            materialColors
                                .onSurfaceVariant
                    )
                }
            }

            Spacer(
                Modifier.height(16.dp)
            )

            /*
             * =================================================
             * DONUT
             * =================================================
             */

            Box(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(245.dp),

                contentAlignment =
                    Alignment.Center
            ) {

                Canvas(

                    modifier =
                        Modifier.size(215.dp)

                ) {

                    val strokeWidth =
                        32.dp.toPx()

                    val diameter =
                        size.minDimension

                    val arcSize =
                        diameter -
                                strokeWidth

                    val topLeft =
                        Offset(
                            strokeWidth / 2f,
                            strokeWidth / 2f
                        )

                    /*
                     * Base ring.
                     */

                    drawArc(

                        color =
                            materialColors
                                .surfaceVariant,

                        startAngle =
                            -90f,

                        sweepAngle =
                            360f,

                        useCenter =
                            false,

                        topLeft =
                            topLeft,

                        size =
                            androidx.compose.ui
                                .geometry
                                .Size(
                                    arcSize,
                                    arcSize
                                ),

                        style =
                            Stroke(
                                width =
                                    strokeWidth
                            )
                    )

                    /*
                     * Only draw usage segments if there
                     * is actual screen time.
                     */

                    if (
                        totalScreenTime > 0L
                    ) {

                        var currentAngle =
                            -90f

                        val gapAngle =
                            if (
                                chartItemCount > 1
                            ) {
                                1.5f
                            } else {
                                0f
                            }

                        /*
                         * APP SEGMENTS
                         */

                        apps.forEachIndexed {
                                index,
                                pair ->

                            val appTime =
                                pair.second

                            /*
                             * THIS IS THE IMPORTANT PART:
                             *
                             * appTime / TOTAL SCREEN TIME
                             *
                             * NOT appTime / 24 hours.
                             */

                            val fraction =
                                appTime.toFloat() /
                                        totalScreenTime
                                            .toFloat()

                            val sweep =
                                fraction * 360f

                            val visibleSweep =
                                (
                                        sweep -
                                                gapAngle
                                        )
                                    .coerceAtLeast(
                                        0f
                                    )

                            drawArc(

                                color =
                                    chartColors[
                                        index %
                                                chartColors
                                                    .size
                                    ],

                                startAngle =
                                    currentAngle +
                                            gapAngle / 2f,

                                sweepAngle =
                                    visibleSweep,

                                useCenter =
                                    false,

                                topLeft =
                                    topLeft,

                                size =
                                    androidx.compose.ui
                                        .geometry
                                        .Size(
                                            arcSize,
                                            arcSize
                                        ),

                                style =
                                    Stroke(
                                        width =
                                            strokeWidth,

                                        cap =
                                            StrokeCap.Butt
                                    )
                            )

                            currentAngle +=
                                sweep
                        }

                        /*
                         * OTHER APPS
                         */

                        if (hasOther) {

                            val fraction =
                                otherTime.toFloat() /
                                        totalScreenTime
                                            .toFloat()

                            val sweep =
                                fraction * 360f

                            val visibleSweep =
                                (
                                        sweep -
                                                gapAngle
                                        )
                                    .coerceAtLeast(
                                        0f
                                    )

                            drawArc(

                                color =
                                    materialColors
                                        .outlineVariant,

                                startAngle =
                                    currentAngle +
                                            gapAngle / 2f,

                                sweepAngle =
                                    visibleSweep,

                                useCenter =
                                    false,

                                topLeft =
                                    topLeft,

                                size =
                                    androidx.compose.ui
                                        .geometry
                                        .Size(
                                            arcSize,
                                            arcSize
                                        ),

                                style =
                                    Stroke(
                                        width =
                                            strokeWidth,

                                        cap =
                                            StrokeCap.Butt
                                    )
                            )
                        }
                    }
                }

                /*
                 * CENTER OF DONUT
                 */

                Column(

                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {

                    Text(

                        text =
                            "Watched for",

                        style =
                            MaterialTheme
                                .typography
                                .labelLarge,

                        color =
                            materialColors
                                .onSurfaceVariant
                    )

                    Spacer(
                        Modifier.height(2.dp)
                    )

                    Text(

                        text =
                            ScreenTimeManager
                                .formatDuration(
                                    totalScreenTime
                                ),

                        style =
                            MaterialTheme
                                .typography
                                .headlineSmall,

                        fontWeight =
                            FontWeight.ExtraBold
                    )

                    /*Text(

                        text =
                            "",

                        style =
                            MaterialTheme
                                .typography
                                .labelMedium,

                        color =
                            materialColors
                                .onSurfaceVariant
                    )*/
                }
            }

            /*
             * =================================================
             * LEGEND
             * =================================================
             */

            if (apps.isNotEmpty()) {

                Spacer(
                    Modifier.height(4.dp)
                )

                apps.forEachIndexed {
                        index,
                        pair ->

                    UsageLegendRow(

                        name =
                            pair.first
                                .displayName,

                        time =
                            pair.second,

                        total =
                            totalScreenTime,

                        color =
                            chartColors[
                                index %
                                        chartColors.size
                            ]
                    )
                }

                /*
                 * OTHER APPS LEGEND
                 */

                if (hasOther) {

                    UsageLegendRow(

                        name =
                            "Other apps",

                        time =
                            otherTime,

                        total =
                            totalScreenTime,

                        color =
                            materialColors
                                .outlineVariant
                    )
                }

            } else {

                Spacer(
                    Modifier.height(12.dp)
                )

                Text(

                    text =
                        "Use some apps and your breakdown " +
                                "will appear here.",

                    color =
                        materialColors
                            .onSurfaceVariant
                )
            }
        }
    }
}


/*
 * ============================================================
 * LEGEND ROW
 * ============================================================
 */

@Composable
private fun UsageLegendRow(

    name: String,

    time: Long,

    total: Long,

    color: Color

) {

    val percentage =

        if (total > 0L) {

            (
                    time.toDouble() /
                            total.toDouble()
                    ) * 100.0

        } else {

            0.0
        }

    Row(

        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    vertical = 6.dp
                ),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Box(

            modifier =
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
        )

        Spacer(
            Modifier.width(10.dp)
        )

        Text(

            text =
                name,

            modifier =
                Modifier.weight(1f),

            fontWeight =
                FontWeight.Medium,

            maxLines = 1
        )

        Text(

            text =
                ScreenTimeManager
                    .formatDuration(
                        time
                    ),

            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
        )

        Spacer(
            Modifier.width(10.dp)
        )

        Text(

            text =
                formatPercentage(
                    percentage
                ),

            modifier =
                Modifier.width(42.dp),

            fontWeight =
                FontWeight.Bold
        )
    }
}


/*
 * ============================================================
 * PERCENTAGE FORMAT
 * ============================================================
 */

private fun formatPercentage(
    percentage: Double
): String {

    return if (
        percentage >= 10.0
    ) {

        "${percentage.toInt()}%"

    } else if (
        percentage >= 1.0
    ) {

        "${"%.1f".format(percentage)}%"

    } else {

        "<1%"
    }
}


/*
 * ============================================================
 * PROTECT SCREEN
 *
 * TOP:
 * Most-used apps as suggestions.
 *
 * BELOW:
 * Search + all installed apps.
 * ============================================================
 */

@Composable
fun ProtectedAppsScreen(

    modifier: Modifier,

    apps: List<InstalledApp>,

    blockedApps: List<BlockedApp>,

    usageMap: Map<String, Long>,

    onProtectApp:
        (InstalledApp) -> Unit,

    onUnprotect:
        (BlockedApp) -> Unit

) {

    var search by remember {

        mutableStateOf("")
    }

    val blockedPackageNames =
        blockedApps
            .map {
                it.packageName
            }
            .toSet()

    /*
     * ========================================================
     * MOST USED APPS
     *
     * Top 5 apps with actual usage today.
     * ========================================================
     */

    val mostUsedApps =
        apps
            .mapNotNull { app ->

                val time =
                    usageMap[
                        app.packageName
                    ] ?: 0L

                if (time > 0L) {

                    app to time

                } else {

                    null
                }
            }
            .sortedByDescending {
                it.second
            }
            .take(5)

    /*
     * ========================================================
     * ALL APPS
     * ========================================================
     */

    val filteredApps =
        apps
            .filter { app ->

                search.isBlank() ||
                        app.displayName.contains(
                            search,
                            ignoreCase = true
                        )
            }
            .sortedBy {

                it.displayName
            }

    LazyColumn(

        modifier =
            modifier
                .fillMaxSize()
                .padding(
                    horizontal = 16.dp
                ),

        verticalArrangement =
            Arrangement.spacedBy(10.dp)
    ) {

        /*
         * HEADER
         */

        item {

            Spacer(
                Modifier.height(18.dp)
            )

            Text(

                text =
                    "Protect",

                style =
                    MaterialTheme
                        .typography
                        .headlineMedium,

                fontWeight =
                    FontWeight.ExtraBold
            )

            Spacer(
                Modifier.height(3.dp)
            )

            Text(

                text =
                    "Add  the apps you want having delay" ,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }

        /*
         * ====================================================
         * SUGGESTIONS
         * ====================================================
         */

        if (
            mostUsedApps.isNotEmpty() &&
            search.isBlank()
        ) {

            item {

                Spacer(
                    Modifier.height(8.dp)
                )

                SectionTitle(
                    "Suggested apps"
                )
            }

            items(
                items =
                    mostUsedApps
            ) { pair ->

                val app =
                    pair.first

                val time =
                    pair.second

                val blocked =
                    blockedPackageNames
                        .contains(
                            app.packageName
                        )

                SuggestedProtectRow(

                    app =
                        app,

                    time =
                        time,

                    blocked =
                        blocked,

                    onClick = {

                        if (blocked) {

                            blockedApps
                                .firstOrNull {

                                    it.packageName ==
                                            app.packageName

                                }?.let(
                                    onUnprotect
                                )

                        } else {

                            onProtectApp(
                                app
                            )
                        }
                    }
                )
            }

            item {

                Spacer(
                    Modifier.height(10.dp)
                )

                SectionTitle(
                    "All apps"
                )
            }
        }

        /*
         * SEARCH
         */

        item {

            OutlinedTextField(

                value =
                    search,

                onValueChange = {

                    search = it
                },

                modifier =
                    Modifier.fillMaxWidth(),

                singleLine =
                    true,

                shape =
                    RoundedCornerShape(16.dp),

                label = {

                    Text(
                        "Search apps"
                    )
                }
            )
        }

        /*
         * ====================================================
         * ALL INSTALLED APPS
         * ====================================================
         */

        items(
            items =
                filteredApps
        ) { app ->

            val blocked =
                blockedApps.firstOrNull {

                    it.packageName ==
                            app.packageName
                }

            DashboardAppRow(

                app =
                    app,

                screenTimeMillis =
                    usageMap[
                        app.packageName
                    ] ?: 0L,

                blocked =
                    blocked != null,

                onClick = {

                    if (
                        blocked != null
                    ) {

                        onUnprotect(
                            blocked
                        )

                    } else {

                        onProtectApp(
                            app
                        )
                    }
                }
            )
        }

        item {

            Spacer(
                Modifier.height(24.dp)
            )
        }
    }
}


/*
 * ============================================================
 * PROTECT SUGGESTION ROW
 * ============================================================
 */

@Composable
private fun SuggestedProtectRow(

    app: InstalledApp,

    time: Long,

    blocked: Boolean,

    onClick: () -> Unit

) {

    Surface(

        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(18.dp),

        color =
            if (blocked) {

                MaterialTheme
                    .colorScheme
                    .primaryContainer

            } else {

                MaterialTheme
                    .colorScheme
                    .surfaceVariant
            }
    ) {

        Row(

            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Image(

                bitmap =
                    remember(
                        app.packageName
                    ) {

                        app.icon
                            .toBitmap(
                                width = 96,
                                height = 96
                            )
                            .asImageBitmap()
                    },

                contentDescription =
                    app.displayName,

                modifier =
                    Modifier
                        .size(46.dp)
                        .clip(
                            RoundedCornerShape(
                                12.dp
                            )
                        )
            )

            Spacer(
                Modifier.width(12.dp)
            )

            Column(
                modifier =
                    Modifier.weight(1f)
            ) {

                Text(

                    text =
                        app.displayName,

                    fontWeight =
                        FontWeight.Bold,

                    maxLines = 1
                )

                Text(

                    text =
                        ScreenTimeManager
                            .formatDuration(
                                time
                            ),

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }

            if (blocked) {

                TextButton(

                    onClick =
                        onClick
                ) {

                    Text(
                        "Protected"
                    )
                }

            } else {

                Button(

                    onClick =
                        onClick,

                    shape =
                        RoundedCornerShape(
                            12.dp
                        )
                ) {

                    Text(
                        "Protect"
                    )
                }
            }
        }
    }
}


/*
 * ============================================================
 * APP ROW
 * ============================================================
 */

@Composable
fun DashboardAppRow(

    app: InstalledApp,

    screenTimeMillis: Long,

    blocked: Boolean,

    onClick: () -> Unit

) {

    Surface(

        modifier =
            Modifier
                .fillMaxWidth()
                .clip(
                    RoundedCornerShape(
                        18.dp
                    )
                ),

        color =
            if (blocked)

                MaterialTheme
                    .colorScheme
                    .primaryContainer

            else

                MaterialTheme
                    .colorScheme
                    .surfaceVariant
    ) {

        Row(

            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Image(

                bitmap =
                    remember(
                        app.packageName
                    ) {

                        app.icon
                            .toBitmap(
                                width = 96,
                                height = 96
                            )
                            .asImageBitmap()
                    },

                contentDescription =
                    app.displayName,

                modifier =
                    Modifier
                        .size(44.dp)
                        .clip(
                            RoundedCornerShape(
                                12.dp
                            )
                        )
            )

            Spacer(
                Modifier.width(12.dp)
            )

            Column(
                modifier =
                    Modifier.weight(1f)
            ) {

                Text(

                    text =
                        app.displayName,

                    fontWeight =
                        FontWeight.Bold,

                    maxLines = 1
                )

                Text(

                    text =
                        if (
                            screenTimeMillis > 0L
                        ) {

                            ScreenTimeManager
                                .formatDuration(
                                    screenTimeMillis
                                )

                        } else {

                            "Not used today"
                        },

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }

            TextButton(

                onClick =
                    onClick
            ) {

                Text(

                    if (blocked)
                        "Protected"
                    else
                        "Protect"
                )
            }
        }
    }
}


/*
 * ============================================================
 * SETTINGS
 * ============================================================
 */

@Composable
fun SettingsScreen(
    modifier: Modifier
) {

    val context =
        LocalContext.current

    LazyColumn(

        modifier =
            modifier
                .fillMaxSize()
                .padding(
                    horizontal = 16.dp
                ),

        verticalArrangement =
            Arrangement.spacedBy(12.dp)
    ) {

        item {

            Spacer(
                Modifier.height(18.dp)
            )

            Text(

                text =
                    "Settings",

                style =
                    MaterialTheme
                        .typography
                        .headlineMedium,

                fontWeight =
                    FontWeight.ExtraBold
            )

            Spacer(
                Modifier.height(3.dp)
            )

            Text(

                text =
                    "Manage how Dontscroll works on your phone.",

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }

        /*
         * USAGE ACCESS
         */

        item {

            SettingsCard(

                title =
                    "Screen Time Access",

                description =
                    "Required to measure your daily app usage.",

                buttonText =
                    "Open Usage Access",

                onClick = {

                    context.startActivity(

                        Intent(

                            Settings
                                .ACTION_USAGE_ACCESS_SETTINGS
                        )
                    )
                }
            )
        }

        /*
         * ACCESSIBILITY
         */

        item {

            SettingsCard(

                title =
                    "Accessibility Service",

                description =
                    "Required to detect protected apps and " +
                            "show the unlock delay.",

                buttonText =
                    "Open Accessibility",

                onClick = {

                    context.startActivity(

                        Intent(

                            Settings
                                .ACTION_ACCESSIBILITY_SETTINGS
                        )
                    )
                }
            )
        }

        /*
         * ABOUT
         */

        item {

            Card(

                modifier =
                    Modifier.fillMaxWidth(),

                shape =
                    RoundedCornerShape(20.dp)
            ) {

                Column(
                    modifier =
                        Modifier.padding(20.dp)
                ) {

                    Text(

                        text =
                            "About Dontscroll",

                        style =
                            MaterialTheme
                                .typography
                                .titleMedium,

                        fontWeight =
                            FontWeight.Bold
                    )

                    Spacer(
                        Modifier.height(8.dp)
                    )

                    Text(

                        text =
                            "Dontscroll helps you become more " +
                                    "intentional with your screen time " +
                                    "by adding friction before opening " +
                                    "distracting apps so that you " +
                                    "think twice before using the app " +
                                    "(unless you're aysh lolll)",

                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )

                    Spacer(
                        Modifier.height(12.dp)
                    )

                    Surface(

                        shape =
                            RoundedCornerShape(12.dp),

                        color =
                            MaterialTheme
                                .colorScheme
                                .surfaceVariant
                    ) {

                        Spacer(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                        )
                    }
                }
            }
        }

        item {

            Spacer(
                Modifier.height(24.dp)
            )
        }
    }
}


/*
 * ============================================================
 * SETTINGS CARD
 * ============================================================
 */

@Composable
private fun SettingsCard(

    title: String,

    description: String,

    buttonText: String,

    onClick: () -> Unit

) {

    Card(

        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(20.dp)
    ) {

        Column(
            modifier =
                Modifier.padding(20.dp)
        ) {

            Text(

                text =
                    title,

                style =
                    MaterialTheme
                        .typography
                        .titleMedium,

                fontWeight =
                    FontWeight.Bold
            )

            Spacer(
                Modifier.height(6.dp)
            )

            Text(

                text =
                    description,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )

            Spacer(
                Modifier.height(14.dp)
            )

            Button(

                onClick =
                    onClick,

                shape =
                    RoundedCornerShape(13.dp)
            ) {

                Text(
                    buttonText
                )
            }
        }
    }
}


/*
 * ============================================================
 * SECTION TITLE
 * ============================================================
 */

@Composable
fun SectionTitle(
    text: String
) {

    Text(

        text =
            text,

        style =
            MaterialTheme
                .typography
                .titleLarge,

        fontWeight =
            FontWeight.ExtraBold
    )
}


/*
 * ============================================================
 * DELAY CALCULATION
 * ============================================================
 */

fun calculateAutomaticDelay(
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


/*
 * ============================================================
 * FORMAT DELAY
 * ============================================================
 */

fun formatDelay(
    seconds: Long
): String {

    return when {

        seconds < 60L ->
            "$seconds sec"

        seconds % 60L == 0L -> {

            val minutes =
                seconds / 60L

            if (
                minutes == 1L
            ) {

                "1 min"

            } else {

                "$minutes min"
            }
        }

        else -> {

            val minutes =
                seconds / 60L

            val remaining =
                seconds % 60L

            "$minutes min $remaining sec"
        }
    }
}


/*
 * ============================================================
 * DELAY DIALOG
 * ============================================================
 */

@Composable
fun DelayDialog(

    appName: String,

    screenTimeMillis: Long,

    onDismiss: () -> Unit,

    onSave:
        (
        Long,
        Boolean
    ) -> Unit

) {

    var automatic by remember {

        mutableStateOf(false)
    }

    var hours by remember {

        mutableStateOf("0")
    }

    var minutes by remember {

        mutableStateOf("0")
    }

    var seconds by remember {

        mutableStateOf("3")
    }

    val automaticDelay =
        remember(screenTimeMillis) {

            calculateAutomaticDelay(
                screenTimeMillis
            )
        }

    AlertDialog(

        onDismissRequest =
            onDismiss,

        title = {

            Text(
                text =
                    "Protect $appName",
                fontWeight =
                    FontWeight.Bold
            )
        },

        text = {

            Column {

                Text(

                    text =
                        "Choose how long Dontscroll " +
                                "should make you wait before " +
                                "unlocking this app.",

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )

                Spacer(
                    Modifier.height(16.dp)
                )

                Row(

                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    Checkbox(

                        checked =
                            automatic,

                        onCheckedChange = {
                            automatic = it
                        }
                    )

                    Column {

                        Text(

                            text =
                                "Automatic delay",

                            fontWeight =
                                FontWeight.Bold
                        )

                        Text(

                            text =
                                "Increase delay time as today's usage grows.",

                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,

                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant
                        )
                    }
                }

                Spacer(
                    Modifier.height(8.dp)
                )

                if (automatic) {

                    Surface(

                        modifier =
                            Modifier.fillMaxWidth(),

                        shape =
                            RoundedCornerShape(
                                16.dp
                            ),

                        color =
                            MaterialTheme
                                .colorScheme
                                .primaryContainer
                    ) {

                        Column(

                            modifier =
                                Modifier.padding(
                                    16.dp
                                )
                        ) {

                            Text(
                                text =
                                    "Today's usage"
                            )

                            Text(

                                text =
                                    ScreenTimeManager
                                        .formatDuration(
                                            screenTimeMillis
                                        ),

                                fontWeight =
                                    FontWeight.ExtraBold
                            )

                            Spacer(
                                Modifier.height(6.dp)
                            )

                            Text(

                                text =
                                    "Unlock delay: ${
                                        formatDelay(
                                            automaticDelay
                                        )
                                    }",

                                fontWeight =
                                    FontWeight.Bold
                            )
                        }
                    }

                } else {

                    Row(

                        modifier =
                            Modifier.fillMaxWidth(),

                        horizontalArrangement =
                            Arrangement.spacedBy(
                                6.dp
                            )
                    ) {

                        OutlinedTextField(

                            value =
                                hours,

                            onValueChange = {

                                hours =
                                    it.filter(
                                        Char::isDigit
                                    )
                            },

                            modifier =
                                Modifier.weight(
                                    1f
                                ),

                            label = {
                                Text(
                                    "Hours"
                                )
                            },

                            singleLine =
                                true
                        )

                        OutlinedTextField(

                            value =
                                minutes,

                            onValueChange = {

                                minutes =
                                    it.filter(
                                        Char::isDigit
                                    )
                            },

                            modifier =
                                Modifier.weight(
                                    1f
                                ),

                            label = {
                                Text(
                                    "Min"
                                )
                            },

                            singleLine =
                                true
                        )

                        OutlinedTextField(

                            value =
                                seconds,

                            onValueChange = {

                                seconds =
                                    it.filter(
                                        Char::isDigit
                                    )
                            },

                            modifier =
                                Modifier.weight(
                                    1f
                                ),

                            label = {
                                Text(
                                    "Sec"
                                )
                            },

                            singleLine =
                                true
                        )
                    }
                }
            }
        },

        confirmButton = {

            TextButton(

                onClick = {

                    val delaySeconds =

                        if (automatic) {

                            automaticDelay

                        } else {

                            val h =
                                hours
                                    .toLongOrNull()
                                    ?: 0L

                            val m =
                                minutes
                                    .toLongOrNull()
                                    ?: 0L

                            val s =
                                seconds
                                    .toLongOrNull()
                                    ?: 0L

                            h * 3600L +
                                    m * 60L +
                                    s
                        }

                    if (
                        delaySeconds > 0L
                    ) {

                        onSave(

                            delaySeconds,

                            automatic
                        )
                    }
                }
            ) {

                Text(
                    "Save"
                )
            }
        },

        dismissButton = {

            TextButton(

                onClick =
                    onDismiss
            ) {

                Text(
                    "Cancel"
                )
            }
        }
    )
}


/*
 * ============================================================
 * APPS LOADING
 * ============================================================
 */

@Composable
fun DontscrollAppsLoadingScreen() {

    Surface(
        modifier =
            Modifier.fillMaxSize()
    ) {

        Column(

            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),

            verticalArrangement =
                Arrangement.Center
        ) {

            Box(

                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme
                                .colorScheme
                                .primaryContainer
                        ),

                contentAlignment =
                    Alignment.Center
            ) {

                Text(
                    text = "DS",
                    fontWeight =
                        FontWeight.ExtraBold,
                    color =
                        MaterialTheme
                            .colorScheme
                            .primary
                )
            }

            Spacer(
                Modifier.height(18.dp)
            )

            Text(

                text =
                    "Loading your apps…",

                style =
                    MaterialTheme
                        .typography
                        .headlineSmall,

                fontWeight =
                    FontWeight.ExtraBold
            )

            Spacer(
                Modifier.height(6.dp)
            )

            Text(

                text =
                    "Just getting your dashboard ready.",

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }
    }
}