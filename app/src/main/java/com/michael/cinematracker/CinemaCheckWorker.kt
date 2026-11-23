package com.michael.cinematracker

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class CinemaCheckWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val prefs = appContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString("tmdb_api_key", "")?.trim() ?: ""
        val radarFeedUrl = prefs.getString("radar_feed_url", "")?.trim() ?: ""
        val minHype = prefs.getInt("min_hype_threshold", 85)
        val intervalHours = prefs.getLong("sync_interval_hours", 6L).coerceIn(1L, 24L)
        val tbaWatchJson = prefs.getString("tba_watch_list_json", "[]") ?: "[]"
        val watchlistFullJson = prefs.getString("watchlist_full_json", "{}") ?: "{}"
        val customPostersJson = prefs.getString("custom_posters_json", "{}") ?: "{}"
        val forceNetwork = inputData.getBoolean("force_network_sync", false)

        // Quiet hours guard (22:30 - 06:59 local time) for background notifications
        if (!forceNetwork && isQuietHoursNow()) {
            return Result.success()
        }

        return try {
            val today = LocalDate.now()
            val todayStr = today.toString()
            val fullDateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
            val shortDateFmt = DateTimeFormatter.ofPattern("dd MMM")
            val notifiedIds = (prefs.getStringSet("notified_movie_ids", emptySet()) ?: emptySet())
                .toMutableList()
            val customPosters = try { JSONObject(customPostersJson) } catch (_: Exception) { JSONObject() }

            var sentCount = 0
            val watchlistObj = try { JSONObject(watchlistFullJson) } catch (_: Exception) { JSONObject() }
            var watchlistMutated = false
            val weeklyDigestLines = mutableListOf<String>()

            // 1. Offline-capable Watchlist Reminders (Day-0, H-1, Pre-Sale Window)
            val keysIter = watchlistObj.keys()
            while (keysIter.hasNext()) {
                val wId = keysIter.next()
                val entry = watchlistObj.optJSONObject(wId) ?: continue
                if (entry.optBoolean("isTba", false)) continue

                val dayAlert = entry.optBoolean("dayAlert", true)
                val h1Alert = entry.optBoolean("h1Alert", dayAlert)
                val presaleAlert = entry.optBoolean("presaleAlert", true)
                val movieData = entry.optJSONObject("movieData") ?: continue
                val title = movieData.optString("title", "")
                if (title.isEmpty()) continue

                val releaseDateStr = movieData.optString("releaseDate", "").take(10)
                if (releaseDateStr.length < 10 || releaseDateStr == "TBA") continue

                val relDate = try { LocalDate.parse(releaseDateStr) } catch (_: Exception) { null } ?: continue
                val daysUntil = ChronoUnit.DAYS.between(today, relDate)
                val defaultPoster = movieData.optString("poster", "")
                val chosenPoster = customPosters.optString(wId, defaultPoster)
                val prettyDate = relDate.format(fullDateFmt)
                val isMarkedPresale = movieData.optBoolean("presale", false)

                if (daysUntil in 0L..7L) {
                    val dayTag = when (daysUntil) {
                        0L -> "Hari Ini"
                        1L -> "Besok"
                        else -> relDate.format(shortDateFmt)
                    }
                    weeklyDigestLines.add("$title ($dayTag)")
                }

                if (dayAlert && daysUntil == 0L) {
                    val key = "wl_day0_${wId}_$releaseDateStr"
                    if (!notifiedIds.contains(key)) {
                        sendMovieNotification(
                            notificationId = key.hashCode(),
                            channelId = MainActivity.CHANNEL_WATCHLIST_URGENT,
                            subLabel = "Tayang Hari Ini",
                            movieTitle = title,
                            bodyText = "Tayang hari ini ($prettyDate).",
                            posterUrl = chosenPoster,
                            movieId = wId,
                            priority = NotificationCompat.PRIORITY_HIGH
                        )
                        notifiedIds.add(key)
                        sentCount++
                    }
                } else if (h1Alert && daysUntil == 1L) {
                    val key = "wl_day1_${wId}_$releaseDateStr"
                    if (!notifiedIds.contains(key)) {
                        sendMovieNotification(
                            notificationId = key.hashCode(),
                            channelId = MainActivity.CHANNEL_WATCHLIST_URGENT,
                            subLabel = "Tayang Besok • H-1",
                            movieTitle = title,
                            bodyText = "Tayang besok ($prettyDate).",
                            posterUrl = chosenPoster,
                            movieId = wId,
                            priority = NotificationCompat.PRIORITY_HIGH
                        )
                        notifiedIds.add(key)
                        sentCount++
                    }
                } else if (presaleAlert && ((daysUntil in 2L..4L) || (isMarkedPresale && daysUntil in 2L..7L))) {
                    val key = "wl_presale_${wId}_$releaseDateStr"
                    if (!notifiedIds.contains(key)) {
                        sendMovieNotification(
                            notificationId = key.hashCode(),
                            channelId = MainActivity.CHANNEL_WATCHLIST_URGENT,
                            subLabel = "Pre-Sale / Cek Kursi",
                            movieTitle = title,
                            bodyText = "Rilis $prettyDate (H-$daysUntil).",
                            posterUrl = chosenPoster,
                            movieId = wId,
                            priority = NotificationCompat.PRIORITY_HIGH
                        )
                        notifiedIds.add(key)
                        sentCount++
                    }
                }
            }

            if ((apiKey.isEmpty() && radarFeedUrl.isEmpty()) || !isNetworkConnected()) {
                maybeSendWeeklyDigest(prefs, weeklyDigestLines)
                saveNotifiedIds(prefs, notifiedIds)
                return Result.success()
            }

            // Respect network sync interval unless explicitly forced
            val lastNetSync = prefs.getLong("last_worker_net_sync_ms", 0L)
            val nowMs = System.currentTimeMillis()
            val minNetGapMs = (intervalHours * 3600_000L) - 10 * 60_000L
            if (!forceNetwork && lastNetSync > 0L && (nowMs - lastNetSync) < minNetGapMs) {
                maybeSendWeeklyDigest(prefs, weeklyDigestLines)
                saveNotifiedIds(prefs, notifiedIds)
                return Result.success()
            }

            // 1.5 Optional Cloud Radar JSON Feed Check (single compact GET)
            if (radarFeedUrl.startsWith("http://") || radarFeedUrl.startsWith("https://")) {
                try {
                    val radarJson = fetchJson(radarFeedUrl)
                    val entries = radarJson?.optJSONArray("entries")
                    if (entries != null && entries.length() > 0) {
                        val byTmdb = HashMap<Int, JSONObject>()
                        val byNorm = HashMap<String, JSONObject>()
                        for (idx in 0 until entries.length()) {
                            val rObj = entries.optJSONObject(idx) ?: continue
                            val rTmdb = rObj.optInt("tmdbId", 0)
                            val rTitleNorm = rObj.optString("title", "")
                                .lowercase()
                                .replace(Regex("[^a-z0-9]"), "")
                            if (rTmdb > 0) byTmdb[rTmdb] = rObj
                            if (rTitleNorm.isNotEmpty()) byNorm[rTitleNorm] = rObj
                        }

                        val wlKeys = watchlistObj.keys()
                        while (wlKeys.hasNext()) {
                            val wId = wlKeys.next()
                            val entry = watchlistObj.optJSONObject(wId) ?: continue
                            val mData = entry.optJSONObject("movieData") ?: continue
                            val entryTmdb = entry.optInt("tmdbId", 0)
                            val title = mData.optString("title", "")
                            val normTitle = title.lowercase().replace(Regex("[^a-z0-9]"), "")
                            val matchedRadar = (if (entryTmdb > 0) byTmdb[entryTmdb] else null)
                                ?: (if (normTitle.isNotEmpty()) byNorm[normTitle] else null)
                                ?: continue

                            val radarRel = matchedRadar.optString("releaseDateId", "").take(10)
                            val hasValidRel = Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(radarRel)
                            val radarChains = matchedRadar.optJSONArray("chains")
                            val radarImax = matchedRadar.optBoolean("imax", false)
                            val radarPresale = matchedRadar.optBoolean("presaleActive", false)

                            if (radarChains != null && radarChains.length() > 0) {
                                mData.put("chains", radarChains)
                                watchlistMutated = true
                            }
                            if (radarImax && !mData.optBoolean("imax", false)) {
                                mData.put("imax", true)
                                watchlistMutated = true
                            }
                            if (radarPresale && !mData.optBoolean("presale", false)) {
                                mData.put("presale", true)
                                watchlistMutated = true
                                val preKey = "radar_presale_${wId}_$radarRel"
                                if (entry.optBoolean("presaleAlert", true) && !notifiedIds.contains(preKey) && title.isNotEmpty()) {
                                    val chosenPoster = customPosters.optString(wId, mData.optString("poster", ""))
                                    sendMovieNotification(
                                        notificationId = preKey.hashCode(),
                                        channelId = MainActivity.CHANNEL_WATCHLIST_URGENT,
                                        subLabel = "Pre-Sale Terdeteksi • Radar ID",
                                        movieTitle = title,
                                        bodyText = "Tiket pre-sale dilaporkan mulai dibuka.",
                                        posterUrl = chosenPoster,
                                        movieId = wId,
                                        priority = NotificationCompat.PRIORITY_HIGH
                                    )
                                    notifiedIds.add(preKey)
                                    sentCount++
                                }
                            }

                            if (entry.optBoolean("isTba", false) && hasValidRel) {
                                val parsedRadarDate = try { LocalDate.parse(radarRel).format(fullDateFmt) } catch (_: Exception) { radarRel }
                                entry.put("isTba", false)
                                entry.put("lastKnownReleaseDate", radarRel)
                                mData.put("tba", false)
                                mData.put("releaseDate", radarRel)
                                mData.put("confirmedText", "Terkonfirmasi ID: $parsedRadarDate")
                                mData.put("sourceBadge", "Radar Bioskop ID")
                                watchlistMutated = true

                                val radarKey = "radar_tba_${wId}_$radarRel"
                                if (!notifiedIds.contains(radarKey) && title.isNotEmpty()) {
                                    val chosenPoster = customPosters.optString(wId, mData.optString("poster", ""))
                                    sendMovieNotification(
                                        notificationId = radarKey.hashCode(),
                                        channelId = MainActivity.CHANNEL_WATCHLIST_URGENT,
                                        subLabel = "Rilis ID Terkonfirmasi • Radar",
                                        movieTitle = title,
                                        bodyText = "Jadwal bioskop: $parsedRadarDate.",
                                        posterUrl = chosenPoster,
                                        movieId = wId,
                                        priority = NotificationCompat.PRIORITY_HIGH
                                    )
                                    notifiedIds.add(radarKey)
                                    sentCount++
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Optional radar feed failure should not fail TMDB checks
                }
            }

            if (apiKey.isNotEmpty()) {
                // 2. Check TBA Watchlist movies for Indonesian theatrical release confirmation
                val tbaArray = try { JSONArray(tbaWatchJson) } catch (_: Exception) { JSONArray() }

                for (i in 0 until tbaArray.length()) {
                    val tmdbId = tbaArray.optInt(i, -1)
                    if (tmdbId <= 0) continue

                    val searchKeyCheck = "search_$tmdbId"
                    val tmdbKeyCheck = "tmdb_$tmdbId"
                    val existingCheck = watchlistObj.optJSONObject(searchKeyCheck) ?: watchlistObj.optJSONObject(tmdbKeyCheck)
                    if (existingCheck != null && !existingCheck.optBoolean("isTba", true)) {
                        continue
                    }

                    val idReleaseDate = fetchIndonesianTheatricalDate(tmdbId, apiKey) ?: continue

                    if (idReleaseDate.isNotEmpty()) {
                        val searchKey = "search_$tmdbId"
                        val tmdbKey = "tmdb_$tmdbId"
                        val resolvedKey = if (watchlistObj.has(searchKey)) searchKey else tmdbKey
                        val targetEntry = watchlistObj.optJSONObject(resolvedKey)
                        val parsedDate = try { LocalDate.parse(idReleaseDate).format(fullDateFmt) } catch (_: Exception) { idReleaseDate }

                        if (targetEntry != null) {
                            targetEntry.put("isTba", false)
                            targetEntry.put("lastKnownReleaseDate", idReleaseDate)
                            val mData = targetEntry.optJSONObject("movieData")
                            if (mData != null) {
                                mData.put("tba", false)
                                mData.put("releaseDate", idReleaseDate)
                                mData.put("confirmedText", "Mulai tayang $parsedDate")
                                mData.put("sourceBadge", "Bioskop ID")
                            }
                            watchlistMutated = true
                        }

                        val notifyKey = "tba_confirmed_${tmdbId}_$idReleaseDate"
                        if (!notifiedIds.contains(notifyKey)) {
                            val dJson = fetchJson("https://api.themoviedb.org/3/movie/$tmdbId?api_key=$apiKey")
                            val title = dJson?.optString("title", "")?.ifEmpty { null }
                                ?: targetEntry?.optJSONObject("movieData")?.optString("title", "Film Watchlist")
                                ?: "Film Watchlist"
                            val posterPath = dJson?.optString("poster_path", "") ?: ""
                            val defaultPoster = if (posterPath.isNotEmpty()) "https://image.tmdb.org/t/p/w500$posterPath" else ""
                            val chosenPoster = customPosters.optString(searchKey, "")
                                .ifEmpty { customPosters.optString(tmdbKey, defaultPoster) }
                            sendMovieNotification(
                                notificationId = notifyKey.hashCode(),
                                channelId = MainActivity.CHANNEL_WATCHLIST_URGENT,
                                subLabel = "Rilis ID Terkonfirmasi",
                                movieTitle = title,
                                bodyText = "Jadwal bioskop: $parsedDate.",
                                posterUrl = chosenPoster,
                                movieId = resolvedKey,
                                priority = NotificationCompat.PRIORITY_HIGH
                            )
                            notifiedIds.add(notifyKey)
                            sentCount++
                        }
                    }
                }

                // 3. Detect schedule shifts on active upcoming Watchlist items with TMDB ID (cap at 6 per run)
                var shiftChecks = 0
                val wlIter = watchlistObj.keys()
                while (wlIter.hasNext() && shiftChecks < 6) {
                    val wId = wlIter.next()
                    val entry = watchlistObj.optJSONObject(wId) ?: continue
                    if (entry.optBoolean("isTba", false)) continue

                    val tmdbId = entry.optInt("tmdbId", 0)
                    if (tmdbId <= 0) continue

                    val mData = entry.optJSONObject("movieData") ?: continue
                    val oldDate = mData.optString("releaseDate", "").take(10)
                    if (oldDate.length != 10 || oldDate < todayStr) continue

                    shiftChecks++
                    val latestDate = fetchIndonesianTheatricalDate(tmdbId, apiKey)
                    if (!latestDate.isNullOrEmpty() && latestDate != oldDate) {
                        val prettyNew = try { LocalDate.parse(latestDate).format(fullDateFmt) } catch (_: Exception) { latestDate }
                        val prettyOld = try { LocalDate.parse(oldDate).format(fullDateFmt) } catch (_: Exception) { oldDate }
                        entry.put("lastKnownReleaseDate", latestDate)
                        mData.put("releaseDate", latestDate)
                        mData.put("confirmedText", "Jadwal ID terbaru: $prettyNew")
                        watchlistMutated = true

                        val shiftKey = "wl_shift_${wId}_$latestDate"
                        if (!notifiedIds.contains(shiftKey)) {
                            val title = mData.optString("title", "Film Watchlist")
                            val chosenPoster = customPosters.optString(wId, mData.optString("poster", ""))
                            sendMovieNotification(
                                notificationId = shiftKey.hashCode(),
                                channelId = MainActivity.CHANNEL_WATCHLIST_URGENT,
                                subLabel = "Perubahan Jadwal ID",
                                movieTitle = title,
                                bodyText = "Bergeser dari $prettyOld ke $prettyNew.",
                                posterUrl = chosenPoster,
                                movieId = wId,
                                priority = NotificationCompat.PRIORITY_HIGH
                            )
                            notifiedIds.add(shiftKey)
                            sentCount++
                        }
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

                        val relParsed = try { LocalDate.parse(releaseDate) } catch (_: Exception) { null }
                        if (relParsed != null && ChronoUnit.DAYS.between(today, relParsed) in 0L..7L && hypeScore >= 85) {
                            val line = "${title.uppercase()} (${relParsed.format(shortDateFmt)})"
                            if (!weeklyDigestLines.any { it.startsWith(title.uppercase()) }) {
                                weeklyDigestLines.add(line)
                            }
                        }

                        if (sentCount < 4 && hypeScore >= minHype && !notifiedIds.contains(id)) {
                            val defaultPoster = "https://image.tmdb.org/t/p/w500$posterPath"
                            val chosenPoster = customPosters.optString("tmdb_$id", defaultPoster)
                            val parsedDate = relParsed?.format(fullDateFmt) ?: releaseDate
                            sendMovieNotification(
                                notificationId = id.hashCode(),
                                channelId = MainActivity.CHANNEL_WEEKLY_DIGEST,
                                subLabel = "Jadwal Bioskop ID • Hype $hypeScore%",
                                movieTitle = title,
                                bodyText = "Mulai $parsedDate di bioskop ID.",
                                posterUrl = chosenPoster,
                                movieId = "tmdb_$id",
                                priority = NotificationCompat.PRIORITY_DEFAULT
                            )
                            notifiedIds.add(id)
                            sentCount++
                        }
                    }
                }
            }

            maybeSendWeeklyDigest(prefs, weeklyDigestLines)

            val editor = prefs.edit().putLong("last_worker_net_sync_ms", nowMs)
            if (watchlistMutated) {
                val rebuiltTbaIds = JSONArray()
                val remIter = watchlistObj.keys()
                while (remIter.hasNext()) {
                    val rk = remIter.next()
                    val rEntry = watchlistObj.optJSONObject(rk) ?: continue
                    if (rEntry.optBoolean("isTba", false)) {
                        val tid = rEntry.optInt("tmdbId", 0)
                        if (tid > 0) rebuiltTbaIds.put(tid)
                    }
                }
                editor.putString("watchlist_full_json", watchlistObj.toString())
                editor.putString("tba_watch_list_json", rebuiltTbaIds.toString())
            }
            editor.apply()
            saveNotifiedIds(prefs, notifiedIds)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun isQuietHoursNow(): Boolean {
        val now = LocalTime.now()
        return now.hour < 7 || now.hour > 22 || (now.hour == 22 && now.minute >= 30)
    }

    private fun fetchIndonesianTheatricalDate(tmdbId: Int, apiKey: String): String? {
        val relJson = fetchJson("https://api.themoviedb.org/3/movie/$tmdbId/release_dates?api_key=$apiKey")
            ?: return null
        val results = relJson.optJSONArray("results") ?: return ""
        val theatricalDates = mutableListOf<String>()
        var fallbackDate = ""
        for (j in 0 until results.length()) {
            val countryObj = results.getJSONObject(j)
            if (countryObj.optString("iso_3166_1") == "ID") {
                val rDates = countryObj.optJSONArray("release_dates") ?: continue
                for (k in 0 until rDates.length()) {
                    val rd = rDates.getJSONObject(k)
                    val type = rd.optInt("type", 0)
                    val dateStr = rd.optString("release_date", "").take(10)
                    if (dateStr.length == 10) {
                        if (type == 2 || type == 3) {
                            theatricalDates.add(dateStr)
                        } else if (fallbackDate.isEmpty()) {
                            fallbackDate = dateStr
                        }
                    }
                }
            }
        }
        return theatricalDates.minOrNull() ?: fallbackDate
    }

    private fun maybeSendWeeklyDigest(prefs: android.content.SharedPreferences, lines: List<String>) {
        if (lines.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nowMs = System.currentTimeMillis()
        val lastDigestMs = prefs.getLong("last_weekly_digest_ms", 0L)
        val minWeeklyGapMs = 6L * 24L * 3600_000L + 12L * 3600_000L // ~6.5 days
        if (lastDigestMs > 0L && (nowMs - lastDigestMs) < minWeeklyGapMs) return

        val uniqueLines = lines.distinct().take(5)
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, "digest")
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            WEEKLY_DIGEST_NOTIF_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val inboxStyle = NotificationCompat.InboxStyle()
            .setBigContentTitle("Radar Bioskop Pekan Ini (${uniqueLines.size})")
            .setSummaryText("Cek jadwal & kursi di Cinema Tracker")
        uniqueLines.forEach { inboxStyle.addLine(it) }

        val builder = NotificationCompat.Builder(appContext, MainActivity.CHANNEL_WEEKLY_DIGEST)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF18181B.toInt())
            .setSubText("Radar Mingguan ID")
            .setContentTitle("${uniqueLines.size} film rilis pekan ini")
            .setContentText(uniqueLines.first())
            .setStyle(inboxStyle)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(WEEKLY_DIGEST_NOTIF_ID, builder.build())
        prefs.edit().putLong("last_weekly_digest_ms", nowMs).apply()
    }

    private fun isNetworkConnected(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun saveNotifiedIds(prefs: android.content.SharedPreferences, list: MutableList<String>) {
        // Bound stored notification keys to prevent SharedPreferences bloat
        val trimmed = if (list.size > 200) list.takeLast(200) else list
        prefs.edit().putStringSet("notified_movie_ids", HashSet(trimmed)).apply()
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
        channelId: String,
        subLabel: String,
        movieTitle: String,
        bodyText: String,
        posterUrl: String,
        movieId: String,
        priority: Int
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

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
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                rawBitmap = conn.inputStream.use { BitmapFactory.decodeStream(it, null, opts) }
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
            putExtra(MainActivity.EXTRA_OPEN_MOVIE_ID, movieId)
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val wlIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, "watchlist")
        }
        val wlPending = PendingIntent.getActivity(
            appContext,
            notificationId xor 0x4F2A,
            wlIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF18181B.toInt())
            .setSubText(subLabel)
            .setContentTitle(movieTitle)
            .setContentText(bodyText)
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, "Watchlist", wlPending)

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

        rawBitmap?.recycle()
        bannerBitmap?.recycle()
    }

    companion object {
        private const val WEEKLY_DIGEST_NOTIF_ID = 90210
    }
}
