package com.example.htmlapkstudio

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val adapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    
    // Dedicated SPP UUID for Offgrid / Aether communication
    private val serviceUuid: UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")

    @Volatile private var clientSocket: BluetoothSocket? = null
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var outputStream: OutputStream? = null
    private val permissionReqCode = 9021

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {}
        web.addJavascriptInterface(BluetoothBridge(), "AndroidBluetooth")
        web.loadUrl("file:///android_asset/index.html")
    }

    private fun checkPermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = needed.filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), permissionReqCode)
            return false
        }
        return true
    }

    private fun emitEvent(obj: JSONObject) {
        runOnUiThread {
            val js = "window.onNativeBtEvent && window.onNativeBtEvent($obj);"
            web.evaluateJavascript(js, null)
        }
    }

    private fun emitState(state: String, peerName: String = "", message: String = "") {
        val json = JSONObject()
            .put("event", "STATE_CHANGED")
            .put("state", state)
            .put("peerName", peerName)
            .put("message", message)
        emitEvent(json)
    }

    private fun emitError(msg: String) {
        val json = JSONObject()
            .put("event", "STATE_CHANGED")
            .put("state", "ERROR")
            .put("message", msg)
        emitEvent(json)
    }

    private inner class BluetoothBridge {
        @JavascriptInterface
        fun isBluetoothEnabled(): Boolean {
            return try { adapter?.isEnabled == true } catch (_: SecurityException) { false }
        }

        @JavascriptInterface
        fun startServer(name: String) {
            if (!checkPermissions()) {
                emitError("Bluetooth permissions required. Grant them and tap Host again.")
                return
            }
            val bt = adapter ?: run {
                emitError("Device does not support Bluetooth.")
                return
            }
            if (!bt.isEnabled) {
                emitError("Please turn on Bluetooth first.")
                return
            }

            thread {
                try {
                    closeAllConnections()
                    serverSocket = bt.listenUsingRfcommWithServiceRecord(name.take(30), serviceUuid)
                    emitState("hosting", "", "Waiting for incoming peer connection...")
                    
                    val socket = serverSocket!!.accept() // Blocks until a client connects
                    serverSocket?.close()
                    serverSocket = null
                    
                    manageConnectedSocket(socket)
                } catch (e: Exception) {
                    emitError("Server error: ${e.message}")
                    emitState("disconnected")
                }
            }
        }

        @JavascriptInterface
        fun discoverAndConnect() {
            if (!checkPermissions()) {
                emitError("Bluetooth permissions required. Grant them and tap Find & connect again.")
                return
            }
            val bt = adapter ?: run {
                emitError("Device does not support Bluetooth.")
                return
            }
            if (!bt.isEnabled) {
                emitError("Please turn on Bluetooth first.")
                return
            }

            try {
                val pairedDevices = bt.bondedDevices
                if (pairedDevices.isNullOrEmpty()) {
                    emitError("No paired devices found. Pair both phones in Android Bluetooth Settings first.")
                    return
                }

                val names = pairedDevices.map { d ->
                    try { "${d.name ?: "Unknown Device"} (${d.address})" } catch (_: SecurityException) { d.address }
                }.toTypedArray()

                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Select Paired Host Phone")
                        .setItems(names) { _, which ->
                            val targetDevice = pairedDevices.elementAt(which)
                            connectToDevice(targetDevice)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } catch (e: Exception) {
                emitError("Discovery error: ${e.message}")
            }
        }

        @JavascriptInterface
        fun sendMessage(text: String) {
            val cleanText = text.take(2000)
            thread {
                try {
                    val out = outputStream ?: run {
                        emitError("Not connected to any peer.")
                        return@thread
                    }
                    out.write((cleanText + "\n").toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                } catch (e: Exception) {
                    emitError("Send failed: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun disconnect() {
            closeAllConnections()
            emitState("disconnected", "", "Connection closed")
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        thread {
            try {
                closeAllConnections()
                val deviceName = try { device.name ?: "Peer Device" } catch (_: SecurityException) { device.address }
                emitState("connecting", deviceName, "Connecting to $deviceName...")

                val socket = device.createRfcommSocketToServiceRecord(serviceUuid)
                socket.connect() // Blocks until connected
                
                manageConnectedSocket(socket)
            } catch (e: Exception) {
                emitError("Connection failed: ${e.message}. Ensure the host phone is actively hosting.")
                emitState("disconnected")
            }
        }
    }

    private fun manageConnectedSocket(socket: BluetoothSocket) {
        clientSocket = socket
        outputStream = socket.outputStream
        val remoteName = try { socket.remoteDevice.name ?: "Peer Device" } catch (_: SecurityException) { "Connected Peer" }
        
        emitState("connected", remoteName, "Connected successfully")

        thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream, StandardCharsets.UTF_8))
                while (true) {
                    val line = reader.readLine() ?: break
                    val packet = JSONObject()
                        .put("event", "MESSAGE_RECEIVED")
                        .put("raw", line)
                    emitEvent(packet)
                }
            } catch (e: Exception) {
                emitError("Stream closed or peer disconnected")
            } finally {
                closeAllConnections()
                emitState("disconnected", "", "Disconnected from peer")
            }
        }
    }

    @Synchronized
    private fun closeAllConnections() {
        try { outputStream?.close() } catch (_: Exception) {}
        outputStream = null
        try { clientSocket?.close() } catch (_: Exception) {}
        clientSocket = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    override fun onDestroy() {
        closeAllConnections()
        web.removeJavascriptInterface("AndroidBluetooth")
        web.destroy()
        super.onDestroy()
    }
}
