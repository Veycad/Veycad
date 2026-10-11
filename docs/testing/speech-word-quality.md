# Проверка границ слов: инструмент готов, quality gate открыт

В PR 12 нет размеченного RU holdout, доказанного acoustic word alignment или
измеренной калибровки confidence. Этот валидатор проверяет предоставленные
данные, а не распознаёт речь. Синтетические unit fixtures и phrase smoke tests
не являются gold corpus. Реальную точность сейчас заявлять нельзя.

Исходный контракт: [word-alignment-proposal](../integration/word-alignment-proposal.md).
Используется существующий SpeechTranscriber / JNI; второго recognizer нет.

## Данные и неизменяемость

Скопируйте [manifest-template](speech-word-manifest.template.json) в отдельный
каталог evidence. Шаблон намеренно **невалидный**, с `null` вместо выдуманных
SHA, разрешений и идентичностей. Только после подготовки реального корпуса
укажите `corpus_kind=human_annotated_ru`.

- Минимум восемь pilot speakers × четыре условия = 32 записи; отдельно минимум
  два speaker holdout × четыре условия = восемь записей. Holdout не пересекается
  по speaker с pilot/train. Условия: `clean`, `quiet`, `music_noise`, `boundaries`
  (паузы, повторы, стык окон 28–30 s). Минимум полный корпус — 40 записей.
- `audio` — фактический mono PCM16 WAV 16000 Hz. SHA проверяется по байтам,
  frame count и формат — по WAV и длине PCM; duration считается только как
  `floor(input_frames * 1000000 / 16000)`. Повтор аудио по пути или фактическому
  SHA недопустим: копирование одного файла не создаёт независимые записи.
- Для каждой записи нужна существующая декларация разрешения `consent` или
  `license` с `path` и SHA. Нельзя подставлять фиктивную ссылку. Скрипт проверяет
  наличие и неизменность документа; его юридическую достаточность проверяет
  человек. Пользовательские видео автоматически не собираются и не публикуются.
- Два разных annotator ID, два разных исходных файла и `independent=true`;
  отдельный adjudication artifact и adjudicator ID. Каждый JSON имеет вид
  `{"words":[{"text":"речь","start_us":0,"end_us":300000}]}`. Все три версии
  проверяются. Фактическую независимость annotators должен подтвердить reviewer.
- `candidate` связывает backend commit, реальный patch artifact, модель, APK и
  необязательный calibration artifact с SHA. Сохраните также review корпуса и
  разрешений в `corpus_review.artifact`; `approved` отражает состоявшееся ревью.
  Версия 1 допускает только закреплённый whisper.cpp
  `48f628a84833905ee4a0658ee6d4a5c915ce1997`; другой commit требует review и
  явного изменения протокола, одинакового чужого SHA в двух JSON недостаточно.
- Все пути разрешаются относительно соответствующего JSON; SHA — lowercase,
  непустой SHA-256 (commit — 40 hex). Сверяются реальные bytes, повторная проверка
  обнаруживает изменение evidence во время расчёта. Report содержит hashes всех
  прочитанных артефактов и manifest/predictions/criteria. `--report` создаёт только
  новый файл: готовые байты публикуются атомарным exclusive hardlink в том же
  каталоге, любой существующий файл или alias сохраняется без изменений.
  Сам manifest фиксируется до доступа к holdout.

`predictions.json` содержит `schema_version:1`, SHA manifest, `backend_commit`,
`backend_patch_sha256`, `model_sha256`, `apk_sha256`, необязательный `calibration_sha256`,
`alignment_kind`, `confidence_kind`, `generated_at` и полный массив
`recordings:[{"id":"...","words":[...]}]`. Поле `words:[]` допустимо для отказа
распознавания и считается пропуском всех reference words; пропущенная запись
недопустима. Для каждого timed word требуется
`0 <= start_us < end_us <= duration_us`, целые числа без `null`, float и bool,
порядок start не убывает. Ошибки не обрезаются и не интерполируются.

## Предложенные критерии и preregistration

Предложение для редактирования мобильных субтитров: **WER <=20% и matched
coverage >=80%**. Это соответствует максимум одной lexical correction на пять
reference words и минимум четырём matched words из пяти. Это продуктовая
гипотеза о нагрузке редактирования, **не измерение пилота и не одобренный gate**.
WER учитывает также insertions; ошибки времени оцениваются отдельно.

