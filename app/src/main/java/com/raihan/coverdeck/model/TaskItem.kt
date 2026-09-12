package com.raihan.coverdeck.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A single entry in the recents list, flattened in the shell-UID process so the
 * app side never has to touch ActivityManager's hidden task classes.
 */
@Parcelize
data class TaskItem(
    val taskId: Int,
    val packageName: String,
    val activityName: String?,
    val label: String?,
    val userId: Int,
    val displayId: Int,
    val lastActiveTime: Long,
    val isRunning: Boolean,
    val isExcluded: Boolean,
) : Parcelable
