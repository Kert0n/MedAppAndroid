#!/usr/bin/env python3
"""director.py new|original — проходит сценарий по времени реплик на телефоне (запись уже идёт)."""
import re, subprocess, sys, time, xml.etree.ElementTree as ET

ADB = ["adb", "-s", "54121FDAS000R7"]
SCRIPT = "/Users/kert0n/Documents/SharedDocs/Programming/Projects/MedApp/src/AndroidApp/scripts/demo-phone.sh"


import json
TOUCHES = []


def sh(*a):
    return subprocess.run(ADB + ["shell", *a], capture_output=True, text=True).stdout


def nodes():
    sh("uiautomator", "dump", "/sdcard/ui.xml")
    xml = subprocess.run(ADB + ["exec-out", "cat", "/sdcard/ui.xml"], capture_output=True, text=True).stdout
    try:
        root = ET.fromstring(xml[xml.index("<"):])
    except Exception:
        return []
    out = []
    for n in root.iter("node"):
        label = n.get("text") or n.get("content-desc") or ""
        m = re.findall(r"\d+", n.get("bounds", ""))
        if label and len(m) == 4:
            x1, y1, x2, y2 = map(int, m)
            out.append((label, (x1 + x2) // 2, (y1 + y2) // 2))
    return out


def find(want, timeout=6.0):
    until = time.time() + timeout
    while time.time() < until:
        ns = nodes()
        hit = [n for n in ns if n[0] == want] or [n for n in ns if want in n[0]]
        if hit:
            return hit[0]
        time.sleep(0.3)
    raise RuntimeError(f"нет на экране: {want}")


def tap(want, timeout=6.0):
    _, x, y = find(want, timeout)
    t_xy(x, y, 0.6)


def back(n=1):
    for _ in range(n):
        sh("input", "keyevent", "4")
        time.sleep(0.6)


def to_bar():
    for _ in range(5):
        labels = {n[0] for n in nodes()}
        if {"Аптечки", "План", "Сканер", "Опции"} <= labels:
            return
        back()
    raise RuntimeError("нижняя панель не появилась")


def place(name):
    to_bar()
    ns = [n for n in nodes() if n[0] == name]
    _, x, y = max(ns, key=lambda n: n[2])
    t_xy(x, y, 0.8)


def swipe(x1, y1, x2, y2, ms=400):
    TOUCHES.append({"at": time.time(), "x1": x1, "y1": y1, "x2": x2, "y2": y2, "ms": ms})
    sh("input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms))
    time.sleep(0.8)


def scroll_to(want):
    for _ in range(4):
        if any(want == n[0] for n in nodes()):
            return tap(want)
        swipe(500, 1800, 500, 900, 500)
    tap(want)


def drag_express():
    _, x, y = find("Переставить: Нурофен Экспресс")
    _, _, top = find("Переставить: Нурофен")
    target = top - 40
    sh("input", "motionevent", "DOWN", str(x), str(y))
    time.sleep(1.0)
    steps = 14
    for i in range(1, steps + 1):
        sh("input", "motionevent", "MOVE", str(x), str(y + (target - y) * i // steps))
        time.sleep(0.05)
    time.sleep(0.4)
    sh("input", "motionevent", "UP", str(x), str(target))
    time.sleep(1.2)


def slider_full():
    # ползунок Экспресса — первая строка источников после перетаскивания
    _, _, y = find("Приёмов из")
    swipe(168, y + 8, 610, y + 8, 900)


def shade_group():
    swipe(504, 12, 504, 1300, 450)
    time.sleep(0.8)
    ns = nodes()
    med = [n for n in ns if n[0] == "MedApp"]
    if not med:
        raise RuntimeError("в шторке нет MedApp")
    my = med[0][2]
    exp = min((n for n in ns if n[0] in ("Expand", "Развернуть") and n[2] >= my - 20), key=lambda n: n[2])
    t_xy(exp[1], exp[2], 1.2)


def expand_item(title):
    ns = nodes()
    _, _, ty = next(n for n in ns if title in n[0])
    exp = min((n for n in ns if n[0] in ("Expand", "Развернуть")), key=lambda n: abs(n[2] - ty))
    t_xy(exp[1], exp[2], 1.2)


def collapse():
    swipe(504, 2150, 504, 400, 350)
    sh("cmd", "statusbar", "collapse")
    time.sleep(0.6)


def online():
    subprocess.run([SCRIPT, "online"], capture_output=True)


def open_app():
    sh("monkey", "-p", "com.kert0n.medapp", "-c", "android.intent.category.LAUNCHER", "1")
    find("Без ответа за прошлые дни", timeout=10)


def close_popup():
    # Попап встаёт по-разному (одна строка или две): «Закрыть» ищется на экране, и нажатие
    # повторяется, пока попап не уйдёт.
    for _ in range(3):
        _, x, y = find("Закрыть", timeout=6)
        t_xy(x, y, 0.8)
        if not any(n[0].startswith("Без ответа") for n in nodes()):
            return
    raise RuntimeError("попап не закрылся")


def scan():
    place("Сканер")
    try:
        find("Название", timeout=30)
        return True
    except RuntimeError:
        return False


def scroll_form():
    swipe(500, 1700, 500, 800, 900)


P = {
    "Закрыть": (790, 593), "Домашняя": (203, 1055), "Дача": (138, 662), "Просроченные": (227, 588),
    "Нурофен": (181, 1574), "Аптечки": (92, 2121), "План": (297, 2121), "Сканер": (502, 2121),
    "Отчёты": (708, 2121), "Курсы": (767, 393), "День": (273, 393), "Нурофен от спины": (355, 660),
    "Нехватка": (400, 516), "Подключить из листа": (452, 1498), "Подключить в источниках": (452, 1420),
    "Экспресс": (389, 1312), "Источники после подключения": (537, 1280), "Источники": (537, 1096),
    "Сохранить": (504, 2102), "Но-шпа": (166, 998), "Смекта": (165, 1504), "Аспирин": (177, 745),
    "Расход": (811, 393), "Принято": (196, 393), "Меню": (937, 230), "Пригласить в меню": (853, 494),
    "Пригласить": (544, 595), "Пригласить в вопросе": (706, 1423), "Во весь экран": (499, 1552),
}


def t(name, pause=0.5):
    x, y = P[name]
    t_xy(x, y, pause + 0.2)


def list_down():
    swipe(500, 1900, 500, 700, 400)


def drag_fast():
    # Ручка — обычный draggableHandle: одного плавного жеста хватает, без долгого нажатия.
    swipe(102, 1434, 102, 840, 1400)
    time.sleep(0.6)


def slider_fast():
    swipe(168, 1364, 610, 1364, 900)


def shade_fast():
    swipe(504, 12, 504, 1300, 450)
    time.sleep(0.6)
    t_xy(883, 725)


def t_xy(x, y, pause=1.0):
    # Нажатие держится, как у пальца: волна Material успевает разойтись, а точка касания
    # рисуется поверх записи по этому журналу.
    TOUCHES.append({"at": time.time(), "x1": x, "y1": y, "x2": x, "y2": y, "ms": 160})
    sh("input", "swipe", str(x), str(y), str(x), str(y), "160")
    time.sleep(max(pause - 0.2, 0.2))


def backs(n):
    back(n)


NEW = [
    (0, open_app),
    (5, close_popup),
    (9, lambda: t("Домашняя")),
    (10, lambda: t("Просроченные")),
    (14, lambda: t("Просроченные")),
    (15, lambda: t("Нурофен")),
    (21, lambda: swipe(500, 1500, 500, 1000, 600)),
    (24, lambda: backs(2)),
    (25, lambda: (t("План", 0.8), t("Курсы", 0.8), t("Нурофен от спины"))),
    (32, lambda: t("Нехватка")),
    (37, lambda: t("Подключить из листа", 1.0)),
    (38, list_down),
    (42, lambda: t("Экспресс", 1.2)),
    (43, lambda: t("Источники после подключения", 1.0)),
    (45, drag_fast),
    (49, slider_fast),
    (53, lambda: t("Сохранить")),
    (54, lambda: (backs(2), t("День"))),
    (56, shade_fast),
    (59, lambda: (expand_item("Пора принять"), tap("Принял"))),
    (65, collapse),
    (66, lambda: (t("Аптечки", 0.8), t("Дача"))),
    (70, lambda: t("Но-шпа")),
    (73, lambda: (back(), t("Смекта"))),
    (78, lambda: (back(), t("Аспирин"))),
    (85, lambda: (online(), backs(2), t("Отчёты"))),
    (88, lambda: t("Расход")),
    (90, lambda: t("Принято")),
    (93, lambda: (t("Аптечки", 0.8), t("Дача", 0.8), t("Меню", 0.6), t("Пригласить в меню"))),
    (97, lambda: (t("Пригласить", 0.8), t("Пригласить в вопросе"))),
    (103, lambda: t("Во весь экран")),
    (105, lambda: (backs(3), scan())),
    (116, scroll_form),
    (122, lambda: None),
]

ORIGINAL = [
    (0, open_app),
    (6, close_popup),
    (10, lambda: t("Домашняя")),
    (12, lambda: t("Просроченные")),
    (14, lambda: t("Просроченные")),
    (16, lambda: t("Нурофен")),
    (22, lambda: swipe(500, 1500, 500, 1000, 600)),
    (25, lambda: backs(2)),
    (26, lambda: (t("План", 0.8), t("Курсы", 0.8), t("Нурофен от спины"))),
    (33, lambda: t("Источники")),
    (35, lambda: t("Подключить в источниках", 1.0)),
    (37, list_down),
    (41, lambda: t("Экспресс", 1.2)),
    (45, drag_fast),
    (49, slider_fast),
    (53, lambda: t("Сохранить")),
    (56, lambda: (backs(2), t("День"))),
    (58, shade_fast),
    (60, lambda: (expand_item("Пора принять"), tap("Принял"))),
    (64, collapse),
    (68, lambda: (t("Аптечки", 0.8), t("Дача"))),
    (72, lambda: t("Но-шпа")),
    (76, lambda: (back(), t("Смекта"))),
    (80, online),
    (82, lambda: (back(), t("Аспирин"))),
    (92, back),
    (95, lambda: (t("Меню", 0.6), t("Пригласить в меню"))),
    (98, lambda: (t("Пригласить", 0.8), t("Пригласить в вопросе"))),
    (104, lambda: t("Во весь экран")),
    (108, lambda: (backs(2), shade_fast(), tap("Лекарства стало меньше", timeout=20))),
    (116, lambda: (backs(2), t("План", 0.8), t("Курсы", 0.8), t("Нурофен от спины"))),
    (122, lambda: t("Нехватка")),
    (126, back),
    (128, lambda: (backs(1), t("Отчёты"))),
    (131, lambda: t("Расход")),
    (134, lambda: t("Принято")),
    (137, scan),
    (148, scroll_form),
    (154, lambda: None),
]

plan = NEW if sys.argv[1] == "new" else ORIGINAL
CLIP = "/sdcard/medapp-demo.mp4"
sh("pkill", "-INT", "screenrecord")
time.sleep(1.0)
sh("rm", "-f", CLIP)
rec = subprocess.Popen(ADB + ["shell", "screenrecord", "--size", "1008x2244", "--bit-rate", "12000000", "--time-limit", "180", CLIP])
time.sleep(1.0)
t0 = time.time()
for at, act in plan:
    now = time.time() - t0
    if now < at:
        time.sleep(at - now)
    started = time.time() - t0
    try:
        result = act()
    except Exception as e:
        print(f"{started:6.1f} [{at}] СБОЙ: {e}")
        sh("pkill", "-INT", "screenrecord")
        raise SystemExit(1)
    print(f"{started:6.1f} [{at}] {getattr(act, '__name__', '')} {result if result is not None else ''}")
time.sleep(1.5)
stopped = time.time()
sh("pkill", "-INT", "screenrecord")
rec.wait(timeout=15)
time.sleep(1.0)
out = sys.argv[2]
subprocess.run(ADB + ["pull", CLIP, out], capture_output=True)
json.dump({"stopped": stopped, "touches": TOUCHES}, open(out + ".touches.json", "w"))
print(f"итого {time.time() - t0:.1f} с → {out}")
