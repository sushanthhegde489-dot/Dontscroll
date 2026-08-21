package com.sushanth.dontscroll.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_apps")
data class BlockedApp(
    @PrimaryKey
    val packageName: String,
    val displayName: String,
    val unlockDelaySeconds: Long,
    val automaticDelay: Boolean = false
)