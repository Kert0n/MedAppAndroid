# Снимки экранов

Живые проходы перед сдачей UI-PR (AGENTS «Эмулятор»): `<эмулятор>/<NN-экран>/<состояние>.png`,
где `NN` — номер экрана по PLAN H3. Снимаются с устройства как есть (`screencap`), без обработки.

| эмулятор | что ловит |
|---|---|
| `Sm29` | минимум: API 29, 360×640 dp |
| `BigScaled` | большой экран с поднятым масштабом: API 37, 540 dpi, шрифт ×1.3 — 398×887 dp |

Состояния: `empty`, `filled`, `light`/`dark`, `landscape`, `expired`, `refusal-…`, диалоги.

## Снимки, которые `screencap` не берёт

Экраны с ключом приглашения закрыты `FLAG_SECURE` (REQ-007, G3), и кадр с устройства выходит
чёрным. Состояния очереди — отвергнутая операция, нечитаемая строка, «удаление в пути» — руками
воспроизводятся долго и не всегда. Такие снимки рисует `U6ScreenShots` в своём процессе: то же
дерево составляющих, та же тема, тот же экран устройства.

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s <устройство> install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s <устройство> install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <устройство> shell am instrument -w \
  -e class com.kert0n.medapp.ui.U6ScreenShots com.kert0n.medapp.test/com.kert0n.medapp.HiltTestRunner
for p in $(adb -s <устройство> exec-out run-as com.kert0n.medapp find files/shots -name '*.png'); do
  rel=${p#files/shots/}; mkdir -p "docs/screens/<эмулятор>/$(dirname "$rel")"
  adb -s <устройство> exec-out run-as com.kert0n.medapp cat "$p" > "docs/screens/<эмулятор>/$rel"
done
```

Приложение ставится **руками**: `connectedDebugAndroidTest` удаляет его после прогона, и файлы
уходят вместе с ним.
