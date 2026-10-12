# Ручная доводка монтажа — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Дать пользователю подрезку, перестановку и настройку эффектов сгенерированного монтажа с сохранением проекта и экспортом новой версии.

**Architecture:** Долговечный проект сохраняет исходники, исходную автосборку и редактируемую ревизию. Чистые Kotlin-компоненты применяют команды и компилируют ревизию в общий покадровый план; процессный `EditSession` исполняет его для предпросмотра и экспорта. UI передаёт команды и наблюдает состояние, сохраняя действующий автоматический путь рендера.

**Tech Stack:** Kotlin/JVM, Android Views/AppCompat, LiveData, MediaCodec/GLES, существующий AAC muxer, JUnit 4 и Espresso; новые продуктовые зависимости не требуются.

**Spec:** [Согласованная спецификация](../specs/2026-10-10-manual-montage-design.md).

Статус: спецификация и план одобрены; задача 1 в реализации с агентом и отдельным ревью. База — `main` на `a0395e26b93463f91520181a2eeb09dfd05e78a3`, ветка `codex/manual-montage`, draft PR #11. Основной рабочий каталог с чужими незакоммиченными изменениями не является местом реализации.

## Global Constraints

- «Кнопка ±1 кадр изменяет именно один выходной кадр»; экспорт использует 30/60 FPS из сохранённых параметров.
- «Подрезка режет существующее отображение „время клипа → исходный PTS“»; оставшиеся кадры сохраняют скорость и движение.
- «Привязка к музыкальным ударам не переписывает ручные решения».
- «Стиль и фактически выбранная музыка сохраняются».
- «Доводка не открывает отключённые рецепты»; в проверенной базе Sigma отключена, доступны Heartbeat, FEAR и DUALITY.
- «Обычная очистка временных файлов не удаляет общие `projects/<id>` и исходники существующих проектов».
- «Повторные экспорты сохраняют существующий `CaptureLink`».
- «Положительный CI сам по себе не является художественной приёмкой».
- Совместимость проекта: `minSdk=26`, `compileSdk=36`, `targetSdk=36`, Gradle JDK 25; package `com.veycad.app` при существующих путях `com/example/autoedit`.
- Не добавлять сетевые разрешения, авторизацию, новые источники/дорожки, split/duplicate клипов и редактор speed ramp.
- Каждая задача заканчивается scoped commit после применимых проверок; перед коммитом просмотреть staged diff и выполнить `git diff --cached --check`. PR остаётся draft до проверок реализации и пользовательской приёмки; слияние требует отдельного одобрения владельца.

## Review Focus

1. VFR, 29,97 FPS и повторяющиеся decoder PTS: шаг в кадрах задаётся выходной сеткой, сохраняемые PTS не пересчитываются по предполагаемому FPS исходника — задачи 1 и 10.
2. Одинаковый PTS у двух видео DUALITY: слой/маска берутся из правильного `sourceIndex`, включая перестановку и вторичные слои — задачи 2 и 10.
3. Клип длиной в один кадр и перенос последнего стыка: эффект обрезается без смены фазы, перенос без следующего стыка отвергается без изменения ревизии — задачи 3 и 4.
4. Нехватка места или гибель процесса между сохранением проекта и публикацией MP4: прежний результат доступен, незавершённый проект не предлагается для редактирования — задачи 5 и 6.
5. Быстрые жесты, выход с экрана и поздний callback proxy: устаревший результат не подменяет текущий; музыка и эффекты proxy соответствуют глобальному времени — задачи 8 и 9.

## Структура и порядок работы

| Этап | Проверяемый результат | Зависимости |
| --- | --- | --- |
| 1 | Контракт проекта и точное время кадров | — |
| 2 | Импорт и компиляция автосборки без потерь | 1 |
| 3 | Команды доводки и undo/redo | 2 |
| 4 | Рендер ручных эффектов с сохранением стиля | 2, 3 |
| 5 | Долговечный проект и двоичный codec | 2 |
| 6 | Связь автоматических результатов с проектами | 5 |
| 7 | Исполнение и публикация ручной версии | 3, 4, 6 |
| 8 | Корректный proxy и его жизненный цикл | 7 |
| 9 | Экран и жесты доводки | 8 |
| 10 | Проверка MP4 и приёмка на устройстве | 9 |

Это одна возможность с общими контрактами. Выполнять задачи последовательно в указанном порядке; таблица зависимостей не разрешает самостоятельно запускать параллельных агентов. По подтверждённой делегации пользователя — последовательные агенты, ревью каждой части и итоговое независимое ревью.

Новые файлы разделяют обязанности: `EditableMontageProject.kt` — данные; `ClipTimeMapping.kt` — время; `AnchoredMontageEffect.kt` — эффекты; importer/compiler — преобразования; editor/history — команды; store/codec — хранение; saved renderer/analysis resolver — исполнение; preview coordinator — proxy; Activity/View — UI. В существующих `MainActivity` и большом GL renderer нужны только изменения для этой возможности. В списках файлов запись нескольких имён «в этой же директории» означает точный каталог первого полного пути этого пункта.

Все команды ниже запускаются из корня отдельного worktree с JDK 25 и настроенным Android SDK. Для instrumented тестов используется `.uitest` APK на отдельном эмуляторе API 36. `tools/run_ui_tests.ps1` запускает полный UI gate, а filtered Gradle-команды ниже служат коротким циклом разработки.

## Интеграционное уточнение после согласования общего ядра

[Контракт интеграции ручной доводки](../specs/2026-10-10-manual-montage-integration.md) заменяет ниже самостоятельные project/store/allocator API. Проверенное ядро `74b58038` из PR #15 включено без изменений merge-коммитом `28fc903`. Все требования к точности и пользовательским действиям остаются обязательными.

| Задачи | Обязательная адаптация исходного плана |
| --- | --- |
| 1 | `EditableMontageProject` — внутренний адаптер над `HybridProject`; используется общий `ProjectAsset`, `ProjectExportSettings`, `ProjectClock`, `SourceTimeMap`. Монтажный payload принадлежит графу общей ревизии. |
| 2 | Импортёр/компилятор модуля подключаются к общему проекту и frame plan; файлы и ID не дублируются. |
| 3 | Команды подготавливают изменение общей ревизии; общий `HybridEditCommands.apply` владеет allocator/CAS/undo/redo. Подключение требует проверенного совместимого расширения владельца. |
| 4 | Ручные overrides и сохранение фаз стиля реализуются в общем графе/рендере. |
| 5 | Интеграционные тесты общего codec/store и сохранения монтажного payload. Самостоятельные `EditableMontageProjectStore`/`EditableMontageProjectCodec` не создавать; использовать проверенный storage checkpoint владельца. |
| 6–9 | Адаптеры winner/factory/publication, saved render, proxy и экрана подключаются к проверенным общим API; второй session/compiler/store не становится источником истины. |
| 10 | Контентные проверки и устройство сохраняются; локальные тяжёлые прогоны идут по очереди координатора, CI проверяет опубликованный точный head. |

