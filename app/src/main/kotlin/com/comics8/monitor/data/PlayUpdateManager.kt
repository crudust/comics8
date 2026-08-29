package com.comics8.monitor.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
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
    val error: String? = null,
)

object PlayUpdateManager {
    private var cachedAppUpdateManager: AppUpdateManager? = null
    private var cachedAppUpdateInfo: AppUpdateInfo? = null

    private fun getAppUpdateManager(context: Context): AppUpdateManager {
        return cachedAppUpdateManager ?: AppUpdateManagerFactory.create(context.applicationContext).also {
            cachedAppUpdateManager = it
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

            val availability = info.updateAvailability()
            val hasUpdate = availability == UpdateAvailability.UPDATE_AVAILABLE ||
                availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS

            PlayUpdateResult(
                hasUpdate = hasUpdate,
                availableVersionCode = info.availableVersionCode(),
                appUpdateInfo = info,
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

        if (info != null && info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
            val updateType = when {
                info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> AppUpdateType.FLEXIBLE
                info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) -> AppUpdateType.IMMEDIATE
                else -> null
            }

            if (updateType != null) {
                try {
                    val options = AppUpdateOptions.newBuilder(updateType).build()
                    manager.startUpdateFlow(info, activity, options)
                    return
                } catch (_: Exception) {
                    // Fallback to store intent below
                }
            }
        }

        // Fallback: Open Google Play Store page directly
        openPlayStore(activity)
        onFallback()
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
