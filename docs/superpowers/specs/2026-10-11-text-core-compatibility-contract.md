# A2x — общий контракт сохранения текста

## Реализация к ревью на 20 октября 2026

Фактический main `f96dcc54` уже содержит manual payload physical6; текущий
checkpoint добавляет physical7 после root-verified genuine v4 и preceding6
capture. Историческая очередь A2a → A2t → A2x ниже больше не является START gate.
Точные producer/run/artifact/hash receipts и неизменённые bytes сохранены в
[v4 fixtures](../../../app/src/test/resources/hybrid-text-v4/README.md) и
[v6 fixtures](../../../app/src/test/resources/hybrid-text-v6/README.md).

Две реальные assertion failures на `ae8f7584` подтвердили rich serde/projection
RED; независимый v6 capture PASSED. Writer7 добавляет nullable plate после старых
style fields, kind после layer.style и nullable Double confidence + два flags
после cue times. Reader1..6 не потребляет новые bytes и применяет legacy defaults;
MONO/SCALE доступны genuine4. Caption projection явно создаёт SUBTITLE.

Единственная декларация values `TextEditProject.kt` остаётся blob `c975659e`.
Store/commands, CAS/allocator, timing/phase/source maps и schema1 не изменены.
Scope реализации: общий codec, shared projection, focused tests, genuine fixtures
и эти документы. Temporary v6 producer удалён из final source и сохранён в истории.
Требования ниже сохраняют силу, кроме помеченной исторической очереди и уже
закрытого отсутствия capture. Финальные exact-head baseline/UI и независимое
ревью ещё OPEN. Совместное source/integration review владельцем text — OPEN;
phone/STT/acoustic/render/human acceptance отдельно OPEN и не блокирует эту
реализацию codec.

## Историческое предложение от 11 октября

Статус: подготовка отдельного checkpoint, без реализации и без смены encoder. Входной acceptance contract — text PR12 `1d533e3554dae0d9f3d04286ad7dee0ad5027cc0`, `docs/integration/text-core-a2x-acceptance.md`: матрица независимо проверена владельцем и принята координатором, root прочитал её полностью из immutable Git object. Это требования к A2x, а не приёмка реализации общего codec. Type source `TextEditProject.kt` из `a53101496244fbb8476dfb314c701380a7eff80a` byte-identical прежнему reviewed `cb64b2871bcd65ba103020fa9fb16c4d1222558b` (blob `c975659e92399709935ec2d4220b9fb7ad82539f`). Root отдельно сверил unchanged a531 physical-v4 codec blob `2505cd323fc16d7eaffdbe393f3ab9760c7a94fd`: writer записывает enum.name, reader вызывает enumValueOf. Прочитаны published type/contract/legacy store values; результаты standalone text/STT CI не означают PASS A2x или native/human acceptance.

Сначала завершить текущий A2a fix/re-review и отдельный follow-up от main. A2t остаётся отдельным timing-proof этапом; A2x должен завершиться до A2b/B и передачи общего проекта галерее. Очередь реализации: A2a → A2t → A2x → A2b/B/C; оба compatibility этапа до потребителей. Окончательный BASE задаёт controller после предшествующего принятого checkpoint; новый physical format равен фактическому preceding+1, номер заранее не закреплён. Два независимых encoder не создаются.

## Одна модель

Использовать опубликованный `app/src/main/java/com/example/autoedit/TextEditProject.kt` целиком из a531/c975 blob как единственную декларацию text values, сохранив provenance. Удалить старые дубликаты TextPosition/TextFont/TextAnimation/TextStyle/TextLayer/CaptionCue из HybridTextState.kt; owner a531 уже демонстрирует эту консолидацию, но ещё не rich codec. Этот же файл содержит совместимый single-video TextEditProject value; общий HybridTextState и позднее owner layout используют его типы. Не копировать competing store, STT, Activity, exporter или общий project allocator. Не создавать альтернативный TextValues.kt или второй набор DTO. Owner runtime adapter импортируется task10 отдельно. Этот конкретный путь и patch boundary направляются текстовому владельцу на совместное ревью.

Исходные значения сохраняются целиком:

