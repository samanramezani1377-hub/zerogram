package com.zerochat.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zerochat.ui.chat.ChatScreen
import com.zerochat.ui.contacts.ContactsScreen
import com.zerochat.ui.discovery.DiscoveryScreen
import com.zerochat.ui.settings.SettingsScreen

/**
 * Application navigation graph.
 *
 * Screens:
 *  - contacts (home)
 *  - discovery (find nearby peers)
 *  - chat/{peerFingerprint} (conversation)
 *  - settings
 *
 * Uses Compose Navigation with standard slide transitions.
 */
object NavRoutes {
    const val CONTACTS = "contacts"
    const val DISCOVERY = "discovery"
    const val CHAT = "chat/{peerFingerprint}"
    const val SETTINGS = "settings"

    fun chatRoute(fingerprint: String) = "chat/$fingerprint"
}

@Composable
fun ZeroChatNavHost(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = NavRoutes.CONTACTS,
        enterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Left,
                tween(300),
            )
        },
        exitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Left,
                tween(300),
            )
        },
        popEnterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Right,
                tween(300),
            )
        },
        popExitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Right,
                tween(300),
            )
        },
    ) {
        composable(NavRoutes.CONTACTS) {
            ContactsScreen(
                onNavigateToChat = { fp -> navController.navigate(NavRoutes.chatRoute(fp)) },
                onNavigateToDiscovery = { navController.navigate(NavRoutes.DISCOVERY) },
                onNavigateToSettings = { navController.navigate(NavRoutes.SETTINGS) },
            )
        }

        composable(NavRoutes.DISCOVERY) {
            DiscoveryScreen(
                onNavigateBack = { navController.popBackStack() },
                onPeerSelected = { fp -> navController.navigate(NavRoutes.chatRoute(fp)) },
            )
        }

        composable(
            route = NavRoutes.CHAT,
            arguments = listOf(
                navArgument("peerFingerprint") { type = NavType.StringType }
            ),
        ) { entry ->
            val fingerprint = entry.arguments?.getString("peerFingerprint") ?: return@composable
            ChatScreen(
                peerFingerprint = fingerprint,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(NavRoutes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
