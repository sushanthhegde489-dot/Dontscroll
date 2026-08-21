package com.sushanth.dontscroll.data

import android.content.Context
import android.content.Intent

fun getInstalledApps(context: Context): List<InstalledApp> {

    val pm = context.packageManager

    val intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }

    return pm.queryIntentActivities(
        intent,
        0
    )
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
