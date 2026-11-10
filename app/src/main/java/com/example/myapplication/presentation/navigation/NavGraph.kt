package com.example.myapplication.presentation.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.example.myapplication.presentation.components.TwBottomNav
import com.example.myapplication.presentation.history.HistoryScreen
import com.example.myapplication.presentation.home.HomeScreen
import com.example.myapplication.presentation.nearby.NearbyDiscoveryScreen
import com.example.myapplication.presentation.pairing.SenderPairingScreen
import com.example.myapplication.presentation.receive.ReceiveScreen
import com.example.myapplication.presentation.send.SendScreen
import com.example.myapplication.presentation.settings.AppThemeMode
import com.example.myapplication.presentation.settings.SettingsScreen
import com.example.myapplication.presentation.transfer.TransferScreen

private val topLevelRoutes = setOf(Screen.Home.route, Screen.History.route, Screen.Settings.route)

@Composable
fun NavGraph(
    navController: NavHostController,
    currentThemeMode: AppThemeMode = AppThemeMode.SYSTEM,
    onThemeModeChange: (AppThemeMode) -> Unit = {}
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    fun navigateTopLevel(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            enterTransition = {
                if (targetState.destination.route in topLevelRoutes && initialState.destination.route in topLevelRoutes) {
                    fadeIn(tween(180))
                } else {
                    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(260)) + fadeIn(tween(220))
                }
            },
            exitTransition = {
                if (targetState.destination.route in topLevelRoutes && initialState.destination.route in topLevelRoutes) {
                    fadeOut(tween(160))
                } else {
                    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(260)) + fadeOut(tween(200))
                }
            },
            popEnterTransition = {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(260)) + fadeIn(tween(220))
            },
            popExitTransition = {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(260)) + fadeOut(tween(200))
            }
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateToSend          = { navController.navigate(Screen.Send.route) },
                    onNavigateToReceive       = { navController.navigate(Screen.Receive.route) },
                    onNavigateToHistory       = { navigateTopLevel(Screen.History.route) },
                    onNavigateToSettings      = { navigateTopLevel(Screen.Settings.route) },
                    onNavigateToNearbyReceive = { navController.navigate(Screen.NearbyDiscovery.createRoute(false)) }
                )
            }

            composable(Screen.Send.route) {
                SendScreen(
                    onNavigateBack            = { navController.popBackStack() },
                    onNavigateToSenderPairing = { navController.navigate(Screen.SenderPairing.route) },
                    onNavigateToNearby        = { navController.navigate(Screen.NearbyDiscovery.createRoute(true)) }
                )
            }

            composable(Screen.SenderPairing.route) {
                SenderPairingScreen(
                    onNavigateBack       = { navController.popBackStack() },
                    onNavigateToTransfer = {
                        navController.navigate(Screen.Transfer.createRoute(true, "0.0.0.0", true))
                    }
                )
            }

            composable(Screen.Receive.route) {
                ReceiveScreen(
                    onNavigateBack       = { navController.popBackStack() },
                    onNavigateToNearby   = { navController.navigate(Screen.NearbyDiscovery.createRoute(false)) },
                    onNavigateToTransfer = {
                        navController.navigate(Screen.Transfer.createRoute(false, "0.0.0.0", false))
                    }
                )
            }

            composable(Screen.History.route) {
                HistoryScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateBack    = { navController.popBackStack() },
                    currentThemeMode  = currentThemeMode,
                    onThemeModeChange = onThemeModeChange
                )
            }

            composable(
                route = Screen.NearbyDiscovery.route,
                arguments = listOf(navArgument("isSender") { type = NavType.BoolType })
            ) { backStackEntry ->
                val isSender = backStackEntry.arguments?.getBoolean("isSender") ?: false
                NearbyDiscoveryScreen(
                    isSender             = isSender,
                    onNavigateToTransfer = { hostIp, isGroupOwner ->
                        navController.navigate(Screen.Transfer.createRoute(isSender, hostIp, isGroupOwner))
                    },
                    onNavigateBack       = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.Transfer.route,
                arguments = listOf(
                    navArgument("isSender")     { type = NavType.BoolType },
                    navArgument("hostIp")       { type = NavType.StringType },
                    navArgument("isGroupOwner") { type = NavType.BoolType }
                )
            ) { backStackEntry ->
                val isSender     = backStackEntry.arguments?.getBoolean("isSender") ?: false
                val hostIp       = backStackEntry.arguments?.getString("hostIp") ?: ""
                val isGroupOwner = backStackEntry.arguments?.getBoolean("isGroupOwner") ?: false

                TransferScreen(
                    isSender       = isSender,
                    hostIp         = hostIp,
                    isGroupOwner   = isGroupOwner,
                    onNavigateHome = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Home.route) { inclusive = true }
                        }
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
        AnimatedVisibility(
            visible = currentRoute in topLevelRoutes && imeBottom == 0,
            enter = slideInVertically { it } + fadeIn(tween(180)),
            exit = slideOutVertically { it } + fadeOut(tween(140)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            TwBottomNav(
                currentRoute = currentRoute,
                onNavigate   = { targetRoute ->
                    if (targetRoute != currentRoute) navigateTopLevel(targetRoute)
                }
            )
        }
    }
}
