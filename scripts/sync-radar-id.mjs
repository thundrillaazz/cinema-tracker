import fs from 'fs';
import path from 'path';
import { GoogleGenAI } from '@google/genai';

const ROOT_DIR = process.cwd();
const OUT_PATH = path.join(ROOT_DIR, 'public', 'radar-id.json');

const NUMBER_WORD_MAP = {
  one: '1', satu: '1', two: '2', dua: '2', three: '3', tiga: '3',
  four: '4', empat: '4', five: '5', lima: '5', six: '6', enam: '6',
  seven: '7', tujuh: '7', eight: '8', delapan: '8', nine: '9', sembilan: '9',
  ten: '10', sepuluh: '10', ii: '2', iii: '3', iv: '4', vi: '6', vii: '7', viii: '8', ix: '9',
};
const SEQUEL_PREFIX_WORDS = new Set(['part', 'pt', 'vol', 'volume', 'chapter', 'bagian', 'episode', 'bab']);
const RUMOR_HEADLINE_RE =
  /\b(rumor|rumours?|rumors?|kabarnya|diduga|prediksi|bocoran|diperkirakan|konon|belum\s+pasti|spekulasi|hoaks?|hoax|fan[-\s]?made|concept\s+trailer|estimasi|tidak\s+(?:akan\s+)?tayang\s+di\s+(?:bioskop\s+)?indonesia|batal\s+tayang(?:\s+di\s+(?:bioskop\s+)?(?:indonesia|malaysia|singapura))?|dilarang\s+tayang|(?:tidak|tak|belum|gagal(?:\s+lolos)?)\s+(?:lulus|lolos)\s+sensor|hanya\s+tayang\s+(?:di\s+)?(?:bioskop\s+)?(?:global|luar\s+negeri|amerika|us|as))\b/i;
const UNCONFIRMED_ID_RE =
  /\b(belum\s+(?:ada\s+(?:informasi|kabar|jadwal|kepastian|tanda(?:-tanda)?)|pasti|diumumkan|dapat\s+dipastikan|dipastikan|diketahui|terjadwal|tayang\s+di\s+indonesia))\b/i;
const QUESTION_HEADLINE_RE =
  /(?:^(?:kapan|kapankah|apakah|bagaimana|mengapa|kenapa|berapa|benarkah|akankah|sudahkah)\b|\b(?:tayang\s+kapan|kapan\s+(?:tayang|rilis|masuk(?:\s+bioskop)?)|apa(?:kah)?\s+benar|bakal\s+tayang)\b|\?(?:\s*[-|–]\s*[^?]{1,60})?\s*$)/i;
const INDONESIAN_ABSENCE_PHRASE_RE =
  /\b(?:belum\s+(?:ada|tersedia|diketahui|ditemukan|memiliki|mendapat|mengantongi|menerima)\s+(?:informasi(?:\s+lebih\s+lanjut)?|info(?:\s+lebih\s+lanjut)?|kepastian|jadwal|tanggal|kabar|tanda(?:-tanda)?|rencana|pengumuman)(?:\s+(?:lebih\s+lanjut|resmi|pasti|terkait|mengenai|soal|penayangan|tayang|rilis))?|belum\s+mengumumkan(?:\s+(?:jadwal|tanggal|penayangan|rilis|perilisan|kapan|kepastian))?|belum\s+(?:dapat\s+dipastikan|diketahui|diumumkan|dipastikan|terkonfirmasi|resmi|terjadwal|tayang\s+di\s+(?:bioskop\s+)?indonesia|masuk\s+(?:bioskop\s+)?indonesia|ada\s+kepastian)|(?:masih\s+)?belum\s+dipastikan(?:\s+(?:akan|bakal))?(?:\s+(?:masuk|tayang|rilis))?|(?:tidak\s+ada|belum\s+memiliki)\s+(?:jadwal|tanggal)(?:\s+(?:tayang|rilis))?|tidak\s+(?:akan\s+)?(?:tayang|rilis|masuk)\s+di\s+(?:bioskop\s+)?indonesia|belum\s+diketahui\s+kapan\s+(?:tayang|rilis)|diproyeksikan\s+(?:segera\s+)?(?:masuk|tayang)|berpeluang\s+(?:segera\s+)?(?:masuk|tayang)|(?:masih\s+)?menunggu\s+(?:konfirmasi|jadwal|kepastian|pengumuman)|apakah\s+(?:akan\s+)?tayang\s+di\s+(?:bioskop\s+)?indonesia)\b/i;
const CONFIRMED_SIGNAL_RE =
  /\b(tayang\s+(?:di\s+bioskop|mulai|tanggal)|jadwal\s+tayang|tiket\s+(?:pre-?sale|presale|sudah|tersedia)|lulus\s+sensor|lsf|xxi|cgv|cin[eé]polis|tix\s*id|reissue|tayang\s+ulang)\b/i;
const STREAMING_ONLY_SOURCE_RE =
  /\b(disney\s*\+?|netflix|prime\s*video|amazon|apple\s*tv|hbo|max|vidio|viu|wetv|iqiyi|catchplay|hotstar)\b/i;
const FOREIGN_CENSOR_OR_BAN_RE =
  /\b(?:lembaga\s+penapis\s+filem|\blpf\b|golden\s+screen\s+cinemas|\bgsc\b|\btgv\b|(?:resmi\s+)?dilarang\s+tayang|(?:tidak|tak|belum|gagal(?:\s+lolos)?)\s+(?:lulus|lolos)\s+sensor)\b/i;
const EXPLICIT_INDONESIA_VENUE_RE =
  /\b(xxi|cinema\s*21|cinema\s*xxi|21cineplex|cgv|cin[eé]polis|tix\s*id|\blsf\b|lembaga\s+sensor\s+film|bioskop\s+(?:di\s+)?indonesia|di\s+bioskop\s+indonesia|seluruh\s+bioskop\s+indonesia|jaringan\s+bioskop\s+indonesia|tanah\s+air|di\s+indonesia)\b/i;
const OFFICIAL_CONFIRMED_PORTAL_SIGNAL_RE =
  /\b(xxi|cinema\s*21|cinema\s*xxi|21cineplex|cgv|cin[eé]polis|tix\s*id|\blsf\b|lembaga\s+sensor\s+film|jadwalnonton)\b/i;
const FOREIGN_WIRE_RE =
  /\b(imax\s*70\s*mm|70\s*mm\s+imax|melansir\s+(?:variety|deadline|hollywood\s+reporter|collider|screenrant|indiewire|thewrap)|dilansir\s+dari\s+(?:variety|deadline|hollywood\s+reporter|collider|screenrant|indiewire|thewrap)|mengutip\s+(?:laporan\s+)?(?:variety|deadline|hollywood\s+reporter|collider|screenrant|indiewire|thewrap)|via\s+(?:variety|deadline|hollywood\s+reporter)|amerika\s+serikat|bioskop\s+(?:as|amerika|global|inggris|jepang|korea|malaysia|singapura)|lembaga\s+penapis\s+filem|\blpf\b|golden\s+screen\s+cinemas|\bgsc\b|(?:di\s+)?(?:malaysia|singapura|singapore|brunei)|festival\s+film|sundance|cannes|tiff|venice|berlinale)\b/i;
const GLOBAL_ONLY_SUMMARY_RE =
  /\b(bioskop\s+global|rilis\s+global|secara\s+global|global\s+release|worldwide|overseas|sundance|cannes|tiff|venice|berlinale|amerika\s+serikat|bioskop\s+as|tanpa\s+jadwal\s+indonesia)\b/i;
const LISTICLE_HEADLINE_RE =
  /\b(\d+\s+film|daftar\s+film|deretan\s+film|rekomendasi\s+film|fenomena\s+\w+\s+\d{1,2})\b/i;
const PRESALE_NEGATION_OR_QUESTION_RE =
  /^\s*(kapan|kapankah|benarkah|apakah|cara|bagaimana|berapa|harga|prediksi)\b|\b(belum\s+(buka|dibuka|tersedia|mulai)|kapan\s+(buka|dibuka|mulai|tayang)|akan\s+segera)\b/i;
const ID_MONTH_MAP = {
  januari: 1, jan: 1, februari: 2, feb: 2, maret: 3, mar: 3, april: 4, apr: 4,
  mei: 5, may: 5, juni: 6, jun: 6, juli: 7, jul: 7, agustus: 8, agu: 8, ags: 8, aug: 8,
  september: 9, sept: 9, sep: 9, oktober: 10, okt: 10, oct: 10, november: 11, nov: 11, desember: 12, des: 12, dec: 12,
};

const CINEMA_FORMAT_SUFFIX_RE =
  /\s*[\[(]\s*(?:re-?release|reissue|tayang\s+(?:ulang|kembali)|remaster(?:ed)?|imax\s*2d|imax\s*3d|imax|2d|3d|atmos|dolby\s*atmos|screenx|4dx|starium|reald\s*3d)\s*[\])]/gi;

function maskNegatedCensorAndForeignPhrases(text) {
  return String(text || '')
    .replace(/\b(?:tidak|tak|belum|gagal(?:\s+lolos)?)\s+(?:lulus|lolos)\s+sensor\b/gi, ' ')
    .replace(/\b(?:lembaga\s+penapis\s+filem|lpf|golden\s+screen\s+cinemas|gsc|tgv)\b/gi, ' ');
}

