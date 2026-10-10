# Общий снимок, leases и страницы анализа

Статус: обязательный интеграционный контракт PR #15, реализации полного freeze/lazy reader ещё нет. Основание — одобренный гибридный режим и решение координатора сохранить общие проект, runtime и compilation authority. Preview/export получают один снимок; каждый не копирует все входные маски. Реализация: integration C (OS leases), task4 (compile snapshot), task5 (immutable semantic blob v2 и lazy reader), tasks11/13 (потребители и lifecycle).

## Идентичность и handoff

Снимок закрепляет `projectId`, `revisionId`, hash опубликованного revision payload, fps, ordered selection table, music/text/visual state и immutable semantic references. Одинаковый revision ID недостаточен для runtime callback: undo может вернуть старую ревизию; coordinator добавляет operation generation. Изменение saved playhead не меняет этот снимок.

Semantic reference содержит source content hash, analysis version/profile/cadence, immutable blob hash и track generator/window identity. Ключ не строится из mutable current-analysis pointer. После handoff замена анализа у проекта не меняет hash/reference уже выданного снимка; новый consumer получает новый снимок. Project lease не позволяет удалить закреплённые исходники, музыку и данные анализа. Publish/read сначала проверяют payload и только затем делают snapshot доступным.

Legacy `FrameAttachmentTimeline.frames/maskRefinements`, `Plane.values`, flow vectors и blend target могут удерживать mutable caller storage. Это не immutable snapshot. При handoff ядро фиксирует их один раз на уникальный payload: небольшие metadata containers защищает, большие planes сериализует в hash-addressed файл ограниченными чанками или передаёт из уже проверенного immutable blob. Ни legacy graph, ни producer-owned arrays не остаются authority frozen snapshot. Producer обязан завершить запись до handoff; одновременное изменение в процессе фиксации не считается допустимым протоколом.

Compiled plan хранит read-only descriptors и references. Доступ к planes идёт через принадлежащие ядру page handles; публичный snapshot не отдаёт свои mutable arrays/list storage. Renderer adapter может брать scoped read-only view для загрузки GL. Нельзя исправлять ownership копированием planes для каждого scheduled frame или для каждого consumer.

## Lease и shared page reader

`ProjectLease` — закрываемый идемпотентный handle для pin project-owned files. Store координирует process-wide shared refcount и OS liveness lock вне каталога, который переименовывается или удаляется. Процессный exit освобождает OS lock; закрытие одного consumer не освобождает файлы другого. Импорт/анализ/preview/export используют один механизм. `deleteSources` при lease возвращает Busy.

Один shared page cache на runtime проекта обслуживает все readers preview/export. Ключ страницы — blob hash и page index, с учётом layout version; замена analysis создаёт другой ключ. Reader предоставляет `requiredBytes(sourceTimeUs)`, `sampleAt(sourceTimeUs)`, `smartTrack()` и `close()`; имена окончательно закрепляются после независимого ревью реализации. Plane page handle удерживает reservation/reference count до закрытия. Close reader освобождает его handles; lease закрывается после decoder/GL/readers, включая cancel/failure/lifecycle paths. Page только с нулевым refcount может быть вытеснена. Reader не владеет producer timeline и не хранит eager копии всех planes.

Все reservations выполняются до allocation и проверяются overflow-safe. Дополнительный общий app budget — **32 MiB**, включая compact headers/tracks, decoded plane pages, interpolation/upload scratch, thumbnails, preview caches и FBOs. У каждой из этих категорий явный reservation/release, а не отдельный независимый лимит32MiB. MediaCodec buffers измеряются отдельно. Если текущую пару входов/страниц нельзя разместить, preparation возвращает объяснимую ошибку или уже одобренный explicit fallback; не публикует READY и не пропускает семантику молча. Одновременно не более2 live video decoders. Лимит legacy eager decoder64MiB не является лимитом нового lazy runtime.

## Semantic blob v2 и compact smart track

Dependency: public semantic declarations и cache v19 в preview checkpoint `5f545044a9f17c0e37b6cba79dbc959b9e1ee4a4`, PR #24; task/fix review clean, exact-head CI ещё проверить. Прочитан owner contract `preview-aspect-spec/AutoEdit/docs/design/live-preview-semantic-contract.md`. Core до импорта использует cache v18; не заявлять v19/READY как реализованные.

Header v2 сохраняет physical source hash, analysis version/profile/cadence, `SmartFramingTrack.GENERATOR_VERSION=1`, canonical source windows `[startUs,endUs)` и sorted points `(sourceTimeUs,centerX,centerY,status)`. Point payload: Long + two Float + explicit one-byte status tag,17bytes. Теги TRACKING/HOLDING/NO_PERSON/AMBIGUOUS/UNKNOWN задаются явными константами; enum ordinal не persistent format. Identity включает generator и windows, которые меняют результат.

Restore вызывает `SmartFramingTrack.fromPoints(points,sourceWindows)` без загрузки masks/depth/flow. Проверяются bounded header/window/point counts и фактические byte extents до allocation, unique sorted nonnegative PTS, canonical windows, coordinates0..1 и точки внутри windows. Gap не интерполируется; вне windows — centered UNKNOWN. Compact track тоже учитывается в32MiB. `smartTrack()` читает header, не декодирует planes.

Face count сохраняется независимо от largest-face region: nullable detectedFaceCount и faceInferenceSucceeded берутся от одного ближайшего semantic sample; null не превращается в0 или1. v19 rejects v18/earlier analysis-cache layout; совместимость старых проектов обеспечивает missing/regeneration status, без удаления исходников и без фиктивного upgrade. Track/header/planes проходят validation и atomic durable publish до READY. Ошибка track/blob не оставляет READY с пустой подменой.

## Обязательная проверка

- После handoff изменить producer lists и массивы mask/depth/blend/flow: все выбранные значения frozen preview/export остаются прежними. Два consumers используют один hash/page ownership, не две полные копии.
- Заменить analysis pointer/blob после handoff: старый снимок читает прежний hash, новый читает новый. Старый pinned blob не удаляется до release.
- Cancel/failure/close в разных порядках освобождают reservations и leases один раз; живой consumer не теряет pages. Process death освобождает liveness lock, сохранённая ревизия остаётся читаемой.
- Roundtrip header восстанавливает точный track/status/windows без Plane allocation; corrupted/huge counts отвергаются до allocation; gap/unknown/ambiguous и face0/1/2 сохраняют owner semantics.
- Preparation failure между blob/header публикацией и READY восстанавливается без ложного READY. Page+track+FBO budget соблюдается вместе; превышение даёт объяснимый результат.

JVM ownership/IO tests подтверждают модель handoff и crash simulation. Фактическая память Android, decoder/GL release, MP4 parity, real ML и человеческая приёмка требуют отдельных конечных gates.
