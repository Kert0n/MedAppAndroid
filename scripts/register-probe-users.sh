#!/usr/bin/env bash
# Заводит и восстанавливает трёх пробных пользователей проб на сервере из local.properties.
# Двое ходят в ContractProbe и в истории двух людей, третий — в истории, где полок две, а людей трое.
#
# Учётные данные придумывает клиент (PLAN B1): логин и пароль дописываются в local.properties
# ДО запроса, поэтому потерянный ответ ничего не теряет — повтор идёт теми же данными. Если сервер
# учётку потерял (новая раскатка, чистая база), скрипт регистрирует те же данные заново, и она
# возвращается под тем же логином. Учётка, которая на месте, не трогается.
#
# Секреты уходят curl через stdin, а не аргументами: аргументы видны в списке процессов. На экран
# они не выводятся, а local.properties в git не попадает (PLAN G, AGENTS «Связь с сервером»).
set -euo pipefail
cd "$(dirname "$0")/.."

props=local.properties
prop() { grep -E "^$1=" "$props" | head -1 | cut -d= -f2- || true; }

base=$(prop MEDAPP_BASE_URL)
base=${base:-https://medapp.ru.net}
token=$(prop MEDAPP_REGISTRATION_TOKEN)
if [ -z "$token" ]; then
    echo "MEDAPP_REGISTRATION_TOKEN не задан в $props" >&2
    exit 1
fi

# Дописываемая строка не должна прилипнуть к последней строке файла.
[ -n "$(tail -c1 "$props")" ] && echo >> "$props"

# Что говорит выдача пропуска по этим данным; печатается код ответа.
token_code() {
    printf 'user = "%s:%s"\n' "$1" "$2" |
        curl -sS -o /dev/null -w '%{http_code}' -X POST "$base/v1/auth/token" --config -
}

# Просим сервер запомнить придуманные данные; печатается код ответа.
register() {
    printf 'header = "X-Registration-Token: %s"\nheader = "Content-Type: application/json"\ndata = "{\\"login\\":\\"%s\\",\\"password\\":\\"%s\\"}"\n' \
        "$token" "$1" "$2" |
        curl -sS -o /dev/null -w '%{http_code}' -X POST "$base/v1/auth/register" --config -
}

for user in A B C; do
    login=$(prop "MEDAPP_PROBE_${user}_LOGIN")
    password=$(prop "MEDAPP_PROBE_${user}_KEY")
    if [ -z "$login" ] || [ -z "$password" ]; then
        login=$(uuidgen | tr '[:upper:]' '[:lower:]')
        password=$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')
        printf 'MEDAPP_PROBE_%s_LOGIN=%s\nMEDAPP_PROBE_%s_KEY=%s\n' \
            "$user" "$login" "$user" "$password" >> "$props"
        echo "Пробный пользователь $user придуман и записан в $props."
    fi

    # 200 — учётка есть и она наша; 401 — сервер её не знает, заводим теми же данными; всё
    # остальное (429, 5xx, обрыв) о существовании учётки не говорит, и гадать по нему нельзя.
    issued=$(token_code "$login" "$password")
    case "$issued" in
        200)
            echo "Пробный пользователь $user на месте."
            continue
            ;;
        401) ;;
        *)
            echo "Выдача пропуска пользователю $user ответила HTTP $issued — состояние учётки неизвестно." >&2
            echo "Повторите позже; регистрировать поверх неизвестного состояния скрипт не будет." >&2
            exit 1
            ;;
    esac

    code=$(register "$login" "$password")
    case "$code" in
        201) echo "Пробный пользователь $user заведён на $base." ;;
        409)
            echo "Логин пользователя $user занят, а пропуск по записанному паролю не выдан." >&2
            echo "Удалите строки MEDAPP_PROBE_${user}_* из $props и запустите скрипт снова." >&2
            exit 1
            ;;
        *)
            echo "Регистрация пользователя $user не прошла: HTTP $code" >&2
            exit 1
            ;;
    esac
done
