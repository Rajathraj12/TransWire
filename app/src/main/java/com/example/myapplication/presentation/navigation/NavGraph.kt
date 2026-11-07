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
                onNavigateToSenderPairing = { navController.navigate(Screen.SenderPairing.route) }
            )
        }
        
        composable(Screen.SenderPairing.route) {
            com.example.myapplication.presentation.pairing.SenderPairingScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable(Screen.Receive.route) {
            ReceiveScreen(
                onNavigateBack = { navController.popBackStack() }
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
    }
}