Оригинальные сигнатуры ниже описывают монтажную проекцию и первоначальное разбиение. При конфликте с общим ядром действует контракт интеграции; изменение интерфейса записывается в ledger и exact task brief до передачи агенту. Отсутствующий будущий API не считается выполненной задачей.

## Общие тестовые данные

В задаче 1 создать `app/src/test/java/com/example/autoedit/ManualMontageFixtures.kt` с `linearProject(fps: Int = 30): EditableMontageProject` и `generatedGraph(): MontageGraph`.

- Источники `video-0` и `video-1` имеют длительность 10 000 000 µs; музыка `score` — 4 000 000 µs.
- Чистый fixture использует `Recipe.DUALITY_LOOP` для двух источников и `metadata.generator="manual-contract-fixture"`; рецептурные device fixtures в задаче 10 используют реальные generator/music своего рецепта.
- `A1`: sourceIndex 0, исходное окно 1–3 s; `B`: sourceIndex 1, 4–6 s; `A2`: sourceIndex 0, 6–8 s. Каждый клип длится 2 s; начальная ревизия — 1.
- У A1 `OPEN`, у B/A2 `WHIP`. Каждый исходный source PTS для линейной карты равен `sourceStartUs + round(frame * 1_000_000 / fps)`, включая конечную точку карты.
- Начальные parameter tracks держат scale 1,25 для A1 и grade redBias 0,1 для B; самостоятельная вспышка `flash-B` длится 100 ms и принадлежит входящему стыку B.
- `ExportSettings` в fixture: 720×1280, соответствующий FPS, bitrate 5 000 000. Production importer сохраняет фактические размеры, включая квадрат FEAR.
- Fixture строит чистые данные напрямую и не вызывает importer, editor или Android API. Контентные тесты используют исходный план как независимый oracle.

### Task 1: Контракт проекта, сетка кадров и явное source time mapping

**Files:**
- Create: `app/src/main/java/com/example/autoedit/EditableMontageProject.kt`, `ClipTimeMapping.kt`, `AnchoredMontageEffect.kt` в этой же директории.
- Modify: `app/src/main/java/com/example/autoedit/MontageGraph.kt`, `NleProjectModel.kt`, `HighQualityFramePlan.kt` в этой же директории.
- Test: `app/src/test/java/com/example/autoedit/ClipTimeMappingTest.kt`, `ManualMontageFixtures.kt`, `VeycadEngineCoreTest.kt` в этой же директории.

**Interfaces:**
- Produces: `ExportSettings(width: Int, height: Int, fps: Int, bitrate: Int)`; `FrameRange(start: Long, endExclusive: Long)` с `count: Long`; `TimelineRange(startUs: Long, endUs: Long)`.
- Produces: `ClipTimeMapping(fps: Int, sourceDurationUs: Long, sourceUsByFrame: LongArray)`; `sourceTimeUs(frame: Long): Long`, `frameTimeUs(frame: Long, fps: Int): Long`. Отрицательные frame допускают расширение; карта имеет N+1 точек для N кадров. Экстраполяция использует скорость соответствующего края.
- Produces: `ProjectAsset(id: String, relativePath: String, displayName: String, sizeBytes: Long, sha256: String, durationUs: Long)`; позиция в `sources: List<ProjectAsset>` является постоянным sourceIndex, музыка хранится отдельным asset.
- Produces: `EditableClip(id: String, sourceIndex: Int, origin: MontageGraph.Clip, timeMap: ClipTimeMapping, visible: FrameRange, localTracks: List<ParameterTrack>)`; `MontageRevision(number: Long, clips: List<EditableClip>, effects: List<AnchoredMontageEffect>)`.
- Produces: `EditableMontageProject(id: String, schemaVersion: Int, recipe: MontageStyleCatalog.Recipe, export: ExportSettings, sources: List<ProjectAsset>, music: ProjectAsset, originalGraph: MontageGraph, baseline: MontageRevision, current: MontageRevision, captureLink: CompletedRenderStore.CaptureLink?)`.
- Produces: `AnchoredMontageEffect` с `id`, `logicalId`, `originId`, `anchor: EffectAnchor`, `enabled`, исходным overlay/node и диапазоном его исходной фазы. `EffectAnchor` — `Clip(clipId, localStartUs, localEndUs)`, `Boundary(incomingClipId, offsetUs, durationUs)` или `Music(startUs, endUs)`.
- Graph v3 добавляет необязательный `editableTiming: EditableFrameTiming?`, содержащий `fps`, поклиповые карты/FrameRange и точные границы в числе кадров. `HighQualityFramePlan.build(graph, fps)` использует этот контракт при его наличии, legacy mapping — иначе. `HighQualityFramePlan.Frame` получает `globalOutputTimeUs: Long = outputTimeUs`.

- [ ] **Step 1: Написать red tests** `oneOutputFrameAt30And60Fps`, `trimKeepsOriginalRampSamples`, `vfrAndRepeatedPtsAreNotResampled`, `legacyGraphsKeepTheirSchedule`.
  ```kotlin
  assertEquals(33_333L, frameTimeUs(1, 30)); assertEquals(16_667L, frameTimeUs(1, 60))
  assertEquals(300_000L, frameTimeUs(9, 30))
  // Для карты [0, 18_000, 18_000, 63_000, 91_000]: trim [1,4) сохраняет именно индексы 1,2,3.
  assertArrayEquals(longArrayOf(18_000, 18_000, 63_000), retainedSourcePts)
  assertEquals(beforeMigration.frames, afterMigration.frames)
  ```
- [ ] **Step 2: Запустить** `.\gradlew.bat :app:testDebugUnitTest --tests "com.veycad.app.ClipTimeMappingTest"`; ожидается FAIL/compile error из-за отсутствующего контракта.
- [ ] **Step 3: Реализовать** перечисленные типы и graph v3, включая v1→v2→v3 migration без изменения legacy-расписания; считать абсолютный PTS через целые номера кадров с одним округлением. Проверить уникальность ID, монотонность source PTS (равные допускаются), допустимые FPS и минимум один видимый кадр. Источник измеряется в µs, округление `Clip.sourceStartMs` не является oracle для нового mapping.
- [ ] **Step 4: Запустить** предыдущий filtered test и `.\gradlew.bat :app:testDebugUnitTest --tests "com.veycad.app.VeycadEngineCoreTest"`; ожидается PASS, включая прежние render-window проверки.
- [ ] **Step 5: Просмотреть scoped diff, проверить и закоммитить** `feat(timeline): добавить контракт редактируемого проекта и точное время кадров` с файлами этой задачи и её тестами.

### Task 2: Импорт winner-графа и компиляция локальных треков/эффектов

**Files:**
- Create: `app/src/main/java/com/example/autoedit/EditableMontageImporter.kt`, `EditableMontageCompiler.kt`, `MontageEffectBindings.kt` в этой же директории.
- Modify: `app/src/main/java/com/example/autoedit/MontageGraph.kt`, `HighQualityFramePlan.kt`, `FrameAttachments.kt`, `LayerCompositorModel.kt`, `GpuEffectGraph.kt` в этой же директории.
- Test: `app/src/test/java/com/example/autoedit/EditableMontageCompilerTest.kt`, `MontageEffectBindingsTest.kt` в этой же директории.

