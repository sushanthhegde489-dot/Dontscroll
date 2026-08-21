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

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
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


class MainActivity : ComponentActivity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

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


    /*
     * Used to refresh permission state.
     */
    var permissionRefresh by remember {

        mutableLongStateOf(
            System.currentTimeMillis()
        )
    }


    /*
     * Fake startup loading.
     */
    var showLoading by remember {

        mutableStateOf(true)
    }


    /*
     * Android settings launcher.
     */
    val settingsLauncher =
        rememberLauncherForActivityResult(

            ActivityResultContracts
                .StartActivityForResult()

        ) {

            permissionRefresh =
                System.currentTimeMillis()
        }


    /*
     * Accessibility permission.
     */
    val accessibilityEnabled =
        remember(permissionRefresh) {

            isAccessibilityServiceEnabled(
                context
            )
        }


    /*
     * Usage access permission.
     */
    val usageAccessEnabled =
        remember(permissionRefresh) {

            ScreenTimeManager
                .hasUsageAccess(
                    context
                )
        }


    /*
     * --------------------------------------------------------
     * FAKE STARTUP LOADING
     * --------------------------------------------------------
     *
     * This is intentionally short.
     */
    LaunchedEffect(Unit) {

        delay(900L)

        showLoading = false
    }


    /*
     * --------------------------------------------------------
     * KEEP CHECKING PERMISSIONS
     * --------------------------------------------------------
     */
    LaunchedEffect(Unit) {

        while (true) {

            delay(1000L)

            permissionRefresh =
                System.currentTimeMillis()
        }
    }


    /*
     * --------------------------------------------------------
     * SHOW STARTUP LOADING
     * --------------------------------------------------------
     */
    if (showLoading) {

        DontscrollLoadingScreen()

        return
    }


    /*
     * --------------------------------------------------------
     * REQUIRED PERMISSIONS
     * --------------------------------------------------------
     */
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
                        Settings
                            .ACTION_ACCESSIBILITY_SETTINGS
                    )
                )
            },

            onUsageAccessClick = {

                settingsLauncher.launch(

                    Intent(
                        Settings
                            .ACTION_USAGE_ACCESS_SETTINGS
                    )
                )
            }
        )

        return
    }


    /*
     * --------------------------------------------------------
     * MAIN SCREEN
     * --------------------------------------------------------
     */
    DontscrollMainScreen(
        context = context
    )
}


/*
 * ============================================================
 * STARTUP LOADING SCREEN
 * ============================================================
 */

