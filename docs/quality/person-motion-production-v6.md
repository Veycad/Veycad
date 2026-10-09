# Движение человека: подключение correspondence к анализу и FEAR

2026-09-26. Промежуточное исправление измерения, не приёмка FEAR или релиза.

Эта запись фиксирует этап v6. Последующая диагностика —
`motion-stage-probe-v6.md`, исправление повторных центров/cache v7 —
`unique-motion-centers-v7.md`. Старые результаты ниже не переименовываются
в результаты нового алгоритма.

## Что изменено

`PersonMotionEvidence.observe` подключён к `MediaFrameVisualAnalyzer`.
Каждый анализируемый кадр получает новую segmentation/face/pose попытку.
Маска и human confidence берутся только из текущего semantic result;
перенос последней маски или последнего human label не допускается для
этого измерения. Первое наблюдение и реальный PTS gap вне 100–500 мс
дают `null`. Ошибка семантики также даёт `null`, не ноль.

Local block matching проверяет texture, uniqueness и forward/backward
consistency. Camera translation подтверждается только фоном вне person
mask: минимум 8 согласованных ячеек, 70% confidence mass и 3 квадранта.
Subject intensity — среднее модулей camera-compensated displacement
до усреднения направлений. Для производственного наблюдения требуется
минимум 4 person cells и 25% person mass. Эти инженерные требования
не являются статистически откалиброванными вероятностями.

`VisualEventMap.MotionMeasurement` хранит интенсивность, camera translation,
confidence, counts/coverage и реальный intervalUs отдельно от старых
cameraMotion/subjectMotion vectors. Кэш v6 сериализует измерение и `null`.
Кэши v4/v5 не используются как новая evidence и не удаляются ради прохождения
проверки. Старые vectors сохраняются для существующих других consumers;
их корректность как направления скорости здесь не доказана.

FEAR opener gate использует только новое измерение: threshold 0.18,
3 consecutive supported moving samples, span минимум 500000 мкс,
gap максимум 500000 мкс. Unknown разрывает run. Отсутствие трёх
измеренных samples в любом opener window даёт отдельный
`insufficient_motion_evidence`, а не заявление о неподвижности героя.
UI и debug report различают неизвестность и измеренное отсутствие run.
Release matrix принимает новый typed code, но он не заменяет обязательный
negative case `insufficient_motion` на действительно неподвижном материале.

## Единицы и пределы

Новая интенсивность — displacement / search radius 3 pixels на luma plane
48×72 за фактический интервал кадра. Это **не скорость** и не старый luma
moment. Число 0.18 оставлено без подгонки под coconut, но равенство чисел
не доказывает эквивалентность старого и нового порогов. Interval сохраняется
для аудита; устойчивость к разному cadence ещё требует отдельной проверки.

Camera model — только translation. Rotation/zoom/parallax, слишком быстрое
движение, слабо текстурированные люди, почти полный person mask или
недоступная pose/face могут оставить движение неизвестным. Failure/unknown
не является доказательством отсутствия физического движения.

Семантическая частота повышена с каждого второго до каждого наблюдения;
pose теперь также запускается на каждом кадре. Это меняет стоимость анализа
и mask timeline у всех продуктов; нужны device timing и регрессии остальных
стилей. FEAR shot ranking/cascade и события других стилей пока продолжают
использовать часть старых vectors: исправление всех motion consumers не
завершено. Нельзя считать одно исправление opener полноценным исправлением
грамматики или качества FEAR.

## Проверка до device run

357 Android unit tests, failures/errors 0; debug APK собран.
65 Python quality-tool tests прошли. Тесты проверяют старые vectors без
measurement, различие unknown/static, stale semantics, недопустимый PTS gap,
малую поддержку, cache round-trip measured/null и запрет чтения v5.
Pixel-level opposed-motion fixture проходит путь matcher → mask → background
camera → observation → FEAR gate без direction vector. Это синтетическая
проверка, не заключение о реальных танцорах или визуальном качестве монтажа.