Перед holdout нужно провести pilot, проверить пригодность этих критериев,
получить явное принятие и зафиксировать `criteria.json`. Позднее менять пороги
под результаты holdout нельзя. Нужны `schema_version:1`, SHA manifest,
`registered_at`, `pilot_report:{path,sha256}` и
`acceptance:{approved:true,artifact:{path,sha256}}`. Кроме SHA manifest, заранее
фиксируются `protocol_version:1`, `validator_sha256` (реальные байты
`tools/speech_word_quality.py`) и `protocol_sha256` (реальные байты этого
протокола), а также `unicode_version` используемого Python runtime. Отсутствие
этой привязки оставляет alignment `OPEN`, несовпадение
делает evidence `INVALID`: менять алгоритм нормализации/matching после доступа
к holdout нельзя. В `criteria` указываются:

```json
{
  "wer_max": 0.20, "matched_coverage_min": 0.80,
  "start_median_max_us": 200000, "start_p95_max_us": 500000,
  "end_median_max_us": 200000, "end_p95_max_us": 500000
}
```

Время registration должно быть между созданием manifest и первым доступом к
holdout, строго раньше последнего. Reviewer проверяет внешний журнал/подписанное
решение: локальные timestamps сами по себе не доказывают preregistration.
Скрипт допускает более строгие пороги, но не ослабляет указанные пределы.
При отсутствии измеренного pilot report или принятия критериев gate `OPEN`.

## Расчёт и запуск

```powershell
python tools/speech_word_quality.py --manifest C:/evidence/manifest.json `
  --predictions C:/evidence/predictions.json --criteria C:/evidence/criteria.json `
  --report C:/evidence/word-report.json
python -m unittest discover -s tools -p test_speech_word_quality.py -v
```

Нормализация фиксирована: Unicode NFC, casefold, удаление пунктуации на краях
слова; `ё` не заменяется на `е`, реальные повторы не удаляются. Tokenization
предоставляется в words; пробелы внутри слова, surrogate и symbol-only/emoji-only
слова недопустимы: требуется хотя бы одна Unicode буква или цифра. Levenshtein минимизирует
число lexical edits; при равном edit cost максимизирует exact lexical matches,
при оставшейся неоднозначности выбирает diagonal/deletion/insertion. Времена
не влияют на matching. Only matched pairs получают absolute start/end errors;
все unmatched индексы, substitutions/deletions/insertions остаются в отчёте.

Report показывает counts, WER, coverage, отдельные start/end median и p95 для
каждого файла, условия, split, pooled и holdout по каждому условию. p95 —
nearest rank `ceil(.95*n)`, median обычная; выбросы не удаляются. Нет matches —
percentiles `null`, без ложного нуля. Пороги проверяются для holdout pooled
**и каждого holdout condition**, поэтому pilot не маскирует ошибки holdout.
Report фиксирует `protocol_version:1`, SHA фактических байтов валидатора и этого
протокола (`validator_sha256`, `protocol_sha256`), а также `unicode_version`,
от которой зависит Unicode-нормализация. Эти файлы тоже проверяются на изменения
во время расчёта; изменение скрипта после import делает результат `INVALID`.

Exit codes: `0 PASS`, `1 FAIL` (пороги нарушены), `2 INVALID` (malformed/чужие
hashes/неполный корпус), `3 OPEN` (недостаточно доказательств). Exit code и `gate`
относятся к `alignment_gate`: для его `PASS` нужны `ACOUSTIC_WORD`, review
корпуса, accepted preregistration и успешные фактические WER/coverage/start/end
пороги holdout. `TOKEN_HEURISTIC` и `SEGMENT_ONLY` оставляют alignment `OPEN`.
Калибровка уверенности не блокирует измеренную проверку таймингов.

Отдельный `confidence_gate` остаётся `OPEN` для `UNAVAILABLE`, `TOKEN_AGGREGATE`
и `CALIBRATED_WORD` без независимой ручной проверки. Для декларации его `PASS`
нужны реальный calibration artifact, совпавший prediction SHA и
`candidate.calibration_review` с `reviewer_id`, `approved:true`, SHA calibration
в `calibration_sha256` и отдельным hashed review artifact. Reviewer проверяет
метод, независимые данные и измеренный calibration report; скрипт не оценивает
качество калибровки по содержимому документа. `confidence_review_declared:true`
фиксирует ручное решение, **не автоматическое доказательство калибровки по SHA**.
`needs_review:true` сохраняется, пока хотя бы один из двух gate не `PASS`.

`INVALID` отменяет частичные метрики, `OPEN` сохраняет видимые threshold failures.
`INVALID` останавливается на первом нарушении; его `invalid_count:1` означает
одно обнаруженное нарушение, а не число всех ошибок в непроверенном остатке.

Декларация `ACOUSTIC_WORD` и файл calibration сами по себе не доказывают их
истинность: reviewer должен проверить метод alignment, calibration protocol,
backend patch и исходные записи. A25, RAM/скорость, cancellation, полный decode
MP4 и человеческая приёмка остаются отдельными проверками. Сейчас этих данных
нет; инструмент готовит измерение, официальный word-quality gate остаётся открыт.
