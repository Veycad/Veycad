# Предложение результата SpeechTranscriber для интеграции

Дата: 2026-10-10. Статус: конкретное совместимое расширение для владельца текста/STT #12 через координатора; проверенный commit расширения и качество backend пока не подтверждены. Базовый recognizer — единственный Whisper из #12, без второго Vosk/editor/store. Координатор подтвердил, что владелец уже добавляет SpeechOutcome и transcribeOutcome/transcribePcmOutcome: расширяется именно этот API, второй SpeechResult не создаётся. Имена ниже описывают требуемую семантику, окончательные сигнатуры закрепляет владелец после ревью.

## Существующая граница

Прочитан `text-subtitles/docs/integration/text-subtitles-contract.md`: `transcribe` принимает MP4, `transcribePcm` принимает заимствованный PcmFile (s16le,16kHz,mono,frames), оба возвращают List<CaptionCue>. Время — конечная выходная шкала от0. transcribe удаляет собственный PCM, transcribePcm не удаляет заимствованный файл. Cancellation callback пробрасывается исключением; модель закрывается finally.

Эти правила сохраняются. Для source speech адаптер галереи готовит PCM после склеек/ramp/повторов/offset/gaps до музыки; для final-audio workflow используется слышимый микс MP4. Размер PCM обязан покрывать frames; backend не читает сверх frames и не добавляет собственный source time map.

## Минимальный подробный результат у того же владельца

```kotlin
sealed class SpeechOutcome {
  data class Success(val cues: List<CaptionCue>, val words: List<SpeechWord>,
                     val evidence: SpeechEvidence) : SpeechOutcome()
  data class NoSpeech(val reason: NoSpeechReason, val evidence: SpeechEvidence) : SpeechOutcome()
  data class Failure(val code: SpeechFailureCode, val message: String,
                     val retryable: Boolean, val evidence: SpeechEvidence?) : SpeechOutcome()
}
data class SpeechWord(val text: String, val startUs: Long, val endUs: Long,
                      val confidence: Float?)
data class SpeechEvidence(val backendVersion: String, val modelSha256: String?,
  val requestedLanguage: String, val detectedLanguage: String?,
  val inputFrames: Long, val recognitionPerformed: Boolean,
  val timingKind: TimingKind, val confidenceKind: ConfidenceKind)
```

Финальные имена принадлежат #12. TimingKind различает WORD_ALIGNMENT, TOKEN_ALIGNMENT и SEGMENT_ONLY; ConfidenceKind различает BACKEND_WORD, TOKEN_AGGREGATE и UNAVAILABLE. Метки описывают настоящую семантику backend, не превращают segment estimates в точные words. Confidence — конечное0..1 либо null; значение/агрегация определены и зафиксированы, null не заменяется1. Неполные/несопоставимые evidence явно требуют ручной проверки и оставляют word-quality gate открытым.

Success содержит непустые настоящие cues в `[0,durationUs)`, где durationUs=floor(frames*1_000_000/16_000) с half-open интервалами; raw words хранятся отдельно от пользовательских исправлений. Реальные words нужны для принятия word-boundary требований; segment-only checkpoint может поддерживать прежний экран, но не выдаётся за выполнение median/p95≤200/500ms. Word metadata не выдумывается делением фразы на равные интервалы. Confidence<0.75 и неизвестная confidence дают needsReview у соответствующих cues; ручное исправление не запускает STT заново.

Для каждого пригодного timed word время переводится из фактической шкалы backend в целые микросекунды той же выходной PCM-шкалы от0. Инвариант: `0 <= startUs < endUs <= durationUs`; последнее слово может заканчиваться ровно в durationUs. Отрицательные, обратные, нулевые и выходящие за durationUs интервалы дают RESULT_INVALID. Если backend не предоставляет пригодного alignment, words не выдаются как валидные timed words: отсутствие/непригодность явно записывается в evidence, результат остаётся SEGMENT_ONLY и word-quality gate открыт. Нельзя незаметно обрезать, растягивать или выдумывать интервалы, чтобы пройти валидацию. Порядок и дедупликация слов при перекрытии recognition windows сохраняют реальные backend timings и документируются владельцем.

NoSpeech выдаётся для успешно проверенного входа и завершённого распознавания без речи. NO_AUDIO_TRACK может быть явным preflight reason с recognitionPerformed=false; это не доказательство запуска модели. Цифровая тишина без речи и quiet intelligible speech — разные fixtures. Невозможность загрузить модель, native failure, malformed result, unsupported PCM и decode/IO error не становятся пустым List и NoSpeech.

