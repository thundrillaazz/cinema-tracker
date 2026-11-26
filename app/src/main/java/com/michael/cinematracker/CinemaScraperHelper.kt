package com.michael.cinematracker

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object CinemaScraperHelper {
    const val HTTP_TIMEOUT_MS = 8000
    const val CINEMA_FEED_CACHE_TTL_MS = 2L * 3600_000L
    const val RSS_SNIPPET_LIMIT = 1500
    const val MAX_CONCURRENT_REQUESTS = 3
    private const val UA_RADAR = "Mozilla/5.0 (compatible; CinemaTrackerRadar/4.4.0)"
    private const val UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    private const val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val FORMAT_SUFFIX_RE = Regex(
        "\\s*[\\[(]\\s*(?:re-?release|reissue|tayang\\s+(?:ulang|kembali)|remaster(?:ed)?|imax\\s*2d|imax\\s*3d|imax|2d|3d|atmos|dolby\\s*atmos|screenx|4dx|starium|reald\\s*3d)\\s*[\\])]",
        RegexOption.IGNORE_CASE
    )
    private val IMAX_RE = Regex("\\bimax\\b", RegexOption.IGNORE_CASE)
    private val REISSUE_RE = Regex(
        "\\b(re-?release|reissue|tayang\\s+(?:ulang|kembali)|remaster(?:ed)?|encore|4k)\\b",
        RegexOption.IGNORE_CASE
    )
    private val PRESALE_AFFIRMATIVE_RE = Regex(
        "\\b(pre-?sale|presale|penjualan\\s+tiket|open\\s+pre-?sale)\\b",
        RegexOption.IGNORE_CASE
    )
    private val PRESALE_ACTIVE_VERB_RE = Regex(
        "\\b(?:(?:sudah\\s+|telah\\s+|resmi\\s+)?(?:dibuka|tersedia|dijual|bisa\\s+(?:dibeli|dipesan)|dimulai)|buka\\s+penjualan\\s+tiket|mulai\\s+hari\\s+ini|open\\s+pre-?sale|beli\\s+tiket)\\b",
        RegexOption.IGNORE_CASE
    )
    private val RUMOR_RE = Regex(
        "\\b(rumor|rumours?|rumors?|kabarnya|diduga|prediksi|bocoran|diperkirakan|konon|belum\\s+pasti|spekulasi|hoaks?|hoax|fan[-\\s]?made|concept\\s+trailer|estimasi)\\b",
        RegexOption.IGNORE_CASE
    )
    private val STREAMING_ONLY_RE = Regex(
        "\\b(disney\\s*\\+?|netflix|prime\\s*video|amazon|apple\\s*tv|hbo|max|vidio|viu|wetv|iqiyi|catchplay|hotstar)\\b",
        RegexOption.IGNORE_CASE
    )
    private val EXPLICIT_ID_VENUE_RE = Regex(
        "\\b(xxi|cinema\\s*21|cinema\\s*xxi|21cineplex|cgv|cin[eé]polis|tix\\s*id|\\blsf\\b|lulus\\s+sensor|bioskop\\s+(?:di\\s+)?indonesia|di\\s+bioskop\\s+indonesia|seluruh\\s+bioskop\\s+indonesia|jaringan\\s+bioskop\\s+indonesia|tanah\\s+air|di\\s+indonesia)\\b",
        RegexOption.IGNORE_CASE
    )
    private val FOREIGN_WIRE_RE = Regex(
        "\\b(imax\\s*70\\s*mm|70\\s*mm\\s+imax|melansir\\s+(?:variety|deadline|hollywood\\s+reporter|collider|screenrant|indiewire|thewrap)|dilansir\\s+dari\\s+(?:variety|deadline|hollywood\\s+reporter|collider|screenrant|indiewire|thewrap)|via\\s+(?:variety|deadline|hollywood\\s+reporter)|amerika\\s+serikat|bioskop\\s+(?:as|amerika|global|inggris|jepang|korea))\\b",
        RegexOption.IGNORE_CASE
    )
    private val NOISE_RE = Regex(
        "\\b(disney\\s*\\+?|netflix|prime\\s*video|amazon|apple\\s*tv|hbo|max|vidio|viu|wetv|iqiyi|catchplay|hotstar|bioskop\\s+trans\\s*tv|trans\\s*tv|lk21|indoxxi|link\\s+nonton|sub\\s+indo|episode\\s+\\d+|sortir\\s*[aà]\\s*paris)\\b",
        RegexOption.IGNORE_CASE
    )
    private val WORD_NUM_MAP = mapOf(
        "one" to "1", "satu" to "1",
        "two" to "2", "dua" to "2",
        "three" to "3", "tiga" to "3",
        "four" to "4", "empat" to "4",
        "five" to "5", "lima" to "5",
        "six" to "6", "enam" to "6",
        "seven" to "7", "tujuh" to "7",
        "eight" to "8", "delapan" to "8",
        "nine" to "9", "sembilan" to "9",
        "ten" to "10", "sepuluh" to "10",
        "ii" to "2", "iii" to "3", "iv" to "4",
        "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9"
    )
    private val SEQUEL_PREFIXES = setOf("part", "pt", "vol", "volume", "chapter", "bagian", "episode", "bab")
    private val STOP_TOKENS = setOf("the", "and", "for", "with", "part", "pt", "movie", "film", "of", "in", "a", "an")
    private val ID_MONTH_MAP = mapOf(
        "januari" to 1, "jan" to 1,
        "februari" to 2, "feb" to 2,
        "maret" to 3, "mar" to 3,
        "april" to 4, "apr" to 4,
        "mei" to 5, "may" to 5,
        "juni" to 6, "jun" to 6,
        "juli" to 7, "jul" to 7,
        "agustus" to 8, "agu" to 8, "ags" to 8, "aug" to 8,
        "september" to 9, "sep" to 9, "sept" to 9,
        "oktober" to 10, "okt" to 10, "oct" to 10,
        "november" to 11, "nov" to 11,
        "desember" to 12, "des" to 12, "dec" to 12
    )

    fun isPlaceholderDate(dateStr: String?): Boolean {
        val clean = (dateStr ?: "").trim().take(10)
        if (clean.length != 10 || clean == "TBA") return true
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(clean)) return true
        return clean.endsWith("-12-31")
    }

    fun validIso(raw: String?): String {
        val clean = (raw ?: "").trim().take(10)
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(clean) || clean.endsWith("-12-31")) return ""
        return clean
    }

    fun stripCinemaFormatSuffixes(raw: String): String {
        return FORMAT_SUFFIX_RE.replace(raw, " ").replace(Regex("\\s+"), " ").trim()
    }

    fun tokenizeNormalizedTitle(raw: String): List<String> {
        val cleaned = stripCinemaFormatSuffixes(raw)
            .lowercase(Locale.US)
            .replace(Regex("[:\\-–—'\"‘’“”/.,!?()\\[\\]_]+"), " ")
            .replace(Regex("[^a-z0-9\\s]+"), " ")
            .trim()
        if (cleaned.isEmpty()) return emptyList()
        val rawTokens = cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val mapped = rawTokens.mapIndexed { idx, tok ->
            val direct = WORD_NUM_MAP[tok]
            if (direct != null) {
                direct
            } else if (tok == "i" || tok == "v" || tok == "x") {
                val prev = if (idx > 0) rawTokens[idx - 1] else ""
                val isTrailing = rawTokens.size >= 2 && idx == rawTokens.size - 1
                if (SEQUEL_PREFIXES.contains(prev) || isTrailing) {
                    when (tok) {
                        "i" -> "1"
                        "v" -> "5"
                        else -> "10"
                    }
                } else tok
            } else tok
        }
        val out = ArrayList<String>(mapped.size)
        for (i in mapped.indices) {
            val cur = mapped[i]
            val next = if (i + 1 < mapped.size) mapped[i + 1] else ""
            if ((cur == "part" || cur == "pt" || cur == "bagian") && Regex("^\\d+$").matches(next)) {
                continue
            }
            out.add(cur)
        }
        return out
    }

    fun normalizeTitleForMatch(raw: String): String {
        return tokenizeNormalizedTitle(raw).joinToString("")
    }

    private fun extractSequelNumbers(raw: String): Set<String> {
        val tokens = tokenizeNormalizedTitle(raw)
        val nums = LinkedHashSet<String>()
        for (t in tokens) {
            val n = t.toIntOrNull()
            if (n != null && n in 2..15) {
                nums.add(t)
            }
        }
        return nums
    }

    fun sequelMarkersCompatible(titleA: String, titleB: String): Boolean {
        val numsA = extractSequelNumbers(titleA)
        val numsB = extractSequelNumbers(titleB)
        if (numsA.isEmpty() && numsB.isEmpty()) return true
        if (numsA.isEmpty() != numsB.isEmpty()) {
            // Allow movie without number only if headline doesn't carry a 2..15 sequel marker OR reject first movie swallowing sequel
            return false
        }
        return numsA.any { numsB.contains(it) }
    }

    fun titleMatchesHeadline(movieTitle: String, headline: String): Boolean {
        val cleanHeadTokens = tokenizeNormalizedTitle(headline)
        if (cleanHeadTokens.isEmpty()) return false
        if (!sequelMarkersCompatible(movieTitle, headline)) return false
        val cleanHeadJoined = cleanHeadTokens.joinToString("")
        val headTokenSet = cleanHeadTokens.toHashSet()
        val tokens = tokenizeNormalizedTitle(movieTitle)
            .filter { (it.length >= 2 || Regex("^\\d+$").matches(it)) && !STOP_TOKENS.contains(it) }
        if (tokens.isEmpty()) {
            val norm = normalizeTitleForMatch(movieTitle)
            return norm.isNotEmpty() && cleanHeadJoined.contains(norm)
        }
        val hits = tokens.filter { t ->
            if (Regex("^\\d+$").matches(t) || t.length <= 3) headTokenSet.contains(t)
            else headTokenSet.contains(t) || cleanHeadJoined.contains(t)
        }
        val numTokens = tokens.filter { Regex("^\\d+$").matches(it) }
        if (numTokens.isNotEmpty() && !numTokens.all { headTokenSet.contains(it) }) return false
        return if (tokens.size == 1) hits.size == 1 else hits.size >= minOf(2, tokens.size)
    }

    fun isAffirmativePresaleText(text: String): Boolean {
        val txt = text.trim()
        if (txt.isEmpty() || RUMOR_RE.containsMatchIn(txt)) return false
        if (txt.contains("?") || Regex("\\b(kapan|apakah|benarkah|kenapa|berapa)\\b", RegexOption.IGNORE_CASE).containsMatchIn(txt)) {
            return false
        }
        if (Regex("\\b(belum\\s+(?:dibuka|buka|tersedia|dijual)|akan\\s+segera|segera\\s+hadir)\\b", RegexOption.IGNORE_CASE).containsMatchIn(txt)) {
            return false
        }
        return PRESALE_AFFIRMATIVE_RE.containsMatchIn(txt) && PRESALE_ACTIVE_VERB_RE.containsMatchIn(txt)
    }

    fun extractIndonesianDateFromText(movieTitle: String, text: String, defaultYear: Int): String {
        val clean = text.trim()
        if (clean.isEmpty() || RUMOR_RE.containsMatchIn(clean)) return ""
        val clauses = clean.split(Regex("[.!?\\n|•]+")).map { it.trim() }.filter { it.length >= 4 }
        val dateTextRe = Regex(
            "\\b([1-9]|[12]\\d|3[01])(?:\\s*(?:-|–|—|s/d|s\\.d\\.|hingga|sampai)\\s*(?:[1-9]|[12]\\d|3[01]))?\\s+(januari|jan|februari|feb|maret|mar|april|apr|mei|may|juni|jun|juli|jul|agustus|agu|ags|aug|september|sept|sep|oktober|okt|oct|november|nov|desember|des|dec)(?:\\s*,?\\s*(20\\d{2}))?\\b",
            RegexOption.IGNORE_CASE
        )
        val slashDateRe = Regex("\\b([0-3]?\\d)[/-]([01]?\\d)[/-](20\\d{2})\\b")
        val releaseContextRe = Regex(
            "\\b(tayang|rilis|dirilis|mulai|bioskop|xxi|cinema\\s*21|cgv|cin[eé]polis|tix\\s*id|resmi|pre-?sale|imax|hadir)\\b",
            RegexOption.IGNORE_CASE
        )

        var bestScore = -1
        var bestIso = ""
        for (clause in clauses) {
            if (RUMOR_RE.containsMatchIn(clause)) continue
            val matchesTitle = movieTitle.isEmpty() || titleMatchesHeadline(movieTitle, clause)
            val hasContext = releaseContextRe.containsMatchIn(clause)
            if (!matchesTitle && !hasContext) continue
            val baseTier = (if (matchesTitle) 200 else 100) + (if (EXPLICIT_ID_VENUE_RE.containsMatchIn(clause)) 100 else 0)

            for (m in dateTextRe.findAll(clause)) {
                val day = m.groupValues[1].toIntOrNull() ?: continue
                val month = ID_MONTH_MAP[m.groupValues[2].lowercase(Locale.US)] ?: continue
                val yr = m.groupValues[3].toIntOrNull() ?: defaultYear
                if (day !in 1..31 || month !in 1..12) continue
                val iso = validIso(String.format(Locale.US, "%04d-%02d-%02d", yr, month, day))
                if (iso.isEmpty()) continue
                val score = baseTier + (if (hasContext) 30 else 0)
                if (score > bestScore) {
                    bestScore = score
                    bestIso = iso
                }
            }
            for (sm in slashDateRe.findAll(clause)) {
                val day = sm.groupValues[1].toIntOrNull() ?: continue
                val month = sm.groupValues[2].toIntOrNull() ?: continue
                val yr = sm.groupValues[3].toIntOrNull() ?: continue
                if (day !in 1..31 || month !in 1..12) continue
                val iso = validIso(String.format(Locale.US, "%04d-%02d-%02d", yr, month, day))
                if (iso.isEmpty()) continue
                val score = baseTier + (if (hasContext) 20 else 0)
                if (score > bestScore) {
                    bestScore = score
                    bestIso = iso
                }
            }
        }
        return bestIso
    }

    fun decodeEntities(s: String): String {
        return s.replace(Regex("<!\\[CDATA\\[([\\s\\S]*?)]]>"), "$1")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace(Regex("&nbsp;", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun fetchTextBounded(
        urlStr: String,
        ua: String = UA_MOBILE,
        timeoutMs: Int = HTTP_TIMEOUT_MS,
        maxBytes: Int = 327680
    ): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", ua)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json,application/xml")
            }
            if (conn.responseCode !in 200..299) return ""
            val buf = ByteArray(maxBytes)
            var total = 0
            conn.inputStream.use { input ->
                while (total < maxBytes) {
                    val read = input.read(buf, total, maxBytes - total)
                    if (read <= 0) break
                    total += read
                }
            }
            String(buf, 0, total, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            ""
        } finally {
            conn?.disconnect()
        }
    }

    fun fetchMovieNewsRss(movieTitle: String): JSONArray {
        val cleanTitle = movieTitle.trim()
            .replace(Regex("[:\\-–—'\"‘’“”.,!?()\\[\\]]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(100)
        if (cleanTitle.length < 2) return JSONArray()

        val bingQuery = "\"$cleanTitle\" jadwal tayang bioskop Indonesia XXI CGV"
        val googleQuery = "\"$cleanTitle\" (XXI OR CGV OR Cinepolis OR bioskop OR LSF OR \"pre-sale\" OR IMAX OR tayang) when:120d"
        val urls = listOf(
            "https://www.bing.com/news/search?q=${Uri.encode(bingQuery)}&cc=ID&setlang=id&format=rss",
            "https://news.google.com/rss/search?q=${Uri.encode(googleQuery)}&hl=id&gl=ID&ceid=ID:id"
        )
        val xmlBodies = fetchUrlsConcurrently(urls, UA_RADAR, maxBytes = 262144)

        val seenLinks = HashSet<String>()
        val items = JSONArray()
        for (xml in xmlBodies) {
            if (xml.isEmpty()) continue
            parseXmlRssItems(xml, maxPerFeed = 6, maxTotal = 10, items = items, seenLinks = seenLinks, requireIdVenue = false)
        }
        return items
    }

    fun fetchIndonesianReissueNewsRss(): JSONArray {
        val q1 =
            "(\"tayang kembali\" OR \"kembali tayang\" OR \"tayang ulang\" OR \"re-release\" OR \"reissue\" OR \"remastered\") (XXI OR CGV OR Cinepolis OR \"bioskop Indonesia\" OR IMAX)"
        val q2 = "(\"kembali ke bioskop\" OR \"tayang lagi di bioskop\") (XXI OR CGV OR Cinepolis OR IMAX)"
        val q3 = "(\"jadwal film bioskop\" OR \"film tayang di bioskop\") (\"reissue\" OR \"tayang ulang\" OR \"tayang kembali\")"
        val urls = listOf(
            "https://news.google.com/rss/search?q=${Uri.encode("$q1 when:45d")}&hl=id&gl=ID&ceid=ID:id",
            "https://news.google.com/rss/search?q=${Uri.encode("$q2 when:45d")}&hl=id&gl=ID&ceid=ID:id",
            "https://news.google.com/rss/search?q=${Uri.encode("$q3 when:45d")}&hl=id&gl=ID&ceid=ID:id",
            "https://www.bing.com/news/search?q=${Uri.encode(q1)}&cc=ID&setlang=id&format=rss",
            "https://www.bing.com/news/search?q=${Uri.encode(q2)}&cc=ID&setlang=id&format=rss",
            "https://www.bing.com/news/search?q=${Uri.encode(q3)}&cc=ID&setlang=id&format=rss"
        )
        val xmlBodies = fetchUrlsConcurrently(urls, UA_RADAR, maxBytes = 262144)

        val seenLinks = HashSet<String>()
        val items = JSONArray()
        for (xml in xmlBodies) {
            if (xml.isEmpty()) continue
            parseXmlRssItems(xml, maxPerFeed = 10, maxTotal = 36, items = items, seenLinks = seenLinks, requireIdVenue = true)
        }
        return items
    }

    private fun parseXmlRssItems(
        xml: String,
        maxPerFeed: Int,
        maxTotal: Int,
        items: JSONArray,
        seenLinks: MutableSet<String>,
        requireIdVenue: Boolean
    ) {
        val nowMs = System.currentTimeMillis()
        val maxAgeMs = 45L * 24L * 3600_000L
        val rfcFmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            var inItem = false
            var curTitle = ""
            var curLink = ""
            var curSource = ""
            var curPubDate = ""
            var curSnippet = ""
            var added = 0
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT && added < maxPerFeed && items.length() < maxTotal) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name ?: ""
                        if (tag == "item") {
                            inItem = true
                            curTitle = ""
                            curLink = ""
                            curSource = ""
                            curPubDate = ""
                            curSnippet = ""
                        } else if (inItem) {
                            when (tag) {
                                "title" -> curTitle = parser.nextText().trim()
                                "link" -> curLink = parser.nextText().trim()
                                "source", "news:Source", "Source" -> curSource = parser.nextText().trim()
                                "pubDate" -> curPubDate = parser.nextText().trim()
                                "description" -> {
                                    curSnippet = decodeEntities(parser.nextText()).take(RSS_SNIPPET_LIMIT)
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "item" && inItem) {
                            val recentEnough = if (!requireIdVenue || curPubDate.isBlank()) true else try {
                                val parsed = rfcFmt.parse(curPubDate.trim())
                                parsed == null || (nowMs - parsed.time) <= maxAgeMs
                            } catch (_: Exception) {
                                true
                            }
                            if (recentEnough && curTitle.isNotEmpty() && curLink.startsWith("http") && !seenLinks.contains(curLink)) {
                                val fallbackHost = try {
                                    URL(curLink).host.removePrefix("www.")
                                } catch (_: Exception) {
                                    ""
                                }
                                val resolvedSource = curSource.ifEmpty { fallbackHost }
                                val combinedText = "$curTitle $curSnippet $resolvedSource"
                                val passVenueFilter = if (requireIdVenue) {
                                    val hasIdVenue = EXPLICIT_ID_VENUE_RE.containsMatchIn(combinedText)
                                    val isForeignOnly = FOREIGN_WIRE_RE.containsMatchIn(combinedText) && !hasIdVenue
                                    hasIdVenue && !isForeignOnly && !NOISE_RE.containsMatchIn(combinedText) && !NOISE_RE.containsMatchIn(curLink)
                                } else {
                                    !STREAMING_ONLY_RE.containsMatchIn(resolvedSource) && !STREAMING_ONLY_RE.containsMatchIn(curLink)
                                }
                                if (passVenueFilter) {
                                    seenLinks.add(curLink)
                                    items.put(JSONObject().apply {
                                        put("title", curTitle)
                                        put("snippet", curSnippet)
                                        put("link", curLink)
                                        put("source", resolvedSource)
                                        put("pubDate", curPubDate)
                                    })
                                    added++
                                }
                            }
                            inItem = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
            // best-effort single feed
        }
    }

    fun resolveArticleScheduleText(articleUrl: String): JSONObject {
        val empty = JSONObject().apply {
            put("url", "")
            put("text", "")
        }
        val cleanUrl = articleUrl.trim()
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) return empty

        val targetUrl = resolveGoogleNewsUrl(cleanUrl)
        if (targetUrl.isEmpty() || targetUrl.contains("news.google.com/")) return empty
        val html = fetchTextBounded(targetUrl, UA_MOBILE, HTTP_TIMEOUT_MS, maxBytes = 131072)
        if (html.isEmpty()) return empty
        val structured = html
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("</(?:t[dh]|td|th)>", RegexOption.IGNORE_CASE), " · ")
            .replace(Regex("</(?:tr|li|h[1-6]|p|div|section|article|blockquote)>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&nbsp;", RegexOption.IGNORE_CASE), " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace(Regex("&#39;|&apos;", RegexOption.IGNORE_CASE), "'")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n[ \\t]+"), "\n")
            .replace(Regex("\\n{2,}"), "\n")
            .trim()
            .take(14000)

        return JSONObject().apply {
            put("url", targetUrl)
            put("text", structured)
        }
    }

    private fun resolveGoogleNewsUrl(gUrl: String): String {
        if (!gUrl.contains("news.google.com/")) return gUrl
        var conn: HttpURLConnection? = null
        try {
            val splashHtml = fetchTextBounded(gUrl, UA_DESKTOP, HTTP_TIMEOUT_MS, maxBytes = 131072)
            val sgM = Regex("data-n-a-sg=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(splashHtml)
            val tsM = Regex("data-n-a-ts=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(splashHtml)
            val idM = Regex("data-n-a-id=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(splashHtml)
            if (sgM == null || tsM == null || idM == null) return ""
            val tsNum = tsM.groupValues[1].toLongOrNull()
            val innerArr = JSONArray().apply {
                put("garturlreq")
                put(JSONArray("[[\"X\",\"X\",[\"X\",\"X\"],null,null,1,1,\"ID:id\",null,1,null,null,null,null,null,0,1],\"X\",\"X\",1,[1,1,1],1,1,null,0,0,null,0]"))
                put(idM.groupValues[1])
                if (tsNum != null) put(tsNum) else put(tsM.groupValues[1])
                put(sgM.groupValues[1])
            }.toString()
            val outerArr = JSONArray().apply {
                put(JSONArray().apply {
                    put(JSONArray().apply {
                        put("Fbv4je")
                        put(innerArr)
                        put(JSONObject.NULL)
                        put("generic")
                    })
                })
            }.toString()
            val postBody = "f.req=${Uri.encode(outerArr)}"
            conn = (URL("https://news.google.com/_/DotsSplashUi/data/batchexecute").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                setRequestProperty("User-Agent", UA_DESKTOP)
            }
            conn.outputStream.use { it.write(postBody.toByteArray(StandardCharsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return ""
            val respText = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            for (line in respText.split('\n')) {
                val trimmed = line.trim()
                if (!trimmed.startsWith("[")) continue
                try {
                    val parsedLine = JSONArray(trimmed)
                    val first = parsedLine.optJSONArray(0)
                    if (first != null && first.optString(0) == "wrb.fr") {
                        val inner = JSONArray(first.optString(2))
                        val resolved = inner.optString(1, "")
                        if (inner.optString(0) == "garturlres" && resolved.startsWith("http")) {
                            return resolved
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        } finally {
            conn?.disconnect()
        }
        return ""
    }

    private fun fetchUrlsConcurrently(
        urls: List<String>,
        ua: String = UA_MOBILE,
        maxBytes: Int = 327680
    ): List<String> {
        if (urls.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(MAX_CONCURRENT_REQUESTS)
        return try {
            val futures = urls.map { targetUrl ->
                pool.submit(Callable {
                    fetchTextBounded(targetUrl, ua, HTTP_TIMEOUT_MS, maxBytes)
                })
            }
            futures.map { f ->
                try {
                    f.get(HTTP_TIMEOUT_MS.toLong() + 3000L, TimeUnit.MILLISECONDS)
                } catch (_: Exception) {
                    ""
                }
            }
        } finally {
            pool.shutdown()
        }
    }

    fun fetchIndonesianCinemaFeedsJson(context: Context, forceRefresh: Boolean = false): String {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val cachedJson = prefs.getString("ct_native_cinema_id_json", "") ?: ""
        val cachedTs = prefs.getLong("ct_native_cinema_id_ts", 0L)
        if (!forceRefresh && cachedJson.isNotEmpty() && cachedTs > 0L && (System.currentTimeMillis() - cachedTs) < CINEMA_FEED_CACHE_TTL_MS) {
            return cachedJson
        }

        val todayIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
        val currentYear = todayIso.take(4).toIntOrNull() ?: 2026
        val byKey = LinkedHashMap<String, JSONObject>()

        fun upsertFeedEntry(
            rawTitle: String,
            releaseDateId: String = "",
            status: String = "upcoming",
            chainsList: List<String> = emptyList(),
            imax: Boolean = false,
            presaleActive: Boolean = false,
            isReRelease: Boolean = false,
            tahunProduksi: Int = 0,
            lsfStatus: String = "",
            ageRating: String = "",
            source: String = "",
            sourceUrl: String = ""
        ) {
            val hasImax = imax || IMAX_RE.containsMatchIn(rawTitle)
            val hasReissue = isReRelease || REISSUE_RE.containsMatchIn(rawTitle)
            val clean = stripCinemaFormatSuffixes(rawTitle)
            val key = normalizeTitleForMatch(clean)
            if (clean.length < 2 || key.isEmpty()) return

            val existing = byKey[key]
            if (existing == null) {
                val obj = JSONObject().apply {
                    put("title", clean.take(90))
                    if (releaseDateId.isNotEmpty()) put("releaseDateId", releaseDateId)
                    put("status", status)
                    put("chains", JSONArray(chainsList.distinct()))
                    put("imax", hasImax)
                    if (presaleActive) put("presaleActive", true)
                    if (hasReissue) put("isReRelease", true)
                    if (tahunProduksi > 0) put("tahunProduksi", tahunProduksi)
                    if (lsfStatus.isNotEmpty()) put("lsfStatus", lsfStatus)
                    if (ageRating.isNotEmpty()) put("ageRating", ageRating)
                    put("source", source)
                    if (sourceUrl.isNotEmpty()) put("sourceUrl", sourceUrl)
                }
                byKey[key] = obj
            } else {
                if (existing.optString("releaseDateId", "").isEmpty() && releaseDateId.isNotEmpty()) {
                    existing.put("releaseDateId", releaseDateId)
                }
                if (status == "now") existing.put("status", "now")
                val chainSet = LinkedHashSet<String>()
                val oldArr = existing.optJSONArray("chains")
                if (oldArr != null) {
                    for (i in 0 until oldArr.length()) chainSet.add(oldArr.getString(i))
                }
                chainSet.addAll(chainsList)
                existing.put("chains", JSONArray(chainSet.toList()))
                existing.put("imax", existing.optBoolean("imax", false) || hasImax)
                if (presaleActive) existing.put("presaleActive", true)
                if (hasReissue) existing.put("isReRelease", true)
                if (existing.optInt("tahunProduksi", 0) == 0 && tahunProduksi > 0) existing.put("tahunProduksi", tahunProduksi)
                if (existing.optString("lsfStatus", "").isEmpty() && lsfStatus.isNotEmpty()) existing.put("lsfStatus", lsfStatus)
                if (existing.optString("ageRating", "").isEmpty() && ageRating.isNotEmpty()) existing.put("ageRating", ageRating)
                if (existing.optJSONArray("chains")?.length() == 0 && chainsList.isNotEmpty() && source.isNotEmpty()) {
                    existing.put("source", source)
                }
                if (existing.optString("sourceUrl", "").isEmpty() && sourceUrl.isNotEmpty()) {
                    existing.put("sourceUrl", sourceUrl)
                }
            }
        }

        // Fetch primary commercial cinema pages with bounded 3-thread pool & 8s timeout
        val primarySpecs = listOf(
            "https://m.21cineplex.com/",
            "https://m.21cineplex.com/gui.coming_soon.php?order=2",
            "https://www.cgv.id/",
            "https://www.cgv.id/movies",
            "https://www.cgv.id/en/movies/now_playing",
            "https://www.tix.id/",
            "https://jadwalnonton.com/",
            "https://jadwalnonton.com/comingsoon/",
            "https://lsf.go.id/film",
            "https://lsf.go.id/film?page=2"
        )
        val htmlResults = fetchUrlsConcurrently(primarySpecs, UA_MOBILE, maxBytes = 327680)
        val pageMap = primarySpecs.zip(htmlResults).toMap()

        // 1. Cinema XXI (m.21cineplex.com/ & gui.coming_soon.php?order=2)
        val xxiTargets = listOf(
            "https://m.21cineplex.com/" to "now",
            "https://m.21cineplex.com/gui.coming_soon.php?order=2" to "upcoming"
        )
        val xxiPatterns = listOf(
            Regex("<a[^>]+href=[\"'][^\"']*(?:movie_details|gui\\.movie_details|movie_id=)[^\"']*[\"'][^>]*>[\\s\\S]{0,450}?<(?:img[^>]+alt=[\"']([^\"']{2,90})[\"']|div[^>]*class=[\"'][^\"']*(?:title|grid_title)[^\"']*[\"'][^>]*>([^<]{2,90})<|h[1-5][^>]*>([^<]{2,90})<)", RegexOption.IGNORE_CASE),
            Regex("data-movie-title=[\"']([^\"']{2,90})[\"']", RegexOption.IGNORE_CASE),
            Regex("class=[\"'][^\"']*movie-title[^\"']*[\"'][^>]*>([^<]{2,90})<", RegexOption.IGNORE_CASE)
        )
        val xxiNoiseRe = Regex("^(?:cinema\\s*21|cinema\\s*xxi|21cineplex|now\\s+playing|coming\\s+soon|theaters?|imax|premiere|mtix|home|login)$", RegexOption.IGNORE_CASE)
        for ((xUrl, xStatus) in xxiTargets) {
            val html = pageMap[xUrl] ?: ""
            if (html.isEmpty()) continue
            var count = 0
            for (rx in xxiPatterns) {
                for (m in rx.findAll(html)) {
                    val cand = decodeEntities(m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: "")
                    if (cand.length < 2 || xxiNoiseRe.matches(cand)) continue
                    val startCtx = maxOf(0, m.range.first - 60)
                    val endCtx = minOf(html.length, m.range.last + 220)
                    val snippetCtx = decodeEntities(html.substring(startCtx, endCtx))
                    val relIso = extractIndonesianDateFromText("", snippetCtx, currentYear)
                    val isPre = PRESALE_AFFIRMATIVE_RE.containsMatchIn("$cand $snippetCtx") &&
                        (xStatus == "upcoming" || PRESALE_ACTIVE_VERB_RE.containsMatchIn(snippetCtx))
                    upsertFeedEntry(
                        rawTitle = cand,
                        releaseDateId = relIso,
                        status = xStatus,
                        chainsList = listOf("XXI"),
                        presaleActive = isPre,
                        source = "Cinema XXI",
                        sourceUrl = xUrl
                    )
                    count++
                    if (count >= 60) break
                }
                if (count >= 60) break
            }
        }

        // 2. CGV Indonesia (cgv.id, /movies, /en/movies/now_playing — RSC JSON + HTML cards for presale, anime & live viewing)
        val cgvUrls = listOf(
            "https://www.cgv.id/en/movies/now_playing",
            "https://www.cgv.id/movies",
            "https://www.cgv.id/"
        )
        val cgvHtmlLinkRe = Regex(
            "<a[^>]+href=[\"']([^\"']*(?:/movies/info/|/en/movies/detail/|/movies/detail/)[^\"']*)[\"'][^>]*>([\\s\\S]{0,420}?)</a>",
            RegexOption.IGNORE_CASE
        )
        val cgvAltRe = Regex("alt=[\"']([^\"']{2,90})[\"']", RegexOption.IGNORE_CASE)
        for (cUrl in cgvUrls) {
            val cgvHtml = pageMap[cUrl] ?: ""
            if (cgvHtml.isEmpty()) continue
            val pushRe = Regex("self\\.__next_f\\.push\\(\\[1,\"([\\s\\S]*?)\"\\]\\)")
            val decodedBuf = StringBuilder()
            for (pm in pushRe.findAll(cgvHtml)) {
                try {
                    decodedBuf.append(JSONObject("{\"s\":\"${pm.groupValues[1]}\"}").getString("s"))
                } catch (_: Exception) {}
            }
            val decoded = decodedBuf.toString()
            if (decoded.isNotEmpty()) {
                fun extractBalancedArr(keyRe: Regex): JSONArray? {
                    val km = keyRe.find(decoded) ?: return null
                    val start = decoded.indexOf('[', km.range.last)
                    if (start < 0) return null
                    var depth = 0
                    var inStr = false
                    var esc = false
                    for (i in start until decoded.length) {
                        val ch = decoded[i]
                        if (inStr) {
                            if (esc) esc = false
                            else if (ch == '\\') esc = true
                            else if (ch == '"') inStr = false
                        } else {
                            when (ch) {
                                '"' -> inStr = true
                                '[' -> depth++
                                ']' -> {
                                    depth--
                                    if (depth == 0) {
                                        return try { JSONArray(decoded.substring(start, i + 1)) } catch (_: Exception) { null }
                                    }
                                }
                            }
                        }
                    }
                    return null
                }

                fun consumeCgvArr(arr: JSONArray?, st: String) {
                    if (arr == null) return
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val rawName = item.optString("name", item.optString("title", "")).trim()
                        if (rawName.isEmpty()) continue
                        val hideDate = item.optBoolean("hide_opening_date", false)
                        val openIso = if (!hideDate) validIso(item.optString("opening_date", "")) else ""
                        val typeStr = item.optString("type", "")
                        val statusTag = item.optString("status", "")
                        val rawAge = item.optString("censor_rating", "").uppercase(Locale.US).trim()
                        val mappedAge = when {
                            rawAge == "SU" || rawAge.contains("SEMUA") -> "SU"
                            rawAge.contains("21") -> "D 21+"
                            rawAge.contains("17") -> "D 17+"
                            rawAge.contains("13") -> "R 13+"
                            else -> ""
                        }
                        val isPre = item.optBoolean("is_presale", false) ||
                            PRESALE_AFFIRMATIVE_RE.containsMatchIn("$rawName $typeStr $statusTag")
                        upsertFeedEntry(
                            rawTitle = rawName,
                            releaseDateId = openIso,
                            status = st,
                            chainsList = listOf("CGV"),
                            imax = IMAX_RE.containsMatchIn("$rawName $typeStr"),
                            presaleActive = isPre,
                            ageRating = mappedAge,
                            source = "CGV Indonesia",
                            sourceUrl = cUrl
                        )
                    }
                }
                consumeCgvArr(extractBalancedArr(Regex("\"moviePlayings\"\\s*:\\s*\\[")), "now")
                consumeCgvArr(extractBalancedArr(Regex("\"movieUpcomings\"\\s*:\\s*\\[")), "upcoming")
                consumeCgvArr(extractBalancedArr(Regex("\"presales\"\\s*:\\s*\\[")), "upcoming")
            }

            // Fallback HTML link/card extraction (presale, anime, live viewing)
            for (lm in cgvHtmlLinkRe.findAll(cgvHtml)) {
                val href = lm.groupValues[1]
                val innerRaw = lm.groupValues[2]
                val altM = cgvAltRe.find(innerRaw)
                val rawTitle = decodeEntities(altM?.groupValues?.get(1) ?: innerRaw)
                    .replace(Regex("^(?:buy\\s+ticket|beli\\s+tiket|pre-?sale|coming\\s+soon|now\\s+playing)\\s*", RegexOption.IGNORE_CASE), "")
                    .trim()
                if (rawTitle.length !in 2..90) continue
                val startCtx = maxOf(0, lm.range.first - 80)
                val endCtx = minOf(cgvHtml.length, lm.range.last + 220)
                val ctx = decodeEntities(cgvHtml.substring(startCtx, endCtx))
                val relIso = extractIndonesianDateFromText("", ctx, currentYear)
                val isUpcoming = relIso > todayIso || Regex("coming[-_\\s]?soon|upcoming|pre-?sale", RegexOption.IGNORE_CASE).containsMatchIn("$href $ctx")
                val absUrl = if (href.startsWith("http")) href else "https://www.cgv.id$href"
                upsertFeedEntry(
                    rawTitle = rawTitle,
                    releaseDateId = relIso,
                    status = if (isUpcoming) "upcoming" else "now",
                    chainsList = listOf("CGV"),
                    presaleActive = PRESALE_AFFIRMATIVE_RE.containsMatchIn("$rawTitle $ctx"),
                    source = "CGV Indonesia",
                    sourceUrl = absUrl
                )
            }
        }

        // 3. TIX ID (tix.id)
        val tixHtml = pageMap["https://www.tix.id/"] ?: ""
        if (tixHtml.isNotEmpty()) {
            val tixRe = Regex("href=[\"'](https?://(?:app\\.)?tix\\.id/movies/([a-z0-9-]+)-\\d+/(\\d{4}-\\d{2}-\\d{2}))[\"'][^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE)
            var tixCount = 0
            for (m in tixRe.findAll(tixHtml)) {
                val url = m.groupValues[1]
                val slug = m.groupValues[2]
                val relIso = validIso(m.groupValues[3])
                val rawInner = decodeEntities(m.groupValues[4])
                val isPre = PRESALE_AFFIRMATIVE_RE.containsMatchIn(rawInner)
                val inner = rawInner
                    .replace(Regex("^(?:beli\\s+tiket|pre-?sale|coming\\s+soon|now\\s+playing)\\s*", RegexOption.IGNORE_CASE), "")
                    .trim()
                val rawTitle = if (inner.length in 2..80 && !Regex("^(?:lihat|detail|trailer)", RegexOption.IGNORE_CASE).containsMatchIn(inner)) {
                    inner
                } else {
                    slug.replace('-', ' ')
                }
                upsertFeedEntry(
                    rawTitle = rawTitle,
                    releaseDateId = relIso,
                    status = if (relIso.isNotEmpty() && relIso > todayIso) "upcoming" else "now",
                    chainsList = listOf("TIX ID"),
                    presaleActive = isPre,
                    source = "TIX ID",
                    sourceUrl = url
                )
                tixCount++
                if (tixCount >= 40) break
            }
        }

        // 4. Jadwalnonton.com (captures Cinépolis + XXI + CGV schedules & upcoming releases)
        val jnSpecs = listOf(
            "https://jadwalnonton.com/" to "now",
            "https://jadwalnonton.com/comingsoon/" to "upcoming"
        )
        val jnLinkRe = Regex(
            "<a[^>]+href=[\"']([^\"']*/film/[^\"']+|https?://jadwalnonton\\.com/[^\"']+/movie/[^\"']+)[\"'][^>]*>([\\s\\S]{0,420}?)</a>",
            RegexOption.IGNORE_CASE
        )
        val jnAltRe = Regex("(?:alt|title)=[\"']([^\"']{2,90})[\"']", RegexOption.IGNORE_CASE)
        val jnNoiseRe = Regex("^(?:jadwalnonton|now\\s+playing|coming\\s+soon|bioskop|xxi|cgv|cin[eé]polis|lihat\\s+jadwal|selengkapnya)$", RegexOption.IGNORE_CASE)
        for ((jUrl, jStatus) in jnSpecs) {
            val jHtml = pageMap[jUrl] ?: ""
            if (jHtml.isEmpty()) continue
            var jCount = 0
            for (jm in jnLinkRe.findAll(jHtml)) {
                val href = jm.groupValues[1]
                val innerRaw = jm.groupValues[2]
                val altM = jnAltRe.find(innerRaw)
                val candidate = decodeEntities(altM?.groupValues?.get(1) ?: innerRaw)
                    .replace(Regex("^(?:nonton|jadwal|film)\\s+", RegexOption.IGNORE_CASE), "")
                    .trim()
                if (candidate.length !in 2..90 || jnNoiseRe.matches(candidate)) continue
                val startCtx = maxOf(0, jm.range.first - 60)
                val endCtx = minOf(jHtml.length, jm.range.last + 260)
                val ctx = decodeEntities(jHtml.substring(startCtx, endCtx))
                val relIso = extractIndonesianDateFromText("", ctx, currentYear)
                val jChains = ArrayList<String>()
                if (Regex("\\b(?:xxi|cinema\\s*21|cinema\\s*xxi)\\b", RegexOption.IGNORE_CASE).containsMatchIn(ctx)) jChains.add("XXI")
                if (Regex("\\bcgv\\b", RegexOption.IGNORE_CASE).containsMatchIn(ctx)) jChains.add("CGV")
                if (Regex("cin[eé]polis|cinemaxx", RegexOption.IGNORE_CASE).containsMatchIn(ctx)) jChains.add("Cinépolis")
                val absUrl = if (href.startsWith("http")) href else "https://jadwalnonton.com$href"
                upsertFeedEntry(
                    rawTitle = candidate,
                    releaseDateId = relIso,
                    status = if (relIso.isNotEmpty() && relIso > todayIso) "upcoming" else jStatus,
                    chainsList = jChains,
                    presaleActive = PRESALE_AFFIRMATIVE_RE.containsMatchIn(ctx),
                    source = "Jadwalnonton",
                    sourceUrl = absUrl
                )
                jCount++
                if (jCount >= 45) break
            }
        }

        // 5. LSF RI (lsf.go.id/film) with persistent cache & max-3 concurrent detail fetches
        val lsfPages = listOf(pageMap["https://lsf.go.id/film"] ?: "", pageMap["https://lsf.go.id/film?page=2"] ?: "")
        val lsfCardRe = Regex("href=[\"'](https?://lsf\\.go\\.id/film/[^\"'/]+/(\\d+))[\"'][^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE)
        val seenLsfIds = HashSet<String>()
        val lsfCacheObj = try {
            JSONObject(prefs.getString("ct_lsf_detail_cache_json", "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        data class LsfCard(val id: String, val title: String, val url: String)
        val lsfCards = ArrayList<LsfCard>()
        for (pageHtml in lsfPages) {
            if (pageHtml.isEmpty()) continue
            for (cm in lsfCardRe.findAll(pageHtml)) {
                if (lsfCards.size >= 24) break
                val url = cm.groupValues[1]
                val lsfId = cm.groupValues[2]
                if (seenLsfIds.contains(lsfId)) continue
                val txt = decodeEntities(cm.groupValues[3])
                if (txt.length < 2) continue
                val cleanLsfTitle = txt
                    .replace(Regex("\\b(SU|R\\s*13\\+|D\\s*17\\+|D\\s*21\\+|Bioskop|TV|Streaming|IKLAN)\\b", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (cleanLsfTitle.length < 2) continue
                seenLsfIds.add(lsfId)
                lsfCards.add(LsfCard(lsfId, cleanLsfTitle, url))
            }
        }

        val uncachedLsf = lsfCards.filter { !lsfCacheObj.has(it.id) }.take(8)
        var lsfCacheDirty = false
        if (uncachedLsf.isNotEmpty()) {
            val detailHtmls = fetchUrlsConcurrently(uncachedLsf.map { it.url }, UA_MOBILE, maxBytes = 120000)
            val yrRe = Regex("Tahun\\s+Produksi\\s*:?\\s*((?:19|20)\\d{2})", RegexOption.IGNORE_CASE)
            val dtRe = Regex("Tanggal\\s+Tayang\\s*:?\\s*(\\d{1,2})[/-](\\d{1,2})[/-](20\\d{2})", RegexOption.IGNORE_CASE)
            val clsRe = Regex("Klasifikasi(?:\\s+Usia)?\\s*:?\\s*(SU|R\\s*13\\+|D\\s*17\\+|D\\s*21\\+)", RegexOption.IGNORE_CASE)
            val medRe = Regex("Media\\s*:?\\s*([A-Za-z\\s]{2,20})", RegexOption.IGNORE_CASE)

            uncachedLsf.forEachIndexed { idx, card ->
                val detHtml = detailHtmls.getOrNull(idx) ?: ""
                if (detHtml.isEmpty()) return@forEachIndexed
                val detTxt = decodeEntities(detHtml)
                val medM = medRe.find(detTxt)
                val isNonCinema = medM != null &&
                    !Regex("bioskop|teater|layar", RegexOption.IGNORE_CASE).containsMatchIn(medM.groupValues[1]) &&
                    Regex("tv|iklan|streaming", RegexOption.IGNORE_CASE).containsMatchIn(medM.groupValues[1])
                val newDet = JSONObject()
                if (isNonCinema) {
                    newDet.put("skip", true)
                } else {
                    val yrM = yrRe.find(detTxt)
                    val dtM = dtRe.find(detTxt)
                    val clsM = clsRe.find(detTxt)
                    val prodYr = yrM?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val dIso = if (dtM != null) {
                        validIso("${dtM.groupValues[3]}-${dtM.groupValues[2].padStart(2, '0')}-${dtM.groupValues[1].padStart(2, '0')}")
                    } else ""
                    val ageR = (clsM?.groupValues?.get(1) ?: "").replace(Regex("\\s+"), " ").trim()
                    if (prodYr > 0) newDet.put("tahunProduksi", prodYr)
                    if (dIso.isNotEmpty()) newDet.put("releaseDateId", dIso)
                    if (ageR.isNotEmpty()) newDet.put("ageRating", ageR)
                    newDet.put("lsfStatus", if (ageR.isNotEmpty()) "Lulus Sensor LSF ($ageR)" else "Lulus Sensor LSF RI")
                }
                lsfCacheObj.put(card.id, newDet)
                lsfCacheDirty = true
            }
        }

        for (card in lsfCards) {
            val det = lsfCacheObj.optJSONObject(card.id) ?: continue
            if (det.optBoolean("skip", false)) continue
            val dIso = validIso(det.optString("releaseDateId", ""))
            val prodYr = det.optInt("tahunProduksi", 0)
            val lsfStatus = det.optString("lsfStatus", "")
            val ageRating = det.optString("ageRating", "")
            if (dIso.isNotEmpty() || lsfStatus.isNotEmpty()) {
                val isRe = prodYr in 1901 until (currentYear - 1)
                upsertFeedEntry(
                    rawTitle = card.title,
                    releaseDateId = dIso,
                    status = if (dIso.isNotEmpty() && dIso <= todayIso) "now" else "upcoming",
                    isReRelease = isRe,
                    tahunProduksi = prodYr,
                    lsfStatus = lsfStatus,
                    ageRating = ageRating,
                    source = "LSF RI",
                    sourceUrl = card.url
                )
            }
        }
        if (lsfCacheDirty) {
            prefs.edit().putString("ct_lsf_detail_cache_json", lsfCacheObj.toString()).apply()
        }

        val payload = JSONObject().apply {
            put("updatedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(java.util.Date()))
            put("entries", JSONArray(byKey.values.toList()))
        }.toString()

        if (byKey.isNotEmpty()) {
            prefs.edit()
                .putString("ct_native_cinema_id_json", payload)
                .putLong("ct_native_cinema_id_ts", System.currentTimeMillis())
                .apply()
        }
        return payload
    }
}
