#!/usr/bin/env bash
# Съёмка показа с телефона (docs/demo/съёмка-с-телефона.md).
#
#   scripts/demo-phone.sh setup     один раз: собрать и поставить сборку для записи
#   scripts/demo-phone.sh live      наполнение для показа вживую: мир посеян, уведомления в шторке, без записи
#   scripts/demo-phone.sh take      каждый дубль: пересеять мир, уведомления уже в шторке, запись пошла
#   scripts/demo-phone.sh take neighbour   то же, и сосед берёт из дачной пачки (уведомление после online)
#   scripts/demo-phone.sh online    в кадре: вернуть связь (перед приглашением)
#   scripts/demo-phone.sh stop      конец дубля: остановить запись и забрать файл
#   scripts/demo-phone.sh restore   после съёмки: вернуть живую базу и учётку, убрать «Дачу»
#   scripts/demo-phone.sh clear     только убрать «Дачу» с сервера
#
# Телефон — первый подключённый не-эмулятор, или ANDROID_SERIAL. На эмуляторе (ANDROID_SERIAL=emulator-…)
# ставится debug, а «я» в мире — собственная учётка эмулятора: так у телефона и эмулятора каждая своя
# «Дача», и посев одного не убирает «Дачу» другого.
set -euo pipefail

cd "$(dirname "$0")/.."
PACKAGE=com.kert0n.medapp
RUNNER=$PACKAGE.test/$PACKAGE.HiltTestRunner
SERIAL=${ANDROID_SERIAL:-$(adb devices | awk 'NR > 1 && $2 == "device" && $1 !~ /^emulator-/ { print $1; exit }')}
[ -n "$SERIAL" ] || { echo "телефон не подключён (adb devices)"; exit 1; }
ADB=(adb -s "$SERIAL")
case "$SERIAL" in emulator-*) EMU=1 ;; *) EMU=0 ;; esac
ME=(); [ $EMU = 1 ] && ME=(-e me own)
OUT=../../docs/WorkInProgress
CLIP=/sdcard/medapp-demo.mp4

prop() { sed -n "s/^$1=//p" local.properties | head -1; }

# Адрес и пробные учётки — из local.properties, аргументами инструментации: в APK их нет.
probe_args() {
    local args=(-e probeBaseUrl "$(prop MEDAPP_BASE_URL)")
    for user in A B C; do
        args+=(-e "probeLogin$user" "$(prop "MEDAPP_PROBE_${user}_LOGIN")" -e "probeKey$user" "$(prop "MEDAPP_PROBE_${user}_KEY")")
    done
    printf '%s\0' "${args[@]}"
}

instrument() {
    local args=()
    while IFS= read -r -d '' a; do args+=("$a"); done < <(probe_args)
    "${ADB[@]}" logcat -c
    local log
    log=$("${ADB[@]}" shell am instrument -w -e stage "$1" "${@:2}" ${ME[@]+"${ME[@]}"} "${args[@]}" \
        -e class $PACKAGE.tour.PhoneStage "$RUNNER" 2>&1)
    "${ADB[@]}" logcat -d -s PhoneStage:I | grep PhoneStage || true
    if ! grep -q "^OK (" <<<"$log"; then
        echo "$log" | tail -n 30
        echo "не сыграло — см. вывод выше"
        exit 1
    fi
}

stop_recording() { "${ADB[@]}" shell pkill -INT screenrecord >/dev/null 2>&1 || true; }

case "${1:-}" in
setup)
  if [ $EMU = 1 ]; then
    echo "сборка debug для эмулятора…"
    JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.4-tem" ./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest -Pmedapp.recording=true
    "${ADB[@]}" install -r -g app/build/outputs/apk/debug/app-debug.apk
    "${ADB[@]}" uninstall $PACKAGE.test >/dev/null 2>&1 || true
    "${ADB[@]}" install -r -t -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    "${ADB[@]}" shell appops set $PACKAGE SCHEDULE_EXACT_ALARM allow
    echo "готово. Наполнение — ANDROID_SERIAL=$SERIAL scripts/demo-phone.sh live"
  else
    echo "сборка release для записи (запрет снимка экрана снят только в ней)…"
    JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.4-tem" MEDAPP_PUBLICATION=${MEDAPP_PUBLICATION:-16} \
        ./gradlew -q :app:assembleRelease :app:assembleReleaseAndroidTest \
        -Pmedapp.testBuildType=release -Pmedapp.recording=true
    # Поверх установленного release — обновлением: данные остаются. Снимать приложение нельзя —
    # там живая учётка; не встаёт по подписи — останавливаемся.
    "${ADB[@]}" install -r app/build/outputs/apk/release/app-release.apk
    # Пакет проверок данных не держит: прежний, с другой подписью, просто снимается.
    "${ADB[@]}" uninstall $PACKAGE.test >/dev/null 2>&1 || true
    "${ADB[@]}" install -r -t app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
    "${ADB[@]}" shell pm grant $PACKAGE android.permission.POST_NOTIFICATIONS
    "${ADB[@]}" shell pm grant $PACKAGE android.permission.CAMERA
    "${ADB[@]}" shell appops set $PACKAGE SCHEDULE_EXACT_ALARM allow
    echo "готово. Дубль — scripts/demo-phone.sh take"
  fi
    ;;
