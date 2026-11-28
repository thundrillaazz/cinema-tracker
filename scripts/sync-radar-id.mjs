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
  /\b(rumor|rumours?|rumors?|kabarnya|diduga|prediksi|bocoran|diperkirakan|konon|belum\s+pasti|spekulasi|hoaks?|hoax|fan[-\s]?made|concept\s+trailer|estimasi|tidak\s+(?:akan\s+)?tayang\s+di\s+(?:bioskop\s+)?indonesia|batal\s+tayang(?:\s+di\s+indonesia)?|hanya\s+tayang\s+di\s+(?:bioskop\s+)?(?:global|amerika|us))\b/i;
const UNCONFIRMED_ID_RE =
  /\b(belum\s+(?:ada\s+(?:informasi|kabar|jadwal|kepastian|tanda(?:-tanda)?)|pasti|diumumkan|dapat\s+dipastikan|dipastikan|diketahui|terjadwal|tayang\s+di\s+indonesia))\b/i;
const QUESTION_HEADLINE_RE =
  /(?:^\s*(?:kapan|apakah|bagaimana|mengapa|kenapa|berapa|benarkah|akankah|sudahkah)\b|\b(?:tayang\s+kapan|kapan\s+tayang(?:\s+di\s+indonesia)?|rilis\s+kapan|kapan\s+rilis|tayang\s+di\s+indonesia\s*\?|di\s+bioskop\s+indonesia\s*\?)\b|\?\s*(?:[-–—|•·]\s*[^?]{1,40})?\$)/i;
const INDONESIAN_ABSENCE_PHRASE_RE =
  /\b(belum\s+(?:ada\s+(?:informasi|kabar|jadwal|kepastian|tanda(?:-tanda)?)|diketahui|diumumkan|terjadwal|dapat\s+dipastikan|dipastikan|resmi)(?:\s+kapan)?(?:\s+(?:akan|bakal))?(?:\s+(?:tayang|rilis|masuk))?(?:\s+di\s+(?:bioskop\s+)?indonesia)?|(?:jadwal|tanggal)\s+(?:tayang|rilis)(?:nya)?\s+di\s+(?:bioskop\s+)?indonesia\s+(?:belum|masih\s+belum|tidak)|tidak\s+(?:ada|akan|tayang|masuk)\s+(?:jadwal|penayangan)?\s*(?:di\s+)?(?:bioskop\s+)?indonesia|hanya\s+(?:tayang|rilis)\s+(?:secara\s+terbatas|di\s+festival|di\s+luar\s+negeri|secara\s+global)|belum\s+tayang\s+di\s+(?:bioskop\s+)?indonesia|belum\s+masuk\s+bioskop\s+indonesia)\b/i;
const CONFIRMED_SIGNAL_RE =
  /\b(tayang\s+(?:di\s+bioskop|mulai|tanggal)|jadwal\s+tayang|tiket\s+(?:pre-?sale|presale|sudah|tersedia)|lulus\s+sensor|lsf|xxi|cgv|cin[eé]polis|tix\s*id|reissue|tayang\s+ulang)\b/i;
const STREAMING_ONLY_SOURCE_RE =
  /\b(disney\s*\+?|netflix|prime\s*video|amazon|apple\s*tv|hbo|max|vidio|viu|wetv|iqiyi|catchplay|hotstar)\b/i;
const EXPLICIT_INDONESIA_VENUE_RE =
  /\b(xxi|cinema\s*21|cinema\s*xxi|21cineplex|cgv|cin[eé]polis|tix\s*id|\blsf\b|lulus\s+sensor|bioskop\s+(?:di\s+)?indonesia|di\s+bioskop\s+indonesia|seluruh\s+bioskop\s+indonesia|jaringan\s+bioskop\s+indonesia|tanah\s+air|di\s+indonesia)\b/i;
const FOREIGN_WIRE_RE =
  /\b(imax\s*70\s*mm|70\s*mm\s+imax|melansir\s+(?:variety|deadline|hollywood\s+reporter|collider|screenrant|indiewire|thewrap)|dilansir\s+dari\s+(?:variety|deadline|hollywood\s+reporter|collider|screenrant|indiewire|thewrap)|mengutip\s+(?:laporan\s+)?(?:variety|deadline|hollywood\s+reporter|collider|screenrant|indiewire|thewrap)|via\s+(?:variety|deadline|hollywood\s+reporter)|amerika\s+serikat|bioskop\s+(?:as|amerika|global|inggris|jepang|korea)|festival\s+film|sundance|cannes|tiff|venice|berlinale)\b/i;
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
  /\s*[\[(]\s*(?:re-?release\vert{}reissue\vert{}tayang\s+(?:ulang\vert{}kembali)\vert{}remaster(?:ed)?\vert{}imax\s*2d\vert{}imax\s*3d\vert{}imax\vert{}2d\vert{}3d\vert{}atmos\vert{}dolby\s*atmos\vert{}screenx\vert{}4dx\vert{}starium\vert{}reald\s*3d)\s*[\])]/gi;

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
  if (/(kompas\.com|detik\.com|kumparan\.com|tirto\.id|idntimes\.com|tempo\.co|kapanlagi\.com|antaranews\.com|merdeka\.com|liputan6\.com|cnnindonesia\.com|cnbcindonesia\.com|rri\.co\.id|katadata\.co\.id|hypeabis\.id|jawapos\.com|suara\.com|tribunnews\.com)/.test(s)) {
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
    if ((cur === 'part' || cur === 'pt' || cur === 'bagian') && /^\d+\$/.test(next)) continue;
    out.push(cur);
  }
  return out;
}

function normalizeKey(str) {
  return tokenizeNormalizedTitle(str).join('');
}

function sanitizeIso(raw) {
  const clean = String(raw || '').trim().slice(0, 10);
  if (!/^\d{4}-\d{2}-\d{2}\$/.test(clean) || clean.endsWith('-12-31')) return '';
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
  const s = String(text || '').toLowerCase();
  const out = [];
  if (/\b(xxi|cinema\s*21|cinema\s*xxi|21cineplex)\b/i.test(s)) out.push('XXI');
  if (/\bcgv\b/i.test(s)) out.push('CGV');
  if (/cin[eé]polis/i.test(s)) out.push('Cinépolis');
  return out;
}

function decodeXmlEntities(str) {
  return String(str || '')
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '\$1')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'")
    .replace(/&nbsp;/gi, ' ')
    .trim();
}

function stripHtmlToText(html) {
  return decodeXmlEntities(
    String(html || '')
      .replace(/<script[\s\S]*?<\/script>/gi, ' ')
      .replace(/<style[\s\S]*?<\/style>/gi, ' ')
      .replace(/<svg[\s\S]*?<\/svg>/gi, ' ')
      .replace(/<noscript[\s\S]*?<\/noscript>/gi, ' ')
      .replace(/<!--[\s\S]*?-->/g, ' ')
      .replace(/<\/(?:t[dh]|td|th)>/gi, ' · ')
      .replace(/<\/(?:tr|li|h[1-6]|p|div|section|article|blockquote)>/gi, '\n')
      .replace(/<br\s*\/?>/gi, '\n')
      .replace(/<[^>]+>/g, ' ')
      .replace(/[ \t]+/g, ' ')
      .replace(/\n[ \t]+/g, '\n')
      .replace(/\n{2,}/g, '\n')
  ).slice(0, 28000);
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
