# Контракт текста и распознавания, PR 12

Статус: проверенный checkpoint текста в `codex/text-subtitles`, коммит
`cb64b2871bcd65ba103020fa9fb16c4d1222558b`. Remote clean gate: 662 JVM и 255 Python;
device-проверки: 81 Android, включая 16 text/layout/export/Whisper сценариев,
все прошли без ошибок и пропусков (подробности в
[протоколе](../testing/text-subtitles.md)). Изменения общего HybridProject сюда
не копируются. Историческая основа интеграции — checkpoint `74b58038` из PR 15;
текущее состояние общего ядра и его аудиоадаптера проверяется их владельцами
отдельно. Результат их проверки не следует из зелёного PR 12.

## Модель и часы

Файлы в `app/src/main/java/com/example/autoedit/`, package `com.veycad.app`:

- `TextEditProject.kt`: `TextLayer(id,text,startUs,endUs,style)`,
  `CaptionCue(id,text,startUs,endUs)`, `TextStyle(position,font,sizeRatio,color,darkPlate,animation)`.
  Совместимые поля расширений описаны ниже; старые конструкторы сохранены.
- `TextEditProject(sourcePath,durationUs,width,height,layers,captions,captionStyle,captionsEdited,language,sourceOriginUs=0)`.
- `TextLayerLayout.draw(canvas,project,timeUs)` — одна раскладка для preview и burn-in.
- `CaptionSegmenter.merge(existing,incoming,durationUs)` — согласование перекрывающихся окон STT.

Все интервалы — целые микросекунды `[startUs,endUs)` от начала **выходного видео**,
ограниченные `durationUs`. Первый PTS видеодорожки исходного MP4 — начало этой шкалы;
аудиосмещение относительно него сохраняется. Стиль содержит SANS/BOLD/SERIF,
TOP/CENTER/BOTTOM, отношение размера к ширине, ARGB, подложку и NONE/FADE/SLIDE.
`captionStyle` применяется ко всем фразам; `captionsEdited` сохраняет факт ручных
изменений и защищает их от незаметной замены повторным STT.

Адаптер HybridRevision должен сохранять эти поля и стабильные ID целиком.
Три `TextItem.Appearance` существующего checkpoint не покрывают модель: требуется
расширение схемы владельцем ядра. Текстовые команды входят в общий CAS/revision
и undo/redo; собственный счётчик ревизий не добавляется. При frame-based хранении
используется `ProjectClock.timeUs(frame)`; сохранённые source/VFR PTS не пересчитываются
через округлённую скорость. Для экспорта окна текст рисуется по абсолютному
`projectTimeUs`, даже если кодер использует локальный `outputTimeUs`.

## Whisper и подготовленная речь

`SpeechTranscriber.kt`, `WhisperSpeechTranscriber.kt`, `TextPcmDecoder.kt`:

```kotlin
internal interface SpeechTranscriber {
    fun transcribe(context: Context, source: File, language: String,
        checkCancelled: () -> Unit, onProgress: (Int) -> Unit): List<CaptionCue>
    fun transcribePcm(context: Context, pcm: PcmFile, language: String,
        checkCancelled: () -> Unit, onProgress: (Int) -> Unit): List<CaptionCue>
}
internal data class PcmFile(val file: File, val sampleRate: Int,
    val channels: Int, val frames: Long, val hasAudio: Boolean)
```

API `internal` доступен адаптеру в том же Gradle-модуле `app`. Реализацию STT
не дублировать; при выделении другого модуля сначала согласовать public facade.

`transcribe` декодирует слышимую дорожку выбранного MP4 на диск, дополняет
пропуски/короткий хвост тишиной и удаляет собственный PCM после обработки.
Готовый музыкальный эдит распознаётся по своему звуку.

`transcribePcm` принимает **заимствованный** файл s16le, 16000 Hz, mono.
`frames` задаёт длительность окончательной выходной шкалы; байтов должно быть
не меньше `frames*2`. Начало файла соответствует выходному времени 0. Метод
не удаляет файл; владелец аудиокомпозиции освобождает его после окончания вызова.
Отмена передаётся исключением из `checkCancelled`; JNI использует её как abort
probe. Контекст модели освобождается в `finally`, до последующего видеоэкспорта.
Язык — `ru`, `en` или `auto`; результат содержит абсолютные выходные cue times.