Failure codes минимум MODEL_MISSING, MODEL_CORRUPT, INPUT_INVALID, DECODE_FAILED, IO_FAILED, NATIVE_FAILED, RESULT_INVALID. Ограниченный понятный message не содержит путей/чувствительных логов. Cancellation сохраняет исходное исключение callback и идёт в отменённое состояние воркера, не маскируется NoSpeech/Failure с публикацией пустых captions.

## Совместимость существующих вызовов

Расширить существующие подробные entry points `transcribeOutcome`/`transcribePcmOutcome` в том же SpeechTranscriber. Имена не должны конфликтовать только типом return. Старые методы возвращают cues из того же единственного outcome run: Success→cues, NoSpeech→emptyList, Failure→отличимое typed exception. Нельзя запускать модель дважды ради старого/нового результата. Уже существующие вызывающие методы продолжают работать, а новый coordinator использует различимые outcomes.

Если временный adapter оборачивает legacy cues, он маркирует SEGMENT_ONLY/UNAVAILABLE и не присваивает model/word evidence, которого не получал. Успех such adapter не закрывает STT quality gate. Для финальной интеграции владелец предоставляет реальные model hash/word timestamps/backend probabilities либо совместно согласованную замену backend за тем же интерфейсом при доказанной непригодности.

## Persistence и ownership

SpeechEvidence и raw words принадлежат текстовому модулю и сохраняются через адаптер общей HybridRevision/asset store #15, вместе с correction flags и ключом transcript: ordered source fingerprints + graph/source map + audio mode/settings + model hash + requested recognition language (`ru`/`en`/`auto`). Requested language определяет идентичность запроса; detected language — отдельное необязательное evidence backend и не заменяет requested `auto` результатом `ru` в ключе. Не создавать второй project ID/revision counter или самостоятельно восстанавливать source map из исправленных cues. Failure не перезаписывает последнюю принятую дорожку/MP4; NoSpeech и Failure показываются отдельно.

## Проверки совместной границы

- Реальный русский PCM без video track проходит transcribePcmOutcome, остаётся существующим после success/error/cancel; собственный PCM MP4 маршрута удаляется finally.
- Источник из3+ файлов с nonzeroPTS, silent middle, speed ramp/repeat и gap: слова соответствуют слышимой source speech на выходной шкале. A/V durations отличаются максимум на кадр; source map совпадает с video frame evidence.
- Цифровая тишина source + voiced app music даёт NoSpeech в source-speech маршруте; разборчивая тихая русская речь не исчезает. Final-audio MP4 распознаётся по его настоящему слышимому миксу отдельно.
- Missing/corrupt model, native error, invalid format/result и IO loss дают разные Failure, не NoSpeech. Cancellation закрывает native/decoder и не удаляет заимствованный PCM. Один model run и один lease при repeated taps.
- Licensed RU corpus с ручной разметкой, шумной речью/музыкой исходника: per-sample WER, median/p95 word boundaries≤200/500ms по реальным words, неизвестная confidence/низкая<0.75 отмечена. Segment-only результат не подменяет word-boundary измерение.
- Malformed/out-of-range word fixtures проверяют отрицательные, обратные, нулевые и превышающие durationUs интервалы; отдельный валидный fixture заканчивается ровно в durationUs. Непригодный alignment не превращается в пригодные слова или закрытый word-quality gate.
- Изменение style/ручной correction не вызывает новый STT; graph/source/audio/model/requested-language изменение инвалидирует cache. Переходы `ru`/`auto`/`en` при одинаковом PCM и модели требуют нового recognition; detected language не меняет идентичность исходного `auto`-запроса. Durable correction переживает rotation/process death и repeat export.
- Report: exact commit/APK/model SHA, fixture license/annotation method, backend/evidence kinds, device/API/ABI/page size, runtime/peak RAM/buffer/cancel, WER/boundaries/полный MP4 decode. Эмуляторы — текущий разрешённый этап; Samsung A25/человеческая приёмка отдельно pending.

Тяжёлый слот сейчас принадлежит тексту/STT; этот контракт не разрешает новый параллельный локальный UI/clean baseline. Небольшие JVM adapters/tests идут с --max-workers=1. Реализация и evidence привязываются к проверенному закоммиченному API владельца #12.
