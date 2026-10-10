# Veycad Hybrid Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Реализовать полный путь «автоматический монтаж → склейки, своя музыка и текст → сохранение проекта → экспорт и шеринг» в одной первой версии.

**Architecture:** Сохраняем выбранный результат режиссуры и исходники в долговечном проекте. Чистые команды создают ревизии; компилятор задаёт единый план кадров, звука, текста и эффектов для предпросмотра и экспорта. Новый экран использует существующие ресурсы Veycad и не хранит второй таймлайн.

**Tech Stack:** Kotlin, Android Views/AppCompat, MediaCodec, GLES, MediaExtractor, Canvas, JUnit 4, Espresso и существующий Gradle gate. Новые облачные службы и сетевые разрешения не требуются.

**Spec:** [Одобренный проект](../specs/2026-10-10-hybrid-mode-design.md).

## Global Constraints

- Все четыре возможности входят в одну первую версию.
- Проект работает локально, в текущем оформлении Veycad, без добавления авторизации.
- Общий холст по умолчанию — 9:16; по последующему делегированному решению координатора сохраняются все одобренные 9:16/16:9/1:1/4:5 и настройки кадрирования выбранных элементов. Явный выбор автора сохраняется; recipe default из reviewed ProjectFormats учитывается фабрикой.
- Границы проекта хранятся в целых кадрах при фиксированной для проекта частоте 30 или 60 fps.
- Времена исходников хранятся в микросекундах.
- Сдвиг сохраняет суммарную длительность пары.
- Правка не должна незаметно ускорять весь соседний фрагмент.
- Хранятся 50 последних команд для отмены.
- Исходники активных проектов не удаляются очисткой временных файлов.
- Экспорт получает снимок `projectId + revisionId`.
- Предпросмотр и экспорт используют один скомпилированный таймлайн.
- Предварительное сохранение в галерею не требуется.
- Коммит и PR после каждой связной завершённой задачи; слияние только вручную после явного решения владельца.
- Сохраняем `minSdk = 26`, `com.veycad.app` и существующие доступность стилей, камерные связи и запрет сетевых разрешений.

## Review Focus

1. Новый импорт и очистка черновика после готового монтажа: сохранённый проект должен продолжить читать собственные исходники. Проверки задач 2 и 6.
2. Сдвиг на один кадр при 60 fps, VFR и speed ramp: скорость чистых участков и PTS не должны изменяться из-за округления. Проверки задач 1, 3 и 4.
3. Музыка с ненулевым началом, коротким хвостом и повторами: предпросмотр с середины и экспорт должны использовать один отрывок без щелчков и дрейфа. Проверки задач 7, 8 и 11.
4. Отмена либо новая правка во время импорта, прокси, пересборки или экспорта: старый результат не подменяет текущую ревизию и не запускает повторный шеринг. Проверки задач 9, 11 и 14.
5. Авторские Heartbeat, FEAR и двухисточниковый DUALITY: правка не возвращает старую хореографию, не теряет второй источник и не меняет рецепт без решения автора. Проверки задач 5, 9 и 15.

## Рабочая копия и последовательность

Исходная база — `main` `a0395e26b93463f91520181a2eeb09dfd05e78a3`. Одобренная спецификация находится в `codex/hybrid-mode-design`. Основная копия `C:/Users/rexar/Documents/Codex/AutoEdit` содержит несвязанные изменения и не используется для реализации.

Соседние модули продвинулись после одобрения исходного плана. Последующее делегированное владельцем решение координатора сохраняет все одобренные требования галереи, ручного монтажа, текста/STT и Live Scrubbing с единым ядром в PR #15. Проверяемые dependency checkpoints и недостающие совместимые этапы перечислены в [интеграционном плане](2026-10-10-hybrid-integration-checkpoints.md) и [общем контракте](../specs/2026-10-10-shared-project-contract.md). Не переносить чужой WIP и не создавать второй store/compiler/ID authority. Перед задачей 7 проверить окончательные коммиты/PR custom-music и переиспользовать reviewed механизм через адаптер, не изменяя чужую рабочую копию.

Kotlin-файлы ниже размещаются в `app/src/main/java/com/example/autoedit/`, JVM-тесты — в `app/src/test/java/com/example/autoedit/`, Android-тесты — в `app/src/androidTest/java/com/example/autoedit/`. Их package — `com.veycad.app`. Полные пути указаны в каждом задании.

Зависимости: 1 → 2/3 → 4 → 5/6 → 7 → 8/9/10 → 11 → 12 → 13 → 14 → 15. Общие модель, кодек и временные интерфейсы фиксируются до работ над музыкой и текстом. Вспомогательные типы вводятся задачей, владеющей ими; заглушки не считаются завершённой задачей.

## Команды проверки

В PowerShell установить `JAVA_HOME` на Android Studio JBR и `ANDROID_HOME` на установленный SDK. Текущие пути: `C:/Program Files/Android/Android Studio/jbr` и `C:/Users/rexar/AppData/Local/Android/Sdk`.

В этой среде `python` ведёт к заглушке Store; для Gradle использовать `-PpythonExecutable=C:/Users/rexar/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe`. Это параметр запуска, не сохраняемый локальный путь в конфигурации проекта.

Ниже `JVM(<Class>)` означает команду `.\gradlew.bat :app:testDebugUnitTest --tests com.veycad.app.<Class>`. `BASELINE` означает `.\gradlew.bat -PpythonExecutable=C:/Users/rexar/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe clean baselineVerify`. Для машины с другим Python указать её доступный исполняемый файл.

UI запускается через `tools/run_ui_tests.ps1` по существующей инструкции `docs/testing/ui-tests.md`, только в отдельном AVD и пакете `.uitest`. Занятые другими задачами эмуляторы нельзя сбрасывать; перед запуском выбрать свободный AVD. На физическом телефоне не удалять приложение или пользовательские данные ради теста.

