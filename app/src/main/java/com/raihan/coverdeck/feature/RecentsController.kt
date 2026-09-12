package com.raihan.coverdeck.feature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import com.raihan.coverdeck.model.TaskItem
import com.raihan.coverdeck.privileged.Privileged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

data class RecentEntry(
    val task: TaskItem,
    val label: String,
    val icon: Drawable?,
    val thumbnail: Bitmap?,
)

/**
 * One UI style recents, rebuilt from ActivityTaskManager for the cover screen.
 *
 * One UI 8.5 has no recents on the cover at all: the stock recents screen is One UI
 * Home's private RecentsActivity on the inner display, and the cover's recents button
 * exists in SystemUI but is kept invisible. So this reads the real task list through
 * shell (REAL_GET_TASKS), pulls each snapshot, and supports the stock actions: resume,
 * close, close all, and Keep open.
 */
class RecentsController(private val context: Context) {

    private val _entries = MutableStateFlow<List<RecentEntry>>(emptyList())

    /** Newest first, the order ActivityTaskManager reports. */
    val entries: StateFlow<List<RecentEntry>> = _entries.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    val keptOpen: StateFlow<Set<String>> = KeepOpen.packages

    private val iconCache = mutableMapOf<String, Drawable?>()

    init {
        KeepOpen.init(context)
    }

    /**
     * Two passes, so the panel is usable immediately: labels and icons go out first,
     * then snapshots fill in one at a time. Each snapshot is its own binder round trip,
     * and waiting for all of them made opening recents feel slow.
     */
    suspend fun refresh(maxItems: Int = 16, thumbPx: Int = 480) {
        _loading.value = true
        try {
            val tasks = withContext(Dispatchers.IO) {
                Privileged.with { it.getRecentTasks(maxItems * 2) } ?: emptyList()
            }
            // Home and recents tasks are already dropped by type on the privileged side.
            // CoverDeck's own recents screen is an ordinary task, and the window manager
            // still lists an excluded task while it is on top, so drop it here.
            val visible = tasks.asSequence()
                .filter { it.packageName.isNotBlank() && it.packageName !in ALWAYS_HIDDEN }
                .filter { it.activityName != RECENTS_ACTIVITY }
                .distinctBy { it.taskId }
                .take(maxItems)
                .toList()

            val previousThumbs = _entries.value.associate { it.task.taskId to it.thumbnail }
            _entries.value = withContext(Dispatchers.IO) {
                visible.map { task ->
                    RecentEntry(
                        task = task,
                        label = labelFor(task),
                        icon = iconFor(task.packageName),
                        // Reuse the last snapshot until the fresh one arrives, so cards do
                        // not flash empty every time recents opens.
                        thumbnail = previousThumbs[task.taskId],
                    )
                }
            }

            for (task in visible) {
                val thumb = withContext(Dispatchers.IO) {
                    Privileged.with { it.getTaskSnapshot(task.taskId, thumbPx) }
                } ?: continue
                _entries.update { list ->
                    list.map { if (it.task.taskId == task.taskId) it.copy(thumbnail = thumb) else it }
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

    /** Resuming onto [displayId] is also how "open on the other screen" works. */
    fun resume(entry: RecentEntry, displayId: Int) {
        Privileged.with { it.launchTask(entry.task.taskId, displayId) }
    }

    fun close(entry: RecentEntry) {
        Privileged.with { it.removeTask(entry.task.taskId) }
        _entries.update { list -> list.filterNot { it.task.taskId == entry.task.taskId } }
    }

    /** Like One UI, Close all leaves apps marked Keep open alone. */
    fun closeAll() {
        val kept = KeepOpen.packages.value
        val closing = _entries.value.filterNot { it.task.packageName in kept }
        Privileged.with { service ->
            closing.forEach { runCatching { service.removeTask(it.task.taskId) } }
        }
        _entries.update { list -> list.filter { it.task.packageName in kept } }
    }

    fun toggleKeepOpen(entry: RecentEntry) = KeepOpen.toggle(entry.task.packageName)

    /** App info, opened on the same screen recents is showing on. */
    fun openAppInfo(entry: RecentEntry, displayId: Int) {
        Privileged.with {
            it.exec(
                "am start --display $displayId -a android.settings.APPLICATION_DETAILS_SETTINGS " +
                    "-d package:${entry.task.packageName}",
            )
        }
    }

    private companion object {
        const val RECENTS_ACTIVITY = "com.raihan.coverdeck.recents.CoverRecentsActivity"

        val ALWAYS_HIDDEN = setOf(
            "com.android.systemui",
            "com.samsung.android.app.cocktailbarservice",
        )
    }
}

/**
 * Keep open, by package. Task ids change every time an app is relaunched, so pinning a
 * task id would silently forget the choice; One UI's own lock survives that too.
 */
object KeepOpen {

    private const val PREFS = "coverdeck_recents"
    private const val KEY = "keep_open_packages"

    private val _packages = MutableStateFlow<Set<String>>(emptySet())
    val packages: StateFlow<Set<String>> = _packages.asStateFlow()

    private var loaded = false
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (loaded) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _packages.value = prefs?.getStringSet(KEY, emptySet()).orEmpty().toSet()
        loaded = true
    }

    fun toggle(packageName: String) {
        val next = _packages.value.toMutableSet().apply {
            if (!add(packageName)) remove(packageName)
        }
        _packages.value = next
        prefs?.edit()?.putStringSet(KEY, next)?.apply()
    }
}