APK SHA-256:
`b4fc1ab0c590527f7acf763f90adab60244e1e240f043eb8fbcf40727ba61be3`.

## План calibration до запуска

Тот же раскрытый coconut, source SHA
`ac54e7a8a6ede438cd08e0ff0b6c5cce6fc556af62ecace9747b142bbb5190cb`.
Оригинальная музыка FEAR SHA
`2fc09a0cb163ad9eb6cdda3095fcf103ab4aab7b9dc52ec3a3e5564b085bce37`.
Новый выход `calibration_v4_fear_correspondence_20260926.mp4[.result]`.
До установки/запуска сохраняется новый source/resource/test/APK baseline;
source/music на устройстве уже сверены. Проверить отсутствие активного
render thread и runtime APK/API/model. Не перезаписывать прежние экспорты.

После terminal result извлечь v6 observations, known/unknown counts,
matching coverage и реальные intervals, сравнить новый gate с прежним
8 samples / longest run 2 на v5. Если материал снова отклонён, это остаётся
ошибкой/ограничением на положительном challenge, не успешным negative test.
Если экспорт появится, требуются actual AAC и новая hash-bound human form.
Этот известный источник не становится новым независимым holdout/родителем.

## Фактический device результат

Baseline сохранён до установки:
`artifacts/quality/baseline-v4-correspondence-20260926.tar`, SHA
`b37f9069f4abae7a4f3522a1e7b4abd677e2d67ac5abf36a8bd0e2a03d9843ed`.
В нём source/resources, build configuration, quality docs/tools,
357 XML tests и фактический APK. Это calibration snapshot, не новая
фиксация независимого корпуса. Исходные v1/v2/v3 архивы не изменены.

На API 36 / `Android SDK built for x86_64` установленный base.apk SHA
совпал с планом. Поток PID 15050 / TID 15072 наблюдался живым;
финальный result получен в 13:33 UTC, поток затем отсутствует.
Source/music/runtime identity в `.result` совпадают с планом.
MP4 не появился; `material_rejected / insufficient_motion_evidence`.

Анализ: 31812 мс, 121 observations, 121 semantic frames, 121 masks,
277 model successes. V5 ранее занимал 24990 мс / 61 semantic frame.
Новый v6 cache SHA
`cec3ffadb0a78bb1b3f47d4288ba989bdf8e4c53fac7c454c1840a96fe71de5d`.
Read-only report:
`artifacts/quality/runs/calibration_v4_fear_motion_v6_diagnostic_20260926.json`.

Все 121 motion observations — unknown; measured/moving/run/span — 0.
Первое unknown ожидаемо, но отсутствие измерений во всём ролике
остаётся незакрытым ограничением, а не успешной приёмкой отрицательного
материала. Legacy peaks не изменились: camera 0.5910924673,
subject 0.2256349825; они больше не предоставляют fallback для FEAR.

79 масок имеют confidence >=0.8, 104 наблюдения human confidence >=0.55;
70 одновременно проходят оба условия. Значит, объяснять все unknown только
отсутствием маски/человека нельзя. Реальные gaps: 60×267000 и 60×233000 мкс,
все в допустимом диапазоне. Точное место потери поддержки (patch match,
background consensus или subject coverage) пока не установлено:
текущий cache хранит итоговое measurement/null, не этап причины отказа.

Следующий шаг — аудит по этапам на тех же фактических кадрах/масках,
без снижения порогов под этот источник. Дополнительно при инспекции кода
обнаружено, что grid coordinates clamp у края может дублировать центры
patches: счётчики поддержки нужно проверить на уникальные физические
ячейки перед дальнейшей калибровкой. Само это наблюдение не объясняет
121 unknown и не является доказанной причиной отказа coconut.

FEAR на реальном движении ещё не исправлен/принят. Остальные стили требуют
регрессий после изменения semantic cadence; общая независимая серия,
визуальная приёмка новых DUALITY и release matrix остаются незавершёнными.
