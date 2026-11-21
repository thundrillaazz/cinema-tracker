#!/bin/bash
echo "Mengupdate Cinema Tracker v4.0..."

# 1. index.html (Full Rombakan UI/UX)
cat << 'HTMLEOF' > app/src/main/assets/index.html
<!DOCTYPE html>
<html lang="id">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>Cinema Tracker</title>
    <style>
        :root {
            --bg-color: #F8FAFC;
            --card-bg: #FFFFFF;
            --text-main: #0F172A;
            --text-sub: #64748B;
            --accent: #E11D48;
            --border: #E2E8F0;
            --nav-bg: #FFFFFF;
            --shadow: 0 4px 6px -1px rgba(0,0,0,0.05);
            --font: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
        }
        [data-theme="dark"] {
            --bg-color: #0F172A;
            --card-bg: #1E293B;
            --text-main: #F8FAFC;
            --text-sub: #94A3B8;
            --border: #334155;
            --nav-bg: #1E293B;
            --shadow: 0 4px 6px -1px rgba(0,0,0,0.3);
        }

        * { box-sizing: border-box; margin: 0; padding: 0; -webkit-tap-highlight-color: transparent; }
        body { font-family: var(--font); background-color: var(--bg-color); color: var(--text-main); overflow-x: hidden; padding-bottom: 70px; transition: background 0.3s; }
        
        /* Header */
        header { padding: 20px 16px 10px; background: var(--bg-color); position: sticky; top: 0; z-index: 10; }
        .header-title { font-size: 24px; font-weight: 800; letter-spacing: -0.5px; display: flex; align-items: center; gap: 8px; }
        .header-sub { font-size: 13px; color: var(--text-sub); font-weight: 500; margin-top: 2px; }

        /* Layout */
        .view-container { display: none; padding: 0 16px; animation: fadeIn 0.3s ease; }
        .view-container.active { display: block; }
        @keyframes fadeIn { from { opacity: 0; transform: translateY(5px); } to { opacity: 1; transform: translateY(0); } }

        /* Search Bar */
        .search-bar { background: var(--card-bg); border: 1px solid var(--border); border-radius: 12px; padding: 12px 16px; display: flex; align-items: center; gap: 10px; margin-bottom: 16px; }
        .search-bar input { border: none; background: transparent; color: var(--text-main); font-size: 15px; width: 100%; outline: none; }

        /* Movie Grid */
        .movie-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 16px; }
        .card { background: var(--card-bg); border-radius: 16px; overflow: hidden; box-shadow: var(--shadow); position: relative; cursor: pointer; transition: transform 0.2s; border: 1px solid var(--border); }
        .card:active { transform: scale(0.97); }
        .card-img { width: 100%; aspect-ratio: 2/3; object-fit: cover; background: var(--border); display: block; }
        .card-info { padding: 12px; }
        .card-title { font-size: 14px; font-weight: 700; margin-bottom: 4px; display: -webkit-box; -webkit-line-clamp: 1; -webkit-box-orient: vertical; overflow: hidden; }
        .card-date { font-size: 12px; color: var(--text-sub); display: flex; justify-content: space-between; align-items: center; }
        .badge-hype { font-size: 10px; font-weight: 700; color: var(--accent); background: rgba(225, 29, 72, 0.1); padding: 2px 6px; border-radius: 4px; }
        
        .btn-bookmark { position: absolute; top: 10px; right: 10px; background: rgba(0,0,0,0.5); backdrop-filter: blur(4px); border: none; border-radius: 50%; width: 36px; height: 36px; display: flex; align-items: center; justify-content: center; cursor: pointer; z-index: 2; transition: background 0.2s; }
        .btn-bookmark.active { background: var(--accent); }
        .btn-bookmark svg { width: 18px; height: 18px; fill: none; stroke: #FFF; stroke-width: 2; }
        .btn-bookmark.active svg { fill: #FFF; }

        /* Watchlist View (Fokus Rilis & Countdown) */
        .filter-chips { display: flex; gap: 8px; overflow-x: auto; padding-bottom: 12px; scrollbar-width: none; }
        .filter-chips::-webkit-scrollbar { display: none; }
        .chip { padding: 8px 16px; border-radius: 20px; background: var(--card-bg); border: 1px solid var(--border); font-size: 13px; font-weight: 600; color: var(--text-sub); white-space: nowrap; cursor: pointer; transition: 0.2s; }
        .chip.active { background: var(--text-main); color: var(--bg-color); border-color: var(--text-main); }

        .watchlist-item { display: flex; gap: 16px; background: var(--card-bg); padding: 12px; border-radius: 16px; margin-bottom: 12px; border: 1px solid var(--border); align-items: center; cursor: pointer; }
        .wl-img { width: 70px; height: 105px; border-radius: 8px; object-fit: cover; }
        .wl-info { flex: 1; }
        .wl-title { font-size: 16px; font-weight: 700; margin-bottom: 4px; }
        .wl-meta { font-size: 13px; color: var(--text-sub); margin-bottom: 8px; }
        .wl-countdown { font-size: 14px; font-weight: 800; color: var(--accent); display: flex; align-items: center; gap: 6px; }
        .wl-countdown.passed { color: #10B981; }

        /* Detail Modal */
        .modal { position: fixed; inset: 0; background: var(--bg-color); z-index: 100; display: none; flex-direction: column; overflow-y: auto; }
        .modal.active { display: flex; animation: slideUp 0.3s ease; }
        @keyframes slideUp { from { transform: translateY(100%); } to { transform: translateY(0); } }
        .modal-hero { position: relative; width: 100%; height: 45vh; }
        .modal-hero img { width: 100%; height: 100%; object-fit: cover; }
        .modal-hero::after { content: ''; position: absolute; inset: 0; background: linear-gradient(to top, var(--bg-color) 0%, transparent 100%); }
        .btn-close { position: absolute; top: 20px; right: 20px; background: rgba(0,0,0,0.6); border: none; border-radius: 50%; width: 40px; height: 40px; color: white; font-size: 20px; cursor: pointer; z-index: 10; display: flex; align-items: center; justify-content: center; backdrop-filter: blur(4px); }
        
        .modal-content { padding: 20px; margin-top: -60px; position: relative; z-index: 2; }
        .modal-title { font-size: 26px; font-weight: 800; line-height: 1.2; margin-bottom: 8px; }
        .modal-meta { display: flex; gap: 12px; font-size: 14px; color: var(--text-sub); margin-bottom: 16px; flex-wrap: wrap; }
        .modal-synopsis { font-size: 14px; line-height: 1.6; color: var(--text-main); margin-bottom: 24px; }
        
        .action-buttons { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 24px; }
        .btn { padding: 14px; border-radius: 12px; border: none; font-weight: 700; font-size: 14px; cursor: pointer; display: flex; align-items: center; justify-content: center; gap: 8px; transition: 0.2s; }
        .btn-primary { background: var(--text-main); color: var(--bg-color); }
        .btn-secondary { background: var(--card-bg); border: 1px solid var(--border); color: var(--text-main); }
        .btn-accent { background: var(--accent); color: white; }
        .btn-accent.active { background: var(--card-bg); border: 1px solid var(--accent); color: var(--accent); }
        
        .info-row { display: flex; margin-bottom: 12px; font-size: 14px; }
        .info-label { width: 100px; color: var(--text-sub); font-weight: 600; }
        .info-value { flex: 1; font-weight: 500; }

        /* Bottom Nav */
        .bottom-nav { position: fixed; bottom: 0; left: 0; right: 0; background: var(--nav-bg); display: flex; justify-content: space-around; padding: 12px 0 24px; border-top: 1px solid var(--border); z-index: 50; }
        .nav-item { display: flex; flex-direction: column; align-items: center; gap: 4px; color: var(--text-sub); font-size: 11px; font-weight: 600; cursor: pointer; transition: 0.2s; border: none; background: transparent; width: 25%; }
        .nav-item svg { width: 22px; height: 22px; fill: none; stroke: currentColor; stroke-width: 2; }
        .nav-item.active { color: var(--text-main); }
    </style>
</head>
<body>

<header>
    <div class="header-title">
        <svg width="28" height="28" viewBox="0 0 24 24" fill="var(--text-main)"><path d="M18 4l2 4h-3l-2-4h-2l2 4h-3l-2-4H8l2 4H7L5 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V4h-4z"/></svg>
        Cinema Tracker
    </div>
    <div class="header-sub">Jadwal Rilis Bioskop</div>
</header>

<!-- VIEW: BIOSKOP -->
<div id="view-bioskop" class="view-container active">
    <div class="movie-grid" id="grid-bioskop"></div>
</div>

<!-- VIEW: CARI -->
<div id="view-cari" class="view-container">
    <div class="search-bar">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="var(--text-sub)" stroke-width="2"><circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>
        <input type="text" id="search-input" placeholder="Cari judul film..." oninput="handleSearch()">
    </div>
    <div class="movie-grid" id="grid-cari"></div>
</div>

<!-- VIEW: WATCHLIST -->
<div id="view-watchlist" class="view-container">
    <div class="filter-chips" id="filter-watchlist">
        <div class="chip active" onclick="setFilter('semua', this)">Semua</div>
        <div class="chip" onclick="setFilter('segera', this)">Segera (≤14 Hari)</div>
        <div class="chip" onclick="setFilter('bulan', this)">Bulan Ini</div>
    </div>
    <div id="list-watchlist"></div>
</div>

<!-- VIEW: SETTINGS -->
<div id="view-settings" class="view-container">
    <div class="card" style="padding: 16px; margin-bottom: 12px;">
        <h3 style="margin-bottom: 12px;">Pengaturan API</h3>
        <div class="search-bar" style="margin-bottom: 0;">
            <input type="text" id="api-key-input" placeholder="TMDB API Key (v3)" onchange="saveApiKey(this.value)">
        </div>
        <p style="font-size: 12px; color: var(--text-sub); margin-top: 8px;">Dapatkan API Key gratis di themoviedb.org</p>
    </div>
    <div class="card" style="padding: 16px;">
        <h3 style="margin-bottom: 12px;">Tema Aplikasi</h3>
        <div style="display: flex; gap: 8px;">
            <button class="btn btn-secondary" style="flex: 1;" onclick="setTheme('light')">Light</button>
            <button class="btn btn-secondary" style="flex: 1;" onclick="setTheme('dark')">Dark</button>
            <button class="btn btn-secondary" style="flex: 1;" onclick="setTheme('system')">System</button>
        </div>
    </div>
</div>

<!-- MODAL: DETAIL FILM -->
<div id="movie-modal" class="modal">
    <button class="btn-close" onclick="closeModal()">✕</button>
    <div class="modal-hero">
        <img id="modal-img" src="" alt="Poster">
    </div>
    <div class="modal-content">
        <h1 id="modal-title" class="modal-title"></h1>
        <div class="modal-meta">
            <span id="modal-date"></span>
            <span id="modal-genre"></span>
        </div>
        <div class="action-buttons">
            <button id="btn-save-modal" class="btn btn-primary" onclick="toggleWatchlistFromModal()">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"></path></svg>
                <span>Simpan</span>
            </button>
            <button class="btn btn-secondary" onclick="openTrailer()">Trailer</button>
        </div>
        <p id="modal-synopsis" class="modal-synopsis"></p>
        
        <div class="info-row">
            <div class="info-label">Sutradara</div>
            <div class="info-value" id="modal-director">-</div>
        </div>
        <div class="info-row">
            <div class="info-label">Pemain</div>
            <div class="info-value" id="modal-cast">-</div>
        </div>
        
        <button class="btn btn-secondary" style="width: 100%; margin-top: 16px;" onclick="openLetterboxd()">
            Lihat di Letterboxd
        </button>
    </div>
</div>

<!-- BOTTOM NAV -->
<nav class="bottom-nav">
    <button class="nav-item active" onclick="switchTab('bioskop', this)">
        <svg viewBox="0 0 24 24"><rect x="3" y="3" width="7" height="7"></rect><rect x="14" y="3" width="7" height="7"></rect><rect x="14" y="14" width="7" height="7"></rect><rect x="3" y="14" width="7" height="7"></rect></svg>
        Bioskop
    </button>
    <button class="nav-item" onclick="switchTab('cari', this)">
        <svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>
        Cari
    </button>
    <button class="nav-item" onclick="switchTab('watchlist', this)">
        <svg viewBox="0 0 24 24"><path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"></path></svg>
        Watchlist
    </button>
    <button class="nav-item" onclick="switchTab('settings', this)">
        <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path></svg>
        Settings
    </button>
</nav>

<script>
    // STATE
    let state = {
        theme: localStorage.getItem('ct_theme') || 'system',
        apiKey: localStorage.getItem('ct_api_key') || '',
        watchlist: JSON.parse(localStorage.getItem('ct_watchlist') || '[]'),
        movies: [],
        currentMovie: null,
        currentFilter: 'semua'
    };

    // DUMMY DATA (Fallback jika API Key kosong / limit)
    const fallbackMovies = [
        { id: 1, title: "Avengers: Doomsday", release_date: "2026-12-16", poster_path: "/avengers.jpg", overview: "Petualangan baru Earth's Mightiest Heroes menghadapi ancaman baru.", vote_average: 8.5, genre_ids: [28, 12] },
        { id: 2, title: "Dune: Part Three", release_date: "2026-12-16", poster_path: "/dune.jpg", overview: "Lanjutan perjalanan Paul Atreides di planet Arrakis.", vote_average: 8.9, genre_ids: [878, 12] },
        { id: 3, title: "Werwulf", release_date: "2026-12-25", poster_path: "/werwulf.jpg", overview: "Teror makhluk mistis di tengah kota.", vote_average: 7.2, genre_ids: [27] }
    ];

    // INIT
    document.addEventListener('DOMContentLoaded', () => {
        applyTheme();
        document.getElementById('api-key-input').value = state.apiKey;
        fetchMovies();
        renderWatchlist();
        syncToAndroid();
    });

    // THEME
    function setTheme(theme) {
        state.theme = theme;
        localStorage.setItem('ct_theme', theme);
        applyTheme();
        // Sinkronisasi Status Bar Native (jika didukung JS Bridge)
        if (window.AndroidBridge) {
            window.AndroidBridge.syncTheme(theme === 'dark' ? '#0F172A' : '#F8FAFC');
        }
    }

    function applyTheme() {
        const isDark = state.theme === 'dark' || (state.theme === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);
        document.documentElement.setAttribute('data-theme', isDark ? 'dark' : 'light');
    }

    // NAVIGATION
    function switchTab(tabId, el) {
        document.querySelectorAll('.view-container').forEach(c => c.classList.remove('active'));
        document.querySelectorAll('.nav-item').forEach(n => n.classList.remove('active'));
        document.getElementById(`view-${tabId}`).classList.add('active');
        el.classList.add('active');
    }

    // FETCH API
    async function fetchMovies() {
        if (!state.apiKey) {
            state.movies = fallbackMovies;
            renderBioskop();
            return;
        }
        try {
            const res = await fetch(`https://api.themoviedb.org/3/movie/upcoming?api_key=${state.apiKey}&region=ID&language=en-US&page=1`);
            const data = await res.json();
            if (data.results) {
                state.movies = data.results.filter(m => m.release_date);
                renderBioskop();
                syncToAndroid();
            }
        } catch (e) {
            state.movies = fallbackMovies;
            renderBioskop();
        }
    }

    // RENDER BIOSKOP
    function renderBioskop() {
        const grid = document.getElementById('grid-bioskop');
        grid.innerHTML = state.movies.map(m => createCardHTML(m)).join('');
    }

    // RENDER CARI
    function handleSearch() {
        const q = document.getElementById('search-input').value.toLowerCase();
        const grid = document.getElementById('grid-cari');
        if (!q) { grid.innerHTML = ''; return; }
        const filtered = state.movies.filter(m => m.title.toLowerCase().includes(q));
        grid.innerHTML = filtered.map(m => createCardHTML(m)).join('');
    }

    // CARD HTML GENERATOR
    function createCardHTML(m) {
        const isSaved = state.watchlist.some(w => w.id === m.id);
        const poster = m.poster_path ? `https://image.tmdb.org/t/p/w342${m.poster_path}` : 'https://via.placeholder.com/342x513?text=No+Poster';
        const dateStr = formatDate(m.release_date);
        const days = getDaysLeft(m.release_date);
        
        return `
        <div class="card" onclick="openDetail(${m.id})">
            <button class="btn-bookmark ${isSaved ? 'active' : ''}" onclick="event.stopPropagation(); toggleWatchlist(${m.id}, this)">
                <svg viewBox="0 0 24 24"><path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"></path></svg>
            </button>
            <img src="${poster}" class="card-img" loading="lazy" alt="${m.title}">
            <div class="card-info">
                <div class="card-title">${m.title}</div>
                <div class="card-date">
                    <span>${dateStr}</span>
                    <span class="badge-hype">${days >= 0 ? 'H-' + days : 'Tayang'}</span>
                </div>
            </div>
        </div>
        `;
    }

    // WATCHLIST LOGIC
    function toggleWatchlist(id, btnEl = null) {
        const movie = state.movies.find(m => m.id === id);
        if (!movie) return;

        const index = state.watchlist.findIndex(w => w.id === id);
        if (index > -1) {
            state.watchlist.splice(index, 1); // Remove
        } else {
            state.watchlist.push(movie); // Add
        }
        
        localStorage.setItem('ct_watchlist', JSON.stringify(state.watchlist));
        syncToAndroid();

        // Update UI secara instan (Cegah bug tidak berubah warna)
        if (btnEl) {
            btnEl.classList.toggle('active');
        }
        
        // Update grid utama dan search
        renderBioskop();
        if (document.getElementById('search-input').value) handleSearch();
        
        // Update Watchlist View
        renderWatchlist();
        
        // Update Modal jika sedang terbuka
        if (state.currentMovie && state.currentMovie.id === id) {
            updateModalSaveBtn();
        }
    }

    function renderWatchlist() {
        const container = document.getElementById('list-watchlist');
        if (state.watchlist.length === 0) {
            container.innerHTML = `<div style="text-align:center; padding: 40px 20px; color: var(--text-sub);">
                <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" style="margin-bottom:12px; opacity:0.5;"><path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"></path></svg>
                <p>Belum ada film di watchlist.<br>Simpan film favoritmu di sini.</p>
            </div>`;
            return;
        }

        // Filter Logic
        let filtered = [...state.watchlist];
        if (state.currentFilter === 'segera') {
            filtered = filtered.filter(m => {
                const d = getDaysLeft(m.release_date);
                return d >= 0 && d <= 14;
            });
        } else if (state.currentFilter === 'bulan') {
            const now = new Date();
            filtered = filtered.filter(m => {
                const rDate = new Date(m.release_date);
                return rDate.getMonth() === now.getMonth() && rDate.getFullYear() === now.getFullYear();
            });
        }

        // Sort by Release Date (Terdekat)
        filtered.sort((a, b) => new Date(a.release_date) - new Date(b.release_date));

        container.innerHTML = filtered.map(m => {
            const poster = m.poster_path ? `https://image.tmdb.org/t/p/w185${m.poster_path}` : 'https://via.placeholder.com/185x278?text=No+Poster';
            const dateStr = formatDate(m.release_date);
            const days = getDaysLeft(m.release_date);
            const countdownText = days > 0 ? `H-${days}` : (days === 0 ? 'Hari Ini' : 'Sudah Tayang');
            const countdownClass = days <= 0 ? 'passed' : '';

            return `
            <div class="watchlist-item" onclick="openDetail(${m.id})">
                <img src="${poster}" class="wl-img" loading="lazy" alt="${m.title}">
                <div class="wl-info">
                    <div class="wl-title">${m.title}</div>
                    <div class="wl-meta">Rilis: ${dateStr}</div>
                    <div class="wl-countdown ${countdownClass}">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline></svg>
                        ${countdownText}
                    </div>
                </div>
            </div>
            `;
        }).join('');
    }

    function setFilter(filter, el) {
        state.currentFilter = filter;
        document.querySelectorAll('#filter-watchlist .chip').forEach(c => c.classList.remove('active'));
        el.classList.add('active');
        renderWatchlist();
    }

    // DETAIL MODAL
    function openDetail(id) {
        const m = state.movies.find(x => x.id === id) || state.watchlist.find(x => x.id === id);
        if (!m) return;
        state.currentMovie = m;

        const poster = m.poster_path ? `https://image.tmdb.org/t/p/w780${m.poster_path}` : 'https://via.placeholder.com/780x1170?text=No+Poster';
        document.getElementById('modal-img').src = poster;
        document.getElementById('modal-title').textContent = m.title;
        document.getElementById('modal-date').textContent = `Rilis: ${formatDate(m.release_date)}`;
        document.getElementById('modal-genre').textContent = getDaysLeft(m.release_date) > 0 ? `H-${getDaysLeft(m.release_date)}` : 'Tayang';
        document.getElementById('modal-synopsis').textContent = m.overview || 'Sinopsis belum tersedia.';
        
        updateModalSaveBtn();
        document.getElementById('movie-modal').classList.add('active');
    }

    function closeModal() {
        document.getElementById('movie-modal').classList.remove('active');
        state.currentMovie = null;
    }

    function updateModalSaveBtn() {
        const btn = document.getElementById('btn-save-modal');
        const isSaved = state.watchlist.some(w => w.id === state.currentMovie.id);
        if (isSaved) {
            btn.classList.add('active');
            btn.innerHTML = `<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" stroke="currentColor" stroke-width="2"><path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"></path></svg> Tersimpan`;
        } else {
            btn.classList.remove('active');
            btn.innerHTML = `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"></path></svg> Simpan`;
        }
    }

    function toggleWatchlistFromModal() {
        if (state.currentMovie) {
            toggleWatchlist(state.currentMovie.id);
        }
    }

    // BRIDGE ACTIONS
    function openTrailer() {
        if (window.AndroidBridge && state.currentMovie) {
            window.AndroidBridge.openTrailerDirect("", state.currentMovie.title);
        }
    }

    function openLetterboxd() {
        if (window.AndroidBridge && state.currentMovie) {
            window.AndroidBridge.openLetterboxdDirect(state.currentMovie.id, "");
        }
    }

    function saveApiKey(key) {
        state.apiKey = key;
        localStorage.setItem('ct_api_key', key);
        fetchMovies();
        syncToAndroid();
    }

    function syncToAndroid() {
        if (window.AndroidBridge) {
            const apiKey = state.apiKey || "";
            const watchlistStr = JSON.stringify(state.watchlist);
            const tbaStr = JSON.stringify(state.movies.filter(m => !m.release_date || m.release_date === ""));
            window.AndroidBridge.syncRadarState(watchlistStr, tbaStr, apiKey);
        }
    }

    // HELPERS
    function formatDate(dateStr) {
        if (!dateStr) return "TBA";
        const d = new Date(dateStr);
        return d.toLocaleDateString('id-ID', { day: 'numeric', month: 'short', year: 'numeric' });
    }

    function getDaysLeft(dateStr) {
        if (!dateStr) return 999;
        const today = new Date();
        today.setHours(0,0,0,0);
        const target = new Date(dateStr);
        const diff = target - today;
        return Math.ceil(diff / (1000 * 60 * 60 * 24));
    }
</script>
</body>
</html>
HTMLEOF

# 2. MainActivity.kt
cat << 'KTEOF' > app/src/main/java/com/michael/cinematracker/MainActivity.kt
package com.michael.cinematracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.parseColor("#F8FAFC")
        window.navigationBarColor = Color.parseColor("#F8FAFC")

        createNotificationChannel()
        requestNotificationPermissionIfNeeded()

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            addJavascriptInterface(WebAppInterface(this@MainActivity), "AndroidBridge")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme ?: return false
                    if (scheme == "file") return false
                    openExternalUri(uri)
                    return true
                }
            }
        }

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")

        scheduleReleaseWorker()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    "(function(){ if(window.handleNativeBack && window.handleNativeBack()) return 'handled'; if(document.getElementById('movie-modal').classList.contains('active')) { closeModal(); return 'handled'; } return 'exit'; })()"
                ) { result ->
                    if (result != "\"handled\"") {
                        if (webView.canGoBack()) {
                            webView.goBack()
                        } else {
                            finish()
                        }
                    }
                }
            }
        })
    }

    private fun openExternalUri(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Tidak ada aplikasi untuk membuka tautan ini", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CinemaCheckWorker.CHANNEL_ID,
                "Radar Bioskop, TBA & Presale",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi film masuk jadwal ID, TBA terkonfirmasi, presale, H-1, dan hari-H"
                enableVibration(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun scheduleReleaseWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<CinemaCheckWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "cinema_tracker_release_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    inner class WebAppInterface(private val context: Context) {

        @JavascriptInterface
        fun openLetterboxdDirect(tmdbId: Int, slugFallback: String) {
            runOnUiThread {
                val url = if (tmdbId > 0) {
                    "https://letterboxd.com/tmdb/$tmdbId/"
                } else {
                    "https://letterboxd.com/search/$slugFallback/"
                }
                openExternalUri(Uri.parse(url))
            }
        }

        @JavascriptInterface
        fun openTrailerDirect(youtubeKey: String, movieTitle: String) {
            runOnUiThread {
                if (youtubeKey.isNotBlank()) {
                    try {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$youtubeKey"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        return@runOnUiThread
                    } catch (_: ActivityNotFoundException) {
                        openExternalUri(Uri.parse("https://www.youtube.com/watch?v=$youtubeKey"))
                        return@runOnUiThread
                    }
                }
                val q = Uri.encode("$movieTitle official trailer")
                openExternalUri(Uri.parse("https://m.youtube.com/results?search_query=$q"))
            }
        }

        @JavascriptInterface
        fun syncRadarState(watchlistJson: String, tbaJson: String, apiKey: String) {
            context.getSharedPreferences(CinemaCheckWorker.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(CinemaCheckWorker.KEY_WATCHLIST, watchlistJson)
                .putString(CinemaCheckWorker.KEY_TBA, tbaJson)
                .putString(CinemaCheckWorker.KEY_API_KEY, apiKey)
                .apply()
        }

        @JavascriptInterface
        fun syncTheme(colorHex: String) {
            runOnUiThread {
                try {
                    window.statusBarColor = Color.parseColor(colorHex)
                    window.navigationBarColor = Color.parseColor(colorHex)
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun savePosterBase64(base64Data: String, fileName: String) {
            runOnUiThread {
                try {
                    val clean = base64Data.substringAfter(",")
                    val bytes = Base64.decode(clean, Base64.DEFAULT)
                    val safeName = if (fileName.endsWith(".jpg", true)) fileName else "$fileName.jpg"

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, safeName)
                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/CinemaTracker")
                        }
                        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        if (uri != null) {
                            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                            Toast.makeText(context, "Poster HD disimpan ke Galeri", Toast.LENGTH_SHORT).show()
                            return@runOnUiThread
                        }
                    }

                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "CinemaTracker"
                    )
                    if (!dir.exists()) dir.mkdirs()
                    val outFile = File(dir, safeName)
                    FileOutputStream(outFile).use { it.write(bytes) }
                    Toast.makeText(context, "Poster HD tersimpan", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(context, "Gagal menyimpan poster", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
KTEOF

# 3. CinemaCheckWorker.kt (Optimasi API Call)
cat << 'KTEOF' > app/src/main/java/com/michael/cinematracker/CinemaCheckWorker.kt
package com.michael.cinematracker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class CinemaCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val PREFS_NAME = "cinema_tracker_v49_prefs"
        const val KEY_WATCHLIST = "watchlist_json"
        const val KEY_TBA = "tba_json"
        const val KEY_API_KEY = "tmdb_api_key"
        const val CHANNEL_ID = "cinema_tracker_radar_alerts"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString(KEY_API_KEY, "")?.trim().orEmpty()
        val watchlistRaw = prefs.getString(KEY_WATCHLIST, "[]").orEmpty()
        val tbaRaw = prefs.getString(KEY_TBA, "[]").orEmpty()

        if (apiKey.isEmpty()) return@withContext Result.success()

        try {
            val today = LocalDate.now()

            val tbaList = JSONArray(tbaRaw)
            for (i in 0 until tbaList.length()) {
                val item = tbaList.getJSONObject(i)
                val movieId = item.optInt("id", 0)
                val title = item.optString("title", "Film TBA")
                if (movieId == 0) continue

                val idDate = fetchIndonesiaReleaseDate(movieId, apiKey)
                if (!idDate.isNullOrBlank()) {
                    notifyOnce(prefs, movieId, "tba_indo_$idDate", "TBA Masuk Jadwal Indo: $title", "Jadwal tayang bioskop Indonesia terkonfirmasi: $idDate.")
                }
            }

            val watchlist = JSONArray(watchlistRaw)
            for (i in 0 until watchlist.length()) {
                val item = watchlist.getJSONObject(i)
                val movieId = item.optInt("id", 0)
                val title = item.optString("title", "Film")
                val notifyPresale = item.optBoolean("notify_presale", true)
                val notifyH1 = item.optBoolean("notify_h1", true)
                val notifyH0 = item.optBoolean("notify_h0", true)

                var releaseDate = item.optString("release_date", "")
                val refreshed = fetchIndonesiaReleaseDate(movieId, apiKey)
                if (!refreshed.isNullOrBlank()) {
                    releaseDate = refreshed
                }

                if (releaseDate.length >= 10) {
                    val target = LocalDate.parse(releaseDate.substring(0, 10))
                    val days = ChronoUnit.DAYS.between(today, target)

                    if (days == 7L && notifyPresale) {
                        notifyOnce(prefs, movieId, "presale_$releaseDate", "Open Pre-Sale: $title", "Tayang $releaseDate. Cek presale tiket bioskop sekarang!")
                    }
                    if (days == 1L && notifyH1) {
                        notifyOnce(prefs, movieId, "h1_$releaseDate", "Besok Tayang (H-1): $title", "$title mulai tayang besok di bioskop Indonesia.")
                    }
                    if (days == 0L && notifyH0) {
                        notifyOnce(prefs, movieId, "h0_$releaseDate", "Hari Perdana: $title", "$title resmi tayang hari ini di bioskop.")
                    }
                }
            }

            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun fetchIndonesiaReleaseDate(movieId: Int, apiKey: String): String? {
        val urlStr = "https://api.themoviedb.org/3/movie/$movieId/release_dates?api_key=$apiKey"
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
        }
        return try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val results = JSONObject(body).optJSONArray("results") ?: return null
            for (i in 0 until results.length()) {
                val country = results.getJSONObject(i)
                if (country.optString("iso_3166_1") == "ID") {
                    val dates = country.optJSONArray("release_dates") ?: continue
                    for (j in 0 until dates.length()) {
                        val d = dates.getJSONObject(j)
                        val type = d.optInt("type", 0)
                        if (type == 3 || type == 2) {
                            val date = d.optString("release_date").take(10)
                            if (date.isNotBlank()) return date
                        }
                    }
                    if (dates.length() > 0) {
                        val date = dates.getJSONObject(0).optString("release_date").take(10)
                        if (date.isNotBlank()) return date
                    }
                }
            }
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun notifyOnce(
        prefs: android.content.SharedPreferences,
        movieId: Int,
        tag: String,
        title: String,
        message: String
    ) {
        val key = "alert_${movieId}_$tag"
        if (prefs.getBoolean(key, false)) return

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            applicationContext,
            movieId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        manager.notify((movieId.toString() + tag).hashCode(), notif)
        prefs.edit().putBoolean(key, true).apply()
    }
}
KTEOF

echo "Update Selesai! Silakan jalankan git commit dan push."
