# Предложение результата SpeechTranscriber для интеграции

Дата: 2026-10-10. Статус: конкретное совместимое расширение для владельца текста/STT #12 через координатора; API пока не реализован и качество backend не подтверждено. Базовый recognizer — единственный Whisper из #12, без второго Vosk/editor/store.

## Существующая граница

Прочитан `text-subtitles/docs/integration/text-subtitles-contract.md`: `transcribe` принимает MP4, `transcribePcm` принимает заимствованный PcmFile (s16le,16kHz,mono,frames), оба возвращают List<CaptionCue>. Время — конечная выходная шкала от0. transcribe удаляет собственный PCM, transcribePcm не удаляет заимствованный файл. Cancellation callback пробрасывается исключением; модель закрывается finally.

Эти правила сохраняются. Для source speech адаптер галереи готовит PCM после склеек/ramp/повторов/offset/gaps до музыки; для final-audio workflow используется слышимый микс MP4. Размер PCM обязан покрывать frames; backend не читает сверх frames и не добавляет собственный source time map.

## Минимальный подробный результат у того же владельца

```kotlin
sealed class SpeechResult {
  data class Success(val cues: List<CaptionCue>, val words: List<SpeechWord>,
                     val evidence: SpeechEvidence) : SpeechResult()
  data class NoSpeech(val reason: NoSpeechReason, val evidence: SpeechEvidence) : SpeechResult()
  data class Failure(val code: SpeechFailureCode, val message: String,
                     val retryable: Boolean, val evidence: SpeechEvidence?) : SpeechResult()
}
data class SpeechWord(val text: String, val startUs: Long, val endUs: Long,
                      val confidence: Float?)
data class SpeechEvidence(val backendVersion: String, val modelSha256: String?,
  val language: String, val inputFrames: Long, val recognitionPerformed: Boolean,
  val timingKind: TimingKind, val confidenceKind: ConfidenceKind)
```

Финальные имена принадлежат #12. TimingKind различает WORD_ALIGNMENT, TOKEN_ALIGNMENT и SEGMENT_ONLY; ConfidenceKind различает BACKEND_WORD, TOKEN_AGGREGATE и UNAVAILABLE. Метки описывают настоящую семантику backend, не превращают segment estimates в точные words. Confidence — конечное0..1 либо null; значение/агрегация определены и зафиксированы, null не заменяется1. Неполные/несопоставимые evidence явно требуют ручной проверки и оставляют word-quality gate открытым.

Success содержит непустые настоящие cues в `[0,durationUs)`, где durationUs=floor(frames*1_000_000/16_000) с half-open интервалами; raw words хранятся отдельно от пользовательских исправлений. Реальные words нужны для принятия word-boundary требований; segment-only checkpoint может поддерживать прежний экран, но не выдаётся за выполнение median/p95≤200/500ms. Word metadata не выдумывается делением фразы на равные интервалы. Confidence<0.75 и неизвестная confidence дают needsReview у соответствующих cues; ручное исправление не запускает STT заново.

NoSpeech выдаётся для успешно проверенного входа и завершённого распознавания без речи. NO_AUDIO_TRACK может быть явным preflight reason с recognitionPerformed=false; это не доказательство запуска модели. Цифровая тишина без речи и quiet intelligible speech — разные fixtures. Невозможность загрузить модель, native failure, malformed result, unsupported PCM и decode/IO error не становятся пустым List и NoSpeech.

Failure codes минимум MODEL_MISSING, MODEL_CORRUPT, INPUT_INVALID, DECODE_FAILED, IO_FAILED, NATIVE_FAILED, RESULT_INVALID. Ограниченный понятный message не содержит путей/чувствительных логов. Cancellation сохраняет исходное исключение callback и идёт в отменённое состояние воркера, не маскируется NoSpeech/Failure с публикацией пустых captions.

## Совместимость существующих вызовов

Добавить подробный entry point (`transcribeDetailed`/`transcribePcmDetailed` либо эквивалент) в тот же SpeechTranscriber. Имена не должны конфликтовать только типом return. Старые методы возвращают cues из того же единственного detailed run: Success→cues, NoSpeech→emptyList, Failure→отличимое typed exception. Нельзя запускать модель дважды ради старого/нового результата. Уже существующие вызывающие методы продолжают работать, а новый coordinator использует различимые outcomes.

Если временный adapter оборачивает legacy cues, он маркирует SEGMENT_ONLY/UNAVAILABLE и не присваивает model/word evidence, которого не получал. Успех such adapter не закрывает STT quality gate. Для финальной интеграции владелец предоставляет реальные model hash/word timestamps/backend probabilities либо совместно согласованную замену backend за тем же интерфейсом при доказанной непригодности.

## Persistence и ownership

SpeechEvidence и raw words принадлежат текстовому модулю и сохраняются через адаптер общей HybridRevision/asset store #15, вместе с correction flags и ключом transcript: ordered source fingerprints + graph/source map + audio mode/settings + model hash. Не создавать второй project ID/revision counter или самостоятельно восстанавливать source map из исправленных cues. Failure не перезаписывает последнюю принятую дорожку/MP4; NoSpeech и Failure показываются отдельно.

## Проверки совместной границы

- Реальный русский PCM без video track проходит transcribePcmDetailed, остаётся существующим после success/error/cancel; собственный PCM MP4 маршрута удаляется finally.
- Источник из3+ файлов с nonzeroPTS, silent middle, speed ramp/repeat и gap: слова соответствуют слышимой source speech на выходной шкале. A/V durations отличаются максимум на кадр; source map совпадает с video frame evidence.
- Цифровая тишина source + voiced app music даёт NoSpeech в source-speech маршруте; разборчивая тихая русская речь не исчезает. Final-audio MP4 распознаётся по его настоящему слышимому миксу отдельно.
- Missing/corrupt model, native error, invalid format/result и IO loss дают разные Failure, не NoSpeech. Cancellation закрывает native/decoder и не удаляет заимствованный PCM. Один model run и один lease при repeated taps.
- Licensed RU corpus с ручной разметкой, шумной речью/музыкой исходника: per-sample WER, median/p95 word boundaries≤200/500ms по реальным words, неизвестная confidence/низкая<0.75 отмечена. Segment-only результат не подменяет word-boundary измерение.
- Изменение style/ручной correction не вызывает новый STT; graph/source/audio/model изменение инвалидирует cache. Durable correction переживает rotation/process death и repeat export.
- Report: exact commit/APK/model SHA, fixture license/annotation method, backend/evidence kinds, device/API/ABI/page size, runtime/peak RAM/buffer/cancel, WER/boundaries/полный MP4 decode. Эмуляторы — текущий разрешённый этап; Samsung A25/человеческая приёмка отдельно pending.

Тяжёлый слот сейчас принадлежит тексту/STT; этот контракт не разрешает новый параллельный локальный UI/clean baseline. Небольшие JVM adapters/tests идут с --max-workers=1. Реализация и evidence привязываются к проверенному закоммиченному API владельца #12.