Каждое задание проходит RED → GREEN: сначала тест действительно падает по новому поведению, затем реализация и повтор той же команды. Перед коммитом просмотр `git diff --cached`, затем `git diff --cached --check`; индексируются только перечисленные файлы этой задачи. Commit step включает код и его необходимые тесты.

### Task 1: Модель проекта и точное время

**Files:** Create `app/src/main/java/com/example/autoedit/HybridProject.kt`, `ProjectClock.kt`, `SourceTimeMap.kt`; Test `app/src/test/java/com/example/autoedit/ProjectClockTest.kt`, `SourceTimeMapTest.kt`, `HybridProjectTest.kt`.

**Interfaces:**
- `ProjectClock(fps: Int).timeUs(frame: Int): Long`, `.nearestFrame(timeUs: Long): Int`; разрешены 30/60, неотрицательное время.
- `FrameSpan(start: Int, endExclusive: Int)`; `length: Int` положителен.
- `SourceTimeMap(points: List<Point>)`; `Point(localFrame: Int, sourceTimeUs: Long)`; `sample(localFrame: Int): Long`, `slice(from: Int, until: Int): SourceTimeMap`, `extendLeft(frames: Int, minimumUs: Long): SourceTimeMap`, `extendRight(frames: Int, maximumUs: Long): SourceTimeMap`. Интерполяция линейная между сохранёнными точками; продолжение использует крайний наклон и отклоняет выход за источник.
- `ProjectAsset(id: String, fileName: String, kind: Kind, durationUs: Long, contentHash: String)`; `Kind.VIDEO/AUDIO`.
- `HybridClip(id: String, assetId: String, span: FrameSpan, sourceMap: SourceTimeMap, original: MontageGraph.Clip)`.
- `ProjectMusic(assetId: String, startUs: Long, gain: Float, fadeInUs: Long, fadeOutUs: Long, repeat: Boolean)`; gain 0..1, времена неотрицательны.
- `TextItem(id: String, text: String, span: FrameSpan, x: Float, y: Float, width: Float, size: Float, color: Int, appearance: Appearance, fadeFrames: Int)`; `PLAIN/ACCENT/BACKGROUND`, координаты и размеры относительны холсту.
- `ProjectStyle(recipeId: String, recipeVersion: Int, mode: Mode, showAuthoredText: Boolean)`; `AUTHORED/ADAPTIVE`.
- `HybridRevision(id: Long, parentId: Long?, graph: MontageGraph, clips: List<HybridClip>, music: ProjectMusic, texts: List<TextItem>, style: ProjectStyle, lockedCutIds: Set<String>)`.
- `HybridProject(id: String, schemaVersion: Int, fps: Int, nextRevisionId: Long, assets: List<ProjectAsset>, original: HybridRevision, current: HybridRevision, undo: List<HybridRevision>, redo: List<HybridRevision>, exports: List<ProjectExportRef>)`; счётчик `nextRevisionId` сохраняется отдельно от отменяемого состояния и не уменьшается.
- `ProjectExportRef(revisionId: Long, fileName: String, settings: ProjectExportSettings)`; `ProjectExportSettings(width: Int, height: Int, fps: Int, bitrate: Int, audioBitrate: Int)` — здесь вводится тип значения, задача 13 добавляет выбор и политику экспорта.

- [ ] Write tests: `absoluteFrameTimesDoNotAccumulateRounding` asserts frame 60 at 60 fps = 1_000_000 us and frame 3 at 30 fps = 100_000 us; `slicePreservesOriginalSourceTimes` compares every retained point; `extensionsRejectUnavailableFrames` rejects both bounds; `projectRejectsGapsAndDuplicateIds` rejects inconsistent timelines, asset references and revisions.
- [ ] Run the three new JVM classes; confirm failure because these interfaces are absent.
- [ ] Implement the model and absolute calculation `(frame * 1_000_000L + fps / 2) / fps`; prohibit overflow and invalid source maps. Store only values and relative asset names, not Android Context or mutable shared arrays.
- [ ] Run all three classes; expect zero failures, including 30/60 fps and nonuniform source PTS.
- [ ] Commit `feat(storage): добавить модель гибридного проекта и точное время`.

### Task 2: Кодек и долговечное хранение

**Files:** Create `app/src/main/java/com/example/autoedit/HybridProjectCodec.kt`, `HybridProjectStore.kt`, `ProjectAssetStore.kt`, `AnalysisSidecarStore.kt`; Test `app/src/test/java/com/example/autoedit/HybridProjectCodecTest.kt`, `HybridProjectStoreTest.kt`, `ProjectAssetStoreTest.kt`, `AnalysisSidecarStoreTest.kt`; Modify `app/src/main/java/com/example/autoedit/RenderWorkspace.kt` only for protected project ownership.

**Interfaces:**
- `HybridProjectCodec(sidecars: AnalysisSidecarStore).encode(project: HybridProject): ByteArray`, `.decode(bytes: ByteArray): HybridProject`.
- `ProjectAssetStore(projectDirectory: File).import(source: File, kind: ProjectAsset.Kind, durationUs: Long): ProjectAsset`, `.resolve(asset: ProjectAsset): File`.
- `HybridProjectStore(filesDir: File).create(project: HybridProject): Unit`, `.load(projectId: String): HybridProject`, `.save(project: HybridProject, expectedRevisionId: Long): Unit`, `.list(): List<HybridProject>`, `.delete(projectId: String): Unit`.
- `HybridProjectStore.directory(projectId: String): File` возвращает проверенный каталог проекта. Import получает длительность после проверки контейнера; повторный импорт одинакового хеша возвращает существующий файл.
- `AnalysisSidecarStore(directory: File).write(kind: String, data: ByteArray): String` returns a content hash; `.read(hash: String): ByteArray?` verifies that hash and treats absence/corruption as missing analysis. Codec writes structural graph values into its manifest and large cached analysis into sidecars; missing caches are marked for task-5 regeneration and never silently replace source footage.
- `projects/<id>/` owns immutable revisions and source files. Large mask/depth/flow arrays use hashed binary sidecars; manifest points to them and can regenerate missing analysis. The codec preserves all graph parameters, clip transforms, speed ramps, overlays and effect nodes; no Java object serialization.

