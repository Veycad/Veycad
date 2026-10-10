# Контракт текста и распознавания, PR 12

Статус: проверенная реализация в `codex/text-subtitles`, коммит
`a2e36bd60f756cf374673562d8323d71698fd474`. Локальный clean gate: 655 JVM и 255 Python;
device-проверки: 8 export/STT и 4 UI, все прошли (подробности в
[протоколе](../testing/text-subtitles.md)). Изменения общего HybridProject сюда
не копируются. Основа интеграции ядра — checkpoint `74b58038` из PR 15;
проектируемые расширения ядра требуют отдельного проверенного коммита.

## Модель и часы

Файлы в `app/src/main/java/com/example/autoedit/`, package `com.veycad.app`:

- `TextEditProject.kt`: `TextLayer(id,text,startUs,endUs,style)`,
  `CaptionCue(id,text,startUs,endUs)`, `TextStyle(position,font,sizeRatio,color,darkPlate,animation)`.
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
фразы. Доказанные границы каждого слова сейчас **не предоставляются**. Для такого
расширения нужны экспорт token/word intervals из backend, правила склейки,
эталонный RU-корпус с ручными границами и измеренная ошибка тайминга. Токенная
вероятность Whisper не является откалиброванной уверенностью фразы. Успешные
короткие EN/RU-фикстуры и MP4-проверки не заменяют этот корпус или приёмку качества.

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
