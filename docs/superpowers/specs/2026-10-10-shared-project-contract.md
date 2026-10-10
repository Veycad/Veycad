# Общий контракт проекта Veycad

Статус: ядро модели и хранения прошло независимое ревью; редактор и интеграционные расширения ещё в реализации. Основа модели: `74b58038abedc918c0877cfa3cec215a0f4d9a9e`; хранение с исправлением: `b6f860b5a57827abc5744207d57dd85a5c7678e6`, PR #15, ветка `codex/hybrid-mode-design`. 16 целевых storage-тестов прошли; полный CI этого checkpoint пока не подтверждён.

## Доступно в checkpoint

| Файл | Стабильный API |
| --- | --- |
| `HybridProject.kt` | `HybridProject(id, schemaVersion, fps, nextRevisionId, assets, original, current, undo, redo, exports)`; `HybridRevision(id, parentId, graph, clips, music, texts, style, lockedCutIds)` |
| `HybridProject.kt` | `ProjectAsset(id, fileName, kind, durationUs, contentHash, displayName)`; `HybridClip(id, assetId, span, sourceMap, original)` |
| `ProjectClock.kt` | `ProjectClock(fps).timeUs(frame)`, `.nearestFrame(timeUs)`; `FrameSpan(start, endExclusive)` с `length` |
| `SourceTimeMap.kt` | `SourceTimeMap(points)`, `Point(localFrame, sourceTimeUs)`, `.sample(localFrame)`, `.slice(from, until)`, `.extendLeft(frames, minimumUs)`, `.extendRight(frames, maximumUs)` |

Частота проекта — 30 или 60 fps. Границы измеряются кадрами; время берётся от абсолютного номера кадра. `SourceTimeMap` содержит границы 0 и N для N кадров, допускает повторяющиеся PTS и не пересчитывает существующие samples через скорость. Фабрика автоматического результата сохранит каждый фактический legacy/VFR sample, включая двухисточниковые графы. Команды обязаны сохранить все оставшиеся samples: у разреженной карты округление новых концов среза иначе может изменить несохранённый промежуточный sample на 1 мкс.

`HybridClip.assetId` определяет файл; `HybridClip.original.sourceIndex` сохраняет индекс выбранного элемента. При адаптации N источников индекс не перенумеровывается из-за перестановки клипов. Требуется долговечная упорядоченная таблица `sourceIndex → selectionId/assetId`, включая ещё не использованные источники. Один файл после дедупликации может соответствовать нескольким выбранным элементам с разными selection ID и индексами. Это расширение checkpoint, а не уже реализованная гарантия. Коллекции проекта снимками защищены от изменения извне; глубокая фиксация массивов анализа относится к границам сохранения и компиляции.

## Хранение — checkpoint b6f860b5

`HybridProjectStore(filesDir)` предоставляет `create(project)`, `load(projectId)`, `save(project, expectedRevisionId)`, `list()`, `delete(projectId)`, `directory(projectId)`. Каталог `projects/<id>/` принадлежит проекту. Исходники копируются до публикации ссылок; очистка transient/draft не удаляет их.

`ProjectAssetStore(projectDirectory).import(source, kind, durationUs)` возвращает `ProjectAsset`; `.resolve(asset)` возвращает безопасный путь внутри owned sources. Проверка существования выполняется при публикации; resolve сам не проверяет содержимое по hash. Не создавать второй конкурирующий `ProjectAsset` или project store. Опубликованные asset records неизменяемы; save сохраняет исторические привязки и их порядок, новые assets добавляются в конец.

`HybridProjectCodec(sidecars)` сохраняет структурные поля графа primitive binary форматом; большие данные анализа — hashed sidecars. `decodeWithAnalysisStatus(bytes)` и store `loadWithAnalysisStatus(id)` возвращают неизменяемый `ProjectLoadResult(project, missingAnalysisHashes)` с `analysisRegenerationRequired`. Store `loadRevision(projectId, revisionId)` читает опубликованную ревизию, включая удалённую из undo. Повреждение анализа не удаляет исходники. Missing hashes консервативно сохраняются до ремонта исходного hash; замена другим hash и rehydration относятся к задаче 5. Текущий decoder ограничивает eager analysis 64 MiB; для предпросмотра потребуется lazy reader с общим бюджетом 32 MiB. API-only TODO не являются checkpoint.

## Команды и компиляция — запланированы

Единственная точка применения правки — `HybridEditCommands.apply(project, ProjectCommand)`; результат получает новый монотонный revision ID и сохраняется через CAS `save(..., expectedRevisionId)`. Undo/redo сохраняются в ядре. `replaceMusic` принимает дополнительный импортированный asset и прикрепляет его атомарно.

