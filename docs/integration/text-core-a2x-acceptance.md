# A2x: приёмка сохранения текста без потерь

Статус на 11 октября 2026: матрица для владельца общего codec, **не приёмка
его реализации**. Владелец HybridProject/codec — core-чат. Один набор values
из `TextEditProject.kt`, один HybridRevision/CAS/store; второго DTO, STT или
хранилища здесь нет. Приватный PR 39 не используется как принятый API.

## Проверенные неизменяемые основания

- Owner contract: PR 12 `15e76210ea4eccb7430bb733a3bb2c23c5ac1293`,
  `text-subtitles-contract.md`, blob `2825b73cc883fab23b83db719abc675f899373d5`.
- Values: `TextEditProject.kt` в `a53101496244fbb8476dfb314c701380a7eff80a`
  и `15e76210` — blob `c975659e92399709935ec2d4220b9fb7ad82539f`.
- a531 physical-v4 codec — blob `2505cd323fc16d7eaffdbe393f3ab9760c7a94fd`.
  Writer записывает `enum.name`, reader вызывает `enumValueOf`; MONO/SCALE
  уже существуют в owner values. Их нельзя запрещать в новом reader для v4.
  Это проверка исходников, не запуск старого writer/нового reader.
- В локальном immutable core checkpoint
  `eebbc2e8a8b6424c288ace9e07535381a6c034c5` существуют четыре
  `app/src/test/resources/hybrid-v4/*.bin`. README указывает capture unchanged
  encoder `f51a595b909cefab46e2afe9843739f0a7a112cc` до v5; codec blob совпадает
  с a531, но f51 text values не содержат MONO/SCALE. В этой сверке проверяются
  bytes/заголовки/hashes; запуск encoder при capture повторно не выполняется.

| Core v4 file | Bytes | SHA-256 |
| --- | ---: | --- |
| project.bin | 5541 | `153fb4621e2794b4d6db9954a84b815b73dd549f5c950744f61f195341675e44` |
| revision-0.bin | 1627 | `33e620bfb0e4a51f2fa2f66d1124f686f5e7611658f9710a79a1686ce4267a26` |
| revision-1.bin | 1992 | `fba04c883eab82094ce6b3d56c78b3d19278aeb1afb9353c92d14ec30655ef88` |
| revision-2.bin | 1644 | `f96fb556070eec6b907cdc0f527096b73e83677ac0d4751e23a3e27e634fdf30` |

Во всех четырёх файлах отсутствуют length-prefixed строки MONO/SCALE;
revision bytes заканчиваются 52-byte default TextState без layers/captions,
а project содержит его трижды.
Эти fixtures полезны для v4 metadata/phase/reset/export compatibility, но
**не закрывают a531-v4 MONO/SCALE regression**. В опубликованном PR 12 ресурсы
содержат только v1/v2/v3; дополнительных сохранённых a531-v4 bytes в проверенных
каталогах owner/core evidence не найдено. Их отсутствие относится к этой области
поиска, а не ко всем возможным копиям проекта.

## Матрица для одного нового codec

Каждая положительная строка проверяется через реальные temp files:
create → save → новый store/reopen; отдельно manifest и immutable revision blob.
Для соответствующих значений проверить original/current/undo/redo и retained
export snapshot, включая prune/reopen. Сравнивать все значения, не только
`resolvedPlate`, отображаемый текст или наличие API. Номер нового physical format
выбирает core после принятой preceding storage/timing версии; не фиксировать 7
заранее. Существующие v1–v4 payloads читаются по своей схеме без новых bytes.

