# Под свою музыку

В режиме из галереи нажмите «Свой трек · монтаж по ритму» и выберите локальный
MP3/WAV через системный выбор документов. Это также позволяет выбрать файл,
сохранённый из Telegram, либо предоставленный совместимым файловым провайдером.
После успешного импорта включается отдельный стиль «Под свою музыку».

Ползунок «Начало» задаёт начало музыкального фрагмента с шагом 0,1 секунды.
«Прослушать фрагмент» позволяет проверить выбранное место. При смене начала
ритм анализируется повторно; создание монтажа доступно после завершения анализа.
Трек копируется в закрытое хранилище приложения, а выбор файла и начала
восстанавливается после перезапуска. Импорт и анализ продолжаются при
пересоздании экрана. Ошибка импорта сохраняет предыдущий рабочий выбор.

Для этого стиля склейки выбираются по измеренной сетке битов, сильным долям
и кандидатам дропов. JSON-карта и совпадение с именем встроенного трека не нужны.
FEAR, Heartbeat и DUALITY сохраняют свои авторские музыкальные тайминги.
Прямого скачивания музыки по ссылкам TikTok/Reels нет: требуется аудиофайл.

## Границы реализации

- Анализ выполняется на устройстве по первым 30 секундам выбранного фрагмента.
  Максимальная длительность автоматического результата — 22 секунды.
- Размер импортируемого файла ограничен 128 МиБ. Минимальная длина оставшегося
  фрагмента — одна секунда. Короткий остаток повторяется с выбранного места
  в экспортируемом видео; короткий PCM анализируется с тем же повторением.
- BPM оценивается в диапазоне 65–190. Сильные доли используют гипотезу 4/4;
  дропы определяются по устойчивому росту энергии и баса. Это эвристики,
  а не гарантия музыкальной структуры для речи, нерегулярного ритма или смены темпа.
- Если надёжная оценка BPM отсутствует, экран показывает «ритм не определён».
  Директор использует доступные акценты и резервные интервалы склеек.
- Прослушивание проигрывает до 30 секунд исходного хвоста без повторения.
  Все экспортные петли используют только выбранный хвост, без возврата во вступление.
- В новом режиме достаточно одного видео длиной не менее 15 секунд; наличие
  людей не обязательно. Автоматические проверки не заменяют просмотр результата.

## Воспроизводимая проверка

`CustomAudioTest` проверяет окно PCM, повторение выбранного хвоста, ритм коротких
фрагментов и эвристический дроп. `CustomMusicRoutingTest` проверяет отдельный
стиль и приоритет музыкального дропа для склейки.

`CustomMusicScreenTest` проверяет системный импорт, отмену, сохранение файла
и начала, пересоздание во время работы и блокировку всех входов выбора стиля.
`CustomAudioDeviceTest` использует синтетические WAV/MP3 и реальный
MediaExtractor/MediaCodec/GLES/AAC: сравнивает PCM при сдвиге, проверяет ритмические
склейки и экспорт повторяющегося хвоста, а также весь нативный редактор без людей.
MP4 и текстовые отчёты записываются в external files тестового пакета
`com.veycad.app.uitest`, каталог `custom-audio-evidence`.

Нативный сценарий сохраняет `native-editor-evidence.json` сразу после рендера,
до проверок и очистки private inspector последующими UI-тестами. Отчёт содержит
SHA256 исходника, музыки и итогового MP4, полный граф со смещением аудио,
полный acceptance (метрики, пороги, issues и decoded audio), все VisualSamples,
аудиокарту, visual events/observations и исходный inspector с planned/decoded PTS.
`decoded_source_clock` связывает output PTS с измеренными source PTS; ключи —
микросекунды в строковом виде. Тест проверяет число записей по числу кадров и
неотрицательные PTS; отсутствие или неполнота clock отмечаются явно и вызывают
ошибку после сохранения отчёта. `quality_gate` сохраняет исходное значение;
диагностика не меняет QA-пороги и не делает отрицательный результат успешным.
Это тестовый локальный snapshot, а не общий release-report и не художественная
приёмка. Рефлексия сохраняет поля модели без округления и сокращения массивов.
Запись потоковая, с буфером 8192 символа: planes не дублируются в JSON-дереве или
гигантской строке. Inspector читается через streaming JsonReader и встраивается
из файла ограниченным буфером; в памяти остаётся только компактная карта PTS.
Для assertions возвращается небольшой Summary, полный файл повторно не читается.
Host-проверки `tools/custom_music_tests/CustomMusicJsonWriterTest.kt` запускаются
JUnit с `-Xmx64m` и проверяют точный SHA256 всех 8 миллионов float-значений в
JSON, который больше всего heap, а также scalar precision/null/enum/escaping.
Небольшой `fidelity.json` проверяется независимым JSON-парсером. Android-тест
проверяет streaming clock, включая большие Long PTS, null и отрицательные PTS.
Команда независимой проверки:

