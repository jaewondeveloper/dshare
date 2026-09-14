package com.dshare.sender

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity(), ScreenShareService.StateListener {

    private lateinit var deviceListScreen: View
    private lateinit var deviceList: RecyclerView
    private lateinit var emptyState: View
    private lateinit var connectedScreen: View
    private lateinit var connectedDeviceName: TextView
    private lateinit var shareStatusText: TextView
    private lateinit var btnShareToggle: MaterialButton
    private lateinit var btnDisconnect: View

    private val adapter = DeviceAdapter { device -> showCodeDialog(device) }
    private var discovery: NsdDiscovery? = null

    private var connectedDevice: DiscoveredDevice? = null
    private var connectedCode: String? = null
    private var validationClient: SignalingClient? = null
    private var isSharing = false

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startSharing(result.resultCode, result.data!!)
            } else {
                Toast.makeText(this, "화면 공유 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        deviceList.layoutManager = LinearLayoutManager(this)
        deviceList.adapter = adapter

        btnShareToggle.setOnClickListener {
            if (isSharing) disconnectAndReturnToList() else requestScreenCapture()
        }
        btnDisconnect.setOnClickListener { disconnectAndReturnToList() }

        discovery = NsdDiscovery(applicationContext) { devices ->
            runOnUiThread { updateDeviceList(devices) }
        }
    }

    private fun bindViews() {
        deviceListScreen = findViewById(R.id.deviceListScreen)
        deviceList = findViewById(R.id.deviceList)
        emptyState = findViewById(R.id.emptyState)
        connectedScreen = findViewById(R.id.connectedScreen)
        connectedDeviceName = findViewById(R.id.connectedDeviceName)
        shareStatusText = findViewById(R.id.shareStatusText)
        btnShareToggle = findViewById(R.id.btnShareToggle)
        btnDisconnect = findViewById(R.id.btnDisconnect)
    }

    override fun onStart() {
        super.onStart()
        ScreenShareService.listener = this
        if (connectedDevice == null) {
            updateDeviceList(emptyList())
            discovery?.start()
        }
    }

    override fun onStop() {
        super.onStop()
        if (connectedDevice == null) discovery?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ScreenShareService.listener == this) ScreenShareService.listener = null
        discovery?.stop()
        validationClient?.close()
        if (!isSharing) {
            ActiveSession.client?.close()
            ActiveSession.clear()
        }
    }

    private fun updateDeviceList(devices: List<DiscoveredDevice>) {
        adapter.submitList(devices)
        val empty = devices.isEmpty()
        emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        deviceList.visibility = if (empty) View.GONE else View.VISIBLE
    }

    // ---------------- Pairing code modal + validation ----------------

    private fun showCodeDialog(device: DiscoveredDevice) {
        val view = layoutInflater.inflate(R.layout.dialog_pairing_code, null)
        val input = view.findViewById<TextInputEditText>(R.id.codeInput)
        val errorText = view.findViewById<TextView>(R.id.codeErrorText)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(device.name)
            .setView(view)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_connect, null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val code = input.text?.toString()?.trim().orEmpty()
                if (code.length != 6) {
                    errorText.text = getString(R.string.error_code_length)
                    return@setOnClickListener
                }
                errorText.text = ""
                positive.isEnabled = false
                positive.text = getString(R.string.status_connecting_short)
                connectToDevice(device, code, errorText, positive, dialog)
            }
        }
        dialog.show()
    }

    private fun connectToDevice(
        device: DiscoveredDevice,
        code: String,
        errorText: TextView,
        positiveButton: android.widget.Button,
        dialog: AlertDialog
    ) {
        val handler = Handler(Looper.getMainLooper())
        var settled = false

        fun resetButton() {
            positiveButton.isEnabled = true
            positiveButton.text = getString(R.string.action_connect)
        }

        val timeoutRunnable = Runnable {
            if (settled) return@Runnable
            settled = true
            validationClient?.close()
            validationClient = null
            errorText.text = getString(R.string.error_connect_timeout)
            resetButton()
        }
        handler.postDelayed(timeoutRunnable, 7000)

        val client = SignalingClient(device.host, device.port, object : SignalingClient.Listener {
            override fun onJoined() {
                if (settled) return
                settled = true
                handler.removeCallbacks(timeoutRunnable)
                runOnUiThread {
                    connectedDevice = device
                    connectedCode = code
                    dialog.dismiss()
                    showConnectedScreen(device)
                }
                // Only one signaling session is accepted at a time by the receiver. Instead of
                // closing this validated connection and having ScreenShareService open a brand
                // new one later (which races the receiver's cleanup of this socket), hand this
                // same live connection off to the service via ActiveSession.
                val joinedClient = validationClient
                ActiveSession.client = joinedClient
                ActiveSession.host = device.host
                ActiveSession.port = device.port
                ActiveSession.code = code
                ActiveSession.deviceName = device.name
                validationClient = null

                // Swap in a listener that watches for the connection dying while the user is
                // still looking at the "connected" screen, before they've pressed share.
                joinedClient?.setListener(object : SignalingClient.Listener {
                    override fun onJoined() {}
                    override fun onJoinError(message: String) {}
                    override fun onAnswer(sdp: String) {}
                    override fun onRemoteIce(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {}
                    override fun onBye() {
                        runOnUiThread { disconnectAndReturnToList() }
                    }
                    override fun onSocketClosed() {
                        runOnUiThread { disconnectAndReturnToList() }
                    }
                })
            }

            override fun onJoinError(message: String) {
                if (settled) return
                settled = true
                handler.removeCallbacks(timeoutRunnable)
                runOnUiThread {
                    errorText.text = message
                    resetButton()
                }
            }

            override fun onSocketClosed() {
                if (settled) return
                settled = true
                handler.removeCallbacks(timeoutRunnable)
                runOnUiThread {
                    errorText.text = getString(R.string.error_connect_closed)
                    resetButton()
                }
            }

            override fun onAnswer(sdp: String) {}
            override fun onRemoteIce(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {}
            override fun onBye() {}
        })
        validationClient = client
        client.connectAndJoin(code)
    }

    // ---------------- Connected screen + sharing controls ----------------

    private fun showConnectedScreen(device: DiscoveredDevice) {
        deviceListScreen.visibility = View.GONE
        connectedScreen.visibility = View.VISIBLE
        connectedDeviceName.text = getString(R.string.status_connected_to, device.name)
        shareStatusText.text = ""
        btnShareToggle.text = getString(R.string.action_share_start)
        discovery?.stop()
    }

    private fun disconnectAndReturnToList() {
        if (isSharing) {
            val intent = Intent(this, ScreenShareService::class.java).setAction(ScreenShareService.ACTION_STOP)
            startService(intent)
            isSharing = false
        } else {
            ActiveSession.client?.close()
            ActiveSession.clear()
        }
        connectedDevice = null
        connectedCode = null
        shareStatusText.text = ""
        btnShareToggle.text = getString(R.string.action_share_start)
        connectedScreen.visibility = View.GONE
        deviceListScreen.visibility = View.VISIBLE
        updateDeviceList(emptyList())
        discovery?.start()
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startSharing(resultCode: Int, data: Intent) {
        val device = connectedDevice ?: return
        val code = connectedCode ?: return
        val intent = Intent(this, ScreenShareService::class.java).apply {
            action = ScreenShareService.ACTION_START
            putExtra(ScreenShareService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenShareService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenShareService.EXTRA_HOST, device.host)
            putExtra(ScreenShareService.EXTRA_PORT, device.port)
            putExtra(ScreenShareService.EXTRA_CODE, code)
            putExtra(ScreenShareService.EXTRA_DEVICE_NAME, device.name)
        }
        ContextCompat.startForegroundService(this, intent)
        isSharing = true
        btnShareToggle.text = getString(R.string.action_share_stop)
        // Not actually sharing yet - the PeerConnection still has to connect. Real
        // confirmation comes from onSharingStarted(), fired only once WebRTC reports
        // CONNECTED; showing "공유 중" here would be a lie if the handshake stalls.
        shareStatusText.text = getString(R.string.status_connecting_short)
    }

    override fun onSharingStarted() {
        runOnUiThread {
            isSharing = true
            btnShareToggle.text = getString(R.string.action_share_stop)
            shareStatusText.text = getString(R.string.status_sharing)
        }
    }

    override fun onSharingStopped(errorMessage: String?) {
        runOnUiThread {
            // Whatever ended the session - the user's own stop, the receiver
            // disconnecting, the projection being revoked, a failed handshake - there is
            // nothing left connected, so always land back on the device list rather than
            // leaving a stale "connected" screen behind.
            isSharing = false
            if (!errorMessage.isNullOrEmpty()) {
                Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show()
            }
            if (connectedDevice != null) {
                disconnectAndReturnToList()
            }
        }
    }
}
