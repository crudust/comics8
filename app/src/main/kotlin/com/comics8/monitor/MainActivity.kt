package com.comics8.monitor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.comics8.monitor.ui.ComicsAppUi
import com.comics8.monitor.ui.ListingViewModel
import com.comics8.monitor.ui.theme.ComicsTheme
import com.comics8.monitor.work.RefreshWorker

class MainActivity : ComponentActivity() {
    private val viewModel: ListingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.navigationBars())
        }
        if (savedInstanceState == null) {
            handleIntent(intent)
        }
        setContent {
            ComicsTheme {
                ComicsAppUi(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (BuildConfig.IS_PLAY_STORE) {
            com.comics8.monitor.data.PlayUpdateManager.checkAndResumeUpdate(this)
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.onAppBackgrounded()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (BuildConfig.IS_PLAY_STORE) {
            com.comics8.monitor.data.PlayUpdateManager.unregisterListener()
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (viewModel.state.value.screen == com.comics8.monitor.ui.Screen.Reader &&
            viewModel.state.value.volumePageTurnEnabled
        ) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    viewModel.onVolumePageTurn(advance = true)
                    return true
                }
                android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                    viewModel.onVolumePageTurn(advance = false)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (viewModel.state.value.screen == com.comics8.monitor.ui.Screen.Reader &&
            viewModel.state.value.volumePageTurnEnabled
        ) {
            if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(RefreshWorker.EXTRA_FROM_NOTIFICATION, false)) {
            val targetTab = intent.getStringExtra(RefreshWorker.EXTRA_TARGET_TAB)
            viewModel.handleNotificationLaunch(targetTab)
            return
        }
        val shared = sharedJsUri(intent) ?: return
        viewModel.importJsFromUri(shared)
    }

    private fun sharedJsUri(intent: Intent): Uri? {
        val action = intent.action ?: return null
        val uri = when (action) {
            Intent.ACTION_SEND -> intent.parcelableUri(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return null
        val name = uri.lastPathSegment.orEmpty()
        val type = intent.type.orEmpty()
        val looksJs = name.endsWith(".js", ignoreCase = true) ||
            type.contains("javascript", ignoreCase = true) ||
            type == "text/plain" ||
            type == "application/octet-stream" ||
            type.startsWith("text/")
        return uri.takeIf { looksJs }
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableUri(key: String): Uri? =
        extras?.getParcelable(key) as? Uri
}
