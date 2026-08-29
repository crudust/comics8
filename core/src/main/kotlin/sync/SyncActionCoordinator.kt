package com.comics8.core.sync

import com.comics8.core.model.PairConfirmResult
import com.comics8.core.model.PairRequestResult
import com.comics8.core.model.SyncResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** ViewModel-facing orchestration shared by Android and Desktop. */
class SyncActionCoordinator(
    private val scope: CoroutineScope,
    private val manager: () -> BaseSyncManager?,
    private val refreshAfterPull: suspend () -> Unit,
) {
    fun syncNow() {
        scope.launch { if (manager()?.syncFull()?.success == true) refreshAfterPull() }
    }

    fun restore(key: String, onResult: (SyncResult) -> Unit) {
        scope.launch {
            val syncManager = manager()
            if (syncManager == null) {
                onResult(SyncResult(false, MISSING_MANAGER))
                return@launch
            }
            val result = syncManager.restoreAccount(key)
            if (result.success) refreshAfterPull()
            onResult(result)
        }
    }

    fun requestPairingCode(onResult: (PairRequestResult) -> Unit) {
        scope.launch {
            val syncManager = manager()
            if (syncManager == null) onResult(PairRequestResult(false, message = MISSING_MANAGER))
            else onResult(syncManager.requestPairingCode())
        }
    }

    fun confirmPairingCode(code: String, onResult: (PairConfirmResult) -> Unit) {
        scope.launch {
            val syncManager = manager()
            if (syncManager == null) {
                onResult(PairConfirmResult(false, message = MISSING_MANAGER))
                return@launch
            }
            val result = syncManager.confirmPairingCode(code)
            if (result.success) refreshAfterPull()
            onResult(result)
        }
    }

    companion object {
        private const val MISSING_MANAGER = "동기화 매니저를 찾을 수 없습니다."
    }
}
