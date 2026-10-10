# Общий контракт проекта Veycad

Статус: ядро модели и хранения прошло независимое ревью; редактор и интеграционные расширения ещё в реализации. Основа модели: `74b58038abedc918c0877cfa3cec215a0f4d9a9e`; хранение с исправлением: `b6f860b5a57827abc5744207d57dd85a5c7678e6`, PR #15, ветка `codex/hybrid-mode-design`. 16 целевых storage-тестов прошли. Опубликованный model/storage/docs checkpoint `b5b6af962a37f7bbe1fe60520e6f7d6a7cafa3dc` прошёл оба exact-head CI: [Android baseline](https://github.com/Veycad/Veycad/actions/runs/38048485924) и [Android UI](https://github.com/Veycad/Veycad/actions/runs/38048485782). Это не проверка ещё не подключённых функций редактора.

Команды checkpoint `06811435d7c37187f55074f40eb347aea61a467f`: 72 целевых JVM-теста прошли; независимое ревью и оба scoped повторных ревью завершены, открытых важных замечаний нет. Полный CI этого нового checkpoint ещё не подтверждён. Ниже перечислены фактические интерфейсы; новый renderer/UI ещё не подключён.

## Доступно в checkpoint

| Файл | Стабильный API |
| --- | --- |
| `HybridProject.kt` | `HybridProject(id, schemaVersion, fps, nextRevisionId, assets, original, current, undo, redo, exports)`; `HybridRevision(id, parentId, graph, clips, music, texts, style, lockedCutIds, restoresAutomaticSources=false)` |
| `HybridProject.kt` | `ProjectAsset(id, fileName, kind, durationUs, contentHash, displayName)`; `HybridClip(id, assetId, span, sourceMap, original, originalFrameOffset=0)` |
| `ProjectClock.kt` | `ProjectClock(fps).timeUs(frame)`, `.nearestFrame(timeUs)`; `FrameSpan(start, endExclusive)` с `length` |
| `SourceTimeMap.kt` | `SourceTimeMap(points)`, `Point(localFrame, sourceTimeUs)`, `.sample(localFrame)`, `.slice(from, until)`, `.extendLeft(frames, minimumUs)`, `.extendRight(frames, maximumUs)` |

Частота проекта — 30 или 60 fps. Границы измеряются кадрами; время берётся от абсолютного номера кадра. `SourceTimeMap` содержит границы 0 и N для N кадров, допускает повторяющиеся PTS и не пересчитывает существующие samples через скорость. Фабрика автоматического результата сохранит каждый фактический legacy/VFR sample, включая двухисточниковые графы. Команды обязаны сохранить все оставшиеся samples: у разреженной карты округление новых концов среза иначе может изменить несохранённый промежуточный sample на 1 мкс.

`HybridClip.assetId` определяет файл; `HybridClip.original.sourceIndex` сохраняет индекс выбранного элемента. При адаптации N источников индекс не перенумеровывается из-за перестановки клипов. Требуется долговечная упорядоченная таблица `sourceIndex → selectionId/assetId`, включая ещё не использованные источники. Один файл после дедупликации может соответствовать нескольким выбранным элементам с разными selection ID и индексами. Это расширение checkpoint, а не уже реализованная гарантия. Коллекции проекта снимками защищены от изменения извне; глубокая фиксация массивов анализа относится к границам сохранения и компиляции.

## Хранение — checkpoint b6f860b5

`HybridProjectStore(filesDir)` предоставляет `create(project)`, `load(projectId)`, `save(project, expectedRevisionId)`, `list()`, `delete(projectId)`, `directory(projectId)`. Каталог `projects/<id>/` принадлежит проекту. Исходники копируются до публикации ссылок; очистка transient/draft не удаляет их.

`ProjectAssetStore(projectDirectory).import(source, kind, durationUs)` возвращает `ProjectAsset`; `.resolve(asset)` возвращает безопасный путь внутри owned sources. Проверка существования выполняется при публикации; resolve сам не проверяет содержимое по hash. Не создавать второй конкурирующий `ProjectAsset` или project store. Опубликованные asset records неизменяемы; save сохраняет исторические привязки и их порядок, новые assets добавляются в конец.

