# Экраны MedApp

Коллекция снимков для документации и инструкций. Снимает её **обход** — `app/src/androidTest/java/com/kert0n/medapp/tour` —
на `BigLatest` (1344×2992, 480 dpi, API 37), **по-русски**: приложение объявляет свой язык, и
формы множественного числа («4 упаковки», «9 приёмов») выбирает русское правило. Имена единиц и
форм выпуска («шт», «мл», «таблетки») приходят из словаря сервера и не склоняются.

Обход идёт настоящей оболочкой над миром `TourWorld`: три аптечки (домашняя, общая дача, пустой
рюкзак), лекарства во всех состояниях сразу — просроченная, истекающая, на лечении, в пути, с чужими
бронями, отвергнутая сервером, — идущее лечение с нехваткой, черновик, отменённое лечение и день,
на который уже отвечали. Связь на время обхода отнята: очередь стоит, и видно всё, что ещё едет.
Экраны под `FLAG_SECURE` (ключ приглашения) и первичная настройка рисуются в процессе
(`InProcessTour`) — у них нет строки состояния.

## Что где

| экран | состояние | что видно |
|---|---|---|
| `01-setup` | `checking`, `no-connection` | первая настройка: проверка учётной записи; нет связи с сервером |
| `01-setup` | `key-lost`, `key-lost-confirm` | ключ устройства утрачен: что останется и что пропадёт, подтверждение |
| `02-med-kits` | `filled` | список аптечек: число упаковок, просроченные, общая с участниками, пустая |
| `02-med-kits` | `missed-popup`, `expiring-today-popup` | при входе: приёмы без ответа за прошлые дни; сегодня истекает срок |
| `02-med-kits` | `dark` | то же в тёмной теме |
| `03-med-kit-form` | `new`, `edit` | заведение и правка аптечки |
| `04-med-kit-contents` | `filled`, `narrowed`, `nothing-found`, `empty` | содержимое: просроченная первой, сужение, пустой поиск, пустая аптечка |
| `04-med-kit-contents` | `menu`, `shared-menu`, `shared-in-flight` | меню аптечки; общая дача с «Изменение в пути» и «Удаление в пути» |
| `04-med-kit-contents` | `dark` | тёмная тема |
| `05-all-medicines` | `filled` | все лекарства во всех аптечках |
| `06-package-card` | `on-course`, `expired`, `claimed-by-others` | карточка: занята лечением; просрочена; брони других участников |
| `06-package-card` | `change-on-its-way`, `refused-by-server` | изменение ещё не доехало; сервер отклонил — пересчитать |
| `06-package-card` | `remove-dialog`, `dark` | подтверждение «Выбросить»; тёмная тема |
| `07-package-form` | `new` | новая упаковка |
| `08-package-edit` | `filled` | правка упаковки |
| `09-recount` | `filled` | пересчёт |
| `10-unplanned-intake` | `filled`, `questions` | разовый приём; вопрос «приём заденет занятое» |
| `11-transfer` | `filled` | перенос в другую аптечку |
| `12-day` | `today`, `tomorrow` | план дня: принятый, запланированные, отменённый, разовый; завтра |
| `12-day` | `notifications-off`, `dark` | напоминания не приходят; тёмная тема |
| `13-courses` | `filled` | лечения: идущее с нехваткой, черновик, завершённое |
| `14-course-card` | `running`, `shortage-remedies`, `cancelled` | карточка лечения; что делать с нехваткой; отменённое |
| `15-course-form` | `new`, `draft` | новое лечение; черновик с заметкой |
| `16-course-sources` | `stack`, `unsaved-edit` | источники: обеспечение «Записано» первым, препарат карточкой; незаписанная правка — строка «С правкой» |
| `17-source-picking` | `cards` | выбор препарата: подходящие и неподходящие с причиной |
| `18-intake-card` | `planned` | карточка пункта плана |
| `19-intake-history` | `of-package`, `of-course` | история приёмов лекарства и лечения |
| `20-sharing` | `local`, `shared` | что станет общим; выданный код приглашения |
| `21-invitation` | `full-screen` | QR во весь экран |
| `22-joining` | `empty` | присоединиться по коду |
| `23-removal` | `shared` | уборка общей аптечки: три судьбы |
| `24-scanner` | `live` | сканер: видоискатель и подсказка |
| `26-reports` | `summary`, `future`, `spent`, `spent-year` | отчёты: сводка полосами по категориям и формам; расход до даты; истрачено за месяц и за год |
| `27-options` | `root` | место «Опции»: синхронизация и разрешения с бедой |
| `27-settings` | `form` | пороги и время уведомлений, обмен с сервером |
| `27-permissions` | `notifications-off`, `all-allowed` | разрешения: уведомления выключены; всё разрешено |
| `27-language` | `choice` | язык: как в системе, русский, English |
| `28-sync` | `rows` | синхронизация: ждущие строки и отказ сервера |
| `29-notification` | `shade` | шторка: напоминание о приёме с ответами, сообщение очереди, сводка дня |


## Как переснять

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell run-as com.kert0n.medapp rm -rf files/tour
adb -s emulator-5554 shell am instrument -w -e tour true -e package com.kert0n.medapp.tour \
  com.kert0n.medapp.test/com.kert0n.medapp.HiltTestRunner
for p in $(adb -s emulator-5554 exec-out run-as com.kert0n.medapp find files/tour -name '*.png'); do
  rel=${p#files/tour/}; mkdir -p "docs/screens/$(dirname "$rel")"
  adb -s emulator-5554 exec-out run-as com.kert0n.medapp cat "$p" > "docs/screens/$rel"
done
```

Одну главу — `-e class com.kert0n.medapp.tour.PlanTour`. Приложение ставится **руками**:
`connectedDebugAndroidTest` удаляет его после прогона вместе со снимками. Сбой главы оставляет снимок
экрана в `_fail/` — по нему видно, чего обход не нашёл; в коллекцию `_fail` не кладётся.
