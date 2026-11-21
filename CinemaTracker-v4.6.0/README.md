# Cinema Tracker (v4.6.0)

Radar jadwal tayang bioskop Indonesia, presale tiket, trailer instan, dan akses langsung ke halaman profil film Letterboxd.

## Fitur Utama
- **Fokus Radar Bioskop (Zero Watch-History Bloat):** Khusus memantau film *Akan Tayang (ID)*, *Sedang Tayang (ID)*, dan *Radar Hype* tanpa tab/tombol "Sudah Ditonton" karena logging tontonan tetap berpusat di Letterboxd.
- **Direct Letterboxd Film Profile:** Tombol Letterboxd membuka `https://letterboxd.com/tmdb/<tmdb_id>` yang otomatis mengarahkan langsung ke halaman profil film (`/film/<slug>/`) atau langsung membuka aplikasi Letterboxd di Android.
- **Direct Official Trailer:** Langsung meluncurkan trailer resmi YouTube tanpa halaman perantara.
- **Background Presale & Release Reminder:** WorkManager mengecek jadwal rilis Indonesia (`region=ID`) tiap 6 jam dengan deduplikasi notifikasi untuk H-7 (window presale), H-1, dan Hari-H tayang.
- **Multi Launcher Icon:** Pilihan ikon *Default*, *Obsidian*, dan *Crimson*.

## Build APK via GitHub Actions
1. Push seluruh isi folder ini ke repository GitHub.
2. Masuk ke tab **Actions** -> jalankan workflow **Build Cinema Tracker APK**.
3. Unduh artifact `CinemaTracker-v4.6.0-debug` setelah build selesai.
