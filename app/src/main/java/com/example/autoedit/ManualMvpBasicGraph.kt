package com.veycad.app

/** Caller input for the existing importer, visibly labelled as a basic test montage. */
internal object ManualMvpBasicGraph {
    fun create(sources: List<MediaSource>, musicAssetId: String): MontageGraph {
        require(sources.size in 1..2) { "Для базового тестового монтажа выберите одно или два видео" }
        val windows = sources.map { source ->
            require(source.isBaselineInput) { "Нужен видеоролик AVC со стандартным цветом: ${source.displayName}" }
            val firstUs = requireNotNull(source.firstVideoPtsUs) {
                "У видео не измерен первый кадр: ${source.displayName}"
            }
            val endUs = requireNotNull(source.videoEndPtsUs) {
                "У видео не измерен физический конец: ${source.displayName}"
            }
            val startMs = Math.addExact(firstUs, 999L) / 1_000L
            val lastMs = endUs / 1_000L
            val durationMs = minOf(2_000L, lastMs - startMs)
            require(durationMs >= 1_000L) { "Для ${source.displayName} нужен хотя бы 1 секунда измеренного видео" }
            startMs to durationMs
        }
        val order = listOf(0, sources.lastIndex, 0)
        val names = listOf("A1", "B", "A2")
        val clips = order.mapIndexed { index, sourceIndex ->
            val (startMs, durationMs) = windows[sourceIndex]
            MontageGraph.Clip(names[index], startMs, startMs + durationMs, durationMs,
                MontageGraph.ShotRole.ACTION,
                if (index == 0) MontageGraph.Transition.OPEN else MontageGraph.Transition.HARD_CUT,
                MontageGraph.Motion.HOLD, 1f, 0, sourceIndex = sourceIndex)
        }
        val flashAtMs = clips.first().outputDurationMs
        val flash = MontageGraph.Overlay("flash-B", flashAtMs, flashAtMs + 100L,
            "flash", opacity = .6f)
        return MontageGraph(windows.maxOf { it.first + it.second }, clips.sumOf { it.outputDurationMs },
            clips = clips, audioTrack = MontageGraph.AudioTrack(musicAssetId), overlays = listOf(flash),
            metadata = NleProjectMetadata(generator = "manual-basic-test"))
    }
}
