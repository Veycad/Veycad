# Совместимые checkpoints общего ядра

Дополнение к одобренному [плану гибридного режима](2026-10-10-hybrid-mode.md). Основание: делегированное владельцем решение общего координатора — сохранить все одобренные возможности галереи, ручного монтажа, текста и Live Scrubbing, с единственным project/store/revision API в PR #15. Это последовательные интеграционные задачи с отдельными коммитами и независимым ревью каждой части. Статус: планируемые API, реализации ещё нет.

Выполнять после задачи 3 основного плана до подключения потребителей общего проекта. Задача A1 сохраняет модель/codec источников, форматов, текста и gain; A2 отдельно подключает reviewed manual payload, точную фазу и durable backing. B добавляет staging и migration; C добавляет runtime/leases и deletion/recovery. Полные локальные baseline/UI/emulator проверки остаются в общей очереди; целевые JVM — только `--max-workers=1`, полный CI — для точного коммита.

## A1. Полная модель выбранных источников, форматов и текста

Входные checkpoints (статус ревью указан отдельно):

- Ядро PR #15: модель `74b58038`, storage с защитой исторических исходников `b6f860b5`, команды из задачи 3 после независимого ревью.
- Live Scrubbing: `df81b2d2bbaf559b10439648830f7516914ea677` (public format/geometry/framing values и read-only DraftProject); `3e72c4fe93d0c47015ae6e171d1df3997624cea9` (framing geometry), задачи независимо одобрены. На этом checkpoint SourceId ещё описан как asset ID; адаптация ниже исправляет его на selection ID.
- Текст: committed `50da56de6f1c5d3da5b241c5f5daa3eebe4577a9`, `TextEditProject.kt` содержит public TextStyle/TextLayer/CaptionCue. Чтение кода подтвердило поля; самостоятельное ревью этого DTO checkpoint здесь пока не подтверждено, импортированные declarations включить в независимое ревью A. Более поздние файлы владельца могут быть WIP, их не переносить как проверенную реализацию.
- Галерея: committed `2f0e290a5877daaa66939d1c3d81094d84623cfa` содержит MediaSource и MediaSourceSet; просмотр этого checkpoint не заменяет независимое ревью. Core не создаёт конкурирующий MediaSource. Изменения инспектора после него пока не считать принятыми.

Scope A1: HybridProject/model, backward-compatible codec, source/revision validation, public DTO dependencies/adapters и соответствующие JVM tests. Optional manual graph payload, backing после pruning и shared map storage относятся к A2 ниже. При импорте public value declarations сохранять поля и диапазоны оригинального владельца, указать commit provenance. Не переносить соседние Activity/store/render/STT реализации или пользовательские видео. Pending manual payload types из WIP не считаются проверенной зависимостью.

Требуемые свойства:

1. Долговечная упорядоченная таблица selected videos: `sourceIndex → selectionId/assetId`. Индекс равен позиции, selection ID уникален, asset ID может повторяться. Перестановка клипов не меняет таблицу. Не объединять два выбранных элемента из-за одинакового fingerprint.
2. Геометрия: encoded width/height, rotation 0/90/180/270, pixel aspect ratio; реальная длительность/size, mime, color transfer, audio availability/fingerprint; ownership IMPORTED/CAPTURE и CaptureOrigin. Новые imports требуют реальные метаданные. Старые проекты не получают придуманные геометрию/порядок: неизвестные данные должны оставаться явно неизвестными до проверенного восстановления.
3. Таблица после первой сборки неизменяема; добавление/удаление выбранных видео делается fork/staging новым project ID. Asset records остаются неизменяемыми; необходимые исторические bindings сохраняются. Расширение метаданных не обходит эту гарантию.
4. `HybridRevision.visualSettings` содержит полный ProjectVisualSettings: aspect, explicitlySelected, framing map и параметры всех трёх режимов. FramingKey.SourceId теперь означает выбранный элемент; две вставки одного файла имеют независимые настройки. Все 4 одобренных aspect сохраняются; вертикальный холст остаётся общим default гибридного режима, recipe default из reviewed ProjectFormats учитывает factory. Preview DraftProject — read-only адаптер к этой модели, без своего counter/store.
5. `HybridRevision.textState` (название закрепить в проверенном коммите) сохраняет TextLayer, CaptionCue, captionStyle, captionsEdited, language auto/ru/en полностью. Существующие нормализованные TextItem сохраняются. Защитить containers, copy, equality/hashcode; строки и интервалы валидировать по опубликованным owner DTO. Cues/layers используют absolute projectTimeUs [start,end), а не локальное время preview window.
6. Original/current/undo/redo и loadRevision/export snapshots сохраняют эти поля. `commitRevision` остаётся единственной внешней точкой выделения ID. Нельзя импортировать внешнюю историю или назначать свои IDs.
7. Codec читает прежние v1 и phase-version данные; неизвестную версию отвергает без rewrite. До изменения encoder получить настоящие fixtures. Canonical revision sharing и различение immutable original/current payload сохранить. Старые graphs/clocks/source samples не меняются.
8. `ProjectMusic.gain` расширить до legacy `MontageGraph.AudioTrack` диапазона 0..2 без clamp. Factory/codec/no-op сохраняют фактическую громкость, включая gain1.5 и2; UI может отдельно показывать этот диапазон. Аудиорендер в задаче 8 обрабатывает headroom/пики явно, не подменяя сохранённое значение.
Meaningful A1 tests: repeated-asset selections roundtrip; clip reorder keeps source indices; independent framing for duplicate selections; geometry/capture provenance; full rich text/captions/language roundtrip through reopen/undo/redo/export revision; external commit allocates core ID; old fixtures load without rewrite or fabricated metadata; mutate caller collections does not mutate model; legacy gain1.5/2 no-op and codec roundtrip. Verify encoder/decoder point limits agree. Run changed model/codec/store/command classes and imported DTO tests.

