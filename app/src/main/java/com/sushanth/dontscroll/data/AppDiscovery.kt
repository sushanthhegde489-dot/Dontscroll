package com.sushanth.dontscroll.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

fun getInstalledApps(context: Context): List<InstalledApp> {

    val pm = context.packageManager

    val intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }

    val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(0)
        )
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0)
    }

    return activities
        .asSequence()
        .mapNotNull { resolveInfo ->

        val packageName =
        resolveInfo.activityInfo.packageName

        // Don't show Dontscroll itself.
        if (packageName == context.packageName) {
            return@mapNotNull null
        }

        val label =
        resolveInfo.loadLabel(pm)
        ?.toString()
        ?.trim()
        .orEmpty()

        if (label.isBlank()) {
            return@mapNotNull null
        }

        InstalledApp(
            packageName = packageName,
            displayName = label,
            icon = resolveInfo.loadIcon(pm)
        )
    }
    .distinctBy {
        it.packageName
    }
    .sortedBy {
        it.displayName.lowercase()
    }
    .toList()
}
