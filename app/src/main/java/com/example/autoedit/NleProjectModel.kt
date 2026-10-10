package com.veycad.app

/** Revision metadata makes every render reproducible and gives drafts a migration boundary. */
data class NleProjectMetadata(
    val schemaVersion: Int = MontageGraph.CURRENT_VERSION,
    val revision: Long = 1L,
    val parentRevision: Long? = null,
    val generator: String = "veycad-engine"
) {
    init {
        require(schemaVersion in 1..MontageGraph.CURRENT_VERSION)
        require(revision > 0L && (parentRevision == null || parentRevision in 1 until revision))
        require(generator.isNotBlank())
    }
}

/** Explicit migrations replace scattered renderer fallbacks when the NLE schema changes. */
object MontageGraphMigration {
    fun toCurrent(graph: MontageGraph): MontageGraph = when (graph.version) {
        MontageGraph.CURRENT_VERSION -> graph
        1 -> graph.copy(
            version = MontageGraph.CURRENT_VERSION,
            metadata = NleProjectMetadata(
                schemaVersion = MontageGraph.CURRENT_VERSION,
                revision = graph.metadata.revision + 1L,
                parentRevision = graph.metadata.revision,
                generator = "veycad-v1-migration"
            ),
            parameterTracks = if (graph.parameterTracks.isEmpty()) ParameterTrackFactory.fromLegacy(graph) else graph.parameterTracks
        )
        else -> error("Unsupported MontageGraph schema ${graph.version}")
    }
}

object ParameterTrackFactory {
    fun fromLegacy(graph: MontageGraph): List<ParameterTrack> {
        var timelineStartUs = 0L
        return buildList {
            graph.clips.forEach { clip ->
                val durationUs = clip.outputDurationMs * 1_000L
                fun transformTrack(name: String, value: (MontageGraph.ClipTransform.Keyframe) -> Float) {
                    add(ParameterTrack(
                        id = "${clip.id}-$name",
                        target = ParameterTargets.clip(clip.id, "transform.$name"),
                        type = ParameterTrack.ValueType.SCALAR,
                        keyframes = clip.transform.keyframes.map { keyframe ->
                            ParameterTrack.Keyframe(
                                timelineStartUs + (durationUs * keyframe.at).toLong(), listOf(value(keyframe)),
                                ParameterTrack.Interpolation.CUBIC
                            )
                        }
                    ))
                }
                transformTrack("scale") { it.scale }
                transformTrack("translateX") { it.translateX }
                transformTrack("translateY") { it.translateY }
                transformTrack("rotationDegrees") { it.rotationDegrees }
                add(ParameterTrack(
                    id = "${clip.id}-speed",
                    target = ParameterTargets.clip(clip.id, "speed"),
                    type = ParameterTrack.ValueType.SCALAR,
                    keyframes = clip.speedRamp.keyframes.map { keyframe ->
                        ParameterTrack.Keyframe(
                            timelineStartUs + (durationUs * keyframe.at).toLong(), listOf(keyframe.speed),
                            ParameterTrack.Interpolation.CUBIC, keyframe.curveToNext
                        )
                    }
                ))
                add(ParameterTrack(
                    id = "${clip.id}-grade",
                    target = ParameterTargets.clip(clip.id, "grade.rgbaBias"),
                    type = ParameterTrack.ValueType.VEC4,
                    keyframes = listOf(ParameterTrack.Keyframe(
                        timelineStartUs,
                        listOf(clip.redBias, clip.greenBias, clip.blueBias, clip.exposureBias),
                        ParameterTrack.Interpolation.HOLD
                    ))
                ))
                timelineStartUs += durationUs
            }
            graph.audioTrack?.let { audio ->
                add(ParameterTrack(
                    "master-audio-gain", ParameterTargets.MASTER_AUDIO_GAIN, ParameterTrack.ValueType.SCALAR,
                    listOf(ParameterTrack.Keyframe(0L, listOf(audio.gain), ParameterTrack.Interpolation.HOLD))
                ))
            }
        }
    }
}