Commit: `feat(storage): сохранять полный общий проект источников форматов и текста`.

## A2. Manual payload, точная фаза и долговечные source maps

Dependency status checked 2026-10-10: manual pure compiler `67e730cb936f4f5f7f63a38bfe6c81611189f7cb` прошёл независимое scoped re-review после terminal-HOLD исправления. Его exact-head baseline CI выявил несовместимость `ReferenceMontageProfileTest.slice_glitch_is_visible_through_the_author_window_without_changing_generic_pulses`; владелец исследует origin identity. До исправления и проверки этого конкретного сбоя не импортировать checkpoint как полностью проверенную зависимость. A1 не зависит от этого payload.

Из reviewed manual checkpoint сохранить optional `manualMontageState`, derived `editableTiming`, effect origin/phase/sample window, local tracks и source-specific attachment table; old defaults сохраняют старый план. `originalFrameOffset` — единственная редактируемая visible phase. Baseline ClipPhase сохраняет исходную миллисекундную границу/длительность, включая 1201ms: прогресс нельзя вычислить только от округлённого начала FrameSpan.

Команды trim→extend после slip восстанавливают последнюю выбранную сохранённую нелинейную кривую; текущий край после подрезки и immutable automatic baseline не заменяют удалённые samples более поздней правки. Для clip IDs, отсутствующих в immutable original и уже выпавших из history, требуется durable retained backing, а не скрытая экстраполяция. Сохранять backing с identity/provenance атомарно с ревизией и исключить mutable caller arrays. Residual16MiB envelope для 50 длинных dense maps из Task3 разрешить через shared map storage/references или явную достаточную representability policy до публикации потребителя, который обещает такие истории. Не понижать точность samples и не терять историю ради лимита manifest.

Source maps/backing — авторские данные, не регенерируемый ML cache. Их нельзя хранить как заменяемые анализом файлы или удалять при repair analysis. Hash-addressed references могут делиться между original/current/history/export; повреждение обязано явно сохранять последний целый опубликованный state или сообщать недоступность правки, не генерировать новую кривую. Published revision blobs остаются неизменяемыми. На сложном diff A2 допустимо выполнить двумя отдельно reviewed связными задачами: A2a canonical map/backing storage и A2b manual graph/phase integration; A2 целиком завершён только после обеих.

Tests: manual frame-plan roundtrip at fractional boundary1201ms, effect windows/local tracks and two-source attachments; source/reference roundtrip after reopen; restored nonlinear samples after trim/slip/reopen/history pruning; original/export revisions remain immutable; large repeated maps do not multiply manifest past its bound; allocation/count bounds before reading. Exact dependency regression must pass before declaring A2 complete.

Commit: `feat(storage): сохранять ручные фазы и исходные кривые правок`.

## B. SourceDraft, отменяемый импорт и атомарное продвижение

Read the gallery owner's `docs/superpowers/specs/2026-10-10-gallery-import-staging-contract.md` in gallery-text-spec worktree for current constraints. Do not duplicate its ContentResolver picker/metadata inspector. Core supplies persistent transaction, draft and promotion primitives to that adapter.

Proposed minimal API (exact names pinned after implementation/review):

- `SourceDraft(projectId, schemaVersion, etag, assets, videoSources, legacyMigrationKey?)` — immutable core value, may have zero selections, has no placeholder graph/music.
- `HybridProjectStore.beginSourceImport(previousProjectId: String?)` returns `SourceImportTransaction` with new projectId and private owned directory. It never mutates previous project.
- Transaction `publish(assets, sources, legacyMigrationKey?)` atomically publishes a complete draft; `close()` rolls back if unpublished. A committed draft remains after close; abandoned transactions are protected by leases in C.
- `loadSourceDraft(projectId): SourceDraft?` distinguishes assembled project vs staging; old HybridProject load/list semantics remain explicit.
- `promoteSourceDraft(project, expectedDraftEtag)` checks unchanged draft etag and same project ID, physical ownership and selection bindings; publishes first real revision atomically. Graph/music must be real and valid. Failure leaves original draft reopenable.

Retained selections keep ID/order; new selections append. Physical copies belong to the new project, including forks sharing bytes with prior project. SourceImportTransaction passes controlled directory to gallery importer; bounded provider copying, metadata policy, item errors and cancellation reside in the gallery adapter. No source published before its durable copy. Partial successful import may publish only a validated accepted set; cancellation before commit rolls back whole transaction.

