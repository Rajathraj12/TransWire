package com.example.myapplication.presentation.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Send : Screen("send")
    object Receive : Screen("receive")
    object History : Screen("history")
    object Settings : Screen("settings")
    object SenderPairing : Screen("sender_pairing")
    object NearbyDiscovery : Screen("nearby_discovery/{isSender}") {
        fun createRoute(isSender: Boolean) = "nearby_discovery/$isSender"
    }
    object Transfer : Screen("transfer/{isSender}/{hostIp}/{isGroupOwner}") {
        fun createRoute(isSender: Boolean, hostIp: String, isGroupOwner: Boolean) = "transfer/$isSender/$hostIp/$isGroupOwner"
    }
}