`commitRevision(project, candidate)` предназначен для внешних правок ручного монтажа и visual settings. Candidate сохраняет ID текущей ревизии; ядро проверяет его, выделяет следующий ID и parent и применяет общую историю. `HybridClip.originalFrameOffset` сохранит исходную локальную фазу кадра независимо от source PTS: подрезка начала увеличивает смещение, расширение влево уменьшает, slip сохраняет. Новый binary reader обязан читать прежнюю версию с нулевым смещением; старые проекты не переписываются при чтении.

`HybridCutConstraints.range(revision, cutId, assets, fps)` получает fps явно; convenience overload может принимать проект. ID склейки — ID входящего правого клипа.

`HybridProjectCompiler.compile(project, revision)` создаёт неизменяемый `CompiledHybridProject` с общим `HighQualityFramePlan.Plan`. Кадр несёт разные часы: абсолютный `projectTimeUs` для стиля/текста и локальный `outputTimeUs` для кодирования окна. Preview не пересобирает музыку, текст или ручные склейки. Владельцы ручного монтажа и Live Scrubbing подключаются адаптерами к этому API; самостоятельные competing compiler/ID/storage не должны стать источниками истины.

Источник frame attachments по asset ID будет закреплён в задаче адаптации стилей: ключ включает asset ID/content hash, source PTS и версию анализа. Текущий общий `graph.frameAttachments` ещё не является таким resolver для N источников.

## Необходимые расширения интеграции

После проверки storage потребуется отдельный совместимый checkpoint; изменения принадлежат владельцу ядра:

- Стабильная упорядоченная таблица `sourceIndex → selectionId/assetId`; геометрия и provenance/capture origin видео. Дедупликация физических файлов не объединяет выбранные элементы. Framing привязывается к выбранному элементу, чтобы две вставки одного файла могли иметь разные настройки. Новые optional поля должны открывать старые проекты без потери данных.
- `HybridRevision.visualSettings` сохраняет полный `ProjectVisualSettings` из владельца Live Scrubbing: aspect, explicitlySelected, framings и ключ выбранного элемента/aspect. Его `withAspect/withFraming` возвращают visual edit для команды/CAS ядра, без отдельного revision counter.
- `savedOutputTimeUs` хранится как runtime state отдельно от undoable revision. Семантические ссылки/leases активного preview/export принадлежат общему store.
- Полные `TextLayer/TextStyle/CaptionCue` принадлежат текстовому модулю PR #12. Адаптер текста обязан сохранить SANS/BOLD/SERIF, позицию, размер, цвет, подложку и NONE/FADE/SLIDE; текущие три `TextItem.Appearance` не покрывают этот контракт. Схему расширить до импорта rich text, не обрезать его до простого заголовка. Нормализованное ручное размещение гибридного режима тоже сохраняется.
- STT остаётся за `SpeechTranscriber` текстового модуля; вторую независимую реализацию не создавать. Субтитры видимого/слышимого финального MP4 и субтитры исходной речи после монтажа требуют разных явно проверенных адаптеров.

Галерея передала проектируемый `SourceDraft`: project ID, schema version, CAS etag, owned assets, упорядоченные video selections и необязательный durable migration key. До анализа он хранится без фиктивного графа и музыки. Продвижение в первый реальный HybridProject сохраняет тот же project ID и атомарно проверяет etag. Импорт пишет в закрытую транзакцию; отмена до публикации откатывает её. Общий store должен защищать import/analysis/preview/export межпроцессными leases; удаление исходников при активном lease возвращает Busy. Очистка после сбоя не трогает живые транзакции. Это требования следующего совместимого checkpoint; указанные API ещё не реализованы.

Текстовый контракт включает также captionStyle, captionsEdited и language (auto/ru/en). Все интервалы текста и cues используют абсолютный project time с полуоткрытыми границами. Для исходной речи STT получает borrowed PCM s16le/16 kHz/mono после монтажа; текстовый модуль его не удаляет. Слышный финальный микс проверяется отдельным адаптером. Контракты: `text-subtitles/AutoEdit/docs/integration/text-subtitles-contract.md` и `gallery-text-spec/AutoEdit/docs/superpowers/specs/2026-10-10-gallery-import-staging-contract.md` в соответствующих worktree, прочитаны 2026-10-10; их предложенные core API не являются проверенным кодом.

Контракты других чатов сначала проверяются в их коммитах и затем адаптируются. Чужой WIP, готовность отдельного модуля и зелёные structural tests не считаются готовностью общего режима. Проверки Samsung A25 и человеческая приёмка фиксируются отдельно.
