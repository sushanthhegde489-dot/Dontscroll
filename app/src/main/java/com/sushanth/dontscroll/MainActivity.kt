package com.sushanth.dontscroll

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

import androidx.core.graphics.drawable.toBitmap

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import com.sushanth.dontscroll.data.AppDatabase
import com.sushanth.dontscroll.data.BlockedApp
import com.sushanth.dontscroll.data.InstalledApp
import com.sushanth.dontscroll.data.getInstalledApps
import com.sushanth.dontscroll.service.DoomGuardAccessibilityService
import com.sushanth.dontscroll.ui.theme.DontscrollTheme
import com.sushanth.dontscroll.util.ScreenTimeManager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import kotlin.time.Duration.Companion.seconds
import kotlin.random.Random

import com.sushanth.dontscroll.ui.theme.ChartAmber
import com.sushanth.dontscroll.ui.theme.ChartBlue
import com.sushanth.dontscroll.ui.theme.ChartCoral
import com.sushanth.dontscroll.ui.theme.ChartMaroon
import com.sushanth.dontscroll.ui.theme.ChartPink
import com.sushanth.dontscroll.ui.theme.ChartPurple
import com.sushanth.dontscroll.ui.theme.ChartRose

// ============================================================
// MAIN ACTIVITY
// ============================================================

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

// ============================================================
// PRIVACY POLICY ACCEPTANCE
// ============================================================

private const val PREFS_NAME =
    "dontscroll_prefs"

private const val KEY_PRIVACY_POLICY_ACCEPTED =
    "privacy_policy_accepted"

fun hasAcceptedPrivacyPolicy(
    context: Context
): Boolean {

    return context
        .getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
        .getBoolean(
            KEY_PRIVACY_POLICY_ACCEPTED,
            false
        )

}

fun setPrivacyPolicyAccepted(
    context: Context
) {

    context
        .getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
        .edit()
        .putBoolean(
            KEY_PRIVACY_POLICY_ACCEPTED,
            true
        )
        .apply()

}

// ============================================================
// PRIVACY POLICY CONTENT
// ============================================================

private val privacyPolicySections =
    listOf(

        "What Dontscroll collects" to
                "Dontscroll reads your device's usage-access data to measure how long you spend in each app, and uses the Accessibility service to detect when you open an app you've protected. This information is used only to power the delays, breakdowns, and unlock delays you see in the app.",

        "Where your data lives" to
                "Your usage data and the list of apps you've protected are stored locally on your device. Dontscroll does not send this data to any server, and it is not shared with any third party.",

        "Permissions" to
                "Screen Time Access and Accessibility Service are required for Dontscroll's core features to work. You can revoke either permission at any time from your device Settings, though the app won't be able to function without them.",

        "Changes to this policy" to
                "If this policy changes, an updated version will be made available within the app.",

        "Contact" to
                "Questions or feedback about this policy can be directed to the developer via the official Google Play Store listing or at support@dontscroll.app."
    )

// ============================================================
// ACCESSIBILITY SERVICE CHECK
// ============================================================

fun isAccessibilityServiceEnabled(
    context: Context
): Boolean {
    val accessibilityManager = context.getSystemService(
        Context.ACCESSIBILITY_SERVICE
    ) as? AccessibilityManager

    val enabledServices = accessibilityManager
        ?.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )

    val isEnabledInManager = enabledServices?.any { serviceInfo ->
        val service = serviceInfo.resolveInfo?.serviceInfo ?: return@any false
        service.packageName == context.packageName &&
                service.name == "com.sushanth.dontscroll.service.DoomGuardAccessibilityService"
    } == true

    if (isEnabledInManager) {
        return true
    }

    // Fallback for OEM ROMs (MIUI, ColorOS, EMUI) where service list query can lag behind system state
    return try {
        val settingValue = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = "${context.packageName}/com.sushanth.dontscroll.service.DoomGuardAccessibilityService"
        val expectedShort = "${context.packageName}/.service.DoomGuardAccessibilityService"
        settingValue.contains(expected) || settingValue.contains(expectedShort)
    } catch (_: Exception) {
        false
    }
}

// ============================================================
// ROOT APP
// ============================================================

