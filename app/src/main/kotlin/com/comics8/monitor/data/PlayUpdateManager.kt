package com.comics8.monitor.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PlayUpdateResult(
    val hasUpdate: Boolean = false,
    val availableVersionCode: Int = 0,
    val appUpdateInfo: AppUpdateInfo? = null,
    val isDownloaded: Boolean = false,
    val error: String? = null,
)

object PlayUpdateManager {
    private var cachedAppUpdateManager: AppUpdateManager? = null
    private var cachedAppUpdateInfo: AppUpdateInfo? = null
    private var isListenerRegistered = false

    private val installStateListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            // Flexible 업데이트 백그라운드 다운로드 완료 시 즉시 설치 및 재시작 트리거
            cachedAppUpdateManager?.completeUpdate()
        }
    }

    private fun getAppUpdateManager(context: Context): AppUpdateManager {
        val mgr = cachedAppUpdateManager ?: AppUpdateManagerFactory.create(context.applicationContext).also {
            cachedAppUpdateManager = it
        }
        if (!isListenerRegistered) {
            mgr.registerListener(installStateListener)
            isListenerRegistered = true
        }
        return mgr
    }

    fun unregisterListener() {
        cachedAppUpdateManager?.let { mgr ->
            if (isListenerRegistered) {
                mgr.unregisterListener(installStateListener)
                isListenerRegistered = false
            }
        }
    }

    suspend fun checkPlayUpdate(context: Context): PlayUpdateResult = withContext(Dispatchers.IO) {
        try {
            val manager = getAppUpdateManager(context)
            val info = suspendCancellableCoroutine<AppUpdateInfo> { cont ->
                manager.appUpdateInfo
                    .addOnSuccessListener { appUpdateInfo ->
                        if (cont.isActive) cont.resume(appUpdateInfo)
                    }
                    .addOnFailureListener { exc ->
                        if (cont.isActive) cont.resumeWithException(exc)
                    }
            }
            cachedAppUpdateInfo = info

            val isDownloaded = info.installStatus() == InstallStatus.DOWNLOADED
            val availability = info.updateAvailability()
            val hasUpdate = isDownloaded ||
                availability == UpdateAvailability.UPDATE_AVAILABLE ||
                availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS

            PlayUpdateResult(
                hasUpdate = hasUpdate,
                availableVersionCode = info.availableVersionCode(),
                appUpdateInfo = info,
                isDownloaded = isDownloaded,
            )
        } catch (e: Exception) {
            PlayUpdateResult(
                hasUpdate = false,
                error = e.localizedMessage ?: e.message ?: "Google Play 업데이트 확인 실패",
            )
        }
    }

    fun startUpdateFlow(activity: Activity, onFallback: () -> Unit = {}) {
        val manager = getAppUpdateManager(activity)
        val info = cachedAppUpdateInfo

        if (info != null) {
            // 1. 이미 다운로드가 완료된 상태인 경우 즉시 설치 및 재시작
            if (info.installStatus() == InstallStatus.DOWNLOADED) {
                try {
                    manager.completeUpdate()
                    return
                } catch (_: Exception) {
                    // Fallback to store intent below
                }
            }

            // 2. IMMEDIATE (즉시 전체 화면 업데이트) 우선 시도, 불가 시 FLEXIBLE (백그라운드 다운로드)
            val updateType = when {
                info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) -> AppUpdateType.IMMEDIATE
                info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> AppUpdateType.FLEXIBLE
                else -> null
            }

            if (updateType != null && (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE ||
                    info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS)
            ) {
                try {
                    val options = AppUpdateOptions.newBuilder(updateType).build()
                    manager.startUpdateFlow(info, activity, options)
                    return
                } catch (_: Exception) {
                    // Fallback to store intent below
                }
            }
        }

        // 3. Fallback: Open Google Play Store page directly
        openPlayStore(activity)
        onFallback()
    }

    fun resumeUpdateIfDownloaded(context: Context) {
        val manager = getAppUpdateManager(context)
        manager.appUpdateInfo.addOnSuccessListener { info ->
            cachedAppUpdateInfo = info
            if (info.installStatus() == InstallStatus.DOWNLOADED) {
                manager.completeUpdate()
            }
        }
    }

    fun openPlayStore(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

