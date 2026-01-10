package com.v2ray.ang.handler

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.fmt.VlessFmt
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil

object SubscriptionUpdater {

    class UpdateTask(context: Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {

        private val notificationManager = NotificationManagerCompat.from(applicationContext)
        private val notification =
            NotificationCompat.Builder(applicationContext, AppConfig.SUBSCRIPTION_UPDATE_CHANNEL)
                .setWhen(0)
                .setTicker("Update")
                .setContentTitle(context.getString(R.string.title_pref_auto_update_subscription))
                .setSmallIcon(R.drawable.ic_stat_name)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        /**
         * Performs the subscription update work.
         * @return The result of the work.
         */
        @SuppressLint("MissingPermission")
        override suspend fun doWork(): Result {
            Log.i(AppConfig.TAG, "subscription automatic update starting")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notification.setChannelId(AppConfig.SUBSCRIPTION_UPDATE_CHANNEL)
                val channel =
                    NotificationChannel(
                        AppConfig.SUBSCRIPTION_UPDATE_CHANNEL,
                        AppConfig.SUBSCRIPTION_UPDATE_CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_MIN
                    )
                notificationManager.createNotificationChannel(channel)
            }
            notificationManager.notify(3, notification.build())

            updateUniversalSubscription { message ->
                 notification.setContentText(message)
                 notificationManager.notify(3, notification.build())
            }

            notificationManager.cancel(3)
            return Result.success()
        }
    }

    suspend fun updateUniversalSubscription(onProgress: ((String) -> Unit)? = null) {
        try {
            Log.i(AppConfig.TAG, "Universal Master Index Update Starting")
            onProgress?.invoke("Getting universal url...")

            // Step A: Fetch universal.json
            val universalContent = try {
                HttpUtil.getUrlContentWithUserAgent(AppConfig.UNIVERSAL_INDEX_URL, null)
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to fetch universal index", e)
                onProgress?.invoke("Failed to fetch index")
                return
            }

            if (universalContent.isEmpty()) {
                Log.e(AppConfig.TAG, "Universal index content is empty")
                onProgress?.invoke("Index empty")
                return
            }

            // Step B: Parse JSON Array
            val fileList = try {
                JsonUtil.fromJson(universalContent, Array<String>::class.java)
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to parse universal index JSON", e)
                onProgress?.invoke("Index parse error")
                return
            }

            if (fileList == null || fileList.isEmpty()) {
                Log.i(AppConfig.TAG, "No files found in universal index")
                onProgress?.invoke("No files found")
                return
            }

            onProgress?.invoke("Reading Data...")

            // Helper to find existing config by remark
            fun findConfigByRemark(remark: String): String? {
                val serverList = MmkvManager.decodeServerList()
                for (guid in serverList) {
                    val config = MmkvManager.decodeServerConfig(guid)
                    if (config?.remarks == remark) {
                        return guid
                    }
                }
                return null
            }

            // Step C: Loop through files
            for (filename in fileList) {
                if (filename.isEmpty()) continue

                // Naming logic: Strip "dtech" prefix and ".json" suffix
                var configName = filename
                if (configName.endsWith(".json", ignoreCase = true)) {
                    configName = configName.substring(0, configName.length - 5)
                }
                if (configName.startsWith("dtech", ignoreCase = true)) {
                    configName = configName.substring(5)
                }
                // Fallback if empty after stripping
                if (configName.isEmpty()) {
                    configName = filename
                }

                onProgress?.invoke("Getting config $configName...")

                val targetUrl = AppConfig.DTECH_BASE_DOMAIN + filename
                Log.i(AppConfig.TAG, "Processing universal file: $filename as $configName ($targetUrl)")

                // Fetch content (raw string)
                val configContent = try {
                    HttpUtil.getUrlContentWithUserAgent(targetUrl, null)
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "Failed to fetch config content for $filename", e)
                    continue
                }

                if (configContent.isBlank()) {
                    Log.w(AppConfig.TAG, "Empty content for $filename")
                    continue
                }

                // Parse VLESS
                try {
                    val config = VlessFmt.parse(configContent)
                    if (config != null) {
                        config.remarks = configName

                        val existingGuid = findConfigByRemark(configName)
                        if (existingGuid != null) {
                             MmkvManager.encodeServerConfig(existingGuid, config)
                        } else {
                             MmkvManager.encodeServerConfig("", config)
                        }
                    } else {
                        Log.e(AppConfig.TAG, "Failed to parse VLESS for $filename")
                    }
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "Error parsing/saving config for $filename", e)
                }
            }

            onProgress?.invoke("Updated config list")
            // Short delay to let user see the message before hiding?
            // The ViewModel will likely handle the hiding logic or we can send empty string.
            // But user said "disappears when everything is finished".

            Log.i(AppConfig.TAG, "Universal Master Index Update Completed")

        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Critical error in Universal Update", e)
            onProgress?.invoke("Update Error")
        }
    }
}
