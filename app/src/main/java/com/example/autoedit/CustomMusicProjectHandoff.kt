package com.veycad.app

import android.content.Context
import android.media.MediaMetadataRetriever
import java.util.UUID

/** Actual automatic winner; common stores own all sources, revisions and history. */
internal object CustomMusicProjectHandoff {
    fun publish(context: Context, graph: MontageGraph, sources: MediaSourceSet, musicFile: java.io.File,
        settings: ProjectExportSettings, checkCancelled: () -> Unit = {},
        recipe: MontageStyleCatalog.Recipe = MontageStyleCatalog.Recipe.CUSTOM_MUSIC,
        captureOrigin: CaptureOrigin? = null): String {
        val store = HybridProjectStore(context.filesDir)
        val id = "${if (recipe == MontageStyleCatalog.Recipe.CUSTOM_MUSIC) "music" else "edit"}-${UUID.randomUUID()}"
        val directory = store.directory(id)
        val assets = ProjectAssetStore(directory)
        try {
            val inspector = MediaInputInspector(checkCancelled)
            val inspected = sources.items.map { source ->
                checkCancelled()
                inspector.inspectForProject(source.id, source.file, source.displayName).also {
                    require(it.first.fingerprint == source.fingerprint) { "Исходник изменился во время монтажа" }
                }
            }
            val videos = inspected.map { (source, metadata) ->
                checkCancelled()
                assets.importVideo(source, metadata.geometry.pixelAspectRatio)
            }
            val selections = inspected.mapIndexed { index, (source, _) ->
                SelectedVideo.fromInspected(source, videos[index],
                    if (captureOrigin == null) SourceOwnership.IMPORTED else SourceOwnership.CAPTURE, captureOrigin)
            }
            checkCancelled()
            val retriever = MediaMetadataRetriever()
            val durationUs = try {
                retriever.setDataSource(musicFile.path)
                Math.multiplyExact(requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))
                    .toLong(), 1_000L)
            } finally { retriever.release() }
            val title = CustomMusicStore(context).restore()?.takeIf { it.file.canonicalFile == musicFile.canonicalFile }?.title
                ?: musicFile.name
            val music = assets.import(musicFile, ProjectAsset.Kind.AUDIO, durationUs).copy(displayName = title)
            val track = requireNotNull(graph.audioTrack) { "В автосборке отсутствует музыка" }
            require(track.sourceStartUs < durationUs)
            val owned = graph.copy(audioTrack = track.copy(sourceId = music.id))
            val link = captureOrigin?.let { CompletedRenderStore.CaptureLink(it.sessionId, it.takeOrdinal, it.recommended) }
            val imported = EditableMontageImporter.create(id, owned, recipe,
                settings, videos, music, link, selections).shared
            val aspect = ProjectAspect.entries.firstOrNull { aspect ->
                val size = aspect.size(360)
                size.width.toLong() * settings.height == size.height.toLong() * settings.width
            } ?: throw IllegalArgumentException("Неподдерживаемый формат проекта")
            val original = imported.original.copy(visualSettings = ProjectVisualSettings(aspect, false))
            val project = imported.copy(original = original, current = original)
            checkCancelled()
            store.create(project)
            return id
        } catch (error: Throwable) {
            // Only this import owns this new UUID directory.
            directory.deleteRecursively()
            throw error
        }
    }
}