**Interfaces:**
- Consumes: типы задачи 1 и `HighQualityFramePlan.build(graph, fps)`.
- Produces: `EditableMontageImporter.create(id: String, graph: MontageGraph, recipe: MontageStyleCatalog.Recipe, export: ExportSettings, sources: List<ProjectAsset>, music: ProjectAsset, captureLink: CompletedRenderStore.CaptureLink?): EditableMontageProject`.
- Produces: `EditableMontageCompiler.compile(project: EditableMontageProject): MontageGraph`; `layout(revision: MontageRevision, fps: Int): List<ClipLayout>`; `ClipLayout(clipId: String, startFrame: Long, endFrameExclusive: Long)`.
- Produces: `MontageEffectBindings.bind(graph: MontageGraph, fps: Int): List<AnchoredMontageEffect>`; `SourceAttachments(sourceIndex: Int, timeline: FrameAttachmentTimeline)`, необязательные `MontageGraph.sourceAttachments` вместо смешивания PTS разных видео.
- Overlay/GPU node получают совместимые поля `originId`, `phaseStart`, `phaseEnd` с defaults для legacy. Идентичность авторского cue (`heartbeat-echo-*`, Sigma finale и т. п.) берётся из `originId`, уникальность сегмента — из `id`.

- [ ] **Step 1: Написать red tests** `unmodifiedImportIsAnExactRoundTrip`, `reorderedClipKeepsSourceAndGrade`, `samePtsInTwoSourcesKeepDifferentMasks`, `splitEffectKeepsEnvelopePhase`, `trimmedSecondaryRoleDoesNotFreezePrimary`.
  ```kotlin
  assertEquals(original.frames, HighQualityFramePlan.build(compiled, 30).frames)
  assertEquals(listOf(1, 0, 0), reordered.clips.map { it.sourceIndex })
  assertEquals(.1f, frameOfB.redBias, 0f)
  assertEquals(.2f, source0MaskConfidence, 0f); assertEquals(.9f, source1MaskConfidence, 0f)
  assertEquals(originalEffectAt1500ms, segmentedEffectAt1500ms)
  assertTrue(primaryPts.zipWithNext().all { (a, b) -> b >= a })
  ```
- [ ] **Step 2: Запустить** `.\gradlew.bat :app:testDebugUnitTest --tests "com.veycad.app.EditableMontageCompilerTest" --tests "com.veycad.app.MontageEffectBindingsTest"`; ожидается FAIL.
- [ ] **Step 3: Реализовать** importer, compiler и binding. No-op compile возвращает исходный legacy-граф без округления его расписания. Для изменённой ревизии карты берутся из исходных scheduled frames, локальные tracks сохраняют оригинальное время, trims вырезают существующую часть, расширение удерживает крайний ключ.
  Привязку существующих эффектов объявить явно в `MontageEffectBindings`: transition nodes → входящий клип; flash около границы → этот стык; authored music pulses → Music; прочие пересекающие клипы слои → локальные сегменты. Не определять musical cue только по близости к биту. Перечислить ID/kind всех текущих рецептов в тестах, включая повторную фазу heartbeat. Для удалённой части secondary-role окна скрывается соответствующий слой, первичный PTS продолжает движение.
- [ ] **Step 4: Повторить filtered tests**, затем `.\gradlew.bat :app:testDebugUnitTest --tests "com.veycad.app.HeartbeatDirectorTest" --tests "com.veycad.app.DualityLoopDirectorTest" --tests "com.veycad.app.EffectOrchestratorTest"`; ожидается PASS и неизменная legacy-композиция.
- [ ] **Step 5: Проверить и закоммитить scoped изменения** `feat(timeline): сохранять локальные треки и привязки эффектов при компиляции`.

### Task 3: Команды trim/reorder/effects и история действий

**Files:**
- Create: `app/src/main/java/com/example/autoedit/MontageTimelineEditor.kt`, `MontageUndoHistory.kt` в этой же директории.
- Test: `app/src/test/java/com/example/autoedit/MontageTimelineEditorTest.kt`, `MontageUndoHistoryTest.kt` в этой же директории.

**Interfaces:**
- Consumes: `EditableMontageProject`, compiler/layout и effect anchors из задач 1–2.
- Produces: `sealed interface TimelineCommand` с вложенными `Trim(clipId: String, edge: ClipEdge, frame: Long)`, `Move(clipId: String, toIndex: Int)`, `SetTransition(incomingClipId: String, transition: MontageGraph.Transition)`, `SetFlashEnabled(effectId: String, enabled: Boolean)`, `MoveFlashToNext(effectId: String)`, `RestoreBaseline`; `ClipEdge.START/END`.
- Produces: `MontageTimelineEditor.apply(project: EditableMontageProject, command: TimelineCommand): EditOutcome`; `EditOutcome.Applied(project, changedClipIds: Set<String>)` / `Rejected(reason: String)`.
- Produces: `MontageUndoHistory.record(before: MontageRevision, after: MontageRevision)`, `undo(current: MontageRevision): MontageRevision?`, `redo(current: MontageRevision): MontageRevision?`. Undo/redo используют новый номер `current.number + 1`, а не возвращают старый номер ревизии. Одна завершённая drag-операция записывает одно действие.

- [ ] **Step 1: Написать red tests** `trimNineFramesKeepsRemainingMotion`, `extendNineFramesRestoresSourceMaterial`, `extensionBeyondSourceIsRejected`, `aBaBecomesBaa`, `oneFrameClipIsValidButZeroIsRejected`, `movingLastFlashIsRejected`, `firstClipKeepsDormantIncomingTransition`, `undoResetAndRedoAreReversible`.
  ```kotlin
  assertEquals(1_300_000L, trimmedA1.timeMap.sourceTimeUs(trimmedA1.visible.start))
  assertEquals(51L, trimmedA1.visible.count) // исходные 60 кадров минус 9
  assertEquals(69L, extendedA1.visible.count)
  assertEquals(3_300_000L, extendedA1.timeMap.sourceTimeUs(extendedA1.visible.endExclusive))
  assertEquals(listOf("B", "A1", "A2"), moved.current.clips.map { it.id })
  assertEquals(1, moved.current.effects.count { it.logicalId == "flash-B" })
  assertEquals(1L, oneFrame.visible.count); assertTrue(zeroFrames is EditOutcome.Rejected)
  assertEquals(before, lastFlashRejectedProject); assertEquals(3L, undoAfterOneEdit.number)
  ```
- [ ] **Step 2: Запустить** `.\gradlew.bat :app:testDebugUnitTest --tests "com.veycad.app.MontageTimelineEditorTest" --tests "com.veycad.app.MontageUndoHistoryTest"`; ожидается FAIL.
- [ ] **Step 3: Реализовать** команды и историю; валидировать через compiler до Applied. Для 60 FPS шаг 0,3 s равен 18 кадрам, для 30 FPS — 9. Trim допускает расширение внутри исходника; MoveFlashToNext меняет владельца, сохраняя ID/параметры, и не создаёт копии. RestoreBaseline также отменяем. Ошибочная/пустая операция не увеличивает ревизию и не очищает redo; новая содержательная операция очищает redo.
- [ ] **Step 4: Повторить filtered tests** с комбинациями trim→move→flash→undo/redo и обеими частотами кадров; ожидается PASS и одинаковая компиляция одинаковых ревизий.
- [ ] **Step 5: Проверить и закоммитить** `feat(timeline): добавить команды доводки и отмену действий`.

