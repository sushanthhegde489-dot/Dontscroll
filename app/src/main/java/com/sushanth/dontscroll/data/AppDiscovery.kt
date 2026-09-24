package com.sushanth.dontscroll.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

@Volatile
private var cachedInstalledApps: List<InstalledApp>? = null

fun invalidateInstalledAppsCache() {
    cachedInstalledApps = null
}

fun getInstalledApps(context: Context, forceRefresh: Boolean = false): List<InstalledApp> {
    if (!forceRefresh) {
        cachedInstalledApps?.let { return it }
    }

    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }

    val activities = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
    } catch (_: Exception) {
        emptyList()
    }

    val result = activities
        .asSequence()
        .mapNotNull { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
            val packageName = activityInfo.packageName ?: return@mapNotNull null

            // Don't show Dontscroll itself.
            if (packageName == context.packageName) {
                return@mapNotNull null
            }

            val label = try {
                resolveInfo.loadLabel(pm)?.toString()?.trim().orEmpty()
            } catch (_: Exception) {
                ""
            }

            if (label.isBlank()) {
                return@mapNotNull null
            }

            val icon = try {
                resolveInfo.loadIcon(pm) ?: pm.defaultActivityIcon
            } catch (_: Exception) {
                pm.defaultActivityIcon
            }

            InstalledApp(
                packageName = packageName,
                displayName = label,
                icon = icon
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.displayName.lowercase() }
        .toList()

    cachedInstalledApps = result
    return result
}