function hasExplicitIndonesiaVenue(text) {
  const masked = maskNegatedCensorAndForeignPhrases(text);
  if (EXPLICIT_INDONESIA_VENUE_RE.test(masked)) return true;
  return /\blulus\s+sensor\b/i.test(masked) && !/\b(?:malaysia|singapura|singapore|brunei)\b/i.test(masked);
}

function getWibTodayIso() {
  try {
    const parts = new Intl.DateTimeFormat('en-CA', {
      timeZone: 'Asia/Jakarta',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }).formatToParts(new Date());
    const y = parts.find((p) => p.type === 'year')?.value;
    const m = parts.find((p) => p.type === 'month')?.value;
    const d = parts.find((p) => p.type === 'day')?.value;
    if (y && m && d) return `${y}-${m}-${d}`;
  } catch {
    // ignore
  }
  return new Date(Date.now() + 7 * 3600_000).toISOString().slice(0, 10);
}

function getSourceTierScore(source, url) {
  const s = `${source || ''} ${url || ''}`.toLowerCase();
  if (/(21cineplex\.com|cgv\.id|cinepolis\.co\.id|tix\.id|lsf\.go\.id|jadwalnonton\.com|cinema\s*xxi|cinema\s*21)/.test(s)) {
    return 3;
  }
  if (/(kompas\.com|detik\.com|kumparan\.com|tirto\.id|idntimes\.com|tempo\.co|kapanlagi\.com|antaranews\.com|merdeka\.com|liputan6\.com|cnnindonesia\.com|cnbcindonesia\.com|rri\.co\.id|katadata\.co\.id|hypeabis\.id|jawapos\.com|suara\.com|tribunnews\.com|medcom\.id|sindonews\.com|pikiran-rakyat\.com|cinemags|kincir)/.test(s)) {
    return 2;
  }
  return 1;
}

function isQuestionHeadline(headline) {
  const s = String(headline || '').trim();
  if (!s) return false;
  return QUESTION_HEADLINE_RE.test(s);
}

function isDisqualifiedByAbsenceGuard(text, hasConfirmedTier3IdDate = false) {
  const s = String(text || '').trim();
  if (!s) return false;
  if (FOREIGN_CENSOR_OR_BAN_RE.test(s)) return true;
  if (RUMOR_HEADLINE_RE.test(s)) return true;
  if (INDONESIAN_ABSENCE_PHRASE_RE.test(s) || UNCONFIRMED_ID_RE.test(s)) {
    if (hasConfirmedTier3IdDate && CONFIRMED_SIGNAL_RE.test(s)) return false;
    return true;
  }
  return false;
}

function stripCinemaFormatSuffixes(str) {
  return String(str || '').replace(CINEMA_FORMAT_SUFFIX_RE, ' ').replace(/\s+/g, ' ').trim();
}

function tokenizeNormalizedTitle(str) {
  const cleaned = stripCinemaFormatSuffixes(str)
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[:\-–—'"‘’“”\/.,!?()\[\]_]+/g, ' ')
    .replace(/[^a-z0-9\s]+/g, ' ')
    .trim();
  if (!cleaned) return [];
  const rawTokens = cleaned.split(/\s+/).filter(Boolean);
  const mapped = rawTokens.map((tok, idx) => {
    if (NUMBER_WORD_MAP[tok]) return NUMBER_WORD_MAP[tok];
    if (tok === 'i' || tok === 'v' || tok === 'x') {
      const prev = idx > 0 ? rawTokens[idx - 1] : '';
      const isTrailing = rawTokens.length >= 2 && idx === rawTokens.length - 1;
      if (SEQUEL_PREFIX_WORDS.has(prev) || isTrailing) {
        return tok === 'i' ? '1' : tok === 'v' ? '5' : '10';
      }
    }
    return tok;
  });
  const out = [];
  for (let i = 0; i < mapped.length; i++) {
    const cur = mapped[i];
    const next = i + 1 < mapped.length ? mapped[i + 1] : '';
    if ((cur === 'part' || cur === 'pt' || cur === 'bagian') && /^\d+$/.test(next)) continue;
    out.push(cur);
  }
  return out;
}

function normalizeKey(str) {
  return tokenizeNormalizedTitle(str).join('');
}

function sanitizeIso(raw) {
  const clean = String(raw || '').trim().slice(0, 10);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(clean) || clean.endsWith('-12-31')) return '';
  const dt = new Date(`${clean}T00:00:00Z`);
  return Number.isNaN(dt.getTime()) ? '' : clean;
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
  const clauses = String(text || '')
    .split(/[\n\r]+|(?<=[.!?])\s+/)
    .map((s) => s.trim())
    .filter(Boolean);
  const out = new Set();
  for (const cl of clauses) {
    if (
      INDONESIAN_ABSENCE_PHRASE_RE.test(cl) ||
      UNCONFIRMED_ID_RE.test(cl) ||
      RUMOR_HEADLINE_RE.test(cl) ||
      isQuestionHeadline(cl) ||
      FOREIGN_CENSOR_OR_BAN_RE.test(cl)
    ) {
      continue;
    }
    if (FOREIGN_WIRE_RE.test(cl) && !hasExplicitIndonesiaVenue(cl)) continue;
    if (/\b(xxi|cinema\s*21|cinema\s*xxi|21cineplex)\b/i.test(cl)) out.add('XXI');
    if (/\bcgv\b/i.test(cl)) out.add('CGV');
    if (/cin[eé]polis/i.test(cl)) out.add('Cinépolis');
  }
  return Array.from(out);
}

function hasConfirmedLsfMention(text) {
  const clauses = String(text || '')
    .split(/[\n\r]+|(?<=[.!?])\s+/)
    .map((s) => s.trim())
    .filter(Boolean);
  for (const cl of clauses) {
    if (
      INDONESIAN_ABSENCE_PHRASE_RE.test(cl) ||
      UNCONFIRMED_ID_RE.test(cl) ||
      RUMOR_HEADLINE_RE.test(cl) ||
      isQuestionHeadline(cl) ||
      FOREIGN_CENSOR_OR_BAN_RE.test(cl) ||
      /\b(?:malaysia|singapura|singapore|brunei)\b/i.test(cl)
    ) {
      continue;
    }
    const masked = maskNegatedCensorAndForeignPhrases(cl);
    if (/\b(lsf|lembaga\s+sensor\s+film|lulus\s+sensor)\b/i.test(masked)) {
      return true;
    }
  }
  return false;
}

function buildSearchTitleVariants(baseTitle) {
  const clean = String(baseTitle || '').trim();
  const compactNumeric = tokenizeNormalizedTitle(clean).join(' ');
  const hasDistinctNumeric =
    compactNumeric &&
    compactNumeric !== clean.toLowerCase() &&
    /\b\d+\b/.test(compactNumeric) &&
    compactNumeric.split(/\s+/).length >= 2;
  if (hasDistinctNumeric) {
    return {
      googleExpr: `("${clean}" OR "${compactNumeric}")`,
      bingQueryTerm: `${clean} "${compactNumeric}"`,
    };
  }
  return {
    googleExpr: `"${clean}"`,
    bingQueryTerm: clean,
  };
}

function decodeXmlEntities(str) {
  return String(str || '')
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'")
    .replace(/&nbsp;/gi, ' ')
    .trim();
}

function stripHtmlToText(html) {
  const cleaned = decodeXmlEntities(
    String(html || '')
      .replace(/<script[\s\S]*?<\/script>/gi, ' ')
      .replace(/<style[\s\S]*?<\/style>/gi, ' ')
      .replace(/<svg[\s\S]*?<\/svg>/gi, ' ')
      .replace(/<noscript[\s\S]*?<\/noscript>/gi, ' ')
      .replace(/<nav\b[^>]*>[\s\S]*?<\/nav>/gi, ' ')
      .replace(/<aside\b[^>]*>[\s\S]*?<\/aside>/gi, ' ')
      .replace(/<footer\b[^>]*>[\s\S]*?<\/footer>/gi, ' ')
      .replace(/<!--[\s\S]*?-->/g, ' ')
      .replace(/<\/(?:t[dh]|td|th)>/gi, ' · ')
      .replace(/<\/(?:tr|li|h[1-6]|p|div|section|article|blockquote)>/gi, '\n')
      .replace(/<br\s*\/?>/gi, '\n')
      .replace(/<[^>]+>/g, ' ')
      .replace(/[ \t]+/g, ' ')
      .replace(/\n[ \t]+/g, '\n')
      .replace(/\n{2,}/g, '\n')
  );
  const lines = cleaned.split(/\n+/).map((l) => l.trim()).filter(Boolean);
  const kept = [];
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (/^(?:baca\s+juga|lihat\s+juga|simak\s+juga|rekomendasi|iklan|advertisement|bagikan|share)\s*:/i.test(line)) {
      continue;
    }
    if (
      i >= Math.floor(lines.length * 0.45) &&
      /^(?:artikel\s+terkait|berita\s+terkait|topik\s+terkait|tag\s+terkait|komentar)\s*:?\s*$/i.test(line)
    ) {
      break;
    }
    kept.push(line);
  }
  return kept.join('\n').slice(0, 28000);
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
    out.push({ title: label.slice(0, 64), url: uri, tier: getSourceTierScore(label, uri) });
  }
  return out
    .sort((a, b) => b.tier - a.tier)
    .slice(0, 4)
    .map(({ title, url }) => ({ title, url }));
}

