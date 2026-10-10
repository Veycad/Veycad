# Интеграция ручной доводки с общим проектом

Решение от 10 октября 2026 года по делегации владельца через координатора. Требования согласованной спецификации ручной доводки сохраняются. Этот документ заменяет самостоятельные project/store/revision-контракты первоначального плана.

## Проверенная зависимость

Ветка ручного монтажа включает merge исходного checkpoint `74b58038abedc918c0877cfa3cec215a0f4d9a9e` из PR #15. `HybridProject.kt`, `ProjectClock.kt`, `SourceTimeMap.kt` и их тесты взяты без изменений. Владелец этих API — чат «Добавить экспорт и шеринг». Следующие зависимости подключаются только из проверенных коммитов; чужой WIP не копируется.

`HybridProject` владеет asset ID, original/current, undo/redo, exports и `nextRevisionId`. Используются его `ProjectAsset`, `ProjectExportSettings`, `HybridClip`, `HybridRevision`, `ProjectClock` и `SourceTimeMap`. Второй реестр файлов, счётчик ревизий или каталог проектов не создаются. Постоянные исходники принадлежат общему `projects/<id>/`.

## Контракт модуля монтажа

- `EditableMontageProject` — внутренний адаптер над `HybridProject`, а не самостоятельный сохраняемый проект. Его исходники, исходная версия, текущая ревизия и номера выводятся из общего проекта. Параметры экспорта используют `ProjectExportSettings`, включая audio bitrate. Связь `CaptureLink` сохраняется на границе публикации.
- `EditableClip`, `MontageRevision`, signed `FrameRange` и `ClipTimeMapping` — значения проекции для монтажа. Публичный `ProjectAsset` модуля удаляется. Карта проекции строится из общего `SourceTimeMap` с каждой фактической точкой 0..N; разреженные карты материализуются по кадрам перед подрезкой. Оставшиеся PTS не меняются даже на 1 мкс. Границы source допускают конец durationUs, последний отображаемый sample строго меньше durationUs.
- `frameTimeUs` для неотрицательных кадров делегирует общему `ProjectClock`; signed кадры нужны только для проверки расширения окна. Экстраполяция за исходник отвергается; clamp не превращает ошибку в замороженный кадр.
- Сохраняемые дополнительные данные модуля — только привязки эффектов, local tracks и исходные координаты фазы/видимого окна. Они принадлежат графу конкретной `HybridRevision`. `MontageGraph.manualMontageState` — optional payload с default null. Адаптер проверяет ID, число видимых кадров относительно `span`, исходное число кадров фазы, идентичность исходника и допустимые PTS канонического `HybridClip.sourceMap`. Равенство текущей карты исходной не требуется: допустимый slip общего проекта сохраняется. Удержание PTS после trim проверяется в командах/importer, где разреженные карты сначала материализуются. ID и файлы в payload не становятся отдельным реестром.
- `MontageGraph.editableTiming` — производная покадровая проекция для общего `HighQualityFramePlan`. No-op импорт обязан совпадать с исходным планом, включая v1/v2, VFR, repeated PTS, authored tracks и разные sourceIndex. `globalOutputTimeUs` сохраняет глобальные часы при локальном PTS окна.
- `EditableMontageCompiler` подготавливает граф и timing для общего compiler/frame plan; он не сохраняет проект и не запускает директора. Рендер preview/export использует один общий snapshot.
- Чистые команды монтажа подготавливают изменение `HybridRevision`; назначение нового ID, undo/redo, CAS и сохранение выполняются общей точкой `HybridEditCommands.apply`. Совместимое расширение команд требуется до подключения UI; отдельная история модуля не создаётся.

## Минимальные расширения владельцу ядра

Пока не реализованы и не считаются готовыми:

1. Стабильная таблица video asset ID → sourceIndex, в том числе ещё не использованных источников. Порядок клипов не определяет индекс файла.
2. Сохранение optional `manualMontageState` и `editableTiming` в общем codec с сохранением исходных PTS, фаз эффектов и local tracks; round-trip сравнивается по frame plan. Старый проект без payload открывается без изменения расписания.
3. Общие команды trim/reorder/transition/move flash/reset для подготовленного изменения монтажа, с одним allocator, CAS и существующей историей. Изменение других частей ревизии (music/text/framing) не теряется.
4. Параметры исходного экспорта и CaptureLink на общей границе создания/публикации; source duration и playback geometry проверяются по контейнерам.
5. Compiler/analysis seam, который берёт attachments по asset ID/sourceIndex и source PTS; одинаковые PTS двух видео не смешивают маски.

До проверенного storage checkpoint задача 5 плана проверяет контракт и подготавливает интеграционные тесты, но не создаёт конкурирующие `EditableMontageProjectStore` или `EditableMontageProjectCodec`. Задачи 6–9 подключаются к проверенным общим factory/compiler/session/preview API. Нативная проверка и пользовательская приёмка остаются обязательными для готовности возможности.

## Подготовка графа задачи 2

