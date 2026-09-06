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

# При любом обрыве напоминаем о правках версии, оставшихся в рабочем дереве.
VERSION_FILES_TOUCHED=0
cleanup() {
    if [ "$VERSION_FILES_TOUCHED" = "1" ] && [ "${RELEASE_DONE:-0}" != "1" ]; then
        echo >&2
        echo "⚠️ Выпуск прерван, но номер версии и адрес манифеста уже изменены в рабочем дереве." >&2
        echo "   Откатить: git checkout -- smarttubetv/build.gradle common/src/ststable/res/values/update_urls.xml" >&2
    fi
}
trap cleanup EXIT

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

# --- наши патчи к чужому подмодулю ---
# ⛔ Без этого выпуск однажды тихо уедет без них: патчи живут в рабочем дереве подмодуля,
# а не в его истории (пушить в yuliskov/MediaServiceCore мы не можем). См. patches/README.md.
"$REPO_ROOT/scripts/apply-patches.sh"

# --- версия ---
GRADLE_FILE="smarttubetv/build.gradle"
OLD_CODE="$(grep -m1 -oE 'versionCode [0-9]+' "$GRADLE_FILE" | awk '{print $2}')"
NEW_CODE=$((OLD_CODE + 1))
sed -i '' -E "s/versionCode $OLD_CODE/versionCode $NEW_CODE/" "$GRADLE_FILE"
sed -i '' -E "s/versionName \"[^\"]*\"/versionName \"$VERSION_NAME\"/" "$GRADLE_FILE"
VERSION_FILES_TOUCHED=1
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
APK_DIR="smarttubetv/build/outputs/apk/ststable/release"
# ⛔ Чистим каталог сборки: иначе `ls` может подобрать APK от прошлого релиза
# (имя файла содержит номер версии, и выбор «по алфавиту» отдаёт 1.3 вместо 1.4).
rm -f "$APK_DIR"/*.apk
./gradlew :smarttubetv:assembleStstableRelease -x lintVitalStstableRelease
# Берём файл строго по номеру ТОЙ версии, которую сейчас выпускаем.
MATCHES=$(ls "$APK_DIR"/*_"${VERSION_NAME}"_universal.apk 2>/dev/null | wc -l | tr -d ' ')
if [ "$MATCHES" != "1" ]; then
    echo "ожидал ровно один universal-APK версии $VERSION_NAME в $APK_DIR, нашёл $MATCHES" >&2
    exit 1
fi
BUILT="$(ls "$APK_DIR"/*_"${VERSION_NAME}"_universal.apk)"
OUT="$REPO_ROOT/build-release/$APK_NAME"
mkdir -p "$(dirname "$OUT")"
cp "$BUILT" "$OUT"
# ⛔ Пароли передаём через ОКРУЖЕНИЕ, а не аргументом командной строки:
# аргументы любого процесса видны через `ps` всем пользователям машины, окружение — только владельцу.
export EFIR_STORE_PASS="$(grep -m1 '^storePassword=' "$KEYSTORE_PROPS" | cut -d= -f2-)"
export EFIR_KEY_PASS="$(grep -m1 '^keyPassword=' "$KEYSTORE_PROPS" | cut -d= -f2-)"
[ -n "$EFIR_STORE_PASS" ] && [ -n "$EFIR_KEY_PASS" ] || { echo "не прочитались пароли из $KEYSTORE_PROPS" >&2; exit 1; }
apksigner sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass env:EFIR_STORE_PASS --key-pass env:EFIR_KEY_PASS "$OUT"
unset EFIR_STORE_PASS EFIR_KEY_PASS
apksigner verify "$OUT" >/dev/null && echo "подпись проверена"

# --- манифест для встроенного апдейтера ---
MANIFEST="$REPO_ROOT/build-release/$MANIFEST_NAME"
# ⛔ Отличаем «релиза ещё нет» от сетевого сбоя. Если проглотить ошибку, манифест будет
# перезаписан пустым, и ВСЯ история changelog прошлых версий пропадёт на сервере.
set +e
RELEASE_INFO="$(gh release view latest --repo "$SLUG" --json assets 2>&1)"
RELEASE_RC=$?
set -e
if [ $RELEASE_RC -ne 0 ]; then
    if printf '%s' "$RELEASE_INFO" | grep -qiE "release not found|not found"; then
        echo "релиза latest ещё нет — создаём историю changelog с нуля"
        echo '{}' > "$MANIFEST"
    else
        echo "не удалось проверить релиз (сеть, токен, GitHub): $RELEASE_INFO" >&2
        echo "прерываюсь, чтобы не затереть накопленный changelog пустым манифестом" >&2
        exit 1
    fi
elif printf '%s' "$RELEASE_INFO" | grep -q "$MANIFEST_NAME"; then
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
RELEASE_DONE=1
echo "готово: версия $VERSION_NAME (код $NEW_CODE) опубликована"
echo "манифест: $MANIFEST_URL"
echo "⚠️ не забудь закоммитить изменившиеся $GRADLE_FILE и update_urls.xml"
