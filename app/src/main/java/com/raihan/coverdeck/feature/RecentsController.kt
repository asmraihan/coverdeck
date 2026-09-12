package com.raihan.coverdeck.feature

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import com.raihan.coverdeck.model.TaskItem
import com.raihan.coverdeck.privileged.Privileged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class RecentEntry(
    val task: TaskItem,
    val label: String,
    val icon: Drawable?,
    val thumbnail: Bitmap?,
)

/**
 * The One UI style recents list, rebuilt from ActivityTaskManager.
 *
 * The system recents UI does not exist on the cover screen, so this reads the real
 * task list through shell (which holds REAL_GET_TASKS), pulls each task's snapshot,
 * and lets the user resume, relocate or kill it.
 */
class RecentsController(private val context: Context) {

    private val _entries = MutableStateFlow<List<RecentEntry>>(emptyList())
    val entries: StateFlow<List<RecentEntry>> = _entries.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val iconCache = mutableMapOf<String, Drawable?>()

    suspend fun refresh(maxItems: Int = 16, thumbPx: Int = 320) {
        _loading.value = true
        try {
            val tasks = withContext(Dispatchers.IO) {
                Privileged.with { it.getRecentTasks(maxItems * 2) } ?: emptyList()
            }
            val homePackages = homePackages()
            val visible = tasks.asSequence()
                .filter { it.packageName.isNotBlank() }
                .filter { it.packageName !in homePackages }
                .filter { it.packageName !in ALWAYS_HIDDEN }
                .distinctBy { it.taskId }
                .take(maxItems)
                .toList()

            _entries.value = withContext(Dispatchers.IO) {
                visible.map { task ->
                    RecentEntry(
                        task = task,
                        label = labelFor(task),
                        icon = iconFor(task.packageName),
                        thumbnail = Privileged.with { it.getTaskSnapshot(task.taskId, thumbPx) },
                    )
                }
            }
        } finally {
            _loading.value = false
        }
    }

    private fun labelFor(task: TaskItem): String {
        task.label?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(task.packageName, 0)).toString()
        }.getOrDefault(task.packageName.substringAfterLast('.'))
    }

    private fun iconFor(packageName: String): Drawable? = iconCache.getOrPut(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
    }

    /** Resuming onto [displayId] is also how "send this app to the cover screen" works. */
    fun resume(entry: RecentEntry, displayId: Int) {
        Privileged.with { it.launchTask(entry.task.taskId, displayId) }
    }

    fun moveToDisplay(entry: RecentEntry, displayId: Int) {
        Privileged.with { it.moveTaskToDisplay(entry.task.taskId, displayId) }
    }

    fun close(entry: RecentEntry) {
        Privileged.with { it.removeTask(entry.task.taskId) }
        _entries.value = _entries.value.filterNot { it.task.taskId == entry.task.taskId }
    }

    fun closeAll() {
        val current = _entries.value
        Privileged.with { service ->
            current.forEach { runCatching { service.removeTask(it.task.taskId) } }
        }
        _entries.value = emptyList()
    }

    private fun homePackages(): Set<String> = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }.getOrDefault(emptySet())

    private companion object {
        val ALWAYS_HIDDEN = setOf(
            "com.android.systemui",
            "com.samsung.android.app.cocktailbarservice",
        )
    }
}