live)
    "${ADB[@]}" shell pm path $PACKAGE.test >/dev/null 2>&1 || { echo "сначала scripts/demo-phone.sh setup"; exit 1; }
    stop_recording
    "${ADB[@]}" shell settings put system show_touches 0
    "${ADB[@]}" shell cmd notification set_dnd off >/dev/null 2>&1 || true
    instrument seed
    # Приложение поднимается один раз: всё, чей срок наступил, оно само ставит в шторку.
    "${ADB[@]}" shell am start -W -n $PACKAGE/.app.MainActivity >/dev/null
    for _ in $(seq 1 20); do
        "${ADB[@]}" shell dumpsys notification --noredact | grep -q "Пора принять" && break
        sleep 0.5
    done
    "${ADB[@]}" shell input keyevent 3
    "${ADB[@]}" shell am kill $PACKAGE
    echo "готово: мир посеян, уведомления в шторке, связь отнята. Вернуть связь — online."
    ;;
take)
    "${ADB[@]}" shell pm path $PACKAGE.test >/dev/null 2>&1 || { echo "сначала scripts/demo-phone.sh setup"; exit 1; }
    stop_recording
    "${ADB[@]}" shell cmd notification set_dnd off >/dev/null 2>&1 || true
    # Касания видны в записи: их рисует система поверх экрана, и screenrecord их снимает.
    "${ADB[@]}" shell settings put system show_touches 1
    # `take neighbour` — ещё и сосед берёт из дачной пачки: после `online` придёт «Лекарства стало меньше».
    if [ "${2:-}" = neighbour ]; then instrument seed -e neighbour true; else instrument seed; fi
    # Приложение поднимается один раз вне кадра: оно само ставит всё, что уже наступило, в шторку.
    # Потом процесс снимается, и вход перед камерой снова холодный — с попапом прошлых дней.
    "${ADB[@]}" shell am start -W -n $PACKAGE/.app.MainActivity >/dev/null
    for _ in $(seq 1 20); do
        "${ADB[@]}" shell dumpsys notification --noredact | grep -q "Пора принять" && break
        sleep 0.5
    done
    "${ADB[@]}" shell input keyevent 3
    "${ADB[@]}" shell am kill $PACKAGE
    "${ADB[@]}" shell rm -f $CLIP
    # Отвязано от скрипта: иначе запись гибнет вместе с ним.
    nohup "${ADB[@]}" shell screenrecord --size 1008x2244 --bit-rate 12000000 --time-limit 180 $CLIP >/dev/null 2>&1 &
    disown
    echo "запись идёт: рабочий стол, уведомления в шторке. Конец дубля — scripts/demo-phone.sh stop"
    ;;
online)
    "${ADB[@]}" shell cmd connectivity airplane-mode disable
    "${ADB[@]}" shell cmd wifi set-wifi-enabled enabled
    echo "связь возвращается"
    ;;
stop)
    stop_recording
    sleep 2
    name="$OUT/demov2-$(date +%H%M%S).mp4"
    "${ADB[@]}" pull $CLIP "$name" >/dev/null
    echo "запись: $name"
    ;;
clear)
    instrument clear
    ;;
restore)
    stop_recording
    "${ADB[@]}" shell settings put system show_touches 0
    instrument restore
    echo "живые данные на месте. Приложение откройте сами — оно проверит учётку при связи."
    ;;
*)
    sed -n '2,16p' "$0"
    exit 1
    ;;
esac