@Composable
fun DontscrollLoadingScreen() {

    Surface(

        modifier =
            Modifier.fillMaxSize(),

        color =
            MaterialTheme
                .colorScheme
                .background
    ) {

        Box(

            modifier =
                Modifier.fillMaxSize(),

            contentAlignment =
                Alignment.Center
        ) {

            Column(

                horizontalAlignment =
                    Alignment.CenterHorizontally,

                verticalArrangement =
                    Arrangement.Center
            ) {

                Text(

                    text =
                        "DONTSCROLL",

                    style =
                        MaterialTheme
                            .typography
                            .displaySmall,

                    color =
                        MaterialTheme
                            .colorScheme
                            .primary
                )

                Spacer(
                    Modifier.height(8.dp)
                )

                Text(

                    text =
                        "Take control of your scrolling.",

                    style =
                        MaterialTheme
                            .typography
                            .bodyLarge,

                    color =
                        MaterialTheme
                            .colorScheme
                            .secondary
                )

                Spacer(
                    Modifier.height(40.dp)
                )

                CircularProgressIndicator(

                    color =
                        MaterialTheme
                            .colorScheme
                            .secondary
                )

                Spacer(
                    Modifier.height(16.dp)
                )

                Text(

                    text =
                        "Loading...",

                    style =
                        MaterialTheme
                            .typography
                            .bodyMedium,

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }
        }
    }
}


/*
 * ============================================================
 * REQUIRED PERMISSIONS SCREEN
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
                Alignment.CenterHorizontally,

            verticalArrangement =
                Arrangement.Center
        ) {

            Text(

                text =
                    "Permissions",

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
                Modifier.height(8.dp)
            )

            Text(

                text =
                    "Give necessary permissions required to run the app properly",

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
                Modifier.height(32.dp)
            )

            Text(

                text =
                    "Permissions required",

                style =
                    MaterialTheme
                        .typography
                        .titleMedium
            )

            Spacer(
                Modifier.height(16.dp)
            )


            PermissionCard(

                title =
                    "Accessibility Service",

                description =
                    "Allows Dontscroll to detect when " +
                            "you open a protected app.",

                enabled =
                    accessibilityEnabled,

                onClick =
                    onAccessibilityClick
            )


            Spacer(
                Modifier.height(12.dp)
            )


            PermissionCard(

                title =
                    "Screen Time Access",

                description =
                    "Allows Dontscroll to measure " +
                            "your daily app usage.",

                enabled =
                    usageAccessEnabled,

                onClick =
                    onUsageAccessClick
            )


            Spacer(
                Modifier.height(24.dp)
            )


            Text(

                text =
                    "Both permissions are required " +
                            "for Dontscroll to work.",

                style =
                    MaterialTheme
                        .typography
                        .bodyMedium,

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

    title: String,

    description: String,

    enabled: Boolean,

    onClick: () -> Unit

) {

    Card(

        modifier =
            Modifier.fillMaxWidth(),

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
                Modifier.padding(16.dp)
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
                            title,

                        style =
                            MaterialTheme
                                .typography
                                .titleMedium
                    )

                    Spacer(
                        Modifier.height(4.dp)
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


                if (enabled) {

                    Text(

                        text =
                            "✓",

                        style =
                            MaterialTheme
                                .typography
                                .headlineSmall,

                        color =
                            MaterialTheme
                                .colorScheme
                                .primary
                    )
                }
            }


            if (!enabled) {

                Spacer(
                    Modifier.height(12.dp)
                )

                Button(

                    onClick =
                        onClick,

                    modifier =
                        Modifier.fillMaxWidth()
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


    /*
     * Loading state for installed apps.
     */
    var appsLoading by remember {

        mutableStateOf(true)
    }


    var search by remember {

        mutableStateOf("")
    }


    var selectedApp by remember {

        mutableStateOf<InstalledApp?>(null)
    }


    var refresh by remember {

        mutableLongStateOf(
            System.currentTimeMillis()
        )
    }


    /*
     * --------------------------------------------------------
     * LOAD INSTALLED APPS
     * --------------------------------------------------------
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


        /*
         * Small fake delay so the loading screen
         * is actually visible.
         */
        delay(500L)

        appsLoading = false
    }


    /*
     * --------------------------------------------------------
     * PERIODIC REFRESH
     * --------------------------------------------------------
     */

    LaunchedEffect(Unit) {

        while (true) {

            refresh =
                System.currentTimeMillis()

            delay(10_000L)
        }
    }


    /*
     * --------------------------------------------------------
     * DATABASE
     * --------------------------------------------------------
     */

    val blockedApps by database
        .blockedAppDao()
        .getAll()
        .collectAsStateWithLifecycle(

            initialValue =
                emptyList()
        )


    val blockedMap =
        blockedApps.associateBy {

            it.packageName
        }


    /*
     * --------------------------------------------------------
     * USAGE
     * --------------------------------------------------------
     */

    val usageList =
        remember(refresh) {

            ScreenTimeManager
                .getTodayUsage(
                    context
                )
        }


    val usageMap =
        usageList.associate {

            it.packageName to
                    it.totalTimeMillis
        }


    /*
     * --------------------------------------------------------
     * SEARCH
     * --------------------------------------------------------
     */

    val filteredApps =
        apps.filter { app ->

            search.isBlank() ||
                    app.displayName.contains(
                        search,
                        ignoreCase = true
                    )
        }


    /*
     * --------------------------------------------------------
     * APPS LOADING SCREEN
     * --------------------------------------------------------
     */

    if (appsLoading) {

        DontscrollAppsLoadingScreen()

        return
    }


    /*
     * --------------------------------------------------------
     * MAIN UI
     * --------------------------------------------------------
     */

    Scaffold(

        containerColor =
            MaterialTheme
                .colorScheme
                .background

    ) { paddingValues ->

        Column(

            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp)
        ) {

            Spacer(
                Modifier.height(20.dp)
            )


            Text(

                text =
                    "Hey Doomscroller!!",

                style =
                    MaterialTheme
                        .typography
                        .headlineMedium,

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
                    "Take control of your scrolling.",

                style =
                    MaterialTheme
                        .typography
                        .bodyMedium,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )


            Spacer(
                Modifier.height(16.dp)
            )


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

                label = {
                    Text(
                        "Search apps"
                    )
                },

                placeholder = {
                    Text(
                        "Instagram, YouTube, Chrome..."
                    )
                }
            )


            Spacer(
                Modifier.height(16.dp)
            )


            Row(

                modifier =
                    Modifier.fillMaxWidth(),

                horizontalArrangement =
                    Arrangement.SpaceBetween,

                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Text(

                    text =
                        "Protected: ${blockedApps.size}",

                    style =
                        MaterialTheme
                            .typography
                            .titleMedium
                )


                Text(

                    text =
                        "${filteredApps.size} apps",

                    style =
                        MaterialTheme
                            .typography
                            .bodyMedium,

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }


            Spacer(
                Modifier.height(8.dp)
            )


            LazyColumn(

                modifier =
                    Modifier.fillMaxSize(),

                verticalArrangement =
                    Arrangement.spacedBy(4.dp)
            ) {

                items(

                    items =
                        filteredApps,

                    key = {
                        it.packageName
                    }

                ) { app ->

                    val blocked =
                        blockedMap[
                            app.packageName
                        ]


                    val screenTime =
                        usageMap[
                            app.packageName
                        ] ?: 0L


                    AppRow(

                        app =
                            app,

                        blocked =
                            blocked != null,

                        automaticDelay =
                            blocked?.automaticDelay
                                ?: false,

                        screenTimeMillis =
                            screenTime,

                        onToggle = { enabled ->

                            if (enabled) {

                                selectedApp =
                                    app

                            } else {

                                blocked?.let {

                                    scope.launch {

                                        database
                                            .blockedAppDao()
                                            .delete(it)
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }


    /*
     * --------------------------------------------------------
     * DELAY DIALOG
     * --------------------------------------------------------
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

                    val blockedApp =
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
                                blockedApp
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
 * APPS LOADING SCREEN
 * ============================================================
 */

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

        Box(

            modifier =
                Modifier.fillMaxSize(),

            contentAlignment =
                Alignment.Center
        ) {

            Column(

                horizontalAlignment =
                    Alignment.CenterHorizontally
            ) {

                CircularProgressIndicator(

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
                        "Loading your apps",

                    style =
                        MaterialTheme
                            .typography
                            .headlineSmall
                )


                Spacer(
                    Modifier.height(8.dp)
                )


                Text(

                    text =
                        "Preparing Dontscroll...",

                    style =
                        MaterialTheme
                            .typography
                            .bodyMedium,

                    color =
                        MaterialTheme
                            .colorScheme
                            .secondary
                )
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
fun AppRow(

    app: InstalledApp,

    blocked: Boolean,

    automaticDelay: Boolean,

    screenTimeMillis: Long,

    onToggle: (Boolean) -> Unit

) {

    Surface(

        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),

        shape =
            MaterialTheme.shapes.medium,

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
                    Modifier.size(48.dp)
            )


            Spacer(
                Modifier.size(12.dp)
            )


            Column(

                modifier =
                    Modifier.weight(1f)
            ) {

                Text(

                    text =
                        app.displayName,

                    style =
                        MaterialTheme
                            .typography
                            .titleMedium
                )


                Spacer(
                    Modifier.height(2.dp)
                )


                Text(

                    text =
                        "Today: ${
                            ScreenTimeManager
                                .formatDuration(
                                    screenTimeMillis
                                )
                        }",

                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )


                if (blocked) {

                    Spacer(
                        Modifier.height(2.dp)
                    )


                    Text(

                        text =
                            if (automaticDelay) {

                                "Protected • Automatic"

                            } else {

                                "Protected • Manual"
                            },

                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,

                        color =
                            MaterialTheme
                                .colorScheme
                                .primary
                    )
                }
            }


            Checkbox(

                checked =
                    blocked,

                onCheckedChange =
                    onToggle
            )
        }
    }
}


/*
 * ============================================================
 * AUTOMATIC DELAY
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

    onSave: (
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


    /*
     * DEFAULT DELAY
     *
     * Change "3" to "30" if you want
     * the default to be 30 seconds.
     */
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

        containerColor =
            MaterialTheme
                .colorScheme
                .surfaceContainerHigh,


        title = {

            Text(

                text =
                    "Unlock delay",

                style =
                    MaterialTheme
                        .typography
                        .headlineSmall
            )
        },


        text = {

            Column {

                Text(

                    text =
                        "Choose how long $appName " +
                                "should make you wait.",

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )


                Spacer(
                    Modifier.height(16.dp)
                )


                Row(

                    modifier =
                        Modifier.fillMaxWidth(),

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

                            style =
                                MaterialTheme
                                    .typography
                                    .titleMedium
                        )


                        Text(

                            text =
                                "Based on today's screen time",

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


                if (automatic) {

                    Spacer(
                        Modifier.height(12.dp)
                    )


                    Surface(

                        modifier =
                            Modifier.fillMaxWidth(),

                        shape =
                            MaterialTheme
                                .shapes.medium,

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

                                text =
                                    "Today's screen time",

                                style =
                                    MaterialTheme
                                        .typography
                                        .labelMedium
                            )


                            Text(

                                text =
                                    ScreenTimeManager
                                        .formatDuration(
                                            screenTimeMillis
                                        ),

                                style =
                                    MaterialTheme
                                        .typography
                                        .titleMedium
                            )


                            Spacer(
                                Modifier.height(8.dp)
                            )


                            Text(

                                text =
                                    "Unlock delay: ${
                                        formatDelay(
                                            automaticDelay
                                        )
                                    }",

                                style =
                                    MaterialTheme
                                        .typography
                                        .bodyMedium
                            )
                        }
                    }

                } else {

                    Spacer(
                        Modifier.height(8.dp)
                    )


                    Row(

                        modifier =
                            Modifier.fillMaxWidth(),

                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp)
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
                                Modifier.weight(1f),

                            label = {
                                Text(
                                    "Minutes"
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
                                Modifier.weight(1f),

                            label = {
                                Text(
                                    "Seconds"
                                )
                            },

                            singleLine =
                                true
                        )
                    }
                }
            }
        },


        /*
         * ====================================================
         * SAVE
         * ====================================================
         */

        confirmButton = {

            TextButton(

                onClick = {

                    val delaySeconds =

                        if (automatic) {

                            automaticDelay

                        } else {

                            hours
                                .toLongOrNull()
                                ?.times(3600L)
                                ?.plus(

                                    (
                                            minutes
                                                .toLongOrNull()
                                                ?: 0L
                                            ) * 60L

                                )
                                ?.plus(

                                    seconds
                                        .toLongOrNull()
                                        ?: 0L

                                )
                                ?: 0L
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


        /*
         * ====================================================
         * CANCEL
         * ====================================================
         */

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