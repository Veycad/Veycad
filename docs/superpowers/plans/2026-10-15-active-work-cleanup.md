# Безопасная очистка активных задач — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** исключить удаление рабочих файлов монтажа, текста, распознавания и съёмки пользовательской командой очистки.

**Architecture:** один Android-независимый координатор атомарно допускает обычную работу или эксклюзивную очистку. Сессии удерживают собственные допуски до фактического завершения ресурсов и публикации конечного состояния. Очисткой и сбросом черновика владеет `EditSession`; Activity показывает диалог и наблюдает результат.

**Tech Stack:** Kotlin, Java concurrency/NIO, Android API 26+, существующие Executor/Handler/LiveData, JUnit 4, Android instrumentation/Espresso/CameraX/MediaCodec.

**Spec:** [2026-10-15-active-work-cleanup-design.md](../specs/2026-10-15-active-work-cleanup-design.md), коммит `75fa48747beacc36a619e439725bd1152933429c`, [PR #50](https://github.com/Veycad/Veycad/pull/50). Владелец одобрил спецификацию ответом «Подтверждаю» 15 октября 2026 года. Сам план и способ выполнения ещё ожидают его решения.

## Global Constraints

- Только пункт 1 очереди 1 → 2 → 3 → 4–5 → 6–8 → 9–10; пункт 11 отдельно.
- API 26+, локальная обработка без аккаунта и сети; новые зависимости не нужны.
- Не создавать второй project store, codec, renderer или scheduler; использовать существующие сессии.
- Допуск выдаётся до executor и первого доступа к защищаемым данным; отмена не освобождает его до завершения worker.
- Очистка отклоняется при любой активной операции; новые задачи отклоняются во время очистки. Обычные задачи сохраняют прежнюю политику параллелизма.
- Удалять только `filesDir/source-draft`, `filesDir/pending-renders`, `cacheDir/imports`, `cacheDir/full-video-analysis`, `cacheDir/text-work`.
- Сохранять completed/saved MP4, text-sources/text-drafts, capture-sessions, projects, модели, музыку, diagnostics, неизвестный кэш и все файлы `:matte_engine`.
- Смерть процесса сбрасывает реестр, но не даёт права удалять файлы provider. Не обещать защиту от очистки кэша самой Android.
- Отказ допуска ничего не удаляет и не сбрасывает выбор; частичная ошибка удаления не считается успехом.
- Работать в `codex/active-work-cleanup`, от согласованной актуальной базы. Сохранить чужие изменения; только именованные файлы, отдельные связные коммиты, PR и ручное слияние после явного одобрения.

## Review Focus

1. Диалог открыт до старта работы: подтверждение обязано заново атомарно получить допуск — задачи 1 и 3.
2. Отмена подготовки камеры и поздний callback старого generation не освобождают новый дубль — задача 3.
3. Ссылка в разрешённом каталоге не ведёт удаление или подсчёт размера наружу — задача 2.
4. Отказ executor/Handler и исключение observer не оставляют вечный допуск — задачи 1 и 3.
5. Частично удалённый черновик не восстанавливается как готовый после перезапуска — задачи 2 и 3.

## Файлы и границы

Все Kotlin-пути ниже относительны `app/src/main/java/com/example/autoedit/`; package остаётся `com.veycad.app`.

| Файл | Ответственность |
| --- | --- |
| Новый `MediaWorkCoordinator.kt` | Атомарный допуск и независимые идемпотентные leases |
| Новый `TemporaryFileCleanup.kt` | Разрешённые пути, безопасный подсчёт и удаление, отчёт частичных ошибок |
| `EditSession.kt`, `TextEditSession.kt`, `CaptureRecordingSession.kt` | Владение допусками, обработка отказа запуска, конечное состояние |
| `RenderWorkspace.kt` | Существующие пути; удаление только при действующем допуске очистки |
| `MainActivity.kt` | Диалог, запуск команды, отображение очистки и инвалидированного выбора |
| `app/src/uiTest/.../UiTestApplication.kt` | Контролируемые сессии и барьеры тестов в отдельном APK |
| `app/src/test/.../*Test.kt` | Координатор, политика путей и существующие storage-регрессии |
| `app/src/androidTest/.../*Test.kt` | Интеграция сессий, гонки UI, реальный текстовый экспорт |

Исходники приложения в этом документальном PR не меняются. Новые файлы и тестовые seams ниже — задания будущей реализации.

## Task 1: атомарный допуск работы и очистки

**Files:** создать `MediaWorkCoordinator.kt` и `app/src/test/java/com/example/autoedit/MediaWorkCoordinatorTest.kt`.

**Interfaces:**

```kotlin
internal class MediaWorkCoordinator {
    enum class Kind { WORK, CLEANUP }
    class Lease : AutoCloseable {
        internal fun requireCleanupActive()
    } // constructor private; чужой допуск создать нельзя
    data class Snapshot(val activeWork: Int, val cleaning: Boolean)
    fun tryBeginWork(): Lease?
    fun tryBeginCleanup(): Lease?
    fun snapshot(): Snapshot
    companion object { val process: MediaWorkCoordinator }
}
```

Lease связан с создавшим его координатором и Kind. Внутренняя проверка действующего lease потребуется задаче 2; закрытый lease не разрешает удаление. Процессный экземпляр используется всеми production-сессиями; тесты могут создать отдельный экземпляр.

- [ ] **1. Написать тесты:** `workAndCleanupNeverOverlap`, `twoWorkersBlockUntilBothClose`, `duplicateCloseCannotReleaseAnotherLease`, `closedCleanupCannotAuthorizeDeletion`, `freshProcessHasNoInheritedWork`. Использовать CountDownLatch/CyclicBarrier, а не sleep; успешный admission cleanup всегда означает activeWork=0, successful work — cleaning=false.
- [ ] **2. RED:** `./gradlew :app:testDebugUnitTest --tests com.veycad.app.MediaWorkCoordinatorTest`; ожидается ошибка отсутствующего API либо упавшие утверждения контракта, а не сбой окружения.
- [ ] **3. Реализовать указанные сигнатуры:** одна критическая секция на проверку/выдачу/закрытие; никакой файловой работы, ожидания executor, callback или Handler под mutex. Lease закрывается идемпотентно с любого потока. Snapshot только для отображения, не авторизация очистки.
- [ ] **4. GREEN:** та же команда; все новые случаи выполнены без fail/error/skip. Проверить соревнование admission с одновременным стартом двух потоков на барьере.
- [ ] **5. Коммит:** только новый координатор и его тесты, `feat(storage): добавить атомарный допуск медиазадач`.

## Task 2: явные пути очистки и отчёт о результате

**Files:** создать `TemporaryFileCleanup.kt`, `app/src/test/java/com/example/autoedit/TemporaryFileCleanupTest.kt`; изменить `RenderWorkspace.kt`, `RenderWorkspaceTest.kt`, `CompletedRenderStoreTest.kt`, `ProjectAssetStoreTest.kt` в существующих source sets.

**Interfaces:**

```kotlin
internal object TemporaryFileCleanup {
    enum class Scope { ALL_TEMPORARY, SOURCE_DRAFT, TRANSIENT_RENDER }
    enum class DraftDisposition { UNTOUCHED, REMOVED, INCOMPLETE }
    data class Report(val failures: List<String>, val draft: DraftDisposition)
    fun estimateBytes(filesDir: File, cacheDir: File, scope: Scope): Long
    fun clear(filesDir: File, cacheDir: File, scope: Scope,
              lease: MediaWorkCoordinator.Lease): Report
}
```

`clear` вызывает `lease.requireCleanupActive()`. Для промежуточного коммита сохранить вызовы `RenderWorkspace.clearTransient(filesDir, cacheDir)` и `clearSourceDraft(filesDir)` совместимыми: добавить необязательный `lease: MediaWorkCoordinator.Lease? = null`; без переданного lease wrapper сам атомарно получает process cleanup lease либо отклоняет операцию, а выданный им lease закрывает в finally. TRANSIENT_RENDER сохраняет прежний набор imports/pending-renders. Переданный lease wrapper не закрывает. После задачи 3 Activity эти методы напрямую не вызывает. Обновить и существующих тестовых потребителей, не оставляя промежуточного некомпилируемого commit.

- [ ] **1. Написать тесты:** `clearsOnlyDeclaredTargets`, `estimateUsesSameTargetsWithoutFollowingLinks`, `preservesSavedSourcesModelsUnknownCacheAndProvider`, `rejectsClosedOrWorkLease`, `reportsPartialDeletion`. Подготовить различимые байты во всех разрешённых и защищённых путях; добавить ссылку внутри разрешённого каталога на защищённый файл, проверить неизменность внешнего файла. Ошибку удаления воспроизводить локальным инъецируемым deleter, без изменения разрешений всего workspace.
- [ ] **2. RED:** `./gradlew :app:testDebugUnitTest --tests com.veycad.app.TemporaryFileCleanupTest`; ожидается отсутствие API или нарушение сохранности тестовых fixtures.
- [ ] **3. Реализовать очистку:** единый список пяти путей для ALL_TEMPORARY, только source-draft для SOURCE_DRAFT, imports/pending-renders для TRANSIENT_RENDER; обход через Java NIO без FOLLOW_LINKS. Не резолвить symlink в цель удаления, не переходить через ссылку-предка. Небезопасный целевой путь даёт failure, а не обход защиты. Суммирование размера — с проверкой переполнения; list/read ошибки не превращать в подтверждение нулевого размера.
- [ ] **4. GREEN:** выполнить тесты четырёх перечисленных классов, включая прежние storage-тесты. Все завершённые/сохранённые MP4 и project assets сохраняют точные байты; partially failed report содержит фактические failed paths и INCOMPLETE для недоочищенного source-draft.
- [ ] **5. Коммит:** новые policy/tests и адаптации storage-потребителей, `fix(storage): ограничить очистку принадлежащими ей каталогами`.

## Task 3: подключить сессии, команду очистки и UI одной связной интеграцией

**Files:** изменить `EditSession.kt`, `TextEditSession.kt`, `CaptureRecordingSession.kt`, `MainActivity.kt`, `app/src/uiTest/java/com/example/autoedit/UiTestApplication.kt`, `app/src/androidTest/java/com/example/autoedit/SettingsScreenTest.kt`, `TextEditActivityDeviceTest.kt`, `TextExportDeviceTest.kt`; создать `app/src/androidTest/java/com/example/autoedit/MediaWorkSessionDeviceTest.kt`. Сессии, Activity и все необходимые регрессии коммитятся вместе, после окончания обеих частей этой задачи.

**Interfaces:**

- В каждую сессию добавить internal constructor dependencies `coordinator: MediaWorkCoordinator = MediaWorkCoordinator.process` и injectable `ExecutorService`, сохранив production-фабрики и existing EditSession.RenderEngine. Не добавлять Activity в зависимости.
- `EditSession.clearTemporaryFiles(): Boolean`, `EditSession.clearDraft(): Boolean`: true означает принятую задачу, не успешное удаление. Обе команды используют эксклюзивный lease; scope соответственно ALL_TEMPORARY/SOURCE_DRAFT.
- `EditSession.State` получает `cleaning: Boolean`, `draftGeneration: Long`, `cleanupReport: TemporaryFileCleanup.Report?`. Generation принадлежит сессии, монотонен в процессе и переносится во все публикации State.
- Для управляемого экспорта `TextEditSession.ExportEngine.export(context: Context, project: TextEditProject, output: File, checkCancelled: () -> Unit, onProgress: (Int) -> Unit): Unit`; production вызывает существующий TextVideoExporter без изменений. SpeechTranscriber передаётся существующим интерфейсом. UiTestApplication предоставляет тестовую TextEditSession через такой же Owner seam, как уже есть у EditSession.
- Для SOURCE_DRAFT-очистки preferences `source_draft` получают `cleanup_incomplete=true` до первого удаления, с проверкой commit. REMOVED очищает эти preferences; INCOMPLETE оставляет marker. Успешный импорт снимает marker. Это защита восстановления после частичного удаления, не реализация атомарного импорта из пункта 2.

- [ ] **1. Написать тесты:** `queuedWorkAlreadyBlocksCleanup`, `cancelKeepsProtectionUntilWorkerExits`, `rejectedExecutorRestoresStateAndReleasesOwnLease`, `observerFailureDoesNotLeakLease`, `capturePreparationHandoffHasNoIdleGap`, `staleCameraGenerationCannotReleaseNewRecording`, `recoveryDoesNotRemoveProviderFiles`, `cleanupFailureInvalidatesPartialDraft`. До старта worker и на его выходе использовать барьеры; после каждого случая snapshot должен подтверждать отсутствие утечки.
- [ ] **2. RED:** на выделенном test APK запустить `MediaWorkSessionDeviceTest` через существующий Android test runner; ожидаются конкретные нарушения новых утверждений. Сбой сборки/эмулятора отдельно устранить, не записывать как воспроизведение дефекта.
- [ ] **3. Подключить EditSession/TextEditSession:** lease до busy/preferences и queue; финальная публикация State и все release — в гарантированном завершении. Отказ допуска показывает согласованное сообщение и сохраняет предыдущие entry/draft/project. Отказ executor восстанавливает предыдущее состояние/preferences. Если Handler не принял callback, закрыть только собственный lease. Cleanup lease удерживается до удаления, подтверждённого сброса preferences и синхронного уведомления состояния на main.
- [ ] **4. Подключить камеру и recovery:** long-lived lease охватывает подготовку, запись и финализацию; каждый queued IO/continuation имеет собственное владение до выхода. Ранний stop не снимает защиту с ещё работающей подготовки. Поздний generation callback освобождает только свои ресурсы. Возврат из Activity и состояние FINALIZING не разрешают очистку. Инициализацию с удалением scratch перенести под admission; при занятой очистке отложить recovery до первой принятой работы, не удалять файлы из конструктора.
- [ ] **5. GREEN:** `MediaWorkSessionDeviceTest`, существующие `CaptureRecordingScreenTest`, `CaptureReviewScreenTest`, `RenderProgressScreenTest`, `TextEditActivityDeviceTest`; без fail/error/skip. Проверить terminal publication при исключении observer. UiTestApplication ждёт idle и проверяет leases, а не обнуляет координатор для сокрытия утечек.
### Продолжение задачи 3: UI и настоящее кодирование

Activity вызывает только две команды EditSession из этой задачи. Размер показывает `TemporaryFileCleanup.estimateBytes(..., ALL_TEMPORARY)`; restoreSourceDraft отклоняет marker `cleanup_incomplete`. State.cleaning отображается как очистка, не как монтаж. Draft generation обнуляет UI-выбор только при фактической инвалидации; отказ допуска его не меняет. Новые resource IDs без необходимости не добавлять.

- [ ] **6. Написать регрессии:** `dialogConfirmationRechecksAdmission`, `activeTextWorkSurvivesBackAndRecreation`, `newWorkRejectedWhileCleanupOwnsLease`, `clearDraftRejectionPreservesSelection`, `partialDraftIsNotRestoredAfterReopen`. В прежнем SettingsScreenTest заменить fixture `ui-analysis` на разрешённый `full-video-analysis`; отдельно доказать сохранность неизвестного `ui-analysis`, диагностического экспорта и provider scratch. Проверить две одновременные работы и закрытие только одной.
- [ ] **7. RED:** запустить целевые Android-тесты; получить ожидаемый отказ поведения прежней Activity. Не ограничиваться прямым вызовом координатора: действия должны пройти через настоящий диалог и его positive button.
- [ ] **8. Изменить Activity:** убрать рекурсивное удаление cacheDir, прямое удаление source-draft и преждевременный сброс preferences/выбора из confirmClearTemporaryFiles/showNewEditScreen. Наблюдать состояние cleanup, сохранить подтверждение пользователя, обработать partial error без сообщения полного успеха. При пересоздании считывать сохранённую инвалидацию, не повторять удаление.
- [ ] **9. Добавить native-регрессию:** `realTextExportRejectsConcurrentCleanupAndFinishes`. Создать короткое AVC тестовое видео существующим SyntheticVideo, открыть его через TextEditSession и реально вызвать TextVideoExporter. Wrapper ExportEngine ставит барьер на первом промежуточном progress (не на 100%), затем настоящий UI пытается очистку; она отказана, файлы сохранены. Снять барьер, дождаться публикации MP4, декодировать первый/последний кадры, проверить дорожки и длительность; после окончания очистка разрешена и результат неизменен. Отдельно управляемый SpeechTranscriber подтверждает тот же путь для STT; такой тест не объявляется проверкой качества Whisper.
- [ ] **10. GREEN и полный gate:** выполнить команды ниже на точном последнем коммите; проверить выполненные test XML, отсутствие failures/errors/skips, не только exit code. Сохранить отчёты native-теста и проверку неизменности MP4. Это функциональная проверка защиты файлов, не художественная приёмка монтажа.
- [ ] **11. Коммит и PR:** все production-изменения задачи 3, test seams и необходимые UI/native регрессии вместе, `fix(storage): защитить активную обработку от очистки`. Просмотреть named-path staged diff и `git diff --cached --check`; открыть PR с фактами проверок, отдельным статусом устройства и оставшимися ограничениями. Незавершённая или непроверенная интеграция — явно обозначенный checkpoint в draft PR; не переходить к пункту 2 под видом завершённого пункта 1.

## Команды проверки будущей реализации

Из корня правильного checkout, с настроенными JDK 25/SDK/NDK/Python по WINDOWS.md:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.veycad.app.MediaWorkCoordinatorTest
.\gradlew.bat :app:testDebugUnitTest --tests com.veycad.app.TemporaryFileCleanupTest --tests com.veycad.app.RenderWorkspaceTest --tests com.veycad.app.CompletedRenderStoreTest --tests com.veycad.app.ProjectAssetStoreTest
.\gradlew.bat baselineVerify -PpythonExecutable=python
pwsh -NoProfile -File tools/run_ui_tests.ps1 -Serial emulator-5556 -AvdName AutoEditUi_API36
```

Целевой RED/GREEN instrumented прогон: собрать `:app:assembleUiTest :app:assembleUiTestAndroidTest`, установить только `com.veycad.app.uitest`/`.test` на выделенный emulator-5556, затем `adb -s emulator-5556 shell am instrument -w -r -e class com.veycad.app.MediaWorkSessionDeviceTest com.veycad.app.uitest.test/androidx.test.runner.AndroidJUnitRunner`. Для других целевых классов менять только `-e class`; фактические APK выбрать по output-metadata.json и проверить package. Финальный полный прогон выполняется скриптом выше, с его проверками устройства и полноты результатов. Не использовать рабочий emulator-5554 или пользовательские данные.

Если локальный toolchain недоступен, команды и реальный native-тест выполняются в подходящем CI/изолированном Android-окружении. Структурная проверка исходников не заменяет RED/GREEN или выполненный native-тест. Отдельная проверка на физическом устройстве фиксируется как выполненная либо OPEN, с точной версией и сборкой.

## Self-review и переход к выполнению

Покрытие спецификации: атомарный admission и независимые leases — задача 1; таблица путей и частичные ошибки — задача 2; все владельцы, отмена, handoff/recovery, диалог, UI-память выбора, native-экспорт и финальные проверки — задача 3. Все пять Review Focus имеют именованные тесты. Имена Scope/Report/State согласованы между задачами; существующие storage-потребители unguarded API включены в изменения. Промежуточные policy-коммиты сохраняют совместимость старых вызовов; переход сессий и Activity не разнесён по несовместимым коммитам.

В этом PR выполнены только чтение исходников и self-review плана. Ни один implementation-checkbox не отмечается выполненным до реального изменения кода и соответствующей проверки.

После ревью владелец подтверждает план и выбирает способ выполнения: непосредственно в этой сессии с отдельным итоговым ревью либо последовательными исполнителями и ревьюером на каждой задаче. До этого код приложения не изменяется. После слияния PR #50 базу этого документационного PR можно перенести на main; одобрение спецификации или плана само по себе не разрешает merge.