### Task 4: Пользовательские переходы в GLES и отдельная политика приёмки

**Files:**
- Create: `app/src/main/java/com/example/autoedit/ManualRenderOverrides.kt`, `ManualMontageAcceptance.kt` в этой же директории.
- Modify: `app/src/main/java/com/example/autoedit/MontageGraph.kt`, `EditableMontageCompiler.kt`, `HighQualityFramePlan.kt`, `MediaCodecSpeedRampRenderer.kt`, `RenderPassPlanner.kt`, `RenderedVisualSampler.kt`, `RenderedMp4Acceptance.kt` в этой же директории.
- Test: `app/src/test/java/com/example/autoedit/ManualRenderOverridesTest.kt`, `ManualMontageAcceptanceTest.kt`, `RenderPassPlannerTest.kt` в этой же директории.

**Interfaces:**
- Consumes: результат compiler задачи 2 и команды задачи 3.
- Produces: необязательные `MontageGraph.manualOverrides: ManualRenderOverrides?` и `Frame.transitionEffectsAllowed: Boolean = true`; `ManualRenderOverrides(disabledTransitionClipIds: Set<String>, disabledEffectIds: Set<String>)`.
- Produces: `ManualMontageAcceptance.evaluate(graph: MontageGraph, container: RenderedMp4Acceptance.ContainerSample, samples: List<RenderedMp4Acceptance.VisualSample>, decodedAudio: DecodedAudioQuality.Report): Report`; `Report(passed: Boolean, issues: List<String>)`.
- `metadata.generator` сохраняет исходный рецепт. Состояние ручной версии определяется manualOverrides/ревизией, а не подменой generator; automatic acceptance остаётся существующим `RenderedMp4Acceptance.evaluate(...)`.

- [ ] **Step 1: Написать red tests** `hardCutDisablesWholeWhipBundle`, `oneFrameTransitionDoesNotRestartItsPhase`, `manualClipCountIsNotAnAutomaticGrammarFailure`, `badContainerStillFailsManualAcceptance`, `legacyPassScheduleIsUnchanged`.
  ```kotlin
  assertEquals(MontageGraph.Transition.HARD_CUT, cutFrame.transitionIn)
  assertEquals(0f, cutFrame.effects.glow, 0f); assertEquals(0f, cutFrame.effects.glitch, 0f)
  assertFalse(cutFrame.transitionEffectsAllowed); assertEquals(.1f, cutFrame.redBias, 0f)
  assertEquals(originalGenerator, editedGraph.metadata.generator)
  assertTrue(validReorderedManualReport.passed); assertFalse(undecodableReport.passed)
  assertEquals(originalPhase, shortenedTransitionPhase)
  ```
- [ ] **Step 2: Запустить** `.\gradlew.bat :app:testDebugUnitTest --tests "com.veycad.app.ManualRenderOverridesTest" --tests "com.veycad.app.ManualMontageAcceptanceTest"`; ожидается FAIL.
- [ ] **Step 3: Реализовать** overrides во всех потребителях перехода: frame blend, GPU nodes, переходное transform motion, GLES profile branches и план проходов. Входящее `OPEN`/авторская сцена первого клипа сохраняется отдельно от межклипового WHIP; перестановка на первую позицию скрывает именно межклиповый переход. Sampler принимает explicit timing и источник attachments из задачи 2; его ожидания не вычисляются по старому фиксированному шаблону. Manual acceptance проверяет измеренные контейнер/декодирование/звук и исполнение ревизии; auto grammar, beat hit rate и обязательные authored accents не блокируют пользовательский выбор. Технические ошибки не становятся успешным результатом за счёт смены политики.
- [ ] **Step 4: Повторить filtered tests**, затем `.\gradlew.bat :app:testDebugUnitTest --tests "com.veycad.app.RenderPassPlannerTest" --tests "com.veycad.app.RenderedMp4AcceptanceTest" --tests "com.veycad.app.DecodedEffectSignatureTest"`; ожидается PASS. Реальное удаление whip в пикселях дополнительно проверяет задача 10.
- [ ] **Step 5: Проверить и закоммитить** `feat(render): исполнять ручные переходы без восстановления эффектов рецепта`.

### Task 5: Codec и атомарное хранение проекта с исходниками

**Files:**
- Create: `app/src/main/java/com/example/autoedit/MontageProjectCodec.kt`, `MontageProjectStore.kt`, `ProjectAttachmentCodec.kt` в этой же директории.
- Test: `app/src/test/java/com/example/autoedit/MontageProjectCodecTest.kt`, `MontageProjectStoreTest.kt` в этой же директории.

**Interfaces:**
- Consumes: модель проекта, graph v3, source attachments и importer задач 1–2.
- Produces: `MontageProjectCodec.encode(project: EditableMontageProject, writeAttachments: (FrameAttachmentTimeline) -> AttachmentRef): ByteArray`, `decode(bytes: ByteArray, readAttachments: (AttachmentRef) -> FrameAttachmentTimeline): EditableMontageProject`; формат проекта v1 с явным magic/version и длинами полей. Store передаёт callbacks codec из следующего пункта, чтобы большие planes не попадали в manifest/ByteArray. Использовать `DataInputStream`/`DataOutputStream`, не Java object serialization и не Android `org.json` в unit tests.
- Produces: `ProjectAttachmentCodec.write(timeline: FrameAttachmentTimeline, target: File): AttachmentRef`, `read(ref: AttachmentRef, root: File): FrameAttachmentTimeline`; `AttachmentRef(relativePath: String, sizeBytes: Long, sha256: String)`.
- Produces: `MontageProjectStore(filesDir: File, fault: (StorePhase) -> Unit = {})`; `create(snapshot: GeneratedMontageSnapshot): EditableMontageProject`, `load(projectId: String): EditableMontageProject`, `loadRevision(projectId: String, revision: Long): EditableMontageProject`, `save(project: EditableMontageProject)`, `deleteProject(projectId: String)`, `totalBytes(): Long`.
- Produces: `GeneratedMontageSnapshot(graph: MontageGraph, recipe: MontageStyleCatalog.Recipe, export: ExportSettings, sourceFiles: List<File>, sourceNames: List<String>, musicFile: File, captureLink: CompletedRenderStore.CaptureLink?)` и `StorePhase.ASSETS_COPIED/REVISION_WRITTEN/MANIFEST_COMMITTED` для fault injection.

- [ ] **Step 1: Написать red tests** `restartLoadsCurrentAndBaseline`, `truncatedRevisionKeepsPreviousManifest`, `diskFailureLeavesNoEditablePartialProject`, `missingAssetNamesTheFile`, `attachmentCodecKeepsBothSourceIndices`, `codecRejectsInvalidLengthsAndPaths`.
  ```kotlin
  assertEquals(edited.current, MontageProjectStore(root).load(project.id).current)
  assertEquals(1L, recovered.baseline.number); assertEquals(2L, recovered.current.number)
  assertEquals(previous.current, afterInjectedFailure.current)
  assertTrue(assetFailure.message.orEmpty().contains("video-1"))
  assertEquals(originalPlane, decodedPlane)
  assertFalse(File(root, "outside-project.mp4").exists())
  ```