```shell
python tools/custom_music_tests/verify_fidelity.py <каталог-host-evidence>/fidelity.json
```

Парные и одиночные UTF-16 суррогаты экранируются как `\uXXXX`, чтобы UTF-8 запись
сохраняла исходные строки.

Для разбора `colour-jump` сопоставьте `maximumColourJumpTimeUs` с соседними
VisualSamples, clip/overlay окнами графа и decoded source clock из inspector.

CI сохраняет восемь синтетических файлов (`editor-source.mp4`, `editor.wav`,
`native-editor.mp4`, `native-editor-evidence.json`, `native-editor-report.txt`,
`stream-clock.json`, `stream-clock-copy.json`, `selected-tail.mp4`)
в `android-ui-reports` / `build/reports/ui-tests/custom-music-native/`.
Wrapper `tools/run_custom_music_ci_tests.sh` выполняет существующий UI runner
и копирует эти файлы до выключения эмулятора, даже при неуспешном gate.
Он сохраняет exit code runner; отсутствие файла делает сбор evidence неуспешным.
`ci-manifest.txt` фиксирует GitHub run/attempt, checkout SHA, GitHub SHA, статусы
runner/сбора и SHA256 каждого файла; отсутствующие файлы помечены MISSING.
Для PR GitHub SHA может быть merge commit; head SHA берётся из metadata данного
Actions run. UI fixture cleanup очищает private files/cache, но не этот внешний
каталог, поэтому сбор после фазы screens сохраняет файлы до shutdown.
`stream-clock.json` намеренно заканчивается лишним `{}` для negative-теста
reader; `stream-clock-copy.json` хранит валидную копию до этого изменения.
Код QA и его пороги не меняются. Команда лёгкой host-проверки поведения:
`python tools/custom_music_tests/test_ci_retention.py` (нужен Bash).

Для `weak-rendered-transitions` проверьте каждый тип перехода и максимальный
`transitionStrength` в его окне. Сначала нужен один такой нативный прогон;
парный контроль или base/current сравнение нужны только при неясной причине.

Обязательные проверки: `./gradlew clean baselineVerify` и
`tools/run_ui_tests.ps1` на выделенном эмуляторе. Приёмку на физическом телефоне
и на реальных треках со сменой темпа необходимо указывать отдельно в PR.

## Точечная диагностика сохранённого MP4

В workflow `Android UI` отдельный manual input `custom_music_targeted=true`
запускает диагностику сохранённого синтетического MP4 из CI `38054343649`
(artifact `11670925739`, исходный head `143a478a`). Обычный UI job при этом
не запускается. Для PR/push и dispatch без этого input прежний UI gate сохранён.
Локальные Gradle, эмулятор и рендер для этой диагностики не требуются.
Saved-MP4 часть выполняет только decode; парный visual control делает
два новых рендера на удалённом CI runner.

`prepare_targeted_input.py` сверяет pinned ZIP, все восемь manifest SHA,
source run/head/merge parents и полный исходный JSON. Из восьми фактических
BLACKOUT PTS и ближайших соседей Inspector выбираются 16 уникальных запросов.
Optional Android test source подключается только с
`-PcustomMusicTargeted=true`; обычный APK тестов не содержит этого набора.
MediaCodec последовательно читает сохранённый MP4 до EOS и записывает
requested/actual PTS (явный допуск округления 1 мкс), codec/build и измеренные
48×72 YUV luma grids. Это дополнительное свидетельство, не новый `transitionPeak`.

Для проверки конкретного colour-jump тот же workflow передаёт один source
и один frozen visual graph базовому и текущему renderer на одном эмуляторе.
На обоих refs из графа убрана только `audioTrack`; все остальные поля и все
64 mask planes сохраняются. Plane values передаются как lossless f32le с
размерами, offsets и range SHA вместо большого JSON-дерева на Android.
Одинаковый typed test loader подключается в базу только через test source set;
production источники базы не меняются. Сравниваются 480 planned fingerprints,
actual source clocks и decoded YUV hashes, а также 160 colour samples.
При несовпадении diagnostic comparison завершается неуспешно и сохраняет отчёт.

В `custom-music-targeted-reports` сохраняются входы, provenance, отчёты, XML,
логи, APK hashes и manifest. Частичный отчёт сохраняется до assertion и до
shutdown эмулятора. Исходные JSON/MP4, thresholds и `quality_gate=false`
не меняются. Контроль относится к одному synthetic source/frozen graph;
он не доказывает отсутствие регрессии всей функции (в базе нет CUSTOM_MUSIC),
PCM_FLOAT headroom, приёмку на телефоне или человеческий просмотр.

### Подтверждённые результаты checkpoint 20d64c5a

