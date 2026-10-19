package com.example.kingsentry.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.kingsentry.data.KingSentryDao
import com.example.kingsentry.workers.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

sealed interface SyncUiState {
    data class Offline(val pending: Int) : SyncUiState
    object Checking : SyncUiState
    object Connected : SyncUiState
    data class Syncing(val done: Int, val total: Int) : SyncUiState
    object Synced : SyncUiState
    data class Failed(val message: String, val pending: Int) : SyncUiState
}

enum class NetworkState {
    OFFLINE,
    CHECKING,
    VALIDATED
}

/**
 * Singleton managing connection status, Room unsynced scan count,
 * and WorkManager sync execution progress to derive priority-driven [SyncUiState].
 */
object SyncStatusManager {
    private const val TAG = "SyncStatus"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _networkState = MutableStateFlow(NetworkState.OFFLINE)
    private val _pendingCount = MutableStateFlow(0)
    private val _workerState = MutableStateFlow<WorkerState>(WorkerState.Idle)
    private val _userFailedMessage = MutableStateFlow<String?>(null)

    private val _uiState = MutableStateFlow<SyncUiState>(SyncUiState.Offline(0))
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    private sealed interface WorkerState {
        object Idle : WorkerState
        data class Running(val done: Int, val total: Int) : WorkerState
        object Succeeded : WorkerState
        data class Failed(val message: String) : WorkerState
    }

    private var isInitialized = false

    fun init(context: Context, dao: KingSentryDao) {
        if (isInitialized) return
        isInitialized = true

        val appContext = context.applicationContext

        // 1. Connectivity Callback
        setupNetworkCallback(appContext)

        // 2. Room Unsynced Count Observer
        scope.launch(Dispatchers.IO) {
            dao.observeUnsyncedCount().collect { count ->
                _pendingCount.value = count
            }
        }

        // 3. WorkManager Observer for sync_scans
        scope.launch(Dispatchers.Main) {
            WorkManager.getInstance(appContext)
                .getWorkInfosForUniqueWorkFlow(SyncWorker.UNIQUE_WORK_NAME)
                .collect { workInfos ->
                    val info = workInfos.firstOrNull() ?: return@collect
                    val done = info.progress.getInt("done", 0)
                    val total = info.progress.getInt("total", 0)

                    when (info.state) {
                        WorkInfo.State.RUNNING -> {
                            _userFailedMessage.value = null
                            _workerState.value = WorkerState.Running(done, total)
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            _workerState.value = WorkerState.Succeeded
                        }
                        WorkInfo.State.FAILED -> {
                            val msg = "Sync failed"
                            _userFailedMessage.value = msg
                            _workerState.value = WorkerState.Failed(msg)
                        }
                        else -> {
                            // ENQUEUED / BLOCKED / CANCELLED
                        }
                    }
                }
        }

        // 4. Combine inputs to derive SyncUiState with strict priority
        scope.launch {
            combine(
                _networkState,
                _pendingCount,
                _workerState,
                _userFailedMessage
            ) { net, pending, worker, failedMsg ->
                computeState(net, pending, worker, failedMsg)
            }.collect { newState ->
                if (_uiState.value != newState) {
                    Log.d(TAG, "State transition: ${_uiState.value} -> $newState")
                    _uiState.value = newState
                }
            }
        }
    }

    private fun computeState(
        net: NetworkState,
        pending: Int,
        worker: WorkerState,
        failedMsg: String?
    ): SyncUiState {
        // Priority: Failed > Syncing > Offline > Checking > Connected > Synced
        if (failedMsg != null) {
            return SyncUiState.Failed(failedMsg, pending)
        }

        if (worker is WorkerState.Running) {
            return SyncUiState.Syncing(worker.done, worker.total)
        }

        return when (net) {
            NetworkState.OFFLINE -> SyncUiState.Offline(pending)
            NetworkState.CHECKING -> SyncUiState.Checking
            NetworkState.VALIDATED -> {
                if (pending == 0) {
                    SyncUiState.Synced
                } else {
                    SyncUiState.Connected
                }
            }
        }
    }

    fun reportWorkerStart(total: Int) {
        _userFailedMessage.value = null
        _workerState.value = WorkerState.Running(0, total)
    }

    fun reportWorkerProgress(done: Int, total: Int) {
        _workerState.value = WorkerState.Running(done, total)
    }

    fun reportWorkerSuccess() {
        _userFailedMessage.value = null
        _workerState.value = WorkerState.Succeeded
    }

    fun reportWorkerFailure(message: String) {
        _userFailedMessage.value = message
        _workerState.value = WorkerState.Failed(message)
    }

    fun reportWorkerRetry() {
        _workerState.value = WorkerState.Idle
    }

    fun clearFailure() {
        _userFailedMessage.value = null
        _workerState.value = WorkerState.Idle
    }

    private fun setupNetworkCallback(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

                val state = when {
                    hasInternet && isValidated -> NetworkState.VALIDATED
                    hasInternet -> NetworkState.CHECKING
                    else -> NetworkState.OFFLINE
                }
                _networkState.value = state
            }

            override fun onLost(network: Network) {
                _networkState.value = NetworkState.OFFLINE
            }
        }

        try {
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}")
        }

        // Initial check
        try {
            val activeNet = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(activeNet)
            if (caps != null) {
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                _networkState.value = when {
                    hasInternet && isValidated -> NetworkState.VALIDATED
                    hasInternet -> NetworkState.CHECKING
                    else -> NetworkState.OFFLINE
                }
            } else {
                _networkState.value = NetworkState.OFFLINE
            }
        } catch (e: Exception) {
            _networkState.value = NetworkState.OFFLINE
        }
    }
}
