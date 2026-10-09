# Motion stage probe: известный coconut, без изменения порогов

2026-09-26. План до запуска. Предыдущий полноценный FEAR run v4 завершился
`insufficient_motion_evidence`, 0 measured / 121 unknown. Маска+human проходят
минимум в 70 samples; реальные PTS gaps допустимы. Причина ниже этих этапов
не была измерена и не должна угадываться.

Добавлен `PersonMotionEvidence.assess`: production `observe` и `measure`
делегируют тому же pixel path, что диагностический audit. Matching/camera/
human/mask/coverage thresholds и сетка **не менялись**. В `Cell` добавлены
диагностические texture/fit/uniqueness/roundtrip поля, без изменения старой
confidence формулы. Кэш остаётся v6: формат и смысл результата не меняются.

`MotionEvidenceProbe` существует только в debug APK. Требует существующий
SHA-keyed production cache; заново декодирует реальные кадры и требует
полного совпадения PTS последовательности с cache observations. Использует
тот же `MediaFrameVisualAnalyzer.motionPlane` (48×72 Bitmap scaling/luma),
маску по **точному** attachment PTS и cached human confidence. Не выполняет
новую semantic inference, не интерполирует/переносит маски, не вызывает
director и не создаёт MP4. Выдаёт stage, cell counters, unique centers,
texture/fit/uniqueness/roundtrip support, background quadrants/person cells
и совпадение нового replay measurement с cached measurement.

Это объяснение поведения существующего анализа, не новая evidence качества
масок, независимая приёмка или доказательство статичности танцоров.
Если fresh decode/cached labels приводят к несовпадению, оно сохраняется
в отчёте; причину production refusal нельзя объявлять объяснённой таким replay.

359 Android unit tests / 0 failures+errors; APK собран.
Новые тесты проверяют stage first-frame/stale semantics/gap/mask/human/
camera, неизменность measured-static result и support counters.
APK SHA `930c07172a62a844c82c0224b4ce14cd4d77eed4300dd2268eb89c11d1e568a1`.

Источник SHA
`ac54e7a8a6ede438cd08e0ff0b6c5cce6fc556af62ecace9747b142bbb5190cb`,
expected cache SHA
`cec3ffadb0a78bb1b3f47d4288ba989bdf8e4c53fac7c454c1840a96fe71de5d`.
Новый probe output `calibration_v5_motion_stage_probe_20260926.json[.result]`.
Source/music на устройстве проверены, активный golden thread не обнаружен.
Перед установкой сохранить отдельный debug-probe baseline, не менять v4.
После завершения сверить runtime/source/cache SHA и stage counts.

Известный clamp риск остаётся до измерения: повторяющиеся физические центры
нельзя считать независимыми patch witnesses. Диагностика специально сначала
повторяет старую сетку; исправление сетки будет отдельным изменением с новой
версией cache, чтобы не смешать объяснение старого run с новым алгоритмом.

## Завершённый replay

Probe baseline `artifacts/quality/baseline-v5-motion-probe-20260926.tar`, SHA
`b04839d4730f4b24ac84046cac3715e4a6a8dd8232e7ab67e70d6bbfe53d3a08`.
Поток PID 15196 / TID 15216 наблюдался живым; в 13:41 UTC финальный
probe marker получен и поток отсутствует. APK/source/cache/API/model
в JSON совпали с планом. Probe JSON SHA
`7428c92725ff2b9b14fd01fccaa562e45f18e82c61037a35ff0b97f0c91424a8`.
Все 121 replay measurements совпали с cached measurements (все `null`).

Причины первого не пройденного этапа:

| Этап | Наблюдения |
| --- | ---: |
| Первый кадр без предыдущего | 1 |
| Маска недоступна/недостаточно уверенная | 42 |
| Независимое human evidence недостаточно | 9 |
| Camera consensus по фону не поддержан | 65 |
| Нет поддержанного движения героя при поддержанной камере | 1 |
| Поддержка героя ниже 4 cells / 25% mass | 3 |

Во всех 65 camera failures: 216 cells, но только 160 unique centers.
Texture support min/median/max 197/214/216; двусторонний fit >=0.5
0/3/21; uniqueness >=0.5 46/83/140; roundtrip 107/146/203.
Combined reliable cells 0/0/14, reliable background 0/0/12,
background quadrants 0/0/3, reliable person cells 0/0/2.

Таким образом, по общим счётчикам кадра texture поддержана значительно чаще,
чем photometric matching. Отдельные texture/fit счётчики только для фона
здесь не записаны: недостаток текстуры именно фона этим ещё не исключён.
Низкая общая поддержка photometric matching — измеренное ограничение,
но её вклад в каждый конкретный camera failure не выделен отдельно.
Из этих счётчиков нельзя определить, вызвано ли несовпадение слишком большим
перемещением за 233/267 мс, subpixel/camera motion, deformation, compression
или изменением освещения. Причину физического mismatch ещё надо проверить.
Порог fit не снижается ради coconut. Duplicate-center defect подтверждён
самостоятельно, но не объясняет все unknown и не доказывает исправление
реального движения после dedup.

Дальнейшее исправление уникальных центров и cache v7 описывается отдельно;
этот JSON остаётся свидетельством старого v6 path, не нового алгоритма.