@Composable
fun DontscrollApp() {

    val context =
        LocalContext.current

    var privacyPolicyAccepted by remember {
        mutableStateOf(
            hasAcceptedPrivacyPolicy(
                context
            )
        )
    }

    if (!privacyPolicyAccepted) {

        PrivacyPolicyGateScreen(

            onAgree = {

                setPrivacyPolicyAccepted(
                    context
                )

                privacyPolicyAccepted = true
            }
        )

        return
    }

    var permissionRefresh by remember {
        mutableLongStateOf(
            System.currentTimeMillis()
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionRefresh = System.currentTimeMillis()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var showAccessibilityDisclosure by remember {
        mutableStateOf(false)
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
                showAccessibilityDisclosure = true
            },

            onUsageAccessClick = {

                settingsLauncher.launch(
                    Intent(
                        Settings.ACTION_USAGE_ACCESS_SETTINGS
                    )
                )
            }
        )

        if (showAccessibilityDisclosure) {
            AlertDialog(
                onDismissRequest = {
                    showAccessibilityDisclosure = false
                },
                title = {
                    Text(
                        "Accessibility Service Disclosure",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column {
                        Text(
                            "Dontscroll uses the AccessibilityService API solely to detect when you open an app you have chosen to protect, allowing Dontscroll to display the delay waiting screen."
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "• Data accessed: Foreground app package names.\n" +
                            "• Purpose: To trigger the intervention delay before opening protected apps.\n" +
                            "• Privacy: Dontscroll does NOT collect, read, or share any personal data, keystrokes, or screen contents."
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Tap 'Agree & Enable' to open Android Accessibility settings and activate Dontscroll.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showAccessibilityDisclosure = false
                            settingsLauncher.launch(
                                Intent(
                                    Settings.ACTION_ACCESSIBILITY_SETTINGS
                                )
                            )
                        }
                    ) {
                        Text(
                            "Agree & Enable",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showAccessibilityDisclosure = false
                        }
                    ) {
                        Text("Not Now")
                    }
                }
            )
        }

        return
    }

    DontscrollMainScreen(
        context = context
    )

}

// ============================================================
// PRIVACY POLICY GATE
// ============================================================

@Composable
private fun PrivacyPolicyGateScreen(
    onAgree: () -> Unit
) {

    var agreedCheckbox by remember {
        mutableStateOf(false)
    }

    Surface(

        modifier =
            Modifier.fillMaxSize(),

        color =
            MaterialTheme
                .colorScheme
                .background
    ) {

        Column(
            modifier =
                Modifier.fillMaxSize()
        ) {

            LazyColumn(

                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(
                            horizontal = 16.dp
                        ),

                verticalArrangement =
                    Arrangement.spacedBy(12.dp)
            ) {

                item {

                    Spacer(
                        Modifier.height(24.dp)
                    )

                    AppLogo()

                    Spacer(
                        Modifier.height(16.dp)
                    )

                    Text(

                        text =
                            "Before you get started",

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

                        text =
                            "Please read and agree to our Privacy Policy to continue.",

                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )
                }

                items(
                    items =
                        privacyPolicySections
                ) { (title, body) ->

                    PrivacyPolicySection(
                        title = title,
                        body = body
                    )
                }

                item {

                    Spacer(
                        Modifier.height(12.dp)
                    )
                }
            }

            Surface(

                color =
                    MaterialTheme
                        .colorScheme
                        .surface,

                shadowElevation =
                    8.dp
            ) {

                Column(
                    modifier =
                        Modifier.padding(16.dp)
                ) {

                    Row(

                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {

                        Checkbox(

                            checked =
                                agreedCheckbox,

                            onCheckedChange = {
                                agreedCheckbox = it
                            }
                        )

                        Text(
                            text =
                                "I have read and agree to the Privacy Policy",

                            modifier =
                                Modifier.weight(1f)
                        )
                    }

                    Spacer(
                        Modifier.height(10.dp)
                    )

                    Button(

                        onClick =
                            onAgree,

                        enabled =
                            agreedCheckbox,

                        modifier =
                            Modifier.fillMaxWidth(),

                        shape =
                            RoundedCornerShape(14.dp),

                        colors =
                            ButtonDefaults.buttonColors(

                                containerColor =
                                    MaterialTheme
                                        .colorScheme
                                        .primary,

                                contentColor =
                                    MaterialTheme
                                        .colorScheme
                                        .onPrimary
                            )
                    ) {

                        Text(
                            "Agree & Continue",
                            fontWeight =
                                FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

}

// ============================================================
// PERMISSIONS
// ============================================================

@Composable
fun RequiredPermissionsScreen(

    accessibilityEnabled: Boolean,

    usageAccessEnabled: Boolean,

    onAccessibilityClick: () -> Unit,

    onUsageAccessClick: () -> Unit

) {

    Surface(
        modifier =
            Modifier.fillMaxSize(),

        color =
            MaterialTheme
                .colorScheme
                .background
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

            AppLogo()

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
                    "Two permissions are required to get the app working.",

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

// ============================================================
// APP LOGO
// ============================================================

// Drop your own logo into res/drawable (or res/drawable/logo.xml for a
// vector) named exactly "logo" and it will be picked up here automatically,
// everywhere AppLogo() is used. If no such drawable exists, this falls
// back to the "DS" monogram.

@Composable
private fun AppLogo(
    size: androidx.compose.ui.unit.Dp = 56.dp
) {

    val context =
        LocalContext.current

    val logoResId =
        remember {

            context.resources.getIdentifier(
                "logo",
                "drawable",
                context.packageName
            )
        }

    Box(

        modifier =
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    MaterialTheme
                        .colorScheme
                        .primaryContainer
                ),

        contentAlignment =
            Alignment.Center
    ) {

        if (logoResId != 0) {

            Image(

                painter =
                    androidx.compose.ui.res.painterResource(
                        id = logoResId
                    ),

                contentDescription =
                    "Dontscroll logo",

                contentScale =
                    androidx.compose.ui.layout.ContentScale.Crop,

                modifier =
                    Modifier.fillMaxSize()
            )

        } else {

            Text(

                text =
                    "DS",

                fontWeight =
                    FontWeight.ExtraBold,

                color =
                    MaterialTheme
                        .colorScheme
                        .onPrimaryContainer
            )
        }
    }

}

// ============================================================
// PERMISSION CARD
// ============================================================

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
            RoundedCornerShape(24.dp),

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
                            .surface
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
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(

                                if (enabled) {

                                    MaterialTheme
                                        .colorScheme
                                        .primary

                                } else {

                                    MaterialTheme
                                        .colorScheme
                                        .secondaryContainer
                                }
                            ),

                    contentAlignment =
                        Alignment.Center
                ) {

                    Text(

                        text =
                            if (enabled)
                                "✓"
                            else
                                number,

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
                                    .onSecondaryContainer
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

                PrimaryButton(

                    text =
                        "Enable",

                    onClick =
                        onClick
                )
            }
        }
    }

}

// ============================================================
// MAIN SCREEN
// ============================================================

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

    var usageList by remember {
        mutableStateOf<List<ScreenTimeManager.AppUsage>>(emptyList())
    }

    LaunchedEffect(refresh) {
        val result = withContext(Dispatchers.IO) {
            ScreenTimeManager.getTodayUsage(context)
        }
        usageList = result
    }

    val usageMap =
        usageList.associate {

            it.packageName to
                    it.totalTimeMillis
        }

    val totalScreenTime =
        usageList.sumOf {

            it.totalTimeMillis
        }.coerceAtLeast(0L)

    if (appsLoading) {

        DontscrollAppsLoadingScreen()

        return
    }

    val pagerState =
        rememberPagerState(

            initialPage = 1,

            pageCount = {
                3
            }
        )

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

    Scaffold(

        containerColor =
            MaterialTheme
                .colorScheme
                .background,

        bottomBar = {

            NavigationBar(

                containerColor =
                    MaterialTheme
                        .colorScheme
                        .surface
            ) {

                NavigationBarItem(

                    selected =
                        currentPage == 0L,

                    onClick = {

                        scope.launch {

                            pagerState
                                .animateScrollToPage(0)
                        }
                    },

                    icon = {

                        Text(
                            "◈",
                            fontWeight =
                                FontWeight.Bold
                        )
                    },

                    label = {
                        Text("Protect")
                    }
                )

                NavigationBarItem(

                    selected =
                        currentPage == 1L,

                    onClick = {

                        scope.launch {

                            pagerState
                                .animateScrollToPage(1)
                        }
                    },

                    icon = {

                        Text(
                            "⌂",
                            fontWeight =
                                FontWeight.Bold
                        )
                    },

                    label = {
                        Text("Home")
                    }
                )

                NavigationBarItem(

                    selected =
                        currentPage == 2L,

                    onClick = {

                        scope.launch {

                            pagerState
                                .animateScrollToPage(2)
                        }
                    },

                    icon = {

                        Text(
                            "⚙",
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

        HorizontalPager(

            state =
                pagerState,

            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
        ) { page ->

            when (page) {

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

                1 -> {

                    HomeBreakdownScreen(

                        modifier =
                            Modifier.fillMaxSize(),

                        apps =
                            apps,

                        blockedApps =
                            blockedApps,

                        usageMap =
                            usageMap,

                        totalScreenTime =
                            totalScreenTime
                    )
                }

                2 -> {

                    SettingsScreen(

                        modifier =
                            Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    selectedApp?.let { app ->
        val existingBlocked = blockedApps.firstOrNull { it.packageName == app.packageName }

        DelayDialog(
            packageName = app.packageName,
            appName = app.displayName,
            screenTimeMillis = usageMap[app.packageName] ?: 0L,
            initialDelaySeconds = existingBlocked?.unlockDelaySeconds,
            initialAutomatic = existingBlocked?.automaticDelay ?: false,
            onDismiss = {
                selectedApp = null
            },

            onSave = {
                    delaySeconds,
                    automatic,
                    continuousOverrideMinutes ->

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

                    if (continuousOverrideMinutes != null) {
                        if (continuousOverrideMinutes > 0L) {
                            DoomGuardAccessibilityService
                                .setAppContinuousUsageLimitMinutes(
                                    context,
                                    app.packageName,
                                    continuousOverrideMinutes
                                )
                        } else {
                            DoomGuardAccessibilityService
                                .clearAppContinuousUsageLimit(
                                    context,
                                    app.packageName
                                )
                        }
                    }

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

// ============================================================
// HOME
// ============================================================

@Composable
fun HomeBreakdownScreen(

    modifier: Modifier,

    apps: List<InstalledApp>,

    blockedApps: List<BlockedApp>,

    usageMap: Map<String, Long>,

    totalScreenTime: Long

) {

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

    val topApps =
        sortedApps.take(6)

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

                text =
                    when {

                        totalScreenTime <
                                30 * 60 * 1000L ->

                            "You've had a relatively light day. Great job."

                        totalScreenTime <
                                60 * 60 * 1000L ->

                            "You've spent a little time on your phone today. Good going."

                        totalScreenTime <
                                2 * 60 * 60 * 1000L ->

                            "You've spent over an hour on your phone today. It's better to touch grass now."

                        totalScreenTime <
                                3 * 60 * 60 * 1000L ->

                            "You've been on your phone for a while today. Please get off your phone."

                        totalScreenTime <
                                4 * 60 * 60 * 1000L ->

                            "You've spent quite a lot of time on your phone today. A stronger pause could be useful."

                        totalScreenTime <
                                5 * 60 * 60 * 1000L ->

                            "You've had a heavy screen-time day. Consider taking a longer break before using your phone."

                        else ->

                            "You've spent a lot of time on your phone today. PLEASE go out and do something."
                    },

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }

        item {

            TodayHeroCard(

                totalScreenTime =
                    totalScreenTime,

                usedAppCount =
                    sortedApps.size
            )
        }

        item {

            CircularUsageCard(

                apps =
                    topApps,

                blockedApps =
                    blockedApps,

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

// ============================================================
// TODAY HERO
// ============================================================

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

// ============================================================
// CIRCULAR USAGE
// ============================================================

@Composable
fun CircularUsageCard(

    apps: List<Pair<InstalledApp, Long>>,

    blockedApps: List<BlockedApp>,

    otherTime: Long,

    totalScreenTime: Long,

    totalUsageAppCount: Int

) {

    val colors =
        MaterialTheme.colorScheme

    val protectedPackageNames =
        remember(blockedApps) {

            blockedApps
                .map { it.packageName }
                .toSet()
        }

    val chartColors = listOf(
        ChartMaroon,
        ChartRose,
        ChartCoral,
        ChartAmber,
        ChartPurple,
        ChartBlue,
        ChartPink
    )


    val hasOther =
        otherTime > 0L

    val chartItemCount =
        apps.size +
                if (hasOther) 1 else 0

    val density = androidx.compose.ui.platform.LocalDensity.current
    val strokeWidthPx = remember(density) {
        with(density) { 32.dp.toPx() }
    }
    val backgroundStroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx)
    }
    val arcStroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Butt)
    }

    Card(

        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(24.dp),

        colors =
            CardDefaults.cardColors(

                containerColor =
                    colors.surface
            )
    ) {

        Column(

            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
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
                    if (totalUsageAppCount == 1) {

                        "1 app used today"

                    } else {

                        "$totalUsageAppCount apps used today"
                    },

                color =
                    colors.onSurfaceVariant
            )

            Spacer(
                Modifier.height(16.dp)
            )

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
                     * Empty ring.
                     * Use a warm tinted color rather than grey.
                     */

                    drawArc(

                        color =
                            colors.primaryContainer,

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

                        style = backgroundStroke
                    )

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

                        apps.forEachIndexed {
                                index,
                                pair ->

                            val fraction =
                                pair.second.toFloat() /
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
                                                chartColors.size
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

                                style = arcStroke
                            )

                            currentAngle +=
                                sweep
                        }

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
                                    colors.secondary,

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

                                style = arcStroke
                            )
                        }
                    }
                }

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
                            colors
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
                }
            }

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
                            ],

                        isProtected =
                            protectedPackageNames.contains(
                                pair.first.packageName
                            )
                    )
                }

                if (hasOther) {

                    UsageLegendRow(

                        name =
                            "Other apps",

                        time =
                            otherTime,

                        total =
                            totalScreenTime,

                        color =
                            colors.secondary,

                        isProtected =
                            false
                    )
                }

            } else {

                Spacer(
                    Modifier.height(12.dp)
                )

                Text(

                    text =
                        "Use some apps and your breakdown will appear here.",

                    color =
                        colors.onSurfaceVariant
                )
            }
        }
    }

}

// ============================================================
// USAGE LEGEND
// ============================================================

@Composable
private fun UsageLegendRow(

    name: String,

    time: Long,

    total: Long,

    color: Color,

    isProtected: Boolean = false

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

        Row(

            modifier =
                Modifier.weight(1f),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Text(

                text =
                    name,

                modifier =
                    Modifier.weight(
                        1f,
                        fill = false
                    ),

                fontWeight =
                    FontWeight.Medium,

                maxLines =
                    1
            )

            if (isProtected) {

                Spacer(
                    Modifier.width(6.dp)
                )

                Text(
                    text = "🔒",
                    style =
                        MaterialTheme
                            .typography
                            .labelSmall
                )
            }
        }

        Text(

            text =
                ScreenTimeManager
                    .formatDuration(time),

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
                FontWeight.Bold,

            color =
                MaterialTheme
                    .colorScheme
                    .primary
        )
    }

}

// ============================================================
// PERCENTAGE
// ============================================================

private fun formatPercentage(
    percentage: Double
): String {

    return if (percentage >= 10.0) {

        "${percentage.toInt()}%"

    } else if (percentage >= 1.0) {

        "${"%.1f".format(percentage)}%"

    } else {

        "<1%"
    }

}

// ============================================================
// PROTECTED APPS
// ============================================================

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
                Modifier.height(4.dp)
            )

            Text(

                text =
                    "Add the apps you want to slow down.",

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }

        if (
            blockedApps.isNotEmpty() &&
            search.isBlank()
        ) {

            item {

                Spacer(
                    Modifier.height(8.dp)
                )

                SectionTitle(
                    "Protected apps"
                )
            }

            items(
                items =
                    blockedApps
            ) { blocked ->

                val matchingApp =
                    apps.firstOrNull {

                        it.packageName ==
                                blocked.packageName
                    }

                ProtectedAppRow(

                    app =
                        matchingApp,

                    blockedApp =
                        blocked,

                    screenTimeMillis =
                        usageMap[
                            blocked.packageName
                        ] ?: 0L,

                    onUnprotect = {
                        onUnprotect(blocked)
                    }
                )
            }
        }

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

                            onProtectApp(app)
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
                    Text("Search apps")
                }
            )
        }

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

                    if (blocked != null) {

                        onUnprotect(blocked)

                    } else {

                        onProtectApp(app)
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

// ============================================================
// SUGGESTED ROW
// ============================================================

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
            RoundedCornerShape(20.dp),

        color =
            if (blocked) {

                MaterialTheme
                    .colorScheme
                    .primaryContainer

            } else {

                MaterialTheme
                    .colorScheme
                    .surface
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

            AppIcon(
                app = app,
                size = 46.dp
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

                    maxLines =
                        1
                )

                Text(

                    text =
                        ScreenTimeManager
                            .formatDuration(time),

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
                        "Protected",
                        color =
                            MaterialTheme
                                .colorScheme
                                .primary
                    )
                }

            } else {

                PrimaryButton(

                    text =
                        "Protect",

                    onClick =
                        onClick
                )
            }
        }
    }

}

