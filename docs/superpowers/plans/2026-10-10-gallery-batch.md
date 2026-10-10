# Пакетный монтаж из галереи Implementation Plan

> Согласование интеграции 2026-10-10: модель проекта/ID/ревизий, долговечные assets, ProjectClock/SourceTimeMap и общий store принадлежат PR #15 (`codex/hybrid-mode-design`, исходная основа `74b58038`). Текст/editor/layout/STT принадлежат PR #12 (Whisper за SpeechTranscriber). Эта запись заменяет самостоятельный EditProjectStore и второй clock/snapshot как источник истины в первоначальных G1/G3 ниже; требования импорта, сохранности, exact timing и проверки экспорта сохраняются. Адаптеры реализуются поверх закоммиченных проверенных контрактов владельцев. [План текста #14](2026-10-10-text-subtitles.md) обновлён для общей интеграции.

## Уточнение задач общей основы

- G1A: MediaSource/MediaSourceSet/GalleryImportPolicy и MediaInputInspector, независимые от хранилища. IDs и порядок выбора сохраняются, даже если общий asset store дедуплицирует одинаковое содержимое.
- G1B: URI → ограниченный copy/hash → owned ProjectAsset → атомарный source draft envelope под общим project ID. До graph/music не создавать фиктивный HybridRevision. Все per-file errors, cancellation/rollback, неизвестный размер URI, свободное место и лимиты первоначального G1 остаются обязательными.
- Договориться с владельцем #15 о pre-render draft, lease, delete-sources без удаления опубликованного MP4 и durable migration marker. Envelope хранит source metadata/order, а не конкурирующую пользовательскую модель. Последующий граф переводит тот же ID в настоящий общий проект.
- G2 сохраняет полный список источников в движке и максимум два decoder cursor; существующие capture/one/two-source адаптеры сохраняются.
- G3 сохраняет точный legacy PTS и долговечные graph/attachments/capture links через общую ревизию, SourceTimeMap и store #15. MontageTimeMap/RenderProjectSnapshot допустимы только как adapters/views общего состояния, без собственного project ID/clock/history или второго хранилища.
- G4–G8 продолжаются после соответствующих контрактов; нейтральный анализ/режиссура/UI/галерейная QA остаются ответственностью этого потока.
- Исходный звук, три режима mix и STT для смонтированной речи до музыки добавляются как интеграционные задачи обновлённого #14. Второй Vosk/редактор не создаётся; captions готового MP4 остаются отдельным согласованным сценарием.
- Исполнение с агентами и ревью каждой части одобрено. Сейчас проверяем эмуляторы; Samsung A25 и человеческая приёмка отдельно не подтверждены. Не ждать телефона для продолжения реализации.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Из 1–20 сторонних видео автоматически собрать разнообразный MP4 без съёмки внутри Veycad и обязательного наличия людей.

**Architecture:** Сначала ввести общее хранение проектов, полный список источников и единый контракт времени, сохранив действующие рецепты и Auto Camera. Затем добавить нейтральный анализ галереи и отдельный `GALLERY_MONTAGE`. GPU-рендер удерживает максимум два декодера, а проверка экспорта разрешает каждый кадр через его фактический источник.

**Tech Stack:** Kotlin/JVM, Android API 26–36, MediaExtractor/MediaCodec, GLES 2.0, текущие Android Views, JUnit4, Espresso, существующие Python quality tools.

**Spec:** [2026-10-10-gallery-batch-design.md](../specs/2026-10-10-gallery-batch-design.md), подтверждена пользователем 2026-10-10.

## Global Constraints

- 1–20 видео, суммарно до 30 минут; результат 15, 30 или 60 секунд, по умолчанию 30.
- Пригодный исходный клип может быть от 0,5 секунды; результат короче секунды не экспортируется.
- Обычная скорость; H.264/AAC MP4, 9:16, 720p либо существующая опция 1080p.
- Максимум два одновременно открытых декодера; анализ не удерживает битмапы всего архива.
- Базовый подтверждённый вход — H.264/SDR; HDR не получает фиктивного преобразования цвета.
- Локальная обработка без аккаунта, нового сетевого разрешения и передачи кадров.
- Heartbeat/FEAR требуют один исходник, DUALITY два; Sigma остаётся недоступной.
- Музыка нового рецепта: отдельный маршрут `neon_drift`; авторские профили старых рецептов не подставляются.
- Исходники опубликованного редактируемого проекта сохраняются при новом импорте; удаление исходников явно отключает повторное редактирование, но сохраняет MP4.
- Отмена отражается на UI в течение секунды и освобождает воркер в течение пяти секунд на проверенном устройстве.
- Ветки `codex/*`, scoped staging, Conventional Commits; публикация через PR, слияние только после явного одобрения владельца.

