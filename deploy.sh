#!/bin/bash
ZIP_FILE=$(ls -t *.zip 2>/dev/null | head -n 1)

if [ -z "$ZIP_FILE" ]; then
  echo "❌ Gak nemu file .zip di folder utama!"
  exit 1
fi

echo "📦 Memproses $ZIP_FILE..."
rm -rf _tmp_extract app/src/main/java app/src/main/assets app/src/main/res
mkdir -p _tmp_extract
unzip -o -q "$ZIP_FILE" -d _tmp_extract

# Cari folder utama yang punya folder 'app' atau 'src' di dalamnya
PROJECT_ROOT=$(find _tmp_extract -maxdepth 2 -type d \( -name "app" -o -name "src" \) | head -n 1 | xargs dirname)

if [ -n "$PROJECT_ROOT" ] && [ "$PROJECT_ROOT" != "." ]; then
  cp -rf "$PROJECT_ROOT"/. .
else
  cp -rf _tmp_extract/. .
fi

rm -rf _tmp_extract *.zip
VER=$(grep "versionName" app/build.gradle.kts 2>/dev/null | head -n 1 | cut -d'"' -f2)
echo "🚀 Deploying versi ${VER:-terbaru} ke GitHub..."

git add -A
git commit -m "deploy: Cinema Tracker v${VER:-update}"
git push
echo "✅ Selesai! Cek tab Actions di GitHub buat ambil APK."