// ============================================================
// PROTECTED APP ROW
// ============================================================

@Composable
private fun ProtectedAppRow(

    app: InstalledApp?,

    blockedApp: BlockedApp,

    screenTimeMillis: Long,

    onUnprotect: () -> Unit

) {

    Surface(

        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(20.dp),

        color =
            MaterialTheme
                .colorScheme
                .primaryContainer
    ) {

        Row(

            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            if (app != null) {

                AppIcon(
                    app = app,
                    size = 46.dp
                )

                Spacer(
                    Modifier.width(12.dp)
                )
            }

            Column(
                modifier =
                    Modifier.weight(1f)
            ) {

                Text(

                    text =
                        blockedApp.displayName,

                    fontWeight =
                        FontWeight.Bold,

                    maxLines =
                        1
                )

                Text(

                    text =
                        if (blockedApp.automaticDelay) {

                            "Automatic delay · " +
                                    formatDelay(
                                        calculateAutomaticDelay(
                                            screenTimeMillis
                                        )
                                    )

                        } else {

                            "Unlock delay: " +
                                    formatDelay(
                                        blockedApp.unlockDelaySeconds
                                    )
                        },

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }

            TextButton(
                onClick =
                    onUnprotect
            ) {

                Text(
                    "Remove",
                    color =
                        MaterialTheme
                            .colorScheme
                            .primary
                )
            }
        }
    }

}

// ============================================================
// APP ROW
// ============================================================

@Composable
fun DashboardAppRow(

    app: InstalledApp,

    screenTimeMillis: Long,

    blocked: Boolean,

    onClick: () -> Unit

) {

    Surface(

        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(20.dp),

        color =
            if (blocked) {

                MaterialTheme
                    .colorScheme
                    .primaryContainer

            } else {

                MaterialTheme
                    .colorScheme
                    .surface
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

            AppIcon(
                app = app,
                size = 44.dp
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

                    maxLines =
                        1
                )

                Text(

                    text =
                        if (screenTimeMillis > 0L) {

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
                        "Protect",

                    color =
                        MaterialTheme
                            .colorScheme
                            .primary
                )
            }
        }
    }

}

// ============================================================
// APP ICON
// ============================================================

@Composable
private fun AppIcon(

    app: InstalledApp,

    size: androidx.compose.ui.unit.Dp

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
                .size(size)
                .clip(
                    RoundedCornerShape(13.dp)
                )
    )

}