Migration: durable key -> project ID persists independently of source deletion. Retry returns the same migrated project. Recover crash between draft publication and marker by matching migration key from envelope; an already deleted migrated source must not trigger silent reimport. Existing legacy originals remain until accepted migration. Never infer a fake successful render.

Tests use real temp-file IO/fault injection: pre-publication cancel rollback; after-publication reopen; CAS promotion stale/wrong ID/assets rejected with identical CURRENT bytes; successful promotion retains ID/table/ownership; repeated same file selections retain two IDs; import/fork never deletes previous project; old store state versions remain readable; migration crash/retry/deletion marker idempotence. No real media or credentials in fixtures.

Commit: `feat(storage): добавить черновики импорта и атомарное создание монтажа`.

## C. Runtime, leases и безопасное удаление исходников

Scope: common store/runtime, lease primitives, staging cleanup/recovery and focused real IO/process tests. No competing preview/music/gallery store.

- Runtime `savedOutputTimeUs` persists separately from revision content, bounded by project duration when composition exists. Changing it does not allocate ID, enter undo or invalidate immutable export snapshot.
- `acquireLease(projectId)` protects project-owned source/analysis/music snapshots used by preview/export/analysis/import. Cross-process protection is required; OS process death releases the liveness lock. JVM shared-lease refcount avoids OverlappingFileLockException, lock path stays outside a directory being renamed/deleted.
- `deleteSources(projectId)` returns explicit Deleted/Busy/Missing result, refuses active leases, removes only project-owned source bytes, preserves metadata/tombstone/exported MP4. Subsequent editor load exposes missing sources while last export remains usable. Other project's physical copies survive.
- Full project deletion also respects live leases and migration tombstones. Never follow linked subtrees outside owned workspace. CompletedRenderStore MP4 lifecycle remains with its owner.
- Cleanup obtains exclusive protection before deleting an abandoned transaction. Never delete a live unpublished import or published draft. Crash recovery leaves latest complete CURRENT/revision state intact; old unreachable transient state may be removed only under the appropriate lock.
- Bound combined app preview cache to 32 MiB and at most2 decoders in later reader/preview tasks; leases must not hold eager duplicated planes. Runtime state does not become an alternative compilation authority.
- Snapshot/lease/page ownership, including producer mutation regression, follows [common freeze contract](../specs/2026-10-10-frozen-project-contract.md); C supplies OS liveness, Task4 fixes compile snapshot, Task5 supplies lazy blob v2 and bounded reader. These gates are required before preview/export publication.

Tests: two store instances/JVM threads; a real subprocess holding a lease (helper in test sources, no app exported receiver); deletion Busy while live, allowed after process exit; close idempotence; import liveness prevents cleanup; simulated crash and reopen; saved playhead survives reopen without counter/history mutation; deleteSources leaves retained metadata and other projects intact. Physical Android abrupt power-loss/visual behavior still requires final gates and must not be claimed from desktop fault simulation.

Commit: `feat(storage): защитить активные проекты и сохранять позицию просмотра`.

## Последующая интеграция

Основная задача 4 consumes revised model/snapshots; task 5 supplies asset+PTS+version keyed lazy analysis/rehydration; task 6 populates real source metadata/table from actual selected automatic result and promotes SourceDraft if present. Music/audio reuse reviewed custom-music helpers, text/STT reuse owner implementation, preview uses common compiled frames/music and its reviewed framing/decoder/GL components. Import only reviewed commits, resolve adapters in scoped integration commits and review them. Exact final-commit CI, actual MP4/UI/receiver checks and physical/human acceptance remain separate evidence.

Semantic dependency: Live Scrubbing smart track/cache v19 checkpoint `5f545044a9f17c0e37b6cba79dbc959b9e1ee4a4`, draft PR #24. Independent task review and fix1 scoped re-review clean; exact-head CI started, not yet verified here. Task5 imports these declarations/cache changes and implements compact semantic blob v2/header/lazy reader, with field provenance and complete associated regressions. Existing core MediaFrameAnalysisCache is still v18. Atomic validated track/blob publication precedes READY; it cannot be simulated with an API stub.

N-source render dependency передана координатором: gallery [PR #20](https://github.com/Veycad/Veycad/pull/20), checkpoint `2e1d0e9759f1a5c661e4c6433fa94b8d235705da`. Координатор проверил exact CI/artifact digests и сообщил667 JVM/255 Python/78 Android без ошибок/пропусков; это provenance внешней проверки, здесь suite повторно не запускался. Просмотр committed renderer подтвердил `Request(sourceFiles: List<File>?=null)` с legacy master/secondary defaults,1..20 ordered files и cap2 decoder. Task4 переиспользует этот reviewed N-source механизм и held-frame QA/geometry проверки через адаптер общей selection table. Gallery не получает отдельный store/counter. EGL/GLES adapter от Live Scrubbing checkpoint76db137 находится в ревью; до чистого результата не импортировать как проверенный код.