- [ ] Write tests: `codecPreservesEveryGraphField` round trips a graph with every supported field and compares its frame plan; `interruptedSaveKeepsPreviousRevision` injects failure before pointer replacement; `pathTraversalAndUnknownSchemaRejected` rejects `../` assets and future schemas without rewriting; `draftCleanupKeepsProjectAssets` creates a new import and clears transient directories; `retainedExportsKeepTheirRevisions` prunes >50 history commands and checks protected references.
- [ ] Run new classes; observe RED before adding persistence.
- [ ] Implement versioned bounded binary encoding with primitive data streams, immutable revision files and same-directory atomic pointer replacement. Import files before publishing references. Track shared capture/source ownership so deletion cannot break another live project.
- [ ] Run new classes; corrupt one cache sidecar and verify the project opens with analysis regeneration required while source files remain intact.
- [ ] Commit `feat(storage): сохранять проекты ревизии и исходники`.

### Task 3: Обратимые команды склеек

**Files:** Create `app/src/main/java/com/example/autoedit/HybridEditCommands.kt`, `HybridCutConstraints.kt`; Modify `HybridProject.kt`, `HybridProjectCodec.kt` for retained local phase and its backward-compatible binary encoding; Test `app/src/test/java/com/example/autoedit/HybridEditCommandsTest.kt`, `HybridCutConstraintsTest.kt`, `HybridProjectCodecTest.kt`.

**Interfaces:**
- `HybridCutConstraints.range(revision: HybridRevision, cutId: String, assets: List<ProjectAsset>, fps: Int): IntRange` returns allowed absolute boundary frames, including transition handles. Add `.range(project: HybridProject, cutId: String)` convenience overload; output fps cannot be inferred from source PTS.
- `HybridEditCommands.moveCut(project: HybridProject, cutId: String, targetFrame: Int): HybridProject`.
- `.slipClip(project: HybridProject, clipId: String, sourceOffsetUs: Long): HybridProject`, `.replaceMusic(project: HybridProject, music: ProjectMusic, asset: ProjectAsset? = null): HybridProject`, `.putText(project: HybridProject, item: TextItem): HybridProject`, `.removeText(project: HybridProject, id: String): HybridProject`, `.setAuthoredText(project: HybridProject, visible: Boolean): HybridProject`.
- `HybridClip.originalFrameOffset: Int = 0` records the original local output-frame coordinate represented by current local frame 0. Trimming the beginning adds removed frames; left extension subtracts them; slip keeps the value. Keep signed offsets for extension and use bounded long arithmetic when combining frame coordinates. This preserves output-animation phase separately from source PTS, including held frames. The task-4 compiler uses the baseline clip span plus this offset rather than renormalizing retained camera motion to the edited length.
- Preserve the v1 binary reader with zero default phase; add explicit format-version dispatch for the new field. Old projects open without rewriting, and shared original/export revisions retain their identities. Include a genuine v1 fixture produced before changing the encoder.
- `.commitRevision(project: HybridProject, candidate: HybridRevision): HybridProject` is the shared seam for external validated manual/visual edits. Require `candidate.id == project.current.id`, allocate the new ID and parent centrally, validate candidate payload, and apply the same undo/redo rules. External adapters do not allocate IDs; persistence remains the caller's CAS save.
- `.undo(project): HybridProject`, `.redo(project): HybridProject`, `.restoreAutomatic(project): HybridProject`. New revision IDs increase monotonically even after undo; preserve exports and source ownership.
- `ProjectCommand` — sealed type with `MoveCut(cutId, targetFrame)`, `SlipClip(clipId, sourceOffsetUs)`, `ReplaceMusic(music, asset: ProjectAsset? = null)`, `PutText(item)`, `RemoveText(id)`, `SetAuthoredText(visible)`, `CommitRevision(candidate)`, `Undo`, `Redo`, `RestoreAutomatic`; `.apply(project: HybridProject, command: ProjectCommand): HybridProject` invokes the exact methods above. Invalid commands throw `HybridEditRejected(reason: String)` before persistence.

- [ ] Write tests: `rollingCutMovesTwoFramesWithoutChangingDuration` asserts pair/end/global duration; `speedRampRetainedFramesKeepTheirPts` compares every retained sample before/after at 60 fps, including a fractional sparse map; `slipPreservesOutputFrames` changes only source times; `retainedCameraPhaseSurvivesCutAndSlip` checks origin offset changes and held-PTS motion phase independently; `version1ProjectLoadsWithZeroPhase` tests migration without rewriting; `undoAfterReopenRestoresMusicTextAndCut` exercises codec; `oneGestureIsOneCommandAndHistoryCapsAt50` checks transaction boundaries and redo invalidation; external commit rejects stale IDs and shares the core counter/history.
- [ ] Run both classes; confirm RED.
- [ ] Implement commands using source map slicing/extension, pure validation and explicit locked-cut IDs. No renderer invocation inside a command. A rejected edit returns an explained failure and does not save a partial revision.
- [ ] Run both classes; test both ends of each source and transition too long for adjacent clips.
- [ ] Commit `feat(ui): добавить обратимые правки склеек музыки и текста`.

