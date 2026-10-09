# Heartbeat — авторский референс Леонида

> Историческая карточка раннего эксперимента от 2026-09-07. Heartbeat теперь
> доступен в продуктовом каталоге; отметка о debug-флаге ниже относится только
> к описанному здесь прототипу.

Источник: `/Users/l-v-polyakov/Downloads/ssstik.io_1788734312850.mp4`.
Леонид 2026-09-07 заявил авторство монтажа, права на музыку и разрешил её
использование. Обработка и материалы остаются локальными. Кадры референса не
добавлять в APK; пользовательский результат должен использовать его исходник.

## Проверенные свойства и границы анализа

AVFoundation: 21.167 s, 1080×1080, 60 fps, identity display transform, одна
аудиодорожка. Локальные контактные листы: `artifacts/reference-analysis/heartbeat/`.
Первый обзор — кадры через 500 ms на всей длительности, НЕ просмотр движения
и НЕ точное измерение битов/скоростных кривых. Уверенность в последовательности
сцен высокая, в механике переходов пока средняя/низкая.

## Наблюдаемая монтажная структура

- 0–2.5 s: тёмный портрет, затем более светлая сцена; небольшой центральный текст.
- 3–11.5 s: серия разных планов, жестов и освещения; светлые акценты видны,
  например, около 7.5 s. Точные границы предстоит определить на кадрах 60 fps.
- 12–19.5 s: возвращаются знакомые планы; видны пространственно смещённые
  двойные изображения. Гипотеза: temporal echo и трансформации, а не новая маска.
- На выборках 20–21 s — чёрный финал; точное начало затемнения не измерено.
- Палитра меняется от холодной тёмной к тёплой/светлой. Не приписывать всё LUT:
  большая часть различия может быть освещением исходных сцен.

## Материал и ограничения

Референс использует множество разных сцен, жестов, ракурсов и крупностей.
Один неподвижный селфи-исходник не содержит такого покрытия. Виртуальный zoom
не считать эквивалентом новой сцены. Проверять повторяемость и качество на трёх
исходниках отдельно, явно указывать ограничения материала.

## Отдельный рецепт HEARTBEAT_V1

### Измерение всех кадров, 2026-09-07

`tools/measure_reference.swift` последовательно декодировал 1270 кадров.
Сохранены реальные PTS, яркость и межкадровые различия в `video-frames.tsv`;
аудиоэнергия на фиксированной сетке 10 ms — `audio-10ms.tsv`. Энергия ещё
не является beat map. Число decoder buffers не является числом аудиособытий.

Обнаружены 26 коротких белых/тёмных импульсов (порог средней яркости
>0.98 / <0.04), отдельно от чёрного хвоста. Часть независимо проверена
AVAssetImageGenerator: белый кадр 2.700 s, тёмные 3.033–3.050 s и
4.800–4.817 s, белые 19.767–19.783 s. Финальный чёрный хвост начинается
19.800 s и длится до конца. Таблица находится в HeartbeatMontageProfile,
пока не подключена к production director.

Важное исправление первоначального предположения: это не только плавный монтаж
с длительными световыми акцентами. Есть импульсы длиной 1–2 кадра при 60 fps;
выход 30 fps может потерять часть из них. Частоту вывода и ограничение частых
вспышек нужно решить явно, а не считать пропущенные импульсы совпадением.
Кадр 3.733 s показывает размытую трансформацию, поэтому высокий frame-delta
не означает автоматически новую сцену. Разметка склеек/скорости/эхо остаётся
незавершённой. Данные не доказывают совпадение с музыкой или качество рендера.

### Проверка пар сцен

Сохранены и просмотрены пары вокруг кандидатов в `heartbeat/cut-pairs/`.
Подтверждается последовательность 26 монтажных слотов: 15 в первом проходе,
затем 11 повторных. Повтор с 11.750 s соответствует ролям 4–14 первого
прохода (снятие рубашки → действие за столом → тёмная сцена → футболка →
улица → защитный экран → раскрытая рубашка → лестница → сумка → синяя
рубашка → светлый портрет). Это соответствие сцен, не доказательство совпадения
внутрикадровых source PTS. Во второй фразе видны смещённые двойные изображения.

