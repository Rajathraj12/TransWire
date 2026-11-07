package com.example.myapplication.presentation.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Send : Screen("send")
    object Receive : Screen("receive")
    object History : Screen("history")
    object Settings : Screen("settings")
    object SenderPairing : Screen("sender_pairing")
}