- [ ] **Step 2: Запустить** `.\gradlew.bat :app:testDebugUnitTest --tests "com.veycad.app.MontageProjectCodecTest" --tests "com.veycad.app.MontageProjectStoreTest"`; ожидается FAIL.
- [ ] **Step 3: Реализовать** `filesDir/montage-projects/<id>/assets`, immutable `revisions/<n>.vmp`, baseline и manifest текущей ревизии. Копировать видео/музыку потоком с SHA-256, не загружать целый MP4 в память. Большие planes хранить отдельно; ByteArray codec содержит проверяемые ссылки и маленькие данные графа. Писать partial, flush/fsync, затем атомарно заменять manifest; ошибка оставляет прежнюю опубликованную ревизию. В начале загрузки проверять версию, длины, хеши, существование assets и принадлежность относительных путей проекту. Content equality массивов time maps должна переживать round trip. Неполные каталоги без committed manifest не отображаются как проекты; cleanup не удаляет активные файлы.
- [ ] **Step 4: Повторить filtered tests** с новым экземпляром store, сбоями каждой StorePhase, оборванным codec и повреждённым plane; ожидается PASS. Проверить, что deleteProject не затрагивает экспортированные MP4 и соседние проекты.
- [ ] **Step 5: Проверить и закоммитить** `feat(storage): сохранять проекты монтажа и исходники атомарно`.

### Task 6: Создание проекта из автосборки и совместимость библиотеки

**Files:**
- Modify: `app/src/main/java/com/example/autoedit/EditSession.kt`, `CompletedRenderStore.kt`, `RenderWorkspace.kt`, `MainActivity.kt` в этой же директории.
- Modify: `app/src/uiTest/java/com/example/autoedit/UiTestApplication.kt`, `app/src/androidTest/java/com/example/autoedit/UiTestFixtureRule.kt`.
- Test: `app/src/test/java/com/example/autoedit/CompletedRenderStoreTest.kt`, `RenderWorkspaceTest.kt` в этой же директории.
- Create test: `app/src/androidTest/java/com/example/autoedit/MontageProjectPublicationTest.kt`.

**Interfaces:**
- Consumes: `GeneratedMontageSnapshot`, `MontageProjectStore.create(snapshot)` задачи 5.
- Extends: `EditSession.RenderSummary` необязательным `snapshot: GeneratedMontageSnapshot? = null`, чтобы тестовый RenderEngine оставался совместимым. Native engine заполняет snapshot выбранным winner-графом и фактическими render settings.
- Extends: `CompletedRenderStore.Entry` полями `projectId: String? = null`, `projectRevision: Long? = null`; `publish(..., captureLink: CaptureLink? = null, projectId: String? = null, projectRevision: Long? = null): Entry`.
- Produces: `RenderWorkspace.projectsDirectory(filesDir: File): File`; `CompletedRenderStore.find(filesDir: File, fileName: String): Entry?` для открытия любого результата библиотеки, а не только последнего.

- [ ] **Step 1: Написать red tests** `winnerIsSavedBeforeScratchDeletion`, `newImportAndCacheClearKeepProjectAssets`, `oldMp4StillLoadsWithoutProject`, `projectSaveFailureStillPublishesUsableMp4`, `captureLinkSurvivesNewProject`, `crashBetweenProjectAndMp4LeavesPreviousResult`.
  ```kotlin
  assertEquals(winnerGraph, loaded.originalGraph)
  assertTrue(loaded.sources.all { File(projectRoot, it.relativePath).isFile })
  assertNull(legacyEntry.projectId); assertTrue(legacyEntry.file.isFile)
  assertNull(entryAfterStoreFailure.projectId); assertTrue(entryAfterStoreFailure.file.length() > 0)
  assertEquals(originalCaptureLink, entry.captureLink)
  assertEquals(previousEntry, latestAfterFailedPublication)
  ```
- [ ] **Step 2: Запустить unit tests** `.\gradlew.bat :app:testDebugUnitTest --tests "com.veycad.app.CompletedRenderStoreTest" --tests "com.veycad.app.RenderWorkspaceTest"` и instrumented `.\gradlew.bat :app:connectedUiTestAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.veycad.app.MontageProjectPublicationTest`; ожидается FAIL.
- [ ] **Step 3: Сохранить snapshot winner в native RenderSummary**, создать проект перед публикацией MP4 и перед удалением pending directory. В snapshot включить настоящие размеры/FPS, выбранную музыку и подготовленный исходник Auto Camera; к графу привязать winner frameAttachments для sourceIndex 0 и `result.secondaryAnalysis?.attachments` для sourceIndex 1. Сбой сохранения проекта публикует готовый MP4 без projectId и с понятной причиной недоступности доводки; частичная ссылка не записывается. Поддержать чтение legacy metadata и сохранение project fields при markSaved/recoverCaptureLinks. Очистка кэша/новый импорт не затрагивают project assets, размер проектов учесть отдельно от временных файлов. UI fake engine возвращает проверяемый snapshot без вызовов ML.
- [ ] **Step 4: Повторить команды Step 2** и `.\gradlew.bat :app:testDebugUnitTest --tests "com.veycad.app.CaptureSessionStoreTest"`; ожидается PASS. Для реального process death нужен отдельный цикл в задаче 10: Activity.recreate сам по себе недостаточен.
- [ ] **Step 5: Проверить и закоммитить** `feat(storage): связывать автоматические результаты с редактируемым проектом`.

### Task 7: Сессия доводки и экспорт зафиксированной ручной ревизии

**Files:**
- Create: `app/src/main/java/com/example/autoedit/SavedMontageRenderEngine.kt`, `ProjectAnalysisResolver.kt`, `MontageEditorState.kt` в этой же директории.
- Modify: `app/src/main/java/com/example/autoedit/EditSession.kt`, `VeykadRenderInspector.kt` в этой же директории; `app/src/uiTest/java/com/example/autoedit/UiTestApplication.kt`.
- Create test: `app/src/androidTest/java/com/example/autoedit/ManualMontageSessionTest.kt`.

**Interfaces:**
- Consumes: compiler/editor/history, project store, manual acceptance и `MediaCodecSpeedRampRenderer.Request`.
- Produces: `SavedMontageRenderEngine.render(request: SavedMontageRenderRequest): SavedMontageRenderResult`; native implementation в том же файле. Request содержит `context`, `project`, `outputFile`, `range: TimelineRange? = null`, `preview: Boolean = false`, `checkCancelled: () -> Unit`. Result содержит `frameCount: Int`, `acceptance: ManualMontageAcceptance.Report`.
- Produces: `ProjectAnalysisResolver.ensureCoverage(project: EditableMontageProject, graph: MontageGraph, checkCancelled: () -> Unit): MontageGraph` — дополняет отсутствующие source PTS, сохраняя существующие planes/refinements.
- Extends: constructor `EditSession` параметром `savedRenderer: SavedMontageRenderEngine = NativeSavedMontageRenderEngine`; fake auto renderer остаётся отдельным engine.
- Produces: `EditSession.editorState: LiveData<MontageEditorState>`, `openProject(projectId: String)`, `applyTimelineCommand(command: TimelineCommand): Boolean`, `undoTimeline(): Boolean`, `redoTimeline(): Boolean`, `exportProject(): Boolean`.
- Produces: `MontageEditorState(project: EditableMontageProject? = null, saving: Boolean = false, exporting: Boolean = false, canUndo: Boolean = false, canRedo: Boolean = false, error: String? = null)`. Boolean означает принятие команды в процессную очередь; окончательный результат приходит через state.

