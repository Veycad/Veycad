# Предложение staging/импорта для общего проекта

Дата: 2026-10-10. Статус: конкретный контракт для согласования владельцем storage #15; не реализованный API. Продуктовые требования #13/#14 сохранены. Единственный владелец ID, каталогов projects, leases и атомарной публикации — HybridProjectStore.

## Данные и идентичность

`SourceDraft` — envelope выбранных источников до появления graph/music, под тем же ID и в том же каталоге, который затем принимает HybridProject. Это не второй пользовательский проект, не история правок и не новый revision counter. У draft есть schemaVersion и content-derived etag для CAS перехода, но HybridRevision появляется только с настоящим графом.

`ImportedVideoSource(selectionId, sourceIndex, assetId, displayName, durationUs, sizeBytes, width, height, rotationDegrees, mime, colorTransfer, hasAudio, fingerprint, firstVideoPtsUs, videoEndPtsUs)` хранится как неизменяемая упорядоченная таблица. Имена дополнительных timing-полей — предложение владельцу #15, а не уже реализованный API. sourceIndex — позиция в source list, selectionId уникален для каждого выбранного элемента. Файл и hash принадлежат ProjectAsset. Таблица sourceIndex → selectionId/assetId обязательна: разные URI с одинаковым содержимым могут ссылаться на один deduplicated asset, поэтому assetId → единственный sourceIndex недостаточно. Gallery MediaSourceSet — runtime view этой таблицы, не отдельный store/ID.

Для бюджета 30 минут нужна проверенная длительность содержимого, отдельно от объявленной track duration и абсолютного конца PTS. `firstVideoPtsUs` — фактически измеренный минимальный video PTS из MediaInputInspector. `videoEndPtsUs` — независимо валидированная граница сырой шкалы; usable span равен `videoEndPtsUs - firstVideoPtsUs`, обе операции проверяются на переполнение/порядок. Сложение origin с duration допустимо только при доказанном контракте content duration. `MediaFormat.KEY_DURATION` сам по себе такого доказательства не даёт. Если прежнее поле `durationUs` сохраняет объявленную длительность для совместимости, оно не заменяет raw endpoint и usable span в анализе, кэше, import policy и SourceTimeMap. Конкретное расширение/миграция остаются у #15; до reviewed timing checkpoint старый runtime descriptor не является готовым контрактом источников.

Например, измеренный first=120000 и доказанный content span=500000 дают raw end=620000; кадр586666 допустим, а сравнение с500000 неверно. Но объявленная track duration может включать initial empty edit, и добавлять origin повторно нельзя. Gallery moments и actual decoded evidence сохраняют сырые PTS; requested target и actual decoded PTS остаются различными полями. Кэш проверяет origin/raw endpoint/usable span и версию timing-контракта и привязывает результат к selectionId. Источник с неразрешимой конвенцией не получает выдуманную measured границу; требуется измерение или явный безопасный отказ.

Конкретное подтверждение этой поправки: exact `87ea4cf417de856743257d8b425f7e52d767c708`, Android UI run `38055369426`, все82теста прошли; artifact11671436474, SHA-256 `ab57a2bca777c640883f76460d00684961b7d725636cb39ab703b4841997e08c` проверен. Независимый лог фикстуры содержит first120000, last1261000, KEY_DURATION1294300, authoredEOS1294334. Старый `first + duration` даёт1414300; один gallery moment заканчивается1414300, за EOS на119966us. Это производственный дефект границы, несмотря на зелёный CI. Отдельный G4 fix2 проверяет raw endpoint; legitimate final hold, прежние declared505000/600000 и legacy requested/saved PTS не обрезаются ради исправления.

Проверенный runtime checkpoint: `27e7cdff744c64c5247fca0a357e3b86a9c9c428` в PR #25. В `MediaSource` поле `durationUs` сохраняет прежнюю объявленную длительность либо legacy estimate; `firstVideoPtsUs: Long = 0L` хранит origin, измеренный inspector, а legacy default сам по себе его не доказывает. Новое `videoPresentationBounds: VideoPresentationBounds? = null` содержит независимо доказанные `firstPtsUs`, exclusive `endPtsUs` и `timingVersion=1`. `videoEndPtsUs` требует этой evidence; `videoContentDurationUs` равен end − first. Нет evidence — gallery policy/analysis отвергают источник, не подставляя `first + duration`. Legacy constructors остаются совместимыми, но не доказывают пригодность для gallery.

