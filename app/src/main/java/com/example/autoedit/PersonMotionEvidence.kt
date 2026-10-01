package com.example.autoedit

/** Bridges actual local pixel correspondence, person mask, and independently supported camera. */
internal object PersonMotionEvidence {
    data class Measurement(val subject: SubjectMotionIntensity.Measurement,
                           val camera: CameraMotionConsensus.Measurement)
    data class SupportCounts(val candidates: Int = 0, val textured: Int = 0, val fitted: Int = 0,
                             val uniqueMatch: Int = 0, val roundtrip: Int = 0, val reliable: Int = 0)

    enum class Stage {
        FIRST_FRAME, STALE_SEMANTICS, PTS_GAP, MASK_UNAVAILABLE, HUMAN_UNAVAILABLE,
        CORRESPONDENCE_UNAVAILABLE, CAMERA_UNSUPPORTED, SUBJECT_UNSUPPORTED,
        PERSON_COVERAGE, MEASURED
    }
    data class Assessment(
        val stage: Stage,
        val raw: Measurement? = null,
        val measurement: VisualEventMap.MotionMeasurement? = null,
        val cells: Int = 0,
        val uniqueCenters: Int = 0,
        val texturedCells: Int = 0,
        val fittedCells: Int = 0,
        val uniqueMatchCells: Int = 0,
        val roundtripCells: Int = 0,
        val reliableCells: Int = 0,
        val backgroundCells: Int = 0,
        val backgroundQuadrants: Int = 0,
        val personCells: Int = 0,
        val centerBackground: SupportCounts = SupportCounts(),
        val cleanQueryBackground: SupportCounts = SupportCounts(),
        val personSupport: SupportCounts = SupportCounts(),
        val mixedBackgroundPatches: Int = 0,
        val localPersonSupport: SupportCounts? = null,
        val localPersonGridCells: Int? = null,
        val localPersonWitnesses: Int? = null,
        /** Pixel-supported camera before the separate whole-body coverage gate. */
        val rawCamera: CameraMotionConsensus.Measurement? = null,
        /** Fresh semantics/actual interval validated independently of body correspondence. */
        val cameraMeasurement: VisualEventMap.CameraMeasurement? = null
    )

    /** A mask/human result must belong to this exact current frame, not a carried snapshot. */
    fun observe(previous: LumaMotionEstimator.Plane?, current: LumaMotionEstimator.Plane,
                previousTimeUs: Long?, timeUs: Long, semanticTimeUs: Long?,
                mask: FrameAttachments.Plane?, humanConfidence: Float): VisualEventMap.MotionMeasurement? =
        assess(previous, current, previousTimeUs, timeUs, semanticTimeUs, mask, humanConfidence).measurement

    fun assess(previous: LumaMotionEstimator.Plane?, current: LumaMotionEstimator.Plane,
               previousTimeUs: Long?, timeUs: Long, semanticTimeUs: Long?,
               mask: FrameAttachments.Plane?, humanConfidence: Float): Assessment {
        if (previousTimeUs == null) return Assessment(Stage.FIRST_FRAME)
        if (semanticTimeUs != timeUs) return Assessment(Stage.STALE_SEMANTICS)
        val intervalUs = timeUs - previousTimeUs
        if (previousTimeUs < 0L || intervalUs !in 100_000L..500_000L) return Assessment(Stage.PTS_GAP)
        val pixels = assessPixels(previous, current, mask, humanConfidence)
        val cameraMeasurement = pixels.rawCamera?.let { camera ->
            VisualEventMap.CameraMeasurement(camera.x, camera.y, camera.confidence,
                camera.agreeingCells, camera.coveredQuadrants, previousTimeUs, timeUs,
                timeUs, intervalUs)
        }
        val audit = pixels.copy(cameraMeasurement = cameraMeasurement)
        val result = audit.raw ?: return audit
        // One lucky patch or a tiny fraction of the person cannot certify whole-subject motion.
        if (result.subject.supportedCells < 4 || result.subject.supportedPersonFraction < .25f)
            return audit.copy(stage = Stage.PERSON_COVERAGE)
        return audit.copy(stage = Stage.MEASURED, measurement = VisualEventMap.MotionMeasurement(
            result.subject.intensity, result.camera.x, result.camera.y,
            result.camera.confidence, result.subject.supportedCells, result.subject.supportedPersonFraction,
            result.camera.agreeingCells, result.camera.coveredQuadrants, intervalUs))
    }

