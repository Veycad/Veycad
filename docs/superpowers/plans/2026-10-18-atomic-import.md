# Атомарный импорт исходников — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** замена одного видео или пары DUALITY публикует только целый набор файлов, порядка и подписей; отказ и перезапуск сохраняют последний подтверждённый выбор.

**Architecture:** узкий SourceDraftStore хранит неизменяемые исходники и атомарно переключает `current.json` существующей утилитой `atomicWrite`. EditSession владеет импортом, восстановлением и очисткой; MainActivity наблюдает подтверждённое состояние. Уборка получает эксклюзивный допуск после освобождения ресурсов импорта.

**Tech Stack:** Kotlin, Java IO/NIO, Android API 26+, платформенный org.json, Executor/Handler/LiveData, JUnit 4, Espresso, PowerShell/adb, отдельный эмулятор API 36.

**Spec:** [2026-10-18-atomic-import-design.md](../specs/2026-10-18-atomic-import-design.md), одобрена владельцем 18 октября 2026 года; [PR #55](https://github.com/Veycad/Veycad/pull/55), `662612933614d27334ecf8aa25816262c5b32e6c`. Способ исполнения уже выбран: native, затем одно независимое финальное ревью. Сам план ожидает ревью; реализация не начата.

## Global Constraints

- Только пункт 2; зависимость — пункт 1, `ffac1cb32ab5872e673fd53984c10515bf076cea`, PR #52. PR #50–52 и #55 не сливать без отдельного разрешения.
- Авторитетный файл — `filesDir/source-draft/current.json`, не более `64 KiB` (`65_536` байт); список содержит `0–2` элемента, пустой список — явный сброс.
- Новые имена — `source-0-<uuid>.mp4`, `source-1-<uuid>.mp4`; UUID общий для набора, индексы непрерывные. Байты сохраняются без перекодирования и определения контейнера по расширению.
- Происхождение — `import`/`legacy`; legacy разрешён только при миграции отсутствующего манифеста. UUID набора и process-local `draftGeneration` различны.
- Только обычные непустые файлы внутри root; длина совпадает с манифестом. Запрещены обход пути, symlink/junction, угадывание выбора при повреждении и неатомарный fallback.
- Не добавлять БД, project store, renderer, scheduler, polling, периодический таймер, кнопку отмены импорта или автоматическое продолжение копирования после смерти процесса.
- Preferences служат зеркалом совместимости; ошибка зеркала, GC или observer после commit не откатывает выбор. Неопределённый commit сохраняет оба набора и блокирует рендер.
- Очистка сначала фиксирует пустой манифест, затем подтверждает внешний `cleanup_incomplete`, только затем удаляет. Валидный новый манифест имеет приоритет над старым marker.
- GC требует действующего эксклюзивного cleanup lease и повторного чтения current; неизвестные файлы, проекты, результаты, text-sources, capture и provider-файлы сохраняются.
- Локальная обработка без аккаунта и сети. Production-зависимости не расширять; JVM-совместимая org.json допускается только в testImplementation.
- Один связный коммит на задачу с необходимыми тестами; именованный staging, просмотр cached diff, PR в draft до завершения приёмки. Не трогать грязный основной checkout AutoEdit.

## Review Focus

1. После render/save `State.draft == null`, хотя выбор на диске есть: неуспешная замена возвращает прежние bytes, подписи, entry и готовность — задача 3, `failedReplacementAfterRenderRestoresConfirmedDraft`.
2. Падение после физической замены, но до возврата функции: новый набор нельзя удалить; недоступное контрольное чтение блокирует рендер — задачи 1 и 3, `throwAfterReplaceKeepsCommittedSources`, `unreadableCommitStatusBlocksRender`.
3. Отсутствие манифеста при первой recreation: подготовленные файлы нельзя принять за legacy — задачи 2 и 3, `ambiguousLegacyGetsTombstoneBeforeCandidate`, `firstImportRecreationNeverSelectsStaging`.
4. Имена с Unicode/кавычками/переводами строк и превышение лимита метаданных: round-trip без использования имён как путей; превышение 65_536 байт не меняет выбор — задача 1, `displayNamesRoundTripAndOversizeIsRejected`.
5. Callback заканчивается раньше worker либо бросает исключение: GC ждёт все leases, observer не откатывает commit — задача 3, `earlyCallbackCannotCollectWorkerSources`, `observerFailureAfterCommitPreservesSelection`.

---

## Карта файлов и порядок коммитов

Все Kotlin-файлы ниже имеют package `com.veycad.app`, несмотря на физический каталог `com/example/autoedit`. Обозначения `M`, `J`, `A`, `U` далее раскрываются однозначно:

- `M` = `app/src/main/java/com/example/autoedit`.
- `J` = `app/src/test/java/com/example/autoedit`.
- `A` = `app/src/androidTest/java/com/example/autoedit`.
- `U` = `app/src/uiTest/java/com/example/autoedit`.

| Коммит | Ответственность | Зависимость |
| --- | --- | --- |
| 1 | Манифест, staging и классификация результата commit | существующие atomicWrite/ownedChild/coordinator |
| 2 | Legacy, tombstone и безопасная уборка | 1 |
| 3 | Полный пользовательский путь EditSession/MainActivity/cleanup и Android-регрессии | 1–2 |
| 4 | Настоящий process-death gate и его включение в CI | 3 |

Первые два коммита добавляют проверяемое хранилище без переключения production-loader. Переключение импорта, loader и cleanup выполняется вместе в третьем: промежуточного production-состояния с двумя конкурирующими источниками истины быть не должно.

## Подготовка исполнения

- [ ] Проверить status/ветку изолированного клона `C:/Users/rexar/Documents/Codex/reviews/veycad-architecture-20261015`; получить свежие remote refs spec/plan/пункта 1. Создать `codex/atomic-import` от утверждённой удалённой ветки плана. Если зависимости изменились, сначала сопоставить diff с одобренной спецификацией.
- [ ] Проверить JDK 25, Android SDK, Python для существующего baseline и LFS. Работать только на выделенном `emulator-5556` / `AutoEditUi_API36`; не устанавливать и не очищать production-пакет `com.veycad.app`.
- [ ] Для RED запусков использовать `:app:testDebugUnitTest --tests 'com.veycad.app.<Class>'`; Android RED запускать адресно через изолированный test APK и существующий runner/listener. Сохранять точную команду, XML и причину отказа в игнорируемом `build/reports/atomic-import/`; compilation error из-за нового API допустим только на первом шаге, поведенческую регрессию затем воспроизвести assertion failure.

### Task 1: Манифест и атомарная публикация целого набора

**Files:**
- Create: `M/SourceDraftManifest.kt` — фиксированная схема и строгая проверка типов/размеров.
- Create: `M/SourceDraftStore.kt` — только выбор исходников, staging и commit.
- Test: `J/SourceDraftManifestTest.kt`, `J/SourceDraftStoreTest.kt`.
- Modify: `app/build.gradle.kts` — только `testImplementation("org.json:json:20240303")` для JVM-тестов платформенного JSON API; не использовать Android mock-return-defaults.
- Reuse without replacement: `M/AnalysisSidecarStore.kt` (`atomicWrite`, `ownedChild`, `readBytesBounded`).

**Interfaces (internal, nested types принадлежат указанному владельцу):**
- `SourceDraftManifest.Entry(fileName: String, displayName: String, length: Long)`; `SourceDraftManifest.Snapshot(batchId: UUID, origin: Origin, entries: List<Entry>)`; `Origin { IMPORT, LEGACY }`.
- `SourceDraftManifest.encode(snapshot: Snapshot): ByteArray`, `decode(bytes: ByteArray): Snapshot`; `VERSION = 1`, `MAX_BYTES = 65_536`. JSON keys: `version`, `batchId`, `origin`, `sources`; у элемента `fileName`, `displayName`, `length`. Enum на диске — lowercase. Никаких числовых/строковых coercions.
- `SourceDraftStore(filesDir: File, io: Io = DiskIo)`; root вычисляется внутри из `filesDir`, не передаётся произвольным путём и не создаётся через небезопасный mkdir до проверки родителей.
- `Input(displayName: String, open: () -> InputStream)`; `read(): Snapshot?` возвращает null только при отсутствии current, некорректный/нечитаемый current бросает `IOException`.
- `replace<T>(inputs: List<Input>, expectedCount: Int, beforeCommit: () -> T): ReplaceResult<T>`; `beforeCommit` готовит музыку и возвращает её, UI её не получает до commit. Вызывать после `restore(legacy)` задачи 2; отсутствие current к началу staging — ошибка контракта. Повреждённый существующий current допускает успешную полную замену.
- `ReplaceResult<T>`: `Published<T>(snapshot: Snapshot, prepared: T, warnings: List<String>)`, `Rejected<T>(failure: Throwable)`, `Uncertain<T>(failure: Throwable)`; результат не скрывает ошибку отката. При Uncertain не удалять ни один потенциально выбранный набор.
- `Io.copyAndSync(input: InputStream, target: File): Unit`, `replaceManifest(target: File, bytes: ByteArray): Unit`, `readManifest(target: File): ByteArray`, `checkpoint(phase: Phase): Unit`; production `DiskIo` использует FileOutputStream/use/fd.sync, bounded read и существующий atomicWrite. `Phase { FILES_PREPARED, MANIFEST_COMMITTED }`: первая точка после готовности финальных файлов и возврата beforeCommit, вторая сразу после успешного atomicWrite, до возврата replace. Инъекция — internal constructor seam, не глобальная production-настройка.

- [ ] **Step 1: Добавить RED тесты codec и реальных файлов.** Fixtures используют разные байты A0/A1 и B0/B1; каждый контрольный read делает новый SourceDraftStore. Основные assertions:

```kotlin
// oldSelectionSurvivesEveryPreCommitFailure: первый/второй input, write,
// sync, music и AtomicMoveNotSupportedException — отдельные случаи.
assertEquals(oldSnapshot, freshStore.read())
assertArrayEquals(old0, oldFile0.readBytes())
assertArrayEquals(old1, oldFile1.readBytes())
// throwAfterReplaceKeepsCommittedSources; inject atomicWrite(); throw IOException().
assertEquals(newBatchId, freshStore.read()!!.batchId)
assertArrayEquals(new0, selectedFiles(freshStore).first().readBytes())
// unreadableCommitStatusPreservesBothSets
assertTrue(result is SourceDraftStore.ReplaceResult.Uncertain<*>)
assertTrue((oldFiles + candidateFiles).all(File::exists))
// displayNamesRoundTripAndOversizeIsRejected
assertEquals(namesWithUnicodeQuotesAndNewlines, decoded.entries.map { it.displayName })
assertTrue(encoded.size <= 65_536)
assertEquals(oldSnapshot, freshStore.read()) // кандидат с metadata > 65_536
```

Добавить `invalidManifestsNeverGuessSources`: invalid JSON/UTF-8, version 2, >2 источников, дубликаты путей, отрицательная/нулевая/дробная длина, неверный UUID/индекс/порядок, import с разными UUID, `../`, absolute/drive paths, source/root symlink и junction, неверная длина, 65_537 байт. За пределами root создать sentinel и доказать неизменность. JVM junction-проверку на Windows выполнять настоящим junction, Android symlink — в задаче 3; отсутствие возможности создать ссылку нельзя выдавать за PASS этой проверки. В task 1 начальный current сеять fixture через существующий atomicWrite; production-bootstrap добавляется в task 2. Проверить закрытие обоих input/output при частичном IO-отказе.

- [ ] **Step 2: Запустить оба новых JVM-класса и зафиксировать RED.** После появления API повторить fault matrix, убедившись в assertion failure на преждевременной публикации/удалении, а не только в ошибке компиляции.
- [ ] **Step 3: Реализовать encode/decode/read/replace.** Строгое чтение JSON и UTF-8, отсутствие coercion, bounded read при росте файла; каждый путь проверяется NOFOLLOW и сравнением canonical/absolute normalized начиная с root. Snapshot защитить от изменения входных списков. Обычный импорт никогда не принимает origin от вызывающего кода.
- [ ] **Step 4: Реализовать staging и границу commit.** Использовать `.staging-<uuid>` под root, синхронизировать и закрыть каждый файл, проверить положительную длину, перенести в уникальные плоские имена без overwrite; подготовить музыку, затем один atomicWrite. Финальные файлы не публиковать отдельно. При ошибке около commit перечитать current и сравнить UUID: candidate → Published, прежний/исходный повреждённый документ без замены → Rejected, невозможно определить → Uncertain. Подготовленное значение T сохранить для Published даже при исключении после replace. Rollback касается только собственной неопубликованной попытки; неуспешное удаление входит в ошибку/предупреждение.
- [ ] **Step 5: Проверить GREEN.** Запустить новые JVM-классы и `AnalysisSidecarStoreTest`; дополнительно проверить no-selection/empty input/wrong count/duplicate inputs после нормализации владельцем, отсутствие передачи staging в выбранные пути и отсутствие fallback при неподдерживаемом atomic move.
- [ ] **Step 6: Просмотреть diff, добавить только перечисленные файлы, проверить cached diff и создать** `feat(storage): добавить атомарное хранилище исходников`.

### Task 2: Миграция, явный сброс и GC по владению

**Files:**
- Modify: `M/SourceDraftStore.kt`.
- Create: `M/SourceDraftRecovery.kt` — только legacy-классификация и правила владения остатками.
- Test: `J/SourceDraftRecoveryTest.kt`, `J/SourceDraftStoreTest.kt`.
- Consume without changing semantics: `M/MediaWorkCoordinator.kt`, `M/TemporaryFileCleanup.kt`.

**Interfaces:**
- Consumes: Snapshot/Entry/read/replace из задачи 1; `Lease.requireCleanupActive()` и `tryBeginCleanup()` из пункта 1.
- Produces: `SourceDraftStore.LegacyState(firstName: String?, secondName: String?, cleanupIncomplete: Boolean)`; `Restored(snapshot: Snapshot, warning: String?)`.
- `restore(legacy: LegacyState): Restored` читает current или закрепляет legacy/пустой выбор; warning обозначает неоднозначный legacy. Повреждённый существующий current остаётся ошибкой, его не перезаписывать при restore.
- `invalidate(lease: MediaWorkCoordinator.Lease): Snapshot` атомарно публикует пустой import-манифест с новым UUID; допускается исправление повреждённого current подтверждённой ручной очисткой.
- `collectObsolete(lease: MediaWorkCoordinator.Lease, previouslyOwned: Set<String> = emptySet()): List<String>` возвращает ошибки удаления, обязательно перечитывает current под lease. Это не `TemporaryFileCleanup.clear(SOURCE_DRAFT)`.
- Helper types SourceDraftRecovery не выходят за store; public owner API выше достаточно для задачи 3.

- [ ] **Step 1: Добавить RED тесты миграции и GC.** Имена тестов и обязательные assertions:

```kotlin
// legacyPairPreservesOrderWithoutCopy
assertEquals(listOf(file0.name, file1.name), restored.snapshot.entries.map { it.fileName })
assertArrayEquals(old0, file0.readBytes())
// ambiguousLegacyGetsTombstoneBeforeCandidate; mixed UUID, orphan index 1,
// неполная известная пара, >2 файла, links, неоднозначные прямые legacy-имена.
assertTrue(freshStore.read()!!.entries.isEmpty())
assertNotNull(restored.warning)
assertTrue(legacyFiles.all(File::exists))
// tombstoneAndCleanupMarkerPreventResurrection
assertTrue(store.restore(legacyWithCleanupMarker).snapshot.entries.isEmpty())
// committedManifestWinsOverStaleMarker
assertEquals(newBatchId, store.restore(legacyWithCleanupMarker).snapshot.batchId)
// collectionRequiresExclusiveAdmissionAndPreservesUnknown
assertThrows(IllegalStateException::class.java) { store.collectObsolete(workLease) }
assertTrue(currentFiles.all(File::exists))
assertTrue(unknownFile.exists())
assertFalse(ownedObsoleteFile.exists()) // только после действующего cleanup lease
```

Отдельно проверить закрытый/поддельный lease, повреждённый current → ни одного удаления, недоступное чтение → тот же результат; stale snapshot не разрешает удалить новый current. Проверить произвольный legacy-path: удаляется по снимку прежней операции, после restart без этого свидетельства сохраняется. Защитить completed/projects/text/capture/provider sentinels.

- [ ] **Step 2: Запустить `SourceDraftRecoveryTest` и увидеть RED по каждому новому контракту.**
- [ ] **Step 3: Реализовать restore.** При отсутствии current сначала прочитать legacy preferences-снимок, классифицировать только прямые обычные непустые медиа; одна coherent source допустима, если не orphan source-1 и не известная пара; source-0/1 пара требует общий UUID. Произвольные два legacy-имени не сортировать для угадывания. Пустой каталог/marker/неоднозначность закрепить пустым current до candidate preparation. Согласованный набор закрепить с origin legacy без копирования. Отказ записи bootstrap останавливает импорт до плоских новых файлов и изменения preferences.
- [ ] **Step 4: Реализовать invalidate и collectObsolete.** Проверить lease до файлового доступа; перечитать current; удалять только точные имена `source-{0,1}-<uuid>.mp4`, собственные `.staging-<uuid>` и `.pending-*.tmp` атомарной записи либо ранее подтверждённые прямые legacy-пути. Проверять root/каждый удаляемый путь, не обходить ссылки, не удалять current.json/активные/неизвестные файлы. Невозможность подтвердить активный набор запрещает весь media GC.
- [ ] **Step 5: Запустить GREEN задач 1–2.** Проверить fault bootstrap/invalidate/GC, отсутствие копирования старых больших файлов, отсутствие полного hash/decode на restore. Все удаления проверять реальными bytes/sentinels, не только mock-вызовами.
- [ ] **Step 6: Просмотреть named cached diff и создать** `feat(storage): добавить миграцию и безопасную уборку исходников`.

### Task 3: Подключить владельца сессии, UI и ручную очистку

**Files:**
- Modify: `M/EditSession.kt`, `M/MainActivity.kt`, `M/MediaWorkDispatch.kt`.
- Modify: `U/UiTestApplication.kt`, `U/UiFixtureProvider.kt`, `A/UiTestFixtureRule.kt` — контролируемые IO/барьеры и helper авторитетного выбора.
- Create: `A/AtomicImportDeviceTest.kt`.
- Test/modify: `J/MediaWorkDispatchTest.kt`; `A/DirectorScreenTest.kt`, `A/FeatureIntegrationTest.kt`, `A/SettingsScreenTest.kt`, `A/RuntimePermissionsTest.kt`, `A/ResultScreenTest.kt`, `A/RenderProgressScreenTest.kt`, `A/MediaWorkSessionDeviceTest.kt`.

**Interfaces:**
- Consumes: API SourceDraftStore задач 1–2; существующие `importVideos(uris: List<Uri>, style: MontageStyleCatalog.Style)`, `clearDraft(): Boolean`, `clearTemporaryFiles(): Boolean` сохраняются.
- Produces: `EditSession.restoreDraft(style: MontageStyleCatalog.Style): Unit`, `State.sourceSelectionConfirmed: Boolean = false`; существующий Draft остаётся files/names/music. Подтверждение хранится также владельцем независимо от временного отсутствия State.draft после render/save; все публикации получают актуальное значение, как draftGeneration.
- Добавить параметры constructor EditSession: `sourceStore: SourceDraftStore = SourceDraftStore(context.applicationContext.filesDir)`, `selectMusic: (MontageStyleCatalog.Style) -> BuiltInMusicCatalog.Selection = { BuiltInMusicCatalog.select(context.applicationContext, it.id) }`. Существующие параметры/позиционные вызовы сохранить.
- Добавить в MediaWorkDispatch `executeAfterRelease(afterRelease: () -> Unit, action: () -> Unit): Unit`, `postAfterRelease(afterRelease: () -> Unit, action: () -> Unit): Unit`; существующие execute/post сохраняются. Hook вызывается в finally после закрытия именно своего lease, в том числе при rejected queue и исключении action; ошибка hook не заменяет первичную ошибку.
- `EditSession.tryCollectSourceObsolete(): Unit` — private, без выбрасывания ошибок в успешный импорт. Pending-флаг и снимок previouslyOwned сохраняются до успешной попытки; вызовы после worker/callback/внешнего withMediaWork lease и при следующем естественном idle/recovery. Никаких новых очередей/таймеров.
- `UiTestFixtureRule.selectedSources(): List<File>` читает store snapshot и проверяет его; helper не скрывает ошибки manifest. `UiTestApplication.sourceIo` и `musicSelector` — test-only настройки, передаются новому owner и сбрасываются только при реальном отсутствии всех leases. Provider поддерживает точечный отказ/pipe второго источника для ошибки в середине чтения.

- [ ] **Step 1: Добавить Android RED через picker и настоящие очереди.** Для `secondInputFailurePreservesReadyDraftAndEntry` сравнить old bytes/порядок/подписи/entry и включённую renderButton после отказа. `failedReplacementAfterRenderRestoresConfirmedDraft` повторяет это после State.draft=null. `firstImportRecreationNeverSelectsStaging` и `recreationDuringReplacementShowsOldSelection` блокируют второй input настоящим IO-барьером; проверяют старый выбор и busy, затем новый целый выбор после разблокировки. DUALITY после commit/recreate/style 2→1→2 сохраняет оба источника.
- [ ] **Step 2: Добавить RED для неизвестного commit и postcommit отказов.** `unreadableCommitStatusBlocksRender`: оба набора существуют, UI явно показывает неподтверждённый выбор, renderButton disabled, прямой session.render тоже отвергнут; успешный последующий restore снимает блок. `observerFailureAfterCommitPreservesSelection`, prefs mirror failure и GC failure сохраняют новый UUID/bytes и освобождают только свои leases. Повторить успешную замену повреждённого current и повреждённый current + неуспешную замену без потери исходных файлов/документа.
- [ ] **Step 3: Добавить RED cleanup/dispatch.** `invalidationFailureDoesNotDeleteSources`, `markerFailureAfterInvalidationRetainsTombstoneAndFiles`, partial delete + restart, import со stale marker, очистка во время copy и import во время cleanup. В JVM `earlyCallbackCannotCollectWorkerSources` исполнить callback до возврата worker; GC разрешён лишь после последнего собственного lease. Проверить Snapshot(0,false) после ошибок executor/handler/observer, чужой lease остаётся активным. Для Android link/root escape создать реальные symlink и сохранить sentinel.
- [ ] **Step 4: Запустить адресные Android/JVM RED и сохранить XML/assertions.** Picker, IO и MainActivity остаются реальными; mock renderer не считается проверкой MP4. Точки остановки не заменять sleep: использовать bounded latch/очередь.
- [ ] **Step 5: Перевести importVideos на replace.** Получить обычный допуск до изменения State/постановки в executor, проверить busy, `uris.distinct()` с порядком первого вхождения и ровно style.sourceCount (1/2). Отказ не меняет selection/preferences/entry. Удерживать previous State и подтверждённый store snapshot даже без previous Draft; восстановить музыку до попытки, если она нужна для старой готовности. Bootstrapping выполнить до подготовки новых плоских файлов. Published публикует весь новый Draft; Rejected — прежний; Uncertain — явная ошибка/false confirmation без удаления. Compatibility mirror только после Published, commit() проверяется, отказ — предупреждение. Сохранить прежний completed entry.
- [ ] **Step 6: Перевести восстановление и render gating на владельца.** restoreDraft работает под обычным допуском и публикует через State; busy owner возвращает имеющийся подтверждённый выбор без конкурирующего restore. Для async restore проверять request token + batch/state change перед публикацией, чтобы старый callback не заменил новый import/cleanup. Удалить raw list/sort и prefs-join из MainActivity.restoreSourceDraft; Activity только запрашивает restore и отображает State, не пишет и не удаляет. Пустой/invalid выбор очищает локальные source-поля. UI и прямой gallery render требуют sourceSelectionConfirmed; съёмку с собственным capture-входом не блокировать этим gallery-флагом. Store/Draft сохраняют оба источника при стиле на одно видео.
- [ ] **Step 7: Подключить очистку и отложенный GC.** Под exclusive lease сначала invalidate; только после его успеха считать draft touched и увеличить generation, независимо от результата удаления. Затем checked marker commit; при его отказе не удалять ничего, сохранить tombstone и сообщить ошибку. После этого существующая whitelisted очистка; marker удалять лишь после подтверждённого полного удаления source-draft. GC успешного импорта запускать через afterRelease + внешний finally; если другой worker активен, сохранить pending до следующего естественного idle/recovery. Во время import worker/main callback хранить непрерывное владение, не создавать idle gap. Не вызывать observer/file API под монитором coordinator.
- [ ] **Step 8: Обновить старые тесты без ослабления.** Проверки количества выбранных медиа читать через selectedSources вместо root.listFiles (теперь root содержит current.json). Отдельно продолжить проверять физическое удаление obsolete и отсутствие `.partial`/staging после normal idle. Empty-selection assertions не приравнивать к пустому root. Сохранить native MP4-тест пункта 1, bytes-защиту остальных хранилищ и полный список существующих тестов.
- [ ] **Step 9: Получить GREEN.** `MediaWorkDispatchTest` плюс JVM задач 1–2; адресные AtomicImportDeviceTest/DirectorScreenTest/SettingsScreenTest/MediaWorkSessionDeviceTest с проверкой полного XML и итогового реестра leases. Ошибки observer ловить на контролируемой границе теста, не убивая весь runner и не выдавая отсутствие отчёта за успех.
- [ ] **Step 10: Просмотреть named cached diff и создать** `fix(storage): подключить атомарный импорт к сессии и восстановлению`.

### Task 4: Проверить смерть процесса и включить gate в CI

**Files:**
- Create: `U/UiImportRestartProbeActivity.kt` — только тестовый управляющий вход.
- Move: `A/SyntheticVideo.kt` → `U/SyntheticVideo.kt`, чтобы существующие instrumentation-тесты и probe использовали один генератор только в test APK; сохранить прежний API и все проверки MediaCodec.
- Modify: `U/UiTestApplication.kt`, `app/src/uiTest/AndroidManifest.xml`.
- Create: `tools/run_atomic_import_restart_tests.ps1`.
- Modify: `tools/run_ui_tests.ps1`, `.github/workflows/android-ui.yml`.
- Create: `docs/testing/atomic-import.md` — воспроизведение и границы доказательств.

**Interfaces:**
- Consumes: production EditSession/SourceDraftStore, Io.checkpoint из задачи 1, реальный UiFixtureProvider и MainActivity.
- Probe: uiTest-only Activity `.UiImportRestartProbeActivity`, exported только в `.uitest` manifest; extras `command=prepare|replace|verify`, `phase=FILES_PREPARED|MANIFEST_COMMITTED`, `runId=<uuid>`. Не принимать произвольные пути/package. Runtime guard требует точный `com.veycad.app.uitest`; компонент отсутствует в main/debug/release production manifest.
- Probe записывает `files/import-restart-probe/<runId>/checkpoint.json` с pid/runId/phase/old/new batch UUID и **реальными ожидаемыми bytes/hash/order/names**, после дискового действия и fsync самого checkpoint; после сигнала worker блокируется. Fixtures — `SyntheticVideo.create` с durationMs 1_000/2_000 для A и 3_000/4_000 для B, имена a0/a1/b0/b1; сравнить фактические bytes и убедиться, что наборы различны. Не hash production-видео при запуске Activity. `verify` стартует MainActivity с обычным owner restore, пишет отдельный verdict.json; host независимо проверяет UI dump/renderButton, selected metadata и bytes через run-as. Probe не создаёт собственный production-loader.
- Script параметры: `-Serial 'emulator-5556' -AvdName 'AutoEditUi_API36' -SdkRoot ''`. Только установленный проверенный test APK. Итог `build/reports/ui-tests/atomic-import-restart.json`: две обязательные строки phase, checkpointPid, restartedPid, selectedBatch, byteChecks, uiChecks, status; при отсутствующей точке/расхождении — ненулевой exit и FAIL/OPEN, не PASS.

- [ ] **Step 1: Добавить отдельный host RED gate.** Сначала до реализации probe gate обязан отказать по отсутствию свежего checkpoint/verdict. Затем внедрить намеренно неверное ожидаемое bytes/order или фазу в локальном тестовом запуске и доказать ненулевой exit; не коммитить поломанную fixture. Не добавлять обычные JUnit tests, которые намеренно убивают собственную instrumentation: существующий full-suite discovery не должен пропускать/исключать тесты ради этого gate.
- [ ] **Step 2: Реализовать probe поверх реального owner.** prepare создаёт подтверждённую пару A; replace запускает импорт пары B и блокируется на нужном post-disk checkpoint. Сигнал после FILES_PREPARED означает final files готовы и музыка подготовлена до pointer replace; MANIFEST_COMMITTED — atomicWrite уже вернулся, preferences/State/GC ещё не выполнены. verify в новом процессе использует обычное восстановление и показывает director; тестовые управления не сохраняют состояние singleton через kill.
- [ ] **Step 3: Реализовать host orchestration с ограниченными ожиданиями.** Проверить serial/AVD/API/package/APK metadata перед изменениями; удалить только отчёты своего runId. Для каждой фазы дождаться свежего fsynced checkpoint и живого ожидаемого PID, выполнить `am force-stop com.veycad.app.uitest`, подтвердить отсутствие процесса, запустить verify, подтвердить новый PID. До commit выбран A, после commit — B; сравнить оба источника, порядок, подписи, ready UI и отсутствие смеси. Screenshot/UI dump/checkpoint/verdict архивировать. finally снять тестовые барьеры/остановить только test-пакет, не трогать пользовательский эмулятор и production-пакет.
- [ ] **Step 4: Включить gate в общий UI-запуск.** После завершённых instrumentation-фаз вызвать новый script, включить его результат в общий summary и не скрывать exit code. Workflow архивирует reports и при отказе; увеличить timeout job с 30 до 45 минут, чтобы отдельные старты не вытеснили существующий полный набор. Не снижать completeness-проверки XML и не заменять настоящие missing/skips на успех.
- [ ] **Step 5: Выполнить финальную проверку последнего дерева.** Сначала `.\gradlew.bat clean baselineVerify`, затем `pwsh -NoProfile -File tools/run_ui_tests.ps1 -Serial emulator-5556 -AvdName AutoEditUi_API36`. После clean при необходимости заново собрать UI APK штатным script. Проверить JVM/Python/lint/security/APK gate, все ожидаемые XML без failures/errors/skips/missing/duplicates и оба process-death verdict. Не использовать прежние количества тестов как актуальный результат; посчитать текущий набор. Отдельный launch нового JVM-store или recreate Activity не закрывает process-death gate.
- [ ] **Step 6: Просмотреть named cached diff и создать** `test(storage): проверить атомарный импорт после смерти процесса`.

## Финальное ревью и публикация

- [ ] Выполнить одно независимое ревью всей реализации против утверждённых spec/plan, особенно uncertain commit, cleanup marker и lease lifetime. Исправления сохранить отдельным связным fix-коммитом; повторить затронутые проверки и полный обязательный gate при изменении runtime-контракта.
- [ ] Отправить ветку, открыть/обновить draft PR с базой утверждённой ветки плана, прикрепить PR к чату. Указать точные SHA зависимостей и последнего проверенного коммита, RED/GREEN, архивы, два настоящих process-death результата, ограничения и результаты независимого ревью.
- [ ] Проверить `baseline` и `ui` CI именно последнего SHA. Незавершённую/недоступную проверку оставить OPEN и PR в draft. Проверки физического устройства и художественная приёмка отдельно OPEN; атомарность не означает проверку монтажного качества или обесточивания накопителя.
- [ ] Передать владельцу результат и ссылки. Одобрение плана разрешает реализацию выбранным native-способом, но не merge.

## Самопроверка плана

Контракт manifest/staging/commit/error покрыт задачей 1; legacy/tombstone/ownership — задачей 2; допуск, полный State, restore, стиль, cleanup и terminal callbacks — задачей 3; отдельная смерть процесса и финальные gates — задачей 4. Все пять Review Focus связаны с именованными регрессиями. Сигнатуры задач согласованы; helper-псевдонимы M/J/A/U раскрыты выше. Production-код в рамках подготовки этого документа не меняется, перечисленные проверки ещё предстоит выполнить.