После задачи 3 выполнить [совместимые checkpoints A–C общего ядра](2026-10-10-hybrid-integration-checkpoints.md). Они закрепляют обязательные контракты N selected sources, full text/visual settings, SourceDraft staging и runtime/leases для одобренных соседних модулей. Каждый checkpoint — отдельная связная задача, коммит и независимое ревью; внешние competing stores/counters не импортируются.

### Task 4: Общая компиляция и план кадров

**Files:** Create `app/src/main/java/com/example/autoedit/HybridProjectCompiler.kt`, `HybridStyleTimeMap.kt`; Modify `HighQualityFramePlan.kt`, `MediaCodecSpeedRampRenderer.kt`; Test `app/src/test/java/com/example/autoedit/HybridProjectCompilerTest.kt`, `LiveDecoderTransitionPlanTest.kt` and existing tests that call `HighQualityFramePlan.build` (discover with `rg -l 'HighQualityFramePlan.build' app/src/test`).

**Interfaces:**
- `HybridProjectCompiler.compile(project: HybridProject, revision: HybridRevision = project.current): CompiledHybridProject`.
- `CompiledHybridProject(projectId: String, revisionId: Long, fps: Int, graph: MontageGraph, frames: HighQualityFramePlan.Plan, assets: List<ProjectAsset>, music: ProjectMusic, texts: List<TextItem>, style: CompiledStylePlan)`.
- Introduce style value contracts here: `StyleAnchor` is sealed (`Absolute(timeUs)`, `Clip(clipId, localFrame)`, `Cut(cutId, offsetFrames)`, `Beat(beatIndex, offsetFrames)`); `StyleEvent(id: String, anchor: StyleAnchor, authoredTimeUs: Long, projectTimeUs: Long)`; `HybridStyleTimeMap(points: List<Point>)`, `Point(projectTimeUs: Long, authoredTimeUs: Long)`, `.styleTimeUs(projectTimeUs: Long): Long`; `CompiledStylePlan(recipeId: String, authoredClock: HybridStyleTimeMap, authoredTextVisible: Boolean, events: List<StyleEvent>)`. Task 4 proves identity mapping; task 5 adds the recipe adapters.
- Frame carries separate `projectTimeUs` and encoder `outputTimeUs`; source PTS comes from `SourceTimeMap`. Existing unedited callers retain the legacy `HighQualityFramePlan.build(graph, fps)` overload.
- Renderer request accepts optional compiled frame/style/text/audio plans, with old defaults preserved.

- [ ] Write tests: `unchangedProjectMatchesLegacySchedule` compares all frame samples for three production styles; `editedBoundaryUsesExactFrame` checks incoming clip on N rather than rounded milliseconds; `vfrSelectionUsesSourcePts` uses nonuniform PTS; `windowKeepsGlobalTimeAndRebasesEncoderPts` starts at 8 s and asserts both clocks.
- [ ] Run compiler class plus existing frame/decoder schedule tests; confirm new expectations RED.
- [ ] Implement compilation and renderer plan seam. Rebuild only timeline-dependent parameters; preserve source maps of unaffected clips, two-source indices and immutable compilation snapshots.
- [ ] Run targeted suites; unchanged automatic graphs must preserve previous results.
- [ ] Commit `feat(render): компилировать правки в единый кадровый план`.

### Task 5: Эффекты и хореография редактируемых стилей

**Files:** Create `app/src/main/java/com/example/autoedit/HybridStyleAdapter.kt`, `HybridAnalysisResolver.kt`; Extend task-4 `HybridStyleTimeMap.kt`; Modify `AuthoredTitleProfile.kt`, `MediaCodecSpeedRampRenderer.kt`, `FrameAttachments.kt` and profile files only at the shared sampling seam; Test `HybridStyleAdapterTest.kt`, `HybridStyleTimeMapTest.kt`, `HybridAnalysisResolverTest.kt`.

**Interfaces:**
- `HybridStyleAdapter.compile(revision: HybridRevision, fps: Int): CompiledStylePlan`.
- `CompiledStylePlan(recipeId: String, authoredClock: HybridStyleTimeMap, authoredTextVisible: Boolean, events: List<StyleEvent>)`; events have stable IDs and explicit absolute/clip/cut/beat anchors.
- `HybridStyleTimeMap.styleTimeUs(projectTimeUs: Long): Long`; monotonic piecewise map between authored and current anchor points, with verified bounds.
- `HybridAnalysisResolver.ensure(assets: List<ProjectAsset>, requiredSourceWindows: Map<String,List<LongRange>>, checkCancelled: () -> Unit): AnalysisBundle`; keys include asset ID, content hash, PTS and analyzer version.
- `AnalysisKey(assetId: String, contentHash: String, sourceTimeUs: Long, analyzerVersion: Int)`; `AnalysisBundle(frames: Map<AnalysisKey, FrameAttachments>, audioBeatMap: AudioBeatMap?)`. Resolver is constructed with `ProjectAssetStore` and local analyzer dependencies; it resolves media only through project ownership.

- [ ] Write tests: `movedCutMovesWhipAndPulseTogether` checks every dependent event; `authoredRecipeIsNotAppliedTwiceAfterEdit` checks profile flags/context; `twoAssetsAtSamePtsUseDifferentMasks` proves namespace separation; `missingHandleAnalysisRejectsOrRecomputes` checks no implicit replacement of the transition; `unchangedProfilesSampleOriginalChoreography` compares title/pulse/echo sample values.
- [ ] Run all three classes; confirm RED.
- [ ] Implement adapters for Heartbeat, FEAR and DUALITY, separating project time, style phase and original provenance. Original automatic rendering keeps original conditions; project rendering consumes one explicit style context. Recompute only newly requested source windows; expose failure before accepting an unsupported edit.
- [ ] Run profile/director tests and targeted compiler tests; verify DUALITY keeps both source roles.
- [ ] Commit `feat(render): привязать эффекты стилей к ручным правкам`.

