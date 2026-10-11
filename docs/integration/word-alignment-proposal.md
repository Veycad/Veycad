# Word alignment: контракт и открытый quality gate

11 октября 2026. Backend PR 12 — закреплённый whisper.cpp
`48f628a84833905ee4a0658ee6d4a5c915ce1997` / multilingual base. Совместимый
research checkpoint добавляет raw token evidence и производные слова с
эвристическими границами. Публичные cues остаются фразами; `wordTimings=null`,
`confidence=null`, `accuracyMeasured=false`. Второй recognizer не добавляется.

## Что действительно предоставляет этот backend

Проверены исходники закреплённого checkout: `include/whisper.h:121–148`,
`src/whisper.cpp:8528–8559,8958` и `examples/cli/cli.cpp:744`.

- Token data содержит token ID, вероятности и t0/t1. Обычный timestamp-путь
  дополняет неизвестные интервалы пропорционально `voice_length`, затем
  корректирует их по энергии. Это эвристика backend, а не доказанное
  акустическое начало и конец слова.
- Experimental DTW выключен по умолчанию. Для base доступны attention heads,
  но публичный `t_dtw` — приблизительный момент токена, с шагом пути 20 ms,
  а не пара границ слова. Следующая точка не превращается автоматически в
  истинный конец предыдущего слова.
- `split_on_word` задаёт разбиение сегментов при max_len, без отдельного aligner.
  `token p` не является откалиброванной уверенностью русского слова.
- BPE-токен может содержать часть UTF-8 символа. Raw bytes нужно объединить до
  декодирования Unicode; отдельный token через `NewStringUTF` может повредить
  русский текст или emoji.

Следовательно, включённые token timestamps и успешный phrase smoke test не
закрывают требование median <=200 ms / p95 <=500 ms для границ слов.

## Реализованный совместимый research outcome

Расширяется тот же `transcribeOutcome` / `transcribePcmOutcome`, с одним запуском
модели и прежним владением PCM. List-вызовы извлекают cues из этого результата.
Существующие PHRASE, NoSpeech reasons, Failure codes и Cancellation сохраняются.

В `SpeechTokenEvidence.kt` реализован следующий внутренний контракт:

```kotlin
enum class SpeechAlignmentKind { SEGMENT_ONLY, TOKEN_HEURISTIC, ACOUSTIC_WORD }
enum class SpeechConfidenceKind { UNAVAILABLE, TOKEN_AGGREGATE, CALIBRATED_WORD }
data class SpeechTokenIdentity(val windowStartFrame: Long,
    val segmentIndex: Int, val tokenIndex: Int)
data class SpeechRawToken(val identity: SpeechTokenIdentity, val tokenId: Int,
    val special: Boolean, val startTick: Long, val endTick: Long,
    val probability: Double, val bytesHex: String)
```

К существующему `SpeechEvidence` добавлены raw evidence с backend commit/bridge hash,
requested language, inputFrames, window language evidence, alignment kind,
confidence kind, raw tokens и производные words. Raw коллекции имеют защитные
копии и доступны только для чтения; bytes хранятся неизменяемой hex-строкой.
У слова nullable start/end, ссылки на все contributing tokens, среднее token p
и признак общего токена с другими словами. Значения confidenceKind на словах
не меняют публичную phrase confidence. DTW отключён; ACOUSTIC_WORD и
CALIBRATED_WORD зарезервированы для будущей доказанной реализации.
Для смешанных языков хранится результат каждого окна, а не язык последнего
окна для всего файла. Requested `auto` не заменяется detected `ru` в cache key.

JNI возвращает ASCII v1 header, сегменты и token bytes/IDs/ticks/probabilities.
Header содержит фактический SHA-256 компилируемого `whisper_jni.cpp`, число
frames, язык и vocabulary IDs. Это hash bridge, а не hash нового DTW patch;
core backend не изменён. Разные окончания строк могут дать разные source hashes,
поэтому отчёт фиксирует фактически встроенный hash проверенного APK.
Unicode восстанавливается по потоку bytes; byte offsets связывают целые слова
с contributing token IDs. Min/max эвристических token intervals маркируется
TOKEN_HEURISTIC и сохраняет provenance. Такой результат не выдаётся за
ACOUSTIC_WORD или закрытый word-quality gate.

