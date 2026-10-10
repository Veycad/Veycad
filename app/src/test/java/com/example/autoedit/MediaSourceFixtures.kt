package com.veycad.app

import java.io.File

internal fun sourceSet(count: Int, durationUs: Long = 1_000_000L): MediaSourceSet =
    MediaSourceSet((0 until count).map { index -> mediaSource("source-$index", durationUs) })

internal fun mediaSource(id: String = "source", durationUs: Long = 1_000_000L,
    firstVideoPtsUs: Long = 0L, endPtsUs: Long = firstVideoPtsUs + durationUs): MediaSource =
    MediaSource(id, File("$id.mp4"), "$id.mp4", durationUs, 1_024L, 0, 160, 240,
        "video/avc", null, false, "a".repeat(64), firstVideoPtsUs,
        VideoPresentationBounds(firstVideoPtsUs, endPtsUs))
