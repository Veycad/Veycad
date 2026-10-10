package com.veycad.app

/** Old physical formats contain no selected-backing table. Keep their conservative guards covered. */
internal fun HybridProject.withoutSourceBackings(): HybridProject = copy(
    original = original.copy(sourceBackings = emptyMap()), current = current.copy(sourceBackings = emptyMap()),
    undo = undo.map { it.copy(sourceBackings = emptyMap()) }, redo = redo.map { it.copy(sourceBackings = emptyMap()) })

/** Assemble legacy history without running the new command's migration of selected curves. */
internal fun legacyCommit(project: HybridProject, candidate: HybridRevision): HybridProject = project.copy(
    current = candidate.copy(id = project.nextRevisionId, parentId = project.current.id, sourceBackings = emptyMap()),
    nextRevisionId = project.nextRevisionId + 1, undo = (project.undo + project.current).takeLast(50), redo = emptyList())