`MediaInputInspector` привязывает timing proof к явному ID выбранной дорожки и сверяет sample count/PTS extrema. Реализованный reader ограничен nonfragmented ISO-BMFF: stts/ctts и поддержанные unit-rate edits; malformed/fragmented/неподдержанные timing metadata дают unknown. Финальные авторские holds сохраняются. Это reviewed implementation checkpoint, не доказательство работы всех внешних контейнеров. Cache profile3 сравнивает полную bounds evidence/version и fingerprint; старые записи не используются как измерение.

При сериализации owner #15 сохраняет эквивалентные origin/end/version вместе с идентичностью неизменяемого asset; точные имена storage API остаются у владельца. Только два известных совместимых endpoint дают usable span; частичная или старая запись без proof требует reinspection сохранённого файла перед gallery. Перезагрузка не должна терять proof или превращать unknown в нулевой origin/объявленную длительность. Legacy requested/saved source targets не пересчитываются. Сам runtime descriptor не реализует draft/store/compiler/leases.

Scoped spec/quality rereview checkpoint27 — PASS; целевые JVM86/11suites и bounded Android fixture Kotlin compile прошли. Exact-head baseline38058108661 прошёл: root проверил artifact11671363674 по SHA-256 и фактические718JVM/255Python без отказов/пропусков. Android UI38058108671 ещё выполняется. До подтверждения real track-ID/edit binding и независимого authoredEOS1294334/нулевого числа moments за EOS native gate остаётся открытым. Старые82 GREEN на87 не переносятся на27.

Общий владелец #15 должен совместимо закрепить origin и source bounds в таблице/asset metadata и проверках SourceTimeMap до её интеграции. Сохранённые legacy targets не сдвигаются и не пересчитываются; origin не разрешает подменять их фактическими decoder PTS. Отсутствующий origin старой схемы не доказывает нулевое начало: миграция при необходимости измеряет его по сохранённому файлу, не переписывая legacy schedule. Это требование следующего checkpoint владельца #15; reviewed G4 runtime descriptor/inspector не реализуют общий store/clock.

`SourceDraft(projectId, schemaVersion, etag, assets, videoSources, legacyMigrationKey?)` содержит только проверенные файлы/метаданные, без фиктивного graph/music. Все ссылки на файл — проверенные относительные имена, разрешаемые ProjectAssetStore. Опубликованные assets живут физически внутри каждого проекта: удаление одного проекта не портит другой.

## Минимальная предлагаемая граница общего store

```kotlin
HybridProjectStore.beginSourceImport(previousProjectId: String?): SourceImportTransaction
HybridProjectStore.loadSourceDraft(projectId: String): SourceDraft?
HybridProjectStore.promoteSourceDraft(project: HybridProject, expectedDraftEtag: String): Unit
HybridProjectStore.acquireLease(projectId: String): ProjectLease
HybridProjectStore.deleteSources(projectId: String): DeleteSourcesResult

SourceImportTransaction.projectId: String
SourceImportTransaction.directory: File
SourceImportTransaction.publish(assets: List<ProjectAsset>, sources: List<ImportedVideoSource>,
                                legacyMigrationKey: String? = null): SourceDraft
SourceImportTransaction.close(): Unit // rollback unless publish succeeded
```

Имена окончательно закрепляет владелец #15. Existing `ProjectAssetStore(transaction.directory).import(file, VIDEO, durationUs)` / `resolve(asset)` сохраняются. Расширение для bounded input stream/precomputed digest допустимо у этого владельца, не во втором asset store. Transaction directory принадлежит shared store и не пересекается с опубликованным previousProjectId.

## Copy, проверка и публикация

1. Новый ID и private transaction directory создаёт shared store. Импортёр открывает content URI и копирует ограниченными chunks в этот каталог, SHA-256 считается по потоку. Неизвестный размер — null, не ноль. Известный размер — подсказка, фактический byte budget проверяется всегда.
2. Проверяется свободное место с reserve под manifest/экспорт; счётчик фактических bytes и cancellation обновляются между chunks. Не держать архив в RAM. При отмене закрыть активный stream/decoder; блокирующий провайдер должен иметь проверяемое освобождение, не только флаг UI.
3. MediaInputInspector проверяет настоящий video track, PTS/duration/geometry/rotation/audio, baseline AVC/SDR либо working SDR decoder probe. HDR HLG/ST2084 отклоняется явно. Расширение файла не является доказательством формата.
4. Лимит входного picker batch — максимум 20 selections: превышение отклоняет весь запрос до IO, предыдущий проект остаётся. Финальная таблица отдельно содержит максимум 20 selections (kept + newly accepted, включая одинаковые bytes) и суммарно ≤30 минут; каждый источник ≥0.5 s. Новые файлы проверяются в порядке выбора: превышающий оставшийся count/duration budget файл даёт rejection, последующие ещё могут быть приняты. accepted содержит только новые успешные файлы; full draft передаётся отдельно. Для REPLACE/ADD без явного изменения kept-набора ноль новых accepted сохраняет previousProjectId и откатывает transaction. Семантика FORK/removal ниже.
5. Перед publish все assets синхронизированы, metadata/order сериализованы и проверены. Публикация нового draft выполняется атомарным rename/replace под shared-store lock; ссылок на .partial, cache или чужой URI в опубликованном envelope нет. Ошибка/отмена до commit не изменяет previous project или его MP4.
6. При partial success публикуется только проверенный набор, UI показывает каждую ошибку. Cancellation — rollback, не per-file rejection с публикацией половины отменённой операции.

