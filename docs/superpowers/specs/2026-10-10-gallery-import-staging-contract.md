# Предложение staging/импорта для общего проекта

Дата: 2026-10-10. Статус: конкретный контракт для согласования владельцем storage #15; не реализованный API. Продуктовые требования #13/#14 сохранены. Единственный владелец ID, каталогов projects, leases и атомарной публикации — HybridProjectStore.

## Данные и идентичность

`SourceDraft` — envelope выбранных источников до появления graph/music, под тем же ID и в том же каталоге, который затем принимает HybridProject. Это не второй пользовательский проект, не история правок и не новый revision counter. У draft есть schemaVersion и content-derived etag для CAS перехода, но HybridRevision появляется только с настоящим графом.

`ImportedVideoSource(selectionId, sourceIndex, assetId, displayName, durationUs, sizeBytes, width, height, rotationDegrees, mime, colorTransfer, hasAudio, fingerprint)` хранится как неизменяемая упорядоченная таблица. sourceIndex — позиция в source list, selectionId уникален для каждого выбранного элемента. Файл и hash принадлежат ProjectAsset. Таблица sourceIndex → selectionId/assetId обязательна: разные URI с одинаковым содержимым могут ссылаться на один deduplicated asset, поэтому assetId → единственный sourceIndex недостаточно. Gallery MediaSourceSet — runtime view этой таблицы, не отдельный store/ID.

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
4. Файлы с ошибками дают отдельные rejected entries. 1–20 принятых видео, суммарно ≤30 минут, каждый ≥0.5 s. Успешные accepted — только новые успешно импортированные файлы; результат содержит полный опубликованный draft отдельно. Если всё отвергнуто, новый transaction удаляется, projectId остаётся previousProjectId, accepted пуст.
5. Перед publish все assets синхронизированы, metadata/order сериализованы и проверены. Публикация нового draft выполняется атомарным rename/replace под shared-store lock; ссылок на .partial, cache или чужой URI в опубликованном envelope нет. Ошибка/отмена до commit не изменяет previous project или его MP4.
6. При partial success публикуется только проверенный набор, UI показывает каждую ошибку. Cancellation — rollback, не per-file rejection с публикацией половины отменённой операции.

## Переход в HybridProject и изменённый выбор

После director готов настоящий graph/music и assets аудио, создаётся HybridProject с тем же projectId. `promoteSourceDraft` проверяет etag, наличие assets и соответствие source table, пишет revision/sidecars, затем атомарно меняет общий pointer. При сбое старый source draft остаётся восстановимым; после успеха источники, порядок и geometry переходят в общую схему. Не публиковать placeholder clip/music ради прохождения constructor validation.

Замена выбора и add/remove создают новый импортный draft/ID с собственными копиями kept assets. Исходники уже опубликованного/редактируемого проекта остаются. Полная таблица индексов фиксируется до первой graph publication; перестановка клипов не меняет sourceIndex. Append/remove после публикации применяются как согласованная операция общего ядра или новый проект, не неявное перенумерование существующих графов.

## Lease, очистка и миграция

- Lease принадлежит shared store и защищает source assets для analysis/preview/export в разных экземплярах/процессах. `deleteSources` при активном lease возвращает Busy, не удаляет используемые файлы. После удаления сохраняются metadata/tombstone и опубликованный MP4; повторное редактирование показывает missing sources явно.
- `clearTransient` удаляет только uncommitted imports/pending renders, не `projects/<id>/sources`. Сбои pointer/revision recovery не удаляют source draft с ещё не принятым результатом.
- Legacy migration получает устойчивый ключ старого draft. Shared store публикует copied source draft и durable marker; повторный вызов возвращает тот же ID. Для crash между publication/marker legacyMigrationKey envelope позволяет восстановить marker. Legacy originals сохраняются до успешного принятия нового draft вызывающим EditSession.
- Если migrated project явно удалён пользователем, marker не должен молча запускать повторный импорт старого draft. Missing result сообщает отличимую ошибку/статус. Параллельные migration/import/delete операции используют ту же lock/lease дисциплину владельца storage.

## Проверки границы

Проверяются реальные файлы/atomic publication и fault injection на внешних IO границах: неизвестный content URI size, потеря доступа в середине копии, disk budget, один malformed файл среди исправных, всё отвергнуто, cancellation, повторный hash под двумя URI, rotation/VFR/nonzero PTS/EOF, active lease/delete, restart до/после pointer replace, stable migration ID, сохранность предыдущих source assets и MP4. По transition в HybridProject проверяется CAS conflict и точное сохранение sourceIndex/PTS.

Тяжёлые Android прогоны выполняются только по очереди координатора на выделенном `.uitest`. Текущий код/метаданные допускают небольшие JVM проверки `--max-workers=1`; настоящая device inspection/экспорт и Samsung A25 не считаются пройденными по структурным тестам.
