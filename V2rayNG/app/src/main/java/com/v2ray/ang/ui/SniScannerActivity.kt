package com.v2ray.ang.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivitySniScannerBinding
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.fmt.VlessFmt
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.MessageUtil
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class SniScannerActivity : BaseActivity() {

    private val binding by lazy { ActivitySniScannerBinding.inflate(layoutInflater) }
    private var isScanning = AtomicBoolean(false)
    private var scanJob: Job? = null
    private val scannerGuid = "SCANNER_TEMP_PROFILE"

    // Receiver for Service Messages
    private val mMsgReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_START_SUCCESS -> {
                    onVpnStarted()
                }
                AppConfig.MSG_STATE_START_FAILURE -> {
                    onVpnStartFailed()
                }
                AppConfig.MSG_MEASURE_DELAY_SUCCESS -> {
                    val content = intent.getStringExtra("content")
                    onPingResult(content)
                }
            }
        }
    }

    // State Synchronization
    private val vpnStartSignal = CompletableDeferred<Boolean>()
    private val pingResultSignal = CompletableDeferred<String?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        title = "Zero-Rate Scanner"

        // Register Receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mMsgReceiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mMsgReceiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY))
        }

        binding.btnStartScan.setOnClickListener {
            if (isScanning.get()) {
                stopScan()
            } else {
                startScan()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(mMsgReceiver)
        stopScan()
    }

    private fun startScan() {
        val baseConfigStr = binding.etBaseConfig.text.toString().trim()
        val hostsRaw = binding.etHostList.text.toString().trim()

        if (baseConfigStr.isEmpty()) {
            toast("Please enter a base config")
            return
        }
        if (hostsRaw.isEmpty()) {
            toast("Please enter at least one host to test")
            return
        }

        // Validate Base Config
        val baseConfig = VlessFmt.parse(baseConfigStr)
        if (baseConfig == null) {
            toast("Invalid VLESS config")
            return
        }

        val hosts = hostsRaw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (hosts.isEmpty()) {
            toast("No valid hosts found")
            return
        }

        isScanning.set(true)
        binding.btnStartScan.text = "STOP SCAN"
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.max = hosts.size
        binding.progressBar.progress = 0
        binding.etResults.setText("")
        binding.etBaseConfig.isEnabled = false
        binding.etHostList.isEnabled = false

        scanJob = lifecycleScope.launch(Dispatchers.IO) {
            var successCount = 0

            // Stop any existing VPN first
            if (V2RayServiceManager.isRunning()) {
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "Stopping current VPN..."
                }
                V2RayServiceManager.stopVService(this@SniScannerActivity)
                delay(2000) // Wait for stop
            }

            for ((index, host) in hosts.withIndex()) {
                if (!isScanning.get()) break

                withContext(Dispatchers.Main) {
                    binding.progressBar.progress = index + 1
                    binding.tvStatus.text = "Testing ($index/${hosts.size}): $host"
                }

                val curConfig = VlessFmt.parse(baseConfigStr)!!
                curConfig.server = host // Set Address to zero-rate host
                curConfig.remarks = "SCAN_TEST_$host"

                // Save to Temp Profile
                MmkvManager.encodeServerConfig(scannerGuid, curConfig)

                // 2. Start VPN
                resetVpnSignal()
                withContext(Dispatchers.Main) {
                    V2RayServiceManager.startVService(this@SniScannerActivity, scannerGuid)
                }

                // Wait for start
                val started = try {
                    withTimeout(15000) {
                        waitForVpnStart()
                    }
                } catch (e: TimeoutCancellationException) {
                    false
                }

                if (started) {
                    // 3. Ping Test
                    resetPingSignal()
                    MessageUtil.sendMsg2Service(this@SniScannerActivity, AppConfig.MSG_MEASURE_DELAY, "")

                    val result = try {
                        withTimeout(10000) {
                            waitForPingResult()
                        }
                    } catch (e: TimeoutCancellationException) {
                        null
                    }

                    if (result != null && result.contains("ms") && !result.contains("-1")) {
                        // Success!
                        successCount++
                        withContext(Dispatchers.Main) {
                            binding.etResults.append(host + " -> " + result + "\n")
                        }
                    }
                }

                // 4. Stop VPN
                withContext(Dispatchers.Main) {
                    V2RayServiceManager.stopVService(this@SniScannerActivity)
                }
                delay(1500) // Cool down
            }

            withContext(Dispatchers.Main) {
                stopScan()
                toast("Scan Finished. Found $successCount working hosts.")
            }
        }
    }

    private fun stopScan() {
        isScanning.set(false)
        scanJob?.cancel()
        binding.btnStartScan.text = "START SCAN"
        binding.progressBar.visibility = View.INVISIBLE
        binding.tvStatus.text = "Ready"
        binding.etBaseConfig.isEnabled = true
        binding.etHostList.isEnabled = true

        // Clean up temp profile?
        MmkvManager.removeServer(scannerGuid)
    }

    // --- State Logic ---

    private var vpnStartDeferred = CompletableDeferred<Boolean>()
    private var pingResultDeferred = CompletableDeferred<String?>()

    private fun resetVpnSignal() {
        vpnStartDeferred = CompletableDeferred()
    }

    private suspend fun waitForVpnStart(): Boolean {
        return vpnStartDeferred.await()
    }

    private fun onVpnStarted() {
        if (vpnStartDeferred.isActive) vpnStartDeferred.complete(true)
    }

    private fun onVpnStartFailed() {
         if (vpnStartDeferred.isActive) vpnStartDeferred.complete(false)
    }

    private fun resetPingSignal() {
        pingResultDeferred = CompletableDeferred()
    }

    private suspend fun waitForPingResult(): String? {
        return pingResultDeferred.await()
    }

    private fun onPingResult(content: String?) {
        if (pingResultDeferred.isActive) pingResultDeferred.complete(content)
    }
}