- [ ] **Step 1: Написать red tests** `manualExportNeverCallsAutomaticDirector`, `exportFreezesRevision`, `cancelDoesNotPublishPartialMp4`, `exportFailureKeepsPreviousEntry`, `extendedWindowAddsOnlyMissingAnalysis`, `closedActivityDoesNotOwnTheWorker`, `lateCallbackFromPreviouslyOpenedProjectIsDiscarded`.
  ```kotlin
  assertEquals(0, fakeAutoRenderer.calls); assertEquals(1, fakeSavedRenderer.calls)
  assertEquals(editedRevision, fakeSavedRenderer.lastRequest.project.current.number)
  assertFalse(session.applyTimelineCommand(commandWhileExporting))
  assertEquals(previousEntry, stateAfterCancel.entry)
  assertEquals(originalSavedMask, enrichedGraph.sourceAttachments.first().timeline.frames.first().mask)
  assertEquals(editedRevision, published.projectRevision)
  assertEquals("project-B", stateAfterLateProjectACallback.project!!.id)
  ```
- [ ] **Step 2: Запустить** `.\gradlew.bat :app:connectedUiTestAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.veycad.app.ManualMontageSessionTest`; ожидается FAIL.
- [ ] **Step 3: Реализовать** процессную очередь: команда→compile/validate→атомарное сохранение→публикация editorState; при ошибке вернуть последнее сохранённое состояние. Все editor callbacks сверяют projectId и поколение открытия, чтобы поздний результат другого проекта не заменял текущий. Open читает текущий черновик проекта; история undo создаётся для текущего открытия и сохраняется при пересоздании Activity, но не после гибели процесса. Для экспорта зафиксировать сохранённую ревизию, заблокировать правки до завершения и использовать существующие wake lock/lease/cancel/publication правила.
  Native saved renderer вызывает compiler, resolver, GPU renderer и техническую приёмку напрямую. Он не вызывает `VeycadAutomaticEditor.render`, режиссёра или candidate selection. Разрешать только доступный рецепт и проверенные assets. Недостающие данные для расширения получать существующим `MediaFrameVisualAnalyzer`/matte path по соответствующему источнику; ни один сохранённый refinement не заменять новым анализом. Результат публиковать отдельным MP4 с projectId/revision/CaptureLink, ручную версию обозначить «С ручными правками».
- [ ] **Step 4: Повторить filtered test** и instrumented `com.veycad.app.RenderProgressScreenTest`; ожидается PASS, включая прежнюю отмену auto render. Проверить, что save/export error не удаляет черновик и последний успешный результат.
- [ ] **Step 5: Проверить и закоммитить** `feat(render): экспортировать сохранённую ручную ревизию монтажа`.

### Task 8: Proxy с глобальным временем, музыкой и защитой от устаревших заданий

**Files:**
- Create: `app/src/main/java/com/example/autoedit/MontagePreviewCoordinator.kt`, `AudioWindowPcm.kt` в этой же директории.
- Modify: `app/src/main/java/com/example/autoedit/EditSession.kt`, `MontageEditorState.kt`, `SavedMontageRenderEngine.kt`, `MediaCodecSpeedRampRenderer.kt`, `AudioExportPlan.kt`, `AacEncoderMuxer.kt`, `RenderedVisualSampler.kt` в этой же директории.
- Test: `app/src/test/java/com/example/autoedit/MontagePreviewCoordinatorTest.kt`, `AudioWindowPcmTest.kt`, `VeycadEngineCoreTest.kt` в этой же директории.
- Create test: `app/src/androidTest/java/com/example/autoedit/MontagePreviewSessionTest.kt`.

**Interfaces:**
- Consumes: saved renderer, editorState и TimelineRange предыдущих задач.
- Produces: `PreviewKey(projectId: String, revision: Long, range: TimelineRange, width: Int, height: Int, fps: Int)`; `MontagePreviewCoordinator.request(key: PreviewKey, render: (checkCancelled: () -> Unit) -> File, deliver: (PreviewKey, File) -> Unit)`, `invalidate()`.
- Produces: `EditSession.requestProjectPreview(range: TimelineRange? = null)` и `stopProjectPreview()`; `PreviewState.Idle`, `Updating(key: PreviewKey)`, `Ready(key: PreviewKey, file: File)`, `Failed(message: String)` добавляются в `MontageEditorState`. null range означает полный ролик; диапазон стыка включает предыдущий и следующий клипы по layout.
- Produces: `AudioExportPlan.window(trackDurationUs: Long, globalStartUs: Long, durationUs: Long): List<Segment>`; `AudioWindowPcm.slice(bytes: ByteArray, sampleRate: Int, channels: Int, globalStartUs: Long, durationUs: Long): ByteArray` для interleaved signed PCM16. Индекс вычисляется от абсолютного числа samples, включая переход через конец повторяемого трека.
- Extends: `AacEncoderMuxer.muxMusicFile(..., checkCancelled: () -> Unit = {}, outputStartUs: Long = 0L)`; `muxMusic(..., durationUs: Long, outputStartUs: Long = 0L)`. Новый аргумент после существующих сохраняет совместимость вызовов.

- [ ] **Step 1: Написать red tests** `lateOldProxyIsDiscarded`, `exitCancelsAndDoesNotDeliver`, `proxyKeepsGlobalShaderTimeAndRebasesEncoderPts`, `audioWindowStartsAtTheSameMusicSample`, `loopBoundaryKeepsStereoChannels`.
  ```kotlin
  assertEquals(listOf(3L), deliveredKeys.map { it.revision }) // rev2 завершилась после rev3
  assertEquals(0, deliveryAfterInvalidate)
  assertEquals(0L, window.frames.first().outputTimeUs)
  assertEquals(1_000_000L, window.frames.first().globalOutputTimeUs)
  assertArrayEquals(shortArrayOf(30, 31, 40, 41, 10, 11), decodedStereoWindow)
  // PCM frame pairs [10,11], [20,21], [30,31], [40,41], rate=4; start=0.5s, duration=0.75s.
  assertEquals(30, previewKey.fps); assertEquals(fullFrame.layer, proxyFrame.layer)
  ```