[Baseline 38059647486](https://github.com/Veycad/Veycad/actions/runs/38059647486)
и [UI 38059647484](https://github.com/Veycad/Veycad/actions/runs/38059647484)
прошли на `20d64c5a5aaea2e2f0b4966fc6f01f0ed09fe0e5`. Два канонических UI XML
подтвердили 74 выполненных теста без failures/errors/skips. PR merge checkout
`33b68e3f0643ce95acb46bdf8f3e075448388492` имеет parents `[a0395e26,20d64c5a]`.
Все восемь SHA manifest в artifact `11672718841` проверены; полный native JSON
имеет SHA `02f556e7b7f188ca5f45b6f3400dde846bc4382e0e4f5e885de8b92e003c1054`.
Это новый native render/output, SHA MP4 `09bb26563846c418b5452d035beb2a83fb528e4bca68e5d46c37eed6d259f187`.
Его QA, 160 VisualSamples и 480 actual execution frames совпали с CI143;
`quality_gate=false` и прежние три issue сохраняются.

[Отдельный diagnostic 38059771527](https://github.com/Veycad/Veycad/actions/runs/38059771527)
также прошёл на exact 20d64c5a: три выполненных теста без failures/errors/skips.
Artifact `11672478425`, 29 051 278 bytes, SHA ZIP
`a120d6a34f1136d3b1a7f9227453b5aa2d6a6386468e1e82f2874509fde45ce0`,
содержит 34 manifest-bound payloads. Saved-MP4 decode здесь использует **старый**
CI143 MP4 с SHA `c2c9eae62f9e1f0e5ecd253d17b0cacd140a65e282bf0995a03425e4b344ea9f`.
Android `c2.goldfish.h264.decoder` прочитал 480 payload frames до EOS, нашёл все
16 выбранных кадров и восемь BLACKOUT targets. У 12 PTS offset=0, у четырёх
offset=-1 мкс; actual PTS сохранены отдельно от requested PTS.

Пики соседних grids пересчитаны независимо в Float32; в таблицу входят только
пары с правым actual PTS внутри исходного BLACKOUT окна, сосед после окна исключён.

| Окно, секунды | Дополнительный диагностический пик |
| --- | ---: |
| 1.488–1.593 | 0.26118720 |
| 5.488–5.593 | 0.73333335 |
| 9.488–9.593 | 0.79360729 |
| 13.488–13.593 | 0.59269410 |

Среднее `0.59520549` не является native `transitionPeak=0.015068494` и не
подменяет gate. Decode подтверждает, что прежняя QA-сетка пропускает внутренние
BLACKOUT кадры. Canonical selector для production QA принадлежит общему core.

Независимое сравнение visual controls base `a0395e26` / current `20d64c5a`
подтвердило `equivalent=true`: 480 planned fingerprints, 480 actual source clocks,
480 decoded YUV fingerprints, 160 colour samples и пара на 12 с равны.
Сохранённые plan и actual source/clip не тождественны на четырёх held outgoing
границах, но поведение одинаково в base/current. Weighted colour delta на 12 с
равна `0.35320183634757996` в обоих контролях. Encoded container SHA различаются;
сравниваются decoded pixels, а не bytes контейнера. Эти два **новых** рендера имеют
`audioTrack=null`, поэтому не дают нового PCM_FLOAT witness или общей приёмки режима.
Native decodedAudio в targeted report перенесён из CI143 и не является новым decode.

### Контракт следующей интеграции с общим core

На проверенном `main a32a6a849abb3c781c21790c409b484f0fb65488` уже есть
canonical `RenderQaSampling` и manual/shared runtime. Перенос только QA-файлов
из прежнего owner checkpoint 90021ca1 в legacy music graph не даёт замкнутого набора зависимостей:
helper зависит от `EffectSampleWindow`, `renderOverlays/renderNodes`, а sampler —
от editable timing, manual overrides и source attachments. Второй selector не нужен.

Additive handoff владельца core должен сохранить один `MontageGraph.AudioTrack`
с третьим параметром `sourceStartUs: Long = 0L` и проверкой `sourceStartUs >= 0`.
Главный конфликт: в main AudioTrack пока содержит только sourceId/gain;
whole-file замена потеряет offset или новые manual поля. В full export выбранный
трек повторяет `[sourceStartUs, trackDurationUs)`, не возвращаясь к вступлению.
В `AudioExportPlan.window` main пока повторяет `[0, trackDurationUs)` по global
phase; для музыки со сдвигом global phase должна вычисляться по длине выбранного
остатка, при default offset=0 прежний контракт сохраняется. Preview/window export
и full export должны пользоваться одним audio clock, а новые manual frame/graph
поля и фактические decoded PTS должны сохраняться.

Требуются проверки offset=0, внутреннего offset, повторения короткого остатка,
window по global phase, MP3/WAV preroll и encoder delay/padding на объединённом SHA.
Старые зелёные gates 20d64c5a относятся к базе a039, а не к будущей интеграции.
PCM_FLOAT history, качество реальных треков, телефон и человеческая приёмка открыты.
