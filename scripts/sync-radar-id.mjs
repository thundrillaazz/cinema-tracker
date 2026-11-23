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

async function fetchTmdbSeedTitles(tmdbKey) {
  if (!tmdbKey) return [];
  const today = new Date().toISOString().slice(0, 10);
  const urls = [
    `https://api.themoviedb.org/3/trending/movie/week?api_key=${encodeURIComponent(tmdbKey)}`,
    `https://api.themoviedb.org/3/movie/upcoming?api_key=${encodeURIComponent(tmdbKey)}&region=ID&page=1`,
  ];
  const map = new Map();
  for (const u of urls) {
    try {
      const res = await fetch(u);
      if (!res.ok) continue;
      const data = await res.json();
      for (const item of (data.results || []).slice(0, 12)) {
        if (!item?.id || !item?.title) continue;
        if (!map.has(item.id)) {
          map.set(item.id, {
            tmdbId: item.id,
            title: item.title,
            releaseDate: item.release_date || today,
          });
        }
      }
    } catch {
      // ignore individual upstream timeout
    }
  }
  return Array.from(map.values()).slice(0, 14);
}

async function main() {
  const geminiKey = (process.env.GEMINI_API_KEY || '').trim();
  if (!geminiKey) {
    console.log('GEMINI_API_KEY tidak tersedia; mempertahankan radar-id.json saat ini.');
    process.exit(0);
  }

  const ai = new GoogleGenAI({
    apiKey: geminiKey,
    httpOptions: {
      headers: { 'User-Agent': 'aistudio-build' },
    },
  });

  const tmdbCandidates = await fetchTmdbSeedTitles((process.env.TMDB_API_KEY || '').trim());
  const hintBlock = tmdbCandidates.length
    ? `Prioritaskan pengecekan untuk daftar film populer berikut jika ada kabar rilis Indonesia:\n${tmdbCandidates
        .map((c) => `- ${c.title} (tmdbId: ${c.tmdbId}, global: ${c.releaseDate})`)
        .join('\n')}`
    : '';

  const todayIso = new Date().toISOString().slice(0, 10);
  const prompt = [
    `Tanggal hari ini: ${todayIso}.`,
    'Cari pengumuman resmi jadwal rilis bioskop INDONESIA terbaru, film yang baru lulus sensor LSF (lsf.go.id), atau pembukaan tiket pre-sale / IMAX di Cinema XXI, CGV Indonesia, Cinépolis Indonesia, atau TIX ID untuk bulan ini dan 2 bulan ke depan.',
    hintBlock,
    'ATURAN KETAT: Hanya masukkan film yang BENAR-BENAR memiliki kabar/pengumuman rilis di Indonesia dari hasil Google Search. Jangan mengarang tanggal atau jaringan bioskop.',
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

  const response = await ai.models.generateContent({
    model: 'gemini-4-hydrogen',
    contents: prompt,
    config: {
      systemInstruction:
        'Anda aggregator jadwal bioskop Indonesia (XXI, CGV, Cinépolis, IMAX, LSF). Keluarkan hanya entri yang terverifikasi oleh hasil Google Search.',
      tools: [{ googleSearch: {} }],
    },
  });

  const parsed = parseJsonPayload(response.text || '');
  const sharedSources = extractGroundSources(response);
  const rawEntries = Array.isArray(parsed?.entries) ? parsed.entries : [];

  const titleToTmdb = new Map(tmdbCandidates.map((c) => [normalizeKey(c.title), c.tmdbId]));
  const nowIso = new Date().toISOString();
  const seenKeys = new Set();
  const entries = [];

  for (const raw of rawEntries) {
    const title = String(raw?.title || '').trim().toUpperCase().slice(0, 100);
    if (!title) continue;
    const tmdbNum = Number(raw?.tmdbId || 0) || titleToTmdb.get(normalizeKey(title)) || 0;
    const key = tmdbNum > 0 ? `tmdb_${tmdbNum}` : `title_${normalizeKey(title)}`;
    if (seenKeys.has(key)) continue;
    seenKeys.add(key);

    entries.push({
      key,
      tmdbId: tmdbNum > 0 ? tmdbNum : undefined,
      title,
      releaseDateId: sanitizeIso(raw?.releaseDateId) || undefined,
      status: ['confirmed', 'presale', 'lsf_passed'].includes(raw?.status) ? raw.status : 'confirmed',
      chains: normalizeChains(raw?.chains),
      imax: Boolean(raw?.imax),
      presaleActive: Boolean(raw?.presaleActive),
      lsfStatus: String(raw?.lsfStatus || '').trim().slice(0, 80),
      summary: String(raw?.summary || '').trim().slice(0, 260) || 'Terkonfirmasi masuk radar rilis Indonesia.',
      sources: sharedSources,
      checkedAt: nowIso,
    });
  }

  const outPayload = {
    version: 1,
    updatedAt: nowIso,
    source: 'cloud-radar',
    entries,
  };

  fs.mkdirSync(path.dirname(OUT_PATH), { recursive: true });
  fs.writeFileSync(OUT_PATH, JSON.stringify(outPayload, null, 2) + '\n', 'utf8');
  console.log(`Updated radar-id.json with ${entries.length} entries.`);
}

main().catch((err) => {
  console.error('Radar sync failed:', err);
  process.exit(1);
});
