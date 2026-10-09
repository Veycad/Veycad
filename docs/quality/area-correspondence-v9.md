# Area-подготовка correspondence pixels / cache v9

2026-09-26. Исправление pixel preprocessing, не приёмка FEAR/релиза.

## Контрпример и contract tests

Независимый synthetic source 512×512 задаёт непрерывную texture с low- и
high-frequency деталями. Известное source displacement (4,-2) после 8×
уменьшения соответствует (0.5,-0.25). Center-aligned bilinear point model
даёт 4 reliable matches, **0 accurate**. Exact area integration даёт
140 reliable и 140 accurate, с неизменным subpixel matcher и thresholds.
Это модель point sampling, не заявление о bit-exact Android Bitmap output
или доказанная причина отказа coconut.

`LumaAreaResampler` вычисляет separable weighted overlap source pixels с
footprint каждого target pixel. Source pixels трактуются как piecewise
constant. Fractional ratios и anisotropic dimensions поддержаны; это box
area filter, не идеальный band-limit/полное устранение всех aliases.
Проверены identity, constant up/down/anisotropic resize, checkerboard area
mean и сохранение global mean при fractional footprint. End-to-end test
area resize → subpixel match → real proxy mask → background camera → subject
scalar сохраняет истинное camera displacement и subject intensity=0.

## Подключение

`MediaFrameVisualAnalyzer.correspondencePlane` извлекает luma полного provider
bitmap и area-resize до тех же 48×72. Только новое explicit measurement
использует этот plane и отдельный previousCorrespondence. Legacy luma moment
и blur-flow продолжают использовать прежний `motionPlane`; их pixel preprocessing
не менялся. Debug stage replay использует тот же correspondencePlane, что
production, не другой filter. Метод `person-background-area-subpixel-v3`,
cache v9. V8 cache без area preprocessing не читается как новая evidence;
формат observation fields не меняется. Старые экспорты не перезаписываются.

Thresholds mask/human/matching/background/person/opener не снижались.
Area integration может снизить texture contrast и оставить больше unknown:
это не повод подменять отсутствие support измеренным нулём или lowered gate.
Деформация, parallax, rotation, cadence, fixed aspect ratio и качество масок
этим автоматически не исправляются. Provider ранее уже GLES-масштабирует
source до max side 480: потерю исходной информации downstream filter
не восстанавливает. Пригодность именно этого preprocessing для разных
реальных источников и устройств ещё не доказана.

## Проверено до device run

376 Android tests / failures+errors 0, APK собран; 65 Python quality tests
прошли. APK SHA
`0be470ec5823f201114c90276e7253dd92fcb718c3f01253a39aeca3ca258fa8`.
После прерывания ADB не видел emulator. Сохранённые terminal v8 reports
проверены, прежние runs не перезапускались. Доступный Fear_API_36 запущен
скрыто с cold boot, без snapshot load/save. sys.boot_completed=1, source/music
SHA соответствуют прежним; это не сброс userdata. Cold boot необходимо
учитывать при сравнении длительности с тёплыми предыдущими запусками.

## План calibration до запуска

Before install сохранить отдельный source/resources/tests/APK baseline.
Новый output `calibration_v8_fear_area_20260926.mp4[.result]`.
Тот же раскрытый source SHA
`ac54e7a8a6ede438cd08e0ff0b6c5cce6fc556af62ecace9747b142bbb5190cb`;
оригинальная музыка SHA
`2fc09a0cb163ad9eb6cdda3095fcf103ab4aab7b9dc52ec3a3e5564b085bce37`.
Сверить отсутствие active golden thread и runtime APK/API/model. После
terminal result извлечь v9 known/unknown counts, run/span/intervals, timing
и stage support при необходимости. Если будет MP4, нужны encoded gates,
actual AAC и новая output-hash-bound human form. Не приравнивать motion
support к приёмке грамматики или product quality. Если отказ — незакрытый
positive challenge, не negative pass. Независимый corpus и человеческая
приёмка всех четырёх продуктов остаются незавершёнными.

## Device результат

Baseline `artifacts/quality/baseline-v8-area-20260926.tar`, SHA
`5afa668fbe0ffb9b1a60ea392d04483449ff5aef824ed02a797470134cd976aa`.
PID 2029 / TID 2049 наблюдался живым. В 14:14 UTC получен terminal result;
поток затем отсутствует. APK/API/model/source/music SHA совпали с планом.
Экспорт не создан, `insufficient_motion_evidence`. 121 observations/semantic
frames/masks, 277 model successes, 41732 мс для одного cold-boot run.
Не считать одну длительность performance benchmark или доказательством,
что area filter ускоряет приложение на всех устройствах.

V9 cache SHA
`28008793d3d2fad71cd0921b924a0fd4d6fd71b4e21ff80b2c6746c827951bf5`.
Read-only report `calibration_v8_fear_motion_v9_diagnostic_20260926.json`.
1 measured / 120 unknown, moving/run/span=0. Единственное measurement:
PTS 25900000 мкс, gap 267000; subject intensity 0.0519553572,
camera intensity 0.0361460087, camera confidence 0.6512531,
9 person cells / 0.2802343 supported person mass,
33 camera cells / 4 quadrants. Оба intensity ниже 0.18; это измеренный
слабый displacement, не доказательство полной статичности сцены.

По сравнению с прежними 0 measured появился один поддержанный observation,
но ни одного moving run. Pixel-level aliasing counterexample исправлен,
а пригодность FEAR на данном реальном positive challenge не исправлена.
Для остальных unknown точное место потери support после area filter требует
отдельного stage replay; нельзя автоматически переносить причины v8.
Нужна проверка разрешения/texture и coverage героя, допустимой camera model
и mask geometry по фактическим данным. Thresholds не подгоняются по этому
источнику; повторные calibration runs не создают независимые holdout parents.

## План stage replay после полного запуска

На том же установленном APK, SHA-keyed v9 cache и source выполняется
`calibration_v8_motion_stage_probe_v9_20260926.json[.result]`.
Проверка заново декодирует точные source PTS, использует текущие cached masks/
human labels и production correspondencePlane. Без semantic recomputation,
director/MP4. Требуются replay/cache agreement, stage counts и cell coverage.
Не переносить автоматически stage причины прежнего point-resize v8.

## Stage replay завершён

Terminal marker получен в 14:19 UTC; golden thread после него отсутствует.
JSON SHA `fad7b495f52583f4b3babf7b630b8ceb0291d89f48e747a8b9011b4cc0096647`.
Runtime/source/cache identity соответствует плану, 121/121 replay/cache
measurements совпали. Stage counts: FIRST_FRAME 1, MASK_UNAVAILABLE 42,
HUMAN_UNAVAILABLE 9, CAMERA_UNSUPPORTED 48, SUBJECT_UNSUPPORTED 9,
PERSON_COVERAGE 11, MEASURED 1. В v8 было 65 camera failures: area preprocessing
улучшил доступность background matching, но support самого человека всё ещё
недостаточен в 20 кадрах с подтверждённой камерой.

На 69 строках после mask/human gates общие min/median/max: texture
135/153/157, fitted 4/19/83, reliable 0/7/64, reliable background 0/6/46,
background quadrants 0/2/4, reliable person cells 0/0/10. Это не отдельные
texture/fit счётчики только фона и не dense ground truth физического движения.
Следующая отдельная проверка — pixel-center projection между 48×72 pixels
и full-frame probability mask другого размера, без подгонки gates по coconut.
