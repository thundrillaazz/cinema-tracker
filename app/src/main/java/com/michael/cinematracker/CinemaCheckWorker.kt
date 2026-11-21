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
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale

class CinemaCheckWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val isManual = inputData.getBoolean("is_manual", false)
        val nowTime = LocalTime.now()
        // Respect quiet hours (22:00 - 07:30) for automatic background checks
        if (!isManual && (nowTime.isAfter(LocalTime.of(22, 0)) || nowTime.isBefore(LocalTime.of(7, 30)))) {
            return Result.success()
        }

        val prefs = appContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString("tmdb_api_key", "")?.trim() ?: ""
        val minHype = prefs.getInt("min_hype_threshold", 85)
        val tbaWatchJson = prefs.getString("tba_watch_list_json", "[]") ?: "[]"
        val watchlistFullJson = prefs.getString("watchlist_full_json", "{}") ?: "{}"
        val customPostersJson = prefs.getString("custom_posters_json", "{}") ?: "{}"
        val knownDatesJson = prefs.getString("known_release_dates_json", "{}") ?: "{}"

        return try {
            val today = LocalDate.now()
            val todayStr = today.toString()
            val fullDateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
            val shortDateFmt = DateTimeFormatter.ofPattern("dd MMM", Locale("id", "ID"))
            val notifiedIds = prefs.getStringSet("notified_movie_ids", emptySet())?.toMutableSet()
                ?: mutableSetOf()
            val customPosters = try { JSONObject(customPostersJson) } catch (_: Exception) { JSONObject() }
            val knownDates = try { JSONObject(knownDatesJson) } catch (_: Exception) { JSONObject() }

            var sentCount = 0
            val thisWeekTitles = mutableListOf<String>()

            // 1. Smart Watchlist Reminders (H-0, H-1, Pre-Sale Window, Date Shift, & Weekly Digest Collector)
            val watchlistObj = try { JSONObject(watchlistFullJson) } catch (_: Exception) { JSONObject() }
            val keysIter = watchlistObj.keys()
            while (keysIter.hasNext()) {
                val wId = keysIter.next()
                val entry = watchlistObj.optJSONObject(wId) ?: continue
                if (entry.optBoolean("isTba", false)) continue
                if (entry.optString("watchStatus", "diincar") == "watched") continue

                val dayAlert = entry.optBoolean("dayAlert", true)
                val presaleAlert = entry.optBoolean("presaleAlert", true)
                val userNote = entry.optString("userNote", "").trim()
                val movieData = entry.optJSONObject("movieData") ?: continue
                val title = movieData.optString("title", "")
                if (title.isEmpty()) continue
                val releaseDateStr = movieData.optString("releaseDate", "")
                if (releaseDateStr.length < 10 || releaseDateStr == "TBA") continue

                val cleanRel = releaseDateStr.take(10)
                val relDate = try { LocalDate.parse(cleanRel) } catch (_: Exception) { null } ?: continue
                val daysUntil = ChronoUnit.DAYS.between(today, relDate)
                val defaultPoster = movieData.optString("poster", "")
                val chosenPoster = customPosters.optString(wId, defaultPoster)
                val prettyDate = relDate.format(fullDateFmt)
                val noteSuffix = if (userNote.isNotEmpty()) " • $userNote" else ""

                // Detect Release Date Shift on tracked Watchlist items
                val prevKnown = knownDates.optString(wId, "")
                if (prevKnown.isNotEmpty() && prevKnown != cleanRel && daysUntil >= 0L) {
                    val shiftKey = "wl_shift_${wId}_${prevKnown}_$cleanRel"
                    if (!notifiedIds.contains(shiftKey)) {
                        sendMovieNotification(
                            shiftKey.hashCode(),
                            "Perubahan Jadwal Rilis",
                            title,
                            "Jadwal bergeser ke $prettyDate$noteSuffix",
                            chosenPoster,
                            wId
                        )
                        notifiedIds.add(shiftKey)
                        sentCount++
                    }
                }
                knownDates.put(wId, cleanRel)

                // Collect upcoming 7-day titles for Weekly Cinema Digest
                if (daysUntil in 0L..6L) {
                    thisWeekTitles.add("• $title (${relDate.format(shortDateFmt)})")
                }

                if (dayAlert && daysUntil == 0L) {
                    val key = "wl_day0_${wId}_$cleanRel"
                    if (!notifiedIds.contains(key)) {
                        sendMovieNotification(
                            key.hashCode(),
                            "Tayang Hari Ini",
                            title,
                            "Mulai tayang hari ini ($prettyDate)$noteSuffix",
                            chosenPoster,
                            wId
                        )
                        notifiedIds.add(key)
                        sentCount++
                    }
                } else if (dayAlert && daysUntil == 1L) {
                    val key = "wl_day1_${wId}_$cleanRel"
                    if (!notifiedIds.contains(key)) {
                        sendMovieNotification(
                            key.hashCode(),
                            "Tayang Besok • H-1",
                            title,
                            "Tayang besok ($prettyDate)$noteSuffix",
                            chosenPoster,
                            wId
                        )
                        notifiedIds.add(key)
                        sentCount++
                    }
                } else if (presaleAlert && daysUntil in 2L..4L) {
                    val key = "wl_presale_${wId}_$cleanRel"
                    if (!notifiedIds.contains(key)) {
                        sendMovieNotification(
                            key.hashCode(),
                            "Cek Tiket Pre-Sale",
                            title,
                            "Rilis $prettyDate (H-$daysUntil)$noteSuffix",
                            chosenPoster,
                            wId
                        )
                        notifiedIds.add(key)
                        sentCount++
                    }
                }
            }

            // 2. Weekly Cinema Digest on Wednesday / Thursday (or manual scan if >= 2 films this week)
            val weekNum = today.get(WeekFields.ISO.weekOfWeekBasedYear())
            val digestKey = "weekly_digest_${today.year}_w$weekNum"
            val isCinemaReleaseMidweek = (today.dayOfWeek == DayOfWeek.WEDNESDAY || today.dayOfWeek == DayOfWeek.THURSDAY)
            if (thisWeekTitles.isNotEmpty() && (isCinemaReleaseMidweek || (isManual && sentCount == 0)) && !notifiedIds.contains(digestKey)) {
                val summaryLine = "${thisWeekTitles.size} film Watchlist tayang pekan ini"
                val detailBody = thisWeekTitles.take(5).joinToString("\n")
                sendDigestNotification(digestKey.hashCode(), "Ringkasan Bioskop Pekan Ini", summaryLine, detailBody)
                notifiedIds.add(digestKey)
                sentCount++
            }

            if (apiKey.isEmpty()) {
                prefs.edit()
                    .putStringSet("notified_movie_ids", notifiedIds)
                    .putString("known_release_dates_json", knownDates.toString())
                    .apply()
                return Result.success()
            }

            // 3. Check TBA Watchlist movies for Indonesian theatrical release confirmation
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
                        "Rilis ID Terkonfirmasi",
                        title,
                        "Jadwal bioskop ID: $parsedDate",
                        chosenPoster,
                        "search_$tmdbId"
                    )
                    notifiedIds.add(notifyKey)
                    sentCount++
                }
            }

            // 4. Scan High-Hype Theatrical Indonesian Releases
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

                    if (hypeScore >= minHype && !notifiedIds.contains(id)) {
                        val defaultPoster = "https://image.tmdb.org/t/p/w500$posterPath"
                        val chosenPoster = customPosters.optString("tmdb_$id", defaultPoster)
                        val parsedDate = try { LocalDate.parse(releaseDate).format(fullDateFmt) } catch (_: Exception) { releaseDate }
                        sendMovieNotification(
                            id.hashCode(),
                            "Jadwal Bioskop ID • Hype $hypeScore%",
                            title,
                            "Mulai $parsedDate di bioskop ID",
                            chosenPoster,
                            "tmdb_$id"
                        )
                        notifiedIds.add(id)
                        sentCount++
                    }
                }
            }

            prefs.edit()
                .putStringSet("notified_movie_ids", notifiedIds)
                .putString("known_release_dates_json", knownDates.toString())
                .apply()
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

    private fun sendDigestNotification(
        notificationId: Int,
        title: String,
        summaryLine: String,
        expandedLines: String
    ) {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
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
            .setSubText("Cinema Tracker ID")
            .setContentTitle(title)
            .setContentText(summaryLine)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(expandedLines)
                    .setBigContentTitle(title)
                    .setSummaryText(summaryLine)
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, builder.build())
    }

    private fun sendMovieNotification(
        notificationId: Int,
        subLabel: String,
        movieTitle: String,
        bodyText: String,
        posterUrl: String,
        movieId: String
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
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (movieId.isNotEmpty()) putExtra(MainActivity.EXTRA_MOVIE_ID, movieId)
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val tixIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tix.id/search?q=" + Uri.encode(movieTitle)))
        val tixPendingIntent = PendingIntent.getActivity(
            appContext,
            notificationId + 77,
            tixIntent,
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
            .addAction(0, "Cek TIX ID", tixPendingIntent)

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
    }
}
