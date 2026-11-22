#!/usr/bin/env sh
set -e

APP_HOME="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"

if [ -f "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" ]; then
  JAVACMD="${JAVA_HOME:+$JAVA_HOME/bin/}java"
  exec "$JAVACMD" -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
fi

GRADLE_VER="8.7"
BOOT_DIR="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/cinema-tracker-gradle-$GRADLE_VER"
GRADLE_BIN="$BOOT_DIR/gradle-$GRADLE_VER/bin/gradle"

if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$BOOT_DIR"
  ZIP_PATH="$BOOT_DIR/gradle-$GRADLE_VER-bin.zip"
  URL="https://services.gradle.org/distributions/gradle-$GRADLE_VER-bin.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$URL" -o "$ZIP_PATH"
  elif command -v wget >/dev/null 2>&1; then
    wget -qO "$ZIP_PATH" "$URL"
  else
    echo "Neither curl nor wget found to bootstrap Gradle $GRADLE_VER" >&2
    exit 1
  fi
  unzip -qo "$ZIP_PATH" -d "$BOOT_DIR"
  rm -f "$ZIP_PATH"
  chmod +x "$GRADLE_BIN"
fi

exec "$GRADLE_BIN" "$@"
