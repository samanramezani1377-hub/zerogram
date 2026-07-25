package com.zerochat.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zerochat.ui.chat.ChatScreen
import com.zerochat.ui.contacts.ContactsScreen
import com.zerochat.ui.discovery.DiscoveryScreen
import com.zerochat.ui.settings.SettingsScreen
import com.zerochat.ui.requests.RequestInboxScreen
import com.zerochat.ui.blocked.BlockedPeersScreen

/**
 * Telegram-style navigation with bottom tabs.
 */
object NavRoutes {
    const val CHATS = "chats"
    const val DISCOVERY = "discovery"
    const val SETTINGS = "settings"
    const val CHAT = "chat/{peerFingerprint}"
    const val REQUESTS = "requests"
    const val BLOCKED = "blocked"

    fun chatRoute(fingerprint: String) = "chat/$fingerprint"
}

data class BottomNavItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

val bottomNavItems = listOf(
    BottomNavItem(NavRoutes.CHATS, "Chats", Icons.Filled.Chat, Icons.Outlined.Chat),
    BottomNavItem(NavRoutes.DISCOVERY, "Discover", Icons.Filled.PersonSearch, Icons.Outlined.PersonSearch),
    BottomNavItem(NavRoutes.SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZeroChatNavHost(
    navController: NavHostController = rememberNavController(),
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Hide bottom bar on chat detail screen
    val showBottomBar = currentRoute != NavRoutes.CHAT &&
            currentRoute != NavRoutes.REQUESTS &&
            currentRoute != NavRoutes.BLOCKED

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = androidx.compose.ui.unit.dp.times(0),
                ) {
                    bottomNavItems.forEach { item ->
                        val selected = currentRoute == item.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (currentRoute != item.route) {
                                    navController.navigate(item.route) {
                                        popUpTo(NavRoutes.CHATS) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.label,
                                )
                            },
                            label = { Text(item.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NavRoutes.CHATS,
            enterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Left,
                    tween(250),
                )
            },
            exitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Left,
                    tween(250),
                )
            },
            popEnterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Right,
                    tween(250),
                )
            },
            popExitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Right,
                    tween(250),
                )
            },
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(NavRoutes.CHATS) {
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
                    onNavigateToBlocked = { navController.navigate(NavRoutes.BLOCKED) },
                    onNavigateToRequests = { navController.navigate(NavRoutes.REQUESTS) },
                )
            }

            composable(NavRoutes.REQUESTS) {
                RequestInboxScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable(NavRoutes.BLOCKED) {
                BlockedPeersScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
        }
    }
}
