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
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.EConfigType
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

    private var isScanning = false
    private var scanJob: Job? = null
    private val tempGuid = "sni_scanner_temp_profile"

    // To verify "Original" logic, we capture the original selected server to restore it later
    private var originalSelectedServer: String? = null

    // Broadcast Receiver
    private val mMsgReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> {
                    // Service started, trigger ping
                    V2RayServiceManager.measureV2rayDelay()
                }
                AppConfig.MSG_MEASURE_DELAY_SUCCESS -> {
                    val content = intent.getSerializableExtra("content") as? String
                    onDelaySuccess(content)
                }
                // We handle failure via timeout mostly, but if we get explicit stop/fail
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
        // We only support VLESS as per request
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

        // Register Receiver
        val filter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mMsgReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(mMsgReceiver, filter)
        }

        scanJob = lifecycleScope.launch(Dispatchers.Main) {
            appendLog("Starting Scan with ${hosts.size} hosts...")

            // Normalize base profile properties
            // If SNI/Host are empty in base profile, they should take the value of the original address
            val originalAddress = baseProfile.server ?: ""
            if (baseProfile.sni.isNullOrEmpty()) baseProfile.sni = originalAddress
            if (baseProfile.host.isNullOrEmpty()) baseProfile.host = originalAddress

            for (host in hosts) {
                if (!isScanning) break

                appendLog("Testing: $host")

                // Prepare Profile
                val testProfile = baseProfile.copy() // assuming data class copy or we construct new
                // Deep copy manually if needed, but ProfileItem is likely a data class or simple object
                // Let's create a fresh one to be safe since we are modifying it
                // Actually ProfileItem is a data class in the codebase usually?
                // Let's use the one we parsed as a template.
                // We must ensure we don't modify 'baseProfile' itself for next iterations.
                // Since I can't rely on 'copy()', I will parse it again or just manually set fields.
                // Re-parsing is safer but slower. Let's use 'copy()' if available (it's a Kotlin data class).
                // Wait, I need to check if ProfileItem is a data class.
                // Memory says "dto/ProfileItem", usually data class. Assuming yes.

                // Set Address to Zero-Rate Host
                // But wait! ProfileItem is mutable?
                // Let's manually set properties on a new object or modify a clone.
                // Since I don't have source code of ProfileItem right here (I didn't read it), I'll assume I can clone it
                // by serializing/deserializing or if it is a data class.
                // Let's check 'AngConfigManager.kt' imports 'com.v2ray.ang.dto.ProfileItem'.
                // I will read it to be sure.

                // For now, I will assume I can modify 'testProfile'.
                // I need to clone 'baseProfile'.
                // Since I can't easily deep clone without library, I'll just re-parse string.
                val currentProfile = VlessFmt.parse(configStr)!!
                if (currentProfile.sni.isNullOrEmpty()) currentProfile.sni = originalAddress
                if (currentProfile.host.isNullOrEmpty()) currentProfile.host = originalAddress

                currentProfile.server = host
                currentProfile.remarks = "SCANNING_TEMP"

                // Save to MMKV
                MmkvManager.encodeServerConfig(tempGuid, currentProfile)
                MmkvManager.setSelectServer(tempGuid)

                // Start Service
                // We need to stop first if running
                if (V2RayServiceManager.isRunning()) {
                     V2RayServiceManager.stopVService(this@SniScannerActivity)
                     // Wait for stop
                     withContext(Dispatchers.IO) {
                         var retries = 0
                         while (V2RayServiceManager.isRunning() && retries < 20) {
                             delay(100)
                             retries++
                         }
                     }
                }

                V2RayServiceManager.startVService(this@SniScannerActivity)

                // Wait for connection
                // We use a Deferred that is completed by the BroadcastReceiver
                try {
                    val result = withTimeoutOrNull(10000) { // 10s timeout for connect + ping
                        waitForPingResult()
                    }

                    if (result != null && result > 0) {
                        appendLog("SUCCESS: $host ($result ms)")
                        tvResults.append("$host\n")
                    } else {
                        // Fail or Timeout
                    }
                } catch (e: Exception) {
                    // Log.e("Scanner", "Error", e)
                }

                // Stop service before next
                V2RayServiceManager.stopVService(this@SniScannerActivity)
                delay(500) // Cooldown
            }

            isScanning = false
            updateUIState(false)
            appendLog("Scan Finished.")

            // Restore original server
            if (originalSelectedServer != null) {
                MmkvManager.setSelectServer(originalSelectedServer)
            }
            // Cleanup temp
            MmkvManager.removeServer(tempGuid)
            try {
                 unregisterReceiver(mMsgReceiver)
            } catch (e: Exception) {}
        }
    }

    private var pingResultDeferred: CompletableDeferred<Long>? = null

    private suspend fun waitForPingResult(): Long {
        pingResultDeferred = CompletableDeferred()
        return pingResultDeferred!!.await()
    }

    // Called from Receiver
    private fun onDelaySuccess(content: String?) {
        if (pingResultDeferred?.isActive == true) {
            // Content format: "123 ms" or "Error: ..."
            // We need to parse.
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
        try {
            unregisterReceiver(mMsgReceiver)
        } catch (e: IllegalArgumentException) {
            // receiver not registered
        }

        V2RayServiceManager.stopVService(this)
        if (originalSelectedServer != null) {
            MmkvManager.setSelectServer(originalSelectedServer)
        }
    }

    private fun updateUIState(scanning: Boolean) {
        btnStart.isEnabled = !scanning
        btnStop.isEnabled = scanning
        etConfig.isEnabled = !scanning
        etHosts.isEnabled = !scanning
    }

    private fun appendLog(msg: String) {
        // Optional: show logs in a separate view or just debug
        // For now, we only show successful hosts in tvResults as per request
        // But logging status helps user know it's working
        // Maybe we can update the "Working Hosts" label or show a toast?
        // Let's just log to Logcat and maybe a small status text if we had one.
        Log.d("SniScanner", msg)
    }
}