HeartbeatMontageProfile теперь содержит сцены, роли повторов и echo-флаг.
Границы пока ориентировочные: ±33.333 ms у размытых переходов; первые две
скрыты белым импульсом, неопределённость отмечена отдельно. Таблица не должна
выдаваться за завершённую точную разметку. Тесты проверяют непрерывность,
повтор ролей и отдельный чёрный хвост. Производственный рендер ещё не подключён.

Не переименовывать Sigma/DYNAMIC в Heartbeat. Каталог уже хранит стабильный ID
`heartbeat`; стиль пока недоступен для рендера, чтобы не выдавать старый рецепт.

Порядок реализации:
1. Извлечь разрешённую аудиодорожку локально; измерить onset/beat и музыкальные
   фразы, сопоставить с точными границами кадров. Не угадывать BPM по названию.
2. Сформировать отдельную таблицу сцен, акцентов, повторов и финала; определить
   правила выбора исходных фрагментов, не переносить запрет повторов из Sigma.
3. Подключить отдельный профиль и музыку через UI → request → director → GLES.
   Применять существующие zoom/blur/echo только по измеренной структуре.
4. Получить реальный MP4 на эмуляторе. Сравнить с исходником и референсом,
   включая темп, переходы, эхо, цвет, финал и A/V. Только затем включить стиль.

## Приёмка

- Рецепт и музыка Heartbeat не изменяют Sigma; отдельные тесты маршрутизации.
- Фактические склейки и акценты MP4 соответствуют измеренной таблице с
  допуском одного выходного кадра, а не просто существуют в графе.
- Нет выпадения аудио, случайных чёрных кадров, обрезания или переворота.
- Ревью переходов на 1× и 0.5× плюс точные кадры до/внутри/после перехода.
- Три пользовательских MP4 и отчёты сохраняются локально; fixtures вне APK.
- Равный уровень качества пока НЕ достигнут и не измерен процентом.

### Уточнение сцены 6.167 s — 2026-09-09

Точное синхронное декодирование кадров `6.150/6.200/6.400/6.700 s` исправило
предыдущую интерпретацию контактного листа. После склейки референс показывает
полную живую сцену: размытая фигура, яркий контровой свет и видимый фон, затем
быстрое восстановление резкости. Это не доказательство alpha-mask и не сцена
персонажа на чёрном фоне. Поэтому Sigma `FOREGROUND_REENTRY` здесь запрещён;
Heartbeat использует hard cut и короткий defocus полного кадра.

Проверка на `veypad-test-2.mp4` также выявила, что прежний граф назначал этому
слоту тот же pool role 2, что следующему световому акценту. Это уничтожало
смену композиции. Актуальный рецепт сохраняет собственный role 8 в первом
проходе и репризе; role 2 остаётся только для двух ярких портретов. Новый
device-render изменил ровно клипы 8 и 19 и снизил decoded face-loss с 10.23%
до 9.33%.

## Первый сквозной эксперимент

Добавлен `HeartbeatDirector`: 26 слотов плюс чёрный хвост; 15 выбранных
существующим директором исходных окон, повтор ролей 4–14, отдельные импульсы,
11 echo-окон. Недостаток выбранных окон вызывает явную ошибку, а не скрытые
одинаковые crops. Это ещё не оценка реального разнообразия материала.
Sigma transition/transform/speed envelopes не используются в новом графе.
Временная скорость пока постоянная; blur, измеренные velocity-кривые, текст,
grade и музыкальная приёмка ещё не реализованы. Echo offset -67 ms/opacity .45
— параметры эксперимента, НЕ измеренные свойства оригинального проекта.

