package com.comics8.monitor

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.comics8.monitor.data.Catalog
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
        handleIntent(intent)
        setContent {
            ComicsTheme {
                ComicsAppUi(viewModel)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.onAppBackgrounded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(RefreshWorker.EXTRA_FROM_NOTIFICATION, false) == true) {
            val catalogName = intent.getStringExtra(RefreshWorker.EXTRA_TARGET_CATALOG)
            val catalog = catalogName?.let {
                try {
                    Catalog.valueOf(it)
                } catch (_: Exception) {
                    null
                }
            }
            viewModel.handleNotificationLaunch(catalog)
        }
    }
}
