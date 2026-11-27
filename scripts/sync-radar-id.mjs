import fs from 'fs';
import path from 'path';
import { GoogleGenAI } from '@google/genai';

const ROOT_DIR = process.cwd();
const OUT_PATH = path.join(ROOT_DIR, 'public', 'radar-id.json');

function normalizeKey(str) {
  return String(str || '').toLowerCase().replace(/[^a-z0-9]/g, '');
}

function sanitizeIso(raw) {
  const clean = String(raw || '').trim().slice(0, 10);
  return /^\d{4}-\d{2}-\d{2}$/.test(clean) ? clean : '';
}

function normalizeChains(raw) {
  if (!Array.isArray(raw)) return [];
  const set = new Set();
  for (const item of raw) {
    const s = String(item || '').toLowerCase();
    if (s.includes('xxi') || s.includes('21')) set.add('XXI');
    if (s.includes('cgv')) set.add('CGV');
    if (s.includes('cinepolis') || s.includes('cinépolis')) set.add('Cinépolis');
  }
  return Array.from(set);
}

function detectChainsFromText(text) {
  const s = String(text || '').toLowerCase();
  const out = [];
  if (/\b(xxi|cinema\s*21|cinema\s*xxi|21cineplex)\b/i.test(s)) out.push('XXI');
  if (/\bcgv\b/i.test(s)) out.push('CGV');
  if (/cin[eé]polis/i.test(s)) out.push('Cinépolis');
  return out;
}

function decodeXmlEntities(str) {
  return String(str || '')
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'")
    .trim();
}

function parseJsonPayload(rawText) {
  if (!rawText) return null;
  const fenced = /```(?:json)?\s*([\s\S]*?)```/i.exec(rawText);
  const text = fenced ? fenced[1].trim() : rawText.trim();
  const start = text.indexOf('{');
  const end = text.lastIndexOf('}');
  if (start === -1 || end <= start) return null;
  try {
    return JSON.parse(text.slice(start, end + 1));
  } catch {
    return null;
  }
}

