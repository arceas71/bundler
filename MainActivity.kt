package com.example.htmlapkstudio

import android.Manifest
import android.app.Activity
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
import java.io.PrintWriter
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val adapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private val serviceUuid: UUID = UUID.fromString("c9a7c110-6c1b-4d8b-9d83-1c4b6a5f7e20")
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var writer: PrintWriter? = null
    private val requestCode = 7134

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
            }
        }
        web.addJavascriptInterface(BluetoothBridge(), "AndroidBluetooth")
        web.loadUrl("file:///android_asset/index.html")
    }

    private fun hasPermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = needed.filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), requestCode)
            return false
        }
        return true
    }

    private fun emit(obj: JSONObject) {
        runOnUiThread {
            val js = "window.dispatchEvent(new CustomEvent('nativeBluetoothEvent',{detail:${obj}}));"
            web.evaluateJavascript(js, null)
        }
    }

    private fun state(s: String, message: String = "") {
        emit(JSONObject().put("type", "state").put("state", s).put("message", message))
    }

    private fun error(message: String) {
        emit(JSONObject().put("type", "error").put("message", message))
    }

    private inner class BluetoothBridge {
        @JavascriptInterface fun isBluetoothEnabled(): Boolean {
            return try { adapter?.isEnabled == true } catch (_: SecurityException) { false }
        }

        @JavascriptInterface fun startServer(name: String) {
            if (!hasPermissions()) { error("Bluetooth permission required. Grant it, then tap Host again."); return }
            val a = adapter ?: run { error("This device has no Bluetooth adapter."); return }
            if (!a.isEnabled) {
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 41)
                error("Enable Bluetooth, then tap Host again.")
                return
            }
            thread {
                try {
                    closeConnections()
                    serverSocket = a.listenUsingRfcommWithServiceRecord(name.take(32), serviceUuid)
                    state("hosting", "Waiting for a Bluetooth connection…")
                    val accepted = serverSocket!!.accept()
                    serverSocket?.close()
                    serverSocket = null
                    attach(accepted)
                } catch (e: Exception) {
                    error("Host failed: ${e.message ?: "unknown error"}")
                    state("disconnected")
                }
            }
        }

        @JavascriptInterface fun discoverAndConnect() {
            if (!hasPermissions()) { error("Bluetooth permission required. Grant it, then tap Find & connect again."); return }
            val a = adapter ?: run { error("This device has no Bluetooth adapter."); return }
            if (!a.isEnabled) {
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 42)
                error("Enable Bluetooth, then tap Find & connect again.")
                return
            }
            try {
                // Use the Android system picker so the user explicitly chooses a paired device.
                val bonded = a.bondedDevices
                if (bonded.isNullOrEmpty()) {
                    startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                    error("Pair the phones in Android Bluetooth settings first, then return and tap Find & connect.")
                    return
                }
                val names = bonded.map { d ->
                    val label = try { "${d.name ?: "Unknown device"} (${d.address})" } catch (_: Exception) { d.address }
                    label
                }.toTypedArray()
                runOnUiThread {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Choose paired host")
                        .setItems(names) { _, which ->
                            val device = bonded.elementAt(which)
                            connectTo(device)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } catch (e: Exception) {
                error("Could not list paired devices: ${e.message}")
            }
        }

        @JavascriptInterface fun sendMessage(message: String) {
            val safe = message.take(4000).replace("\n", " ")
            thread {
                try {
                    val w = writer ?: run { error("Not connected."); return@thread }
                    w.println(safe)
                    w.flush()
                    if (w.checkError()) error("Message could not be sent.")
                } catch (e: Exception) {
                    error("Send failed: ${e.message}")
                }
            }
        }

        @JavascriptInterface fun disconnect() {
            closeConnections()
            state("disconnected", "Disconnected")
        }
    }

    private fun connectTo(device: BluetoothDevice) {
        thread {
            try {
                closeConnections()
                state("connecting", "Connecting to ${device.name ?: "device"}…")
                val s = device.createRfcommSocketToServiceRecord(serviceUuid)
                s.connect()
                attach(s)
            } catch (e: Exception) {
                error("Connection failed: ${e.message ?: "Pair the devices in Android settings first."}")
                state("disconnected")
            }
        }
    }

    private fun attach(s: BluetoothSocket) {
        socket = s
        writer = PrintWriter(s.outputStream, true)
        val remoteName = try { s.remoteDevice.name ?: "Bluetooth device" } catch (_: Exception) { "Bluetooth device" }
        state("connected", "Connected to $remoteName")
        thread {
            try {
                val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
                while (true) {
                    val line = reader.readLine() ?: break
                    emit(JSONObject().put("type", "message").put("message", line).put("from", remoteName))
                }
            } catch (e: Exception) {
                error("Connection ended: ${e.message ?: "disconnected"}")
            } finally {
                closeConnections()
                state("disconnected", "Connection closed")
            }
        }
    }

    @Synchronized private fun closeConnections() {
        try { writer?.close() } catch (_: Exception) {}
        writer = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    override fun onDestroy() {
        closeConnections()
        web.removeJavascriptInterface("AndroidBluetooth")
        web.destroy()
        super.onDestroy()
    }
}
