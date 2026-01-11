package com.v2ray.ang.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.extension.toast
import com.v2ray.ang.fmt.VlessFmt
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.MessageUtil
import kotlinx.coroutines.*
import java.util.UUID

class SniScannerActivity : BaseActivity() {

    private lateinit var etConfig: EditText
    private lateinit var etHosts: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvResults: TextView
    private lateinit var tvStatus: TextView

    private var isScanning = false
    private var scanJob: Job? = null
    private val tempGuid = "sni_scanner_temp_profile"

    // To verify "Original" logic, we capture the original selected server to restore it later
    private var originalSelectedServer: String? = null

    // Defer for service connection
    private var connectedDeferred: CompletableDeferred<Boolean>? = null
    // Defer for ping result
    private var pingResultDeferred: CompletableDeferred<Long>? = null

    // Broadcast Receiver
    private val mMsgReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> {
                    // Service started
                    // Don't auto-ping, just notify connected
                    connectedDeferred?.complete(true)
                }
                AppConfig.MSG_STATE_START_FAILURE, AppConfig.MSG_STATE_STOP -> {
                     connectedDeferred?.complete(false)
                }
                AppConfig.MSG_MEASURE_DELAY_SUCCESS -> {
                    val content = intent.getSerializableExtra("content") as? String
                    onDelaySuccess(content)
                }
            }
        }
    }

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sni_scanner)

        etConfig = findViewById(R.id.et_config)
        etHosts = findViewById(R.id.et_hosts)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        tvResults = findViewById(R.id.tv_results)
        tvStatus = findViewById(R.id.tv_status)

        btnStart.setOnClickListener {
            prepareStartScan()
        }

        btnStop.setOnClickListener {
            stopScan()
        }
    }

    private fun prepareStartScan() {
        val intent = VpnService.prepare(this)
        if (intent == null) {
            startScan()
        } else {
            requestVpnPermission.launch(intent)
        }
    }

    private fun startScan() {
        if (isScanning) return

        val configStr = etConfig.text.toString().trim()
        val hostsStr = etHosts.text.toString().trim()

        if (TextUtils.isEmpty(configStr)) {
            toast("Please enter a Base VLESS Config")
            return
        }
        if (TextUtils.isEmpty(hostsStr)) {
            toast("Please enter at least one Host")
            return
        }

        // Parse Base Config
        val baseProfile: ProfileItem? = if (configStr.startsWith(AppConfig.VLESS)) {
             VlessFmt.parse(configStr)
        } else {
            null
        }

        if (baseProfile == null) {
            toast("Invalid VLESS Config")
            return
        }

        val hosts = hostsStr.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (hosts.isEmpty()) {
            toast("No valid hosts found")
            return
        }

        // Save original selection
        originalSelectedServer = MmkvManager.getSelectServer()

        isScanning = true
        updateUIState(true)
        tvResults.text = "" // Clear previous results
        tvStatus.text = "Status: Preparing..."

        // Register Receiver
        val filter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mMsgReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(mMsgReceiver, filter)
        }

        scanJob = lifecycleScope.launch(Dispatchers.Main) {
            appendLog("Starting Scan with ${hosts.size} hosts...")

            val originalAddress = baseProfile.server ?: ""
            if (baseProfile.sni.isNullOrEmpty()) baseProfile.sni = originalAddress
            if (baseProfile.host.isNullOrEmpty()) baseProfile.host = originalAddress

            for (host in hosts) {
                if (!isScanning) break

                appendLog("Testing: $host")
                updateStatus(host, "Preparing...")
                delay(1000) // Visual Delay

                // Prepare Profile
                val currentProfile = VlessFmt.parse(configStr)
                if (currentProfile == null) {
                    updateStatus(host, "Error: Invalid Profile")
                    continue
                }
                if (currentProfile.sni.isNullOrEmpty()) currentProfile.sni = originalAddress
                if (currentProfile.host.isNullOrEmpty()) currentProfile.host = originalAddress

                currentProfile.server = host
                currentProfile.remarks = "SCANNING_TEMP"

                MmkvManager.encodeServerConfig(tempGuid, currentProfile)
                MmkvManager.setSelectServer(tempGuid)

                // Ensure stopped
                if (V2RayServiceManager.isRunning()) {
                     updateStatus(host, "Stopping previous...")
                     V2RayServiceManager.stopVService(this@SniScannerActivity)
                     withContext(Dispatchers.IO) {
                         var retries = 0
                         while (V2RayServiceManager.isRunning() && retries < 20) {
                             delay(100)
                             retries++
                         }
                     }
                }

                // Start
                updateStatus(host, "Connecting...")
                connectedDeferred = CompletableDeferred()
                V2RayServiceManager.startVService(this@SniScannerActivity)

                // Wait for Connection
                val isConnected = withTimeoutOrNull(10000) {
                    connectedDeferred?.await()
                }

                if (isConnected == true) {
                    updateStatus(host, "Connected. Stabilizing...")
                    delay(2000) // Wait for key icon / stabilization

                    updateStatus(host, "Pinging...")
                    pingResultDeferred = CompletableDeferred()
                    MessageUtil.sendMsg2Service(this@SniScannerActivity, AppConfig.MSG_MEASURE_DELAY, "")

                    val ping = withTimeoutOrNull(5000) {
                        pingResultDeferred?.await()
                    }

                    if (ping != null && ping > 0) {
                        updateStatus(host, "Success: ${ping}ms")
                        tvResults.append("$host (${ping}ms)\n")
                    } else {
                         updateStatus(host, "Ping Failed/Timeout")
                    }
                } else {
                    updateStatus(host, "Connection Failed")
                }

                // Wait a bit to let user see result
                delay(1000)

                // Stop
                updateStatus(host, "Stopping...")
                V2RayServiceManager.stopVService(this@SniScannerActivity)
                delay(1000) // Cooldown
            }

            isScanning = false
            updateUIState(false)
            tvStatus.text = "Status: Scan Finished."
            appendLog("Scan Finished.")

            // Restore original server
            originalSelectedServer?.let { MmkvManager.setSelectServer(it) }
            MmkvManager.removeServer(tempGuid)
            try {
                 unregisterReceiver(mMsgReceiver)
            } catch (e: Exception) {}
        }
    }

    private fun updateStatus(host: String, status: String) {
        tvStatus.text = "Testing: $host\nStatus: $status"
    }

    // Called from Receiver
    private fun onDelaySuccess(content: String?) {
        if (pingResultDeferred?.isActive == true) {
            if (content != null && content.contains("ms")) {
                 try {
                     val ms = content.split(" ")[0].toLong()
                     pingResultDeferred?.complete(ms)
                 } catch (e: Exception) {
                     pingResultDeferred?.complete(-1)
                 }
            } else {
                 pingResultDeferred?.complete(-1)
            }
        }
    }

    private fun stopScan() {
        isScanning = false
        scanJob?.cancel()
        updateUIState(false)
        tvStatus.text = "Status: Stopped by user"
        try {
            unregisterReceiver(mMsgReceiver)
        } catch (e: IllegalArgumentException) {
            // receiver not registered
        }

        V2RayServiceManager.stopVService(this)
        originalSelectedServer?.let { MmkvManager.setSelectServer(it) }
    }

    private fun updateUIState(scanning: Boolean) {
        btnStart.isEnabled = !scanning
        btnStop.isEnabled = scanning
        etConfig.isEnabled = !scanning
        etHosts.isEnabled = !scanning
    }

    private fun appendLog(msg: String) {
        Log.d("SniScanner", msg)
    }
}