function extractGroundSources(response) {
  const chunks = response?.candidates?.[0]?.groundingMetadata?.groundingChunks || [];
  const seen = new Set();
  const out = [];
  for (const chunk of chunks) {
    const uri = chunk?.web?.uri;
    if (!uri || !/^https?:\/\//i.test(uri) || seen.has(uri)) continue;
    seen.add(uri);
    let label = (chunk?.web?.title || '').trim();
    if (!label) {
      try {
        label = new URL(uri).hostname.replace(/^www\./, '');
      } catch {
        label = 'Sumber Web';
      }
    }
    out.push({ title: label.slice(0, 64), url: uri });
    if (out.length >= 4) break;
  }
  return out;
}

async function mapConcurrent(items, limit, fn) {
  const out = new Array(items.length);
  let idx = 0;
  async function worker() {
    while (idx < items.length) {
      const i = idx++;
      out[i] = await fn(items[i], i);
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return out;
}

async function fetchTmdbSeedTitles(tmdbKey) {
  if (!tmdbKey) return [];
  const today = new Date().toISOString().slice(0, 10);
  const urls = [
    `https://api.themoviedb.org/3/movie/upcoming?api_key=${encodeURIComponent(tmdbKey)}&region=ID&page=1`,
    `https://api.themoviedb.org/3/movie/now_playing?api_key=${encodeURIComponent(tmdbKey)}&region=ID&page=1`,
    `https://api.themoviedb.org/3/trending/movie/week?api_key=${encodeURIComponent(tmdbKey)}`,
  ];
  const map = new Map();
  for (const u of urls) {
    try {
      const res = await fetch(u, { signal: AbortSignal.timeout(8000) });
      if (!res.ok) continue;
      const data = await res.json();
      for (const item of (data.results || []).slice(0, 15)) {
        if (!item?.id || !item?.title) continue;
        if (!map.has(item.id)) {
          map.set(item.id, {
            tmdbId: item.id,
            title: item.title,
            originalTitle: item.original_title || item.title,
            releaseDate: item.release_date || today,
          });
        }
      }
    } catch {
      // ignore individual upstream timeout
    }
  }
  return Array.from(map.values()).slice(0, 24);
}

async function fetchTmdbIdReleaseMeta(tmdbId, tmdbKey) {
  if (!tmdbKey || !tmdbId) return null;
  try {
    const res = await fetch(
      `https://api.themoviedb.org/3/movie/${tmdbId}/release_dates?api_key=${encodeURIComponent(tmdbKey)}`,
      { signal: AbortSignal.timeout(7000) }
    );
    if (!res.ok) return null;
    const data = await res.json();
    const idEntry = (data.results || []).find((r) => r.iso_3166_1 === 'ID');
    if (!idEntry || !Array.isArray(idEntry.release_dates)) return null;
    const theatrical = idEntry.release_dates
      .filter((d) => [2, 3].includes(Number(d.type)) && d.release_date)
      .sort((a, b) => String(a.release_date).localeCompare(String(b.release_date)))[0];
    const picked = theatrical || idEntry.release_dates[0];
    if (!picked) return null;
    return {
      releaseDateId: sanitizeIso(picked.release_date),
      certification: String(picked.certification || '').trim().slice(0, 24),
    };
  } catch {
    return null;
  }
}

async function fetchGoogleNewsIdRss(query) {
  const url = `https://news.google.com/rss/search?q=${encodeURIComponent(query)}&hl=id&gl=ID&ceid=ID:id`;
  try {
    const res = await fetch(url, {
      headers: { 'User-Agent': 'Mozilla/5.0 (compatible; CinemaTrackerRadar/4.4)' },
      signal: AbortSignal.timeout(8000),
    });
    if (!res.ok) return [];
    const xml = await res.text();
    const items = [];
    const re = /<item>([\s\S]*?)<\/item>/g;
    let match;
    while ((match = re.exec(xml)) !== null) {
      const block = match[1];
      const titleMatch = /<title>([\s\S]*?)<\/title>/.exec(block);
      const linkMatch = /<link>([\s\S]*?)<\/link>/.exec(block);
      const pubMatch = /<pubDate>([\s\S]*?)<\/pubDate>/.exec(block);
      const sourceMatch = /<source[^>]*>([\s\S]*?)<\/source>/.exec(block);
      const title = decodeXmlEntities(titleMatch ? titleMatch[1] : '');
      const link = decodeXmlEntities(linkMatch ? linkMatch[1] : '');
      const source = decodeXmlEntities(sourceMatch ? sourceMatch[1] : '');
      const pubDate = pubMatch ? pubMatch[1].trim() : '';
      if (!title || !/^https?:\/\//i.test(link)) continue;
      items.push({ title, link, source, pubDate });
      if (items.length >= 4) break;
    }
    return items;
  } catch {
    return [];
  }
}

function titleMatchesHeadline(movieTitle, headline) {
  const cleanHead = normalizeKey(headline);
  const tokens = String(movieTitle || '')
    .toLowerCase()
    .split(/[^a-z0-9]+/)
    .filter((w) => w.length >= 3 && !['the', 'and', 'for', 'with', 'part', 'movie'].includes(w));
  if (tokens.length === 0) return cleanHead.includes(normalizeKey(movieTitle));
  const hits = tokens.filter((t) => cleanHead.includes(t));
  return tokens.length === 1 ? hits.length === 1 : hits.length >= Math.min(2, tokens.length);
}

async function runFreeRssAndTmdbScan(tmdbCandidates, tmdbKey, nowIso) {
  const targets = tmdbCandidates.slice(0, 12);
  const results = await mapConcurrent(targets, 4, async (candidate) => {
    const [idMeta, newsItems] = await Promise.all([
      fetchTmdbIdReleaseMeta(candidate.tmdbId, tmdbKey),
      fetchGoogleNewsIdRss(`"${candidate.title}" (XXI OR CGV OR Cinepolis OR bioskop OR LSF OR "pre-sale" OR IMAX) when:90d`),
    ]);

    const relevantNews = newsItems.filter((item) => titleMatchesHeadline(candidate.title, item.title));
    const combinedText = relevantNews.map((n) => n.title).join(' | ');
    const chains = detectChainsFromText(combinedText);
    const imax = /\bimax\b/i.test(combinedText);
    const presaleActive = /\b(pre-?sale|presale|tiket sudah tersedia|buka penjualan)\b/i.test(combinedText);
    const lsfMention = /\b(lsf|lulus sensor)\b/i.test(combinedText);

    if (!idMeta?.releaseDateId && relevantNews.length === 0) {
      return null;
    }

    let status = 'upcoming_global';
    if (presaleActive) status = 'presale';
    else if (idMeta?.releaseDateId || chains.length > 0) status = 'confirmed';
    else if (lsfMention) status = 'lsf_passed';

    const certText = idMeta?.certification ? `Klasifikasi TMDB ID: ${idMeta.certification}` : '';
    const lsfStatus = certText || (lsfMention ? 'Disebutkan di berita sensor LSF' : '');
    const headlineSnippet = relevantNews[0]?.title ? `Berita: ${relevantNews[0].title}` : '';
    const summary = [
      idMeta?.releaseDateId ? `Jadwal resmi TMDB ID: ${idMeta.releaseDateId}.` : '',
      headlineSnippet,
    ]
      .filter(Boolean)
      .join(' ')
      .slice(0, 260);

    return {
      key: `tmdb_${candidate.tmdbId}`,
      tmdbId: candidate.tmdbId,
      title: String(candidate.title).trim().toUpperCase().slice(0, 100),
      releaseDateId: idMeta?.releaseDateId || undefined,
      dateSource: idMeta?.releaseDateId ? 'tmdb_id' : 'rss',
      status,
      chains,
      imax,
      presaleActive,
      lsfStatus,
      summary: summary || 'Terdeteksi pada radar jadwal/berita bioskop Indonesia.',
      sources: relevantNews.slice(0, 3).map((n) => ({
        title: (n.source || 'Google News ID').slice(0, 64),
        url: n.link,
      })),
      checkedAt: nowIso,
    };
  });

  return results.filter(Boolean);
}

async function generateWithSearchFallback(ai, prompt) {
  const models = [
    'gemini-2.5-flash-lite',
    'gemini-2.5-flash',
    'gemini-flash-latest',
  ];
  let lastErr = null;
  for (const model of models) {
    try {
      return await ai.models.generateContent({
        model,
        contents: prompt,
        config: {
          tools: [{ googleSearch: {} }],
        },
      });
    } catch (err) {
      lastErr = err;
      const msg = err instanceof Error ? err.message : String(err);
      console.warn(`Model ${model} failed: ${msg.slice(0, 180)}`);
    }
  }
  throw lastErr || new Error('All Gemini search models failed');
}

async function runGeminiSearchScan(geminiKey, tmdbCandidates, nowIso) {
  const ai = new GoogleGenAI({
    apiKey: geminiKey,
    httpOptions: { apiVersion: 'v1beta' },
  });

  const hintBlock = tmdbCandidates.length
    ? `Periksa juga jadwal tayang Indonesia untuk daftar film TMDB berikut bila sudah diumumkan:\n${tmdbCandidates
        .slice(0, 18)
        .map((c) => `- ${c.title} (tmdbId: ${c.tmdbId}, global: ${c.releaseDate})`)
        .join('\n')}`
    : '';

  const todayIso = nowIso.slice(0, 10);
  const prompt = [
    'Anda adalah aggregator jadwal bioskop Indonesia (Cinema XXI, CGV Indonesia, Cinépolis Indonesia, IMAX, TIX ID, dan LSF RI).',
    `Tanggal hari ini: ${todayIso}.`,
    'Lakukan pencarian Google Search untuk mengumpulkan daftar lengkap jadwal rilis bioskop INDONESIA mencakup:',
    '1. Film yang sedang tayang, pre-sale, lulus sensor LSF (lsf.go.id), atau akan tayang bulan ini hingga 3 bulan ke depan di bioskop Indonesia.',
    '2. Film TAYANG ULANG / REISSUE / RE-RELEASE / IMAX Special Screening yang diumumkan kembali tayang di bioskop Indonesia (set isReRelease: true), termasuk film seperti Dune, Dune: Part Two, atau film spesial lainnya jika ada jadwal tayang ulang di Indonesia.',
    '3. Film blockbuster / tentpole besar (seperti Avengers: Doomsday, Dune, Christopher Nolan, DC, dll.) yang tanggal rilis bioskop Indonesianya sudah diumumkan media atau distributor resmi (ingat di Indonesia film Hollywood sering rilis hari Rabu, 2 hari lebih awal dari tanggal Jumat AS).',
    hintBlock,
    'ATURAN KETAT: Hanya masukkan film dan tanggal rilis Indonesia yang benar-benar terverifikasi oleh hasil Google Search. Jangan mengarang tanggal.',
    'Balas HANYA 1 JSON valid tanpa teks pembuka/penutup:',
    '{',
    '  "entries": [',
    '    {',
    '      "tmdbId": 0,',
    '      "title": "JUDUL FILM",',
    '      "releaseDateId": "YYYY-MM-DD",',
    '      "status": "confirmed" | "presale" | "lsf_passed",',
    '      "chains": ["XXI", "CGV", "Cinépolis"],',
    '      "imax": false,',
    '      "presaleActive": false,',
    '      "isReRelease": false,',
    '      "lsfStatus": "opsional",',
    '      "summary": "1 kalimat ringkas fakta jadwal ID."',
    '    }',
    '  ]',
    '}',
  ]
    .filter(Boolean)
    .join('\n\n');

  const response = await generateWithSearchFallback(ai, prompt);

  const parsed = parseJsonPayload(response.text || '');
  const sharedSources = extractGroundSources(response);
  const rawEntries = Array.isArray(parsed?.entries) ? parsed.entries : [];

  if (rawEntries.length === 0) {
    throw new Error('Gemini returned 0 entries');
  }

  const titleToTmdb = new Map(tmdbCandidates.map((c) => [normalizeKey(c.title), c.tmdbId]));
  const seenKeys = new Set();
  const entries = [];

  for (const raw of rawEntries) {
    const title = String(raw?.title || '').trim().toUpperCase().slice(0, 100);
    if (!title) continue;
    const tmdbNum = Number(raw?.tmdbId || 0) || titleToTmdb.get(normalizeKey(title)) || 0;
    const key = tmdbNum > 0 ? `tmdb_${tmdbNum}` : `title_${normalizeKey(title)}`;
    if (seenKeys.has(key)) continue;
    seenKeys.add(key);

    const relId = sanitizeIso(raw?.releaseDateId);
    entries.push({
      key,
      tmdbId: tmdbNum > 0 ? tmdbNum : undefined,
      title,
      releaseDateId: relId || undefined,
      dateSource: relId ? 'gemini' : undefined,
      status: ['confirmed', 'presale', 'lsf_passed'].includes(raw?.status) ? raw.status : 'confirmed',
      chains: normalizeChains(raw?.chains),
      imax: Boolean(raw?.imax),
      presaleActive: Boolean(raw?.presaleActive),
      isReRelease: Boolean(raw?.isReRelease),
      lsfStatus: String(raw?.lsfStatus || '').trim().slice(0, 80),
      summary: String(raw?.summary || '').trim().slice(0, 260) || 'Terkonfirmasi masuk radar rilis Indonesia.',
      sources: sharedSources,
      checkedAt: nowIso,
    });
  }

  return entries;
}

async function main() {
  const geminiKey = (process.env.GEMINI_API_KEY || '').trim();
  const tmdbKey = (process.env.TMDB_API_KEY || '').trim();
  const nowIso = new Date().toISOString();

  const tmdbCandidates = await fetchTmdbSeedTitles(tmdbKey);
  let entries = [];
  let sourceLabel = 'cloud-radar-gemini';

  if (geminiKey) {
    try {
      entries = await runGeminiSearchScan(geminiKey, tmdbCandidates, nowIso);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      console.warn(`Gemini grounding fallback triggered: ${msg}`);
      entries = await runFreeRssAndTmdbScan(tmdbCandidates, tmdbKey, nowIso);
      sourceLabel = 'cloud-radar-rss-tmdb';
    }
  } else {
    entries = await runFreeRssAndTmdbScan(tmdbCandidates, tmdbKey, nowIso);
    sourceLabel = 'cloud-radar-rss-tmdb';
  }

  const outPayload = {
    version: 1,
    updatedAt: nowIso,
    source: sourceLabel,
    entries,
  };

  fs.mkdirSync(path.dirname(OUT_PATH), { recursive: true });
  fs.writeFileSync(OUT_PATH, JSON.stringify(outPayload, null, 2) + '\n', 'utf8');
  console.log(`Updated radar-id.json (${sourceLabel}) with ${entries.length} entries.`);
}

main().catch((err) => {
  console.error('Radar sync failed:', err);
  process.exit(1);
});
