#!/usr/bin/env bash
# Накатывает наши патчи на подмодуль MediaServiceCore (чужой репозиторий — пушить туда нельзя).
# Подробности и список патчей: patches/README.md
#
#   scripts/apply-patches.sh
#
# Уже применённый патч не считается ошибкой — о нём просто сообщается.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SUBMODULE="$REPO_ROOT/MediaServiceCore"
PATCH_DIR="$REPO_ROOT/patches"

[ -d "$SUBMODULE" ] || { echo "нет подмодуля: $SUBMODULE" >&2; exit 1; }

shopt -s nullglob
PATCHES=("$PATCH_DIR"/*.patch)
shopt -u nullglob

if [ ${#PATCHES[@]} -eq 0 ]; then
    echo "патчей нет"
    exit 0
fi

FAILED=0
for patch in "${PATCHES[@]}"; do
    name="$(basename "$patch")"
    if git -C "$SUBMODULE" apply --reverse --check "$patch" >/dev/null 2>&1; then
        echo "уже применён: $name"
    elif git -C "$SUBMODULE" apply --check "$patch" >/dev/null 2>&1; then
        git -C "$SUBMODULE" apply "$patch"
        echo "применён:     $name"
    else
        # Не накладывается и не применён — скорее всего апстрим переписал это место.
        echo "⚠️ НЕ ЛОЖИТСЯ:  $name — проверь, нужен ли он ещё (patches/README.md)" >&2
        FAILED=1
    fi
done

exit $FAILED