- [ ] **Step 2: Запустить** `.\gradlew.bat :app:testDebugUnitTest --tests "com.veycad.app.MontagePreviewCoordinatorTest" --tests "com.veycad.app.AudioWindowPcmTest"` и instrumented `com.veycad.app.MontagePreviewSessionTest`; ожидается FAIL.
- [ ] **Step 3: Реализовать** сначала полный frame schedule, затем output window: source PTS/layer/phase/globalOutputTimeUs сохраняются, только encoder PTS обнуляется. Перевести authored-title/Sigma/GLES временные потребители на globalOutputTimeUs; collector и MP4 timestamps остаются локальными. Музыку брать от globalStartUs после одинаковой подготовки PCM/headroom, а не повторно нормализовать каждый кусок proxy. Ресурсный и файловый пути используют один window-контракт.
  Coordinator принадлежит EditSession и не удерживает Activity. Задания serial; новая ревизия отменяет и дожидается unwinding старого render, сверяет key перед доставкой и очищает только свои partial/proxy файлы. Правки не блокируются proxy-заданием; экспорт отменяет proxy и блокирует правки. Кэш хранится в cacheDir; его отсутствие приводит к повторному рендеру. Proxy сохраняет 30/60 FPS, длинная сторона 480 px, обе стороны чётные с минимумом 2 px, bitrate 1 000 000. Полный просмотр использует ту же ревизию и FPS.
- [ ] **Step 4: Повторить filtered tests**, instrumented preview test и `VeycadEngineCoreTest`; ожидается PASS. Проверить неподвижный global cue при window start≠0 и совпадение PCM после музыкального loop. Пиксельная/AAC проверка находится в задаче 10.
- [ ] **Step 5: Проверить и закоммитить** `feat(preview): согласовать предпросмотр с временем эффектов и музыки`.

### Task 9: Экран «Довести монтаж», таймлайн и доступные команды

**Files:**
- Create: `app/src/main/java/com/example/autoedit/MontageEditorActivity.kt`, `MontageTimelineView.kt`, `MontageTimelineGeometry.kt`, `MontageSourceFrameLoader.kt` в этой же директории.
- Create: `app/src/main/res/layout/activity_montage_editor.xml`, `app/src/main/res/values/strings_montage_editor.xml`.
- Modify: `app/src/main/AndroidManifest.xml`, `app/src/main/res/layout/activity_main.xml`, `app/src/main/java/com/example/autoedit/MainActivity.kt`.
- Create test: `app/src/test/java/com/example/autoedit/MontageTimelineGeometryTest.kt`; `app/src/androidTest/java/com/example/autoedit/MontageEditorScreenTest.kt`.
- Modify: `app/src/androidTest/java/com/example/autoedit/UiTestFixtureRule.kt`, `app/src/uiTest/java/com/example/autoedit/UiTestApplication.kt` для готового редактируемого результата и контролируемого saved renderer.

**Interfaces:**
- Consumes: команды задачи 3 и процессный editorState/requestPreview/exportProject задач 7–8.
- Produces: `MontageEditorActivity.EXTRA_PROJECT_ID`; Activity `exported=false`, вход только из существующих результата/библиотеки.
- Produces: `MontageTimelineView.bind(project: EditableMontageProject)`, `onCommand: ((TimelineCommand) -> Unit)?`, `onScrub: ((clipId: String, frame: Long) -> Unit)?`; `MontageTimelineGeometry.frameAt(xPx: Float, scrollPx: Float, pixelsPerFrame: Float): Long` и layout/hit-test для clips/edges/boundaries.
- Produces: `MontageSourceFrameLoader.load(project: EditableMontageProject, sourceIndex: Int, sourceTimeUs: Long, deliver: (Bitmap) -> Unit)` и `cancel()`; существующие `SequentialBitmapDecoder`/`VideoDisplayOrientation` вызываются вне main thread. Loader coalesces быстрый scrub и доставляет только последний запрос.
- IDs: `refineMontageButton`, `montageTimeline`, `montageEditorVideo`, `montagePreviewStatus`, `montageStartEdge`, `montageEndEdge`, `montageFrameBack`, `montageFrameForward`, `montageMoveLeft`, `montageMoveRight`, `montageUndo`, `montageRedo`, `montageRestore`, `montageFullPreview`, `montageExport`, `montageDeleteProject`.

- [ ] **Step 1: Написать red tests** `handlesTrimButBodyReorders`, `oneGestureCreatesOneUndoStep`, `frameButtonsWorkAt30And60`, `libraryOpensItsOwnProject`, `recreatedActivityKeepsDraftAndSelection`, `lastBoundaryHasNoFlashMoveAction`, `screenExitDoesNotPlayOldProxy`.
  ```kotlin
  assertEquals(listOf("B", "A1", "A2"), projectAfterBodyDrag.current.clips.map { it.id })
  assertEquals(beforeIds, projectAfterHandleDrag.current.clips.map { it.id })
  assertEquals(beforeRevision + 1L, afterSingleGesture.current.number)
  assertEquals(beforeFrame + 1L, afterFrameButton.visible.endExclusive)
  assertEquals(libraryEntry.projectId, openedProject.id)
  assertFalse(legacyRefineButton.isShown); assertFalse(exportButton.isEnabled) // во время экспорта
  ```
- [ ] **Step 2: Запустить** `.\gradlew.bat :app:testDebugUnitTest --tests "com.veycad.app.MontageTimelineGeometryTest"` и instrumented `com.veycad.app.MontageEditorScreenTest`; ожидается FAIL.
- [ ] **Step 3: Реализовать** экран в существующем стиле. Подрезка/перестановка фиксируются на ACTION_UP; ACTION_CANCEL не записывает ревизию. Pinch/scroll не считаются trim. У короткого клипа с пересекающимися зонами ручек действует явно выбранный START/END; кнопочные аналоги доступны всегда. Custom View публикует accessibility actions для выбора/шага/перестановки, touch targets — минимум 48 dp.
  Нажатие стыка открывает обычную склейку и поддерживаемые переходы, а также отдельные строки существующих вспышек. Точные подписи: «Довести монтаж», «Обычная склейка», «Отключить вспышку», «На следующий стык», «Кадр исходника», «Обновляю предпросмотр…», «Вернуть автосборку», «Сохранить новую версию». Raw scrub не выдаётся за композитный preview; пока Updating, старый proxy не воспроизводится. На выходе сохраняется черновик, proxy отменяется, экспорт не стартует.
  Результат/библиотека разрешают Entry через `CompletedRenderStore.find`, не через last entry сессии. Кнопка открывает текущий черновик связанного проекта; для старого экспорта с более новой ревизией подпись «Черновик проекта» объясняет отличие от просматриваемого MP4. Потерянный asset показывает displayName и блокирует экспорт. Удаление проекта явно описывает удаление исходников для доводки; готовые MP4 остаются. Размер проекта показывается отдельно от временных файлов. «Восстановить автосборку» не запускает режиссёра.
- [ ] **Step 4: Повторить filtered tests** и полный `pwsh -NoProfile -File tools/run_ui_tests.ps1 -Serial emulator-5556 -AvdName AutoEditUi_API36`; ожидается PASS без пропущенных source test identities. Проверить talkback/button путь, возврат из библиотеки, пересоздание, случайный ACTION_CANCEL и disabled-состояние экспортируемой ревизии. Эмулятор предварительно запущен на указанном отдельном serial.
- [ ] **Step 5: Проверить и закоммитить** `feat(ui): добавить экран ручной доводки сгенерированного монтажа`.

