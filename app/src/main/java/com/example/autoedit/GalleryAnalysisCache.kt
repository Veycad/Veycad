package com.veycad.app

data class GalleryAnalysisProfile(
    val analyzerVersion: Int = 2,
    val coarseIntervalUs: Long = 1_000_000L,
    val refineIntervalUs: Long = 250_000L
) {
    init { require(analyzerVersion > 0 && coarseIntervalUs > 0 && refineIntervalUs > 0) }
}

/** Regenerable, bounded measurements only. Source files and project lifetime belong to shared storage. */
class GalleryAnalysisCache(private val maxEntries: Int = 20, private val maxMomentsPerEntry: Int = 128) {
    private data class Key(val fingerprint: String, val profile: GalleryAnalysisProfile)
    private data class Entry(val durationUs: Long, val firstVideoPtsUs: Long, val moments: List<GalleryMoment>)
    private val entries = LinkedHashMap<Key, Entry>(16, .75f, true)
    init { require(maxEntries > 0 && maxMomentsPerEntry > 0) }

    fun load(source: MediaSource): List<GalleryMoment>? = load(source, GalleryAnalysisProfile())

    @Synchronized
    fun load(source: MediaSource, profile: GalleryAnalysisProfile): List<GalleryMoment>? {
        val entry = entries[Key(source.fingerprint, profile)] ?: return null
        if (entry.durationUs != source.durationUs || entry.firstVideoPtsUs != source.firstVideoPtsUs) return null
        // Selection identity is never part of content reuse and must be rebound on every load.
        return entry.moments.map { it.copy(sourceId = source.id) }
    }

    fun store(source: MediaSource, moments: List<GalleryMoment>) = store(source, moments, GalleryAnalysisProfile())

    @Synchronized
    fun store(source: MediaSource, moments: List<GalleryMoment>, profile: GalleryAnalysisProfile) {
        require(moments.size <= maxMomentsPerEntry)
        require(moments.all { moment ->
            moment.sourceId == source.id && moment.startUs >= source.firstVideoPtsUs && moment.endUs <= source.videoEndPtsUs &&
                moment.endUs - moment.startUs in 500_000L..4_000_000L &&
                moment.features.timeUs in moment.startUs until moment.endUs
        }) { "Gallery measurements must describe real, bounded source windows" }
        entries[Key(source.fingerprint, profile)] = Entry(source.durationUs, source.firstVideoPtsUs, moments.toList())
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
    }
}