async function mapConcurrent(items, limit, fn) {
  if (!items || items.length === 0) return [];
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
  const today = getWibTodayIso();
  const urls = [
    `https://api.themoviedb.org/3/discover/movie?api_key=${encodeURIComponent(tmdbKey)}&region=ID&with_release_type=2|3&release_date.gte=${today}&sort_by=release_date.asc&page=1`,
    `https://api.themoviedb.org/3/discover/movie?api_key=${encodeURIComponent(tmdbKey)}&with_original_language=id&primary_release_date.gte=${today}&sort_by=popularity.desc&page=1`,
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
      for (const item of (data.results || []).slice(0, 14)) {
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
    const todayWib = getWibTodayIso();
    const minDateObj = new Date(`${todayWib}T00:00:00Z`);
    minDateObj.setUTCDate(minDateObj.getUTCDate() - 60);
    const minDate = minDateObj.toISOString().slice(0, 10);
    const valid = idEntry.release_dates.filter(
      (d) => d.release_date && !String(d.release_date).slice(0, 10).endsWith('-12-31')
    );
    const pickWin = (list) => {
      const sorted = [...list].sort((a, b) => String(a.release_date).localeCompare(String(b.release_date)));
      return sorted.find((d) => String(d.release_date).slice(0, 10) >= minDate);
    };
    const picked =
      pickWin(valid.filter((d) => Number(d.type) === 3)) ||
      pickWin(valid.filter((d) => Number(d.type) === 2));
    if (!picked) return null;
    return {
      releaseDateId: sanitizeIso(picked.release_date),
      releaseType: Number(picked.type || 3),
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
      headers: { 'User-Agent': 'Mozilla/5.0 (compatible; CinemaTrackerRadar/4.4.2)' },
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
      const descMatch = /<description>([\s\S]*?)<\/description>/.exec(block);
      const pubMatch = /<pubDate>([\s\S]*?)<\/pubDate>/.exec(block);
      const sourceMatch = /<source[^>]*>([\s\S]*?)<\/source>/.exec(block);
      const title = decodeXmlEntities(titleMatch ? titleMatch[1] : '');
      const snippet = decodeXmlEntities(decodeXmlEntities(descMatch ? descMatch[1] : ''))
        .replace(/<[^>]+>/g, ' ')
        .replace(/\s+/g, ' ')
        .trim()
        .slice(0, 1500);
      const link = decodeXmlEntities(linkMatch ? linkMatch[1] : '');
      const source = decodeXmlEntities(sourceMatch ? sourceMatch[1] : '');
      const pubDate = pubMatch ? pubMatch[1].trim() : '';
      if (!title || !/^https?:\/\//i.test(link)) continue;
      if (STREAMING_ONLY_SOURCE_RE.test(source) || STREAMING_ONLY_SOURCE_RE.test(link)) continue;
      const pubMs = pubDate ? Date.parse(pubDate) : NaN;
      items.push({
        title,
        snippet,
        link,
        source,
        pubDate,
        refDateIso: !Number.isNaN(pubMs) ? new Date(pubMs).toISOString().slice(0, 10) : undefined,
      });
      if (items.length >= 6) break;
    }
    return items;
  } catch {
    return [];
  }
}

async function fetchBingNewsIdRss(query) {
  const url = `https://www.bing.com/news/search?q=${encodeURIComponent(query)}&cc=ID&setlang=id&format=rss`;
  try {
    const res = await fetch(url, {
      headers: { 'User-Agent': 'Mozilla/5.0 (compatible; CinemaTrackerRadar/4.4.2)' },
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
      const descMatch = /<description>([\s\S]*?)<\/description>/.exec(block);
      const pubMatch = /<pubDate>([\s\S]*?)<\/pubDate>/.exec(block);
      const sourceMatch = /<news:Source[^>]*>([\s\S]*?)<\/news:Source>/.exec(block);
      const title = decodeXmlEntities(titleMatch ? titleMatch[1] : '');
      const snippet = decodeXmlEntities(decodeXmlEntities(descMatch ? descMatch[1] : ''))
        .replace(/<[^>]+>/g, ' ')
        .replace(/\s+/g, ' ')
        .trim()
        .slice(0, 1500);
      const link = decodeXmlEntities(linkMatch ? linkMatch[1] : '');
      let source = decodeXmlEntities(sourceMatch ? sourceMatch[1] : '');
      if (!source && /^https?:\/\//i.test(link)) {
        try {
          source = new URL(link).hostname.replace(/^www\./, '');
        } catch {
          source = 'Bing News ID';
        }
      }
      const pubDate = pubMatch ? pubMatch[1].trim() : '';
      if (!title || !/^https?:\/\//i.test(link)) continue;
      if (STREAMING_ONLY_SOURCE_RE.test(source) || STREAMING_ONLY_SOURCE_RE.test(link)) continue;
      const pubMs = pubDate ? Date.parse(pubDate) : NaN;
      items.push({
        title,
        snippet,
        link,
        source,
        pubDate,
        refDateIso: !Number.isNaN(pubMs) ? new Date(pubMs).toISOString().slice(0, 10) : undefined,
      });
      if (items.length >= 6) break;
    }
    return items;
  } catch {
    return [];
  }
}

async function resolveArticleUrl(rawUrl) {
  const clean = String(rawUrl || '').trim();
  if (!/^https?:\/\//i.test(clean)) return '';
  if (clean.includes('bing.com/news/apiclick.aspx')) {
    try {
      const target = new URL(clean).searchParams.get('url');
      if (target && /^https?:\/\//i.test(target)) return target;
    } catch {
      // ignore
    }
  }
  if (!clean.includes('news.google.com/')) return clean;
  try {
    const res = await fetch(clean, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
      },
      redirect: 'follow',
      signal: AbortSignal.timeout(6500),
    });
    if (res.url && !res.url.includes('news.google.com/')) return res.url;
    const html = await res.text();
    const idMatch = /news\.google\.com\/(?:rss\/)?articles\/([^?/#]+)/.exec(clean);
    const tsMatch = /data-n-a-ts="(\d+)"/.exec(html);
    const sgMatch = /data-n-a-sg="([^"]+)"/.exec(html);
    if (idMatch && tsMatch && sgMatch) {
      const payload = [
        [
          [
            'Fbv4je',
            JSON.stringify(['garturlreq', [['en-ID', 'ID', ['FINANCE_TOP_INDICES', 'WEB_TEST_1_0_0'], null, null, 1, 1, 'ID:id', null, 180, null, null, null, null, null, 0, null, null, [1608992183, 723341000]], 'en-ID', 'ID', 1, [2, 3, 4, 8], 1, 0, '655000234', 0, 0, null, 0], idMatch[1], Number(tsMatch[1]), sgMatch[1]]),
            null,
            'generic',
          ],
        ],
      ];
      const batchRes = await fetch('https://news.google.com/_/DotsSplashUi/data/batchexecute', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
        },
        body: `f.req=${encodeURIComponent(JSON.stringify(payload))}`,
        signal: AbortSignal.timeout(6500),
      });
      if (batchRes.ok) {
        const batchTxt = await batchRes.text();
        const urlMatch = /"garturlres","(https?:\\\/\\\/[^"]+)"/.exec(batchTxt);
        if (urlMatch) {
          return JSON.parse(`"${urlMatch[1]}"`);
        }
      }
    }
  } catch {
    // ignore decode failure
  }
  return '';
}

async function fetchArticleBodyText(rawUrl) {
  const targetUrl = await resolveArticleUrl(rawUrl);
  if (!targetUrl || targetUrl.includes('news.google.com/')) return { url: '', text: '' };
  try {
    const res = await fetch(targetUrl, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36',
        Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
      },
      redirect: 'follow',
      signal: AbortSignal.timeout(8000),
    });
    if (!res.ok) return { url: targetUrl, text: '' };
    const html = await res.text();
    const bodyText = stripHtmlToText(html);
    return { url: res.url || targetUrl, text: bodyText };
  } catch {
    return { url: targetUrl, text: '' };
  }
}

const SEQUEL_NUMBER_WORD_MAP = {
  two: '2', dua: '2', three: '3', tiga: '3', four: '4', empat: '4',
  five: '5', lima: '5', six: '6', enam: '6', seven: '7', tujuh: '7',
  eight: '8', delapan: '8', nine: '9', sembilan: '9',
  ii: '2', iii: '3', iv: '4', vi: '6', vii: '7', viii: '8', ix: '9',
};
const SEQUEL_PREFIX_RE = /^(?:part|pt|bagian|chapter|bab|episode|ep|vol|volume|book|buku|act)$/;
const INSTALLMENT_ROMAN_RE = /^(?:ii|iii|iv|vi|vii|viii|ix)$/;
const EN_SEQUEL_WORD_RE = /^(?:two|three|four)$/;

