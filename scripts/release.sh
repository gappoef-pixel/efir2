#!/usr/bin/env bash
# Выпуск новой версии «Эфира 2».
#
#   scripts/release.sh 1.1 "Что нового в этой версии"
#
# Что делает:
#   1) поднимает versionCode на 1, ставит переданное имя версии;
#   2) прописывает в update_urls.xml адрес манифеста, выведенный из git remote;
#   3) собирает релиз и подписывает нашим ключом;
#   4) собирает JSON-манифест для встроенного апдейтера, дописывая changelog к прошлым версиям;
#   5) заливает APK и манифест в релиз с подвижным тегом latest — ссылка не меняется никогда.
#
# ⛔ Подпись ВСЕГДА тем же ключом: иначе Android не даст поставить обновление поверх.
# ⛔ versionCode только вверх: понижение делает обновление невозможным.
set -euo pipefail

VERSION_NAME="${1:-}"
CHANGELOG="${2:-}"
if [ -z "$VERSION_NAME" ] || [ -z "$CHANGELOG" ]; then
    echo "Использование: scripts/release.sh <имя версии> <строка changelog>" >&2
    exit 1
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

KEYSTORE="${EFIR_KEYSTORE:-$HOME/.efir-release.jks}"
KEY_ALIAS="${EFIR_KEY_ALIAS:-efir}"
KEYSTORE_PROPS="${EFIR_KEYSTORE_PROPS:-$HOME/.claude/home-media/tv-youtube-app/android/keystore.properties}"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdk17/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/.local/android-sdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/build-tools/34.0.0:$PATH"

command -v gh >/dev/null || { echo "нет gh — установи и выполни gh auth login" >&2; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "gh не авторизован — выполни gh auth login" >&2; exit 1; }
[ -f "$KEYSTORE" ] || { echo "нет ключа подписи: $KEYSTORE" >&2; exit 1; }

# --- откуда берётся адрес манифеста: из настоящего remote, а не из зашитой строки ---
ORIGIN_URL="$(git remote get-url origin)"
SLUG="$(printf '%s' "$ORIGIN_URL" | sed -E 's#(git@github.com:|https://github.com/)##; s#\.git$##')"
MANIFEST_NAME="efir2_stable.json"
MANIFEST_URL="https://github.com/$SLUG/releases/download/latest/$MANIFEST_NAME"
APK_NAME="efir2_universal.apk"
APK_URL="https://github.com/$SLUG/releases/download/latest/$APK_NAME"
echo "репозиторий: $SLUG"

# --- версия ---
GRADLE_FILE="smarttubetv/build.gradle"
OLD_CODE="$(grep -m1 -oE 'versionCode [0-9]+' "$GRADLE_FILE" | awk '{print $2}')"
NEW_CODE=$((OLD_CODE + 1))
sed -i '' -E "s/versionCode $OLD_CODE/versionCode $NEW_CODE/" "$GRADLE_FILE"
sed -i '' -E "s/versionName \"[^\"]*\"/versionName \"$VERSION_NAME\"/" "$GRADLE_FILE"
echo "версия: $OLD_CODE → $NEW_CODE ($VERSION_NAME)"

# --- адрес обновлений внутри приложения ---
cat > common/src/ststable/res/values/update_urls.xml <<XML
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string-array name="update_urls">
        <item>$MANIFEST_URL</item>
    </string-array>
</resources>
XML

# --- сборка и подпись ---
./gradlew :smarttubetv:assembleStstableRelease -x lintVitalStstableRelease
BUILT="$(ls smarttubetv/build/outputs/apk/ststable/release/*universal*.apk | head -1)"
OUT="$REPO_ROOT/build-release/$APK_NAME"
mkdir -p "$(dirname "$OUT")"
cp "$BUILT" "$OUT"
STORE_PASS="$(grep -m1 '^storePassword=' "$KEYSTORE_PROPS" | cut -d= -f2-)"
KEY_PASS="$(grep -m1 '^keyPassword=' "$KEYSTORE_PROPS" | cut -d= -f2-)"
[ -n "$STORE_PASS" ] && [ -n "$KEY_PASS" ] || { echo "не прочитались пароли из $KEYSTORE_PROPS" >&2; exit 1; }
apksigner sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$STORE_PASS" --key-pass "pass:$KEY_PASS" "$OUT"
apksigner verify "$OUT" >/dev/null && echo "подпись проверена"

# --- манифест для встроенного апдейтера ---
MANIFEST="$REPO_ROOT/build-release/$MANIFEST_NAME"
PREV="$(gh release view latest --repo "$SLUG" --json assets -q '.assets[].name' 2>/dev/null | grep -c "$MANIFEST_NAME" || true)"
if [ "$PREV" != "0" ]; then
    gh release download latest --repo "$SLUG" --pattern "$MANIFEST_NAME" --dir "$REPO_ROOT/build-release" --clobber
else
    echo '{}' > "$MANIFEST"
fi
python3 - "$MANIFEST" "$VERSION_NAME" "$NEW_CODE" "$CHANGELOG" "$APK_URL" <<'PY'
import json, sys
path, name, code, changelog, apk_url = sys.argv[1:6]
try:
    data = json.load(open(path))
except Exception:
    data = {}
data["package"] = {"downloadUrlList": [apk_url]}
data[name] = {"versionCode": int(code), "changelog": [changelog]}
json.dump(data, open(path, "w"), ensure_ascii=False, indent=2)
print("манифест собран, версий внутри:", len([k for k in data if k != "package"]))
PY

# --- публикация ---
gh release view latest --repo "$SLUG" >/dev/null 2>&1 || gh release create latest --repo "$SLUG" --title "Эфир 2" --notes "Свежая сборка «Эфира 2»"
gh release upload latest --repo "$SLUG" "$OUT" "$MANIFEST" --clobber
echo
echo "готово: версия $VERSION_NAME (код $NEW_CODE) опубликована"
echo "манифест: $MANIFEST_URL"
echo "⚠️ не забудь закоммитить изменившиеся $GRADLE_FILE и update_urls.xml"