- TextStyle: position/font/sizeRatio/color/darkPlate/animation и nullable plate. plate=null сохраняет прежнее resolvedPlate и copy(darkPlate=...). Explicit NONE/DARK/ACCENT никогда не заменяется Boolean-ом.
- TextLayer: id/text/startUs/endUs/style и kind LEGACY/TITLE/PLATE/SUBTITLE. TITLE имеет owner лимит180 code points; maxLines=3 относится к layout, не к дополнительному constructor-запрету переносов. Long legacy до2000 UTF16 units остаётся LEGACY, без принудительной переклассификации. Caption projection activeLayers явно возвращает SUBTITLE; её regression проверяется отдельно от serde.
- CaptionCue: id/text/startUs/endUs, nullable finite Double confidence in0..1, manuallyReviewed и confidenceCalibrated. calibration требует non-null confidence; needsReview производный ровно как cb64 (`!manuallyReviewed && (!confidenceCalibrated || confidence==null || confidence<.75)`). Ручная отметка не меняет calibration/evidence.
- HybridTextState retains immutable layer/cue collections, captionStyle/captionsEdited/language и absolute end-exclusive project time. Этот patch не придумывает word timings или SpeechOutcome persistence DTO; task10 получает отдельный reviewed speech contract.

## Предложение формата

Отдельный явный physical-version upgrade с настоящими preceding-format project/revision fixtures, снятыми ДО encoder change. Project/store schema, authored-map canonical bytes, timing proof, allocator/CAS, source backings и published revision blobs остаются совместимыми.

Существующие enum names сохраняются: TextPosition TOP/CENTER/BOTTOM, TextFont SANS/BOLD/SERIF/MONO, TextAnimation NONE/FADE/SLIDE/SCALE. MONO/SCALE уже доступны writer a531 physical-v4, поэтому новый reader обязан принимать их в genuine v4; запрет по новой версии был бы несовместимым. Способ записи enum.name не меняется. Version gate применяется к действительно новым полям: после старых textStyle fields nullable plate enum; после style каждого layer kind enum; после старых caption fields nullable Double confidence, затем оба Boolean flags в указанном порядке. Новые enum lists NONE/DARK/ACCENT и LEGACY/TITLE/PLATE/SUBTITLE фиксируются явно. Unknown tag/non-finite/invalid combinations rejected with bounded Reader.

Readers старых physical versions не потребляют новые bytes, defaults: plate=null, kind=LEGACY, confidence=null, manuallyReviewed=false, confidenceCalibrated=false. Ни font/animation old tags, ни долгий legacy text, ни inherited darkPlate semantics не меняются. Save не переписывает старые published revision/export blobs; open не переписывает CURRENT. needsReview не сериализуется как отдельная истина.

Четыре имеющихся core v4 binary fixtures содержат пустой TextState и не доказывают MONO/SCALE. До encoder upgrade нужен новый capture старого unchanged a531 production writer в изолированном разрешённом remote slot: synthetic inputs с MONO/SCALE в layers/captionStyle, original/current/history/export, producer SHA/blob, журнал и hash/length каждого project/revision. Это новый capture старого encoder, а не якобы существовавший архив. Никакого header patch, ручного binary writer или догадки о потерянных старым writer plate/kind/cue fields. Отдельно сохраняются genuine project/revision bytes фактического preceding A2t format до его обновления.

## Границы patch и приёмка

Scope: единственная модель values + HybridTextState projection + общий HybridProjectCodec и необходимые compatibility tests/fixtures/docs. Общий store/commands меняются только если реальная regression показывает потерю значений; счётчик/CAS/undo остаются единственными. Не импортировать text owner's finishing store/Activity/STT/native encoder в compatibility checkpoint.

Meaningful RED/GREEN на реальных temp files: все новые values create→save→reopen; original/current/history и immutable export snapshot; mixed old/new values through author commands, CAS undo/redo, reset retaining/restoring correct state; plate null/copy semantics, explicit plate with opposite Boolean, MONO/SCALE, TITLE vs2000-char LEGACY; confidence null/0/.74/.75/1 с обоими исходными flags, manual mark without calibration. Genuine old fixture load/save/pruning keeps old blob bytes and defaults; invalid tags/calibration/non-finite confidence rejected before CURRENT change. Не считать reflection/API-presence или unresolved symbols meaningful RED.

Обязательны независимое scoped review, совместное owner review definitions/format/patch boundary, затем exact-head baseline/UI CI и отдельный PR. UI/STT/native MP4/human acceptance относятся к последующим runtime gates. Новый resource override: никаких новых local compile/Gradle/native/emulator/render slots; чтение готовых результатов/review/code/remote CI разрешены. До согласованного test slot capture/RED/GREEN локально не запускаются.

Матрица владельца `1d533e35` принята как входной контракт. Конкретный codec patch затем проходит независимое и совместное owner review; этот документ не подтверждает его реализацию. Готовый checkpoint пока не передаётся потребителям. Genuine RU gold/holdout, acoustic word boundaries/calibration, физический телефон и human review остаются отдельными gates.
