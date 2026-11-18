# Cinema Tracker Android (Samsung Galaxy S24 FE)

## Cara Build APK via GitHub Actions
1. Upload seluruh isi folder ini ke repository GitHub `cinema-tracker-android`.
2. Buka tab **Actions** -> pilih workflow **Build Cinema Tracker Android APK**.
3. Tunggu ±2 menit hingga selesai (centang hijau), lalu download artifact **CinemaTracker-S24FE-APK** di bagian paling bawah.
4. Ekstrak file ZIP hasil download dan install `app-debug.apk` di Samsung Galaxy S24 FE.

## Pengaturan Wajib Setelah Install di S24 FE
1. Buka aplikasi **Cinema Tracker** dan tekan **Allow / Izinkan** pada pop-up izin notifikasi.
2. Tekan dan tahan ikon **Cinema Tracker** di layar HP -> pilih **App info (ⓘ)** -> **Battery** -> pilih **Unrestricted** agar Samsung One UI tidak menidurkan background scanner 6 jam.
3. Tap tombol **🔄 Sync** di pojok kanan atas aplikasi, masukkan **TMDB API Key (v3)** gratis kamu, lalu tap **Simpan Key & Sync Jadwal Indonesia**.
