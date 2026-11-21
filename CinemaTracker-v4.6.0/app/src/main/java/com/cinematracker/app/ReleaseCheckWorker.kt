package com.cinematracker.app

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class ReleaseCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val PREFS_NAME = "cinema_tracker_prefs"
        const val KEY_WATCHLIST = "watchlist_json"
        const val KEY_API_KEY = "tmdb_api_key"
        const val CHANNEL_ID = "cinema_tracker_releases"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString(KEY_API_KEY, "")?.trim().orEmpty()
        val watchlistRaw = prefs.getString(KEY_WATCHLIST, "[]").orEmpty()

        if (apiKey.isEmpty() || watchlistRaw == "[]") {
            return@withContext Result.success()
        }

        try {
            val watchlist = JSONArray(watchlistRaw)
            val today = LocalDate.now()

            for (i in 0 until watchlist.length()) {
                val item = watchlist.getJSONObject(i)
                val movieId = item.optInt("id", 0)
                val title = item.optString("title", "Film")
                var releaseDateStr = item.optString("release_date", "")

                if (movieId == 0) continue

                // Refresh Indonesian theatrical release date from TMDB
                val idRelease = fetchIndonesiaReleaseDate(movieId, apiKey)
                if (!idRelease.isNullOrBlank()) {
                    releaseDateStr = idRelease
                }

                if (releaseDateStr.length >= 10) {
                    val relDate = LocalDate.parse(releaseDateStr.substring(0, 10))
                    val daysUntil = ChronoUnit.DAYS.between(today, relDate)

                    when (daysUntil) {
                        7L -> notifyOnce(
                            prefs,
                            movieId,
                            "h7_$releaseDateStr",
                            "Radar Presale H-7: $title",
                            "Tayang 7 hari lagi ($releaseDateStr). Cek presale XXI / CGV / Cinepolis."
                        )
                        1L -> notifyOnce(
                            prefs,
                            movieId,
                            "h1_$releaseDateStr",
                            "Besok Tayang: $title",
                            "$title mulai tayang besok di bioskop Indonesia."
                        )
                        0L -> notifyOnce(
                            prefs,
                            movieId,
                            "h0_$releaseDateStr",
                            "Tayang Hari Ini: $title",
                            "$title resmi tayang hari ini di bioskop."
                        )
                    }
                }
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun fetchIndonesiaReleaseDate(movieId: Int, apiKey: String): String? {
        val endpoint = "https://api.themoviedb.org/3/movie/$movieId/release_dates?api_key=$apiKey"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
        }
        return try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val results = JSONObject(body).optJSONArray("results") ?: return null
            for (i in 0 until results.length()) {
                val countryObj = results.getJSONObject(i)
                if (countryObj.optString("iso_3166_1") == "ID") {
                    val dates = countryObj.optJSONArray("release_dates") ?: continue
                    // Prefer theatrical release type 3 (or 2 limited)
                    for (j in 0 until dates.length()) {
                        val d = dates.getJSONObject(j)
                        val type = d.optInt("type", 0)
                        if (type == 3 || type == 2) {
                            return d.optString("release_date").take(10)
                        }
                    }
                    if (dates.length() > 0) {
                        return dates.getJSONObject(0).optString("release_date").take(10)
                    }
                }
            }
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun notifyOnce(
        prefs: android.content.SharedPreferences,
        movieId: Int,
        milestoneKey: String,
        title: String,
        message: String
    ) {
        val dedupKey = "notified_${movieId}_$milestoneKey"
        if (prefs.getBoolean(dedupKey, false)) return

        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            movieId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify((movieId.toString() + milestoneKey).hashCode(), notification)
        prefs.edit().putBoolean(dedupKey, true).apply()
    }
}
