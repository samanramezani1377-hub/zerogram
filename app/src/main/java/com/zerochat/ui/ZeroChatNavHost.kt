package com.zerochat.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
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
 * Main navigation graph for the app.
 *
 * Screens:
 *  - Contacts (home)
 *  - Discovery (find nearby peers)
 *  - Chat (conversation view)
 *  - Settings
 */
@Composable
fun ZeroChatNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "contacts",
        enterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300))
        },
        exitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300))
        },
    ) {
        composable("contacts") {
            ContactsScreen(
                onNavigateToChat = { fingerprint ->
                    navController.navigate("chat/$fingerprint")
                },
                onNavigateToDiscovery = {
                    navController.navigate("discovery")
                },
                onNavigateToSettings = {
                    navController.navigate("settings")
                },
            )
        }

        composable("discovery") {
            DiscoveryScreen(
                onNavigateBack = { navController.popBackStack() },
                onPeerSelected = { fingerprint ->
                    navController.navigate("chat/$fingerprint")
                },
            )
        }

        composable(
            route = "chat/{peerFingerprint}",
            arguments = listOf(
                navArgument("peerFingerprint") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val fingerprint = backStackEntry.arguments?.getString("peerFingerprint") ?: return@composable
            ChatScreen(
                peerFingerprint = fingerprint,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