// ============================================================
// SETTINGS
// ============================================================

@Composable
fun SettingsScreen(
    modifier: Modifier
) {

    val context =
        LocalContext.current

    var settingsRefresh by remember {
        mutableLongStateOf(
            System.currentTimeMillis()
        )
    }

    var showBreakDialog by remember {
        mutableStateOf(false)
    }

    var showContinuousLimitDialog by remember {
        mutableStateOf(false)
    }

    var pendingConfirmAction by remember {
        mutableStateOf<PendingConfirmAction?>(null)
    }

    var confirmStep by remember {
        mutableStateOf(0)
    }

    var showPrivacyPolicy by remember {
        mutableStateOf(false)
    }

    if (showPrivacyPolicy) {

        PrivacyPolicyScreen(

            modifier =
                modifier,

            onBack = {
                showPrivacyPolicy = false
            }
        )

        return
    }

    LaunchedEffect(Unit) {

        while (true) {

            delay(1_000L)

            settingsRefresh =
                System.currentTimeMillis()
        }
    }

    val blockingEnabled =
        remember(settingsRefresh) {

            DoomGuardAccessibilityService
                .isBlockingEnabled(context)
        }

    val breakUntil =
        remember(settingsRefresh) {

            DoomGuardAccessibilityService
                .getBreakUntil(context)
        }

    val breakActive =
        breakUntil >
                System.currentTimeMillis()

    val globalContinuousMinutes =
        remember(settingsRefresh) {
            DoomGuardAccessibilityService
                .getContinuousUsageLimitMinutes(
                    context
                )
        }

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
                Modifier.height(4.dp)
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

        // ----------------------------------------------------
        // BLOCKING
        // ----------------------------------------------------

        item {

            Card(

                modifier =
                    Modifier.fillMaxWidth(),

                shape =
                    RoundedCornerShape(24.dp),

                colors =
                    CardDefaults.cardColors(

                        containerColor =
                            if (
                                blockingEnabled &&
                                !breakActive
                            ) {

                                MaterialTheme
                                    .colorScheme
                                    .primaryContainer

                            } else {

                                MaterialTheme
                                    .colorScheme
                                    .surface
                            }
                    )
            ) {

                Column(
                    modifier =
                        Modifier.padding(20.dp)
                ) {

                    Row(

                        modifier =
                            Modifier.fillMaxWidth(),

                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {

                        Column(
                            modifier =
                                Modifier.weight(1f)
                        ) {

                            Text(

                                text =
                                    "Blocking",

                                style =
                                    MaterialTheme
                                        .typography
                                        .titleLarge,

                                fontWeight =
                                    FontWeight.Bold
                            )

                            Spacer(
                                Modifier.height(5.dp)
                            )

                            Text(

                                text =
                                    when {

                                        breakActive ->
                                            "Temporarily paused"

                                        blockingEnabled ->
                                            "Blocking is active for protected apps."

                                        else ->
                                            "Blocking is currently turned off."
                                    },

                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .onSurfaceVariant
                            )
                        }

                        Switch(

                            checked =
                                blockingEnabled &&
                                        !breakActive,

                            onCheckedChange = { enabled ->

                                if (enabled) {

                                    DoomGuardAccessibilityService
                                        .enableBlocking(
                                            context
                                        )

                                    settingsRefresh =
                                        System.currentTimeMillis()

                                } else {

                                    pendingConfirmAction =
                                        PendingConfirmAction.DisableBlocking

                                    confirmStep = 1
                                }
                            }
                        )
                    }

                    Spacer(
                        Modifier.height(14.dp)
                    )

                    Text(

                        text =
                            if (blockingEnabled) {

                                "Your protected apps will show an unlock delay when you open them."

                            } else {

                                "Your protected apps are saved, but blocking is currently disabled."
                            },

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

        // ----------------------------------------------------
        // BREAK
        // ----------------------------------------------------

        item {

            Card(

                modifier =
                    Modifier.fillMaxWidth(),

                shape =
                    RoundedCornerShape(24.dp),

                colors =
                    CardDefaults.cardColors(

                        containerColor =
                            if (breakActive) {

                                MaterialTheme
                                    .colorScheme
                                    .secondaryContainer

                            } else {

                                MaterialTheme
                                    .colorScheme
                                    .surface
                            }
                    )
            ) {

                Column(

                    modifier =
                        Modifier.padding(20.dp)
                ) {

                    Text(

                        text =
                            if (breakActive)
                                "Break active"
                            else
                                "Take a break",

                        style =
                            MaterialTheme
                                .typography
                                .titleLarge,

                        fontWeight =
                            FontWeight.Bold
                    )

                    Spacer(
                        Modifier.height(5.dp)
                    )

                    if (breakActive) {

                        Text(

                            text =
                                "Blocking is paused for " +
                                        formatRemainingBreak(
                                            breakUntil
                                        ) +
                                        ".",

                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant
                        )

                        Spacer(
                            Modifier.height(14.dp)
                        )

                        TextButton(

                            onClick = {

                                DoomGuardAccessibilityService
                                    .endBreak(
                                        context
                                    )

                                settingsRefresh =
                                    System.currentTimeMillis()
                            }
                        ) {

                            Text(
                                "End break"
                            )
                        }

                    } else {

                        Text(

                            text =
                                "Pause Dontscroll temporarily. Your protected apps will remain saved.",

                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant
                        )

                        Spacer(
                            Modifier.height(14.dp)
                        )

                        PrimaryButton(

                            text =
                                "Take a break",

                            onClick = {
                                showBreakDialog = true
                            }
                        )
                    }
                }
            }
        }

        // ----------------------------------------------------
        // CONTINUOUS INTERVENTION TIMER
        // ----------------------------------------------------

        item {
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
                                .surface
                    )
            ) {
                Column(
                    modifier =
                        Modifier.padding(20.dp)
                ) {
                    Text(
                        text =
                            "Continuous intervention",
                        style =
                            MaterialTheme
                                .typography
                                .titleLarge,
                        fontWeight =
                            FontWeight.Bold
                    )

                    Spacer(
                        Modifier.height(6.dp)
                    )

                    Text(
                        text =
                            "After you continue from an intervention, Dontscroll will intervene again after this much uninterrupted time in the app.",
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )

                    Spacer(
                        Modifier.height(12.dp)
                    )

                    Text(
                        text =
                            "$globalContinuousMinutes min systemwide",
                        fontWeight =
                            FontWeight.Bold,
                        color =
                            MaterialTheme
                                .colorScheme
                                .primary
                    )

                    Spacer(
                        Modifier.height(12.dp)
                    )

                    PrimaryButton(
                        text =
                            "Change timer",
                        onClick = {
                            showContinuousLimitDialog =
                                true
                        }
                    )
                }
            }
        }

        // ----------------------------------------------------
        // USAGE ACCESS
        // ----------------------------------------------------

        item {

            SettingsCard(

                title =
                    "Screen Time Access",

                description =
                    "Required to measure your daily app usage.",

                buttonText =
                    "Open Usage Access",

                onClick = {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        )
                    } catch (_: Exception) {
                    }
                }
            )
        }

        // ----------------------------------------------------
        // ACCESSIBILITY
        // ----------------------------------------------------

        item {

            SettingsCard(

                title =
                    "Accessibility Service",

                description =
                    "Required to detect protected apps and show the unlock delay.",

                buttonText =
                    "Open Accessibility",

                onClick = {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        )
                    } catch (_: Exception) {
                    }
                }
            )
        }

        // ----------------------------------------------------
        // PRIVACY POLICY
        // ----------------------------------------------------

        item {

            SettingsCard(

                title =
                    "Privacy Policy",

                description =
                    "See how Dontscroll handles your data.",

                buttonText =
                    "View Privacy Policy",

                onClick = {
                    showPrivacyPolicy = true
                }
            )
        }

        // ----------------------------------------------------
        // ABOUT
        // ----------------------------------------------------

        item {

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
                                .surface
                    )
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
                                .titleLarge,

                        fontWeight =
                            FontWeight.Bold
                    )

                    Spacer(
                        Modifier.height(8.dp)
                    )

                    Text(

                        text =
                            "Dontscroll helps you become more intentional with your screen time by adding friction before opening distracting apps so that you think twice before using the app.",

                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )
                }
            }
        }

        // ----------------------------------------------------
        // SUPPORT THE DEVELOPER (BUY ME A COFFEE)
        // ----------------------------------------------------

        item {
            SupportDeveloperCard()
        }

        item {

            Spacer(
                Modifier.height(24.dp)
            )
        }
    }

    if (showContinuousLimitDialog) {

        ContinuousLimitDialog(
            currentMinutes =
                globalContinuousMinutes,
            onDismiss = {
                showContinuousLimitDialog =
                    false
            },
            onSave = { minutes ->
                DoomGuardAccessibilityService
                    .setContinuousUsageLimitMinutes(
                        context,
                        minutes
                    )

                showContinuousLimitDialog =
                    false

                settingsRefresh =
                    System.currentTimeMillis()
            }
        )
    }

    if (showBreakDialog) {

        BreakDurationDialog(

            onDismiss = {
                showBreakDialog = false
            },

            onSelected = { durationMillis ->

                showBreakDialog = false

                pendingConfirmAction =
                    PendingConfirmAction.StartBreak(
                        durationMillis
                    )

                confirmStep = 1
            }
        )
    }

    if (
        confirmStep > 0 &&
        pendingConfirmAction != null
    ) {

        DoubleConfirmDialog(

            step =
                confirmStep,

            action =
                pendingConfirmAction!!,

            onCancel = {

                pendingConfirmAction = null

                confirmStep = 0
            },

            onAdvance = {

                confirmStep += 1
            },

            onConfirmed = { action ->

                when (action) {

                    is PendingConfirmAction.DisableBlocking -> {

                        DoomGuardAccessibilityService
                            .disableBlocking(
                                context
                            )
                    }

                    is PendingConfirmAction.StartBreak -> {

                        DoomGuardAccessibilityService
                            .startBreak(
                                context,
                                action.durationMillis
                            )
                    }
                }

                pendingConfirmAction = null

                confirmStep = 0

                settingsRefresh =
                    System.currentTimeMillis()
            }
        )
    }

}