    fun measure(previous: LumaMotionEstimator.Plane?, current: LumaMotionEstimator.Plane,
                mask: FrameAttachments.Plane?, humanConfidence: Float): Measurement? =
        assessPixels(previous, current, mask, humanConfidence).raw

    private fun assessPixels(previous: LumaMotionEstimator.Plane?, current: LumaMotionEstimator.Plane,
                             mask: FrameAttachments.Plane?, humanConfidence: Float): Assessment {
        require(humanConfidence in 0f..1f)
        if (mask == null || mask.confidence < .8f) return Assessment(Stage.MASK_UNAVAILABLE)
        if (humanConfidence < .55f) return Assessment(Stage.HUMAN_UNAVAILABLE)
        val prepared = LocalMotionCorrespondence.prepare(previous, current)
            ?: return Assessment(Stage.CORRESPONDENCE_UNAVAILABLE)
        val cells = LocalMotionCorrespondence.estimate(prepared,
            sampling = MotionAnalysisGeometry.sampling(current))
            ?: return Assessment(Stage.CORRESPONDENCE_UNAVAILABLE)
        val person = cells.associateWith {
            PersonMaskProjection.sample(mask, current.width, current.height, it.centerX, it.centerY)
        }
        val footprint = cells.associateWith {
            PersonMaskProjection.patchMaximum(mask, current.width, current.height, it.centerX, it.centerY,
                it.patchRadiusX, it.patchRadiusY)
        }
        fun counts(group: List<LocalMotionCorrespondence.Cell>) = SupportCounts(group.size,
            group.count { it.textured }, group.count { it.fit >= .5f }, group.count { it.uniqueness >= .5f },
            group.count { it.roundtrip }, group.count { it.confidence >= .5f })
        val centerBackground = cells.filter { person.getValue(it) <= .15f }
        val cleanBackground = centerBackground.filter { footprint.getValue(it) <= .15f }
        val background = cleanBackground.filter { it.confidence >= .5f }
        val personCandidates = cells.filter { person.getValue(it) >= .5f }
        val audit = Assessment(Stage.CAMERA_UNSUPPORTED, cells = cells.size,
            uniqueCenters = cells.map { it.centerX to it.centerY }.toSet().size,
            texturedCells = cells.count { it.textured }, fittedCells = cells.count { it.fit >= .5f },
            uniqueMatchCells = cells.count { it.uniqueness >= .5f }, roundtripCells = cells.count { it.roundtrip },
            reliableCells = cells.count { it.confidence >= .5f }, backgroundCells = background.size,
            backgroundQuadrants = background.map { (if ((it.centerX + .5f) / current.width >= .5f) 1 else 0) +
                (if ((it.centerY + .5f) / current.height >= .5f) 2 else 0) }.toSet().size,
            personCells = personCandidates.count { it.confidence >= .5f },
            centerBackground = counts(centerBackground), cleanQueryBackground = counts(cleanBackground),
            personSupport = counts(personCandidates),
            mixedBackgroundPatches = centerBackground.size - cleanBackground.size)
        val camera = CameraMotionConsensus.measure(cells.map {
            CameraMotionConsensus.Cell((it.centerX + .5f) / current.width, (it.centerY + .5f) / current.height,
                it.x, it.y, it.confidence, footprint.getValue(it))
        }) ?: return audit
        val cameraAudit = audit.copy(rawCamera = camera)
        val local = PersonLocalCorrespondence.estimate(prepared, mask, camera.x, camera.y)
            ?: return cameraAudit.copy(stage = Stage.SUBJECT_UNSUPPORTED)
        val localAudit = cameraAudit.copy(localPersonSupport = local.support,
            localPersonGridCells = local.gridCells, localPersonWitnesses = local.witnesses)
        val subject = SubjectMotionIntensity.measure(local.cells,
            camera.x, camera.y, camera.confidence, humanConfidence)
            ?: return localAudit.copy(stage = Stage.SUBJECT_UNSUPPORTED)
        return localAudit.copy(stage = Stage.MEASURED,
            raw = Measurement(subject.copy(supportedCells = local.witnesses), camera))
    }
}
