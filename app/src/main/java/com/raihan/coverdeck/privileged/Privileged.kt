package com.raihan.coverdeck.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.raihan.coverdeck.BuildConfig
import com.raihan.coverdeck.IPrivilegedService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * App-side owner of the Shizuku connection.
 *
 * The whole app funnels privileged work through [service]. When it is null the UI
 * stays usable and every tile explains why it is inert, rather than throwing.
 */
object Privileged {

    private const val TAG = "CoverDeck/Shizuku"
    private const val PERMISSION_REQUEST_CODE = 4201

    sealed interface Status {
        /** Shizuku itself is not installed. */
        data object NotInstalled : Status

        /** Installed, but the service is not running (user has not started it yet). */
        data object NotRunning : Status

        /** Running, but CoverDeck has not been authorised. */
        data object PermissionRequired : Status

        data object Connecting : Status

        data class Ready(val remoteUid: Int, val detail: String) : Status

        data class Failed(val reason: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Connecting)
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile
    var service: IPrivilegedService? = null
        private set

    private var appContext: Context? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, PrivilegedService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("privileged")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                _status.value = Status.Failed("User service returned a dead binder")
                return
            }
            val remote = IPrivilegedService.Stub.asInterface(binder)
            service = remote
            _status.value = runCatching {
                Status.Ready(remote.remoteUid, remote.ping())
            }.getOrElse { Status.Failed(it.message ?: "ping failed") }
            Log.i(TAG, "user service connected: ${_status.value}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _status.value = Status.Failed("User service disconnected")
        }
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { code, grantResult ->
            if (code == PERMISSION_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    bind()
                } else {
                    _status.value = Status.PermissionRequired
                }
            }
        }

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }

    private val binderDead = Shizuku.OnBinderDeadListener {
        service = null
        _status.value = Status.NotRunning
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        refresh()
    }

    fun refresh() {
        val ctx = appContext ?: return
        if (!isShizukuInstalled(ctx)) {
            _status.value = Status.NotInstalled
            return
        }
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            _status.value = Status.NotRunning
            return
        }
        if (runCatching { Shizuku.checkSelfPermission() }.getOrNull() !=
            PackageManager.PERMISSION_GRANTED
        ) {
            _status.value = Status.PermissionRequired
            return
        }
        if (service == null) bind()
    }

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
            .onFailure { _status.value = Status.Failed(it.message ?: "permission request failed") }
    }

    private fun bind() {
        _status.value = Status.Connecting
        runCatching { Shizuku.bindUserService(userServiceArgs, connection) }
            .onFailure {
                Log.e(TAG, "bindUserService failed", it)
                _status.value = Status.Failed(it.message ?: "bind failed")
            }
    }

    fun unbind() {
        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        service = null
    }

    private fun isShizukuInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    }.getOrDefault(false)

    /**
     * Runs [block] against the privileged service, swallowing the remote exception that
     * a One UI update can produce and surfacing it as null instead of a crash.
     */
    inline fun <T> with(block: (IPrivilegedService) -> T): T? {
        val remote = service ?: return null
        return runCatching { block(remote) }
            .onFailure { Log.e("CoverDeck/Shizuku", "privileged call failed", it) }
            .getOrNull()
    }
}