## Review Focus

1. URI облачного провайдера, неизвестный размер и потеря доступа во время копирования — явная ошибка файла и сохранность предыдущего проекта; тест G1/G6.
2. Одинаковое содержимое под разными URI и короткие статичные пейзажи — отсутствие искусственного разнообразия и обязательного лица; тест G4/G5.
3. Поворот 90/180/270°, VFR, ненулевой первый PTS и последний неполный GOP — правильная геометрия/источник без кадра за EOF; тест G2/G7.
4. Перезапуск между записью метаданных и публикацией MP4, активный lease при удалении проекта — восстановление без удаления используемых файлов; тест G1/G3.
5. Auto Camera и ссылки `CompletedRenderStore.CaptureLink` — прежние дубль, рекомендация и повторный просмотр сохраняются; тест G3/G6/G8.

## Основа, ветки и зависимости

План составлен по `main=a0395e26b93463f91520181a2eeb09dfd05e78a3`; namespace и
package — `com.veycad.app`, хотя пути Kotlin пока содержат `com/example/autoedit`.
Не переименовывать каталоги ради этой задачи. Перед исполнением прочитать обе
спецификации/планы, проверить свежую main и `AGENTS.md`, рабочую ветку и status.

G1–G3 — общая основа для галереи и текста; её реализовать один раз в
`codex/gallery-foundation`, с отдельным коммитом каждого законченного шага.
После её проверок подготовить PR, затем G4–G8 в `codex/gallery-batch`.
Текстовый план использует результаты G1–G3 и не создаёт параллельные типы.
Если основа ещё не слита, зависимые PR делать stacked с явно указанной базой;
не сливать её автоматически ради продолжения. Планы/спецификации из draft
PR #13/#14 должны быть доступны исполнителю; продуктовый код в их ветки
проектирования не добавлять. Метод исполнения пока не выбран.

