package com.pocketmartian.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.pocketmartian.app.data.share.IncomingShareRepository
import com.pocketmartian.app.ui.screens.AboutScreen
import com.pocketmartian.app.ui.screens.ChatScreen
import com.pocketmartian.app.ui.screens.DebugLogsScreen
import com.pocketmartian.app.ui.screens.HistoryScreen
import com.pocketmartian.app.ui.screens.SettingsScreen
import com.pocketmartian.app.ui.screens.UsageScreen
import com.pocketmartian.app.ui.screens.VoiceTranslatorScreen

sealed class Screen(val route: String) {
    object Chat : Screen("chat")
    object Settings : Screen("settings?section={section}") {
        fun destination(section: String? = null): String =
            if (section.isNullOrEmpty()) "settings" else "settings?section=$section"
    }
    object About : Screen("about")
    object DebugLogs : Screen("debug_logs")
    object History : Screen("history")
    object VoiceTranslator : Screen("voice_translator")
    object Usage : Screen("usage")
}

@Composable
fun AppNavigation(
    navController: NavHostController,
    incomingShareRepository: IncomingShareRepository
) {
    LaunchedEffect(navController) {
        incomingShareRepository.navigateToChat.collect {
            if (navController.currentDestination?.route != Screen.Chat.route) {
                val popped = navController.popBackStack(Screen.Chat.route, inclusive = false)
                if (!popped && navController.currentDestination?.route != Screen.Chat.route) {
                    navController.navigate(Screen.Chat.route) {
                        launchSingleTop = true
                    }
                }
            }
        }
    }
    NavHost(
        navController = navController,
        startDestination = Screen.Chat.route
    ) {
        composable(Screen.Chat.route) {
            ChatScreen(
                onNavigateToSettings = { navController.navigate(Screen.Settings.destination()) },
                onNavigateToDebugLogs = { navController.navigate(Screen.DebugLogs.route) },
                onNavigateToHistory = { navController.navigate(Screen.History.route) },
                onNavigateToVoiceTranslator = { navController.navigate(Screen.VoiceTranslator.route) }
            )
        }
        composable(
            route = Screen.Settings.route,
            arguments = listOf(
                navArgument("section") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { entry ->
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToAbout = { navController.navigate(Screen.About.route) },
                onNavigateToUsage = { navController.navigate(Screen.Usage.route) },
                onNavigateToLogs = { navController.navigate(Screen.DebugLogs.route) },
                initialSection = entry.arguments?.getString("section")
            )
        }
        composable(Screen.About.route) {
            AboutScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.DebugLogs.route) {
            DebugLogsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.History.route) {
            HistoryScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.VoiceTranslator.route) {
            VoiceTranslatorScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSettings = { navController.navigate(Screen.Settings.destination()) },
                onNavigateToDebugLogs = { navController.navigate(Screen.DebugLogs.route) }
            )
        }
        composable(Screen.Usage.route) {
            UsageScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.destination("management"))
                }
            )
        }
    }
}
