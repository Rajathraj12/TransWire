package com.example.myapplication.presentation.nearby

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.myapplication.data.wifi.WifiDirectManager

class NearbyDiscoveryViewModel(application: Application) : AndroidViewModel(application) {

    private val wifiDirectManager = WifiDirectManager(application)

    val peers = wifiDirectManager.peers
    val connectionInfo = wifiDirectManager.connectionInfo
    val isDiscovering = wifiDirectManager.isDiscovering
    val thisDeviceName = wifiDirectManager.thisDeviceName
    val error = wifiDirectManager.error

    fun startDiscovery() {
        wifiDirectManager.startDiscovery()
    }

    fun stopDiscovery() {
        wifiDirectManager.stopDiscovery()
    }

    fun connectTo(deviceAddress: String) {
        wifiDirectManager.connectToDevice(deviceAddress)
    }

    fun disconnect() {
        wifiDirectManager.disconnect()
    }

    override fun onCleared() {
        super.onCleared()
        wifiDirectManager.stopDiscovery()
        wifiDirectManager.disconnect()
    }
}