// ============================================================
// SUPPORT THE DEVELOPER (BUY ME A COFFEE)
// ============================================================

private const val BUY_ME_A_COFFEE_URL =
    "https://buymeacoffee.com/idkagn"

@Composable
private fun SupportDeveloperCard() {

    val context =
        LocalContext.current

    Card(

        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(24.dp),

        colors =
            CardDefaults.cardColors(
                containerColor =
                    ChartAmber.copy(alpha = 0.18f)
            )
    ) {

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
        ) {

            Text(

                text =
                    "Enjoying Dontscroll?",

                style =
                    MaterialTheme
                        .typography
                        .titleMedium,

                fontWeight =
                    FontWeight.Bold
            )

            Spacer(
                Modifier.height(4.dp)
            )

            Text(

                text =
                    "It's free and always will be. If it has helped you scroll less and reclaim your time, you can buy me a coffee to support continued development.",

                style =
                    MaterialTheme
                        .typography
                        .bodySmall,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )

            Spacer(
                Modifier.height(14.dp)
            )

            Button(

                onClick = {
                    try {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(BUY_ME_A_COFFEE_URL)
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {
                    }
                },

                shape =
                    RoundedCornerShape(14.dp),

                colors =
                    ButtonDefaults.buttonColors(
                        containerColor =
                            ChartAmber,

                        contentColor =
                            Color.Black
                    )
            ) {

                Text(
                    "Buy me a coffee",
                    fontWeight =
                        FontWeight.Bold
                )
            }
        }
    }
}