### Task 6: Сохранение выбранной автосборки и связи результата

**Files:** Create `app/src/main/java/com/example/autoedit/HybridProjectFactory.kt`; Modify `EditSession.kt`, `CompletedRenderStore.kt`, `VeycadAutomaticEditor.kt`, `app/src/uiTest/java/com/example/autoedit/UiTestApplication.kt`; Test `HybridProjectFactoryTest.kt`, `CompletedRenderStoreTest.kt` and new Android `HybridProjectPublicationTest.kt`.

**Interfaces:**
- `HybridProjectFactory(store: HybridProjectStore).create(graph: MontageGraph, fps: Int, sources: List<File>, musicFile: File, recipeId: String, captureLink: CompletedRenderStore.CaptureLink?): HybridProject` creates a fresh project ID, copies assets through task 2 and derives frame ownership from the actual winner schedule.
- `EditSession.RenderSummary` gains selected graph, fps and recipe identity; production cannot publish an editable result without the selected structure. Test fake renders provide a real synthetic seed.
- `CompletedRenderStore.Entry` gains nullable `projectId` and `revisionId`; legacy metadata still loads with null values. `publish` persists these with existing `CaptureLink`.

- [ ] Write tests: `winnerGraphPersistsBeforeResultPublication`, `secondImportCannotDeleteFirstProjectSources`, `captureLinkSurvivesHybridPublication`, `oldMp4LoadsWithoutInventedProject`; production failure before source retention must not show an editable result.
- [ ] Run relevant JVM tests and build new Android test; confirm RED for missing linkage/retention.
- [ ] Implement project publication before MP4 visibility. Store the actually selected graph, never rerun a director to reconstruct it. Reconcile interrupted result/project publication at startup.
- [ ] Run targeted storage tests and Android publication test with controlled render, real import/storage/publication.
- [ ] Commit `feat(storage): сохранять выбранный монтаж для дальнейших правок`.

### Task 7: Импорт собственной музыки и анализ отрывка

**Files:** Create `app/src/main/java/com/example/autoedit/HybridMusicImporter.kt`, `HybridMusicWaveform.kt`; Modify `MediaCodecAudioDecoder.kt`, `AudioBeatMap.kt` only if the accepted custom-music implementation does not already supply source offset; Test `HybridMusicWaveformTest.kt`, Android `HybridMusicImportTest.kt`.

**Interfaces:**
- `HybridMusicImporter(context: Context, assets: ProjectAssetStore).import(uri: Uri, startUs: Long, checkCancelled: () -> Unit): ImportedProjectMusic`.
- `ImportedProjectMusic(asset: ProjectAsset, music: ProjectMusic, beatMap: AudioBeatMap, waveform: FloatArray)`.
- `HybridMusicWaveform.compute(samples: FloatArray, channels: Int, buckets: Int): FloatArray`; finite normalized peaks.
- Decoder exposes `.decode(file: File, maxDecodeUs: Long = Long.MAX_VALUE, preserveFloatHeadroom: Boolean = false, startUs: Long = 0L, checkCancelled: () -> Unit = {}): MediaCodecAudioDecoder.DecodedAudio` with sample-accurate chosen start. Keep a compatibility overload for existing positional calls and preserve trailing-lambda cancellation.

- [ ] Write tests: `offsetAnalysisMatchesChosenAudioSegment` uses distinct impulse patterns before/after offset; `cancelledAndInvalidImportKeepPreviousMusic`, `importedAssetSurvivesProviderLoss`, `waveformIncludesLastPartialBucket`. Exercise MP3, M4A/AAC and WAV fixtures when decoder supports them.
- [ ] Run waveform JVM and build/import Android tests; observe RED.
- [ ] Integrate accepted custom-music code or implement adapter with the above contract. Limit imported files to 128 MiB, validate at least one decodable second after selection and leave project unchanged on failure. Copy into project ownership before accepting it.
- [ ] Run actual decoder/import tests, including last valid offset and cancelled picker.
- [ ] Commit `feat(audio): импортировать и анализировать музыку проекта`.

### Task 8: Единый звук громкость повторы и затухание

**Files:** Create `app/src/main/java/com/example/autoedit/HybridAudioPlan.kt`, `HybridAudioRenderer.kt`; Modify `AacEncoderMuxer.kt`, `MediaCodecSpeedRampRenderer.kt`; Test `HybridAudioPlanTest.kt`, `HybridAudioRendererTest.kt`, Android `HybridAudioSyncTest.kt`.

**Interfaces:**
- `HybridAudioPlan.build(music: ProjectMusic, assetDurationUs: Long, outputDurationUs: Long, sampleRate: Int): HybridAudioPlan`.
- `.sourceSample(outputSample: Long): Long?`, `.gainAt(outputSample: Long): Float`, `.window(startUs: Long,endUs: Long): HybridAudioPlan`; null sample means silence.
- `HybridAudioRenderer.prepare(file: File, plan: HybridAudioPlan, checkCancelled: () -> Unit): PreparedProjectAudio`; `.mux(video: File, audio: PreparedProjectAudio, output: File): Unit`; existing authored muxing remains compatible.
- `PreparedProjectAudio(pcmFile: File, sampleRate: Int, channels: Int, frameCount: Long)` owns a temporary interleaved PCM file; `close()` removes it after both preview/export references release ownership. Stream decoding/muxing in bounded chunks rather than materializing an unbounded entire track.

