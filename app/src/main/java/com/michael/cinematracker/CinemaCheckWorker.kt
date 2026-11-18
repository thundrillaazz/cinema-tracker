package com.michael.cinematracker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

class CinemaCheckWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val prefs = appContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString("tmdb_api_key", "")?.trim() ?: ""

        if (apiKey.isEmpty()) {
            return Result.success()
        }

        return try {
            val todayStr = LocalDate.now().toString()
            val endpoint = "https://api.themoviedb.org/3/discover/movie?api_key=$apiKey" +
                "&region=ID&with_release_type=2|3&with_original_language=en|id|ja|ko" +
                "&release_date.gte=$todayStr&sort_by=popularity.desc&page=1"
            val url = URL(endpoint)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
            }

            if (connection.responseCode != 200) {
                return Result.retry()
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val results = json.optJSONArray("results") ?: return Result.success()

            val notifiedIds = prefs.getStringSet("notified_movie_ids", emptySet())?.toMutableSet()
                ?: mutableSetOf()

            var sentCount = 0

            for (i in 0 until results.length()) {
                if (sentCount >= 3) break
                val item = results.getJSONObject(i)
                val id = item.optInt("id").toString()
                val title = item.optString("title", "Film Baru")
                val releaseDate = item.optString("release_date", "")

                if (releaseDate.isEmpty() || releaseDate < todayStr) continue

                val popularity = item.optDouble("popularity", 0.0)
                val voteAverage = item.optDouble("vote_average", 0.0)

                val isHighHype = popularity >= 25.0 || voteAverage >= 7.0
                if (isHighHype && !notifiedIds.contains(id)) {
                    sendMovieNotification(id.hashCode(), title, releaseDate)
                    notifiedIds.add(id)
                    sentCount++
                }
            }

            prefs.edit().putStringSet("notified_movie_ids", notifiedIds).apply()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun sendMovieNotification(notificationId: Int, title: String, releaseDate: String) {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, MainActivity.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("🎟️ Jadwal Bioskop Indo: $title")
            .setContentText("Rilis ID: $releaseDate • Cek presale tiket di TIX ID / M-TIX / CGV!")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }
}
