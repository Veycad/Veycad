# A2x — общий контракт сохранения текста, предложение для ревью владельца

Статус: подготовка отдельного checkpoint, без реализации и без смены encoder. Актуальный опубликованный/reviewed контракт text PR12 — `a53101496244fbb8476dfb314c701380a7eff80a`, `docs/integration/text-subtitles-contract.md`; прочитан root из immutable Git object. Type source `TextEditProject.kt` byte-identical прежнему reviewed `cb64b2871bcd65ba103020fa9fb16c4d1222558b` (blob `c975659e92399709935ec2d4220b9fb7ad82539f`). Для cb64 root проверил exact baseline38058502740/job114231775083 и UI38058502709/job114231775040: SUCCESS. Exact a531 baseline38150340778/job114502302476 и UI38150340742/job114502302314 пока in progress; не заявлен новый CI/native PASS. Прочитаны published type/contract/legacy store values, staged peer document не используется как опубликованная реализация.

Сначала завершить текущий A2a fix/re-review и отдельный follow-up от main. A2t остаётся отдельным timing-proof этапом; A2x организуется сейчас и должен завершиться до A2b/B и передачи общего проекта галерее. Предложенная очередь реализации: A2a → A2t → A2x → A2b/B/C; оба compatibility этапа до потребителей. Окончательный BASE/номер физического формата задаёт controller после предшествующего принятого checkpoint, не два независимых encoder. Сейчас physical5; при A2t6 новый text format будет7, при иной реальной базе использовать preceding+1.

## Одна модель

Использовать опубликованный `app/src/main/java/com/example/autoedit/TextEditProject.kt` целиком из a531/c975 blob как единственную декларацию text values, сохранив provenance. Удалить старые дубликаты TextPosition/TextFont/TextAnimation/TextStyle/TextLayer/CaptionCue из HybridTextState.kt; owner a531 уже демонстрирует эту консолидацию, но ещё не rich codec. Этот же файл содержит совместимый single-video TextEditProject value; общий HybridTextState и позднее owner layout используют его типы. Не копировать competing store, STT, Activity, exporter или общий project allocator. Не создавать альтернативный TextValues.kt или второй набор DTO. Owner runtime adapter импортируется task10 отдельно. Этот конкретный путь и patch boundary направляются текстовому владельцу на совместное ревью.

Исходные значения сохраняются целиком:

- TextStyle: position/font/sizeRatio/color/darkPlate/animation и nullable plate. plate=null сохраняет прежнее resolvedPlate и copy(darkPlate=...). Explicit NONE/DARK/ACCENT никогда не заменяется Boolean-ом.
- TextLayer: id/text/startUs/endUs/style и kind LEGACY/TITLE/PLATE/SUBTITLE. TITLE имеет owner лимит180 code points и3 строки; long legacy до2000 UTF16 units остаётся LEGACY, без принудительной переклассификации. Caption projection activeLayers возвращает SUBTITLE.
- CaptionCue: id/text/startUs/endUs, nullable finite Double confidence in0..1, manuallyReviewed и confidenceCalibrated. calibration требует non-null confidence; needsReview производный ровно как cb64 (`!manuallyReviewed && (!confidenceCalibrated || confidence==null || confidence<.75)`). Ручная отметка не меняет calibration/evidence.
- HybridTextState retains immutable layer/cue collections, captionStyle/captionsEdited/language и absolute end-exclusive project time. Этот patch не придумывает word timings или SpeechOutcome persistence DTO; task10 получает отдельный reviewed speech contract.

## Предложение формата

Отдельный явный physical-version upgrade с настоящими preceding-format project/revision fixtures, снятыми ДО encoder change. Project/store schema, authored-map canonical bytes, timing proof, allocator/CAS, source backings и published revision blobs остаются совместимыми.

Существующие enum-теги сохраняют порядок: TextPosition TOP/CENTER/BOTTOM; TextFont SANS/BOLD/SERIF получают MONO последним; TextAnimation NONE/FADE/SLIDE получает SCALE последним. Существующий способ записи enum в common codec не меняется; old-format readers сохраняют только исторически допустимые font/animation tags, новые tags разрешены с нового формата. Для новых полей в новом формате: после старых textStyle fields nullable plate enum; после style каждого layer kind enum; после старых caption fields nullable Double confidence, затем оба Boolean flags в указанном порядке. Новые enum lists NONE/DARK/ACCENT и LEGACY/TITLE/PLATE/SUBTITLE фиксируются явно. Unknown tag/non-finite/invalid combinations rejected with bounded Reader.

Readers старых physical versions не потребляют новые bytes, defaults: plate=null, kind=LEGACY, confidence=null, manuallyReviewed=false, confidenceCalibrated=false. Ни font/animation old tags, ни долгий legacy text, ни inherited darkPlate semantics не меняются. Save не переписывает старые published revision/export blobs; open не переписывает CURRENT. needsReview не сериализуется как отдельная истина.

## Границы patch и приёмка

Scope: единственная модель values + HybridTextState projection + общий HybridProjectCodec и необходимые compatibility tests/fixtures/docs. Общий store/commands меняются только если реальная regression показывает потерю значений; счётчик/CAS/undo остаются единственными. Не импортировать text owner's finishing store/Activity/STT/native encoder в compatibility checkpoint.

Meaningful RED/GREEN на реальных temp files: все новые values create→save→reopen; original/current/history и immutable export snapshot; mixed old/new values through author commands, CAS undo/redo, reset retaining/restoring correct state; plate null/copy semantics, explicit plate with opposite Boolean, MONO/SCALE, TITLE vs2000-char LEGACY; confidence null/0/.74/.75/1 с обоими исходными flags, manual mark without calibration. Genuine old fixture load/save/pruning keeps old blob bytes and defaults; invalid tags/calibration/non-finite confidence rejected before CURRENT change. Не считать reflection/API-presence или unresolved symbols meaningful RED.

Обязательны независимое scoped review, совместное owner review definitions/format/patch boundary, затем exact-head baseline/UI CI и отдельный PR. UI/STT/native MP4/human acceptance относятся к последующим runtime gates. Новый resource override: никаких новых local compile/Gradle/native/emulator/render slots; чтение готовых результатов/review/code/remote CI разрешены. До согласованного test slot capture/RED/GREEN локально не запускаются.

Подтверждение конкретного формата и границ patch ожидается через координатора и текстового владельца. Этот документ — предложение для совместного ревью; реализация общего codec ещё не выполнена, готовый checkpoint пока не передаётся потребителям.