### Task 10: Независимые проверки декодированного MP4 и пользовательская приёмка

**Files:**
- Create: `app/src/androidTest/java/com/example/autoedit/ManualMontageSyntheticMedia.kt`, `ManualMontageRenderDeviceTest.kt` в этой же директории.
- Create: `tools/quality_manual_montage_report.py`, `tools/test_quality_manual_montage_report.py`, `docs/testing/manual-montage-review.md`.
- Existing runner: `tools/run_tests.py` уже подхватывает `test_*.py` через unittest discovery; изменений не требует.

**Interfaces:**
- Consumes: native saved renderer и реальные project/UI пути задач 1–9. Эмуляторный fake RenderEngine не является oracle качества MP4.
- Produces: `ManualMontageSyntheticMedia.videoWithCodes(target: File, sourceCode: Int, fps: Int, durationUs: Long, vfr: Boolean = false): File`, `musicWithCodes(target: File): File`, `decodeCodes(file: File): List<DecodedFrameCode>`; `DecodedFrameCode(ptsUs: Long, sourceCode: Int, sourceFrameCode: Int)`.
- Produces: Python CLI `python tools/quality_manual_montage_report.py --evidence <evidence.json> --output <report.json>`. Evidence содержит source/output SHA-256, рецепт, projectId/revision, expected/decoded frame codes, container/audio metrics, изменение эффектов и ссылки на локальные записи просмотра. PASS допускается только при наличии всех обязательных технических измерений; humanReview имеет отдельное значение `pending/accepted/rejected`.

- [ ] **Step 1: Написать red tests** `nativeTrimAndReorderMatchDecodedCodes`, `nativeHardCutHasNoWhipAndMovedFlashAppearsOnce`, `nativeProxyMatchesFullExportAtGlobalTime`, `vfrSourceSelectsMeasuredPts`, `dualityDoesNotSwapMasksOrSources`; Python `test_report_rejects_missing_output_hash`, `test_report_rejects_wrong_revision`, `test_report_keeps_human_review_pending`.
  ```kotlin
  assertEquals(listOf(2, 1, 1), decodedClipWindows.map { it.sourceCode }) // B–A1–A2
  assertEquals(expectedFrameCodes, decodedFrameCodes)
  assertEquals(1, measuredMovedFlashCount); assertEquals(0, measuredWhipAtDisabledBoundary)
  assertTrue(abs(videoDurationUs - expectedDurationUs) <= frameTimeUs(1, fps))
  assertTrue(abs(audioOffsetSamples) <= 1024) // максимум один AAC access unit
  ```
- [ ] **Step 2: Запустить** instrumented `com.veycad.app.ManualMontageRenderDeviceTest` и `python -m unittest discover -s tools -p "test_quality_manual_montage_report.py"`; ожидается FAIL до helper/report реализации.
- [ ] **Step 3: Создать** независимые источники с пространственным source/frame кодом и звуковыми маркерами; expected codes выводить из сценария и входных PTS, а не из проверяемого compiler. Декодировать настоящие MP4, не принимать inspector planned-values за измеренное исполнение. Для pixel/effect сравнения использовать измеренные существующим sampler признаки и одинаковый размер изображений; lossy encode не требует byte equality. AAC сравнивать после учёта encoder delay и одного access unit; исходный PCM window дополнительно проверяется точно в задаче 8.
  Native generic fixture проверяет кадровый/аудиоконтракт. Для каждого доступного рецепта дополнительно использовать его реальный граф и сохранённую штатную музыку; записать no-op и edited render, проверяя сохранение визуального профиля. DUALITY использует два различных sourceCode. Sigma проверять только как legacy regression, не открывать её production UI. Report проверяет связь evidence с hash/revision и оставляет человеческую приёмку отдельной.
- [ ] **Step 4: Запустить финальные gates** `.\gradlew.bat clean baselineVerify`, затем полный `tools/run_ui_tests.ps1` и native render tests на выделенном устройстве. Ожидается PASS на последнем коммите; выполнить Python tests штатным `python tools/run_tests.py`, который уже включает новый test по маске `test_*.py`. Один повторный прогон после устранения обнаруженных дефектов допустим; неизменные успешные проверки повторять без причины не нужно.
- [ ] **Step 5: Выполнить ручной сценарий и зафиксировать evidence** на физическом Android: создать автосборку→вернуть 0,3 s эмоции→B–A–A→обычная склейка вместо whip→перенести вспышку→сохранить черновик. После завершения операций выполнить `adb -s <device-serial> shell am force-stop com.veycad.app.uitest`, запустить приложение заново и проверить черновик, затем экспортировать новую версию и просмотреть оба MP4 с обычной скоростью и звуком. Использовать `.uitest` на устройстве для независимых данных; источник обычного end-to-end просмотра выбирается локально через приложение. Видео, APK и большие отчёты не коммитить; в PR указать фактические команды, device/build, hashes и состояние humanReview. Реальный force-stop проводится вне работающего instrumentation; Activity.recreate не заменяет его.
- [ ] **Step 6: Закоммитить тесты и инструкции** `test(render): проверить ручную доводку по декодированным MP4`. При незавершённой приёмке оставить PR draft с конкретным оставшимся сценарием; зелёные unit/UI tests не означают завершённую пользовательскую приёмку.

## Контроль полноты и завершение

| Требование спецификации | Задачи |
| --- | --- |
| ±1 кадр, trim/расширение без смены скорости | 1, 2, 3, 9, 10 |
| Перестановка и сохранение локальных ключей | 2, 3, 9, 10 |
| Стыки, весь whip bundle, перенос вспышки без копии | 2, 3, 4, 9, 10 |
| Фазы длинных эффектов, secondary roles и per-source attachments | 2, 4, 7, 10 |
| Undo/redo/reset, сохранение после перезапуска | 3, 5, 7, 9, 10 |
| Проектные assets, cache cleanup, disk/process failure | 5, 6, 7, 10 |
| Общий proxy/export план и защита от stale callbacks | 1, 4, 7, 8, 9, 10 |
| Музыка, фактические dimensions/FPS, AAC timestamps | 6, 7, 8, 10 |
| Старые MP4, Auto Camera/CaptureLink, DUALITY | 2, 5, 6, 9, 10 |
| Техническая и человеческая приёмка, scoped commits/PR | 4, 10 и Global Constraints |

Перед выполнением прочитать spec и этот план, сверить рабочую ветку/удалённый head и пройти исходный `baselineVerify`. Сбой исходного gate сначала классифицировать и показать пользователю; не объявлять его результатом новой функции. Конкретные команды и результаты реализации будут добавляться в PR по мере появления.

План не разрешает слияние PR и не утверждает готовность функции. Рекомендуется последовательное native-выполнение в этом worktree и общее независимое ревью перед окончательной приёмкой: mapping, effects, durable assets и preview используют тесно связанные интерфейсы. Альтернатива — subagent-driven выполнение с отдельным реализатором и reviewer для каждой задачи; это увеличит число ревью и стоимость контекста. Способ выполнения выбирает пользователь после просмотра плана.