- [ ] Write tests: `shortMusicRepeatsOnlyWhenEnabled`, `previewAtEightSecondsUsesExportSample`, `fadeRemovesLoopDiscontinuityWithoutDurationDrift`, `sampleCountDefinesMonotonicAacPts`, `volumeDoesNotClipFloatInput`.
- [ ] Run audio JVM classes; confirm RED; build real sync test.
- [ ] Implement sample-clock slicing, explicit looping/silence, gain and short crossfade at repeat boundaries while preserving exact output sample count. Keep one music track; no new source-audio mixing subsystem in this scope.
- [ ] Run JVM tests and decode exported impulse fixture; maximum A/V offset is one output frame at both 30 and 60 fps.
- [ ] Commit `feat(audio): синхронизировать звук предпросмотра и экспорта`.

### Task 9: Адаптивная пересборка и защищённые правки

**Files:** Create `app/src/main/java/com/example/autoedit/HybridReassemblyPlanner.kt`, `HybridReassemblyCoordinator.kt`; Modify directors and `VeycadAutomaticEditor.kt` at explicit adaptive entry points; Test `HybridReassemblyPlannerTest.kt`, Android `HybridReassemblyLifecycleTest.kt`.

**Interfaces:**
- `HybridReassemblyPlanner.build(project: HybridProject, audio: ImportedProjectMusic, preserveManualCuts: Boolean, analysis: AnalysisBundle): ReassemblyPlan`; sealed result `Ready(revision: HybridRevision)` or `Unavailable(reason: String)`.
- `HybridReassemblyCoordinator.start(project: HybridProject, audio: ImportedProjectMusic, preserveManualCuts: Boolean): String` returns operation ID; `.cancel(operationId: String): Unit`, `.accept(project: HybridProject, operationId: String): HybridProject`, `.reject(operationId: String): Unit`.
- `.state: LiveData<ReassemblyState>`; `ReassemblyState(operationId: String, projectId: String, baseRevisionId: Long, status: Status, proposal: HybridRevision?, error: String?)`; `RUNNING/READY/UNAVAILABLE/CANCELLED`. Accept checks the stored base revision atomically, then uses task-2 persistence; rejection leaves current project unchanged.
- Result carries base revision ID and is a previewable proposal, never an automatic replacement. Factory/compiler interfaces from tasks 4/6 are reused.

- [ ] Write tests: `lockedCutsStayExactWhileOtherCutsFollowBeats`, `unlockedReassemblyKeepsDurationTextAndTwoSources`, `noStableBeatKeepsCurrentProject`, `lateProposalCannotReplaceNewEdit`, `rejectRestoresPreviouslySelectedVersion`.
- [ ] Run planner suite; confirm RED. Use synthetic strong beats at known frame times and infeasible intervals.
- [ ] Implement constrained beat alignment per supported style. Locked frame boundaries partition the problem; dynamic programming chooses feasible beat boundaries for remaining cuts with minimum source/transition windows. Infeasible or low-confidence rhythm returns an explained result, not arbitrary cuts. Each recipe retains source roles and compiles adapted effects via task 5. Original strict authored-audio checks stay active on authored entry points.
- [ ] Run director/profile regression suites and proposal lifecycle instrumentation with cancellation and concurrent edits.
- [ ] Commit `feat(render): пересобирать монтаж под трек с сохранением ручных решений`.

### Task 10: Текст и общая композиция

**Files:** Create `app/src/main/java/com/example/autoedit/HybridTextLayout.kt`, `HybridTextRenderer.kt`; Modify `AuthoredTitleProfile.kt`, `MediaCodecSpeedRampRenderer.kt`; Test `HybridTextLayoutTest.kt`, Android `HybridTextRenderTest.kt`.

**Interfaces:**
- `HybridTextLayout.resolve(item: TextItem, canvasWidth: Int, canvasHeight: Int): TextLayout` with wrapped lines and normalized geometry.
- `TextLayout(lines: List<String>, left: Float, top: Float, width: Float, height: Float, baselineOffsets: List<Float>, textSizePx: Float)` contains pixel geometry. Android font metrics are injected through `TextMetrics.measure(text: String, sizePx: Float): Float` and `.lineHeight(sizePx: Float): Float`; JVM layout tests use deterministic metrics and Android pixel tests use the actual typeface.
- `HybridTextRenderer.render(items: List<TextItem>, frame: Int, width: Int,height: Int): Bitmap`; GLES uploads/composes this layer after style composition, with a dirty-cache key of text contents/style/scale.
- Built-in title visibility is supplied only by `CompiledStylePlan.authoredTextVisible`.

- [ ] Write tests: `relativeTextGeometryMatches720And1080`, `endExclusiveSpanHidesTitleAtFinalFrame`, `negativeOrBeyondEndTimesRejected`, `cyrillicWrapAndBackgroundStayInsideBounds`, `styleTextToggleDoesNotDisableOtherStyleEffects`.
- [ ] Run layout JVM tests; confirm RED; build pixel instrumentation test.
- [ ] Implement three appearances, Android local typefaces with verified Cyrillic, color/size/position/time/fade controls via model values. Never place a manual view-only overlay that export cannot reproduce.
- [ ] Render the same text on 720/1080 and compare normalized layout and frames; inspect Cyrillic, line breaks, long words and authored-title toggling.
- [ ] Commit `feat(text): рисовать пользовательские надписи в предпросмотре и видео`.

### Task 11: Точный кадр и локальный предпросмотр

**Files:** Create `app/src/main/java/com/example/autoedit/HybridPreviewCoordinator.kt`, `HybridPreviewPlayer.kt`, `HybridPreviewCache.kt`; Modify renderer window handling and decoder window preparation; Test `HybridPreviewCacheTest.kt`, Android `HybridPreviewParityTest.kt`.

