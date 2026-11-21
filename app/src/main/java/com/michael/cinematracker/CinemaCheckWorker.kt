package com.michael.cinematracker

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

class CinemaCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val PREFS_NAME = "cinema_tracker_v50_prefs"
        const val KEY_WATCHLIST = "watchlist_json"
        const val KEY_TBA = "tba_json"
        const val KEY_API_KEY = "tmdb_api_key"
        const val CHANNEL_ID = "cinema_tracker_radar_alerts"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString(KEY_API_KEY, "")?.trim().orEmpty()
        val watchlistRaw = prefs.getString(KEY_WATCHLIST, "[]").orEmpty()
        val tbaRaw = prefs.getString(KEY_TBA, "[]").orEmpty()

        if (apiKey.isEmpty()) return@withContext Result.success()

        try {
            val today = LocalDate.now()

            val tbaList = JSONArray(tbaRaw)
            for (i in 0 until tbaList.length()) {
                val item = tbaList.getJSONObject(i)
                val movieId = item.optInt("id", 0)
                val title = item.optString("title", "Film TBA")
                if (movieId == 0) continue

                val idDate = fetchIndonesiaReleaseDate(movieId, apiKey)
                if (!idDate.isNullOrBlank()) {
                    notifyOnce(
                        prefs,
                        movieId,
                        "tba_indo_$idDate",
                        "TBA Masuk Jadwal Indo: $title",
                        "Jadwal tayang bioskop Indonesia terkonfirmasi: $idDate."
                    )
                }
            }

            val watchlist = JSONArray(watchlistRaw)
            for (i in 0 until watchlist.length()) {
                val item = watchlist.getJSONObject(i)
                val movieId = item.optInt("id", 0)
                val title = item.optString("title", "Film")
                val notifyPresale = item.optBoolean("notify_presale", true)
                val notifyH1 = item.optBoolean("notify_h1", true)
                val notifyH0 = item.optBoolean("notify_h0", true)

                var releaseDate = item.optString("release_date", "")
                val refreshed = fetchIndonesiaReleaseDate(movieId, apiKey)
                if (!refreshed.isNullOrBlank()) {
                    releaseDate = refreshed
                }

                if (releaseDate.length >= 10) {
                    val target = LocalDate.parse(releaseDate.substring(0, 10))
                    val days = ChronoUnit.DAYS.between(today, target)

                    if (days == 7L && notifyPresale) {
                        notifyOnce(
                            prefs,
                            movieId,
                            "presale_$releaseDate",
                            "Open Pre-Sale Radar: $title",
                            "Tayang $releaseDate di bioskop ID. Pantau presale tiket XXI, CGV & Cinépolis."
                        )
                    }
                    if (days == 1L && notifyH1) {
                        notifyOnce(
                            prefs,
                            movieId,
                            "h1_$releaseDate",
                            "Besok Tayang (H-1): $title",
                            "$title mulai tayang besok di bioskop Indonesia."
                        )
                    }
                    if (days == 0L && notifyH0) {
                        notifyOnce(
                            prefs,
                            movieId,
                            "h0_$releaseDate",
                            "Hari Perdana (Hari-H): $title",
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
        val urlStr = "https://api.themoviedb.org/3/movie/$movieId/release_dates?api_key=$apiKey"
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
        }
        return try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val results = JSONObject(body).optJSONArray("results") ?: return null
            for (i in 0 until results.length()) {
                val country = results.getJSONObject(i)
                if (country.optString("iso_3166_1") == "ID") {
                    val dates = country.optJSONArray("release_dates") ?: continue
                    for (j in 0 until dates.length()) {
                        val d = dates.getJSONObject(j)
                        val type = d.optInt("type", 0)
                        if (type == 3 || type == 2) {
                            val date = d.optString("release_date").take(10)
                            if (date.isNotBlank()) return date
                        }
                    }
                    if (dates.length() > 0) {
                        val date = dates.getJSONObject(0).optString("release_date").take(10)
                        if (date.isNotBlank()) return date
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
        tag: String,
        title: String,
        message: String
    ) {
        val key = "alert_${movieId}_$tag"
        if (prefs.getBoolean(key, false)) return

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            applicationContext,
            movieId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        manager.notify((movieId.toString() + tag).hashCode(), notif)
        prefs.edit().putBoolean(key, true).apply()
    }
}
