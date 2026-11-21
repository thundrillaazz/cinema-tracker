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
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

class CinemaCheckWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val prefs = appContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString("tmdb_api_key", "")?.trim() ?: ""
        val minHype = prefs.getInt("min_hype_threshold", 85)
        val tbaWatchJson = prefs.getString("tba_watch_list_json", "[]") ?: "[]"
        val watchlistFullJson = prefs.getString("watchlist_full_json", "{}") ?: "{}"
        val customPostersJson = prefs.getString("custom_posters_json", "{}") ?: "{}"

        return try {
            val today = LocalDate.now()
            val todayStr = today.toString()
            val shortDateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
            val notifiedIds = prefs.getStringSet("notified_movie_ids", emptySet())?.toMutableSet()
                ?: mutableSetOf()
            val customPosters = try { JSONObject(customPostersJson) } catch (_: Exception) { JSONObject() }

            var sentCount = 0

            // 1. Watchlist Reminders (Day-0, H-1, Pre-Sale Window)
            val watchlistObj = try { JSONObject(watchlistFullJson) } catch (_: Exception) { JSONObject() }
            val keysIter = watchlistObj.keys()
            while (keysIter.hasNext()) {
                val wId = keysIter.next()
                val entry = watchlistObj.optJSONObject(wId) ?: continue
                if (entry.optBoolean("isTba", false)) continue

                val dayAlert = entry.optBoolean("dayAlert", true)
                val presaleAlert = entry.optBoolean("presaleAlert", true)
                val movieData = entry.optJSONObject("movieData") ?: continue
                val title = movieData.optString("title", "")
                if (title.isEmpty()) continue
                val releaseDateStr = movieData.optString("releaseDate", "")
                if (releaseDateStr.length < 10 || releaseDateStr == "TBA") continue

                val relDate = try { LocalDate.parse(releaseDateStr.take(10)) } catch (_: Exception) { null } ?: continue
                val daysUntil = ChronoUnit.DAYS.between(today, relDate)
                val defaultPoster = movieData.optString("poster", "")
                val chosenPoster = customPosters.optString(wId, defaultPoster)
                val prettyDate = relDate.format(shortDateFmt)

                if (dayAlert && daysUntil == 0L) {
                    val key = "wl_day0_${wId}_$releaseDateStr"
                    if (!notifiedIds.contains(key)) {
                        sendMovieNotification(
                            key.hashCode(),
                            "$title • Tayang Hari Ini",
                            "Sudah tayang di bioskop Indonesia.",
                            chosenPoster
                        )
                        notifiedIds.add(key)
                        sentCount++
                    }
                } else if (dayAlert && daysUntil == 1L) {
                    val key = "wl_day1_${wId}_$releaseDateStr"
                    if (!notifiedIds.contains(key)) {
                        sendMovieNotification(
                            key.hashCode(),
                            "$title • Tayang Besok",
                            "Mulai tayang di bioskop besok ($prettyDate).",
                            chosenPoster
                        )
                        notifiedIds.add(key)
                        sentCount++
                    }
                } else if (presaleAlert && daysUntil in 2L..4L) {
                    val key = "wl_presale_${wId}_$releaseDateStr"
                    if (!notifiedIds.contains(key)) {
                        sendMovieNotification(
                            key.hashCode(),
                            "$title • Tiket Pre-Sale Dibuka",
                            "Rilis $prettyDate (H-$daysUntil). Tiket sudah mulai dibuka.",
                            chosenPoster
                        )
                        notifiedIds.add(key)
                        sentCount++
                    }
                }
            }

            if (apiKey.isEmpty()) {
                prefs.edit().putStringSet("notified_movie_ids", notifiedIds).apply()
                return Result.success()
            }

            // 2. Check TBA Watchlist movies for Indonesian theatrical release confirmation
            val tbaArray = JSONArray(tbaWatchJson)
            for (i in 0 until tbaArray.length()) {
                val tmdbId = tbaArray.optInt(i, -1)
                if (tmdbId <= 0) continue

                val relJson = fetchJson("https://api.themoviedb.org/3/movie/$tmdbId/release_dates?api_key=$apiKey") ?: continue
                val results = relJson.optJSONArray("results") ?: continue
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
                    val dJson = fetchJson("https://api.themoviedb.org/3/movie/$tmdbId?api_key=$apiKey") ?: continue
                    val title = dJson.optString("title", "Film Watchlist")
                    val posterPath = dJson.optString("poster_path", "")
                    val defaultPoster = if (posterPath.isNotEmpty()) "https://image.tmdb.org/t/p/w500$posterPath" else ""
                    val chosenPoster = customPosters.optString("search_$tmdbId", "")
                        .ifEmpty { customPosters.optString("tmdb_$tmdbId", defaultPoster) }
                    val parsedDate = try { LocalDate.parse(idReleaseDate).format(shortDateFmt) } catch (_: Exception) { idReleaseDate }
                    sendMovieNotification(
                        notifyKey.hashCode(),
                        "$title • Resmi Masuk Indo",
                        "Jadwal tayang bioskop Indonesia: $parsedDate.",
                        chosenPoster
                    )
                    notifiedIds.add(notifyKey)
                    sentCount++
                }
            }

            // 3. Scan Upcoming Indonesian theatrical releases (including re-releases via region=ID)
            val endpoint = "https://api.themoviedb.org/3/discover/movie?api_key=$apiKey" +
                "&region=ID&with_release_type=2|3" +
                "&release_date.gte=$todayStr&sort_by=popularity.desc&page=1"
            val json = fetchJson(endpoint)
            val results = json?.optJSONArray("results")

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
                        val parsedDate = try { LocalDate.parse(releaseDate).format(shortDateFmt) } catch (_: Exception) { releaseDate }
                        sendMovieNotification(
                            id.hashCode(),
                            "$title • Masuk Jadwal Indo",
                            "Tayang mulai $parsedDate • Hype $hypeScore%.",
                            chosenPoster
                        )
                        notifiedIds.add(id)
                        sentCount++
                    }
                }
            }

            prefs.edit().putStringSet("notified_movie_ids", notifiedIds).apply()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun fetchJson(urlStr: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(body)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun sendMovieNotification(notificationId: Int, title: String, bodyText: String, posterUrl: String) {
        var bitmap: Bitmap? = null
        var conn: HttpURLConnection? = null
        try {
            if (posterUrl.isNotEmpty()) {
                val notifPosterUrl = posterUrl.replace("/w500/", "/w342/")
                conn = (URL(notifPosterUrl).openConnection() as HttpURLConnection).apply {
                    doInput = true
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                conn.connect()
                bitmap = BitmapFactory.decodeStream(conn.inputStream)
            }
        } catch (_: Exception) {
        } finally {
            conn?.disconnect()
        }

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
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText).setBigContentTitle(title))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        if (bitmap != null) {
            builder.setLargeIcon(bitmap)
        }

        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, builder.build())
    }
}