Команды ниже выполняются из корня соответствующего worktree в PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:PYTHON = 'C:\Users\rexar\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
$env:ANDROID_SERIAL = 'emulator-5556'
.\gradlew.bat :app:testDebugUnitTest --tests 'com.veycad.app.MediaSourceSetTest'
```

На этой машине `python` в PATH — Windows Store alias; указанный Python
3.12.14 проверен. На другой машине задать свой рабочий runtime через `PYTHON`
или Gradle `-PpythonExecutable`. SDK/local setup сверять с `WINDOWS.md`;
локальные пути/настройки не добавлять в Git. Targeted instrumented class
запускается `connectedUiTestAndroidTest` с
`-Pandroid.testInstrumentationRunnerArguments.class=com.veycad.app.<Class>`.

Для каждой задачи: тест действительно падает до изменения продукта; после
изменения проходит вместе с затронутыми существующими тестами. Ожидаемая
ошибка компиляции отсутствующего нового типа допустима для первого red run.
Перед коммитом выполнить `git diff --cached --check` и просмотреть diff;
добавлять перечисленные файлы, а не весь worktree. Записи G1–G8 отслеживаются
checkbox; code examples ниже задают обязательные assertions, не полный тест.

## Карта компонентов

| Компонент | Файлы и ответственность |
| --- | --- |
| Источники/проект | `MediaSource.kt`, `EditProjectStore.kt`, `ProjectManifestCodec.kt`, `MediaInputInspector.kt`, `MediaImportSession.kt`: метаданные, атомарный импорт и сохраняемый проект. |
| Совместимый движок | `VeycadAutomaticEditor.kt`, `EditSession.kt`, `MediaCodecSpeedRampRenderer.kt`: список файлов, адаптер старых вызовов, максимум два decoder cursor. |
| Время/снимок | `MontageTimeMap.kt`, `RenderProjectSnapshot.kt`, `GraphSnapshotCodec.kt`, `FrameAttachmentCodec.kt`: единый PTS, сохранение графа и необходимых масок/параметров. |
| Нейтральный анализ | `GalleryFrameMetrics.kt`, `GallerySourceAnalyzer.kt`, `GalleryAnalysisCache.kt`: обзор/уточнение без обязательной сегментации человека. |
| Режиссура | `GalleryMontageDirector.kt`, `GalleryMomentSelector.kt`: общий пул и воспроизводимый подбор разнообразных окон. |
| UI/QA | `GallerySourcesPanel.kt`, `MainActivity.kt`, существующие inspectors и новые instrumented tests: системный picker, жизненный цикл, проверка реального MP4. |

### G1: Источники, атомарный импорт и проект

**Files:** Create `app/src/main/java/com/example/autoedit/{MediaSource,EditProjectStore,ProjectManifestCodec,MediaInputInspector,MediaImportSession}.kt`; modify `RenderWorkspace.kt` в том же каталоге; create tests `app/src/test/java/com/example/autoedit/{MediaSourceSetTest,EditProjectStoreTest,GalleryImportPolicyTest,MediaSourceFixtures}.kt` и `app/src/androidTest/java/com/example/autoedit/MediaImportIntegrationTest.kt`.

**Interfaces:** `MediaSource(id:String,file:File,displayName:String,durationUs:Long,sizeBytes:Long,rotationDegrees:Int,width:Int,height:Int,mime:String,colorTransfer:Int?,hasAudio:Boolean,fingerprint:String)`; `MediaSourceSet(items:List<MediaSource>,schemaVersion:Int=1)` сохраняет порядок и уникальность ID, допускает пустой draft. `MediaInputInspector.inspect(id:String,file:File,name:String):MediaSource`. `GalleryImportPolicy.validate(sources:MediaSourceSet):Unit` требует 1–20 источников, константы `MAX_FILES=20`, `MAX_DURATION_US=1_800_000_000L`, `MIN_SOURCE_US=500_000L`. `MediaImportSession.importUris(uris:List<Uri>,previousProjectId:String?,checkCancelled:()->Unit):ImportReport`; `ImportReport(projectId:String?,accepted:MediaSourceSet,rejected:List<ImportRejection>)`, `ImportRejection(name:String,reason:String)`. `EditProjectStore(root:File).commitSources(id:String,sources:MediaSourceSet):Unit`, `loadSources(id:String):MediaSourceSet?`, `acquireLease(id:String):AutoCloseable`, `deleteSources(id:String):Unit` запрещён при активном lease. `forkSources(id:String,keepIds:List<String>,added:MediaSourceSet):String` создаёт новый project ID с собственным неизменяемым порядком источников для add/remove, не меняя sources прошлого результата.

- [ ] **1. Написать `GalleryImportPolicyTest.enforcesCountAndTotalDuration`**, `EditProjectStoreTest.forkKeepsPublishedSourceOrder` и tests ограничений 20/21 файлов, ровно 30 минут/превышения и переполнения суммы; migration 1/2 файлов, атомарного rollback, нехватки места, потери URI и невозможности удаления активного проекта. В `MediaSourceFixtures.kt` определить `sourceSet(count:Int,durationUs:Long=1_000_000L):MediaSourceSet` и `withProjectStore(block:(EditProjectStore)->Unit)` на временных каталогах.

```kotlin
assertEquals(20, sourceSet(20).items.size)
GalleryImportPolicy.validate(sourceSet(20, 90_000_000L))
assertThrows(IllegalArgumentException::class.java) { GalleryImportPolicy.validate(sourceSet(21)) }
```

- [ ] **2. Red run:** `.\gradlew.bat :app:testDebugUnitTest --tests 'com.veycad.app.MediaSourceSetTest' --tests 'com.veycad.app.EditProjectStoreTest' --tests 'com.veycad.app.GalleryImportPolicyTest'`; ожидается FAIL новых assertions/типов.
- [ ] **3. Реализовать interfaces:** сериализация относительных путей внутри ID проекта, JSON schema 1, `.partial` → атомарный manifest. SHA-256 считать в ходе копирования; проверять реальную дорожку и PTS, свободное место до/во время копирования. URI без `SIZE` не считать нулевым файлом. Частичный импорт возвращает принятые и отклонённые файлы; отмена откатывает новую транзакцию. Add/remove создаёт новый проект с атомарно скопированными удерживаемыми исходниками; не удалять originals и не менять порядок источников уже опубликованного проекта. Данные прежнего черновика мигрировать однократно; cache-cleanup не удаляет projects.
- [ ] **4. Green run:** повторить команду, добавить `RenderWorkspaceTest`; для настоящих URI использовать targeted `MediaImportIntegrationTest` в изолированном `.uitest`. PASS; временные и старые данные имеют ожидаемое состояние.
- [ ] **5. Commit:** только файлы G1, `feat(storage): сохранять проекты и атомарно импортировать набор видео`.

### G2: Список источников и ограниченный GPU decoder pool

**Files:** Modify `app/src/main/java/com/example/autoedit/{EditSession,VeycadAutomaticEditor,MediaCodecSpeedRampRenderer,VideoDisplayOrientation,VeykadRenderInspector,RenderedVisualSampler}.kt`; create `SourceDecoderSlots.kt` рядом; tests `app/src/test/java/com/example/autoedit/{MultiSourceRequestTest,SourceDecoderSlotsTest}.kt`; modify `LiveDecoderTransitionPlanTest.kt`, `app/src/androidTest/java/com/example/autoedit/GlProgramLifecycleTest.kt`.

**Interfaces:** новый `sources:MediaSourceSet` — единственный авторитетный список `VeycadAutomaticEditor.Request`; renderer получает `sourceFiles:List<File>` в том же порядке. Старые публичные вызовы `render(source,secondary,...)` и capture-контекст остаются адаптерами. `SourceDecoderSlots(open:(Int)->AutoCloseable).acquire(sourceIndex:Int,role:DecoderRole):SlotLease`, `releaseAll():Unit`; `DecoderRole.INCOMING/OUTGOING`, `SlotLease(sourceIndex:Int,decoder:AutoCloseable):AutoCloseable`. Повторный acquire того же индекса/роли возвращает живой slot; смена индекса закрывает прежний slot этой роли до открытия нового. Request validates recipe отдельно; третьего decoder cursor одновременно нет.

- [ ] **1. Написать `SourceDecoderSlotsTest.neverOpensThirdDecoder`** и red tests: 20 индексов, неправильный индекс, уникальность порядка, повторное получение того же slot, освобождение при exception; current one/two-source wrappers и capture arguments дают прежний запрос.

```kotlin
assertEquals(19, slots.acquire(19, DecoderRole.INCOMING).sourceIndex)
assertTrue(fakeDecoderFactory.peakOpen <= 2)
assertEquals(previousRequestSources, adaptedRequest.sources.items.map { it.file })
```

- [ ] **2. Red run:** `.\gradlew.bat :app:testDebugUnitTest --tests 'com.veycad.app.MultiSourceRequestTest' --tests 'com.veycad.app.SourceDecoderSlotsTest'`; ожидается FAIL. Fake factory создаётся в test и считает реальные acquire/close события.
- [ ] **3. Реализовать список во всех границах:** renderer orientation/crops, decoder duration, переходы, инспектор и sampler разрешают индекс из списка. До добавления нового рецепта старые validation rules сохраняются. Ввести слот-политику для внутреннего `DecoderSession`, не переписывать всю GLES-сессию. Native startup/cleanup должен закрывать каждый созданный cursor и GL ресурс при любой ошибке.
- [ ] **4. Green run:** новые тесты + `LiveDecoderTransitionPlanTest`; instrumented `GlProgramLifecycleTest` проверяет cleanup на ошибках. PASS; старые рецепты не получают молчаливо дополнительные файлы.
- [ ] **5. Commit:** `refactor(render): передавать список источников и ограничить декодеры`.

### G3: Единое время и восстановимый снимок монтажа

**Files:** Create `app/src/main/java/com/example/autoedit/{MontageTimeMap,RenderProjectSnapshot,GraphSnapshotCodec,FrameAttachmentCodec}.kt`; modify `HighQualityFramePlan.kt`, `MediaFrameAnalysisCache.kt`, `EditProjectStore.kt`, `CompletedRenderStore.kt`, `VeycadAutomaticEditor.kt`, `EditSession.kt`; tests `app/src/test/java/com/example/autoedit/{MontageTimeMapTest,RenderProjectSnapshotTest}.kt`, `CompletedRenderStoreTest.kt`.

**Interfaces:** `MontageTimeMap(graph:MontageGraph).sample(outputTimeUs:Long):SourceTime`; `SourceTime(clipIndex:Int,sourceIndex:Int,sourceTimeUs:Long,clipProgress:Float)`, `durationUs:Long`. `GraphSnapshotCodec.write(graph:MontageGraph,file:File):Unit`, `read(file:File):MontageGraph`; `FrameAttachmentCodec.write(timeline:FrameAttachmentTimeline,file:File):Unit`, `read(file:File):FrameAttachmentTimeline` использует текущие bounded binary plane/timeline encodings. `RenderProjectSnapshot(projectId:String,graph:MontageGraph,musicFile:File,width:Int,height:Int,fps:Int,bitrate:Int)`; store `saveRenderSnapshot(snapshot:RenderProjectSnapshot):Unit`, `loadRenderSnapshot(id:String):RenderProjectSnapshot?`. `CompletedRenderStore.Entry` получает nullable `projectId`, сохраняя `captureLink`.

- [ ] **1. Написать `MontageTimeMapTest.matchesLegacyPtsAtEveryOutputFrame`** и `RenderProjectSnapshotTest.restoresGraphAndAttachmentsAfterRestart`: совпадение source PTS со старым `HighQualityFramePlan` при constant/ramped/tracked speed, повторах, переходах и exact boundary; round-trip всех graph fields, masks/flows/parameter tracks и восстановления после process death. FloatArray сравнивать по содержимому. Повреждённый artifact отклоняется явно; `CaptureLink` не теряется.

```kotlin
assertEquals(oldPlan.frames.map { it.sourceTimeUs }, newPlan.frames.map { it.sourceTimeUs })
assertEquals(graph.clips, GraphSnapshotCodec.read(snapshotFile).clips)
assertEquals(originalCaptureLink, reloadedEntry.captureLink)
```

- [ ] **2. Red run:** `.\gradlew.bat :app:testDebugUnitTest --tests 'com.veycad.app.MontageTimeMapTest' --tests 'com.veycad.app.RenderProjectSnapshotTest'`; FAIL. Зафиксировать legacy PTS oracle в tests до изменения общего вычисления.
- [ ] **3. Реализовать interfaces:** вынести вычисление PTS из frame-plan без изменения float integration/округления; видео и будущий звук потребляют `MontageTimeMap`. Сохранить весь граф, выбранную музыку и необходимые frame attachments в filesDir проекта, не ссылки на purgeable cache. Persist snapshot до публикации MP4; journal/recovery согласует result link. Ограничить lengths/counts при чтении артефактов, проверять checksum. Старые MP4 с отсутствующим projectId по-прежнему доступны.
- [ ] **4. Green run:** новые тесты + `CompletedRenderStoreTest`, `MediaFrameAnalysisCacheTest`, `ReferenceMontageProfileTest`, `HeartbeatMontageProfileTest`. PASS; round-trip даёт одинаковый frame schedule. Выполнить `clean baselineVerify` для foundation; PR остаётся draft при неполной проверке capture/MP4.
- [ ] **5. Commit:** `feat(storage): сохранять граф монтажа и общий контракт времени`; подготовить foundation PR с actual checks и ограничениями.

### G4: Нейтральный анализ галереи

**Files:** Create `app/src/main/java/com/example/autoedit/{GalleryFrameMetrics,GallerySourceAnalyzer,GalleryAnalysisCache}.kt`; modify `SequentialBitmapDecoder.kt` только для ограниченного выборочного чтения, если текущего API недостаточно; tests `app/src/test/java/com/example/autoedit/{GalleryFrameMetricsTest,GallerySourceAnalyzerTest,GalleryAnalysisCacheTest}.kt`; create `app/src/androidTest/java/com/example/autoedit/GalleryAnalysisIntegrationTest.kt`.

**Interfaces:** `GalleryFeatures(timeUs:Long,quality:Float,localMotion:Float,cameraInstability:Float,sceneChange:Float,composition:Float,faceConfidence:Float?)`; `GalleryMoment(sourceId:String,startUs:Long,endUs:Long,features:GalleryFeatures,keyHash:Long)`. `GallerySourceAnalyzer.analyze(source:MediaSource,checkCancelled:()->Unit):List<GalleryMoment>`, config `coarseIntervalUs=1_000_000L`, `refineIntervalUs=250_000L`. `GalleryFrameMetrics.measure(timeUs:Long,luma:FloatArray,width:Int,height:Int,previous:FloatArray?):GalleryFeatures` — pure JVM metrics. Cache ключ — fingerprint + analyzer version + intervals, `GalleryAnalysisCache.load(source:MediaSource):List<GalleryMoment>?`, `store(source:MediaSource,moments:List<GalleryMoment>):Unit`.

- [ ] **1. Написать `GalleryFrameMetricsTest.prefersSharpStableLandscape`** и `GallerySourceAnalyzerTest.keepsShortClipWithinEof`: резкий стабильный кадр выше смазанного/трясущегося, сильное глобальное движение не заменяет действие, неизвестное лицо не отклоняет пейзаж, 0,5 s файл и сцена у EOF дают реальные окна. Два одинаковых файла используют общий fingerprint cache, изменённый профиль не использует старый.

```kotlin
assertTrue(stableLandscape.quality > blurredCameraShake.quality)
assertTrue(shortClipMoments.all { it.endUs <= 500_000L })
assertTrue(landscapeMoments.isNotEmpty())
```

- [ ] **2. Red run:** `.\gradlew.bat :app:testDebugUnitTest --tests 'com.veycad.app.GalleryFrameMetricsTest' --tests 'com.veycad.app.GallerySourceAnalyzerTest' --tests 'com.veycad.app.GalleryAnalysisCacheTest'`; FAIL.
- [ ] **3. Реализовать bounded обзор/уточнение:** first/last + coarse samples, локальное уточнение кандидатов; окна 0,5–4 s ограничены сменой сцены. Обработка последовательная; bitmap recycle после sample. Не вызывать обязательную multiclass segmentation/pose/FEAR correspondence для нового рецепта. Опциональные лица не являются критерием допуска. Кэш измерений не хранит весь видеоархив.
- [ ] **4. Green run:** команда выше и `GalleryAnalysisIntegrationTest` на SyntheticVideo с пейзажной/движущейся текстурой; PASS, cancellation покрывает decode/refine, каждый timeUs получен из реального кадра.
- [ ] **5. Commit:** `feat(analysis): находить пригодные моменты в сторонних видео`.

### G5: Общий пул, разнообразие и новый рецепт

**Files:** Create `app/src/main/java/com/example/autoedit/{GalleryMomentSelector,GalleryMontageDirector}.kt`; modify `MontageStyleCatalog.kt`, `MontageStylePresentation.kt`, `BuiltInMusicCatalog.kt`, `VeycadAutomaticEditor.kt`, `SourceAnalysisProfile.kt` рядом; tests `app/src/test/java/com/example/autoedit/{GalleryMontageDirectorTest,GalleryMomentSelectorTest}.kt`, existing `MontageStyleCatalogTest.kt`, `MontageStylePresentationTest.kt`.

**Interfaces:** `GalleryMomentSelector.select(moments:List<GalleryMoment>,sources:MediaSourceSet,budgetUs:Long):List<GalleryMoment>`; `GalleryMontageDirector.direct(sources:MediaSourceSet,moments:List<GalleryMoment>,audioMap:AudioBeatMap,requestedDurationMs:Long):MontageGraph`. `Recipe.GALLERY_MONTAGE`, stable style ID `gallery_montage`; requested duration ∈ `15_000/30_000/60_000`. Новый recipe route минует семантические requirements старых recipes и строит свои evidence/QA.

- [ ] **1. Написать `GalleryMomentSelectorTest.diversifiesWithoutRepeatingSourceWindows`** и `GalleryMontageDirectorTest.shortfallDoesNotPadWithRepeats`: 10/20 разных источников, один сильный длинный источник среди остальных, одинаковые key frames, невидимое лицо, равные оценки, shortfall. Изменения sourceIndex должны приводить к настоящим выбранным файлам; результат никогда не заполняется repeats/freeze.

```kotlin
assertEquals(firstRun.clips, secondRun.clips)
assertTrue(result.outputDurationMs <= 30_000L)
assertTrue(result.clips.all { it.sourceEndMs <= sourceDurationMs(it.sourceIndex) })
```

- [ ] **2. Red run:** `.\gradlew.bat :app:testDebugUnitTest --tests 'com.veycad.app.GalleryMontageDirectorTest' --tests 'com.veycad.app.GalleryMomentSelectorTest'`; FAIL.
- [ ] **3. Реализовать:** рейтинг `quality * (0.60 + 0.25*localMotion + 0.15*composition) * (1-cameraInstability)` для нормализованных признаков; optional face — дополнительная информация, без жёсткого face gate. После каждого выбора штрафовать перекрытия/визуальные повторы и соседние окна одного источника; tie-break `source order,startUs,endUs`. Не брать плохой файл ради квоты. Beat grid сокращает окна в реальных границах; shortfall возвращает фактический граф, <1 s — явную ошибку. Включить availability только после G6/G7, до этого внутренняя recipe route тестируется без показа в production.
- [ ] **4. Green run:** новые тесты + style catalog/presentation tests; PASS. Отдельный holdout в G8 проверяет, что фиксированный рейтинг даёт полезные моменты; его нельзя объявлять художественно принятым по unit tests.
- [ ] **5. Commit:** `feat(montage): собрать разнообразный ролик из набора источников`.

### G6: Системный мультивыбор и жизненный цикл экрана

**Files:** Create `app/src/main/java/com/example/autoedit/GallerySourcesPanel.kt`, `app/src/main/res/layout/view_gallery_sources.xml`; modify `MainActivity.kt`, `EditSession.kt`, `app/src/main/res/layout/activity_main.xml`, `app/src/main/res/values/strings.xml`, `app/src/uiTest/java/com/example/autoedit/UiFixtureProvider.kt`; tests `app/src/androidTest/java/com/example/autoedit/GallerySourcesScreenTest.kt`, existing `DirectorScreenTest.kt`, `CaptureReviewScreenTest.kt`.

**Interfaces:** `GallerySourcesPanel.bind(sources:MediaSourceSet,rejected:List<ImportRejection>,busy:Boolean):Unit`, callbacks `onAdd:()->Unit`, `onRemove:(String)->Unit`, `onDuration:(Long)->Unit`. `EditSession.importGallery(uris:List<Uri>):Unit`, `removeGallerySource(id:String):Unit`, `renderGallery(requestedDurationMs:Long):Unit` адаптируют G1/G5 и публикуют immutable state.

- [ ] **1. Написать `GallerySourcesScreenTest.importsTwentyFilesAndKeepsPreviousResult`**: SAF выбирает 20 файлов без READ_MEDIA_VIDEO, счётчик/длительность, добавить/убрать, ошибочный/облачный URI, лимит до копирования, короткий MP4, поворот/process restoration, отмена и сохранность старого результата. Auto Capture → gallery → capture не меняет сохранённую рекомендацию.

```kotlin
onView(withText("Собрать ролик")).check(matches(isEnabled()))
assertEquals(20, sessionDraft.sources.items.size)
assertEquals(previousCaptureLink, completedEntry.captureLink)
```

- [ ] **2. Red run:** `.\gradlew.bat connectedUiTestAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.veycad.app.GallerySourcesScreenTest` на выделенном `AutoEditUi_API36`; FAIL. Для тестов использовать настоящий fixture provider, не прямую установку UI-ready flags.
- [ ] **3. Реализовать:** `OpenMultipleDocuments`, panel состояния, 15/30/60 s, явный список отклонений, existing typography/cards. Поток импорта/анализа принадлежит EditSession, Activity только наблюдает; persisted project ID восстанавливается. Сохранить старые one/two-source и Auto Camera listeners/adapters, не делать массовый рефакторинг MainActivity.
- [ ] **4. Green run:** новая UI class + `DirectorScreenTest`, `CaptureReviewScreenTest`; PASS. После G7 включить production availability `gallery_montage` и повторить smoke-путь создания.
- [ ] **5. Commit:** `feat(ui): добавить пакетный импорт и создание ролика из галереи`.

### G7: Проверка multi-source MP4 и регрессий

**Files:** Create `app/src/main/java/com/example/autoedit/GalleryExportAcceptance.kt`; modify `app/src/main/java/com/example/autoedit/{RenderedMp4Acceptance,RenderedVisualSampler,VeykadRenderInspector,ExportContract,MaterialSuitability}.kt`; create tests `app/src/test/java/com/example/autoedit/GalleryExportAcceptanceTest.kt`, `app/src/androidTest/java/com/example/autoedit/GalleryExportIntegrationTest.kt`; extend `app/src/androidTest/java/com/example/autoedit/SyntheticVideo.kt` только тестовыми markers/rotation/audio fixtures; update `docs/SupportMatrix.md` по фактическим результатам.

**Interfaces:** В новом файле определить `GalleryExportEvidence(container:RenderedMp4Acceptance.ContainerSample,sourceSamples:List<GallerySourceSample>,audio:DecodedAudioQuality.Report,fps:Int)` и `GallerySourceSample(outputTimeUs:Long,sourceIndex:Int,sourceId:String,sourceTimeUs:Long,geometryMatches:Boolean,sourceContentMatches:Boolean,unexpectedBlack:Boolean,unexpectedFreeze:Boolean)`. `GalleryExportAcceptance.evaluate(graph:MontageGraph,sources:MediaSourceSet,evidence:GalleryExportEvidence):GalleryExportAcceptance.Report`; `Report(accepted:Boolean,issues:List<String>)`. Общие container/audio integrity проверки использовать без face gate и без авторских transition/accent требований; естественная темнота/статичность исходника не считается артефактом. Current recipes остаются на своих прежних acceptance routes.

- [ ] **1. Написать `GalleryExportIntegrationTest.decodedMarkersMatchEverySelectedSource`** и `GalleryExportAcceptanceTest.rejectsWrongSourceAndPastEof`: четыре и двадцать маркированных AVC источников, разные rotation, VFR/неполный GOP, неверный файл при правильном индексе, чёрный/замороженный кадр, кадр за EOF, источник без аудио. Проверять decoded pixels/PTS, а не только граф/число клипов.

```kotlin
assertEquals(expectedSourceMarker, decodeOutputMarker(outputTimeUs))
assertTrue(audioVideoDurationDifferenceUs <= 1_000_000L / outputFps)
assertFalse(acceptanceOfWrongSource.accepted)
```

- [ ] **2. Red run:** `.\gradlew.bat :app:testDebugUnitTest --tests 'com.veycad.app.GalleryExportAcceptanceTest'`; `.\gradlew.bat connectedUiTestAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.veycad.app.GalleryExportIntegrationTest` на `.uitest`; FAIL на отсутствующей source-bound проверке.
- [ ] **3. Реализовать проверки:** sampler/inspector разрешают все source indices, source duration и rotation. Не ослаблять failures старых recipes; `MaterialSuitability` нового recipe допускает отсутствие людей и отклоняет технически пустой материал. Trace максимум открытых декодеров и освобождение ресурсов.
- [ ] **4. Green run:** unit/instrumented tests + `RenderedMp4AcceptanceTest`, `ExportContainerIntegrityTest`; PASS. Реальный MP4 полностью декодирован; smoke нового recipe G6 проходит. Проверка одного эмулятора не расширяет матрицу устройств автоматически.
- [ ] **5. Commit:** `feat(quality): проверять источники и экспорт пакетного монтажа` вместе с необходимыми тестами.

### G8: Полный gate и приёмка на телефоне

**Files:** Create `docs/testing/gallery-batch-device-review.md`; modify `docs/testing/ui-tests.md`, `docs/SupportMatrix.md`, `README.md` только подтверждёнными возможностями. Временные MP4, сырые видео, machine-local настройки и сборки не коммитить.

**Interfaces:** итоговый review report фиксирует commit SHA, device/API, source manifest без пользовательских путей, число/длительность/кодеки входа, анализ/экспорт wall time, пик памяти, peak decoder count, cancel latency и ручную оценку.

- [ ] **1. Добавить последний regression test** найденной на holdout/устройстве ошибки до её исправления. Если новых ошибок нет, новых формальных тестов/пустого коммита не создавать; сохранить результаты существующих проверок.
- [ ] **2. Red run для найденной ошибки**, затем scoped fix и green run. Для завершения выполнить `.\gradlew.bat clean baselineVerify` и `pwsh tools/run_ui_tests.ps1 -Serial emulator-5556 -AvdName AutoEditUi_API36`; ожидается PASS всех обнаруженных тестов, без пропущенных suites.
- [ ] **3. Проверить физический Samsung A25:** отдельная `.uitest` установка/fixtures, наборы 10/20 видео и combined 30 min budget, 720p/1080p, полные экспорты и просмотр со звуком 1×. Проверить cancel UI ≤1 s, worker ≤5 s, decoder peak ≤2, process interruption и capture-link recovery. Production-данные телефона не сбрасывать. Записать измеренное время и RAM; не назначать им выдуманный PASS до прогона.
- [ ] **4. Человеческая приёмка:** полезность фрагментов, разнообразие и кадрирование; проверить отдельно старые FEAR/Heartbeat/DUALITY и Auto Camera. Если устройства, разрешённых видео или приёмки нет, сохранить явно обозначенный checkpoint в draft PR и перечислить непроверенное.
- [ ] **5. Commit только изменившиеся проверки/документы:** `docs(quality): зафиксировать приёмку пакетного монтажа`; push и PR с результатами последнего SHA. Не сливать и не выдавать технический gate за художественную приёмку.

## Карта покрытия спецификации

| Требование | Задачи |
| --- | --- |
| Пределы, сырой вход, atomic copy, короткие клипы | G1, G4, G6 |
| Проекты, migration, lease, повторное редактирование | G1, G3, G6 |
| Нейтральные события, лица необязательны, разнообразие | G4, G5, G8 |
| Список источников, ориентация, два decoder cursor, EOF | G2, G7 |
| Новый recipe/music route, output duration/quality | G5, G7 |
| UI, отмена, перезапуск, старые стили/камера | G3, G6, G8 |
| Полный MP4, baseline/UI, Samsung и просмотр человеком | G7, G8 |

## Передача в исполнение

План подготовлен для ревью, шаги ещё не выполнены. Рекомендуемый метод —
Native: последовательное исполнение в текущем чате; общие interfaces G1–G3
важнее параллельной скорости. Альтернатива — subagent-driven с отдельным
исполнителем/ревьюером на каждую задачу и большим расходом контекста.
До выбора метода и подтверждения плана продуктовый код не изменять.
