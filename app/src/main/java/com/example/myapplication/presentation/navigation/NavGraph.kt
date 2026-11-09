package com.example.myapplication.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.myapplication.presentation.home.HomeScreen
import com.example.myapplication.presentation.send.SendScreen
import com.example.myapplication.presentation.receive.ReceiveScreen
import com.example.myapplication.presentation.history.HistoryScreen
import com.example.myapplication.presentation.settings.SettingsScreen

@Composable
fun NavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToSend = { navController.navigate(Screen.Send.route) },
                onNavigateToReceive = { navController.navigate(Screen.Receive.route) },
                onNavigateToHistory = { navController.navigate(Screen.History.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
            )
        }
        
        composable(Screen.Send.route) {
            SendScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSenderPairing = { navController.navigate(Screen.SenderPairing.route) },
                onNavigateToNearby = { navController.navigate(Screen.NearbyDiscovery.route) }
            )
        }
        
        composable(Screen.SenderPairing.route) {
            com.example.myapplication.presentation.pairing.SenderPairingScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable(Screen.Receive.route) {
            ReceiveScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToNearby = { navController.navigate(Screen.NearbyDiscovery.createRoute(false)) }
            )
        }
        
        composable(Screen.History.route) {
            HistoryScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable(
            route = Screen.NearbyDiscovery.route,
            arguments = listOf(androidx.navigation.navArgument("isSender") { type = androidx.navigation.NavType.BoolType })
        ) { backStackEntry ->
            val isSender = backStackEntry.arguments?.getBoolean("isSender") ?: false
            com.example.myapplication.presentation.nearby.NearbyDiscoveryScreen(
                isSender = isSender,
                onNavigateToTransfer = { hostIp, isGroupOwner ->
                    navController.navigate(Screen.Transfer.createRoute(isSender, hostIp, isGroupOwner))
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable(
            route = Screen.Transfer.route,
            arguments = listOf(
                androidx.navigation.navArgument("isSender") { type = androidx.navigation.NavType.BoolType },
                androidx.navigation.navArgument("hostIp") { type = androidx.navigation.NavType.StringType },
                androidx.navigation.navArgument("isGroupOwner") { type = androidx.navigation.NavType.BoolType }
            )
        ) { backStackEntry ->
            val isSender = backStackEntry.arguments?.getBoolean("isSender") ?: false
            val hostIp = backStackEntry.arguments?.getString("hostIp") ?: ""
            val isGroupOwner = backStackEntry.arguments?.getBoolean("isGroupOwner") ?: false
            
            com.example.myapplication.presentation.transfer.TransferScreen(
                isSender = isSender,
                hostIp = hostIp,
                isGroupOwner = isGroupOwner,
                onNavigateHome = { 
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