| Область | Обязательные входы | Условие PASS |
| --- | --- | --- |
| Старый v4 | Genuine a531 writer outputs с MONO/SCALE в layers и captionStyle, original/current/history/export; существующие core v4 fixtures | Reader принимает сохранённые enum names; ID/time/style сохранены. Отсутствующие поля получают plate=null, kind=LEGACY, confidence=null, оба flags=false; утраченные старым writer значения не угадываются |
| Preceding format | Genuine project + revision bytes фактического BASE до encoder change, вместе с v1/v2/v3 fixtures | Старые источники, timing proof, map/phase/reset/allocator сохранены; open не переписывает CURRENT, старые retained blobs сохраняют byte hash |
| Styles | layer.style и captionStyle: все fonts/animations, sizeRatio .025/.12, ARGB, position | Равенство каждого поля, включая MONO/SCALE; старые BOLD/SLIDE не меняются |
| Plate | null × darkPlate false/true; NONE/DARK/ACCENT × оба Boolean | Nullable plate и Boolean сохраняются отдельно. copy(darkPlate=...) влияет на fallback только при null; explicit plate не заменяется Boolean |
| Kind/Unicode | LEGACY/TITLE/PLATE/SUBTITLE; TITLE 180 code points с кириллицей/emoji; LEGACY 2000 UTF-16 units | kind/text/ID/time не меняются; TITLE 181 отклоняется, длинный LEGACY не обрезается и не становится TITLE |
| Cue confidence | null с calibrated=false; 0/.74/.75/1 × оба valid manual/calibrated flags | Сохраняются nullable finite Double и оба исходных flags; manual review не устанавливает calibration |
| Review derivation | manual=false/true, calibrated=false/true и null/.74/.75/1, только valid комбинации | needsReview вычисляется как `!manuallyReviewed && (!confidenceCalibrated || confidence==null || confidence<.75)`, не записывается отдельным полем |
| State/projection | captionsEdited, ru/en/auto, immutable lists, stable IDs; activeLayers на start/end до и после reopen, общий preview/export snapshot | Полная модель сохранена; конец исключён. Caption projection явно создаёт kind=SUBTITLE, как owner TextEditProject, а не LEGACY |
| Author operations | Изменение текста/стиля/cue через общий CAS; stale CAS; undo/redo; оба reset | Нет отдельного revision counter. RestoreMontage сохраняет author text целиком либо отклоняет операцию; RestoreAutomatic возвращает original text. Reopen сохраняет историю и export snapshot |
| Invalid payload | Unknown tags, NaN/±Infinity/вне0..1, calibrated=true при null, плохие intervals/IDs, truncated/trailing bytes и collection budgets | Ограниченный reader отклоняет данные; существующий CURRENT/retained blobs остаются целыми. Мутированные файлы — negative fixtures, не genuine legacy capture |

## Подтверждённые gaps и разблокировка

1. **Genuine a531-v4 MONO/SCALE capture отсутствует в проверенном evidence.**
   До смены encoder получить outputs неизменённого a531 writer в изолированном
   разрешённом remote/test slot. Synthetic project inputs допустимы без личного
   медиа; payload должен записать production codec, не Python/ручной binary writer.
   Результат назвать новым capture старого encoder, а не архивом, существовавшим
   до этой сверки. Сохранить producer commit/blob, входы, журнал запуска, project/revision bytes
   и SHA-256; затем permanent reader regression. Подмена header нового файла
   на v4 не закрывает этот gate.
2. **a531/15e v4 теряет plate/kind/cue evidence**: `textStyle/textState` не пишут
   эти поля (codec строки 147–153), reader применяет defaults (264–268).
   Нужны additive version upgrade и все положительные/negative проверки матрицы
   у единственного владельца codec; полный store/history/export roundtrip открыт.
3. **Caption projection теряет SUBTITLE**: `HybridTextState.kt`, blob
   `d15511b52bfdc6990146984fb6373754a0fa3d5e`, строки 27–30, создаёт слой
   без kind; default LEGACY. Owner `TextEditProject.kt:75–78` задаёт SUBTITLE.
   Нужна явная совместимая projection и regression независимо от serde.
4. **Core d5a47a0d proposal ограничивает новые tags новым форматом**; для MONO/SCALE
   это противоречит опубликованному a531 writer. Исправить proposal/reader и
   подтвердить genuine v4 regression; новый version gate относится к новым полям.

Exact-head baseline/UI `15e76210` успешны (918 JVM/285 Python/82 Android),
подробности в [PR 12](https://github.com/Veycad/Veycad/pull/12). Это checkpoint
standalone editor/STT/export, **не PASS A2x**. Эта матрица проверена чтением
immutable источников и binary evidence; новые local compile/Gradle/native/AVD
или runtime reader проверки не выполнялись.

Отдельно OPEN: genuine RU gold/holdout, акустические границы слов/calibration,
физический телефон и human review на обычной скорости. Автоматический A2x PASS
не закрывает эти gates; отсутствие корпуса не мешает обычной codec-интеграции.
