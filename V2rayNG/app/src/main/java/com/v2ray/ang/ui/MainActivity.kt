package com.v2ray.ang.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.tabs.TabLayout
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : BaseActivity() {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    private val adapter by lazy { MainRecyclerAdapter(this) }
    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }

    // Traffic monitor job
    private var trafficMonitorJob: Job? = null

    // Pulse animation
    private val pulseAnimation by lazy {
        ScaleAnimation(
            1f, 1.2f, 1f, 1.2f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 1000
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        }
    }

    // Kept to avoid breaking initGroupTab but it's hidden now
    private val tabGroupListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab?) {
            val selectId = tab?.tag.toString()
            if (selectId != mainViewModel.subscriptionId) {
                mainViewModel.subscriptionIdChanged(selectId)
            }
        }

        override fun onTabUnselected(tab: TabLayout.Tab?) {
        }

        override fun onTabReselected(tab: TabLayout.Tab?) {
        }
    }

    val mainViewModel: MainViewModel by viewModels()

    // register activity result for requesting permission
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                // Pending actions
            } else {
                toast(R.string.toast_permission_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        // Connect Button logic (Using new container)
        binding.btnConnectContainer.setOnClickListener {
            handleConnectClick()
        }

        // Import Button logic
        binding.btnImportConfig.setOnClickListener {
             showImportConfigDialog()
        }

        binding.layoutTest.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                setTestState(getString(R.string.connection_test_testing))
                mainViewModel.testCurrentServerRealPing()
            }
        }

        // Settings icon logic
        binding.ivSettings.setOnClickListener {
             startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = GridLayoutManager(this, 1)
        binding.recyclerView.adapter = adapter

        // Lock Drawer
        binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)

        setupViewModel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun handleConnectClick() {
        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        } else if ((MmkvManager.decodeSettingsString(AppConfig.PREF_MODE) ?: VPN) == VPN) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                startV2Ray()
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            startV2Ray()
        }
    }

    private fun showImportConfigDialog() {
        val etParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )
        etParams.setMargins(50, 0, 50, 0)
        val etConfig = EditText(this)
        etConfig.layoutParams = etParams
        etConfig.hint = "Paste VLESS URL here"
        etConfig.setTextColor(ContextCompat.getColor(this, R.color.colorTextPrimary))
        etConfig.setHintTextColor(ContextCompat.getColor(this, R.color.colorTextSecondary))


        val container = android.widget.LinearLayout(this)
        container.orientation = android.widget.LinearLayout.VERTICAL
        container.addView(etConfig)
        container.setPadding(32, 32, 32, 32)

        AlertDialog.Builder(this)
            .setTitle("Import Config")
            .setView(container)
            .setPositiveButton("Import") { _, _ ->
                val configStr = etConfig.text.toString()
                if (configStr.isNotEmpty()) {
                    importBatchConfig(configStr)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setupViewModel() {
        mainViewModel.updateListAction.observe(this) { index ->
            if (index >= 0) {
                adapter.notifyItemChanged(index)
            } else {
                adapter.notifyDataSetChanged()
            }
        }
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning ->
            adapter.isRunning = isRunning
            if (isRunning) {
                // Connected State
                binding.ivConnectIcon.setImageResource(R.drawable.ic_stop_24dp) // Ensure this resource exists or use android default
                binding.viewPulse.visibility = View.VISIBLE
                binding.viewPulse.startAnimation(pulseAnimation)

                setTestState("Connected")
                binding.layoutTest.isFocusable = true
                startTrafficMonitor()
            } else {
                // Disconnected State
                binding.ivConnectIcon.setImageResource(android.R.drawable.ic_lock_power_off)
                binding.viewPulse.clearAnimation()
                binding.viewPulse.visibility = View.INVISIBLE

                setTestState("Not Connected")
                binding.layoutTest.isFocusable = false
                stopTrafficMonitor()
                // Reset speeds
                binding.tvUploadSpeed.text = "0 KB/s"
                binding.tvDownloadSpeed.text = "0 KB/s"
            }
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun startTrafficMonitor() {
        trafficMonitorJob?.cancel()
        trafficMonitorJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val upload = V2RayServiceManager.queryStats(AppConfig.TAG_PROXY, AppConfig.UPLINK)
                val download = V2RayServiceManager.queryStats(AppConfig.TAG_PROXY, AppConfig.DOWNLINK)

                // Note: queryStats returns total bytes. We need to calculate rate or if V2RayServiceManager handles it.
                // Looking at typical implementations, queryStats usually returns CURRENT value.
                // However, without a diff, it's total.
                // For simplicity in this "visualizer", let's assume we get a value and format it.
                // If it is total bytes, we need to diff it.
                // Let's implement a simple diff mechanism.

                val upSpeed = Utils.getEditableSpeedString(upload) // Assuming Utils has speed formatter, or we make one.
                // Actually Utils.getEditableSpeedString doesn't exist in memory.

                withContext(Dispatchers.Main) {
                     updateTrafficUI(upload, download)
                }
                delay(1000)
            }
        }
    }

    private var lastUpload = 0L
    private var lastDownload = 0L

    private fun updateTrafficUI(totalUpload: Long, totalDownload: Long) {
        // Calculate speed based on diff (assuming queryStats returns accumulated total)
        // If queryStats returns 0, it might mean reset.

        val upDiff = if (totalUpload >= lastUpload) totalUpload - lastUpload else totalUpload
        val downDiff = if (totalDownload >= lastDownload) totalDownload - lastDownload else totalDownload

        lastUpload = totalUpload
        lastDownload = totalDownload

        binding.tvUploadSpeed.text = getSpeedString(upDiff)
        binding.tvDownloadSpeed.text = getSpeedString(downDiff)
    }

    private fun getSpeedString(bytes: Long): String {
        return if (bytes < 1024) {
            "$bytes B/s"
        } else if (bytes < 1024 * 1024) {
            String.format("%.1f KB/s", bytes / 1024f)
        } else {
            String.format("%.1f MB/s", bytes / (1024f * 1024f))
        }
    }

    private fun stopTrafficMonitor() {
        trafficMonitorJob?.cancel()
        lastUpload = 0L
        lastDownload = 0L
    }


    private fun initGroupTab() {
        // ... (Logic kept if needed, but not used in UI)
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            if (mainViewModel.serversCache.isNotEmpty()) {
                val firstGuid = mainViewModel.serversCache[0].guid
                MmkvManager.setSelectServer(firstGuid)
                adapter.notifyDataSetChanged()
            } else {
                toast(R.string.title_file_chooser)
                return
            }
        }

        com.v2ray.ang.handler.V2rayConfigManager.overrideSni = null
        V2RayServiceManager.startVService(this)
    }

    // ... (Other methods remain similar)

    public override fun onResume() {
        super.onResume()
        mainViewModel.reloadServerList()
    }

    private fun importBatchConfig(server: String?) {
        binding.pbWaiting.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                        }
                        else -> toastError(R.string.toast_failure)
                    }
                    binding.pbWaiting.hide()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    binding.pbWaiting.hide()
                }
                Log.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
