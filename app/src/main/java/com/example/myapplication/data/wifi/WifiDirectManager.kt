package com.example.myapplication.data.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SuppressLint("MissingPermission")
class WifiDirectManager(private val context: Context) {

    private val manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager?
    private val channel: WifiP2pManager.Channel? = manager?.initialize(context, Looper.getMainLooper(), null)
    private var receiver: BroadcastReceiver? = null

    private val _peers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val peers: StateFlow<List<WifiP2pDevice>> = _peers.asStateFlow()

    private val _connectionInfo = MutableStateFlow<WifiP2pInfo?>(null)
    val connectionInfo: StateFlow<WifiP2pInfo?> = _connectionInfo.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _thisDeviceName = MutableStateFlow<String?>(null)
    val thisDeviceName: StateFlow<String?> = _thisDeviceName.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun startDiscovery(retryOnBusy: Boolean = true) {
        _error.value = null
        if (manager == null || channel == null) {
            _error.value = "Wi-Fi Direct is not supported on this device."
            return
        }

        registerReceiver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            manager.requestDeviceInfo(channel) { device ->
                if (!device?.deviceName.isNullOrBlank()) {
                    _thisDeviceName.value = device?.deviceName
                }
            }
        }

        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _isDiscovering.value = true
            }

            override fun onFailure(reasonCode: Int) {
                _isDiscovering.value = false
                if (reasonCode == WifiP2pManager.BUSY && retryOnBusy) {
                    mainHandler.postDelayed({
                        if (receiver != null) {
                            startDiscovery(retryOnBusy = false)
                        }
                    }, 1200L)
                    return
                }
                val reasonText = when (reasonCode) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this device."
                    WifiP2pManager.BUSY -> "Wi-Fi Direct is busy. Please try again in a moment."
                    WifiP2pManager.ERROR -> "Unable to start Wi-Fi Direct. Ensure Wi-Fi and Location are turned ON."
                    else -> "Wi-Fi Direct failed with code: $reasonCode"
                }
                _error.value = reasonText
            }
        })
    }

    fun stopDiscovery() {
        mainHandler.removeCallbacksAndMessages(null)
        if (manager != null && channel != null) {
            manager.stopPeerDiscovery(channel, null)
        }
        _isDiscovering.value = false
        unregisterReceiver()
    }

    fun connectToDevice(deviceAddress: String) {
        _error.value = null
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            // Let the system negotiate the group owner
        }

        manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                // Connection initiated, we'll get status in the BroadcastReceiver
            }

            override fun onFailure(reason: Int) {
                _error.value = "Failed to connect. Reason code: $reason"
            }
        })
    }

    fun disconnect() {
        manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _connectionInfo.value = null
            }

            override fun onFailure(reason: Int) {
                // Ignored
            }
        })
    }

    private fun registerReceiver() {
        if (receiver == null) {
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                            val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                            if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                                _error.value = "Wi-Fi Direct is disabled. Please turn on Wi-Fi."
                                _peers.value = emptyList()
                            }
                        }
                        WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                            manager?.requestPeers(channel) { peerList ->
                                _peers.value = peerList.deviceList.toList()
                            }
                        }
                        WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                            @Suppress("DEPRECATION")
                            val p2pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                            } else {
                                intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                            }
                            @Suppress("DEPRECATION")
                            val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                            } else {
                                intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                            }
                            val isConnected = p2pInfo?.groupFormed == true || networkInfo?.isConnected == true
                            if (isConnected) {
                                manager?.requestConnectionInfo(channel) { info ->
                                    if (info?.groupFormed == true) {
                                        _connectionInfo.value = info
                                        _isDiscovering.value = false
                                    }
                                }
                            } else {
                                _connectionInfo.value = null
                            }
                        }
                        WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                            @Suppress("DEPRECATION")
                            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                            } else {
                                intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                            }
                            if (!device?.deviceName.isNullOrBlank()) {
                                _thisDeviceName.value = device?.deviceName
                            }
                        }
                        WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                            val state = intent.getIntExtra(
                                WifiP2pManager.EXTRA_DISCOVERY_STATE,
                                WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED
                            )
                            _isDiscovering.value = state == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, intentFilter)
            }
        }
    }

    private fun unregisterReceiver() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // Receiver not registered
            }
            receiver = null
        }
    }
}