**Interfaces:**
- `HybridPreviewCoordinator.requestFrame(compiled: CompiledHybridProject, frame: Int, onReady: (PreviewFrame) -> Unit): String`.
- `.requestWindow(compiled: CompiledHybridProject, span: FrameSpan, onReady: (PreviewWindow) -> Unit): String`, `.cancel(requestId: String): Unit`.
- `PreviewFrame(projectId: String, revisionId: Long, frame: Int, bitmap: Bitmap, exact: Boolean)`; `PreviewWindow(projectId: String, revisionId: Long, span: FrameSpan, video: File, compiled: CompiledHybridProject)`.
- `HybridPreviewPlayer.play(compiled: CompiledHybridProject, frame: Int)`, `.pause()`, `.seek(frame: Int)`, `.positionFrame(): Int`, `.release()`; one audio-derived playback clock controls all video windows.

- [ ] Write tests: `cacheKeyIncludesRevisionSourcesAndRenderSettings`, `oldFrameRequestCannotWinAfterNewGesture`, `musicOnlyChangeReusesVideoDependencyKey`, `windowAtEightSecondsMatchesFullExportForVideoTextAndAudio`, `pauseSeekResumeKeepsClockAligned`.
- [ ] Run cache suite and build parity instrumentation; confirm RED.
- [ ] Implement frame/window rendering via compiled backend, dependency-expanded transition handles, independent global/encoder clocks and continuous task-8 audio. Reuse immutable clean chunks after dependency validation; prepare the next video segment before boundary and keep playback on the master clock. No restart of music per proxy chunk.
- [ ] Compare decoded frame samples of local windows to full exports at 30/60 fps; measure warm single-frame p95 ≤250 ms and local preview p95 ≤2 s on Samsung A25, reporting actual values.
- [ ] Commit `feat(preview): добавить точный локальный просмотр правок`.

### Task 12: Экран редактора и библиотека проектов

**Files:** Create `app/src/main/java/com/example/autoedit/HybridEditorActivity.kt`, `HybridEditorController.kt`, `HybridTimelineView.kt`, `HybridProjectSession.kt`, `app/src/main/res/layout/activity_hybrid_editor.xml`, `app/src/main/res/values/strings_hybrid.xml`; Modify `MainActivity.kt`, `activity_main.xml`, `AndroidManifest.xml`, test application; Test Android `HybridEditorScreenTest.kt`, `HybridProjectRecoveryTest.kt`.

**Interfaces:**
- Activity accepts only `EXTRA_PROJECT_ID`, is non-exported and loads from task-2 store.
- `HybridProjectSession.get(context: Context): HybridProjectSession` is process owned like EditSession; `Owner` seam allows isolated test injection; asynchronous workers never retain Activity.
- `HybridEditorController.open(projectId: String)`, `.dispatch(command: ProjectCommand)`, `.selectTool(tool: Tool)`, `.seek(frame: Int)`, `.state: LiveData<EditorState>`; commands delegate to task 3. State includes revision, selection, current frame, busy operation, proposal and explained error.
- `Tool.CUTS/MUSIC/TEXT`; `EditorState(project: HybridProject?, tool: Tool, frame: Int, selectedClipId: String?, selectedTextId: String?, busyOperationId: String?, proposal: ReassemblyState?, error: String?)`. UI disables operations requiring an unopened project and passes only IDs to workers.
- `HybridTimelineView` reports a drag transaction with begin/preview/commit and emits exact frame boundaries; accessible ±1 buttons provide the same operation.

- [ ] Write UI tests: `editTwoFramesMusicAndTitleFromResult`, `oneGestureMakesOneUndo`, `doneReturnsCurrentRevision`, `recreateKeepsToolPositionAndProject`, `restartAfterSecondImportKeepsSources`, `legacyMp4HasNoFalseEditButton`, `reassemblyCompareAcceptAndRejectKeepVersions`.
- [ ] Run the new UI classes using `.uitest` fixtures; confirm RED at missing entry points.
- [ ] Implement result/library «Подправить», preview with transport, timeline, waveform, three bottom panels, snap toggle, slip action, text drag/safe guides, music choices and proposal comparison. Use current color resources; do not restyle unrelated screens. Persist finished commands atomically, not every drag sample.
- [ ] Run full existing UI suite plus new classes, including back, picker cancellation, keyboard, lifecycle and explicit retry. A new import, wipe of transient cache or process restart must not lose saved project media.
- [ ] Commit `feat(ui): добавить единый экран гибридной доводки монтажа`.

### Task 13: Качество и экспорт выбранной версии

**Files:** Create `app/src/main/java/com/example/autoedit/HybridExportSettings.kt`, `HybridExportCoordinator.kt`, `HybridExportValidator.kt`; Modify `CompletedRenderStore.kt`, `ExportContract.kt`, renderer request and quality dialog integration; Test `HybridExportSettingsTest.kt`, `HybridExportCoordinatorTest.kt`, Android `HybridEditedExportTest.kt`.

**Interfaces:**
- `ProjectExportSettings(width: Int,height: Int,fps: Int,bitrate: Int,audioBitrate: Int)`; presets 720×1280/3M, 720×1280/5M, 1080×1920/8M at 30 or 12M at 60; AAC target 192k.
- `HybridExportSettings.estimateBytes(durationUs: Long, settings: ProjectExportSettings): Long`; `.supported(settings: ProjectExportSettings, capabilities: MediaCodecInfo.VideoCapabilities): Boolean`; explicit alternate settings if unsupported.
- `HybridExportCoordinator.export(project: HybridProject, settings: ProjectExportSettings, after: AfterExport): String`, `.cancel(operationId: String): Unit`, `.state: LiveData<ExportState>`; `NONE/SAVE/SHARE`.
- `HybridExportValidator.validate(output: File, compiled: CompiledHybridProject, settings: ProjectExportSettings): ExportVerdict`; `ExportState` carries snapshot revision, operation ID and exact published Entry.
- `AfterExport.NONE/SAVE/SHARE`; `ExportState(operationId: String, projectId: String, revisionId: Long, status: Status, progress: Int, entry: CompletedRenderStore.Entry?, after: AfterExport, error: String?)`, `RUNNING/VALIDATING/COMPLETED/CANCELLED/FAILED`; sealed `ExportVerdict.Valid` or `Invalid(reason: String)`.

