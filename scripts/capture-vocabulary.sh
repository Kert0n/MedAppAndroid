#!/usr/bin/env bash
# Снимает встроенный снимок словарей с сервера из local.properties учёткой пробного
# пользователя A и кладёт его в app/src/main/assets/vocabulary.json.
#
# Происхождение и дата снятия записываются в сам снимок: идентификаторы серверные, и сверять их
# придётся с тем же сервером. Во встроенный ассет пишется только снимок с боевого сервера:
# MEDAPP_BASE_URL должен совпасть с MEDAPP_PRODUCTION_URL (оба в local.properties), и в ассет уходит
# метка `production`, не адрес. Для любого другого сервера путь вывода называется аргументом, и туда
# пишется его адрес.
#
# Учётка и пропуск на экран не выводятся.
set -euo pipefail
cd "$(dirname "$0")/.."

props=local.properties
asset=app/src/main/assets/vocabulary.json
prop() { grep -E "^$1=" "$props" | head -1 | cut -d= -f2- || true; }

base=$(prop MEDAPP_BASE_URL)
if [ -z "$base" ]; then
    echo "MEDAPP_BASE_URL не задан в $props" >&2
    exit 1
fi
out=${1:-}
origin=$base
if [ -z "$out" ]; then
    production=$(prop MEDAPP_PRODUCTION_URL)
    if [ -z "$production" ] || [ "$base" != "$production" ]; then
        echo "Во встроенный ассет идёт только боевой сервер: MEDAPP_BASE_URL не совпал с MEDAPP_PRODUCTION_URL." >&2
        echo "Для другого сервера назовите файл вывода явно: $0 <путь>" >&2
        exit 1
    fi
    out=$asset
    origin=production
fi

login=$(prop MEDAPP_PROBE_A_LOGIN)
key=$(prop MEDAPP_PROBE_A_KEY)
if [ -z "$login" ] || [ -z "$key" ]; then
    echo "Пробный пользователь A не заведён: сначала scripts/register-probe-users.sh" >&2
    exit 1
fi

# Секреты уходят curl через stdin (`--config -`), а не аргументами: аргументы видны в списке
# процессов любому на машине — как и в register-probe-users.sh.
token=$(printf 'user = "%s:%s"\n' "$login" "$key" |
    curl -sS --fail-with-body -X POST "$base/v1/auth/token" --config - \
    | python3 -c "import json, sys; print(json.load(sys.stdin)['accessToken'])")
authorized() {
    printf 'header = "Authorization: Bearer %s"\n' "$token" |
        curl -sS --fail-with-body "$1" --config -
}
units=$(authorized "$base/v1/quantity-units")
forms=$(authorized "$base/v1/form-types")

# Пишем рядом и переносим на место: успешный ответ с испорченным JSON не должен оставить
# вместо снимка пустой файл — сборка увезла бы его в APK.
mkdir -p "$(dirname "$out")"
tmp=$(mktemp "$(dirname "$out")/vocabulary.XXXXXX")
trap 'rm -f "$tmp"' EXIT

UNITS="$units" FORMS="$forms" ORIGIN="$origin" python3 - > "$tmp" <<'PY'
import datetime
import json
import os

def by_id(entries):
    return sorted(entries, key=lambda entry: entry["id"])

print(json.dumps({
    "origin": os.environ["ORIGIN"],
    "capturedOn": datetime.datetime.now(datetime.timezone.utc).date().isoformat(),
    "version": 1,
    "quantityUnits": by_id(json.loads(os.environ["UNITS"])),
    "formTypes": by_id(json.loads(os.environ["FORMS"])),
}, ensure_ascii=False, indent=2))
PY

python3 -c "import json, sys; s = json.load(open(sys.argv[1])); print(f\"Снимок словарей с {s['origin']}: единиц {len(s['quantityUnits'])}, форм {len(s['formTypes'])}\")" "$tmp"
mv "$tmp" "$out"
trap - EXIT
