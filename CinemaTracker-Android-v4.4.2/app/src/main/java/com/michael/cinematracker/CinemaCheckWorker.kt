package com.michael.cinematracker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
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
            val fullDateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
            val notifiedIds = prefs.getStringSet("notified_movie_ids", emptySet())?.toMutableSet()
                ?: mutableSetOf()
            val customPosters = try { JSONObject(customPostersJson) } catch (_: Exception) { JSONObject() }

            var sentCount = 0

            val watchlistObj = try { JSONObject(watchlistFullJson) } catch (_: Exception) { JSONObject() }
            val keysIter = watchlistObj.keys()
            while (keysIter.hasNext()) {
                val wId = keysIter.next()
                val entry = watchlistObj.optJSONObject(wId) ?: continue
                if (entry.optBoolean("isTba", false)) continue

                val dayAlert = entry.optBoolean("dayAlert", true)
                val presaleAlert = entry.optBoolean("presaleAlert", true)
                val movieData = entry.optJSONObject("movie") ?: entry.optJSONObject("movieData") ?: continue
                val title = movieData.optString("title", "")
                if (title.isEmpty()) continue
                val releaseDateStr = movieData.optString("releaseDate", "")
                if (releaseDateStr.length < 10 || releaseDateStr == "TBA") continue

                val relDate = try { LocalDate.parse(releaseDateStr.take(10)) } catch (_: Exception) { null } ?: continue
                val daysUntil = ChronoUnit.DAYS.between(today, relDate)
                val defaultPoster = movieData.optString("poster", "")
                val chosenPoster = customPosters.optString(wId, defaultPoster)
                val prettyDate = relDate.format(fullDateFmt)

                if (dayAlert && daysUntil == 0L) {
                    val key = "wl_day0_${wId}_$releaseDateStr"
                    if (!notifiedIds.contains(key)) {
                        sendMovieNotification(
                            key.hashCode(),
                            "Tayang Hari Ini",
                            title,
                            "Tayang hari ini ($prettyDate).",
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
                            "Tayang Besok • H-1",
                            title,
                            "Tayang besok ($prettyDate).",
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
                            "Pre-Sale Tiket",
                            title,
                            "Rilis $prettyDate (H-$daysUntil).",
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

                val notifyKey = "tba_confirmed_${tmdbId}_$idReleaseDate"
                if (idReleaseDate.isNotEmpty() && !notifiedIds.contains(notifyKey)) {
                    val dJson = fetchJson("https://api.themoviedb.org/3/movie/$tmdbId?api_key=$apiKey") ?: continue
                    val title = dJson.optString("title", "Film Watchlist")
                    val posterPath = dJson.optString("poster_path", "")
                    val defaultPoster = if (posterPath.isNotEmpty()) "https://image.tmdb.org/t/p/w500$posterPath" else ""
                    val chosenPoster = customPosters.optString("search_$tmdbId", "")
                        .ifEmpty { customPosters.optString("tmdb_$tmdbId", defaultPoster) }
                    val parsedDate = try { LocalDate.parse(idReleaseDate).format(fullDateFmt) } catch (_: Exception) { idReleaseDate }
                    sendMovieNotification(
                        notifyKey.hashCode(),
                        "Jadwal ID Rilis",
                        title,
                        "Tayang $parsedDate.",
                        chosenPoster
                    )
                    notifiedIds.add(notifyKey)
                    sentCount++
                }
            }

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

                    if (releaseDate.isEmpty() || releaseDate < todayStr || posterPath.isEmpty()) continue

                    val popularity = item.optDouble("popularity", 0.0)
                    if (popularity < 5.0) continue

                    val genreIds = item.optJSONArray("genre_ids")
                    var isDoc = false
                    if (genreIds != null) {
                        for (g in 0 until genreIds.length()) {
                            if (genreIds.optInt(g) == 99) isDoc = true
                        }
                    }
                    if (isDoc && popularity < 12.0) continue

                    val hypeScore = Math.min(98, Math.max(75, Math.round(74 + Math.min(24.0, popularity / 4.0)).toInt()))
                    val dedupKey = "${id}_$releaseDate"

                    if (hypeScore >= minHype && !notifiedIds.contains(dedupKey)) {
                        val defaultPoster = "https://image.tmdb.org/t/p/w500$posterPath"
                        val chosenPoster = customPosters.optString("tmdb_$id", defaultPoster)
                        val parsedDate = try { LocalDate.parse(releaseDate).format(fullDateFmt) } catch (_: Exception) { releaseDate }
                        sendMovieNotification(
                            dedupKey.hashCode(),
                            "Rilis ID • Hype $hypeScore%",
                            title,
                            "Mulai $parsedDate.",
                            chosenPoster
                        )
                        notifiedIds.add(dedupKey)
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

    private fun buildFramedPosterBanner(rawPoster: Bitmap): Bitmap {
        val width = 640
        val height = 320
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                0xFF09090B.toInt(), 0xFF1F2430.toInt(),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val posterH = 280f
        val posterW = posterH * (2f / 3f)
        val left = (width - posterW) / 2f
        val top = (height - posterH) / 2f
        val rect = RectF(left, top, left + posterW, top + posterH)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33FFFFFF
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        val clipPath = Path().apply {
            addRoundRect(rect, 16f, 16f, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawBitmap(rawPoster, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
        canvas.drawRoundRect(rect, 16f, 16f, borderPaint)

        return out
    }

    private fun sendMovieNotification(
        notificationId: Int,
        subLabel: String,
        movieTitle: String,
        bodyText: String,
        posterUrl: String
    ) {
        var rawBitmap: Bitmap? = null
        var bannerBitmap: Bitmap? = null
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
                rawBitmap = BitmapFactory.decodeStream(conn.inputStream)
                if (rawBitmap != null) {
                    bannerBitmap = buildFramedPosterBanner(rawBitmap)
                }
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
            .setColor(0xFF18181B.toInt())
            .setSubText(subLabel)
            .setContentTitle(movieTitle)
            .setContentText(bodyText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        if (rawBitmap != null && bannerBitmap != null) {
            builder.setLargeIcon(rawBitmap)
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(bannerBitmap)
                    .bigLargeIcon(null as Bitmap?)
                    .setBigContentTitle(movieTitle)
                    .setSummaryText(bodyText)
            )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(bodyText).setBigContentTitle(movieTitle))
        }

        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, builder.build())

        bannerBitmap?.recycle()
        rawBitmap?.recycle()
    }
}
