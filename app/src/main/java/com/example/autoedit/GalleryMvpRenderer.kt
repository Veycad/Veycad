package com.veycad.app

/** Neutral gallery adapter. Basic decoded MP4 evidence is separate from artistic acceptance. */
internal object GalleryMvpRenderer {
    fun render(request: VeycadAutomaticEditor.Request): EditSession.RenderSummary {
        check(BuildConfig.GALLERY_MVP && request.recipe == MontageStyleCatalog.Recipe.GALLERY_MONTAGE)
        val checkCancelled = request.checkCancelled
        checkCancelled()
        GalleryImportPolicy.validate(request.sources)
        require(request.sources.items.all { it.isBaselineInput }) { "Выберите MP4 H.264 без HDR" }
        request.onProgress(VeycadAutomaticEditor.Progress(VeycadAutomaticEditor.Stage.ANALYSING_VIDEO, 0, 1))
        val analyzer = GallerySourceAnalyzer()
        val moments = request.sources.items.flatMap { source -> analyzer.analyze(source, checkCancelled) }
        val pcm = MediaCodecAudioDecoder.decode(request.musicFile, maxDecodeUs = 30_000_000L,
            checkCancelled = checkCancelled)
        val beats = AudioBeatMapAnalyzer.analyze(pcm.mono(), pcm.sampleRate)
        val targetSamples = 30L * beats.sampleRate
        val audioMap = if (beats.durationSamples < targetSamples) beats.loopedTo(targetSamples) else beats
        checkCancelled()
        request.onProgress(VeycadAutomaticEditor.Progress(VeycadAutomaticEditor.Stage.DIRECTING, 0, 1))
        val graph = GalleryMontageDirector.direct(request.sources, moments, audioMap, 30_000L)
            .copy(audioTrack = MontageGraph.AudioTrack(request.musicFile.path))
        val plan = RenderPassPlanner.plan(graph, request.capabilities)
        val framePlan = HighQualityFramePlan.build(graph)
        request.onProgress(VeycadAutomaticEditor.Progress(VeycadAutomaticEditor.Stage.RENDERING, 0, 1))
        val frames = MediaCodecSpeedRampRenderer.render(MediaCodecSpeedRampRenderer.Request(
            sourceFiles = request.sources.items.map { it.file }, graph = graph, outputFile = request.outputFile,
            width = request.width, height = request.height, bitrate = request.bitrate,
            audioFile = request.musicFile, context = request.context, renderPlan = plan,
            checkCancelled = checkCancelled))
        checkCancelled()
        request.onProgress(VeycadAutomaticEditor.Progress(VeycadAutomaticEditor.Stage.VERIFYING, 0, 1))
        val container = VeykadRenderInspector.inspectContainer(request.outputFile, request.width, request.height)
        check(container.issues.isEmpty()) { "Не удалось проверить готовое видео. Предыдущий монтаж сохранён" }
        check(frames == framePlan.frames.size && kotlin.math.abs(container.videoLastPtsUs -
            framePlan.frames.last().outputTimeUs) <= 50_000L) { "Готовое видео имеет неверную длительность" }
        // Existing sequential frame reader verifies actual decoding across every authored clip.
        val targets = framePlan.frames.groupBy { it.clipIndex }.values.map { it[it.size / 2].outputTimeUs }
            .distinct().sorted().toLongArray()
        var sampled = 0
        SequentialBitmapDecoder.decodeActual(request.outputFile, targets, 64, 64,
            checkCancelled = checkCancelled) { _, _, bitmap ->
                check(bitmap.width > 0 && bitmap.height > 0); sampled++
            }
        check(sampled == targets.size) { "Не удалось открыть кадры готового видео" }
        checkCancelled()
        request.onProgress(VeycadAutomaticEditor.Progress(VeycadAutomaticEditor.Stage.COMPLETE, 1, 1))
        val projectId = CustomMusicProjectHandoff.publish(request.context, graph, request.sources, request.musicFile,
            ProjectExportSettings(request.width, request.height, framePlan.fps, request.bitrate, 192_000),
            checkCancelled, request.recipe, request.captureOrigin)
        return EditSession.RenderSummary(graph.clips.size, 0f, false,
            listOf("Художественное качество ещё требует вашей оценки"),
            productSummary = "Ролик из галереи · ${request.sources.items.size} видео · ${graph.clips.size} фрагментов · " +
                "музыка · базовая проверка видео пройдена. Оцените монтаж при просмотре.",
            projectId = projectId, ownsNewProject = true)
    }
}
