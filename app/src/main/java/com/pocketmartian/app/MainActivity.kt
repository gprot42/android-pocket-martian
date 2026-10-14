package com.pocketmartian.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.rememberNavController
import com.pocketmartian.app.data.local.LocationRepository
import com.pocketmartian.app.data.repository.ChatRepository
import com.pocketmartian.app.data.local.SettingsRepository
import com.pocketmartian.app.data.share.IncomingShareRepository
import com.pocketmartian.app.data.share.toIncomingShare
import com.pocketmartian.app.ui.navigation.AppNavigation
import com.pocketmartian.app.ui.theme.AppTheme
import com.pocketmartian.app.ui.theme.PocketMartianTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var locationRepository: LocationRepository
    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var incomingShareRepository: IncomingShareRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Rotation restores the same intent; skip so we don't re-apply a share
        // the user already edited or sent. Process death drops an unsent share.
        if (savedInstanceState == null) {
            handleShareIntent(intent)
        }
        setContent {
            val theme by settingsRepository.theme.collectAsState(initial = AppTheme.DARK)
            PocketMartianTheme(appTheme = theme) {
                val navController = rememberNavController()
                AppNavigation(
                    navController = navController,
                    incomingShareRepository = incomingShareRepository
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        val share = intent?.toIncomingShare(this) ?: return
        incomingShareRepository.offer(share)
    }

    // No GPS when the app opens: a session of up to 30 seconds on every open, for prompts
    // that mostly never use a location, was the app's largest battery cost. The GPS is
    // started instead while a prompt that needs it is being typed (see ChatViewModel).

    override fun onStop() {
        // Cancel any in-flight GPS session; the TTL cache is kept in memory.
        locationRepository.stopGpsWarmup()
        // An idle connection to api.x.ai left open pings every 20 s for as long as it is
        // pooled, waking the mobile radio each time. Close idle ones on leaving; a reply
        // still streaming is in use and carries on.
        chatRepository.releaseIdleConnections()
        super.onStop()
    }
}