// ============================================================
// PENDING CONFIRM ACTION
// ============================================================

private sealed class PendingConfirmAction {

    data class StartBreak(
        val durationMillis: Long
    ) : PendingConfirmAction()

    object DisableBlocking : PendingConfirmAction()

}

// ============================================================
// DOUBLE CONFIRM DIALOG
// ============================================================

// No card, no dialog box — this is a full-screen overlay. The
// confirm action jumps to a random spot on the screen each step
// so it can never be reflex-tapped, while "Never mind" stays put
// in an obvious, easy place.

@Composable
private fun DoubleConfirmDialog(

    step: Int,

    action: PendingConfirmAction,

    onCancel: () -> Unit,

    onAdvance: () -> Unit,

    onConfirmed: (PendingConfirmAction) -> Unit

) {

    val actionLabel =
        when (action) {

            is PendingConfirmAction.StartBreak ->
                "take a break"

            is PendingConfirmAction.DisableBlocking ->
                "turn off blocking"
        }

    val title =
        if (step == 1) {

            "Are you sure?"

        } else {

            "Are you really sure?"
        }

    val message =
        if (step == 1) {

            "You're about to $actionLabel. Your protected apps won't be slowed down while this is active."

        } else {

            "This is your last chance to back out. You really want to $actionLabel?"
        }

    val confirmText =
        if (step >= 2) "Yes, really" else "Yes, I'm sure"

    // A brief cooldown after each step appears, so the confirm text
    // can't be tapped through on reflex before it's even been read.

    var confirmEnabled by remember(step, action) {
        mutableStateOf(false)
    }

    LaunchedEffect(step, action) {

        confirmEnabled = false

        delay(500L)

        confirmEnabled = true
    }

    Dialog(

        onDismissRequest =
            onCancel,

        properties =
            DialogProperties(
                usePlatformDefaultWidth = false
            )
    ) {

        BoxWithConstraints(

            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Color.Black.copy(alpha = 0.82f)
                    )
        ) {

            // Re-rolled every time a new step is shown, across the
            // full screen — not confined to a small box. Margins
            // keep it clear of the edges and away from the title
            // text and the cancel button's safe zones.

            val confirmX = remember(step, action) {

                val maxX =
                    (maxWidth - 140.dp)
                        .value
                        .toInt()
                        .coerceAtLeast(1)

                Random.nextInt(0, maxX).dp
            }

            val confirmY = remember(step, action) {
                val minY = (maxHeight.value * 0.28f).toInt().coerceAtLeast(80)
                val maxY = (maxHeight.value * 0.72f).toInt().coerceAtLeast(minY + 1)
                Random.nextInt(minY, maxY).dp
            }

            Column(

                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(
                            top = 64.dp,
                            start = 28.dp,
                            end = 28.dp
                        )
            ) {

                Text(
                    text = title,
                    style =
                        MaterialTheme
                            .typography
                            .titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Spacer(
                    Modifier.height(10.dp)
                )

                Text(
                    text = message,
                    color = Color.White.copy(alpha = 0.75f)
                )
            }

            TextButton(

                onClick = {

                    if (step >= 2) {

                        onConfirmed(action)

                    } else {

                        onAdvance()
                    }
                },

                enabled =
                    confirmEnabled,

                modifier =
                    Modifier.offset(
                        x = confirmX,
                        y = confirmY
                    )
            ) {

                Text(
                    text = confirmText,
                    fontWeight = FontWeight.Bold,
                    color =
                        Color.White.copy(
                            alpha =
                                if (confirmEnabled) 0.9f else 0.3f
                        )
                )
            }

            Button(

                onClick =
                    onCancel,

                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 56.dp),

                shape =
                    RoundedCornerShape(14.dp),

                colors =
                    ButtonDefaults.buttonColors(

                        containerColor =
                            MaterialTheme
                                .colorScheme
                                .primary,

                        contentColor =
                            MaterialTheme
                                .colorScheme
                                .onPrimary
                    )
            ) {

                Text(
                    text = "Never mind",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

}

// ============================================================
// PRIVACY POLICY
// ============================================================

@Composable
fun PrivacyPolicyScreen(

    modifier: Modifier,

    onBack: () -> Unit

) {

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

            TextButton(
                onClick = onBack
            ) {

                Text(
                    "← Back to Settings",
                    color =
                        MaterialTheme
                            .colorScheme
                            .primary
                )
            }

            Spacer(
                Modifier.height(4.dp)
            )

            Text(

                text =
                    "Privacy Policy",

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

                text =
                    "Last updated: 2026",

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }

        items(
            items =
                privacyPolicySections
        ) { (title, body) ->

            PrivacyPolicySection(
                title = title,
                body = body
            )
        }

        item {

            Spacer(
                Modifier.height(24.dp)
            )
        }
    }

}

// ============================================================
// PRIVACY POLICY SECTION
// ============================================================

@Composable
private fun PrivacyPolicySection(

    title: String,

    body: String

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
                        .surface
            )
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
                    body,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }
    }

}

