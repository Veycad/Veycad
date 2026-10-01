# Дробное движение: pixel counterexample и correspondence v8

2026-09-26. Изменение matching model, не релизная приёмка FEAR.

## Контрпример до изменения

Сначала запущены два diagnostic unit tests на прежнем integer-only v7
matcher. Независимая аналитическая texture 64×64:

`0.5 + 0.16 sin(0.83x+0.41y) + 0.12 cos(1.21x-0.67y) + 0.08 sin(0.27x+1.47y+0.9)`.

Кадр после движения вычисляется из той же непрерывной функции в координатах
`x-dx, y-dy`, поэтому истинное displacement задано независимо от matcher.
Пара dx=0.5, dy=-0.25: 140 unique cells, reliable=0, accurate=0,
fit>=0.5 у 0 cells, uniqueness>=0.5 у 2. Статичная контрольная пара той же
texture: все 140 reliable, displacement=0. Значит, недостаток texture или
отсутствие реального движения не объясняют именно этот synthetic отказ.
Это не доказывает, что именно subpixel motion объясняет coconut refusal.

## Изменение

Matcher перебирает **все** quarter-pixel displacements внутри прежнего
search radius ±3: 25×25 hypotheses вместо 7×7 integer positions.
Предварительно вычисляется bilinear quarter-plane для повторного использования
в patches. Candidate/query значения и forward/backward путь используют один
sampler; дискретное roundtrip tolerance остаётся 1 исходный analysis pixel.

Нет выборочного refinement только лучших integer peaks: не проверенная
конкурирующая дробная гипотеза не должна создавать ложную uniqueness.
Exact duplicate centers по-прежнему удаляются до matching.

Uniqueness теперь сравнивает best hypothesis с ближайшим конкурентом из
другого displacement basin: Chebyshev distance минимум 1 analysis pixel.
Соседние quarter-step позиции одного пика не объявляются разными движениями.
Все прежние integer hypotheses входят в полную сетку. Fit divisor 0.05,
uniqueness margin divisor 0.02, texture 0.02 и threshold confidence 0.5
численно не снижались; **модель и смысл uniqueness изменились**, поэтому
эквивалентность старой confidence или её probabilistic calibration не заявляется.

Person/camera requirements и opener 0.18 / 3 samples / 500000 мкс не менялись.
Новое имя метода `person-background-correspondence-subpixel-v2`, cache v8;
v7 cache read rejected, binary observation shape не меняется.
Displacement units всё ещё /3 analysis pixels за actual interval, не скорость.

## Проверки до device run

369 Android unit tests, errors/failures 0; debug APK собран.
Тот же half-pixel synthetic fixture после изменения: 140 reliable и 140
accurate cells. Проверены fractional displacement в обоих направлениях/осях
(0.25,-0.5), (0.75,0.5), (-1.25,0.25), (2.5,-0.75), static/lighting-only,
независимый camera consensus с компенсацией до subject intensity=0,
периодическая fractional texture как unknown. Старые noise/flat flash/
integer translation/opposed-motion/unknown semantics/cache tests также прошли.

Это synthetic contract evidence, не dense ground truth реального видео.
Для arbitrary translation вне quarter-grid precision, deformation,
rotation/zoom/parallax и illumination mismatch результат может быть unknown.
Время и память полного quarter-search на устройстве ещё измеряются.

## План calibration до запуска

APK SHA `dea963634c63868814be4eea82cc0b8e95463d8fd77aeb02243b30d0d93e01af`.
Новый source/resource/test/APK baseline сохранить до установки.
Новый output `calibration_v7_fear_subpixel_20260926.mp4[.result]`.
Тот же раскрытый coconut SHA
`ac54e7a8a6ede438cd08e0ff0b6c5cce6fc556af62ecace9747b142bbb5190cb`;
оригинальная музыка SHA
`2fc09a0cb163ad9eb6cdda3095fcf103ab4aab7b9dc52ec3a3e5564b085bce37`.
Проверить отсутствие другого render thread, source/music и runtime identity.
После terminal result извлечь v8 measured/unknown/motion runs/actual intervals
и timing. Не запускать заново из-за observation timeout.
Если будет экспорт — actual AAC, собственная hash-bound human form и все
грамматические gates, не только motion. Если refusal — не отрицательный pass
на изначально положительном challenge. Известный источник не входит заново
в независимый release corpus; общая приёмка четырёх продуктов не завершена.

## Полный device run

Baseline `artifacts/quality/baseline-v7-subpixel-20260926.tar`, SHA
`e2bc2d81b4e8726aecdbe2fa4c76602e3205c0cfc947d9b0ec9e469e44a131ec`.
PID 15469 / TID 15490 подтверждался живым. В 13:58 UTC получен terminal
result, поток после этого отсутствует. APK/API/model/source/music identity
в отчёте соответствуют плану. MP4 не создан, `insufficient_motion_evidence`.
121 observations/semantic frames/masks, 277 model successes, 57667 мс.
369 Android tests и 65 Python quality-tool tests прошли.

V8 cache SHA
`2ef18c893e792061516260aef814577325799d4f739469595f9abd788d8116b9`.
0 measured / 121 unknown, moving/run/span=0. Legacy vectors peaks неизменны
и не используются как fallback. Исправление synthetic subpixel counterexample
не создало достаточных измерений на этом реальном материале. Одного device run
недостаточно для performance benchmark, но повышение стоимости требует внимания.

До завершения диагностики нельзя объявлять, что real subpixel motion был
единственной причиной отказа. Новый stage replay планируется как
`calibration_v7_motion_stage_probe_v8_20260926.json[.result]`: тот же APK,
v8 cache и exact PTS pixels; без нового semantic inference, director или MP4.
Проверить stage counts, совпадение всех replay/cached measurements и matching
support, затем выбирать следующий model change по фактам, не по pass/no-pass.

## Завершённый stage replay

PID 15469 / TID 15599 подтверждён живым; в 14:01 UTC terminal marker
получен, поток затем отсутствует. JSON SHA
`fc58042d06f4d49928fb528a19f28307d8c94f07ea35ba065d613b95a4af0e37`.
Runtime/source/cache identity совпали с планом; 121/121 replay measurements
совпали с cached measurements. Этапы: FIRST_FRAME 1, MASK_UNAVAILABLE 42,
HUMAN_UNAVAILABLE 9, CAMERA_UNSUPPORTED 65, SUBJECT_UNSUPPORTED 1,
PERSON_COVERAGE 3. Все строки, достигшие matcher, имеют 160 unique cells.

На 69 строках после mask/human gates общие min/median/max counters:
texture 141/155/159, fit>=0.5 0/4/41, uniqueness>=0.5 47/73/129,
roundtrip 81/116/153, combined reliable 0/1/26,
reliable background 0/1/20, background quadrants 0/1/4,
reliable person cells 0/0/2. Это не counters только для фона;
они не исключают недостаток texture именно в background patches.

По сравнению с v7 камера подтверждена у ещё одного observation, но ни один
не стал достаточным person measurement. Не объявлять subpixel единственной
причиной или решением real motion refusal.
Следующая гипотеза — aliasing при сильном уменьшении кадров. В коде
`motionPlane` выполняет прямой Bitmap bilinear resize из provider bitmap
до 48×72; provider ранее масштабирует видео GLES до max side 480.
Нужна независимая synthetic resampling проверка, не заявление о том,
что именно aliasing уже доказан причиной данного video refusal.
