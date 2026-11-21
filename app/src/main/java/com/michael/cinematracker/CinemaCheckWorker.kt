package com.michael.cinematracker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
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

        val minHype = prefs.getInt("min_hype_threshold", 85)
        val tbaWatchJson = prefs.getString("tba_watch_list_json", "[]") ?: "[]"
        val customPostersJson = prefs.getString("custom_posters_json", "{}") ?: "{}"

        return try {
            val todayStr = LocalDate.now().toString()
            val notifiedIds = prefs.getStringSet("notified_movie_ids", emptySet())?.toMutableSet()
                ?: mutableSetOf()
            val customPosters = try { JSONObject(customPostersJson) } catch (_: Exception) { JSONObject() }

            var sentCount = 0

            // 1. Check user's TBA Watchlist movies for Indonesian theatrical release confirmation
            val tbaArray = JSONArray(tbaWatchJson)
            for (i in 0 until tbaArray.length()) {
                val tmdbId = tbaArray.optInt(i, -1)
                if (tmdbId <= 0) continue

                val relUrl = URL("https://api.themoviedb.org/3/movie/$tmdbId/release_dates?api_key=$apiKey")
                val conn = (relUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val results = json.optJSONArray("results") ?: continue
                    var idReleaseDate = ""
                    for (j in 0 until results.length()) {
                        val countryObj = results.getJSONObject(j)
                        if (countryObj.optString("iso_3166_1") == "ID") {
                            val rDates = countryObj.optJSONArray("release_dates") ?: continue
                            for (k in 0 until rDates.length()) {
                                val rd = rDates.getJSONObject(k)
                                val type = rd.optInt("type", 0)
                                val dateStr = rd.optString("release_date", "").take(10)
                                if ((type == 2 || type == 3) && dateStr.isNotEmpty()) {
                                    idReleaseDate = dateStr
                                    break
                                }
                            }
                        }
                    }

                    val notifyKey = "tba_confirmed_$tmdbId"
                    if (idReleaseDate.isNotEmpty() && !notifiedIds.contains(notifyKey)) {
                        val detUrl = URL("https://api.themoviedb.org/3/movie/$tmdbId?api_key=$apiKey")
                        val dConn = detUrl.openConnection() as HttpURLConnection
                        if (dConn.responseCode == 200) {
                            val dJson = JSONObject(dConn.inputStream.bufferedReader().use { it.readText() })
                            val title = dJson.optString("title", "Film Watchlist")
                            val posterPath = dJson.optString("poster_path", "")
                            val defaultPoster = if (posterPath.isNotEmpty()) "https://image.tmdb.org/t/p/w500$posterPath" else ""
                            val chosenPoster = customPosters.optString("search_$tmdbId", "")
                                .ifEmpty { customPosters.optString("tmdb_$tmdbId", defaultPoster) }
                            sendMovieNotification(
                                notifyKey.hashCode(),
                                "Watchlist Confirmed ID: $title",
                                "Resmi tayang di bioskop Indonesia mulai $idReleaseDate",
                                chosenPoster
                            )
                            notifiedIds.add(notifyKey)
                            sentCount++
                        }
                    }
                }
            }

            // 2. Scan High-Hype new Indonesian theatrical releases
            val endpoint = "https://api.themoviedb.org/3/discover/movie?api_key=$apiKey" +
                "&region=ID&with_release_type=2|3&with_original_language=en|id|ja|ko" +
                "&release_date.gte=$todayStr&sort_by=popularity.desc&page=1"
            val url = URL(endpoint)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
            }

            if (connection.responseCode == 200) {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val results = json.optJSONArray("results")

                if (results != null) {
                    for (i in 0 until results.length()) {
                        if (sentCount >= 4) break
                        val item = results.getJSONObject(i)
                        val id = item.optInt("id").toString()
                        val title = item.optString("title", "Film Baru")
                        val releaseDate = item.optString("release_date", "")
                        val posterPath = item.optString("poster_path", "")

                        if (releaseDate.isEmpty() || releaseDate < todayStr) continue

                        val popularity = item.optDouble("popularity", 0.0)
                        val hypeScore = Math.min(98, Math.max(75, Math.round(74 + Math.min(24.0, popularity / 4.0)).toInt()))

                        if (hypeScore >= minHype && !notifiedIds.contains(id)) {
                            val defaultPoster = if (posterPath.isNotEmpty()) "https://image.tmdb.org/t/p/w500$posterPath" else ""
                            val chosenPoster = customPosters.optString("tmdb_$id", defaultPoster)
                            sendMovieNotification(
                                id.hashCode(),
                                "Rilis Bioskop ID: $title",
                                "Tayang $releaseDate • Hype $hypeScore% • Cek presale TIX ID / XXI / CGV",
                                chosenPoster
                            )
                            notifiedIds.add(id)
                            sentCount++
                        }
                    }
                }
            }

            prefs.edit().putStringSet("notified_movie_ids", notifiedIds).apply()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun sendMovieNotification(notificationId: Int, title: String, bodyText: String, posterUrl: String) {
        var bitmap: Bitmap? = null
        try {
            if (posterUrl.isNotEmpty()) {
                val url = URL(posterUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.doInput = true
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.connect()
                bitmap = BitmapFactory.decodeStream(connection.inputStream)
            }
        } catch (_: Exception) {}

        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(appContext, MainActivity.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(bodyText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        if (bitmap != null) {
            builder.setLargeIcon(bitmap)
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(bitmap)
                    .bigLargeIcon(null as Bitmap?)
            )
        }

        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, builder.build())
    }
}