// ============================================================
// SETTINGS CARD
// ============================================================

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
            RoundedCornerShape(24.dp),

        colors =
            CardDefaults.cardColors(

                containerColor =
                    MaterialTheme
                        .colorScheme
                        .surface
            )
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
                        .titleLarge,

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

            PrimaryButton(

                text =
                    buttonText,

                onClick =
                    onClick
            )
        }
    }

}

// ============================================================
// PRIMARY BUTTON
// ============================================================

@Composable
private fun PrimaryButton(

    text: String,

    onClick: () -> Unit

) {

    Button(

        onClick =
            onClick,

        shape =
            RoundedCornerShape(14.dp),

        colors =
            ButtonDefaults.buttonColors(

                containerColor =
                    MaterialTheme
                        .colorScheme
                        .primary,

                contentColor =
                    MaterialTheme
                        .colorScheme
                        .onPrimary
            )
    ) {

        Text(
            text,
            fontWeight =
                FontWeight.Bold
        )
    }

}

// ============================================================
// SECTION TITLE
// ============================================================

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
                .titleSmall,

        fontWeight =
            FontWeight.Bold,

        color =
            MaterialTheme
                .colorScheme
                .onSurfaceVariant
    )

}

// ============================================================
// DELAY CALCULATION
// ============================================================

fun calculateAutomaticDelay(
    screenTimeMillis: Long
): Long {

    val minutes =
        screenTimeMillis /
                60_000L

    return when {

        minutes < 30L -> 15L

        minutes < 60L -> 30L

        minutes < 120L -> 60L

        minutes < 180L -> 120L

        minutes < 240L -> 180L

        minutes < 300L -> 300L

        else -> 600L
    }

}

// ============================================================
// FORMAT DELAY
// ============================================================