Запуск доступен только debug-флагом `heartbeat=true`; продуктовый стиль пока
остаётся недоступным. Выход 60 fps. Прямоугольная огибающая применяется только
к `heartbeat-measured-step`, огибающие Sigma не изменены.
Тесты проверяют длину, source-role повторы, отсутствие Sigma re-entry,
недостаточную coverage, импульс с первого кадра и сплошной чёрный хвост.
`testDebugUnitTest lintDebug assembleDebug` успешно завершены.

В эмуляторе запущен `heartbeat-first-g0-0907` на `veypad-test-0.mp4` с локальной
`heartbeat-author.m4a`. PID при проверке 13554, маркера завершения ещё нет.
Не считать наличие графа или старт Activity доказательством готового MP4.

## Opening and finale calibration — 2026-09-08

Synchronized local review measured the author reference at output 0.273–0.318 s near
`luma=0.087–0.090`; the previous Veycad opening decoded near `0.392`. An opening-only
exposure bias of `-0.72` overshot to `0.033` and was rejected. The calibrated `-0.61`
render decodes at `0.0867` while the following 3.733 s scene remains visually unchanged.
This is accepted as a scoped visual improvement, not a global colour-grade claim.

The previous finale ended on the dog/couch role at 18.900 s (`luma=0.2455`). The reference
returns to its human hero and measures `0.7033` there, holding near `0.6673` at 19.500 s
before the 19.800 s black tail. `heartbeat-directed-finale-0908` now returns to verified
portrait role 4 and applies an authored exposure build; decoded output measures `0.6729`
at 18.900 s and `0.6806` at 19.500 s. A/B/C review shows a substantially clearer closing
motif than the discarded animal ending, and playback reaches the black tail without dropped
frames or browser decode errors. Animals remain in their middle and reprise montage roles.

The MP4 still fails the conservative generic acceptance gate for unrelated known reasons;
these two checkpoint matches do not establish full-reference parity.

## Lead/animal role rhythm — 2026-09-08

The Samsung source pool placed five couch/animal views in authored roles 10–14, producing
long visually similar runs in both phrases. `heartbeat-role-rhythm-0908` retains animal roles
10, 12 and 14, but maps roles 11 and 13 to the already-selected clean gesture/portrait motifs
3 and 4. The same mapping is applied in the reprise, preserving the reference's repeated-phrase
grammar rather than inventing different second-half timing.

Compared with `heartbeat-faceecho70-0908`, inspector clocks change only in the four intended
output windows: 8.30–8.70, 9.633–10.65, 16.317–16.717 and 17.65–18.667 s. At 10.133 s decoded
luma moves from `0.3334` to `0.3662`, toward the reference checkpoint `0.5920`. Face-loss falls
from `0.19831` to `0.15385`. Full browser playback to 20.45 s reports zero dropped frames and
no decode errors. The generic grammar score falls from `0.7581` to `0.7301` because its repeated-
source penalty does not model this author's deliberate motif reprise; retain the visually clearer
role rhythm and do not reinterpret the generic score as reference parity.

## Opening title punctuation — 2026-09-08

The decoded reference visibly spells `HEART` → `HEARTBEAT` → `MY` → `MY HEARTBEAT`
through its opening 2.683 s. The first two switch points remain midpoint estimates inside the
available 500 ms samples; the last is constrained by the independently decoded 1.733 s cut pair.
They are therefore stored as authored timing hypotheses, not claimed lyric/beat measurements.

`heartbeat-title-overlay-0908` adds only this Heartbeat-scoped sequence through one antialiased
alpha atlas sampled by the existing GLES compositor. Its A/B inspector comparison against
`heartbeat-role-rhythm-0908` keeps all 1,271 primary and secondary decoded source PTS identical.
The rendered contact sheet shows the small centred title at 0.00, 1.10 and 2.22 s and no title
after the 2.683 s opening. Container/runtime evidence remains healthy: 1,271 frames, AAC present,
26/26 measured pulses, and 13,288 us A/V drift. This closes a visible opening omission; it does
not establish full-reference parity or repair the remaining source-coverage gap.
