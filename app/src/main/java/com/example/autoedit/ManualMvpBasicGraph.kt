package com.veycad.app

/** Analysis-free basic montage for the existing importer and common editing commands. */
internal object ManualMvpBasicGraph {
    fun validateSources(sources: List<MediaSource>) { sourceWindows(sources) }

    fun create(sources: List<MediaSource>, musicAssetId: String): MontageGraph {
        val windows = sourceWindows(sources)
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

    private fun sourceWindows(sources: List<MediaSource>): List<Pair<Long, Long>> {
        require(sources.size in 1..2) { "Для базового монтажа выберите одно или два видео" }
        return sources.map { source ->
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
            require(startMs >= 0L && Math.addExact(startMs, durationMs) <= source.durationUs / 1_000L) {
                "Временная шкала этого видео пока не поддерживается MVP: ${source.displayName}"
            }
            startMs to durationMs
        }
    }
}
