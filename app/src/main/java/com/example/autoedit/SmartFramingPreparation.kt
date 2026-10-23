package com.veycad.app

import android.content.Context
import java.util.Collections

/** Caller owns the media lease and worker; prepare before READY, with no active GL/audio consumer. */
internal object SmartFramingPreparation {
    fun prepare(context: Context, project: HybridProject, assets: ProjectAssetStore,
        checkCancelled: () -> Unit = {}): Map<SourceId, SmartFramingTrack> {
        val selections = requireNotNull(project.selectedVideos) { "Selected source order is unknown" }
        val visual = project.current.visualSettings
        val physical = project.assets.associateBy { it.id }
        val tracks = linkedMapOf<String, SmartFramingTrack>()
        val selected = linkedMapOf<SourceId, SmartFramingTrack>()
        for (selection in selections) {
            checkCancelled()
            val mode = visual.framings[FramingKey(selection.id, visual.aspect)]?.mode ?: FramingMode.MANUAL
            if (mode != FramingMode.SMART_PERSON) continue
            val asset = physical.getValue(selection.assetId)
            val metadata = requireNotNull(asset.videoMetadata)
            val first = requireNotNull(metadata.firstVideoPtsUs) { "Для автокадрирования нужен проверенный первый PTS исходника" }
            val end = requireNotNull(metadata.videoEndPtsUs) { "Для автокадрирования нужна проверенная граница PTS исходника" }
            require(end > first) { "Нет доступного диапазона кадров для автокадрирования" }
            // Full source analysis is continuous. Editing/cuts sample this same source-PTS value;
            // neither a seek, a format switch nor an output-FPS change alters the tracking path.
            val key = SmartFramingKey(asset.contentHash, asset.durationUs, first, end,
                listOf(VisualEventMap.UsableWindow(first, end)))
            val track = tracks.getOrPut(key.fileName) {
                MediaFrameAnalysisCache.loadSmart(context, key) ?: run {
                    val file = assets.resolve(asset)
                    require(file.isFile && file.length() == metadata.sizeBytes) { "Исходник изменился" }
                    val analysis = SmartFramingSourceAnalysis.analyze(context, file, key, checkCancelled)
                    checkCancelled()
                    val prepared = SmartFramingTrack.build(analysis, key.windows)
                    checkCancelled()
                    MediaFrameAnalysisCache.storeSmart(context, key, prepared)
                    prepared
                }
            }
            selected[selection.id] = track
        }
        checkCancelled()
        return Collections.unmodifiableMap(selected)
    }
}
