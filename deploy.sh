#!/bin/bash
ZIP_FILE=$(ls -t *.zip 2>/dev/null | head -n 1)

if [ -z "$ZIP_FILE" ]; then
  echo "❌ Gak nemu file .zip di folder utama!"
  exit 1
fi

echo "📦 Memproses $ZIP_FILE..."
rm -rf app/src/main/java app/src/main/assets app/src/main/res .github/workflows
unzip -o -q "$ZIP_FILE"

# Kalau hasil ekstrak masuk ke subfolder, otomatis keluarin ke root
SUBDIR=$(find . -maxdepth 1 -type d -name "CinemaTracker*" | head -n 1)
if [ -n "$SUBDIR" ]; then
  cp -rf "$SUBDIR"/. .
  rm -rf "$SUBDIR"
fi

rm -f *.zip
VER=$(grep "versionName" app/build.gradle.kts | head -n 1 | cut -d'"' -f2)
echo "🚀 Deploying versi $VER ke GitHub..."

git add -A
git commit -m "deploy: Cinema Tracker v${VER:-update}"
git push
echo "✅ Selesai! Cek tab Actions di GitHub buat ambil APK."
