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
import com.v2ray.ang.dto.SubscriptionItem
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import java.net.URI

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
            onProgress?.invoke("Fetching Universal Master Index...")

            // Step A: Fetch universal.json
            val universalContent = try {
                HttpUtil.getUrlContentWithUserAgent(AppConfig.UNIVERSAL_INDEX_URL, null)
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to fetch universal index", e)
                return
            }

            if (universalContent.isEmpty()) {
                Log.e(AppConfig.TAG, "Universal index content is empty")
                return
            }

            // Step B: Parse JSON Array
            val fileList = try {
                JsonUtil.fromJson(universalContent, Array<String>::class.java)
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to parse universal index JSON", e)
                return
            }

            if (fileList == null || fileList.isEmpty()) {
                Log.i(AppConfig.TAG, "No files found in universal index")
                return
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

                val targetUrl = AppConfig.DTECH_BASE_DOMAIN + filename
                Log.i(AppConfig.TAG, "Processing universal file: $filename as $configName ($targetUrl)")
                onProgress?.invoke("Updating $configName")

                // Ensure subscription exists or update it
                // We use importUrlAsSubscription-like logic but ensure we force the URL
                // Actually, AngConfigManager.updateConfigViaSub takes a subscription item.
                // We should check if a sub with this URL exists, if not create it.
                // Or maybe the user wants these to be managed automatically without cluttering the sub list?
                // "add the servers to the app's list" -> The most robust way in this codebase is to treat them as subscriptions.

                // Let's look for a subscription with this URL
                val subscriptions = MmkvManager.decodeSubscriptions()
                var subIdToUse = ""
                var subItemToUse: SubscriptionItem? = null

                for (sub in subscriptions) {
                    if (sub.second.url == targetUrl) {
                        subIdToUse = sub.first
                        subItemToUse = sub.second
                        break
                    }
                }

                if (subIdToUse.isEmpty()) {
                    // Create new subscription
                    val subItem = SubscriptionItem()
                    subItem.remarks = configName
                    subItem.url = targetUrl
                    subItem.autoUpdate = true // implied?
                    subIdToUse = MmkvManager.encodeSubscription("", subItem)
                    subItemToUse = subItem
                } else {
                    // Update remarks if changed (optional, but good for consistency)
                    if (subItemToUse != null && subItemToUse.remarks != configName) {
                        subItemToUse.remarks = configName
                        MmkvManager.encodeSubscription(subIdToUse, subItemToUse)
                    }
                }

                // Step E: Fetch and update
                if (subItemToUse != null) {
                    AngConfigManager.updateConfigViaSub(Pair(subIdToUse, subItemToUse))
                }
            }
            Log.i(AppConfig.TAG, "Universal Master Index Update Completed")

        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Critical error in Universal Update", e)
        }
    }
}
