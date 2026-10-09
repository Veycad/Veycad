# Уникальные физические центры motion patches / cache v7

2026-09-26. После точного replay старого path выявлен duplicate-support
дефект: border clamp создавал 216 cells при 160 разных центрах на 48×72.
Это подтверждено actual device probe, не только предположением по коду.

`LocalMotionCorrespondence` теперь строит clamped centers, удаляет точные
повторы до matching и выдаёт один Cell на один центр. Параметры сетки,
search/patch radius, texture/fit/uniqueness/roundtrip thresholds, camera
consensus и person coverage требования не менялись. Overlapping patches
всё ещё могут разделять пиксели: уникальность центра не равна статистической
независимости соседних признаков.

Кэш v7 использует тот же binary observation format, но другое имя/header:
старые v6 результаты с repeated patch weights не являются новыми измерениями.
Нет ручного удаления старых cache или экспортов ради прохождения проверки.
Дополнительные unit regressions: 48×72 → 160 уникальных центров;
17×17 с сеткой 4×4 → один центр, не 16 witnesses; v6 read rejected.
Audit measured-static test требует cells == uniqueCenters.

362 Android unit tests, failures/errors 0; APK собран.
APK SHA `f41ecd4a4185af1dc44d61da4636047c92750125f05930c3283461b61e403f52`.

## План calibration до запуска

Новый output `calibration_v6_fear_unique_centers_20260926.mp4[.result]`.
Тот же известный coconut SHA
`ac54e7a8a6ede438cd08e0ff0b6c5cce6fc556af62ecace9747b142bbb5190cb`;
оригинальная музыка SHA
`2fc09a0cb163ad9eb6cdda3095fcf103ab4aab7b9dc52ec3a3e5564b085bce37`.
Before install сохранить отдельный source/resource/test/APK baseline.
После terminal result сверить runtime/input/music identity, cache version,
known/unknown counts, run и timing; не считать совпадение/различие результатов
само по себе визуальной приёмкой. Новый экспорт потребует actual AAC и новую
human form. Повторный отказ — незакрытый положительный challenge, не negative
pass. Исправление dedup не является доказанным исправлением matching реальной
сцены. Source уже раскрыт, новой независимости не появляется.

## Результат полного запуска

Baseline `artifacts/quality/baseline-v6-unique-centers-20260926.tar`, SHA
`48b71284bdb42db471ba36d7445024299e33a900fdafbf14993070ed46c96c74`.
PID 15296 / TID 15316 наблюдался живым. В 13:45 UTC появился terminal
result, поток затем отсутствует. Runtime APK/API/model и source/music
SHA соответствуют плану. Экспорт снова не создан;
`insufficient_motion_evidence`, 0 measured / 121 unknown.

121 observations / semantic frames / masks, 277 model successes,
37733 мс для одной попытки. Это не performance benchmark: один запуск
не доказывает причинность разницы с прежними 31812 мс.
V7 cache SHA
`7e6fdc860d6da16319254b529b92295710821ea860ee0916048b07c5b60120a4`;
диагностика `calibration_v6_fear_motion_v7_diagnostic_20260926.json`.
362 Android tests и 65 Python quality tests прошли.

Дедупликация не разрешила положительный motion challenge. Для подтверждения
фактических unique-center counters после изменения запускается read-only
motion replay `calibration_v6_motion_stage_probe_v7_20260926.json[.result]`
на том же APK и этом v7 cache. Это probe-only, без нового semantic inference
или экспорта; stage counts и agreement с новым cache проверяются отдельно.

## Проверка счётчиков на устройстве

Probe PID 15296 / TID 15413 наблюдался живым. В 13:47 UTC финальный marker
получен, поток затем отсутствует. APK/source/cache/API/model совпали с
планом; 121/121 replay measurements совпали с production cache.
Во всех строках, достигших matcher, `cells == unique_centers == 160`.
Таким образом, duplicate-center defect исправлен и в фактическом path,
а не только на synthetic fixture.

Этапы неизвестности после dedup: FIRST_FRAME 1, MASK_UNAVAILABLE 42,
HUMAN_UNAVAILABLE 9, CAMERA_UNSUPPORTED 66, PERSON_COVERAGE 3.
Без dedup было 65 camera failures и 1 subject unsupported: removal duplicate
weights меняет доступность camera consensus у одного наблюдения, но не
создаёт ни одного достаточного измерения движения. Все 121 остаются unknown.

Следующая проверяемая гипотеза — integer-pixel matching при дробном движении
камеры и деформации фигуры. Нужны независимые синтетические кадры с известным
subpixel translation/lighting/noise, затем проверка model correspondence,
а не подгонка fit threshold под этот танцевальный ролик. Гипотеза пока не
подтверждена как причина coconut refusal. Контактный лист источника показывает
движущихся людей, но не предоставляет dense ground-truth flow или точную
межкадровую скорость камеры. FEAR всё ещё не принят; release matrix и
приёмка остальных стилей остаются незавершёнными.