`EditableMontageImporter.create(id, graph, recipe, export: ProjectExportSettings, sources, music, captureLink)` создаёт только значения общего проекта и адаптер. Индексы видео соответствуют порядку `sources`; asset ID, имена файлов, фактическая музыка и экспорт поступают от владельца factory. Начальная ревизия имеет ID 1, следующий общий ID — 2; самостоятельного allocator нет. Импорт принимает исходный winner без manual payload/timing, сохраняет v1/v2/v3 и не синтезирует отсутствующие tracks. Каждый исходный clip получает все scheduled PTS и exclusive endpoint.

`EditableMontageCompiler.compile(project, revision: HybridRevision = project.shared.current)` возвращает снимок `MontageGraph` для общего compiler; `layout(revision: MontageRevision, fps)` возвращает `ClipLayout(clipId, startFrame, endFrameExclusive)`. Чистая проекция `project.revisionView(revision)` принимает ещё не установленного кандидата с текущим ID: временный проект или новый ID для проверки не нужны. No-op сохраняет исходное миллисекундное расписание. Изменённая версия использует только канонический текущий `HybridClip.sourceMap` для source PTS. `HybridClip.original` текущей ревизии — единственный источник текущих `transitionIn`/`transitionDurationMs`; `shared.original` остаётся неизменным. Подготовитель команд меняет текущие descriptors, spans/source maps и `graph.manualMontageState`, сохраняя остальные music/text/style поля ревизии. Назначение ID, parent, истории и CAS — обязанность будущего общего `HybridEditCommands.commitRevision(project, candidate)`.

Дополнительный `ClipPhase(firstFrame, startUs, durationUs)` в `ManualClipState`/`EditableClip`/`EditableClipTiming` сохраняет первый абсолютный номер исходного кадра, исходную границу клипа и исходную длительность. Это позволяет воспроизвести именно legacy `clipProgress`, включая границу 1201 ms, независимо от PTS и видимой длины. `visible.start` — signed смещение в исходной фазе. При подключении будущего общего `HybridClip.originalFrameOffset` это значение необходимо вывести из него, а не хранить две редактируемые истины. Local tracks принадлежат клипу, но их keyframe timestamps остаются в исходном authored clock: sampler получает `ClipPhase.timeUs(originalFrame, fps)`. Их кривые не переписываются при подрезке. Timing и локальные keyframe/value lists копируются в неизменяемые снимки.

`MontageEffectBindings.bind(graph, fps)` явно относит `heartbeat-pulse-*`, `fear-step-*`, `onset-flash-*` и `reference-first-phrase-impact` к Music; transition nodes `whip-glow-*`, `whip-glitch-*`, `impact-glow-*` — к входящему Clip; обычный FLASH в пределах 100 ms от стыка — к Boundary; остальные пересечения — к локальным Clip-сегментам. Близость к биту не определяет Music. Boundary первого клипа сохраняется в payload, но не выводится в граф; возвращение клипа восстанавливает слой. Compiler не генерирует заново удалённые эффекты и не решает политику отключения WHIP bundle: подготовка явных enabled/payload изменений принадлежит задачам 3–4, самостоятельный flash независим.

Overlay/Node имеют `originId`, `phaseStart`, `phaseEnd` с legacy defaults. `EffectSampleWindow(startUs, endUs, offsetUs, authoredStartUs, authoredEndUs, frameShift?, fps?)` хранит точное окно и authored clock сегмента. Для local-сегментов `frameShift` пересчитывает абсолютный номер исходного кадра; простой постоянный сдвиг микросекунд меняет некоторые retained samples на 1 мкс. Heartbeat и Sigma специальные ветви читают `originId`, сегмент идентифицируется `id`. Общему codec нужно сохранить эти поля наряду с payload/timing.

`MontageGraph.sourceAttachments: List<SourceAttachments(sourceIndex, timeline)>` выбирает mask/depth/flow внутри конкретного источника. При непустой таблице отсутствующий sourceIndex не откатывается к общему PTS-only timeline. `HighQualityFramePlan.Frame` передаёт `secondarySourceIndex`, `secondaryAttachments`, `originalOutputTimeUs`; поиск temporal role ведётся в исходном clock. Если искомый кадр подрезан, скрывается слой, первичный PTS продолжает двигаться. Общий decoder/compiler ещё должен потребить secondary source identity и attachments; эта задача не подключает новый decoder backend.

Дополнительные ограничения владельцу ядра: текущий порядок VIDEO assets допускает разные файлы, но не заменяет стабильную таблицу `sourceIndex → selectionId/assetId` для повторных выборов одного deduplicated файла. `ProjectMusic.gain` допускает только 0..1, тогда как legacy AudioTrack — 0..2; importer явно отвергает >1, вместо скрытого clamp. Для общего factory нужно расширить общий диапазон/контракт и проверить audio round-trip. Это открытое ограничение совместимости, не ослабление требования сохранения музыки. Для trim→extend нелинейной карты (включая предварительный slip) задача 3 должна восстановить сохранённые точки из общей original/history: экстраполяция края текущей подрезанной карты не восстанавливает удалённые исходные samples.

## Порядок и проверки

Реализация идёт последовательно агентами с отдельным ревью каждой части. Небольшие JVM-проверки запускаются с `--max-workers=1`. Полные локальные baseline/UI/native-прогоны и запуск эмулятора выполняются по очереди координатора; CI точного опубликованного коммита работает независимо. Чужие процессы не завершаются. Физическая проверка Samsung A25 и художественная приёмка не заменяются CI.