function extractSequelInfo(text) {
  let cleaned = String(text || '');
  try {
    cleaned = cleaned.normalize('NFKD').replace(/[\u0300-\u036f]/g, '');
  } catch {
    // ignore
  }
  cleaned = cleaned.toLowerCase();

  const withoutMenjelang = cleaned.replace(
    /\b(?:menjelang|jelang|sebelum|sambut|menyambut)\s+(?:sekuel|sequel)(?:nya)?\b/gi,
    ' '
  );
  const hasGenericSequel = /\b(?:sekuel|sequel)\b/i.test(withoutMenjelang);

  const masked = cleaned
    .replace(
      /\b([1-9]|[12]\d|3[01])(?:\s*(?:-|–|—|s\/d|s\.d\.|hingga|sampai)\s*(?:[1-9]|[12]\d|3[01]))?\s+(?:januari|jan|februari|feb|maret|mar|april|apr|mei|may|juni|jun|juli|jul|agustus|agu|ags|aug|september|sept|sep|oktober|okt|oct|november|nov|desember|des|dec)(?:\s*,?\s*20\d{2})?\b/gi,
      ' '
    )
    .replace(/\b(?:\d{1,2}[\/\-]\d{1,2}[\/\-]\d{2,4}|\d{4}-\d{2}-\d{2})\b/g, ' ')
    .replace(/\b(?:19|20)\d{2}\b/g, ' ')
    .replace(/\b\d+(?:[.,\/]\d+)+\b/g, ' ')
    .replace(/\b(?:\d+\s*(?:k|d|dx|fps|hz|gb|mb|px|mdpl)|\d+\+|cinema\s*21|21cineplex)\b/gi, ' ')
    .replace(
      /\b(?:top|no\.?|nomor|ke-|h-|usia|umur|rating|skor|nilai|jam|pukul|season|musim|rp\.?|halaman|hal)\s*[-:]?\s*\d+\b/gi,
      ' '
    )
    .replace(
      /\b\d+\s*(?:film|judul|serial|series|hari|minggu|pekan|bulan|tahun|dekade|jam|menit|detik|bioskop|jaringan|cabang|kota|lokasi|studio|teater|layar|juta|ribu|ratus|miliar|persen|%|alasan|fakta|rekomendasi|daftar|deretan|orang|penonton|tiket|kursi|pemenang|kategori|nominasi|edisi|kali|berkali|karakter|pemain|aktor|aktris|weeks?|days?|months?|years?|hours?|minutes?|films?|movies?|times?|theaters?|cinemas?|screens?|cities?)\b/gi,
      ' '
    )
    .replace(/[:\-–—'"‘’“”\/.,!?()\[\]_]+/g, ' ')
    .replace(/[^a-z0-9\s]+/g, ' ')
    .trim();

  if (!masked) return { numbers: [], hasGenericSequel };

  const tokens = masked.split(/\s+/).filter(Boolean);
  const nums = [];
  const pushNum = (n) => {
    if (n && !nums.includes(n)) nums.push(n);
  };

  for (let i = 0; i < tokens.length; i++) {
    const tok = tokens[i];
    const prev = i > 0 ? tokens[i - 1] : '';
    const next = i + 1 < tokens.length ? tokens[i + 1] : '';
    if (SEQUEL_PREFIX_RE.test(prev)) {
      if (/^[2-9]$/.test(tok)) {
        pushNum(tok);
        continue;
      }
      if (SEQUEL_NUMBER_WORD_MAP[tok]) {
        pushNum(SEQUEL_NUMBER_WORD_MAP[tok]);
        continue;
      }
    }
    if (/^[2-9]$/.test(tok)) {
      pushNum(tok);
    } else if (INSTALLMENT_ROMAN_RE.test(tok)) {
      pushNum(SEQUEL_NUMBER_WORD_MAP[tok]);
    } else if (
      EN_SEQUEL_WORD_RE.test(tok) &&
      !/^(?:weeks?|days?|months?|years?|hours?|minutes?|films?|movies?|times?)$/.test(next)
    ) {
      pushNum(SEQUEL_NUMBER_WORD_MAP[tok]);
    }
  }
  return { numbers: nums, hasGenericSequel };
}

function sequelMarkersCompatible(movieTitle, targetText) {
  const mInfo = extractSequelInfo(movieTitle);
  const stopWords = new Set(['the', 'and', 'for', 'with', 'part', 'pt', 'movie', 'film', 'of', 'in', 'a', 'an', 'vs']);
  const baseWords = tokenizeNormalizedTitle(movieTitle).filter(
    (w) => !/^\d+$/.test(w) && w.length >= 3 && !stopWords.has(w)
  );
  const segments = String(targetText || '')
    .split(/\s*(?:,|;|\||\/|\+|—|–|\b(?:hingga|sampai|dan|serta|vs)\b)\s*/i)
    .map((s) => s.trim())
    .filter(Boolean);

  if (segments.length > 1 && baseWords.length > 0) {
    const matchedSegs = segments.filter((seg) => {
      const segTokens = new Set(tokenizeNormalizedTitle(seg));
      return baseWords.some((bw) => segTokens.has(bw));
    });
    if (matchedSegs.length > 0) {
      const checkSegment = (seg) => {
        const sInfo = extractSequelInfo(seg);
        if (mInfo.numbers.length === 0) {
          if (sInfo.numbers.length > 0) return false;
          if (sInfo.hasGenericSequel && !mInfo.hasGenericSequel) return false;
          return true;
        }
        return mInfo.numbers.every((n) => sInfo.numbers.includes(n));
      };
      return matchedSegs.some(checkSegment);
    }
  }

  const tInfo = extractSequelInfo(targetText);
  if (mInfo.numbers.length === 0) {
    if (tInfo.numbers.length > 0) return false;
    if (tInfo.hasGenericSequel && !mInfo.hasGenericSequel) return false;
    return true;
  }
  return mInfo.numbers.every((n) => tInfo.numbers.includes(n));
}

function titleMatchesHeadline(movieTitle, headline) {
  const headTokens = tokenizeNormalizedTitle(headline);
  if (headTokens.length === 0) return false;
  if (!sequelMarkersCompatible(movieTitle, headline)) return false;

  const headTokenSet = new Set(headTokens);
  const cleanHead = headTokens.join('');
  const tokens = tokenizeNormalizedTitle(movieTitle).filter(
    (w) => (w.length >= 2 || /^\d+$/.test(w)) && !['the', 'and', 'for', 'with', 'part', 'pt', 'movie', 'film', 'of', 'in', 'a', 'an'].includes(w)
  );
  if (tokens.length === 0) return Boolean(cleanHead && cleanHead.includes(normalizeKey(movieTitle)));
  const numericTokens = tokens.filter((t) => /^\d+$/.test(t));
  if (numericTokens.length > 0 && !numericTokens.every((nt) => headTokenSet.has(nt))) {
    return false;
  }
  const hits = tokens.filter((t) =>
    /^\d+$/.test(t) || t.length <= 3 ? headTokenSet.has(t) : headTokenSet.has(t) || cleanHead.includes(t)
  );
  return tokens.length === 1 ? hits.length === 1 : hits.length >= Math.min(2, tokens.length);
}

function isAffirmativePresaleHeadline(text) {
  const s = String(text || '');
  if (!/\b(pre-?sale|presale|tiket\s+(?:sudah|resmi|bisa)\s+(?:dibeli|dipesan|dibuka|tersedia))\b/i.test(s)) {
    return false;
  }
  if (PRESALE_NEGATION_OR_QUESTION_RE.test(s) || isDisqualifiedByAbsenceGuard(s)) return false;
  return /\b(dibuka|tersedia|bisa\s+(?:dibeli|dipesan)|mulai\s+hari\s+ini|sudah\s+(?:dibuka|buka|tersedia|dijual)|resmi\s+dibuka)\b/i.test(s);
}

const ID_DATE_LOCAL_CTX_RE =
  /\b(di\s+indonesia|bioskop\s+indonesia|tanah\s+air|lebih\s+awal|xxi|cinema\s*21|cinema\s*xxi|cgv|cinepolis|cinépolis|imax|tix\s*id|resmi)\b/gi;
const GLOBAL_DATE_LOCAL_CTX_RE =
  /\b((?:di\s+)?(?:amerika(?:\s+serikat)?|usa|us|as|global|bioskop\s+global|rilis\s+global|secara\s+luas|overseas|worldwide|internasional|dunia|luar\s+negeri|barat|malaysia|singapura|singapore|brunei|thailand|filipina|vietnam|hong\s+kong|taiwan|china|tiongkok|inggris|uk|jepang|korea|eropa|kanada|australia|prancis|jerman|festival(?:\s+film)?|sundance|cannes|tiff|venice|berlinale))\b/gi;

const SCREENING_VERB_RE = /\b(tayang|rilis|dirilis|mulai|resmi|pre-?sale|buka|hadir)\b/i;

function extractAllScoredIndonesianDatesFromClause(clause, defaultYear, refDateIso) {
  if (!clause || RUMOR_HEADLINE_RE.test(clause)) return [];
  if (!SCREENING_VERB_RE.test(clause)) {
    return [];
  }
  const refClean = refDateIso && /^\d{4}-\d{2}-\d{2}$/.test(String(refDateIso).slice(0, 10)) ? String(refDateIso).slice(0, 10) : '';
  const refYear = refClean ? parseInt(refClean.slice(0, 4), 10) : defaultYear || new Date().getFullYear();
  const refMonth = refClean ? parseInt(refClean.slice(5, 7), 10) : 0;

  const matches = [];
  const pushValid = (day, month, year, hasExplicitYear, start, end) => {
    if (!month || day < 1 || day > 31 || year < 2000 || year > 2099) return;
    if (!hasExplicitYear && refClean && refMonth > 0) {
      const monthDiff = (year - refYear) * 12 + (month - refMonth);
      if (monthDiff < -2 || monthDiff > 8) return;
    }
    const iso = sanitizeIso(`${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`);
    if (!iso) return;
    const check = new Date(`${iso}T00:00:00Z`);
    if (Number.isNaN(check.getTime()) || check.getUTCMonth() + 1 !== month || check.getUTCDate() !== day) return;
    matches.push({ iso, start, end });
  };

  const namedRe =
    /\b([1-9]|[12]\d|3[01])(?:\s*(?:-|–|—|s\/d|s\.d\.|hingga|sampai)\s*(?:[1-9]|[12]\d|3[01]))?\s+(januari|jan|februari|feb|maret|mar|april|apr|mei|may|juni|jun|juli|jul|agustus|agu|ags|aug|september|sept|sep|oktober|okt|oct|november|nov|desember|des|dec)(?:\s*,?\s*(20\d{2}))?\b/gi;
  let nm;
  while ((nm = namedRe.exec(clause)) !== null) {
    const day = parseInt(nm[1], 10);
    const month = ID_MONTH_MAP[nm[2].toLowerCase()] || 0;
    const hasExplicitYear = Boolean(nm[3]);
    const year = nm[3] ? parseInt(nm[3], 10) : defaultYear || refYear;
    pushValid(day, month, year, hasExplicitYear, nm.index, nm.index + nm[0].length);
  }
  const numRe = /\b([0]?[1-9]|[12]\d|3[01])[\/\-]([0]?[1-9]|1[0-2])[\/\-](20\d{2})\b/g;
  let dm;
  while ((dm = numRe.exec(clause)) !== null) {
    pushValid(parseInt(dm[1], 10), parseInt(dm[2], 10), parseInt(dm[3], 10), true, dm.index, dm.index + dm[0].length);
  }
  if (matches.length === 0) return [];

  const idHits = Array.from(clause.matchAll(ID_DATE_LOCAL_CTX_RE)).map((m) => ({
    start: m.index ?? 0,
    end: (m.index ?? 0) + m[0].length,
  }));
  const globalHits = Array.from(clause.matchAll(GLOBAL_DATE_LOCAL_CTX_RE)).map((m) => ({
    start: m.index ?? 0,
    end: (m.index ?? 0) + m[0].length,
  }));
  const distToRange = (aStart, aEnd, bStart, bEnd) => {
    if (aEnd >= bStart && bEnd >= aStart) return 0;
    return bStart > aEnd ? bStart - aEnd : aStart - bEnd;
  };

  const out = [];
  for (const m of matches) {
    let minIdDist = Infinity;
    for (const h of idHits) {
      const d = distToRange(m.start, m.end, h.start, h.end);
      if (d < minIdDist) minIdDist = d;
    }
    let minGlobalDist = Infinity;
    for (const h of globalHits) {
      const d = distToRange(m.start, m.end, h.start, h.end);
      if (d < minGlobalDist) minGlobalDist = d;
    }
    const segStart = Math.max(clause.lastIndexOf(',', m.start), clause.lastIndexOf(';', m.start)) + 1;
    const nextComma = clause.indexOf(',', m.end);
    const nextSemi = clause.indexOf(';', m.end);
    const segEndCandidates = [nextComma, nextSemi].filter((x) => x !== -1);
    const segEnd = segEndCandidates.length > 0 ? Math.min(...segEndCandidates) : clause.length;
    const seg = clause.slice(segStart, segEnd);
    const segHasId = new RegExp(ID_DATE_LOCAL_CTX_RE.source, 'i').test(seg);
    const segHasGlobal = new RegExp(GLOBAL_DATE_LOCAL_CTX_RE.source, 'i').test(seg);
    let proximityScore = 0;
    if (segHasId) proximityScore += 80;
    if (segHasGlobal && !segHasId) proximityScore -= 85;
    if (minIdDist <= 55) proximityScore += 120 - minIdDist * 2;
    if (minGlobalDist <= 28 && minGlobalDist < minIdDist) proximityScore -= 95 - minGlobalDist * 2;
    if (proximityScore < 0 || (segHasGlobal && !segHasId)) continue;
    out.push({ iso: m.iso, proximityScore, seg });
  }
  return out;
}

function extractDateForMovieFromTexts(movieTitle, items, refDateIso) {
  let bestIso = '';
  let bestScore = -Infinity;
  const movieHasVs = /\bvs\b/i.test(movieTitle);
  for (const item of items) {
    const headline = String(item.title || '').trim();
    const snippet = String(item.snippet || '').trim();
    if (!headline && !snippet) continue;
    if (RUMOR_HEADLINE_RE.test(headline)) continue;
    const useQuestionBody = Boolean(item.allowQuestionBody && snippet.length > 220);
    if (isQuestionHeadline(headline) && !useQuestionBody) continue;
    const tierScore = getSourceTierScore(item.source, item.link);
    const bodyClauses = snippet
      .split(/[\n\r]+|(?<=[.!?])\s+/)
      .map((s) => s.trim())
      .filter(Boolean);
    const fullItemText = useQuestionBody ? snippet : `${headline}. ${snippet}`;
    const clauses = useQuestionBody
      ? bodyClauses
      : fullItemText
          .split(/[\n\r]+|(?<=[.!?])\s+/)
          .map((s) => s.trim())
          .filter(Boolean);

    const isListicle = LISTICLE_HEADLINE_RE.test(headline);
    if (isListicle && !item.allowListicle) continue;
    const isVsClash = !movieHasVs && /\bvs\b/i.test(headline);
    const yrMatch = /\b(20\d{2})\b/.exec(item.pubDate || '');
    const pubYear = yrMatch ? parseInt(yrMatch[1], 10) : undefined;
    let pubIso = '';
    if (item.pubDate) {
      const pd = Date.parse(item.pubDate);
      if (!Number.isNaN(pd)) pubIso = new Date(pd).toISOString().slice(0, 10);
    }
    const effectiveRefIso = refDateIso || pubIso || getWibTodayIso();
    const headlineMatchesTitle = titleMatchesHeadline(movieTitle, headline);

    const itemHasAbsence =
      isDisqualifiedByAbsenceGuard(fullItemText, false) || FOREIGN_CENSOR_OR_BAN_RE.test(fullItemText);
    if (itemHasAbsence) {
      const hasIndependentOfficialIdClause = clauses.some(
        (cl) =>
          (titleMatchesHeadline(movieTitle, cl) || headlineMatchesTitle) &&
          (OFFICIAL_CONFIRMED_PORTAL_SIGNAL_RE.test(maskNegatedCensorAndForeignPhrases(cl)) ||
            hasConfirmedLsfMention(cl)) &&
          !isDisqualifiedByAbsenceGuard(cl, false) &&
          !FOREIGN_CENSOR_OR_BAN_RE.test(cl)
      );
      if (!hasIndependentOfficialIdClause && tierScore < 3) continue;
    }

    for (const clause of clauses) {
      if (isDisqualifiedByAbsenceGuard(clause, false) || FOREIGN_CENSOR_OR_BAN_RE.test(clause)) continue;
      if (isQuestionHeadline(clause) || /\b(kapan|apakah|benarkah|kenapa|akankah)\b|\?/i.test(clause)) continue;
      const clauseMatchesTitle = titleMatchesHeadline(movieTitle, clause);
      const isLeadPronounClause =
        !clauseMatchesTitle &&
        headlineMatchesTitle &&
        !isListicle &&
        !isVsClash &&
        clauses.length <= 5 &&
        /\b(?:film|sekuel|prekuel|tayangan)\s+(?:ini|tersebut)\b/i.test(clause) &&
        sequelMarkersCompatible(movieTitle, clause);
      if (!clauseMatchesTitle && !isLeadPronounClause) continue;
      const hasExplicitIdContext =
        /\b(di\s+indonesia|bioskop\s+indonesia|tanah\s+air|lebih\s+awal|xxi|cinema\s*21|cinema\s*xxi|cgv|cinepolis|cinépolis|tix\s*id|resmi)\b/i.test(clause);
      if (isVsClash && !hasExplicitIdContext) continue;
      let tierBase = (hasExplicitIdContext ? 300 : 200) + tierScore * 150;
      if (!clauseMatchesTitle) tierBase -= 80;
      if (isVsClash) tierBase -= 120;
      const isEnumerationClause =
        isListicle || /\b(?:yakni|yaitu|seperti|adalah|meliputi|antara\s+lain)\b/i.test(clause);
      for (const cand of extractAllScoredIndonesianDatesFromClause(clause, pubYear, effectiveRefIso)) {
        if (isEnumerationClause && !titleMatchesHeadline(movieTitle, cand.seg)) continue;
        const segBonus = titleMatchesHeadline(movieTitle, cand.seg) ? 60 : 0;
        const totalScore = tierBase + segBonus + cand.proximityScore;
        if (totalScore > bestScore) {
          bestScore = totalScore;
          bestIso = cand.iso;
        }
      }
    }
  }
  return bestIso || '';
}

function extractBalancedJsonArray(str, keyRe) {
  const m = keyRe.exec(str);
  if (!m) return null;
  const start = str.indexOf('[', m.index + m[0].length - 1);
  if (start === -1) return null;
  let depth = 0;
  let inStr = false;
  let esc = false;
  for (let i = start; i < str.length; i++) {
    const ch = str[i];
    if (inStr) {
      if (esc) esc = false;
      else if (ch === '\\') esc = true;
      else if (ch === '"') inStr = false;
    } else {
      if (ch === '"') inStr = true;
      else if (ch === '[') depth++;
      else if (ch === ']') {
        depth--;
        if (depth === 0) {
          try {
            const p = JSON.parse(str.slice(start, i + 1));
            return Array.isArray(p) ? p : null;
          } catch {
            return null;
          }
        }
      }
    }
  }
  return null;
}

async function fetchIndonesianCinemaFeed() {
  const entries = new Map();
  const todayIso = getWibTodayIso();
  const currentYear = parseInt(todayIso.slice(0, 4), 10) || 2026;

  const upsertEntry = (rawTitle, incoming) => {
    const imax = Boolean(incoming.imax || /\bimax\b/i.test(rawTitle));
    const isReRelease = Boolean(
      incoming.isReRelease || /\b(re-?release|reissue|tayang\s+(?:ulang|kembali)|remaster(?:ed)?|encore)\b/i.test(rawTitle)
    );
    const clean = stripCinemaFormatSuffixes(rawTitle);
    const k = normalizeKey(clean);
    if (!clean || !k) return;
    const prev = entries.get(k);
    if (!prev) {
      entries.set(k, {
        title: clean,
        releaseDateId: incoming.releaseDateId || undefined,
        status: incoming.status || 'upcoming',
        chains: incoming.chains || [],
        imax,
        presaleActive: Boolean(incoming.presaleActive),
        isReRelease: isReRelease || undefined,
        lsfStatus: incoming.lsfStatus || undefined,
        source: incoming.source,
        sourceUrl: incoming.sourceUrl,
      });
      return;
    }
    if (!prev.releaseDateId && incoming.releaseDateId) prev.releaseDateId = incoming.releaseDateId;
    if (incoming.status === 'now') prev.status = 'now';
    prev.chains = Array.from(new Set([...prev.chains, ...(incoming.chains || [])]));
    prev.imax = Boolean(prev.imax || imax);
    prev.presaleActive = Boolean(prev.presaleActive || incoming.presaleActive);
    prev.isReRelease = Boolean(prev.isReRelease || isReRelease);
    if (!prev.lsfStatus && incoming.lsfStatus) prev.lsfStatus = incoming.lsfStatus;
    if (prev.chains.length === 0 && (incoming.chains || []).length > 0) {
      prev.source = incoming.source;
      prev.sourceUrl = incoming.sourceUrl;
    }
  };

  // Tier 3 Official Sources: Cinema XXI (now + coming soon), CGV, TIX ID, Jadwalnonton, LSF RI
  try {
    const xxiUrls = [
      { url: 'https://m.21cineplex.com/', status: 'now' },
      { url: 'https://m.21cineplex.com/gui.coming_soon.php?order=2', status: 'upcoming' },
    ];
    await Promise.all(
      xxiUrls.map(async ({ url: u, status }) => {
        try {
          const res = await fetch(u, {
            headers: { 'User-Agent': 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36' },
            signal: AbortSignal.timeout(7000),
          });
          if (!res.ok) return;
          const html = await res.text();
          const patterns = [
            /<a[^>]+href=["'][^"']*(?:movie_details|gui\.movie_details|movie_id=)[^"']*["'][^>]*>[\s\S]{0,450}?<(?:img[^>]+alt=["']([^"']{2,90})["']|div[^>]*class=["'][^"']*(?:title|grid_title)[^"']*["'][^>]*>([^<]{2,90})<|h[1-5][^>]*>([^<]{2,90})<)/gi,
            /data-movie-title=["']([^"']{2,90})["']/gi,
            /class=["'][^"']*movie-title[^"']*["'][^>]*>([^<]{2,90})</gi,
          ];
          for (const rx of patterns) {
            let xm;
            while ((xm = rx.exec(html)) !== null) {
              const cand = decodeXmlEntities(xm[1] || xm[2] || xm[3] || '').trim();
              if (!cand || cand.length < 2 || /^(?:cinema\s*21|cinema\s*xxi|21cineplex|now\s+playing|coming\s+soon|theaters?|imax|premiere|mtix|home|login)$/i.test(cand)) {
                continue;
              }
              const startCtx = Math.max(0, xm.index - 60);
              const endCtx = Math.min(html.length, xm.index + xm[0].length + 220);
              const ctxText = decodeXmlEntities(html.slice(startCtx, endCtx).replace(/<[^>]+>/g, ' '));
              const relIso = extractDateForMovieFromTexts(cand, [{ title: cand, snippet: ctxText, source: 'Cinema XXI', link: u }], todayIso);
              const isPre =
                /\b(pre-?sale|presale)\b/i.test(`${cand} ${ctxText}`) &&
                (status === 'upcoming' || /\b(dibuka|tersedia|beli)\b/i.test(ctxText));
              upsertEntry(cand, {
                releaseDateId: relIso || undefined,
                status,
                chains: ['XXI'],
                presaleActive: isPre,
                source: 'Cinema XXI',
                sourceUrl: u,
              });
            }
          }
        } catch {
          // ignore
        }
      })
    );
  } catch {
    // ignore
  }

  try {
    const cgvUrls = ['https://www.cgv.id/en/movies/now_playing', 'https://www.cgv.id/movies'];
    await Promise.all(
      cgvUrls.map(async (cUrl) => {
        try {
          const res = await fetch(cUrl, {
            headers: { 'User-Agent': 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36' },
            signal: AbortSignal.timeout(7000),
          });
          if (!res.ok) return;
          const html = await res.text();
          const pushRe = /self\.__next_f\.push\(\[1,"([\s\S]*?)"\]\)/g;
          let decoded = '';
          let pm;
          while ((pm = pushRe.exec(html)) !== null) {
            try {
              decoded += JSON.parse(`"${pm[1]}"`);
            } catch {
              // ignore
            }
          }
          const consume = (arr, status) => {
            for (const r of arr || []) {
              if (!r || !(r.name || r.title)) continue;
              const rawName = String(r.name || r.title);
              const rel = !r.hide_opening_date ? sanitizeIso(r.opening_date) : '';
              upsertEntry(rawName, {
                releaseDateId: rel || undefined,
                status,
                chains: ['CGV'],
                imax: /\bimax\b/i.test(`${rawName} ${r.type || ''}`),
                presaleActive: Boolean(r.is_presale || /\bpre-?sale\b/i.test(`${rawName} ${r.type || ''} ${r.status || ''}`)),
                source: 'CGV Indonesia',
                sourceUrl: cUrl,
              });
            }
          };
          consume(extractBalancedJsonArray(decoded, /"moviePlayings"\s*:\s*\[/), 'now');
          consume(extractBalancedJsonArray(decoded, /"movieUpcomings"\s*:\s*\[/), 'upcoming');
          consume(extractBalancedJsonArray(decoded, /"presales"\s*:\s*\[/), 'upcoming');
        } catch {
          // ignore
        }
      })
    );
  } catch {
    // ignore
  }

  try {
    const res = await fetch('https://www.tix.id/', {
      headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36' },
      signal: AbortSignal.timeout(7000),
    });
    if (res.ok) {
      const html = await res.text();
      const linkRe = /href=["'](https:\/\/app\.tix\.id\/movies\/([a-z0-9-]+)-\d+\/(\d{4}-\d{2}-\d{2}))["'][^>]*>([\s\S]*?)<\/a>/gi;
      let m;
      while ((m = linkRe.exec(html)) !== null) {
        const inner = decodeXmlEntities(m[4] || '').replace(/^(?:beli\s+tiket|pre-?sale|coming\s+soon|now\s+playing)\s*/i, '').trim();
        const title = inner && inner.length >= 2 && inner.length <= 80 ? inner : m[2].replace(/-/g, ' ');
        const rel = sanitizeIso(m[3]);
        const isPre = /\b(pre-?sale|presale)\b/i.test(m[0]);
        upsertEntry(title, {
          releaseDateId: rel || undefined,
          status: rel && rel <= todayIso ? 'now' : 'upcoming',
          chains: [],
          imax: /\bimax\b/i.test(title),
          presaleActive: isPre,
          source: 'TIX ID',
          sourceUrl: m[1],
        });
      }
    }
  } catch {
    // ignore
  }

  try {
    const jnUrls = [
      { url: 'https://jadwalnonton.com/', status: 'now' },
      { url: 'https://jadwalnonton.com/comingsoon/', status: 'upcoming' },
    ];
    await Promise.all(
      jnUrls.map(async ({ url: jUrl, status }) => {
        try {
          const res = await fetch(jUrl, {
            headers: { 'User-Agent': 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36' },
            signal: AbortSignal.timeout(7000),
          });
          if (!res.ok) return;
          const html = await res.text();
          const cardRe = /<a[^>]+href=["'](https?:\/\/(?:www\.)?jadwalnonton\.com\/film\/[^"']+)["'][^>]*>([\s\S]{0,600}?)<\/a>/gi;
          let jm;
          while ((jm = cardRe.exec(html)) !== null) {
            const href = jm[1];
            const inner = jm[2];
            const titleM = /alt=["']([^"']{2,90})["']|<(?:h[2-5]|strong|div)[^>]*>([^<]{2,90})<\//i.exec(inner);
            const rawTitle = decodeXmlEntities((titleM && (titleM[1] || titleM[2])) || '').trim();
            if (!rawTitle || rawTitle.length < 2) continue;
            const ctx = stripHtmlToText(html.slice(Math.max(0, jm.index - 40), Math.min(html.length, jm.index + jm[0].length + 220)));
            const chains = detectChainsFromText(ctx);
            const relIso = extractDateForMovieFromTexts(rawTitle, [{ title: rawTitle, snippet: ctx, source: 'Jadwalnonton', link: href }], todayIso);
            upsertEntry(rawTitle, {
              releaseDateId: relIso || undefined,
              status,
              chains,
              imax: /\bimax\b/i.test(`${rawTitle} ${ctx}`),
              presaleActive: /\bpre-?sale\b/i.test(ctx),
              source: 'Jadwalnonton',
              sourceUrl: href,
            });
          }
        } catch {
          // ignore
        }
      })
    );
  } catch {
    // ignore
  }

  try {
    const res = await fetch('https://lsf.go.id/film', {
      headers: { 'User-Agent': 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36' },
      signal: AbortSignal.timeout(7000),
    });
    if (res.ok) {
      const html = await res.text();
      const itemRe = /href=["'](https?:\/\/lsf\.go\.id\/film\/[^"']+)["'][^>]*>[\s\S]{0,600}?<(?:h[2-5]|div|span|p)[^>]*>([^<]{2,95})</gi;
      let lm;
      while ((lm = itemRe.exec(html)) !== null) {
        const rawTitle = decodeXmlEntities(lm[2]).trim();
        if (!rawTitle || rawTitle.length < 2 || /^(?:beranda|profil|film|sensor|berita)$/i.test(rawTitle)) continue;
        const ctx = stripHtmlToText(html.slice(lm.index, Math.min(html.length, lm.index + 500)));
        const ageMatch = /\b(SU|R\s*13\+|D\s*17\+|D\s*21\+)\b/i.exec(ctx);
        const yrMatch = /\b(19\d{2}|20\d{2})\b/.exec(ctx);
        const prodYr = yrMatch ? parseInt(yrMatch[1], 10) : 0;
        const isRe = prodYr > 1900 && prodYr < currentYear - 1;
        upsertEntry(rawTitle, {
          status: 'lsf_passed',
          chains: [],
          isReRelease: isRe,
          lsfStatus: ageMatch ? `Lulus Sensor LSF (${ageMatch[1].toUpperCase()})` : 'Lulus Sensor LSF RI',
          source: 'LSF RI',
          sourceUrl: lm[1],
        });
      }
    }
  } catch {
    // ignore
  }

  return entries;
}

async function runFreeRssAndTmdbScan(tmdbCandidates, tmdbKey, nowIso) {
  const feedMap = await fetchIndonesianCinemaFeed().catch(() => new Map());
  const todayIso = getWibTodayIso();
  const targets = tmdbCandidates.slice(0, 12);

  const results = await mapConcurrent(targets, 4, async (candidate) => {
    const cleanPunctTitle = String(candidate.title || '')
      .replace(/[:\-–—'"‘’“”\/.,!?()\[\]]+/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
    const qBase = cleanPunctTitle || candidate.title;
    const qVariants = buildSearchTitleVariants(qBase);
    const feedHit = feedMap.get(normalizeKey(candidate.title)) || feedMap.get(normalizeKey(candidate.originalTitle || ''));

    const [idMeta, bingItems, googleItems] = await Promise.all([
      fetchTmdbIdReleaseMeta(candidate.tmdbId, tmdbKey),
      fetchBingNewsIdRss(`${qVariants.bingQueryTerm} jadwal tayang bioskop Indonesia XXI CGV`),
      fetchGoogleNewsIdRss(`${qVariants.googleExpr} (XXI OR CGV OR Cinepolis OR bioskop OR LSF OR "pre-sale" OR IMAX) when:90d`),
    ]);

    const seenUrls = new Set();
    const newsItems = [];
    for (const item of [...bingItems, ...googleItems]) {
      if (!seenUrls.has(item.link)) {
        seenUrls.add(item.link);
        newsItems.push(item);
      }
    }

    const relevantNews = newsItems
      .filter((item) => {
        if (RUMOR_HEADLINE_RE.test(item.title)) return false;
        const tier = getSourceTierScore(item.source, item.link);
        if (isQuestionHeadline(item.title) && tier < 2) return false;
        const combinedItem = `${item.title} ${item.snippet || ''} ${item.source || ''}`;
        if (FOREIGN_CENSOR_OR_BAN_RE.test(combinedItem)) return false;
        if (FOREIGN_WIRE_RE.test(combinedItem) && !hasExplicitIndonesiaVenue(combinedItem)) return false;
        return titleMatchesHeadline(candidate.title, item.title);
      })
      .sort((a, b) => {
        const tierDiff = getSourceTierScore(b.source, b.link) - getSourceTierScore(a.source, a.link);
        if (tierDiff !== 0) return tierDiff;
        const qDiff = (isQuestionHeadline(a.title) ? 1 : 0) - (isQuestionHeadline(b.title) ? 1 : 0);
        if (qDiff !== 0) return qDiff;
        const aId = hasExplicitIndonesiaVenue(`${a.title} ${a.snippet || ''}`) ? 1 : 0;
        const bId = hasExplicitIndonesiaVenue(`${b.title} ${b.snippet || ''}`) ? 1 : 0;
        return bId - aId;
      });

    const verifiedNews = [];
    const bodyCandidates = relevantNews.slice(0, 4);
    await mapConcurrent(bodyCandidates, 3, async (item) => {
      const snippetCombined = `${item.title} ${item.snippet || ''}`;
      const tierPre = getSourceTierScore(item.source, item.link);
      const isQHeadline = isQuestionHeadline(item.title);
      if (isDisqualifiedByAbsenceGuard(snippetCombined, tierPre >= 3)) {
        return;
      }
      if (feedHit?.releaseDateId && idMeta?.releaseDateId) {
        if (!isQHeadline) verifiedNews.push(item);
        return;
      }
      const art = await fetchArticleBodyText(item.link);
      const bodyText = art.text || '';
      const resolvedUrl = art.url || item.link;
      if (!bodyText) {
        if (!isQHeadline && !isDisqualifiedByAbsenceGuard(snippetCombined, false)) {
          verifiedNews.push(item);
        }
        return;
      }
      const fullCombo = isQHeadline ? bodyText : `${item.title}. ${bodyText}`;
      const tierScore = getSourceTierScore(item.source, resolvedUrl);
      if (isDisqualifiedByAbsenceGuard(fullCombo, tierScore >= 3)) {
        return;
      }
      const hasIdVenue = hasExplicitIndonesiaVenue(fullCombo);
      if ((FOREIGN_WIRE_RE.test(fullCombo) || GLOBAL_ONLY_SUMMARY_RE.test(fullCombo)) && !hasIdVenue) {
        return;
      }
      if (!titleMatchesHeadline(candidate.title, bodyText)) {
        return;
      }
      verifiedNews.push({
        ...item,
        snippet: bodyText,
        link: resolvedUrl,
        allowQuestionBody: isQHeadline,
      });
    });

    const usableNews = verifiedNews.sort((a, b) => getSourceTierScore(b.source, b.link) - getSourceTierScore(a.source, a.link));
    const safeBodyJoined = usableNews.map((n) => (n.allowQuestionBody ? n.snippet || '' : `${n.title}. ${n.snippet || ''}`)).join('\n');
    const chainSet = new Set([...(feedHit?.chains || []), ...detectChainsFromText(safeBodyJoined)]);
    const chains = Array.from(chainSet);
    const imax = Boolean(feedHit?.imax || /\bimax\b/i.test(safeBodyJoined));
    const presaleActive = Boolean(feedHit?.presaleActive || usableNews.some((n) => isAffirmativePresaleHeadline(`${n.title} ${n.snippet || ''}`)));
    const lsfMention = Boolean(feedHit?.lsfStatus || hasConfirmedLsfMention(safeBodyJoined));

    const extractedRssDate = extractDateForMovieFromTexts(candidate.title, usableNews, todayIso);
    const resolvedReleaseDateId = feedHit?.releaseDateId || idMeta?.releaseDateId || extractedRssDate || '';
    const dateSource = feedHit?.releaseDateId
      ? 'cinema_chain'
      : idMeta?.releaseDateId
      ? 'tmdb_id'
      : extractedRssDate
      ? 'rss'
      : undefined;

    if (!resolvedReleaseDateId && usableNews.length === 0 && !feedHit) {
      return null;
    }
    if (!resolvedReleaseDateId && chains.length === 0 && !lsfMention && !feedHit) {
      return null;
    }

    let status = 'upcoming_global';
    if (presaleActive) status = 'presale';
    else if (resolvedReleaseDateId || chains.length > 0 || (imax && hasExplicitIndonesiaVenue(safeBodyJoined))) {
      status = 'confirmed';
    } else if (lsfMention) status = 'lsf_passed';

    const certText = idMeta?.certification ? `Klasifikasi TMDB ID: ${idMeta.certification}` : '';
    const lsfStatus = feedHit?.lsfStatus || certText || (lsfMention ? 'Disebutkan di berita sensor LSF' : '');
    const headlineSnippet = usableNews[0]?.title ? `Berita ID: ${usableNews[0].title}` : '';
    const summary = [
      resolvedReleaseDateId ? `Jadwal resmi ID: ${resolvedReleaseDateId}.` : '',
      feedHit ? `Terdaftar di ${feedHit.source}.` : '',
      headlineSnippet,
    ]
      .filter(Boolean)
      .join(' ')
      .slice(0, 260);

    const sources = [];
    if (feedHit?.sourceUrl) sources.push({ title: feedHit.source.slice(0, 64), url: feedHit.sourceUrl });
    for (const n of usableNews) {
      if (sources.some((s) => s.url === n.link)) continue;
      sources.push({ title: (n.source || 'Berita ID').slice(0, 64), url: n.link });
      if (sources.length >= 3) break;
    }

    return {
      key: `tmdb_${candidate.tmdbId}`,
      tmdbId: candidate.tmdbId,
      title: String(candidate.title).trim().toUpperCase().slice(0, 100),
      releaseDateId: resolvedReleaseDateId || undefined,
      dateSource,
      status,
      chains,
      imax,
      presaleActive,
      lsfStatus,
      summary: summary || 'Terdeteksi pada radar jadwal/berita bioskop Indonesia.',
      sources,
      checkedAt: nowIso,
    };
  });

  return results.filter(Boolean);
}

async function runGeminiSearchScan(geminiKey, tmdbCandidates, nowIso) {
  const ai = new GoogleGenAI({
    apiKey: geminiKey,
    apiVersion: 'v1beta',
    httpOptions: {
      apiVersion: 'v1beta',
      headers: { 'User-Agent': 'aistudio-build' },
    },
  });

  const hintBlock = tmdbCandidates.length
    ? `Prioritaskan pengecekan untuk daftar film populer berikut jika ada kabar rilis Indonesia:\n${tmdbCandidates
        .slice(0, 16)
        .map((c) => `- ${c.title} (tmdbId: ${c.tmdbId}, global: ${c.releaseDate})`)
        .join('\n')}`
    : '';

  const todayIso = getWibTodayIso();
  const prompt = [
    `Tanggal hari ini (WIB): ${todayIso}.`,
    'Prioritas sumber verifikasi (dari paling dipercaya): (1) Website resmi jaringan bioskop Indonesia (21cineplex.com, cgv.id, cinepolis.co.id, tix.id, jadwalnonton.com, lsf.go.id), (2) Media Indonesia kredibel (Kompas, Detik, Kumparan, Tirto, IDN Times, Tempo, Antara, Katadata), (3) Artikel lain yang menyebut tanggal resmi Indonesia.',
    'Cari pengumuman resmi jadwal rilis bioskop INDONESIA terbaru (termasuk penayangan ulang / reissue maupun film besar bulan ini dan 2 bulan ke depan), film yang baru lulus sensor LSF (lsf.go.id), atau pembukaan tiket pre-sale / IMAX di Cinema XXI, CGV Indonesia, Cinépolis Indonesia, atau TIX ID.',
    hintBlock,
    'ATURAN KETAT: Hanya masukkan film yang BENAR-BENAR memiliki kabar/pengumuman rilis di bioskop Indonesia. DILARANG memasukkan film yang hanya tayang global/festival atau artikel yang menyatakan jadwal Indonesia belum ada / belum dipastikan.',
    'Balas HANYA 1 JSON valid:',
    '{',
    '  "entries": [',
    '    {',
    '      "tmdbId": 0,',
    '      "title": "JUDUL FILM",',
    '      "releaseDateId": "YYYY-MM-DD atau kosong",',
    '      "status": "confirmed" | "presale" | "lsf_passed",',
    '      "chains": ["XXI", "CGV", "Cinépolis"],',
    '      "imax": false,',
    '      "presaleActive": false,',
    '      "lsfStatus": "opsional",',
    '      "summary": "1 kalimat ringkas fakta jadwal ID."',
    '    }',
    '  ]',
    '}',
  ]
    .filter(Boolean)
    .join('\n\n');

  const models = ['gemini-2.5-flash', 'gemini-3.1-flash-lite'];
  let response = null;
  let lastErr = null;
  for (const model of models) {
    try {
      response = await ai.models.generateContent({
        model,
        contents: prompt,
        config: {
          systemInstruction:
            'Anda aggregator jadwal bioskop Indonesia (XXI, CGV, Cinépolis, IMAX, LSF). Keluarkan hanya entri yang terverifikasi tayang di bioskop Indonesia berdasarkan hasil Google Search.',
          tools: [{ googleSearch: {} }],
        },
      });
      if (response) break;
    } catch (err) {
      lastErr = err;
    }
  }
  if (!response) throw lastErr || new Error('Gemini grounding unavailable');

  const parsed = parseJsonPayload(response.text || '');
  const sharedSources = extractGroundSources(response);
  const rawEntries = Array.isArray(parsed?.entries) ? parsed.entries : [];

  const titleToTmdb = new Map(tmdbCandidates.map((c) => [normalizeKey(c.title), c.tmdbId]));
  const seenKeys = new Set();
  const entries = [];

  for (const raw of rawEntries) {
    const title = String(raw?.title || '').trim().toUpperCase().slice(0, 100);
    if (!title) continue;
    const summary = String(raw?.summary || '').trim().slice(0, 260) || 'Terkonfirmasi masuk radar rilis Indonesia.';
    const lsfStatus = String(raw?.lsfStatus || '').trim().slice(0, 80);
    const chains = normalizeChains(raw?.chains);
    const resolvedRel = sanitizeIso(raw?.releaseDateId) || undefined;
    if (
      isQuestionHeadline(summary) ||
      FOREIGN_CENSOR_OR_BAN_RE.test(`${summary} ${lsfStatus}`) ||
      isDisqualifiedByAbsenceGuard(`${summary} ${lsfStatus}`, false)
    ) {
      continue;
    }
    if (
      GLOBAL_ONLY_SUMMARY_RE.test(summary) &&
      chains.length === 0 &&
      !hasExplicitIndonesiaVenue(`${summary} ${lsfStatus}`)
    ) {
      continue;
    }
    if (!resolvedRel && chains.length === 0 && !lsfStatus && !hasExplicitIndonesiaVenue(summary)) {
      continue;
    }

    const tmdbNum = Number(raw?.tmdbId || 0) || titleToTmdb.get(normalizeKey(title)) || 0;
    const key = tmdbNum > 0 ? `tmdb_${tmdbNum}` : `title_${normalizeKey(title)}`;
    if (seenKeys.has(key)) continue;
    seenKeys.add(key);

    entries.push({
      key,
      tmdbId: tmdbNum > 0 ? tmdbNum : undefined,
      title,
      releaseDateId: resolvedRel,
      dateSource: resolvedRel ? 'gemini' : undefined,
      status: ['confirmed', 'presale', 'lsf_passed'].includes(raw?.status) ? raw.status : 'confirmed',
      chains,
      imax: Boolean(raw?.imax),
      presaleActive: Boolean(raw?.presaleActive),
      lsfStatus,
      summary,
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
  const baseEntries = await runFreeRssAndTmdbScan(tmdbCandidates, tmdbKey, nowIso);
  const mergedMap = new Map(baseEntries.map((e) => [e.key, e]));
  let sourceLabel = 'cloud-radar-rss-tmdb';

  if (geminiKey) {
    try {
      const geminiEntries = await runGeminiSearchScan(geminiKey, tmdbCandidates, nowIso);
      for (const ge of geminiEntries) {
        const prev = mergedMap.get(ge.key);
        if (!prev) {
          mergedMap.set(ge.key, ge);
        } else {
          const mergedSources = [...(prev.sources || [])];
          for (const src of ge.sources || []) {
            if (src?.url && !mergedSources.some((s) => s.url === src.url)) {
              mergedSources.push(src);
            }
          }
          mergedMap.set(ge.key, {
            ...prev,
            releaseDateId: ge.releaseDateId || prev.releaseDateId,
            dateSource: ge.releaseDateId ? 'gemini' : prev.dateSource,
            status: ge.status || prev.status,
            chains: Array.from(new Set([...(prev.chains || []), ...(ge.chains || [])])),
            imax: Boolean(prev.imax || ge.imax),
            presaleActive: Boolean(prev.presaleActive || ge.presaleActive),
            lsfStatus: ge.lsfStatus || prev.lsfStatus,
            summary: ge.summary || prev.summary,
            sources: mergedSources.slice(0, 4),
            checkedAt: nowIso,
          });
        }
      }
      sourceLabel = 'cloud-radar-gemini+rss';
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      console.warn(`Gemini grounding fallback triggered (${msg.slice(0, 180)}).`);
    }
  }

  const entries = Array.from(mergedMap.values()).sort((a, b) => {
    const da = a.releaseDateId || '9999-12-31';
    const db = b.releaseDateId || '9999-12-31';
    return da.localeCompare(db);
  });

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
