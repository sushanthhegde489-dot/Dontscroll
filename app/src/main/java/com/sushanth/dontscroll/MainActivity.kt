package com.sushanth.dontscroll

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            DontscrollTheme {
                DontscrollApp()
            }
        }
    }
}

/**
 * Checks whether Dontscroll's accessibility service
 * is currently enabled by the user.
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

        service.packageName == context.packageName &&
                service.name ==
                "com.sushanth.dontscroll.service.DoomGuardAccessibilityService"
    }
}

/**
 * Root composable.
 *
 * The user cannot access the main app until both
 * required permissions are enabled.
 */
@Composable
fun DontscrollApp() {

    val context = LocalContext.current

    var permissionRefresh by remember {
        mutableLongStateOf(
            System.currentTimeMillis()
        )
    }

    val settingsLauncher =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts.StartActivityForResult()
        ) {
            permissionRefresh =
                System.currentTimeMillis()
        }

    val accessibilityEnabled =
        remember(permissionRefresh) {
            isAccessibilityServiceEnabled(context)
        }

    val usageAccessEnabled =
        remember(permissionRefresh) {
            ScreenTimeManager.hasUsageAccess(context)
        }

    /**
     * Periodically check permission state.
     *
     * This handles Android versions where returning
     * from Settings does not immediately trigger a
     * recomposition.
     */
    LaunchedEffect(Unit) {

        while (true) {

            delay(1000L)

            permissionRefresh =
                System.currentTimeMillis()
        }
    }

    /**
     * Setup screen.
     */
    if (!accessibilityEnabled || !usageAccessEnabled) {

        RequiredPermissionsScreen(
            accessibilityEnabled = accessibilityEnabled,
            usageAccessEnabled = usageAccessEnabled,

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

    /**
     * Main application.
     */
    DontscrollMainScreen(
        context = context
    )
}

/**
 * Permission/setup screen.
 */
@Composable
fun RequiredPermissionsScreen(
    accessibilityEnabled: Boolean,
    usageAccessEnabled: Boolean,
    onAccessibilityClick: () -> Unit,
    onUsageAccessClick: () -> Unit
) {

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),

            horizontalAlignment =
                Alignment.CenterHorizontally,

            verticalArrangement =
                Arrangement.Center
        ) {

            Text(
                text = "Dontscroll",

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
                    "Take control of your scrolling.",

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
                    "A couple of permissions are needed",

                style =
                    MaterialTheme
                        .typography
                        .titleMedium
            )

            Spacer(
                Modifier.height(16.dp)
            )

            PermissionCard(
                title = "Accessibility Service",

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
                title = "Screen Time Access",

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

            if (
                accessibilityEnabled &&
                usageAccessEnabled
            ) {

                Text(
                    text = "You're all set.",

                    style =
                        MaterialTheme
                            .typography
                            .titleMedium,

                    color =
                        MaterialTheme
                            .colorScheme
                            .primary
                )

            } else {

                Text(
                    text =
                        "Both permissions are required.",

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

/**
 * Individual permission card.
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

        shape =
            MaterialTheme.shapes.medium,

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
                        text = title,

                        style =
                            MaterialTheme
                                .typography
                                .titleMedium,

                        color =
                            if (enabled) {
                                MaterialTheme
                                    .colorScheme
                                    .onPrimaryContainer
                            } else {
                                MaterialTheme
                                    .colorScheme
                                    .onSurface
                            }
                    )

                    Spacer(
                        Modifier.height(4.dp)
                    )

                    Text(
                        text = description,

                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,

                        color =
                            if (enabled) {
                                MaterialTheme
                                    .colorScheme
                                    .onPrimaryContainer
                            } else {
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant
                            }
                    )
                }

                if (enabled) {

                    Text(
                        text = "✓",

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
                    onClick = onClick,

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

/**
 * Main Dontscroll screen.
 */
@Composable
fun DontscrollMainScreen(
    context: Context
) {

    val database =
        remember {
            AppDatabase.getInstance(context)
        }

    val scope =
        rememberCoroutineScope()

    var apps by remember {
        mutableStateOf<List<InstalledApp>>(
            emptyList()
        )
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

    /**
     * Load installed apps.
     */
    LaunchedEffect(Unit) {

        apps =
            withContext(Dispatchers.IO) {
                getInstalledApps(context)
            }
    }

    /**
     * Refresh usage every 10 seconds.
     */
    LaunchedEffect(Unit) {

        while (true) {

            refresh =
                System.currentTimeMillis()

            delay(10_000L)
        }
    }

    /**
     * Observe protected applications.
     */
    val blockedApps by database
        .blockedAppDao()
        .getAll()
        .collectAsStateWithLifecycle(
            initialValue = emptyList()
        )

    val blockedMap =
        blockedApps.associateBy {
            it.packageName
        }

    /**
     * Today's usage.
     */
    val usageList =
        remember(refresh) {

            ScreenTimeManager
                .getTodayUsage(context)
        }

    val usageMap =
        usageList.associate {
            it.packageName to
                    it.totalTimeMillis
        }

    /**
     * Search/filter.
     */
    val filteredApps =
        apps.filter { app ->

            search.isBlank() ||
                    app.displayName.contains(
                        search,
                        ignoreCase = true
                    )
        }

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

            /**
             * Header.
             */
            Text(
                text = "Dontscroll",

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

            /**
             * Search.
             */
            OutlinedTextField(

                value = search,

                onValueChange = {
                    search = it
                },

                modifier =
                    Modifier.fillMaxWidth(),

                singleLine = true,

                shape =
                    MaterialTheme.shapes.medium,

                label = {
                    Text("Search apps")
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

            /**
             * App count/header.
             */
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

            /**
             * App list.
             */
            LazyColumn(
                modifier =
                    Modifier.fillMaxSize(),

                verticalArrangement =
                    Arrangement.spacedBy(4.dp)
            ) {

                items(

                    items = filteredApps,

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
                        app = app,

                        blocked =
                            blocked != null,

                        screenTimeMillis =
                            screenTime,

                        onToggle = { enabled ->

                            if (enabled) {

                                selectedApp =
                                    app

                            } else {

                                if (blocked != null) {

                                    scope.launch {

                                        database
                                            .blockedAppDao()
                                            .delete(
                                                blocked
                                            )
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    /**
     * Unlock-delay dialog.
     */
    selectedApp?.let { app ->

        DelayDialog(
            appName =
                app.displayName,

            onDismiss = {
                selectedApp = null
            },

            onSave = {
                    hours,
                    minutes,
                    seconds ->

                val totalSeconds =
                    hours * 3600L +
                            minutes * 60L +
                            seconds

                if (totalSeconds > 0L) {

                    val blockedApp =
                        BlockedApp(
                            packageName =
                                app.packageName,

                            displayName =
                                app.displayName,

                            unlockDelaySeconds =
                                totalSeconds
                        )

                    scope.launch {

                        database
                            .blockedAppDao()
                            .insert(
                                blockedApp
                            )
                    }
                }

                selectedApp = null
            }
        )
    }
}

/**
 * Application list row.
 */
@Composable
fun AppRow(
    app: InstalledApp,
    blocked: Boolean,
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
                    remember(app.packageName) {

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
                        text = "Protected",

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
                checked = blocked,

                onCheckedChange =
                    onToggle
            )
        }
    }
}

/**
 * Timer configuration dialog.
 */
@Composable
fun DelayDialog(
    appName: String,
    onDismiss: () -> Unit,
    onSave: (Long, Long, Long) -> Unit
) {

    var hours by remember {
        mutableStateOf("0")
    }

    var minutes by remember {
        mutableStateOf("0")
    }

    var seconds by remember {
        mutableStateOf("30")
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
                text = "Unlock delay",

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
                        "How long should $appName " +
                                "make you wait?",

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

                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {

                    OutlinedTextField(
                        value = hours,

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

                        singleLine = true,

                        shape =
                            MaterialTheme.shapes.medium
                    )

                    OutlinedTextField(
                        value = minutes,

                        onValueChange = {
                            minutes =
                                it.filter(
                                    Char::isDigit
                                )
                        },

                        modifier =
                            Modifier.weight(1f),

                        label = {
                            Text("Minutes")
                        },

                        singleLine = true,

                        shape =
                            MaterialTheme.shapes.medium
                    )

                    OutlinedTextField(
                        value = seconds,

                        onValueChange = {
                            seconds =
                                it.filter(
                                    Char::isDigit
                                )
                        },

                        modifier =
                            Modifier.weight(1f),

                        label = {
                            Text("Seconds")
                        },

                        singleLine = true,

                        shape =
                            MaterialTheme.shapes.medium
                    )
                }
            }
        },

        confirmButton = {

            TextButton(
                onClick = {

                    onSave(
                        hours.toLongOrNull()
                            ?: 0L,

                        minutes.toLongOrNull()
                            ?: 0L,

                        seconds.toLongOrNull()
                            ?: 0L
                    )
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