`HybridProjectCodec(sidecars)` сохраняет структурные поля графа primitive binary форматом; большие данные анализа — hashed sidecars. `decodeWithAnalysisStatus(bytes)` и store `loadWithAnalysisStatus(id)` возвращают неизменяемый `ProjectLoadResult(project, missingAnalysisHashes)` с `analysisRegenerationRequired`. Store `loadRevision(projectId, revisionId)` читает опубликованную ревизию, включая удалённую из undo. Повреждение анализа не удаляет исходники. Missing hashes консервативно сохраняются до ремонта исходного hash; замена другим hash и rehydration относятся к задаче 5. Текущий decoder ограничивает eager analysis 64 MiB; для предпросмотра потребуется lazy reader с общим бюджетом 32 MiB. API-only TODO не являются checkpoint.

## Команды checkpoint 06811435; компиляция — запланирована

Единственная точка применения правки — `HybridEditCommands.apply(project, ProjectCommand)`; новая правка получает монотонный revision ID и сохраняется через CAS `save(..., expectedRevisionId)`. Undo/redo выбирают прежние ID, сохраняя отдельный nextRevisionId. Доступны MoveCut, SlipClip, ReplaceMusic, PutText, RemoveText, SetAuthoredText, CommitRevision, Undo, Redo, RestoreAutomatic. `replaceMusic` принимает дополнительный импортированный asset и прикрепляет его атомарно. No-op не расходует ID/history. Неверная правка отклоняется с HybridEditRejected.

`commitRevision(project, candidate)` предназначен для внешних правок ручного монтажа и visual settings. Candidate сохраняет ID текущей ревизии; ядро проверяет его, выделяет следующий ID и parent и применяет общую историю. `HybridClip.originalFrameOffset` сохраняет исходную локальную фазу кадра независимо от source PTS: подрезка начала увеличивает смещение, расширение влево уменьшает, slip сохраняет. Physical v3 reader явно читает v1 (phase0/resetfalse), v2 (signedphase/resetfalse) и v3 (signedphase/resetevent). Логическая schemaVersion остаётся1; genuine v1/v2 fixtures проверяют чтение без rewrite.

`restoresAutomaticSources` — provenance event только ревизии, выделенной RestoreAutomatic. Обычный public commit очищает inherited/supplied marker при новой правке; no-op сохраняет текущую ревизию. Undo/redo сохраняют событие. Model проверяет отмеченные source clips по immutable original. Явный reset может изменить выбор скрытой кривой при одинаковом видимом payload и потому создаёт ревизию; reset фактического original или уже выбранного unchanged reset остаётся no-op. Внешние adapters не реализуют собственный reset.

`HybridCutConstraints.range(revision, cutId, assets, fps)` получает fps явно; convenience overload принимает проект. ID склейки — ID входящего правого клипа.

После подрезки редактор должен использовать `range(project, cutId)`: этот overload учитывает выбранную историю/явный reset и сохранённые скрытые samples. Revision-only overload знает текущий край, не history. Восстанавливаются последние доказанно выбранные nonlinear samples с учётом slip; redo не подменяет backing новой ветки. Проверка сравнений ограничена общим бюджетом до перебора длинной sparse карты. Неоднозначные/missing/pruned backing дают объяснимый отказ; долговечный backing после pruning и shared maps для50 длинных историй остаются обязательным A2 до подключения потребителей.

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

Обязательные интеграционные этапы: [A1/A2/B/C](../plans/2026-10-10-hybrid-integration-checkpoints.md). Freeze, shared page ownership, leases и semantic blob v2 закреплены в [контракте неизменяемого снимка](2026-10-10-frozen-project-contract.md); их полная реализация ещё впереди. Smart framing dependency — reviewed `5f545044a9f17c0e37b6cba79dbc959b9e1ee4a4`, [draft PR #24](https://github.com/Veycad/Veycad/pull/24), с отдельным review адаптации и проверкой exact-head CI. Снимок закрепляет immutable hash, поэтому обновление анализа после handoff не меняет preview/export. Lazy header/track/planes и FBO учитываются в общем32MiB, planes не копируются целиком на каждого потребителя.
