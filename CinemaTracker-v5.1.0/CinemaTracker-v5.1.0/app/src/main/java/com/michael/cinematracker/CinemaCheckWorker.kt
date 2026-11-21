package com.michael.cinematracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class CinemaCheckWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = appContext.getSharedPreferences("CinemaTrackerPrefs", Context.MODE_PRIVATE)
        val watchlistRaw = prefs.getString("watchlist_json", "[]") ?: "[]"
        val apiKey = prefs.getString("tmdb_api_key", MainActivity.DEFAULT_TMDB_KEY) ?: MainActivity.DEFAULT_TMDB_KEY

        if (apiKey.isBlank()) return Result.success()

        return try {
            val watchlist = JSONArray(watchlistRaw)
            if (watchlist.length() == 0) return Result.success()

            val updatedList = JSONArray()
            var changed = false

            for (i in 0 until watchlist.length()) {
                val item = watchlist.getJSONObject(i)
                val id = item.optInt("id", 0)
                val title = item.optString("title", "Film")
                val oldDate = item.optString("release_date", "")

                if (id > 0) {
                    val urlStr = "https://api.themoviedb.org/3/movie/$id?api_key=$apiKey&append_to_response=release_dates"
                    val conn = URL(urlStr).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(body)
                        val globalDate = json.optString("release_date", oldDate)
                        val idDate = extractIndonesiaDate(json) ?: globalDate

                        if (oldDate.isBlank() && idDate.isNotBlank()) {
                            sendNotification(
                                id,
                                "Jadwal Rilis Dikonfirmasi!",
                                "$title resmi dijadwalkan tayang mulai $idDate"
                            )
                            item.put("release_date", idDate)
                            changed = true
                        } else if (idDate.isNotBlank() && idDate != oldDate) {
                            sendNotification(
                                id,
                                "Perubahan Jadwal Tayang",
                                "$title diperbarui menjadi $idDate"
                            )
                            item.put("release_date", idDate)
                            changed = true
                        }
                    }
                    conn.disconnect()
                }
                updatedList.put(item)
            }

            if (changed) {
                prefs.edit().putString("watchlist_json", updatedList.toString()).apply()
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun extractIndonesiaDate(json: JSONObject): String? {
        val releaseDates = json.optJSONObject("release_dates") ?: return null
        val results = releaseDates.optJSONArray("results") ?: return null
        for (i in 0 until results.length()) {
            val countryObj = results.getJSONObject(i)
            if (countryObj.optString("iso_3166_1") == "ID") {
                val dates = countryObj.optJSONArray("release_dates") ?: continue
                if (dates.length() > 0) {
                    val raw = dates.getJSONObject(0).optString("release_date", "")
                    if (raw.length >= 10) return raw.substring(0, 10)
                }
            }
        }
        return null
    }

    private fun sendNotification(notifId: Int, title: String, message: String) {
        val channelId = "cinema_tracker_alerts"
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Jadwal & Pre-Sale Bioskop",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(notifId, notif)
    }
}