- [ ] Write tests: `twentySecondCompactEstimateIs7980000BytesBeforeContainer`, `fpsAndCutPositionsStaySameAcrossQuality`, `exportDoesNotInvokeAutomaticCandidateSelector`, `lateExportKeepsItsSnapshotRevision`, `cancelOrOutOfSpaceDoesNotPublishPartialMp4`, `matchingPublishedExportIsReused`.
- [ ] Run settings/coordinator tests; confirm RED; build actual MP4 validation test.
- [ ] Implement direct compiled render, codec capability validation, approximate size display, bounded bitrate control, atomic result publication and persisted export links. Verify frames/duration/audio/text of manual version using its actual timeline rather than authored-reference choreography. Preserve old export and project on failure.
- [ ] Run targeted suites and real 720/1080 exports at 30/60, decode video/audio, compare critical frames/text and sync to one-frame limit.
- [ ] Commit `feat(export): экспортировать выбранную ревизию с управлением качеством`.

### Task 14: Системный шеринг и действия после экспорта

**Files:** Create `app/src/main/java/com/example/autoedit/HybridShareCoordinator.kt`; Modify result/editor actions, `LocalDiagnostics` call sites and existing FileProvider paths only for validated published directories; Test `HybridShareCoordinatorTest.kt`, Android `HybridShareIntegrationTest.kt` and a non-exported `.uitest` receiver activity.

**Interfaces:**
- `HybridShareCoordinator.pending(operationId: String, entry: CompletedRenderStore.Entry): ShareAction`, `.consume(operationId: String): Boolean`, `.createIntent(entry: CompletedRenderStore.Entry): Intent`.
- `ShareAction(operationId: String, entry: CompletedRenderStore.Entry, automaticEligible: Boolean)`; consumed operation IDs persist outside Activity state. Foreground eligibility is determined by the resumed screen that owns that export action.
- «Экспортировать и поделиться» dispatches task-13 SHARE; saving uses SAVE. Dirty projects never share a different/older revision silently.

- [ ] Write tests: `successfulForegroundExportOpensChooserOnce`, `restoreAndCancelledChooserDoNotRepeat`, `backgroundCompletionKeepsManualShareAction`, `dirtyProjectExportsBeforeSharing`, `receiverCanReadExactMp4WithoutGalleryCopy`, `notificationDisabledDoesNotRequestPermission`.
- [ ] Run coordinator/JVM state tests and build receiver instrumentation; confirm RED.
- [ ] Implement ACTION_SEND video/mp4 with content URI, read grant and ClipData where required. Consume operation before automatic launch; after process interruption use visible manual share action. Honor notification preference and permission. Never upload or message a recipient outside the chooser.
- [ ] Run actual receiving-activity byte read and existing save/gallery tests; verify failure to open chooser does not discard exported file.
- [ ] Commit `feat(share): открывать системный шеринг готовой ревизии`.

### Task 15: Полная приёмка и передача реализации

**Files:** Create `app/src/androidTest/java/com/example/autoedit/HybridEndToEndTest.kt`, `HybridPerformanceTest.kt`, `docs/testing/hybrid-mode-review.md`; Modify test discovery script only if its existing discovery fails to include new classes; update implementation plan checkboxes and PR description.

**Interfaces:** Existing production path and instrumentation receive no extra test-only bypasses. Fixture-generated clips/tracks contain known PTS, colors, impulses and two-source roles; reports identify exact commit/APK/revision/codec/device.

- [ ] Write E2E expectations for automatic project → two-frame cut → custom music → Cyrillic title → reopen → export → real receiving app. Add failure-path acceptance for unsupported audio, depleted source handles, denied notifications, stale jobs and disk exhaustion.
- [ ] Run E2E and verify failures expose missing integration rather than bypassing production algorithms.
- [ ] Resolve integration defects using the already approved contracts; add a failing regression before each correction. Do not call the whole mode ready because a single core task is green.
- [ ] Run `BASELINE`, full isolated UI suite and real render/parity/sync tests. Review ordinary-speed exports on Samsung A25 and a device with less memory. Run the specified 10-person/8-success ≤15-second usability trial; document outstanding human/device acceptance instead of inventing evidence.
- [ ] Obtain whole-branch review using the selected execution workflow, fix important issues and rerun affected checks. Push completed scoped commits, update/create the implementation PR with exact check results and attach it to this chat. Keep draft while acceptance is incomplete; do not merge without owner approval.

## Plan self review

Все разделы спецификации имеют задачи: модель/хранение 1–2/6; склейки 3–5; музыка 7–9; текст 10; предпросмотр 4/11; UI/восстановление 12; качество/экспорт 13; шеринг 14; техническая и человеческая приёмка 15. Пять Review Focus закреплены в соответствующих тестах.

Начальный `clean baselineVerify` прошёл 10 октября 2026 года: 255 Python-тестов, JVM-тесты, lint, security contract и сборка debug APK. Первый запуск остановился на заглушке Store вместо Python; повтор с явным `pythonExecutable` завершился `BUILD SUCCESSFUL`. Физический телефон сейчас не подключён, доступны только эмуляторы.

Численные бюджеты и пользовательская приёмка остаются проверяемыми критериями; при отсутствии подключённого физического устройства либо участников испытания реализацию можно сохранить как draft checkpoint с перечисленными незавершёнными проверками, но нельзя объявить полностью принятой.

После ревью этого плана выбранный способ исполнения сохраняется на весь объём. Реализация не требует повторного разрешения после каждого задания; разногласия внутри плана разрешаются по одобренной спецификации и фиксируются в журнале исполнения.