## Переход в HybridProject и изменённый выбор

После director готов настоящий graph/music и assets аудио, создаётся HybridProject с тем же projectId. `promoteSourceDraft` CAS проверяет, что current pointer всё ещё SourceDraft с ожидаемым etag, проверяет assets/source table, пишет revision/sidecars и атомарно публикует первую настоящую ревизию. Recovery copy старого draft не разрешает повторное promote поверх уже изменённого HybridProject. При сбое старый source draft остаётся восстановимым; после успеха источники, порядок и geometry переходят в общую схему. Не публиковать placeholder clip/music ради прохождения constructor validation.

Для REPLACE kept-набор пуст; ADD сохраняет весь previous source set; FORK явно задаёт keepSelectionIds в исходном порядке и может добавлять новые URI. Лимиты применяются ко всей будущей таблице, не к количеству уникальных assets. Removal-only FORK публикует retained subset с accepted/rejected пустыми; удаление последнего выбора публикует пустой source draft и выключает analysis/export (draft допускает 0, render требует 1–20). Если все новые URI отвергнуты, но FORK явно удалял старые selections, публикуется этот retained subset вместе с ошибками: removal не откатывается молча. Если kept-набор не менялся и новых accepted нет, остаётся previousProjectId. В итоговый HybridProject пустой draft не переводится.

Замена выбора и add/remove создают новый импортный draft/ID с собственными копиями kept assets. Исходники уже опубликованного/редактируемого проекта остаются. Полная таблица индексов фиксируется до первой graph publication; перестановка клипов не меняет sourceIndex. Append/remove после публикации применяются как согласованная операция общего ядра или новый проект, не неявное перенумерование существующих графов.

## Lease, очистка и миграция

- Lease принадлежит shared store и защищает source assets для analysis/preview/export в разных экземплярах/процессах. `deleteSources` при активном lease возвращает Busy, не удаляет используемые файлы. После удаления сохраняются metadata/tombstone и опубликованный MP4; повторное редактирование показывает missing sources явно.
- `beginSourceImport` устанавливает store-owned liveness lock/lease до первой копии и держит его до commit/rollback, включая migration. Lock находится вне переименовываемой transaction directory. `clearTransient`/recovery из другого экземпляра/процесса пропускает live imports и возвращает только abandoned transactions после получения cross-process protection; crash освобождает OS lock. Активный uncommitted import не считается мусором. `clearTransient` не удаляет `projects/<id>/sources`. Сбои pointer/revision recovery не удаляют source draft с ещё не принятым результатом.
- Legacy migration получает устойчивый ключ старого draft. Shared store публикует copied source draft и durable marker; повторный вызов возвращает тот же ID. Для crash между publication/marker legacyMigrationKey envelope позволяет восстановить marker. Legacy originals сохраняются до успешного принятия нового draft вызывающим EditSession.
- Если migrated project явно удалён пользователем, marker не должен молча запускать повторный импорт старого draft. Missing result сообщает отличимую ошибку/статус. Параллельные migration/import/delete операции используют ту же lock/lease дисциплину владельца storage.

## Проверки границы

Проверяются реальные файлы/atomic publication и fault injection на внешних IO границах: неизвестный content URI size, потеря доступа в середине копии, disk budget, один malformed файл среди исправных, всё отвергнуто, cancellation, повторный hash под двумя URI, rotation/VFR/nonzero PTS/EOF, active lease/delete, restart до/после pointer replace, stable migration ID, сохранность предыдущих source assets и MP4. Дополнительно: 20 kept + 1 new, дублированный hash, near-30min retained + new, picker >20, removal-only/zero/new-all-invalid, copy/migration одновременно с clearTransient из другого процесса, crash recovery. По transition проверяются CAS conflict, second promotion rejection и точное сохранение sourceIndex/PTS.

Тяжёлые Android прогоны выполняются только по очереди координатора на выделенном `.uitest`. Текущий код/метаданные допускают небольшие JVM проверки `--max-workers=1`; настоящая device inspection/экспорт и Samsung A25 не считаются пройденными по структурным тестам.
