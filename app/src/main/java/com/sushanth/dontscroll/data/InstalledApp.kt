package com.sushanth.dontscroll.data

import android.graphics.drawable.Drawable

data class InstalledApp(
    val packageName: String,
    val displayName: String,
    val icon: Drawable
)