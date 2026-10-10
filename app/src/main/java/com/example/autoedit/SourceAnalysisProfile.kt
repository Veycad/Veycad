package com.veycad.app

/** Source capabilities, not montage styles or a claim that a measurement succeeded. */
internal enum class SourceAnalysisProfile(val cacheToken: String, val requestsCorrespondence: Boolean) {
    EDITORIAL_SEMANTICS("editorial-semantics-v1", false),
    EDITORIAL_WITH_CORRESPONDENCE("editorial-correspondence-v1", true);

    enum class CorrespondenceState { NOT_REQUESTED, ASSESSED }

    val correspondenceState: CorrespondenceState get() =
        if (requestsCorrespondence) CorrespondenceState.ASSESSED else CorrespondenceState.NOT_REQUESTED

    /** Avoid even preparing a correspondence plane when its capability is not requested. */
    inline fun <T : Any> correspondence(work: () -> T): T? =
        if (requestsCorrespondence) work() else null

    fun supplies(required: SourceAnalysisProfile): Boolean =
        !required.requestsCorrespondence || requestsCorrespondence

    fun validate(observations: List<VisualEventMap.Observation>, completedAssessments: Int) {
        require(observations.isNotEmpty())
        if (requestsCorrespondence) {
            // An assessment can return unknown; neither successful measurements nor legacy
            // vectors determine whether this full source capability was actually requested.
            require(completedAssessments == observations.size) {
                "Correspondence profile requires an assessment for every source observation"
            }
        } else {
            require(completedAssessments == 0) { "Editorial correspondence must be NOT_REQUESTED" }
            require(observations.all { it.motionMeasurement == null && it.cameraMeasurement == null }) {
                "Editorial analysis must not carry correspondence measurements"
            }
        }
    }

    companion object {
        /** Product requirements own this mapping; add capabilities before adding a new consumer. */
        fun requiredFor(recipe: MontageStyleCatalog.Recipe): SourceAnalysisProfile = when (recipe) {
            MontageStyleCatalog.Recipe.GALLERY_MONTAGE ->
                throw IllegalArgumentException("Gallery requires GallerySourceAnalyzer, not editorial semantic analysis")
            MontageStyleCatalog.Recipe.FEAR_STROBE -> EDITORIAL_WITH_CORRESPONDENCE
            MontageStyleCatalog.Recipe.SIGMA,
            MontageStyleCatalog.Recipe.HEARTBEAT,
            MontageStyleCatalog.Recipe.DUALITY_LOOP -> EDITORIAL_SEMANTICS
        }

        fun fromCacheToken(token: String): SourceAnalysisProfile =
            values().singleOrNull { it.cacheToken == token }
                ?: throw IllegalArgumentException("Unknown source analysis profile: $token")
    }
}
