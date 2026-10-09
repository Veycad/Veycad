# Pixel-center projection маски / cache v10

2026-09-26. Исправление геометрического sampling, не приёмка FEAR/релиза.

V9 stage replay показывает: 48 camera failures, 9 subject unsupported,
11 person coverage shortfalls и лишь 1 measured observation. Это локализует
часть недостатка support на человеке, но не доказывает единственную причину.

При инспекции выявлен отдельный geometric defect: прежний mask sampling
`floor(cellX * maskWidth / planeWidth)` использовал edge-index mapping.
Центры motion pixels и probability-mask texels в full-frame normalized FOV
должны соответствовать координате
`(cellX + 0.5) * maskWidth / planeWidth - 0.5` и аналогично Y.
При равных размерах texel identity сохраняется. При разных размерах old
mapping попадал в другую физическую точку, особенно у границы человека.

`PersonMaskProjection.sample` использует center-aligned bilinear mask values,
clamp на крайних texels. Маска считается full-frame normalized probability
plane, как и существующий renderer contract. Это не identity tracking,
semantic inference, коррекция неизвестного letterbox/crop или улучшение
истинной segmentation accuracy. Смешанный boundary не является чистым фоном.
Нормированные центры camera cells/audit quadrants также используют +0.5.

Independent synthetic controls: exact equal-resolution texel identity,
affine probability field при разном X/Y ratio, constant fractional/anisotropic
resize с clamp edges. Boundary counterexample: plane 6×6, mask 12×12,
person начинается с mask X=5, motion cell X=2. Old support=0 (ложный clean
background), centered support=0.5. Background cutoff 0.15 не изменяется:
правильная геометрия исключает такой mixed boundary из camera background.
Эти тесты не доказывают исправление всего real coconut refusal.

New method `person-background-centered-area-subpixel-v4`, cache v10.
V9 cache edge-index values не используются как новая evidence; binary format
не меняется. Thresholds confidence/coverage/foreground/background/opener
не снижались. Legacy luma/blur-flow и остальные motion consumers не заменены
новым scalar в этом изменении; полное исправление их velocity/ranking semantics
ещё не выполнено.

381 Android unit tests / failures+errors 0, APK собран.
APK SHA `1a0f6b7f33e2156fdb1f37dfbf855c84ee0e08ace36acd5016064ac5a05adf49`.

## План calibration до запуска

Before install сохранить source/resources/tests/APK snapshot.
Новый output `calibration_v9_fear_mask_center_20260926.mp4[.result]`.
Тот же раскрытый coconut source SHA
`ac54e7a8a6ede438cd08e0ff0b6c5cce6fc556af62ecace9747b142bbb5190cb`;
оригинальная музыка SHA
`2fc09a0cb163ad9eb6cdda3095fcf103ab4aab7b9dc52ec3a3e5564b085bce37`.
Проверить отсутствие другого active golden thread, source/music и runtime
identity. После terminal result извлечь v10 measured/unknown counts и runs,
не считать число измерений доказательством физической точности или приёмки.
Если экспорт появится, все encoded gates, actual AAC и новый human review
по точному output SHA остаются обязательными. Отказ на positive challenge
не превращать в negative pass; повтор не даёт нового holdout parent.

## Device результат

Baseline `artifacts/quality/baseline-v9-mask-center-20260926.tar`, SHA
`9b295b8e8ff73ffac7e1dbbd0dbfbb386f4dd143cbfb97d2e19af8696832de1c`.
В 14:24 UTC terminal result получен, golden thread после него отсутствует.
Runtime APK/API/model/source/music identity соответствует плану.
Экспорт не создан: `insufficient_motion_evidence`. Анализ 38304 мс,
121 observations/semantic frames/masks, 277 model successes.
381 Android tests и 65 Python quality tests прошли.

V10 cache SHA
`1cf647c59f7f58a4f3cf4f50c750577db314077c12a3bf8121b8e473d3a80c48`.
Read-only report `calibration_v9_fear_motion_v10_diagnostic_20260926.json`.
1 measured / 120 unknown, moving/run/span=0. Измерен тот же PTS 25900000:
subject intensity теперь 0.0453442447 (раньше 0.0519553572),
9 person cells / supported fraction 0.28738454 (раньше 0.28023434).
Camera intensity 0.0361460087, confidence 0.6512531, 33 cells / 4 quadrants
сохранились. Оба intensity меньше 0.18; source suitability не подтверждена.
Численное изменение support показывает, что geometry correction вошла в
actual runtime path, но не доказывает физическую точность каждого matching
или улучшение visual quality готового монтажа.

Pixel-center counterexample исправлен; real positive challenge по-прежнему
не закрыт. Следующая проверка — разрешение/сохранение aspect ratio для
мелких full-body/multiple-person деталей и их correspondence coverage,
с независимым known-displacement fixture и согласованными единицами.
Нельзя увеличивать/уменьшать search normalization так, чтобы тот же input
механически пересёк 0.18 без реального улучшения evidence. Также ещё не
закрыты другие motion consumers, маски/rhythm Sigma, лицо Heartbeat,
human review новой DUALITY и независимая межпродуктовая release series.