Совместимый outcome-адаптер предоставляет `transcribeOutcome` и
`transcribePcmOutcome`, сохраняя прежние List-вызовы. `SpeechOutcome` различает
`Success(cues,evidence)`, `NoSpeech(evidence)` и `Failure(cause,evidence,code)`;
отмена продолжает передаваться `CancellationException`. Пустой результат не
подменяет ошибку декодера или модели. `SpeechEvidence` у текущего backend
указывает `timing=PHRASE`, `wordTimings=null`, `confidence=null`,
`accuracyMeasured=false`. Это явное отсутствие измерений, без фиктивной confidence.
`recognitionPerformed` показывает факт native-вызова; в legacy List-адаптере
статус неизвестен (`null`). Причины пустого результата различаются:
`NO_AUDIO_TRACK`, `DIGITAL_SILENCE`, `RECOGNIZED_NO_SPEECH`, `LEGACY_EMPTY`.
Пропускается только цифровой ноль; порог громкости не отбрасывает тихую речь.
`Failure.code` различает input/decode/model/integrity/IO/native/invalid result;
`userMessage` безопасен для интерфейса и не содержит внутренних путей.
Malformed native rows дают `RESULT_INVALID`, а не отсутствие речи. SHA-256
проверяется у реально используемого кэша модели перед каждым открытием.

JNI включает token timestamps для сегментации, но публичный результат содержит
фразы. Research outcome дополнительно сохраняет immutable raw сегменты/токены,
язык каждого окна, backend commit, SHA-256 исходника JNI bridge и отдельный
SHA bounded-decoding patch script в header v2 (`backendPatchSha256`).
Hex bytes объединяются до UTF-8 decoding; производные слова используют ссылки
на contributing tokens и маркируются TOKEN_HEURISTIC. UNKNOWN/нулевые token
intervals оставляют слово untimed. На перекрытии выбирается один наблюдённый
вариант без усреднения, raw windows сохраняются целиком. Причина однократной
отмены сохраняется sticky callback. Эти расширения используют тот же native call.

Доказанные акустические границы каждого слова сейчас **не предоставляются**.
Для них нужны reviewed acoustic backend, эталонный RU-корпус с ручными границами
и измеренная ошибка тайминга. Токенная
вероятность Whisper не является откалиброванной уверенностью фразы. Успешные
короткие EN/RU-фикстуры и MP4-проверки не заменяют этот корпус или приёмку качества.

Результаты исследования закреплённого backend и research outcome/
alignment API с RU holdout-протоколом: [word alignment](word-alignment-proposal.md).
Acoustic-word backend и calibrated confidence не реализованы; gate открыт.

Галерея/гибридный режим сначала строит речевой PCM после trim, склеек и временных
карт всех источников. При распознавании исходной речи эта шина готовится до
добавления музыки; при распознавании окончательного звука используется итоговый
микс. Сдвиги заполняются тишиной, короткие реплики не зацикливаются. Привязка
assetId/sourceIndex и композиция нескольких источников принадлежат общему ядру
и аудиоадаптеру, а не второму STT или независимому проектному хранилищу.

## Экспорт и сохранность

`TextVideoExporter.export(context,project,output,checkCancelled,onProgress)`
принимает один исходный или уже готовый MP4. Использует 1× finishing pass;
`output` обязан отличаться от исходника. Совместимый полный AAC-LC переносится
пакетами; остальные дорожки декодируются, выравниваются и кодируются AAC.
Проверяются дорожки, длительность и декодирование первого/последнего кадра.
При ошибке или отмене временный MP4 удаляется.

`TextEditSession` публикует новый результат через `CompletedRenderStore`.
Метаданные `textSourcePath` записываются до появления MP4; затем обновляется
ссылка `TextEditStore.resultPath(sourcePath)`. При восстановлении публикационная
метадата закрывает промежуток между этими операциями. Исходник и прежние версии
результата сохраняются. Эти single-video черновики — существующий finishing
workflow; адаптер общего проекта сохраняет текст в HybridRevision и использует
единое долговечное хранилище assets из проверенного storage-коммита.

## Совместимые поля интеграционного расширения

Модель и store: коммит `784d1b3`. Раскладка/UI: проверенный `cb64b287`,
exact-head clean/device CI успешны; результаты фиксируются в PR и протоколе.

- `TextFont`: прежние SANS/BOLD/SERIF и новый MONO.
- `TextAnimation`: прежние NONE/FADE/SLIDE и новый SCALE 0.94–1.0;
  окно `min(200000 us, max(1 us, duration/2))` внутри интервала слоя.
- `TextStyle.plate:TextPlate?=null`: NONE/DARK/ACCENT. При null используется
  старый `darkPlate`, включая `copy(darkPlate=...)`. Акцент ARGB `0xff5427a4`.
- `TextLayer.kind:TextLayerKind=LEGACY`: новые TITLE/PLATE/SUBTITLE явны.
  TITLE <=180 Unicode code points и 3 строки; остальные 2 строки.
  Старые Properties v1 без kind остаются LEGACY, без потери длинного текста.
- `CaptionCue.confidence:Double?=null`, `manuallyReviewed:Boolean=false`,
  `confidenceCalibrated:Boolean=false`; confidence finite в 0..1,
  calibrated требует значения. `needsReview` истинен до ручной отметки при
  unknown, <.75 или uncalibrated. Отметка не заменяет raw confidence.