fun formatDelay(
    seconds: Long
): String {

    return when {

        seconds < 60L ->
            "$seconds sec"

        seconds % 60L == 0L -> {

            val minutes =
                seconds / 60L

            if (minutes == 1L) {

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

// ============================================================
// DELAY DIALOG
// ============================================================

@Composable
fun DelayDialog(
    packageName: String,
    appName: String,
    screenTimeMillis: Long,
    initialDelaySeconds: Long? = null,
    initialAutomatic: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (Long, Boolean, Long?) -> Unit
) {
    var automatic by remember(initialAutomatic) {
        mutableStateOf(initialAutomatic)
    }

    val initialTotal = initialDelaySeconds ?: 15L

    var hours by remember(initialDelaySeconds) {
        mutableStateOf((initialTotal / 3600L).toString())
    }

    var minutes by remember(initialDelaySeconds) {
        mutableStateOf(((initialTotal % 3600L) / 60L).toString())
    }

    var seconds by remember(initialDelaySeconds) {
        mutableStateOf((initialTotal % 60L).toString())
    }

    val automaticDelay =
        remember(screenTimeMillis) {

            calculateAutomaticDelay(
                screenTimeMillis
            )
        }

    val context =
        LocalContext.current

    var useAppContinuousOverride by remember(packageName) {
        mutableStateOf(
            DoomGuardAccessibilityService
                .getAppContinuousUsageLimitMinutes(
                    context,
                    packageName
                ) != null
        )
    }

    var appContinuousMinutes by remember(packageName) {
        mutableStateOf(
            (
                    DoomGuardAccessibilityService
                        .getAppContinuousUsageLimitMinutes(
                            context,
                            packageName
                        )
                        ?: DoomGuardAccessibilityService
                            .getContinuousUsageLimitMinutes(
                                context
                            )
                    ).toString()
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
                        "Choose how long Dontscroll should make you wait before unlocking this app.",

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
                            RoundedCornerShape(18.dp),

                        color =
                            MaterialTheme
                                .colorScheme
                                .primaryContainer
                    ) {

                        Column(

                            modifier =
                                Modifier.padding(16.dp)
                        ) {

                            Text(
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
                                    FontWeight.Bold,

                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .primary
                            )
                        }
                    }

                } else {

                    Row(

                        modifier =
                            Modifier.fillMaxWidth(),

                        horizontalArrangement =
                            Arrangement.spacedBy(6.dp)
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
                                Modifier.weight(1f),

                            label = {
                                Text("Hours")
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
                                Modifier.weight(1f),

                            label = {
                                Text("Min")
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
                                Modifier.weight(1f),

                            label = {
                                Text("Sec")
                            },

                            singleLine =
                                true
                        )
                    }
                }

                Spacer(
                    Modifier.height(14.dp)
                )

                HorizontalDivider()

                Spacer(
                    Modifier.height(10.dp)
                )

                Text(
                    text =
                        "Continuous-use intervention",
                    fontWeight =
                        FontWeight.Bold
                )

                Text(
                    text =
                        "After you continue, Dontscroll can intervene again when you stay in this app for the configured time.",
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )

                Row(
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked =
                            useAppContinuousOverride,
                        onCheckedChange = {
                            useAppContinuousOverride =
                                it
                        }
                    )

                    Text(
                        "Use an app-specific timer"
                    )
                }

                if (useAppContinuousOverride) {
                    OutlinedTextField(
                        value =
                            appContinuousMinutes,
                        onValueChange = {
                            appContinuousMinutes =
                                it.filter(
                                    Char::isDigit
                                )
                        },
                        modifier =
                            Modifier.fillMaxWidth(),
                        label = {
                            Text("Minutes")
                        },
                        singleLine = true
                    )
                } else {
                    Text(
                        text =
                            "Using systemwide timer: ${
                                DoomGuardAccessibilityService
                                    .getContinuousUsageLimitMinutes(
                                        context
                                    )
                            } min",
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color =
                            MaterialTheme
                                .colorScheme
                                .primary
                    )
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
                            automatic,
                            if (useAppContinuousOverride) {
                                appContinuousMinutes
                                    .toLongOrNull()
                                    ?.takeIf {
                                        it > 0L
                                    }
                            } else {
                                0L
                            }
                        )
                    }
                }
            ) {

                Text(
                    "Save",
                    color =
                        MaterialTheme
                            .colorScheme
                            .primary
                )
            }
        },

        dismissButton = {

            TextButton(
                onClick =
                    onDismiss
            ) {

                Text("Cancel")
            }
        }
    )

}

// ============================================================
// CONTINUOUS INTERVENTION TIMER DIALOG
// ============================================================

@Composable
private fun ContinuousLimitDialog(
    currentMinutes: Long,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit
) {
    var minutes by remember(currentMinutes) {
        mutableStateOf(
            currentMinutes.toString()
        )
    }

    AlertDialog(
        onDismissRequest =
            onDismiss,
        title = {
            Text(
                "Continuous intervention timer",
                fontWeight =
                    FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    "This timer is systemwide by default. Every protected app uses it unless you give that app its own override in its protection settings."
                )

                Spacer(
                    Modifier.height(14.dp)
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
                    label = {
                        Text("Minutes")
                    },
                    singleLine = true,
                    modifier =
                        Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value =
                        minutes
                            .toLongOrNull()
                            ?.coerceAtLeast(1L)

                    if (value != null) {
                        onSave(value)
                    }
                }
            ) {
                Text(
                    "Save",
                    color =
                        MaterialTheme
                            .colorScheme
                            .primary
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick =
                    onDismiss
            ) {
                Text("Cancel")
            }
        }
    )
}

// ============================================================
// BREAK DURATION DIALOG
// ============================================================

@Composable
private fun BreakDurationDialog(

    onDismiss: () -> Unit,

    onSelected: (Long) -> Unit

) {

    AlertDialog(

        onDismissRequest =
            onDismiss,

        title = {

            Text(

                "Take a break",

                fontWeight =
                    FontWeight.Bold
            )
        },

        text = {

            Column {

                Text(
                    "How long should Dontscroll stop blocking apps?"
                )

                Spacer(
                    Modifier.height(14.dp)
                )

                BreakOption(
                    "15 minutes"
                ) {
                    onSelected(
                        15L * 60L * 1_000L
                    )
                }

                BreakOption(
                    "30 minutes"
                ) {
                    onSelected(
                        30L * 60L * 1_000L
                    )
                }

                BreakOption(
                    "1 hour"
                ) {
                    onSelected(
                        60L * 60L * 1_000L
                    )
                }

                BreakOption(
                    "2 hours"
                ) {
                    onSelected(
                        2L * 60L * 60L * 1_000L
                    )
                }
            }
        },

        confirmButton = {},

        dismissButton = {

            TextButton(
                onClick =
                    onDismiss
            ) {

                Text("Cancel")
            }
        }
    )

}

// ============================================================
// BREAK OPTION
// ============================================================

@Composable
private fun BreakOption(

    text: String,

    onClick: () -> Unit

) {

    PrimaryButton(

        text =
            text,

        onClick =
            onClick
    )

}

// ============================================================
// BREAK TIME
// ============================================================

private fun formatRemainingBreak(
    breakUntil: Long
): String {

    val remaining =
        (
                breakUntil -
                        System.currentTimeMillis()
                )
            .coerceAtLeast(0L)

    val totalSeconds =
        remaining / 1_000L

    val hours =
        totalSeconds / 3_600L

    val minutes =
        (
                totalSeconds % 3_600L
                ) / 60L

    val seconds =
        totalSeconds % 60L

    return when {

        hours > 0L ->
            "${hours}h ${minutes}m"

        minutes > 0L ->
            "${minutes}m ${seconds}s"

        else ->
            "${seconds}s"
    }

}

// ============================================================
// LOADING
// ============================================================

@Composable
fun DontscrollAppsLoadingScreen() {

    Surface(

        modifier =
            Modifier.fillMaxSize(),

        color =
            MaterialTheme
                .colorScheme
                .background
    ) {

        Column(

            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),

            verticalArrangement =
                Arrangement.Center
        ) {

            AppLogo()

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