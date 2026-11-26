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
        val intervalHours = prefs.getLong("sync_interval_hours", 3L).coerceIn(1L, 24L)
        val tbaWatchJson = prefs.getString("tba_watch_list_json", "[]") ?: "[]"
        val watchlistFullJson = prefs.getString("watchlist_full_json", "{}") ?: "{}"
        val customPostersJson = prefs.getString("custom_posters_json", "{}") ?: "{}"
        val forceNetwork = inputData.getBoolean("force_network_sync", false)

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

            // 1. Offline-capable Watchlist Reminders (24h active — Day-0, H-1, Pre-Sale Window)
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
                if (CinemaScraperHelper.isPlaceholderDate(releaseDateStr)) continue

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

            if (!isNetworkConnected()) {
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

            val minCatalogDate = today.minusDays(60).toString()
            val maxCatalogDate = today.plusDays(400).toString()

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
                            val rTitleNorm = CinemaScraperHelper.normalizeTitleForMatch(rObj.optString("title", ""))
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
                            val normTitle = CinemaScraperHelper.normalizeTitleForMatch(title)
                            val matchedRadar = (if (entryTmdb > 0) byTmdb[entryTmdb] else null)
                                ?: (if (normTitle.isNotEmpty()) byNorm[normTitle] else null)
                                ?: continue
                            if (!CinemaScraperHelper.sequelMarkersCompatible(title, matchedRadar.optString("title", ""))) {
                                continue
                            }

                            val radarRel = matchedRadar.optString("releaseDateId", "").take(10)
                            val radarStatus = matchedRadar.optString("status", "")
                            val hasValidRel = !CinemaScraperHelper.isPlaceholderDate(radarRel) &&
                                radarStatus in listOf("confirmed", "presale", "lsf_passed")
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

                            val radarDateSource = matchedRadar.optString("dateSource", "").trim()
                            val radarSummary = matchedRadar.optString("summary", "").trim()
                            val isRssRadar = radarDateSource == "rss" ||
                                (radarDateSource.isEmpty() && radarSummary.startsWith("Berita ID:", ignoreCase = true))
                            val wasTbaCheck = entry.optBoolean("isTba", false)
                            val oldDateCheck = mData.optString("releaseDate", "").take(10)
                            val oldBadgeCheck = mData.optString("sourceBadge", "")
                            val hasOfficialTmdbId = oldBadgeCheck == "Bioskop ID" || oldBadgeCheck.startsWith("TMDB ID")
                            val rssBlockedByOfficial = isRssRadar && !wasTbaCheck && hasOfficialTmdbId &&
                                !CinemaScraperHelper.isPlaceholderDate(oldDateCheck) && oldDateCheck >= minCatalogDate

                            if (hasValidRel && !rssBlockedByOfficial) {
                                val wasTba = wasTbaCheck
                                val oldDate = oldDateCheck
                                val oldBadge = oldBadgeCheck
                                val isGlobalDate = oldBadge == "TMDB Global" || oldBadge == "Jadwal Global" || oldBadge == "Estimasi / TBA"
                                val needsOverride = wasTba || CinemaScraperHelper.isPlaceholderDate(oldDate) || isGlobalDate || oldDate != radarRel

                                if (needsOverride) {
                                    val parsedRadarDate = try { LocalDate.parse(radarRel).format(fullDateFmt) } catch (_: Exception) { radarRel }
                                    val prettyOld = try { LocalDate.parse(oldDate).format(fullDateFmt) } catch (_: Exception) { oldDate }
                                    entry.put("isTba", false)
                                    entry.put("lastKnownReleaseDate", radarRel)
                                    entry.put("workerUpdatedAt", nowMs)
                                    mData.put("tba", false)
                                    mData.put("releaseDate", radarRel)
                                    mData.put("confirmedText", "Terkonfirmasi ID: $parsedRadarDate")
                                    mData.put("sourceBadge", "Radar Bioskop ID")
                                    watchlistMutated = true

                                    val chosenPoster = customPosters.optString(wId, mData.optString("poster", ""))
                                    if (!wasTba && !CinemaScraperHelper.isPlaceholderDate(oldDate) && oldDate != radarRel) {
                                        val shiftKey = "wl_shift_${wId}_$radarRel"
                                        if (!notifiedIds.contains(shiftKey) && title.isNotEmpty()) {
                                            val verb = if (radarRel < oldDate) "dimajukan" else "bergeser"
                                            sendMovieNotification(
                                                notificationId = shiftKey.hashCode(),
                                                channelId = MainActivity.CHANNEL_WATCHLIST_URGENT,
                                                subLabel = "Perubahan Jadwal ID",
                                                movieTitle = title,
                                                bodyText = "Jadwal bioskop ID $verb dari $prettyOld ke $parsedRadarDate.",
                                                posterUrl = chosenPoster,
                                                movieId = wId,
                                                priority = NotificationCompat.PRIORITY_HIGH
                                            )
                                            notifiedIds.add(shiftKey)
                                            sentCount++
                                        }
                                    } else {
                                        val radarKey = "radar_tba_${wId}_$radarRel"
                                        if (!notifiedIds.contains(radarKey) && title.isNotEmpty()) {
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
                        }
                    }
                } catch (_: Exception) {
                    // Optional radar feed failure should not fail direct cinema/TMDB checks
                }
            }

            // 1.75 Direct Indonesian Commercial Cinema Feeds (XXI, CGV, Cinépolis via Jadwalnonton/TIX ID, LSF — 2h cached)
            val feedEntries = mutableListOf<JSONObject>()
            val feedByNorm = HashMap<String, JSONObject>()
            try {
                val feedJsonStr = CinemaScraperHelper.fetchIndonesianCinemaFeedsJson(appContext, forceRefresh = forceNetwork)
                val feedArr = JSONObject(feedJsonStr).optJSONArray("entries")
                if (feedArr != null) {
                    for (idx in 0 until feedArr.length()) {
                        val fe = feedArr.optJSONObject(idx) ?: continue
                        val feTitle = fe.optString("title", "").trim()
                        val feNorm = CinemaScraperHelper.normalizeTitleForMatch(feTitle)
                        if (feNorm.isEmpty()) continue
                        feedEntries.add(fe)
                        feedByNorm[feNorm] = fe
                    }
                }
            } catch (_: Exception) {}

            // Match ALL Watchlist items directly against commercial cinema feeds (XXI/CGV/Cinépolis/TIX ID/LSF)
            if (feedEntries.isNotEmpty()) {
                val wlKeysFeed = watchlistObj.keys()
                while (wlKeysFeed.hasNext()) {
                    val wId = wlKeysFeed.next()
                    val entry = watchlistObj.optJSONObject(wId) ?: continue
                    val mData = entry.optJSONObject("movieData") ?: continue
                    val title = mData.optString("title", "")
                    if (title.isEmpty()) continue
                    if (mData.optString("releaseChannel", "") == "digital" || mData.optString("sourceBadge", "") == "Rilis Digital") continue
                    val normTitle = CinemaScraperHelper.normalizeTitleForMatch(title)
                    val matchedFeed = feedByNorm[normTitle] ?: feedEntries.firstOrNull { fe ->
                        val fTitle = fe.optString("title", "")
                        CinemaScraperHelper.sequelMarkersCompatible(title, fTitle) &&
                            CinemaScraperHelper.titleMatchesHeadline(title, fTitle) &&
                            CinemaScraperHelper.titleMatchesHeadline(fTitle, title)
                    } ?: continue
                    if (!CinemaScraperHelper.sequelMarkersCompatible(title, matchedFeed.optString("title", ""))) continue

                    val feChains = matchedFeed.optJSONArray("chains")
                    if (feChains != null && feChains.length() > 0) {
                        val mergedChainSet = LinkedHashSet<String>()
                        val prevChains = mData.optJSONArray("chains")
                        if (prevChains != null) {
                            for (ci in 0 until prevChains.length()) mergedChainSet.add(prevChains.getString(ci))
                        }
                        for (ci in 0 until feChains.length()) mergedChainSet.add(feChains.getString(ci))
                        mData.put("chains", JSONArray(mergedChainSet.toList()))
                        watchlistMutated = true
                    }
                    if (matchedFeed.optBoolean("imax", false) && !mData.optBoolean("imax", false)) {
                        mData.put("imax", true)
                        watchlistMutated = true
                    }
                    if (matchedFeed.optBoolean("isReRelease", false) && !mData.optBoolean("isReRelease", false)) {
                        mData.put("isReRelease", true)
                        watchlistMutated = true
                    }
                    val chosenPoster = customPosters.optString(wId, mData.optString("poster", ""))
                    if (matchedFeed.optBoolean("presaleActive", false) && !mData.optBoolean("presale", false)) {
                        mData.put("presale", true)
                        watchlistMutated = true
                        val preKey = "feed_presale_${wId}_${matchedFeed.optString("releaseDateId", todayStr)}"
                        if (entry.optBoolean("presaleAlert", true) && !notifiedIds.contains(preKey)) {
                            sendMovieNotification(
                                notificationId = preKey.hashCode(),
                                channelId = MainActivity.CHANNEL_WATCHLIST_URGENT,
                                subLabel = "Pre-Sale Dibuka • Bioskop ID",
                                movieTitle = title,
                                bodyText = "Tiket pre-sale sudah tersedia di jaringan bioskop ID.",
                                posterUrl = chosenPoster,
                                movieId = wId,
                                priority = NotificationCompat.PRIORITY_HIGH
                            )
                            notifiedIds.add(preKey)
                            sentCount++
                        }
                    }

                    val feRel = CinemaScraperHelper.validIso(matchedFeed.optString("releaseDateId", ""))
                    val feStatus = matchedFeed.optString("status", "")
                    val feInWindow = feRel.isNotEmpty() && feRel >= minCatalogDate && feRel <= maxCatalogDate
                    val wasTba = entry.optBoolean("isTba", false)
                    val oldDate = mData.optString("releaseDate", "").take(10)
                    val oldBadge = mData.optString("sourceBadge", "")
                    val isOldGlobal = oldBadge == "TMDB Global" || oldBadge == "Jadwal Global" || oldBadge == "Estimasi / TBA"
                    val isReFlag = matchedFeed.optBoolean("isReRelease", false) || mData.optBoolean("isReRelease", false)

                    if (feInWindow && (wasTba || isOldGlobal || CinemaScraperHelper.isPlaceholderDate(oldDate) || feRel != oldDate)) {
                        val prettyNew = try { LocalDate.parse(feRel).format(fullDateFmt) } catch (_: Exception) { feRel }
                        entry.put("isTba", false)
                        entry.put("lastKnownReleaseDate", feRel)
                        entry.put("workerUpdatedAt", nowMs)
                        mData.put("tba", false)
                        mData.put("releaseChannel", "theatrical")
                        mData.put("releaseDate", feRel)
                        mData.put("status", if (feRel <= todayStr) "now" else "upcoming")
                        mData.put("sourceBadge", "Bioskop ID")
                        mData.put("confirmedText", "Terkonfirmasi ID: $prettyNew")
                        watchlistMutated = true

                        val notifyKey = "feed_confirm_${wId}_$feRel"
                        if (!notifiedIds.contains(notifyKey)) {
                            sendMovieNotification(
                                notificationId = notifyKey.hashCode(),
                                channelId = MainActivity.CHANNEL_WATCHLIST_URGENT,
                                subLabel = if (isReFlag) "Re-Release ID Terkonfirmasi" else "Rilis ID Terkonfirmasi",
                                movieTitle = title,
                                bodyText = "Terjadwal tayang $prettyNew di bioskop Indonesia.",
                                posterUrl = chosenPoster,
                                movieId = wId,
                                priority = NotificationCompat.PRIORITY_HIGH
                            )
                            notifiedIds.add(notifyKey)
                            sentCount++
                        }
                    } else if (!feInWindow && (feStatus == "now" || matchedFeed.optString("lsfStatus", "").isNotEmpty() || isReFlag)) {
                        // Confirmed commercial ID presence (undated or currently playing)
                        if (wasTba && mData.optString("sourceBadge", "") != "Radar Bioskop ID") {
                            mData.put("sourceBadge", "Radar Bioskop ID")
                            mData.put("confirmedText", "Terkonfirmasi ID · Tanggal Menyusul")
                            watchlistMutated = true
                        }
                    }
                }
            }

            // Bounded RSS search budget per worker cycle (max 8 new RSS searches/cycle)
            var rssSearchBudget = 8

            if (apiKey.isNotEmpty()) {
                val checkedTmdbIds = HashSet<Int>()

                // 2. Force-check ALL Watchlist movies with TMDB ID (TBA + Global + Confirmed shifts)
                val wlIter = watchlistObj.keys()
                while (wlIter.hasNext()) {
                    val wId = wlIter.next()
                    val entry = watchlistObj.optJSONObject(wId) ?: continue
                    val mData = entry.optJSONObject("movieData") ?: continue
                    val tmdbId = entry.optInt("tmdbId", mData.optInt("tmdbId", 0))
                    if (tmdbId <= 0) continue

                    val wasTba = entry.optBoolean("isTba", false)
                    val oldDate = mData.optString("releaseDate", "").take(10)
                    val oldBadge = mData.optString("sourceBadge", "")
                    val isGlobalDate = oldBadge == "TMDB Global" || oldBadge == "Jadwal Global" || oldBadge == "Estimasi / TBA"
                    val isDigital = mData.optString("releaseChannel", "") == "digital" || oldBadge == "Rilis Digital"
                    if (isDigital) continue

                    checkedTmdbIds.add(tmdbId)
                    val latestDate = fetchIndonesianTheatricalDate(tmdbId, apiKey, minCatalogDate) ?: continue
                    if (latestDate.isEmpty() || CinemaScraperHelper.isPlaceholderDate(latestDate)) {
                        val isArchivalStuck = !CinemaScraperHelper.isPlaceholderDate(oldDate) && oldDate < minCatalogDate
                        val hasIdDateBadge = oldBadge == "Bioskop ID" || oldBadge.startsWith("TMDB ID")
                        if (!wasTba && (isArchivalStuck || hasIdDateBadge)) {
                            entry.put("isTba", true)
                            entry.put("lastKnownReleaseDate", "TBA")
                            entry.put("workerUpdatedAt", nowMs)
                            mData.put("tba", true)
                            mData.put("releaseChannel", "theatrical")
                            mData.put("releaseDate", "TBA")
                            mData.put("dateLabel", "TBA ID")
                            mData.put("sourceBadge", "Estimasi / TBA")
                            mData.put("confirmedText", "Jadwal bioskop Indonesia belum diumumkan")
                            watchlistMutated = true
                        }
                        continue
                    }

                    val needsUpdate = wasTba || CinemaScraperHelper.isPlaceholderDate(oldDate) || isGlobalDate || latestDate != oldDate
                    if (needsUpdate) {
                        val prettyNew = try { LocalDate.parse(latestDate).format(fullDateFmt) } catch (_: Exception) { latestDate }
                        val prettyOld = try { LocalDate.parse(oldDate).format(fullDateFmt) } catch (_: Exception) { oldDate }
                        entry.put("isTba", false)
                        entry.put("lastKnownReleaseDate", latestDate)
                        entry.put("workerUpdatedAt", nowMs)
                        mData.put("tba", false)
                        mData.put("releaseChannel", "theatrical")
                        mData.put("releaseDate", latestDate)
                        mData.put("sourceBadge", "Bioskop ID")
                        mData.put("confirmedText", if (!wasTba && latestDate != oldDate) "Jadwal ID terbaru: $prettyNew" else "Mulai tayang $prettyNew")
                        watchlistMutated = true

                        val title = mData.optString("title", "Film Watchlist")
                        val chosenPoster = customPosters.optString(wId, mData.optString("poster", ""))

                        if (!wasTba && !CinemaScraperHelper.isPlaceholderDate(oldDate) && latestDate != oldDate) {
                            val shiftKey = "wl_shift_${wId}_$latestDate"
                            if (!notifiedIds.contains(shiftKey)) {
                                val verb = if (latestDate < oldDate) "dimajukan" else "bergeser"
                                sendMovieNotification(
                                    notificationId = shiftKey.hashCode(),
                                    channelId = MainActivity.CHANNEL_WATCHLIST_URGENT,
                                    subLabel = "Perubahan Jadwal ID",
                                    movieTitle = title,
                                    bodyText = "Jadwal bioskop ID $verb dari $prettyOld ke $prettyNew.",
                                    posterUrl = chosenPoster,
                                    movieId = wId,
                                    priority = NotificationCompat.PRIORITY_HIGH
                                )
                                notifiedIds.add(shiftKey)
                                sentCount++
                            }
                        } else if (wasTba || CinemaScraperHelper.isPlaceholderDate(oldDate)) {
                            val notifyKey = "tba_confirmed_${tmdbId}_$latestDate"
                            if (!notifiedIds.contains(notifyKey)) {
                                sendMovieNotification(
                                    notificationId = notifyKey.hashCode(),
                                    channelId = MainActivity.CHANNEL_WATCHLIST_URGENT,
                                    subLabel = "Rilis ID Terkonfirmasi",
                                    movieTitle = title,
                                    bodyText = "Jadwal bioskop: $prettyNew.",
                                    posterUrl = chosenPoster,
                                    movieId = wId,
                                    priority = NotificationCompat.PRIORITY_HIGH
                                )
                                notifiedIds.add(notifyKey)
                                sentCount++
                            }
                        }
                    }
                }

                // 3. Fallback check for legacy TBA IDs list if not already present in watchlistObj
                val tbaArray = try { JSONArray(tbaWatchJson) } catch (_: Exception) { JSONArray() }
                for (i in 0 until tbaArray.length()) {
                    val tmdbId = tbaArray.optInt(i, -1)
                    if (tmdbId <= 0 || checkedTmdbIds.contains(tmdbId)) continue
                    val idReleaseDate = fetchIndonesianTheatricalDate(tmdbId, apiKey, todayStr) ?: continue
                    if (idReleaseDate.isEmpty() || CinemaScraperHelper.isPlaceholderDate(idReleaseDate)) continue

                    val parsedDate = try { LocalDate.parse(idReleaseDate).format(fullDateFmt) } catch (_: Exception) { idReleaseDate }
                    val notifyKey = "tba_confirmed_${tmdbId}_$idReleaseDate"
                    if (!notifiedIds.contains(notifyKey)) {
                        val dJson = fetchJson("https://api.themoviedb.org/3/movie/$tmdbId?api_key=$apiKey")
                        val title = dJson?.optString("title", "")?.ifEmpty { null } ?: "Film Watchlist"
                        val posterPath = dJson?.optString("poster_path", "") ?: ""
                        val defaultPoster = if (posterPath.isNotEmpty()) "https://image.tmdb.org/t/p/w500$posterPath" else ""
                        val chosenPoster = customPosters.optString("search_$tmdbId", "")
                            .ifEmpty { customPosters.optString("tmdb_$tmdbId", defaultPoster) }
                        sendMovieNotification(
                            notificationId = notifyKey.hashCode(),
                            channelId = MainActivity.CHANNEL_WATCHLIST_URGENT,
                            subLabel = "Rilis ID Terkonfirmasi",
                            movieTitle = title,
                            bodyText = "Jadwal bioskop: $parsedDate.",
                            posterUrl = chosenPoster,
                            movieId = "tmdb_$tmdbId",
                            priority = NotificationCompat.PRIORITY_HIGH
                        )
                        notifiedIds.add(notifyKey)
                        sentCount++
                    }
                }
            }

            // 3.5 Bounded RSS search check (max 8 per cycle) for Watchlist movies still waiting for an ID date
            val wlRssIter = watchlistObj.keys()
            while (wlRssIter.hasNext() && rssSearchBudget > 0) {
                val wId = wlRssIter.next()
                val entry = watchlistObj.optJSONObject(wId) ?: continue
                val mData = entry.optJSONObject("movieData") ?: continue
                if (!entry.optBoolean("isTba", false)) continue
                if (mData.optString("releaseChannel", "") == "digital" || mData.optString("sourceBadge", "") == "Rilis Digital") continue
                val title = mData.optString("title", "").trim()
                if (title.length < 2) continue

                rssSearchBudget--
                val rssArr = CinemaScraperHelper.fetchMovieNewsRss(title)
                if (rssArr.length() == 0) continue

                var bestDate = ""
                var hasPresaleRss = false
                for (ri in 0 until rssArr.length()) {
                    val rObj = rssArr.optJSONObject(ri) ?: continue
                    val rHead = rObj.optString("title", "")
                    val rSnip = rObj.optString("snippet", "")
                    if (!CinemaScraperHelper.titleMatchesHeadline(title, rHead) && !CinemaScraperHelper.titleMatchesHeadline(title, rSnip)) {
                        continue
                    }
                    if (CinemaScraperHelper.isAffirmativePresaleText("$rHead. $rSnip")) {
                        hasPresaleRss = true
                    }
                    if (bestDate.isEmpty()) {
                        val cand = CinemaScraperHelper.extractIndonesianDateFromText(title, "$rHead. $rSnip", today.year)
                        if (cand.isNotEmpty() && cand >= minCatalogDate && cand <= maxCatalogDate) {
                            bestDate = cand
                        }
                    }
                }

                val chosenPoster = customPosters.optString(wId, mData.optString("poster", ""))
                if (hasPresaleRss && !mData.optBoolean("presale", false)) {
                    mData.put("presale", true)
                    watchlistMutated = true
                }
                if (bestDate.isNotEmpty()) {
                    val prettyNew = try { LocalDate.parse(bestDate).format(fullDateFmt) } catch (_: Exception) { bestDate }
                    entry.put("isTba", false)
                    entry.put("lastKnownReleaseDate", bestDate)
                    entry.put("workerUpdatedAt", nowMs)
                    mData.put("tba", false)
                    mData.put("releaseChannel", "theatrical")
                    mData.put("releaseDate", bestDate)
                    mData.put("sourceBadge", "Radar Bioskop ID")
                    mData.put("confirmedText", "Jadwal Berita ID: $prettyNew")
                    watchlistMutated = true

                    val rssNotifKey = "rss_confirm_${wId}_$bestDate"
                    if (!notifiedIds.contains(rssNotifKey)) {
                        sendMovieNotification(
                            notificationId = rssNotifKey.hashCode(),
                            channelId = MainActivity.CHANNEL_WATCHLIST_URGENT,
                            subLabel = "Rilis ID Terkonfirmasi • Berita",
                            movieTitle = title,
                            bodyText = "Dilaporkan tayang di bioskop ID mulai $prettyNew.",
                            posterUrl = chosenPoster,
                            movieId = wId,
                            priority = NotificationCompat.PRIORITY_HIGH
                        )
                        notifiedIds.add(rssNotifKey)
                        sentCount++
                    }
                }
            }

            // 4. Notify newly confirmed commercial Indonesian releases & re-releases from direct feeds (XXI/CGV/Cinépolis/LSF)
            if (feedEntries.isNotEmpty()) {
                for (fe in feedEntries) {
                    if (sentCount >= 6) break
                    val feTitle = fe.optString("title", "").trim()
                    if (feTitle.isEmpty()) continue
                    val feRel = CinemaScraperHelper.validIso(fe.optString("releaseDateId", ""))
                    val isReRelease = fe.optBoolean("isReRelease", false)
                    val isPresale = fe.optBoolean("presaleActive", false)
                    val feChains = fe.optJSONArray("chains")
                    val hasCommercialChain = (feChains?.length() ?: 0) > 0
                    if (!isReRelease && !isPresale && !hasCommercialChain && feRel.isEmpty()) continue
                    if (feRel.isNotEmpty() && feRel < todayStr) continue
                    if (!isReRelease && feRel.isEmpty() && !isPresale) continue

                    val dedupeKey = "direct_id_${CinemaScraperHelper.normalizeTitleForMatch(feTitle)}_${feRel.ifEmpty { todayStr }}"
                    if (notifiedIds.contains(dedupeKey)) continue

                    val chainNames = ArrayList<String>()
                    if (feChains != null) {
                        for (ci in 0 until feChains.length()) chainNames.add(feChains.getString(ci))
                    }
                    val chainLabel = if (chainNames.isNotEmpty()) chainNames.joinToString(" · ") else fe.optString("source", "Bioskop ID")
                    val prettyDate = if (feRel.isNotEmpty()) {
                        try { LocalDate.parse(feRel).format(fullDateFmt) } catch (_: Exception) { feRel }
                    } else "Tanggal Menyusul"

                    sendMovieNotification(
                        notificationId = dedupeKey.hashCode(),
                        channelId = MainActivity.CHANNEL_WEEKLY_DIGEST,
                        subLabel = when {
                            isReRelease -> "Tayang Ulang Bioskop ID • $chainLabel"
                            isPresale -> "Pre-Sale Bioskop ID • $chainLabel"
                            else -> "Jadwal Baru Bioskop ID • $chainLabel"
                        },
                        movieTitle = feTitle,
                        bodyText = if (isReRelease) "Tayang ulang ($prettyDate) di $chainLabel." else "Jadwal tayang ID: $prettyDate.",
                        posterUrl = "",
                        movieId = "",
                        priority = NotificationCompat.PRIORITY_DEFAULT
                    )
                    notifiedIds.add(dedupeKey)
                    sentCount++
                }
            }

            // 5. Scan High-Hype Theatrical Indonesian Releases from TMDB (strict ID theatrical verification)
            if (apiKey.isNotEmpty()) {
                val endpoint = "https://api.themoviedb.org/3/discover/movie?api_key=$apiKey" +
                    "&region=ID&with_release_type=2|3" +
                    "&release_date.gte=$todayStr&sort_by=popularity.desc&page=1"
                val json = fetchJson(endpoint)
                val results = json?.optJSONArray("results")

                if (results != null) {
                    var radarVerifyCount = 0
                    for (i in 0 until results.length()) {
                        val item = results.getJSONObject(i)
                        val tmdbIdNum = item.optInt("id", 0)
                        if (tmdbIdNum <= 0) continue
                        val id = tmdbIdNum.toString()
                        val title = item.optString("title", "Film Baru")
                        val rawReleaseDate = item.optString("release_date", "").take(10)
                        val posterPath = item.optString("poster_path", "")

                        if (CinemaScraperHelper.isPlaceholderDate(rawReleaseDate) || rawReleaseDate < todayStr || posterPath.isEmpty()) continue

                        val popularity = item.optDouble("popularity", 0.0)
                        if (popularity < 1.5) continue

                        val genreIds = item.optJSONArray("genre_ids")
                        var isDoc = false
                        if (genreIds != null) {
                            for (g in 0 until genreIds.length()) {
                                if (genreIds.optInt(g) == 99) isDoc = true
                            }
                        }
                        if (isDoc && popularity < 12.0) continue

                        val voteAvg = item.optDouble("vote_average", 0.0)
                        val hypeBase = 71.0 + kotlin.math.log10(kotlin.math.max(0.0, popularity) + 1.0) * 12.2 +
                            if (popularity >= 10.0 && voteAvg >= 7.2) 3.0 else 0.0
                        val hypeScore = kotlin.math.min(98, kotlin.math.max(76, kotlin.math.round(hypeBase).toInt()))
                        val shouldCheck = (sentCount < 6 && hypeScore >= minHype && !notifiedIds.contains(id)) || hypeScore >= 86
                        if (!shouldCheck || radarVerifyCount >= 10) continue

                        radarVerifyCount++
                        val verifiedIdDate = fetchIndonesianTheatricalDate(tmdbIdNum, apiKey, todayStr)
                        if (verifiedIdDate.isNullOrEmpty() || CinemaScraperHelper.isPlaceholderDate(verifiedIdDate)) continue

                        val relParsed = try { LocalDate.parse(verifiedIdDate) } catch (_: Exception) { null } ?: continue
                        val daysFromToday = ChronoUnit.DAYS.between(today, relParsed)
                        if (daysFromToday in 0L..7L && hypeScore >= 85) {
                            val line = "${title.uppercase()} (${relParsed.format(shortDateFmt)})"
                            if (!weeklyDigestLines.any { it.startsWith(title.uppercase()) }) {
                                weeklyDigestLines.add(line)
                            }
                        }

                        if (sentCount < 6 && hypeScore >= minHype && !notifiedIds.contains(id)) {
                            val defaultPoster = "https://image.tmdb.org/t/p/w500$posterPath"
                            val chosenPoster = customPosters.optString("tmdb_$id", defaultPoster)
                            val parsedDate = relParsed.format(fullDateFmt)
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

    private fun fetchIndonesianTheatricalDate(tmdbId: Int, apiKey: String, minDate: String = ""): String? {
        val relJson = fetchJson("https://api.themoviedb.org/3/movie/$tmdbId/release_dates?api_key=$apiKey")
            ?: return null
        val results = relJson.optJSONArray("results") ?: return ""
        val type3Dates = mutableListOf<String>()
        val type2Dates = mutableListOf<String>()
        for (j in 0 until results.length()) {
            val countryObj = results.getJSONObject(j)
            if (countryObj.optString("iso_3166_1") == "ID") {
                val rDates = countryObj.optJSONArray("release_dates") ?: continue
                for (k in 0 until rDates.length()) {
                    val rd = rDates.getJSONObject(k)
                    val type = rd.optInt("type", 0)
                    val dateStr = rd.optString("release_date", "").take(10)
                    if (!CinemaScraperHelper.isPlaceholderDate(dateStr)) {
                        if (type == 3) type3Dates.add(dateStr)
                        else if (type == 2) type2Dates.add(dateStr)
                    }
                }
            }
        }
        return if (minDate.isNotEmpty()) {
            type3Dates.filter { it >= minDate }.minOrNull()
                ?: type2Dates.filter { it >= minDate }.minOrNull()
                ?: ""
        } else {
            type3Dates.minOrNull() ?: type2Dates.minOrNull() ?: ""
        }
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
        val minWeeklyGapMs = 6L * 24L * 3600_000L + 12L * 3600_000L
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
        val trimmed = if (list.size > 200) list.takeLast(200) else list
        prefs.edit().putStringSet("notified_movie_ids", HashSet(trimmed)).apply()
    }

    private fun fetchJson(urlStr: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CinemaScraperHelper.HTTP_TIMEOUT_MS
                readTimeout = CinemaScraperHelper.HTTP_TIMEOUT_MS
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