- `SpeechEvidence.confidenceCalibrated=false` и `needsReview` с теми же
  условиями unknown/low/uncalibrated; текущий backend PHRASE/null/false.
- Общая `TextSafeArea` x=.08.. .92, y=.10.. .85 включает plate/motion,
  упорядочивает TOP/CENTER/BOTTOM и исключает пересечения. Layout уменьшает
  шрифт до .018 ширины; невместимая плотность даёт контрастное предупреждение
  в preview и exception до публикации MP4 в `draw(...,strict=true)`.

Владелец PR 15 должен сохранять kind, plate, MONO/SCALE и cue evidence в одной
HybridRevision/codec, с теми же defaults для старых проектов. Manual review
не является calibration. Text-команды по-прежнему используют общий CAS и
undo/redo, исходную PTS/VFR шкалу, один STT и borrowed PCM.

## Следующий проверяемый checkpoint

Raw-token implementation: `07ec407122cfbeff063ebe2db6ee4b39f4ea6708`.
После merge актуальной main `a32a6a849abb3c781c21790c409b484f0fb65488`
в `5f836b6` удалены временные дубликаты текстовых типов из HybridTextState:
общий state использует TextEditProject types. Renderer сохраняет sourceAttachments,
текст и авторские титры семплируются по globalOutputTimeUs; encoder/progress
остаются на локальных часах. Targeted speech/frame/core/legacy-codec серия:
46 tests / 8 suites, failures/errors 0. Baseline `a5310149` прошёл exact-head CI:
917 JVM / 121 suites, 285 Python, native build трёх ABI и lint; ошибок/пропусков нет.
Android UI `a5310149`: 82 cases, 1 failure segment tick800>756, errors/skips0.
Новый bounded-decoding patch ограничивает timestamp vocabulary реальным остатком
до sampling и fallback seek_delta, не обрезает emitted raw rows; parser strict.
Итог проверок исправления фиксируется для последнего коммита в PR 12;
прежние результаты не переносятся.

На импортированной main общий HybridProjectCodec ещё не сохраняет plate/kind
и cue confidence/manual review: ACCENT может превратиться в DARK, TITLE —
в LEGACY, отметка проверки — в unknown. Поэтому rich-text Hybrid roundtrip
и gallery/native source-audio handoff **не приняты**. Их согласование остаётся
у владельцев общего ядра/аудиоадаптера. TextEditStore отдельного MP4 сохраняет
все расширенные поля; его workflow проверен отдельно.

Исходные integration spec/plan из main сохранены под отдельными именами
`2026-10-10-text-subtitles-integration-design.md` / `...-integration.md`;
owner spec/plan PR 12 остаются на прежних путях. Исторические Vosk/второй store
в integration spec заменены её верхним согласованием: действующий STT один.

## Owner review стыка с общим codec

Контракт core `2026-10-11-text-core-compatibility-contract.md`, checkpoint
`d5a47a0d`, проверен против опубликованной модели PR 12 `a5310149`
(blob `c975659e`). Definitions/defaults, один codec и граница additive version
upgrade после A2a/A2t согласованы, включая original/current/history,
CAS/undo/redo и export snapshots. Реализация и будущие проверки не приняты.

Обнаружено конкретное расхождение wire proposal: MONO/SCALE нельзя запрещать
для всех старых physical versions. Опубликованный `a5310149` уже записывает
и читает эти enum names в общем `FORMAT_VERSION=4`. Новый reader обязан
продолжать принимать такие v4 проекты и snapshots/history; нужна настоящая
a531-v4 fixture. Version bump относится к новым plate/kind/cue evidence полям;
его окончательный номер выбирает владелец codec после preceding storage version.

Сверка существующих immutable v4 fixtures и условия сохранения без потерь:
[A2x acceptance matrix](text-core-a2x-acceptance.md). Core v4 fixtures не содержат
MONO/SCALE и не заменяют genuine a531 capture; отдельно требуется исправление
caption projection LEGACY → SUBTITLE. Матрица не является приёмкой общего codec.

Обновление 20 октября 2026: genuine a531-v4 и фактический preceding-v6 capture
проверены до смены encoder, см. [v4](../../app/src/test/resources/hybrid-text-v4/README.md)
и [v6](../../app/src/test/resources/hybrid-text-v6/README.md). Codec checkpoint
добавляет physical7 для plate/kind/cue evidence и явный SUBTITLE в shared
projection, сохраняя c975 values без изменений и readers1..6. Это подготовка
source/integration handoff владельцу text; совместное owner review и финальный
exact-head baseline/UI остаются OPEN. Проверки телефона/STT/акустики и
человеческая приёмка не входят в этот codec checkpoint.