Для кандидата акустического alignment нужно исследовать полный DTW path и
границы переходов, затем группировку BPE в слова и обработку пунктуации/пауз.
Это отдельный reviewed patch закреплённого backend с собственным hash, повторной
оценкой RAM/скорости/отмены. Непригодный alignment оставляет SEGMENT_ONLY;
интервалы не обрезаются, не растягиваются и не интерполируются для PASS.

UNKNOWN tick `-1` сохраняется; если contributing token неизвестен или имеет
нулевую длительность, слово остаётся untimed без интерполяции. Валидное timed
word обязано иметь `0 <= startUs < endUs <= durationUs`, где
`durationUs=floor(inputFrames*1_000_000/16000)`. Обратные и выходящие за пределы
окна известные ticks, а также ticks меньше `-1`, дают RESULT_INVALID; end ровно
в durationUs разрешён. Cancel сохраняет первое исключение даже при однократном
callback, освобождает модель и сохраняет borrowed PCM. Реальный короткий/неполный
native window проверяется device-сценарием, а не только искусственными wire rows.

На перекрытии окон выбирается один фактически наблюдавшийся вариант слова
с сохранением его времен и provenance. Времена не усредняются. Удаляются
дубли одной наблюдавшейся области, но не реальные соседние повторения слова.
Raw evidence неизменно после ручных исправлений cue. Confidence unknown,
низкая <.75 или без доказанной калибровки требует review; явная ручная отметка
хранится отдельно и не меняет исходную probability в 1.0.

## Конкретный протокол RU оценки

До сбора записей фиксируется неизменяемый manifest и правила расчёта:

1. Пилот: минимум восемь говорящих, по четыре записи (чистая речь, тихая речь,
   исходная музыка/шум, паузы/повторы/стык 28–30 s окон). Только записи с
   разрешением/лицензией, разрешающей такую обработку и публикацию fixtures.
   Пользовательские видео не добавляются в Git автоматически.
2. Для каждого файла: SHA-256, licence/consent reference, sampleRate/channels,
   transcript, условия записи и вручную размеченные start/end каждого слова.
   Два аннотатора независимо слушают звук и проверяют waveform; расхождения
   разрешаются до запуска оценки. Синтетический smoke fixture без этой разметки
   не считается gold corpus.
3. Ещё минимум два говорящих, по тем же четырём условиям, целиком остаются
   holdout: всего минимум 40 различных записей от 10 говорящих. Их слова не используются
   для выбора DTW/grouping/threshold. Параметры фиксируются до holdout run.
4. Нормализация текста и punctuation policy записываются заранее. Levenshtein
   word alignment даёт substitutions/deletions/insertions и WER. Для matched
   words отдельно измеряются абсолютные ошибки начала и конца; пропуски,
   вставки и invalid intervals остаются в coverage/ошибках отчёта.
5. Для каждого файла и pooled набора публикуются reference/hypothesis counts,
   matched coverage, WER, invalid count, median и p95 обеих границ. p95 считается
   nearest-rank `ceil(.95*n)` без удаления выбросов. Малое число matched words
   при плохом WER не закрывает gate даже с красивыми timing percentiles.
6. Условие timing gate: median <=200 ms и p95 <=500 ms **для обеих границ**,
   без malformed intervals. Предлагаются WER <=20% и matched coverage >=80%:
   продуктовый предел примерно одной текстовой ошибки на пять reference words.
   Это предложение, а не результат пилота или утверждённый порог. До holdout
   требуется документированная проверка pilot report и принятие критериев;
   пороги нельзя подобрать постфактум под результат.
7. Отчёт связывает source/patch/model/APK SHA, annotations/manifest hash,
   device/API/ABI/page size, wall time, peak RAM, PCM buffer size, cancellation,
   полный MP4 decode и услышанную source speech. A25 и человеческая приёмка
   сохраняются отдельными этапами.

Воспроизводимый [валидатор и manifest](../testing/speech-word-quality.md)
проверяет provenance и считает WER/coverage/обе границы. Alignment и confidence
имеют отдельные gates; без доказанного acoustic backend и реального RU corpus
alignment остаётся OPEN. Хеш calibration artifact сам по себе не доказывает
калибровку. Шаблон с пустыми placeholders намеренно не проходит full gate.

**Текущий результат:** raw/token research checkpoint реализован;
размеченный RU holdout и доказанный acoustic word alignment отсутствуют.
Word-quality gate открыт. PHRASE checkpoint и needsReview можно интегрировать
раньше, сохраняя эти ограничения в общем HybridRevision через владельца PR 15.
